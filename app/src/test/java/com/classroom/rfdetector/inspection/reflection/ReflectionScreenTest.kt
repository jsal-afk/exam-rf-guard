package com.classroom.rfdetector.inspection.reflection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.classroom.rfdetector.ui.AppRfTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReflectionScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().lastOrNull() ?: compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val target = File("build/outputs/ui-captures/$name.png")
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    @Test fun syntheticPreviewDrawsOverlayAndMissingTorchIsDisabled() {
        compose.setContent {
            AppRfTheme {
                ReflectionContent(true, "빛 반사 위치를 살펴보세요", false, false, listOf(RectF(100f, 100f, 140f, 140f)),
                    preview = { Box(Modifier.fillMaxSize()) }, start = {}, stop = {}, torch = {})
            }
        }
        compose.onNodeWithText("휴대폰 불빛 없음").assertIsNotEnabled()
        compose.onNodeWithText("빛 반사 위치 · 가까이서 확인").assertExists()
        val bitmap = capture("reflection-synthetic-overlay")
        var orangePixels = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) > 230 && android.graphics.Color.green(pixel) in 120..190 && android.graphics.Color.blue(pixel) < 80) orangePixels++
        }
        assertTrue("overlay should produce visible orange pixels", orangePixels > 20)
    }

    @Test fun unavailableCameraShowsRetryWithoutPreviewOrTorch() {
        var starts = 0
        compose.setContent { AppRfTheme { ReflectionContent(false, "후면 카메라를 사용할 수 없습니다", false, false, emptyList(), preview = {}, start = { starts++ }, stop = {}, torch = {}) } }
        compose.onNodeWithText("후면 카메라를 사용할 수 없습니다").assertIsDisplayed()
        compose.onNodeWithText("휴대폰 불빛 없음").assertDoesNotExist()
        capture("reflection-camera-unavailable")
        compose.onNodeWithText("카메라 시작").performClick()
        assertEquals(1, starts)
    }

    @Test fun deniedPermissionAndStopClearCameraStateWithoutStartingHardware() {
        lateinit var controller: ReflectionCamera
        compose.runOnIdle { controller = ReflectionCamera(PreviewView(compose.activity), compose.activity) }
        compose.runOnIdle { controller.permissionDenied() }
        assertFalse(controller.running)
        assertTrue(controller.status.contains("권한"))
        assertFalse(controller.hasTorch)
        compose.runOnIdle { controller.stop() }
        assertFalse(controller.running)
        assertTrue(controller.boxes.isEmpty())
        assertFalse(controller.torchOn)
    }

    @Test fun activeSessionClosesOnBackgroundAndOnLeavingComposition() {
        var closes = 0
        val backend = object : ReflectionBackend {
            override fun request(callback: (Result<ReflectionProvider>) -> Unit) {
                callback(Result.success(object : ReflectionProvider {
                    override fun hasRearCamera() = true
                    override fun bind(onBoxes: (List<RectF>) -> Unit, onError: (String) -> Unit) = object : ReflectionBinding {
                        override val hasTorch = true
                        override fun torch(enabled: Boolean, completion: (Boolean) -> Unit) { completion(true) }
                        override fun close() { closes++ }
                    }
                }))
            }
            override fun post(action: () -> Unit) = action()
        }
        lateinit var controller: ReflectionCamera
        compose.runOnIdle { controller = ReflectionCamera(PreviewView(compose.activity), compose.activity, backend) }
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) ReflectionLifecycle(controller, compose.activity) }
        compose.runOnIdle { controller.start(); assertTrue(controller.running) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(1, closes); assertFalse(controller.running)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertFalse(controller.running)
        compose.runOnIdle { controller.start(); shown = false }
        compose.waitForIdle()
        assertEquals(2, closes); assertFalse(controller.running)
    }

    @Test fun leavingAndReopeningNeverAutoStartsCamera() {
        var shown by mutableStateOf(true)
        compose.setContent { AppRfTheme { if (shown) ReflectionInspectionScreen() } }
        compose.onNodeWithText("카메라 시작").assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("카메라 시작").assertIsDisplayed()
        compose.runOnIdle { shown = false }
        compose.runOnIdle { shown = true }
        compose.onNodeWithText("카메라 시작").assertIsDisplayed()
    }
}
