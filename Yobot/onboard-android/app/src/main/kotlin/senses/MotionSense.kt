package com.vadymsidorov.yobot.senses

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.vadymsidorov.yobot.core.events.Motion
import com.vadymsidorov.yobot.core.events.MotionKind
import com.vadymsidorov.yobot.core.events.Percept
import com.vadymsidorov.yobot.core.senses.Sense
import com.vadymsidorov.yobot.core.telemetry.Logger
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Classifies body motion from the rotation vector and linear acceleration. Posts on every
 * class change plus a 2 Hz pose update; raw samples never leave this class.
 *
 * Pitch and roll are relative to standing upright in portrait (screen facing out):
 * roll is sideways lean, pitch is leaning back (+) or forward (−).
 */
class MotionSense(context: Context, private val log: Logger) : Sense, SensorEventListener {
    override val name = "motion"

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private var thread: HandlerThread? = null
    private var post: ((Percept) -> Unit)? = null

    // Sensor-thread state.
    private val rotation = FloatArray(9)
    private var pitch = 0f
    private var roll = 0f
    private var energy = 0f
    private var lastMagnitude = 0f
    private val peakTimes = LongArray(8)
    private var peakIndex = 0
    private var liftStartMs = -1L
    private var stillSinceMs = 0L
    private var holdUntilMs = 0L
    private var kind = MotionKind.Still
    private var lastPostMs = 0L
    private var moving = false
    private var tilted = false

    override fun start(post: (Percept) -> Unit) {
        if (thread != null) return
        if (rotationSensor == null || linearSensor == null) {
            log.warn("motion disabled: rotation or linear acceleration sensor missing")
            return
        }
        this.post = post
        val t = HandlerThread("yobot-motion").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME, handler)
        sensors.registerListener(this, linearSensor, SensorManager.SENSOR_DELAY_GAME, handler)
    }

    override fun stop() {
        sensors.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        post = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> onRotation(event.values)
            Sensor.TYPE_LINEAR_ACCELERATION -> onLinear(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun onRotation(values: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rotation, values)
        // Last row of R is the world "up" axis in device coordinates.
        val gx = rotation[6]
        val gy = rotation[7]
        val gz = rotation[8]
        roll = Math.toDegrees(atan2(gx, gy).toDouble()).toFloat()
        pitch = Math.toDegrees(atan2(gz, gy).toDouble()).toFloat()
    }

    private fun onLinear(values: FloatArray) {
        val now = SystemClock.elapsedRealtime()
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        lastMagnitude = magnitude
        energy += (magnitude - energy) * 0.1f
        // World-vertical component of the acceleration (up is positive).
        val up = rotation[6] * x + rotation[7] * y + rotation[8] * z

        if (magnitude > SHAKE_PEAK) {
            peakTimes[peakIndex] = now
            peakIndex = (peakIndex + 1) % peakTimes.size
        }
        val recentPeaks = peakTimes.count { now - it < SHAKE_WINDOW_MS }

        liftStartMs = if (up > LIFT_ACCEL) (if (liftStartMs < 0) now else liftStartMs) else -1L
        val lifted = liftStartMs >= 0 && now - liftStartMs >= LIFT_HOLD_MS && now - stillSinceMs >= STILL_BEFORE_LIFT_MS

        moving = if (moving) energy > MOVING_EXIT else energy > MOVING_ENTER
        tilted = if (tilted) maxOf(abs(pitch), abs(roll)) > TILT_EXIT else maxOf(abs(pitch), abs(roll)) > TILT_ENTER

        val next = when {
            recentPeaks >= SHAKE_PEAKS -> MotionKind.Shaken
            lifted && kind != MotionKind.Shaken -> MotionKind.PickedUp
            now < holdUntilMs -> kind
            moving -> MotionKind.Moving
            tilted -> MotionKind.Tilted
            else -> MotionKind.Still
        }
        if (next == MotionKind.Shaken || next == MotionKind.PickedUp) holdUntilMs = now + HOLD_MS
        if (next == MotionKind.Still && kind != MotionKind.Still) stillSinceMs = now
        if (next != MotionKind.Still && next != MotionKind.Tilted) stillSinceMs = Long.MAX_VALUE / 2

        if (next != kind || now - lastPostMs >= POSE_PERIOD_MS) {
            kind = next
            lastPostMs = now
            post?.invoke(Motion(next, pitch, roll, lastMagnitude))
        }
    }

    private companion object {
        const val SHAKE_PEAK = 12f
        const val SHAKE_PEAKS = 4
        const val SHAKE_WINDOW_MS = 1_000L
        const val LIFT_ACCEL = 1.8f
        const val LIFT_HOLD_MS = 120L
        const val STILL_BEFORE_LIFT_MS = 800L
        const val MOVING_ENTER = 0.8f
        const val MOVING_EXIT = 0.35f
        const val TILT_ENTER = 25f
        const val TILT_EXIT = 18f
        const val HOLD_MS = 1_500L
        const val POSE_PERIOD_MS = 500L
    }
}
