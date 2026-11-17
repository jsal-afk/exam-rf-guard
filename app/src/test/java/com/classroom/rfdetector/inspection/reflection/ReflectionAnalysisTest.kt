package com.classroom.rfdetector.inspection.reflection

import android.graphics.Rect
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReflectionAnalysisTest {
    private fun frame(time: Long, base: Int = 60, spot: Boolean = true, glare: Boolean = false, offset: Int = 0, texture: Boolean = false): LumaFrame {
        val width = 80; val height = 60; val rowStride = 170; val pixelStride = 2
        val buffer = ByteBuffer.allocate(rowStride * height + 7); buffer.position(7)
        for (y in 0 until height) for (x in 0 until width) {
            val value = when {
                glare && x in 15..65 && y in 10..50 -> 240
                spot && x in 30 + offset..32 + offset && y in 20..22 -> 245
                texture -> if (((x + offset) / 3 + y / 3) % 2 == 0) 30 else 120
                else -> base
            }
            buffer.put(7 + y * rowStride + x * pixelStride, value.toByte())
        }
        return LumaFrame(width, height, Rect(0, 0, width, height), rowStride, pixelStride, buffer, time)
    }
    private fun repeated(analyzer: ReflectionAnalyzer, base: Int = 60, spot: Boolean = true, glare: Boolean = false) =
        (1..3).map { analyzer.analyze(frame(it * 100_000_000L, base, spot, glare)) }.last()

    @Test fun compactSpotRequiresPersistenceButGlareAndBrightBackgroundAreRejected() {
        val detector = ReflectionAnalyzer()
        assertTrue(detector.analyze(frame(100_000_000)).isEmpty())
        assertTrue(detector.analyze(frame(200_000_000)).isEmpty())
        val result = detector.analyze(frame(300_000_000))
        assertEquals(1, result.size)
        assertTrue(result.single().contains(31f, 21f))
        assertTrue(repeated(ReflectionAnalyzer(), glare = true).isEmpty())
        assertTrue(repeated(ReflectionAnalyzer(), base = 230, spot = false).isEmpty())
        assertTrue(repeated(ReflectionAnalyzer(), spot = false).isEmpty())
    }

    @Test fun sceneMovementExposureChangeAndStaleFrameResetPersistence() {
        val d = ReflectionAnalyzer()
        repeated(d)
        assertTrue(d.analyze(frame(400_000_000, base = 100)).isEmpty())
        assertTrue(d.analyze(frame(2_000_000_000, base = 100)).isEmpty())
        val motion = ReflectionAnalyzer()
        for (i in 1..3) motion.analyze(frame(i * 100_000_000L, texture = true))
        assertTrue(motion.analyze(frame(400_000_000, offset = 3, texture = true)).isEmpty())
        assertTrue(motion.analyze(frame(500_000_000, offset = 8, texture = true)).isEmpty())
    }

    @Test fun paddedPixelStrideAndCropReturnOriginalBufferCoordinates() {
        val original = frame(100_000_000)
        assertEquals(245, original.sample(31, 21))
        assertEquals(60, original.sample(10, 10))
        val detector = ReflectionAnalyzer()
        var found = emptyList<android.graphics.RectF>()
        for (i in 1..3) {
            val f = frame(i * 100_000_000L)
            found = detector.analyze(f.copy(crop = Rect(10, 5, 70, 55)))
        }
        assertEquals(1, found.size)
        assertTrue(found.single().contains(31f, 21f))
        assertTrue(detector.analyze(original.copy(crop = Rect(0, 0, 81, 60))).isEmpty())
    }

    @Test fun poolingFindsSinglePixelGlintsBetweenOldSamplesAndAtCropEdgesButRejectsGlare() {
        val crop = Rect(7, 9, 238, 178)
        fun image(time: Long, x: Int, y: Int, glare: Boolean): LumaFrame {
            val stride = 507
            val buffer = ByteBuffer.allocate(stride * 190 + 3)
            buffer.position(3)
            for (py in 0 until 190) for (px in 0 until 250) {
                val bright = if (glare) px in 40..200 && py in 30..150 else px == x && py == y
                buffer.put(3 + py * stride + px * 2, (if (bright) 245 else 60).toByte())
            }
            return LumaFrame(250, 190, crop, stride, 2, buffer, time)
        }
        for ((x, y) in listOf(50 to 51, 7 to 9, 237 to 177)) {
            val detector = ReflectionAnalyzer()
            var found = emptyList<android.graphics.RectF>()
            for (i in 1..3) found = detector.analyze(image(i * 100_000_000L, x, y, false))
            assertEquals(1, found.size)
            assertTrue(found.single().contains(x.toFloat(), y.toFloat()))
        }
        val glare = ReflectionAnalyzer()
        for (i in 1..3) assertTrue(glare.analyze(image(i * 100_000_000L, 0, 0, true)).isEmpty())
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun cropCornersAndInteriorRectMapExactlyInPortraitAndLandscape() {
        val crop = Rect(10, 20, 110, 220)
        val normalizedCorners = listOf(
            floatArrayOf(0f,0f, 1f,0f, 1f,1f, 0f,1f),
            floatArrayOf(1f,0f, 1f,1f, 0f,1f, 0f,0f),
            floatArrayOf(1f,1f, 0f,1f, 0f,0f, 1f,0f),
            floatArrayOf(0f,1f, 0f,0f, 1f,0f, 1f,1f)
        )
        val normalizedRects = listOf(
            floatArrayOf(.2f,.3f,.6f,.8f), floatArrayOf(.2f,.2f,.7f,.6f),
            floatArrayOf(.4f,.2f,.8f,.7f), floatArrayOf(.3f,.4f,.8f,.8f)
        )
        for ((width, height) in listOf(200 to 400, 400 to 200)) for (index in 0..3) {
            val matrix = previewMatrix(crop, index * 90, width, height)
            val points = floatArrayOf(10f,20f, 110f,20f, 110f,220f, 10f,220f)
            matrix.mapPoints(points)
            val expected = normalizedCorners[index].mapIndexed { i, n -> n * if (i % 2 == 0) width else height }.toFloatArray()
            assertArrayEquals(expected, points, .01f)
            val rect = android.graphics.RectF(30f, 80f, 70f, 180f)
            matrix.mapRect(rect)
            val box = normalizedRects[index].mapIndexed { i, n -> n * if (i % 2 == 0) width else height }.toFloatArray()
            assertArrayEquals(box, floatArrayOf(rect.left, rect.top, rect.right, rect.bottom), .01f)
        }
    }
}
