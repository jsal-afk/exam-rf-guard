package com.classroom.rfdetector.inspection

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import com.classroom.rfdetector.App
import com.classroom.rfdetector.AppViewModel
import com.classroom.rfdetector.inspection.radio.*
import com.classroom.rfdetector.core.ScanSource
import com.classroom.rfdetector.ui.AppRfTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InspectionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun optionalToolsAreReachableWithLargeTextAndDeniedExperimentalPermissions() {
        shadowOf(compose.activity.application).denyPermissions(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,1.5f)) { AppRfTheme { App() } } }
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        try {
            compose.onNodeWithText("추가 확인").performClick()
            save("inspection-menu-small")
            for ((index, title) in listOf("다섯 곳 신호 비교","작은 소리 변화","가까운 자기장 변화","빛 반사 찾기").withIndex()) {
                compose.onNodeWithText(title).performScrollTo().performClick()
                save("inspection-tool-$index-small")
                compose.onNodeWithText("도구 목록으로").assertIsDisplayed().performClick()
            }
            compose.onNodeWithContentDescription("돌아가기").performClick()
            compose.runOnIdle { vm.setSim(true) }
            compose.onNodeWithText("빈 교실 측정 시작").performClick()
            compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); vm.devices.value.size == 5 }
            assertTrue(vm.scanning.value)
            assertFalse(vm.requiredPermissions().contains(Manifest.permission.CAMERA))
            assertFalse(vm.requiredPermissions().contains(Manifest.permission.RECORD_AUDIO))
        } finally { vm.shutdown() }
    }

    @Test fun singleNewWifiResultCanBeRecordedAndResetWithLargeText() {
        val model = RoomSurvey("WIFI:AP", ScanSource.WIFI)
        var now by mutableLongStateOf(2_000_000_000L)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,1.5f)) { AppRfTheme {
            RadioSurveyPanel("합성 와이파이", ScanSource.WIFI,model,now,true,"새 결과를 기다립니다",Checkpoint.FRONT_LEFT,
                onSelectPoint={},onStart={},onStop={},onCapture={model.begin(Checkpoint.FRONT_LEFT,now); now++},
                onFinish={model.finish(now); now++},onCancel={model.cancelCapture(); now++},onReference={model.setReference(now); now++},
                onReset={model.reset(); now++},onChoose={})
        } } }
        compose.onNodeWithText("이곳에서 새 측정 시작").performScrollTo().performClick()
        compose.onNodeWithText("이 지점 기록").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { now += 1_000_000_000; model.accept(model.trackingKey,now,-60,now) }
        compose.onNodeWithText("이 지점 기록").assertIsEnabled().performClick()
        assertTrue(model.tentative)
        assertNull(model.readings.getValue(Checkpoint.FRONT_LEFT).dispersion)
        compose.onNodeWithText("다섯 곳 비교 · 1/5 기록").performScrollTo().assertIsDisplayed()
        save("inspection-radio-small")
        compose.onNodeWithText("다섯 곳 기록 초기화").performScrollTo().performClick()
        compose.onNodeWithText("다섯 곳 비교 · 0/5 기록").performScrollTo().assertIsDisplayed()
        assertTrue(model.readings.isEmpty())
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = WindowInspector.getGlobalWindowViews().first { it.width > 0 && it.height > 0 }
        val bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File("build/outputs/ui-captures/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
