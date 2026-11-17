package com.classroom.rfdetector.inspection.magnetic

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal interface MagneticSensors {
    val available: Boolean
    val maxRange: Float
    val resolution: Float
    fun register(onField: (Float, Float, Float, Int, Long) -> Unit, onMotion: (Float, Float, Float, Long) -> Unit, onAccuracyLost: () -> Unit): Boolean
    fun unregister()
}

internal class AndroidMagneticSensors(context: Context) : MagneticSensors {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val field = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val acceleration = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var listener: SensorEventListener? = null
    override val available get() = field != null && acceleration != null
    override val maxRange get() = field?.maximumRange ?: 0f
    override val resolution get() = field?.resolution ?: 0f

    override fun register(onField: (Float, Float, Float, Int, Long) -> Unit, onMotion: (Float, Float, Float, Long) -> Unit, onAccuracyLost: () -> Unit): Boolean {
        val magnetic = field ?: return false
        val motion = acceleration ?: return false
        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    onField(event.values[0], event.values[1], event.values[2], event.accuracy, event.timestamp)
                } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    onMotion(event.values[0], event.values[1], event.values[2], event.timestamp)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD && accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) onAccuracyLost()
            }
        }
        listener = callback
        return try {
            if (!manager.registerListener(callback, magnetic, SensorManager.SENSOR_DELAY_GAME) ||
                !manager.registerListener(callback, motion, SensorManager.SENSOR_DELAY_GAME)) {
                unregister()
                false
            } else true
        } catch (_: RuntimeException) {
            unregister()
            false
        }
    }

    override fun unregister() {
        listener?.let { manager.unregisterListener(it) }
        listener = null
    }
}

internal class MagneticController(private val sensors: MagneticSensors, private val clock: () -> Long = SystemClock::elapsedRealtimeNanos) {
    val available = sensors.available
    private val model = MagneticModel(sensors.maxRange, sensors.resolution)
    var reading by mutableStateOf(model.reading)
        private set
    var running by mutableStateOf(false)
        private set
    var registrationFailed by mutableStateOf(false)
        private set

    fun start() {
        if (running || !available) return
        registrationFailed = false
        model.start(clock())
        reading = model.reading
        running = true
        if (!sensors.register(
                { x, y, z, accuracy, time -> model.field(x, y, z, accuracy, time); reading = model.reading },
                { x, y, z, time -> model.acceleration(x, y, z, time); reading = model.reading },
                { model.accuracyLost(); reading = model.reading },
            )) {
            sensors.unregister()
            running = false
            model.stop()
            reading = model.reading
            registrationFailed = true
        }
    }

    fun retake() {
        if (!running) return
        model.start(clock())
        reading = model.reading
    }

    fun tick() {
        if (!running) return
        model.tick(clock())
        reading = model.reading
    }

    fun stop() {
        if (running) sensors.unregister()
        running = false
        model.stop()
        reading = model.reading
    }
}
