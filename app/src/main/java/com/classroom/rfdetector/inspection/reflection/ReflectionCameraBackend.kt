package com.classroom.rfdetector.inspection.reflection

import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

internal interface ReflectionBackend {
    fun request(callback: (Result<ReflectionProvider>) -> Unit)
    fun post(action: () -> Unit)
}

internal interface ReflectionProvider {
    fun hasRearCamera(): Boolean
    fun bind(onBoxes: (List<RectF>) -> Unit, onError: (String) -> Unit): ReflectionBinding
}

internal interface ReflectionBinding {
    val hasTorch: Boolean
    fun torch(enabled: Boolean, completion: (Boolean) -> Unit)
    fun close()
}

/** The proxy is closed even if reading planes, processing, or delivery throws. */
internal fun processReflectionImage(image: ImageProxy, detector: ReflectionAnalyzer, deliver: (List<RectF>, Rect, Int) -> Unit, onError: () -> Unit) {
    try {
        val plane = image.planes[0]
        val found = detector.analyze(LumaFrame(image.width, image.height, Rect(image.cropRect), plane.rowStride, plane.pixelStride, plane.buffer, image.imageInfo.timestamp))
        deliver(found, Rect(image.cropRect), image.imageInfo.rotationDegrees)
    } catch (_: Exception) {
        onError()
    } finally {
        image.close()
    }
}

internal class CameraXReflectionBackend(private val view: PreviewView, private val owner: LifecycleOwner) : ReflectionBackend {
    override fun post(action: () -> Unit) { view.post(action) }
    override fun request(callback: (Result<ReflectionProvider>) -> Unit) {
        val future = ProcessCameraProvider.getInstance(view.context)
        future.addListener({
            try { callback(Result.success(CameraXProvider(future.get()))) }
            catch (error: Exception) { callback(Result.failure(error)) }
        }, ContextCompat.getMainExecutor(view.context))
    }
    private inner class CameraXProvider(private val provider: ProcessCameraProvider) : ReflectionProvider {
        override fun hasRearCamera() = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        override fun bind(onBoxes: (List<RectF>) -> Unit, onError: (String) -> Unit): ReflectionBinding {
            val viewport = view.viewPort ?: throw IllegalStateException("preview not laid out")
            val rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
            val width = view.width; val height = view.height
            val preview = Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setTargetRotation(rotation).setTargetResolution(android.util.Size(640, 480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val worker = Executors.newSingleThreadExecutor()
            val main = Handler(Looper.getMainLooper())
            val detector = ReflectionAnalyzer()
            analysis.setAnalyzer(worker) { image ->
                processReflectionImage(image, detector, { found, crop, degrees ->
                    main.post {
                        if ((view.display?.rotation ?: android.view.Surface.ROTATION_0) != rotation || view.width != width || view.height != height) {
                            onError("화면 방향이 바뀌었습니다. 카메라를 다시 시작해 주세요")
                        } else if (width > 0 && height > 0) {
                            val matrix = previewMatrix(crop, degrees, width, height)
                            onBoxes(found.map { RectF(it).apply { matrix.mapRect(this) } })
                        }
                    }
                }, { main.post { onError("카메라 영상을 처리할 수 없습니다") } })
            }
            try {
                val group = UseCaseGroup.Builder().setViewPort(viewport).addUseCase(preview).addUseCase(analysis).build()
                val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                return object : ReflectionBinding {
                    override val hasTorch = camera.cameraInfo.hasFlashUnit()
                    private val observer = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { state ->
                        if (state.error != null) onError("카메라를 사용할 수 없습니다. 다른 앱을 닫고 다시 시도해 주세요")
                    }
                    init { camera.cameraInfo.cameraState.observe(owner, observer) }
                    override fun torch(enabled: Boolean, completion: (Boolean) -> Unit) {
                        try {
                            val future = camera.cameraControl.enableTorch(enabled)
                            future.addListener({ completion(try { future.get(); true } catch (_: Exception) { false }) }, ContextCompat.getMainExecutor(view.context))
                        } catch (_: Exception) { completion(false) }
                    }
                    override fun close() {
                        try { camera.cameraInfo.cameraState.removeObserver(observer) } finally {
                            try { camera.cameraControl.enableTorch(false) } catch (_: Exception) { }
                            try { analysis.clearAnalyzer(); provider.unbind(preview, analysis) } finally { worker.shutdown() }
                        }
                    }
                }
            } catch (error: Exception) {
                try { analysis.clearAnalyzer(); provider.unbind(preview, analysis) } finally { worker.shutdown() }
                throw error
            }
        }
    }
}
