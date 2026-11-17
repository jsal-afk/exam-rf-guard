package com.classroom.rfdetector.inspection.magnetic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.classroom.rfdetector.ui.AppRfTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class FakeMagneticSensors(override val available: Boolean = true, private val registrationSucceeds: Boolean = true) : MagneticSensors {
    override val maxRange = 1000f
    override val resolution = 0.1f
    var unregisters = 0
    var registrations = 0
    lateinit var field: (Float, Float, Float, Int, Long) -> Unit
    lateinit var motion: (Float, Float, Float, Long) -> Unit
    override fun register(onField: (Float, Float, Float, Int, Long) -> Unit, onMotion: (Float, Float, Float, Long) -> Unit, onAccuracyLost: () -> Unit): Boolean {
        registrations++
        field = onField
        motion = onMotion
        return registrationSucceeds
    }
    override fun unregister() { unregisters++ }
    fun emit(time: Long, value: Float, ax: Float = 0f) {
        motion(ax, 0f, 9.8f, time)
        field(value, 0f, 0f, 3, time)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MagneticScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun missingSensorsDisableStartAndRenderCapability() {
        val sensors = FakeMagneticSensors(available = false)
        val controller = MagneticController(sensors) { 1_000_000_000L }
        compose.setContent { AppRfTheme { MagneticInspectionContent(controller) } }
        compose.onNodeWithText("이 휴대전화에는 필요한 자기장 또는 움직임 센서가 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("측정 시작").assertIsNotEnabled()
        assertEquals(0, sensors.registrations)
        capture("magnetic-unavailable")
    }

    @Test fun failedRegistrationShowsErrorAndReleasesPartialRegistration() {
        val sensors = FakeMagneticSensors(registrationSucceeds = false)
        val controller = MagneticController(sensors) { 1_000_000_000L }
        compose.setContent { AppRfTheme { MagneticInspectionContent(controller) } }
        compose.onNodeWithText("측정 시작").performClick()
        compose.onNodeWithText("센서를 시작하지 못했습니다. 다시 시도해 주세요.").assertIsDisplayed()
        assertEquals(1, sensors.unregisters)
        assertFalse(controller.running)
    }

    @Test fun syntheticHistoryIsVisibleAndBackgroundStopsWithoutRestart() {
        val sensors = FakeMagneticSensors()
        var now = 1_000_000_000L
        val controller = MagneticController(sensors) { now }
        compose.setContent { AppRfTheme { MagneticInspectionContent(controller) } }
        compose.onNodeWithText("측정 시작").performClick()
        compose.runOnIdle {
            repeat(23) { index ->
                now = 1_000_000_000L + index * 100_000_000L
                sensors.emit(now, if (index == 22) 50f else 40f)
            }
        }
        compose.onNodeWithText("변화 커짐").assertIsDisplayed()
        compose.onNodeWithContentDescription("최근 자기장 변화 그래프").assertIsDisplayed()
        capture("magnetic-history")
        compose.runOnIdle { now += 100_000_000L; sensors.emit(now, 50f, ax = 1f) }
        compose.onNodeWithText("움직임 감지 · 잠시 멈추면 비교를 계속합니다").assertIsDisplayed()
        compose.runOnIdle {
            repeat(10) { now += 100_000_000L; sensors.emit(now, 50f, ax = 1f) }
        }
        compose.onNodeWithText("변화 커짐").assertIsDisplayed()
        compose.onNodeWithContentDescription("최근 자기장 변화 그래프").assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("측정 시작").assertIsDisplayed()
        compose.onNodeWithContentDescription("최근 자기장 변화 그래프").assertDoesNotExist()
        assertEquals(1, sensors.registrations)
        assertEquals(1, sensors.unregisters)
    }

    private fun capture(name: String) {
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
