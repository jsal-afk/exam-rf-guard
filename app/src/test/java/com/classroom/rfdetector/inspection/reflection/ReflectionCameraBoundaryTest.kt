package com.classroom.rfdetector.inspection.reflection

import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReflectionCameraBoundaryTest {
    private class Backend : ReflectionBackend {
        val requests = mutableListOf<(Result<ReflectionProvider>) -> Unit>()
        val posts = mutableListOf<() -> Unit>()
        override fun request(callback: (Result<ReflectionProvider>) -> Unit) { requests.add(callback) }
        override fun post(action: () -> Unit) { posts.add(action) }
    }
    private class Binding(override val hasTorch: Boolean = true) : ReflectionBinding {
        var closed = 0
        val requests = mutableListOf<Pair<Boolean, (Boolean) -> Unit>>()
        override fun torch(enabled: Boolean, completion: (Boolean) -> Unit) { requests.add(enabled to completion) }
        override fun close() { closed++ }
    }
    private class Provider(val device: Binding = Binding(), var rear: Boolean = true, var fail: Boolean = false) : ReflectionProvider {
        var binds = 0
        lateinit var boxes: (List<RectF>) -> Unit
        lateinit var error: (String) -> Unit
        override fun hasRearCamera() = rear
        override fun bind(onBoxes: (List<RectF>) -> Unit, onError: (String) -> Unit): ReflectionBinding {
            binds++; boxes = onBoxes; error = onError
            if (fail) throw IllegalStateException("camera busy")
            return device
        }
    }
    private fun camera(backend: Backend): ReflectionCamera {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return ReflectionCamera(PreviewView(activity), activity, backend)
    }

    @Test fun pendingProviderAndPostedBindCannotResurrectAfterStopAndRestart() {
        val backend = Backend(); val controller = camera(backend)
        val first = Provider(); val second = Provider(); val third = Provider()
        controller.start(); controller.stop(); controller.start()
        backend.requests[0](Result.success(first))
        assertTrue(backend.posts.isEmpty())
        backend.requests[1](Result.success(second))
        controller.stop(); controller.start()
        backend.posts[0]()
        assertEquals(0, second.binds)
        backend.requests[2](Result.success(third)); backend.posts[1]()
        assertEquals(1, third.binds); assertTrue(controller.running)
        third.boxes(listOf(RectF(1f, 2f, 3f, 4f)))
        assertEquals(1, controller.boxes.size)
        controller.stop()
        assertEquals(1, third.device.closed); assertTrue(controller.boxes.isEmpty())
        third.boxes(listOf(RectF(2f, 2f, 4f, 4f)))
        third.error("late error")
        assertFalse(controller.running); assertTrue(controller.boxes.isEmpty())
        assertFalse(controller.status.contains("late"))
    }

    @Test fun missingCameraAndBindFailureReleaseSessionAndOfferRetry() {
        val backend = Backend(); val controller = camera(backend)
        controller.start(); backend.requests[0](Result.success(Provider(rear = false)))
        assertFalse(controller.running); assertTrue(controller.status.contains("후면")); assertTrue(backend.posts.isEmpty())
        controller.start(); val busy = Provider(fail = true)
        backend.requests[1](Result.success(busy)); backend.posts[0]()
        assertFalse(controller.running); assertTrue(controller.status.contains("다른 앱"))
        controller.start(); backend.requests[2](Result.failure(IllegalStateException()))
        assertFalse(controller.running); assertTrue(controller.status.contains("사용할 수 없습니다"))
    }

    @Test fun torchFailureRetryPendingAndStopCannotRestoreOldState() {
        val backend = Backend(); val controller = camera(backend); val provider = Provider()
        controller.start(); backend.requests[0](Result.success(provider)); backend.posts[0]()
        controller.toggleTorch(); controller.toggleTorch()
        assertEquals(1, provider.device.requests.size); assertTrue(controller.torchPending)
        provider.device.requests[0].second(false)
        assertFalse(controller.torchPending); assertFalse(controller.torchOn); assertTrue(controller.status.contains("불빛"))
        controller.toggleTorch(); provider.device.requests[1].second(true)
        assertTrue(controller.torchOn); assertFalse(controller.status.contains("불빛"))
        controller.toggleTorch(); controller.stop()
        provider.device.requests[2].second(true)
        assertFalse(controller.running); assertFalse(controller.torchOn); assertFalse(controller.torchPending)
        assertEquals(1, provider.device.closed)
    }

    @Test fun missingTorchNeverIssuesRequestAndCameraErrorClosesBinding() {
        val backend = Backend(); val controller = camera(backend); val provider = Provider(Binding(false))
        controller.start(); backend.requests[0](Result.success(provider)); backend.posts[0]()
        assertFalse(controller.hasTorch); controller.toggleTorch(); assertTrue(provider.device.requests.isEmpty())
        provider.error("camera failed")
        assertEquals(1, provider.device.closed); assertFalse(controller.running); assertEquals("camera failed", controller.status)
    }

    @Test fun imageProxyClosesOnSuccessInvalidCropAndProcessingException() {
        for (mode in 0..2) {
            var closed = 0; var delivered = 0; var errors = 0
            val plane = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ ->
                when (method.name) { "getRowStride" -> 20; "getPixelStride" -> 1; "getBuffer" -> ByteBuffer.allocate(400); else -> null }
            } as ImageProxy.PlaneProxy
            val info = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
                when (method.name) { "getTimestamp" -> 100_000_000L; "getRotationDegrees" -> 0; else -> null }
            } as ImageInfo
            val image = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
                when (method.name) {
                    "close" -> { closed++; null }
                    "getWidth", "getHeight" -> 20
                    "getCropRect" -> if (mode == 1) Rect(0, 0, 21, 20) else Rect(0, 0, 20, 20)
                    "getPlanes" -> if (mode == 2) throw IllegalStateException("bad plane") else arrayOf(plane)
                    "getImageInfo" -> info
                    else -> null
                }
            } as ImageProxy
            processReflectionImage(image, ReflectionAnalyzer(), { _, _, _ -> delivered++ }, { errors++ })
            assertEquals(1, closed)
            assertEquals(if (mode == 2) 0 else 1, delivered)
            assertEquals(if (mode == 2) 1 else 0, errors)
        }
    }
}
