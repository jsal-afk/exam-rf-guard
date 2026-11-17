package com.classroom.rfdetector.inspection.audio

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class AudioCaptureTest {
    private class BlockingInput(override val sampleRate: Int) : AudioInput {
        override val ultrasound = "test"
        val blocked = CountDownLatch(1)
        val released = CountDownLatch(1)
        private val unblock = CountDownLatch(1)
        private var reads = 0
        override fun start() = Unit
        override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
            if (reads == 2) {
                blocked.countDown()
                unblock.await(5, TimeUnit.SECONDS)
                return -1
            }
            val n = minOf(1024, length)
            for (i in 0 until n) buffer[offset + i] = (5000 * sin(2 * PI * 4000 * (reads * 1024 + i) / sampleRate)).toInt().toShort()
            reads++
            return n
        }
        override fun usable() = true
        override fun stop() { unblock.countDown() }
        override fun release() { released.countDown() }
    }

    @Test fun partialReadsAssembleAndStopReleasesBlockedInputWithoutStalePublication() {
        val first = BlockingInput(48000)
        val second = BlockingInput(44100)
        val inputs = ConcurrentLinkedQueue(listOf(first, second))
        val callbacks = ConcurrentLinkedQueue<() -> Unit>()
        val states = mutableListOf<AudioViewState>()
        val capture = AudioCapture({ inputs.remove() }, { true }, { callbacks.add(it) }, { states.add(it) })
        capture.start()
        assertTrue(first.blocked.await(5, TimeUnit.SECONDS))
        capture.stop()
        assertTrue(first.released.await(5, TimeUnit.SECONDS))
        capture.start()
        assertTrue(second.blocked.await(5, TimeUnit.SECONDS))
        while (true) (callbacks.poll() ?: break).invoke()
        val analyses = states.mapNotNull { state -> state.analysis?.let { state.sampleRate to it } }
        assertEquals(1, analyses.size)
        assertEquals(44100, analyses.single().first)
        assertEquals(2048L, analyses.single().second.validSamples)
        assertEquals(AudioQuality.BASELINE, analyses.single().second.quality)
        capture.stop()
        assertTrue(second.released.await(5, TimeUnit.SECONDS))
        while (true) (callbacks.poll() ?: break).invoke()
        assertFalse(states.last().running)
    }

    @Test fun readFailureAfterPartialFrameNeverPublishesAnalysis() {
        val released = CountDownLatch(1)
        val posted = CountDownLatch(1)
        val callbacks = ConcurrentLinkedQueue<() -> Unit>()
        val states = mutableListOf<AudioViewState>()
        val input = object : AudioInput {
            override val sampleRate = 48000
            override val ultrasound = "test"
            private var reads = 0
            override fun start() = Unit
            override fun read(buffer: ShortArray, offset: Int, length: Int): Int = if (reads++ == 0) 100 else -1
            override fun usable() = true
            override fun stop() = Unit
            override fun release() { released.countDown() }
        }
        val capture = AudioCapture({ input }, { true }, { callbacks.add(it); posted.countDown() }, { states.add(it) })
        capture.start()
        assertTrue(released.await(5, TimeUnit.SECONDS))
        assertTrue(posted.await(5, TimeUnit.SECONDS))
        while (true) (callbacks.poll() ?: break).invoke()
        assertTrue(states.none { it.analysis != null })
        assertEquals("마이크 읽기에 실패했어요", states.last().message)
    }
}
