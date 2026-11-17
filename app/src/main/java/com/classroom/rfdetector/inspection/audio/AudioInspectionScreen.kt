package com.classroom.rfdetector.inspection.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.classroom.rfdetector.ui.RfColors

internal class PendingAudioStart {
    private var requested = false
    private var granted = false
    fun request() { requested = true; granted = false }
    fun permissionResult(allowed: Boolean) { if (requested) granted = allowed; if (!allowed) requested = false }
    fun consumeOnResume(): Boolean {
        if (!requested || !granted) return false
        cancel()
        return true
    }
    fun cancel() { requested = false; granted = false }
}

@Composable
fun AudioInspectionScreen() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(AudioViewState()) }
    val capture = remember(context) { AudioCapture(context.applicationContext) { state = it } }
    val pending = remember { PendingAudioStart() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending.permissionResult(granted)
        if (!granted) state = AudioViewState(message = "마이크 권한이 거부됐어요")
        else if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && pending.consumeOnResume()) capture.start()
    }
    DisposableEffect(owner, capture) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pending.consumeOnResume()) capture.start()
            if (event == Lifecycle.Event.ON_STOP) { pending.cancel(); capture.stop("화면을 떠나 마이크를 껐어요") }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { pending.cancel(); owner.lifecycle.removeObserver(observer); capture.stop() }
    }
    AudioInspectionContent(state, onStart = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) capture.start()
        else { pending.request(); permission.launch(Manifest.permission.RECORD_AUDIO) }
    }, onStop = { capture.stop() }, onReset = { capture.stop("다시 시작하면 새 기준을 모아요") })
}

@Composable
internal fun AudioInspectionContent(state: AudioViewState, onStart: () -> Unit, onStop: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("작은 소리 살펴보기", style = MaterialTheme.typography.headlineSmall)
        Text("휴대전화에 실제로 닿는 소리만 살펴봅니다. 주변 소음도 변화로 보일 수 있어요.")
        Text(state.message, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.running) Button(onClick = onStop, modifier = Modifier.heightIn(min = 52.dp)) { Text("중지") }
            else Button(onClick = onStart, modifier = Modifier.heightIn(min = 52.dp)) { Text("마이크 시작") }
            if (state.running) Button(onClick = onReset, modifier = Modifier.heightIn(min = 52.dp)) { Text("기준 다시 잡기") }
        }
        state.sampleRate?.let { rate ->
            Text("실제 표본 속도: $rate Hz · 표본상 범위: ${rate / 2} Hz까지")
            Text("마이크 보고: ${state.ultrasound}")
        }
        state.analysis?.let { result ->
            Text("기준: ${"%.1f".format(java.util.Locale.ROOT, result.baselineSeconds)} / 3초 · 유효 표본 ${result.validSamples}개")
            Spectrogram(result.columns)
            Text("시간 →  /  위쪽이 높은 주파수", style = MaterialTheme.typography.bodySmall)
            if (result.quality == AudioQuality.READY) {
                Text("작은 소리 변화: ${if (result.lowChange) "커짐" else "뚜렷한 변화 없음"}")
                Text("높은 소리: ${if ((state.sampleRate ?: 0) < 42000) "18–21 kHz 표본 범위 부족" else if (state.ultrasound.startsWith("18–21 kHz 지원하지") || state.ultrasound.contains("알 수 없음")) "마이크 응답 확인 불가" else if (result.highChange) "변화 있음" else "뚜렷한 변화 없음"}")
                Text("반복되는 변화: ${if (result.repeating) "있음" else "확인되지 않음"}")
            } else Text("유효한 기준과 입력이 모여야 변화를 표시해요")
        }
    }
}

@Composable
internal fun Spectrogram(columns: List<FloatArray>) {
    Canvas(Modifier.fillMaxWidth().height(180.dp).background(RfColors.surfaceAlt)) {
        if (columns.isNotEmpty()) {
            val width = size.width / 120f
            columns.forEachIndexed { x, column ->
                val height = size.height / column.size
                column.forEachIndexed { y, value ->
                    drawRect(Color(red = value, green = value * 0.8f, blue = 0.15f + value * 0.35f),
                        Offset(x * width, size.height - (y + 1) * height), Size(width + 0.5f, height + 0.5f))
                }
            }
        }
    }
}
