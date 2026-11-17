package com.classroom.rfdetector.inspection.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

internal data class AudioViewState(
    val running: Boolean = false,
    val message: String = "시작을 눌러 마이크를 켜세요",
    val sampleRate: Int? = null,
    val ultrasound: String = "확인 전",
    val analysis: AudioAnalysis? = null,
)

internal fun inputFailure(routeType: Int, silenced: Boolean?): String? = when {
    routeType != AudioDeviceInfo.TYPE_BUILTIN_MIC -> "외부 마이크 입력은 사용할 수 없어요"
    silenced == null -> "마이크 입력 상태를 확인할 수 없어요"
    silenced -> "마이크 입력이 시스템에 의해 차단됐어요"
    else -> null
}

internal class AudioInputException(message: String) : Exception(message)

/** read() returns positive samples, or throws; usable() discards data until routing is known. */
internal interface AudioInput {
    val sampleRate: Int
    val ultrasound: String
    fun start()
    fun read(buffer: ShortArray, offset: Int, length: Int): Int
    fun usable(): Boolean
    fun stop()
    fun release()
}

private class AndroidAudioInput(private val record: AudioRecord, override val ultrasound: String) : AudioInput {
    override val sampleRate get() = record.sampleRate
    private var missingRoute = 0
    override fun start() {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw AudioInputException("마이크 시작에 실패했어요")
    }
    override fun read(buffer: ShortArray, offset: Int, length: Int): Int = record.read(buffer, offset, length, AudioRecord.READ_BLOCKING)
    override fun usable(): Boolean {
        val route = record.routedDevice
        if (route == null) {
            if (++missingRoute >= 4) throw AudioInputException("실제 마이크 입력 경로를 확인할 수 없어요")
            return false
        }
        inputFailure(route.type, record.activeRecordingConfiguration?.isClientSilenced)?.let { throw AudioInputException(it) }
        return true
    }
    override fun stop() { try { record.stop() } catch (_: IllegalStateException) { } }
    override fun release() { record.release() }
}

private fun openAndroidInput(context: Context): AudioInput {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        throw AudioInputException("마이크 권한이 없어요")
    }
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val builtin = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    val ultrasound = when (manager.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND)) {
        "true" -> "18–21 kHz 지원 보고됨 (보정되지 않음)"
        "false" -> "18–21 kHz 지원하지 않음으로 보고됨"
        else -> "18–21 kHz 지원 여부 알 수 없음"
    }
    val source = if (manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    for (rate in intArrayOf(48000, 44100, 32000)) {
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) continue
        val candidate = try {
            AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuffer, 8192)).build()
        } catch (_: IllegalArgumentException) { continue } catch (_: UnsupportedOperationException) { continue }
        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
            if (builtin != null) candidate.setPreferredDevice(builtin)
            return AndroidAudioInput(candidate, ultrasound)
        }
        candidate.release()
    }
    throw AudioInputException("지원되는 내장 마이크 입력을 열 수 없어요")
}

/** Owns one capture thread. stop() interrupts blocking reads; that thread alone releases the input. */
internal class AudioCapture(
    private val open: () -> AudioInput,
    private val granted: () -> Boolean,
    private val dispatch: (() -> Unit) -> Unit,
    private val publish: (AudioViewState) -> Unit,
) {
    constructor(context: Context, publish: (AudioViewState) -> Unit) : this(
        { openAndroidInput(context) },
        { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        { main.post(it) }, publish,
    )
    companion object { private val main by lazy { Handler(Looper.getMainLooper()) } }
    private val lock = Any()
    private var session: Session? = null
    private var generation = 0
    private class Session(val id: Int) {
        val stopped = AtomicBoolean(false)
        var input: AudioInput? = null
    }

    fun start() {
        val current = synchronized(lock) {
            if (session != null) return
            Session(++generation).also { session = it }
        }
        publish(AudioViewState(running = true, message = "마이크를 준비하는 중"))
        Thread({ run(current) }, "audio-inspection").start()
    }

    fun stop(message: String = "측정을 중지했어요") {
        synchronized(lock) {
            generation++
            session?.let {
                it.stopped.set(true)
                it.input?.stop()
            }
            session = null
        }
        publish(AudioViewState(message = message))
    }

    private fun emit(s: Session, state: AudioViewState) {
        dispatch {
            val current = synchronized(lock) { session === s && generation == s.id && !s.stopped.get() }
            if (current) publish(state)
        }
    }

    private fun run(s: Session) {
        var input: AudioInput? = null
        var terminal = "마이크를 사용할 수 없어요"
        try {
            input = open()
            val source = input
            synchronized(lock) {
                if (s.stopped.get()) return
                s.input = source
                source.start()
            }
            val rate = source.sampleRate
            val analyzer = AudioAnalyzer(rate)
            val frame = ShortArray(2048)
            var filled = 0
            while (!s.stopped.get()) {
                if (!granted()) throw AudioInputException("마이크 권한이 없어졌어요")
                val n = source.read(frame, filled, frame.size - filled)
                if (s.stopped.get()) break
                if (n <= 0 || n > frame.size - filled) throw AudioInputException("마이크 읽기에 실패했어요")
                if (!source.usable()) { filled = 0; continue }
                filled += n
                if (filled == frame.size) {
                    val result = analyzer.accept(frame)
                    filled = 0
                    val text = when (result.quality) {
                        AudioQuality.BASELINE -> "기준 소리를 모으는 중"
                        AudioQuality.READY -> "소리 변화를 살펴보는 중"
                        AudioQuality.SILENCE -> "입력이 너무 작거나 없어요 · 기준을 다시 모아요"
                        AudioQuality.CLIPPED -> "입력이 너무 커요 · 기준을 다시 모아요"
                        AudioQuality.UNSTABLE -> "기준이 불안정해요 · 조용히 다시 모아요"
                        AudioQuality.PARTIAL -> "입력이 불완전해요 · 기준을 다시 모아요"
                    }
                    emit(s, AudioViewState(true, text, rate, source.ultrasound, result))
                }
            }
        } catch (e: AudioInputException) {
            terminal = e.message ?: terminal
        } catch (_: SecurityException) {
            terminal = "마이크 권한이 없어요"
        } catch (_: IllegalStateException) {
            terminal = "마이크를 사용할 수 없어요"
        } finally {
            synchronized(lock) { s.input = null }
            input?.stop()
            input?.release()
            dispatch {
                val current = synchronized(lock) {
                    if (session === s && generation == s.id) { session = null; true } else false
                }
                if (current) publish(AudioViewState(message = terminal))
            }
        }
    }
}
