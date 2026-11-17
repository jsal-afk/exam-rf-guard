package com.classroom.rfdetector.inspection.audio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.classroom.rfdetector.ui.AppRfTheme
import com.classroom.rfdetector.ui.RfColors
import kotlin.math.PI
import kotlin.math.sin
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioInspectionScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun grantedAtStartedIsConsumedOnResumeAndCancelledWhenLeaving() {
        val pending = PendingAudioStart()
        var starts = 0
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pending.consumeOnResume()) starts++
            if (event == Lifecycle.Event.ON_STOP) pending.cancel()
        }
        compose.runOnIdle { compose.activity.lifecycle.addObserver(observer) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        pending.request()
        pending.permissionResult(true)
        assertEquals(0, starts)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, starts)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        pending.request()
        pending.permissionResult(true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, starts)
        compose.runOnIdle { compose.activity.lifecycle.removeObserver(observer) }
    }


    @Test fun syntheticSpectrumShowsActualBandActivityAndUnsupportedHighRange() {
        val analyzer = AudioAnalyzer(32000)
        fun tone(frame: Int, hz: Double, amplitude: Double) = ShortArray(2048) { sample ->
            (32767 * amplitude * sin(2 * PI * hz * (frame.toLong() * 2048 + sample) / 32000)).toInt().toShort()
        }
        repeat(48) { analyzer.accept(tone(it,4000.0,0.2)) }
        var result = AudioAnalysis(AudioQuality.BASELINE)
        repeat(110) { result = analyzer.accept(tone(it,500.0,if ((it/8)%2==0) 0.08 else 0.4)) }
        compose.setContent { AppRfTheme { Surface(color = RfColors.bg) {
            AudioInspectionContent(AudioViewState(true, "소리 변화를 살펴보는 중", 32000, "18–21 kHz 지원 여부 알 수 없음", result), {}, {}, {})
        } } }
        compose.onNodeWithText("높은 소리: 18–21 kHz 표본 범위 부족").assertExists()
        compose.onNodeWithText("작은 소리 변화: 커짐").assertExists()
        compose.onNodeWithText("반복되는 변화: 있음").assertExists()
        save("audio-synthetic-spectrum")
    }

    @Test fun returningFromBackgroundDoesNotStartMicrophone() {
        compose.setContent { AppRfTheme { AudioInspectionScreen() } }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("마이크 시작").assertIsDisplayed()
        compose.onNodeWithText("화면을 떠나 마이크를 껐어요").assertExists()
    }

    private fun save(name: String) {
        val target = File("build/outputs/ui-captures/$name.png")
        target.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().lastOrNull() ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
