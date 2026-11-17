package com.classroom.rfdetector.inspection.magnetic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.classroom.rfdetector.ui.RfColors
import kotlinx.coroutines.delay

@Composable
fun MagneticInspectionScreen() {
    val context = LocalContext.current
    val controller = remember(context) { MagneticController(AndroidMagneticSensors(context)) }
    MagneticInspectionContent(controller)
}

@Composable
internal fun MagneticInspectionContent(controller: MagneticController) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.stop() }
    }
    LaunchedEffect(controller, controller.running) {
        while (controller.running) {
            delay(250)
            controller.tick()
        }
    }
    val reading = controller.reading
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("자기장 변화 살펴보기", style = MaterialTheme.typography.headlineSmall)
        Text("살펴볼 곳마다 잠시 멈추세요. 자석, 케이스, 책상과 주변 금속도 변화를 만듭니다.", style = MaterialTheme.typography.bodyLarge)
        when {
            !controller.available -> Text("이 휴대전화에는 필요한 자기장 또는 움직임 센서가 없습니다.")
            controller.registrationFailed -> Text("센서를 시작하지 못했습니다. 다시 시도해 주세요.")
            !controller.running -> Text("시작을 누르면 잠시 기준을 잡습니다.")
            else -> Text(when (reading.phase) {
                MagneticPhase.BASELINE -> "움직이지 말고 기준을 잡는 중"
                MagneticPhase.MOVING -> "움직임 감지 · 잠시 멈추면 비교를 계속합니다"
                MagneticPhase.SMALL -> "변화 작음"
                MagneticPhase.LARGE -> "변화 커짐"
                MagneticPhase.RETAKE -> "센서 값을 확인할 수 없습니다 · 다시 기준 잡기"
                MagneticPhase.STALE -> "센서 값이 늦어졌습니다 · 다시 기준 잡기"
                MagneticPhase.IDLE -> "측정 대기"
            }, style = MaterialTheme.typography.headlineSmall)
        }
        if (reading.history.isNotEmpty()) {
            Text("최근 자기장 크기 변화", style = MaterialTheme.typography.titleMedium)
            MagneticHistory(reading.history)
            var details by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { details = !details }) { Text(if (details) "측정값 접기" else "측정값 보기") }
            if (details) reading.delta?.let { Text("기준과 차이: ${"%.1f".format(it)} µT", style = MaterialTheme.typography.bodyMedium) }
        }
        if (controller.running) {
            OutlinedButton(onClick = controller::retake, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("다시 기준 잡기") }
            Button(onClick = controller::stop, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("측정 멈추기") }
        } else {
            Button(onClick = controller::start, enabled = controller.available, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("측정 시작") }
        }
    }
}

@Composable
private fun MagneticHistory(history: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "최근 자기장 변화 그래프" }) {
        val ceiling = maxOf(10f, history.maxOrNull() ?: 10f)
        val step = size.width / 79f
        val start = 80 - history.size
        history.zipWithNext().forEachIndexed { index, (left, right) ->
            drawLine(RfColors.primary,
                Offset((start + index) * step, size.height * (1f - left / ceiling)),
                Offset((start + index + 1) * step, size.height * (1f - right / ceiling)),
                strokeWidth = 3.dp.toPx())
        }
    }
}
