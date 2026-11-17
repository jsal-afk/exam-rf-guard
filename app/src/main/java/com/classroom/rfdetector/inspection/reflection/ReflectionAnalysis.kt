package com.classroom.rfdetector.inspection.reflection

import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Samples only the visible crop; coordinates returned below remain in the original Y buffer. */
internal data class LumaFrame(val width: Int, val height: Int, val crop: Rect, val rowStride: Int, val pixelStride: Int, val bytes: ByteBuffer, val timestamp: Long) {
    fun sample(x: Int, y: Int): Int = bytes.get(bytes.position() + y * rowStride + x * pixelStride).toInt() and 255
}

internal class ReflectionAnalyzer {
    private var previous: IntArray? = null
    private var previousTime = 0L
    private var previousShape: List<Int>? = null
    private var tracks = emptyList<Track>()
    private data class Track(val x: Float, val y: Float, val count: Int)
    private data class Component(val rect: RectF, val x: Float, val y: Float)

    fun reset() { previous = null; previousShape = null; previousTime = 0; tracks = emptyList() }

    fun analyze(frame: LumaFrame): List<RectF> {
        val crop = frame.crop
        if (crop.left < 0 || crop.top < 0 || crop.right > frame.width || crop.bottom > frame.height || crop.width() < 16 || crop.height() < 16 || frame.rowStride <= 0 || frame.pixelStride <= 0) { reset(); return emptyList() }
        val lastIndex = frame.bytes.position().toLong() + (crop.bottom - 1L) * frame.rowStride + (crop.right - 1L) * frame.pixelStride
        if (lastIndex >= frame.bytes.limit()) { reset(); return emptyList() }
        val w = min(80, crop.width()); val h = min(60, crop.height())
        // Cover every crop pixel, including cell and crop edges. Max pooling retains
        // sub-cell glints; cell means separately describe scene/exposure changes.
        require(crop.width().toLong() * crop.height() <= 2_000_000L) { "analysis frame too large" }
        val grid = IntArray(w * h)
        val scene = IntArray(w * h)
        for (gy in 0 until h) for (gx in 0 until w) {
            val left = crop.left + gx * crop.width() / w
            val right = crop.left + (gx + 1) * crop.width() / w
            val top = crop.top + gy * crop.height() / h
            val bottom = crop.top + (gy + 1) * crop.height() / h
            var peak = 0; var sum = 0
            for (y in top until bottom) for (x in left until right) {
                val value = frame.sample(x, y)
                peak = max(peak, value); sum += value
            }
            val index = gy * w + gx
            grid[index] = peak
            scene[index] = sum / ((right - left) * (bottom - top))
        }
        val shape = listOf(crop.left, crop.top, crop.right, crop.bottom, w, h)
        val old = previous
        val validTime = previousTime != 0L && frame.timestamp > previousTime && frame.timestamp - previousTime <= 1_000_000_000L
        val sameShape = previousShape == shape
        val sceneChange = if (old != null && sameShape) scene.indices.sumOf { abs(scene[it] - old[it]).toLong() }.toDouble() / scene.size else 0.0
        val exposureChange = if (old != null && sameShape) abs(scene.average() - old.average()) else 0.0
        if (!validTime || !sameShape || sceneChange > 15 || exposureChange > 10) tracks = emptyList()
        previous = scene; previousTime = frame.timestamp; previousShape = shape

        // Saturated or broadly bright images are not localized reflection evidence.
        val threshold = max(200, (scene.average() + 35).toInt())
        if (threshold > 255 || grid.count { it >= threshold } > grid.size / 12) { tracks = emptyList(); return emptyList() }
        val visited = BooleanArray(grid.size)
        val queue = IntArray(grid.size)
        val components = mutableListOf<Component>()
        for (start in grid.indices) {
            if (visited[start] || grid[start] < threshold) continue
            var head = 0; var tail = 0; queue[tail++] = start; visited[start] = true
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            while (head < tail) {
                val index = queue[head++]; val x = index % w; val y = index / w
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val n = ny * w + nx
                    if (!visited[n] && grid[n] >= threshold) { visited[n] = true; queue[tail++] = n }
                }
            }
            val cw = maxX - minX + 1; val ch = maxY - minY + 1
            if (tail > max(4, grid.size / 100) || cw > w / 6 || ch > h / 6 || max(cw, ch) > 3 * min(cw, ch) || tail * 3 < cw * ch) continue
            var ringSum = 0; var ringCount = 0
            for (y in max(0, minY - 3)..min(h - 1, maxY + 3)) for (x in max(0, minX - 3)..min(w - 1, maxX + 3)) {
                if (x in minX..maxX && y in minY..maxY) continue
                ringSum += grid[y * w + x]; ringCount++
            }
            if (ringCount == 0 || grid[start] - ringSum.toDouble() / ringCount < 35) continue
            val rect = RectF((crop.left + minX * crop.width() / w).toFloat(), (crop.top + minY * crop.height() / h).toFloat(), (crop.left + (maxX + 1) * crop.width() / w).toFloat(), (crop.top + (maxY + 1) * crop.height() / h).toFloat())
            components.add(Component(rect, (minX + maxX) / 2f, (minY + maxY) / 2f))
        }
        val next = mutableListOf<Track>()
        val visible = mutableListOf<RectF>()
        for (component in components.take(20)) {
            val prior = tracks.minByOrNull { (it.x - component.x) * (it.x - component.x) + (it.y - component.y) * (it.y - component.y) }
            val count = if (prior != null && abs(prior.x - component.x) <= 2 && abs(prior.y - component.y) <= 2) prior.count + 1 else 1
            next.add(Track(component.x, component.y, count))
            if (count >= 3) visible.add(component.rect)
        }
        tracks = next
        return visible
    }
}
