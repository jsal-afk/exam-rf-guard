package com.classroom.rfdetector.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.classroom.rfdetector.App
import com.classroom.rfdetector.AppViewModel
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 화면에는 연습 모드가 없으므로 테스트는 뷰모델에서 가상 전파를 켠다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuardFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun launch(): AppViewModel {
        compose.setContent { AppRfTheme { App() } }
        return ViewModelProvider(compose.activity)[AppViewModel::class.java].also { vm -> compose.runOnIdle { vm.setSim(true) } }
    }

    private fun recordEmptyRoom(vm: AppViewModel) {
        compose.onNodeWithText("빈 교실 측정 시작").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { vm.devices.value.size == 5 }
        compose.onNodeWithText("빈 교실 기록 저장").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.baselineSet.value
        }
    }

    private fun addEarbuds(vm: AppViewModel) {
        compose.runOnIdle { vm.simAddDevice(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)) }
        compose.waitUntil(10_000) { vm.watch.value.count == 1 }
    }

    @Test fun proctorRecordsEmptyRoomThenSeesAndLocatesNewSignal() {
        val vm = launch()
        try {
            saveScreen("guard-1-ready")
            recordEmptyRoom(vm)
            saveScreen("guard-3-clear")
            addEarbuds(vm)
            saveScreen("guard-4-found")
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("무선 이어폰"))
            compose.onNodeWithText("무선 이어폰").performClick()
            compose.onNodeWithText("기기 정보 보기").performClick()
            compose.onNodeWithText("주소").assertIsDisplayed()
            assertEquals(0, vm.acknowledged.value.size)
            saveScreen("guard-5-find")
            compose.onNodeWithText("목록으로").performClick()
            assertNull(vm.findTarget.value)
            compose.onNodeWithContentDescription("감시 끝내기").performClick()
            compose.onNodeWithText("끝내기").performClick()
            assertTrue(!vm.scanning.value)
            saveScreen("guard-6-ended")
            compose.onNodeWithText("같은 빈 교실 기록으로 다시 감시").performClick()
            compose.waitUntil(10_000) { vm.watch.value.count == 0 }
        } finally { vm.shutdown() }
    }

    @Test fun confirmedSignalLeavesTheListAndLeavingTheAppKeepsWatching() {
        val vm = launch()
        try {
            recordEmptyRoom(vm)
            addEarbuds(vm)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithContentDescription("감시 끝내기").assertIsDisplayed()
            assertEquals(1, vm.watch.value.count)
            assertEquals(1, vm.scanSession.value!!.pauses)

            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("무선 이어폰"))
            compose.onNodeWithText("무선 이어폰").performClick()
            compose.onNodeWithText("이 신호 확인함").performClick()
            compose.waitUntil(10_000) { vm.watch.value.count == 0 }
            assertEquals(1, vm.acknowledged.value.size)

            compose.onNodeWithContentDescription("빈 교실 다시 측정").performClick()
            compose.onNode(hasText("다시 측정") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(10_000) { !vm.baselineSet.value }
            assertNull(vm.baselineSnapshot.value)
            assertTrue(vm.acknowledged.value.isEmpty())
        } finally { vm.shutdown() }
    }

    @Test fun signalsFromTheEmptyRoomCanBeOpenedForDetails() {
        val vm = launch()
        try {
            recordEmptyRoom(vm)
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("원래 있던 전파 5종"))
            compose.onNodeWithText("원래 있던 전파 5종").performClick()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("스마트워치 (가상)", substring = true))
            compose.onNodeWithText("스마트워치 (가상)", substring = true).performClick()
            compose.onNodeWithText("이 신호 확인함").assertDoesNotExist()
            compose.onNodeWithText("목록으로").performClick()
            compose.onNodeWithContentDescription("감시 끝내기").assertIsDisplayed()
        } finally { vm.shutdown() }
    }

    @Test fun supplementaryAndDisappearedObservationsCanBeOpened() {
        val vm = launch()
        try {
            recordEmptyRoom(vm)
            compose.runOnIdle {
                val now = System.currentTimeMillis()
                vm.tracker.update(com.classroom.rfdetector.core.RfObservation("weak", com.classroom.rfdetector.core.ScanSource.BLE,
                    "보조 신호", -95, null, false, now))
                vm.tracker.update(com.classroom.rfdetector.core.RfObservation("past", com.classroom.rfdetector.core.ScanSource.BLE,
                    "이전 신호", -60, null, false, now - 20_000))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            }
            compose.waitUntil(10_000) { vm.watch.value.supplementaryCount == 1 && vm.watch.value.history.any { it.key == "past" } }
            assertEquals(0, vm.watch.value.count)
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("조용히 확인할 신호 1개") and hasClickAction())
            compose.onNode(hasText("조용히 확인할 신호 1개") and hasClickAction()).performClick()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("보조 신호"))
            saveScreen("guard-supplementary")
            compose.onNodeWithText("보조 신호").performClick()
            compose.onNodeWithText("이 신호 확인함").assertExists()
            compose.onNodeWithText("목록으로").performClick()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("최근 놓친 신호 1개"))
            compose.onNodeWithText("최근 놓친 신호 1개").performClick()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("이전 신호"))
            saveScreen("guard-recent-history")
            compose.onNodeWithText("이전 신호").performClick()
            compose.onNodeWithText("기기 정보 보기").performClick()
            compose.onNodeWithText("주소").performScrollTo()
            compose.onNodeWithText("past").assertIsDisplayed()
        } finally { vm.shutdown() }
    }


    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun guardFlowStaysReachableOnSmallScreenWithLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) { AppRfTheme { App() } }
        }
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        try {
            compose.runOnIdle { vm.setSim(true) }
            saveScreen("guard-small-ready")
            recordEmptyRoom(vm)
            addEarbuds(vm)
            saveScreen("guard-small-found")
            compose.onNodeWithContentDescription("감시 끝내기").assertIsDisplayed()
            compose.onNodeWithContentDescription("빈 교실 다시 측정").assertIsDisplayed()
        } finally { vm.shutdown() }
    }


    private fun saveScreen(name: String) {
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
