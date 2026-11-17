package com.classroom.rfdetector.inspection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.AppViewModel
import com.classroom.rfdetector.inspection.audio.AudioInspectionScreen
import com.classroom.rfdetector.inspection.magnetic.MagneticInspectionScreen
import com.classroom.rfdetector.inspection.radio.RadioInspectionScreen
import com.classroom.rfdetector.inspection.reflection.ReflectionInspectionScreen

private enum class Tool(val title: String, val hint: String) {
    RADIO("다섯 곳 신호 비교", "선택한 신호가 더 강했던 쪽을 비교합니다"),
    AUDIO("작은 소리 변화", "휴대폰에 닿는 소리의 높이와 반복을 봅니다"),
    MAGNETIC("가까운 자기장 변화", "휴대폰 가까이의 자석·금속 영향을 비교합니다"),
    REFLECTION("빛 반사 찾기", "후면 카메라로 작은 반사 위치를 살펴봅니다"),
}

@Composable
fun InspectionTools(vm: AppViewModel) {
    var selected by rememberSaveable { mutableStateOf<Tool?>(null) }
    // 측정 중 휴대폰 자신의 진동이 음향·자기장에 섞이지 않게 한다.
    LaunchedEffect(Unit) { vm.setGeiger(false); vm.findTarget.value = null }
    BackHandler(selected != null) { selected = null }
    Column(Modifier.fillMaxSize()) {
        val tool = selected
        if (tool == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("주변을 더 살펴보는 도구입니다. 결과만으로 기기나 사람을 특정할 수 없습니다.")
                Tool.entries.forEach { item ->
                    OutlinedButton(onClick = { selected = item }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(item.hint, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        } else {
            TextButton(onClick = { selected = null }) { Text("도구 목록으로") }
            Text(tool.title, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f)) {
                when (tool) {
                    Tool.RADIO -> RadioInspectionScreen(vm)
                    Tool.AUDIO -> AudioInspectionScreen()
                    Tool.MAGNETIC -> MagneticInspectionScreen()
                    Tool.REFLECTION -> ReflectionInspectionScreen()
                }
            }
        }
    }
}
