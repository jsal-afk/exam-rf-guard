package com.classroom.rfdetector.inspection.reflection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.classroom.rfdetector.ui.RfColors

/** CameraX's documented crop-vertex mapping, for a shared Preview/Analysis ViewPort. */
internal fun previewMatrix(crop: Rect, rotation: Int, width: Int, height: Int): Matrix {
    val source = floatArrayOf(crop.left.toFloat(), crop.top.toFloat(), crop.right.toFloat(), crop.top.toFloat(), crop.right.toFloat(), crop.bottom.toFloat(), crop.left.toFloat(), crop.bottom.toFloat())
    val corners = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
    val destination = FloatArray(8) { corners[(it + rotation / 90 * 2) % 8] }
    return Matrix().apply { setPolyToPoly(source, 0, destination, 0, 4) }
}

internal class ReflectionCamera(private val view: PreviewView, private val owner: LifecycleOwner, private val backend: ReflectionBackend = CameraXReflectionBackend(view, owner)) {
    var running by mutableStateOf(false); private set
    var status by mutableStateOf("카메라를 시작해 주세요"); private set
    var hasTorch by mutableStateOf(false); private set
    var torchOn by mutableStateOf(false); private set
    var torchPending by mutableStateOf(false); private set
    var boxes by mutableStateOf<List<RectF>>(emptyList()); private set
    private var generation = 0
    private var binding: ReflectionBinding? = null
    private val torchError = "휴대폰 불빛을 켜거나 끌 수 없습니다"
    private val ready = "빛 반사 위치를 살펴보세요"

    fun permissionDenied() { stop(); status = "카메라 권한이 필요합니다. 설정에서 권한을 허용해 주세요" }
    fun start() {
        if (running || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        running = true; status = "카메라 준비 중"; boxes = emptyList()
        val token = ++generation
        fun current() = token == generation && running
        try {
            backend.request { result ->
                if (current()) {
                    try {
                        val provider = result.getOrThrow()
                        if (!provider.hasRearCamera()) {
                            fail("후면 카메라를 사용할 수 없습니다")
                        } else {
                            backend.post {
                                if (current()) {
                                    try {
                                        val bound = provider.bind(
                                            { found -> if (current()) boxes = found },
                                            { error -> if (current()) fail(error) }
                                        )
                                        if (current()) {
                                            binding = bound; hasTorch = bound.hasTorch; status = ready
                                        } else bound.close()
                                    } catch (_: Exception) {
                                        if (current()) fail("카메라를 사용할 수 없습니다. 다른 앱을 닫고 다시 시도해 주세요")
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) { if (current()) fail("카메라를 사용할 수 없습니다") }
                }
            }
        } catch (_: Exception) { if (current()) fail("카메라를 사용할 수 없습니다") }
    }
    fun toggleTorch() {
        val active = binding ?: return
        if (!hasTorch || torchPending) return
        val desired = !torchOn; val token = generation
        torchPending = true
        active.torch(desired) { success ->
            if (token == generation && running) {
                torchPending = false
                if (success) {
                    torchOn = desired
                    if (status == torchError) status = ready
                } else status = torchError
            }
        }
    }
    private fun fail(message: String) { stop(); status = message }
    fun stop() {
        generation++
        binding?.close(); binding = null
        running = false; hasTorch = false; torchOn = false; torchPending = false; boxes = emptyList()
        status = "카메라를 시작해 주세요"
    }
}

@Composable
fun ReflectionInspectionScreen() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val controller = remember(preview, owner) { ReflectionCamera(preview, owner) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.start() else controller.permissionDenied()
    }
    ReflectionLifecycle(controller, owner)
    ReflectionContent(controller.running, controller.status, controller.hasTorch, controller.torchOn, controller.boxes,
        preview = { AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize()) },
        start = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) controller.start()
            else permission.launch(Manifest.permission.CAMERA)
        }, stop = controller::stop, torch = controller::toggleTorch, torchPending = controller.torchPending)
}

@Composable
internal fun ReflectionLifecycle(controller: ReflectionCamera, owner: LifecycleOwner) {
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.stop() }
    }
}

@Composable
internal fun ReflectionContent(running: Boolean, status: String, hasTorch: Boolean, torchOn: Boolean, boxes: List<RectF>, preview: @Composable () -> Unit, start: () -> Unit, stop: () -> Unit, torch: () -> Unit, torchPending: Boolean = false) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("유리, 단추, 금속도 빛을 반사합니다. 표시된 곳을 가까이서 확인하세요.", style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.fillMaxWidth().height(320.dp).background(RfColors.surfaceAlt)) {
            preview()
            ReflectionOverlay(boxes)
        }
        Text(status, style = MaterialTheme.typography.bodyLarge)
        if (running && boxes.isNotEmpty()) Text("빛 반사 위치 · 가까이서 확인", color = RfColors.primary)
        Button(onClick = if (running) stop else start, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (running) "카메라 중지" else "카메라 시작") }
        if (running) {
            OutlinedButton(onClick = torch, enabled = hasTorch && !torchPending, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (!hasTorch) "휴대폰 불빛 없음" else if (torchOn) "휴대폰 불빛 끄기" else "휴대폰 불빛 켜기") }
        }
    }
}

@Composable
internal fun ReflectionOverlay(boxes: List<RectF>) {
    Canvas(Modifier.fillMaxSize()) {
        boxes.forEach { rect ->
            val margin = 8.dp.toPx()
            drawRect(RfColors.warning, Offset(rect.left - margin, rect.top - margin), Size(rect.width() + margin * 2, rect.height() + margin * 2), style = Stroke(width = 3.dp.toPx()))
        }
    }
}
