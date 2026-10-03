package com.vadymsidorov.yobot.senses

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.vadymsidorov.yobot.core.events.Percept
import com.vadymsidorov.yobot.core.reflex.MotionReflex
import com.vadymsidorov.yobot.core.senses.BodyMotion
import com.vadymsidorov.yobot.core.senses.MotionClassifier
import com.vadymsidorov.yobot.core.senses.Sense
import com.vadymsidorov.yobot.core.telemetry.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Body motion from Android's fused gravity and linear-acceleration sensors and the gyroscope,
 * read on its own thread at game rate (about 50 Hz). It has two outputs:
 *
 * - the normal path: coarse [com.vadymsidorov.yobot.core.events.Motion] percepts, one per
 *   change of kind, posted to the Executive;
 * - a reflex: every sample on [bodyMotion], which the face reads directly, bypassing the
 *   Executive. See [MotionReflex].
 */
class ImuSense(context: Context, private val log: Logger) : Sense, MotionReflex {
    override val name = "imu"

    private val current = MutableStateFlow(BodyMotion())
    override val bodyMotion: StateFlow<BodyMotion> = current.asStateFlow()

    private val sensors = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private var listener: Listener? = null

    @Synchronized
    override fun start(post: (Percept) -> Unit) {
        if (thread != null) return
        val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val acceleration = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (gravity == null || acceleration == null) {
            log.error("imu not started: gravity or linear acceleration sensor missing")
            return
        }
        val t = HandlerThread("imu").apply { start() }
        val handler = Handler(t.looper)
        val l = Listener(post)
        sensors.registerListener(l, gravity, SensorManager.SENSOR_DELAY_GAME, handler)
        sensors.registerListener(l, acceleration, SensorManager.SENSOR_DELAY_GAME, handler)
        // Optional: without a gyroscope the rotation rates stay zero.
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?.let { sensors.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME, handler) }
            ?: log.warn("imu has no gyroscope; rotation is not sensed")
        thread = t
        listener = l
        log.info("imu started")
    }

    @Synchronized
    override fun stop() {
        val t = thread ?: return
        listener?.let { sensors.unregisterListener(it) }
        t.quitSafely()
        thread = null
        listener = null
        // The face should not keep reacting to the last sample while the sense is off.
        current.value = BodyMotion()
        log.info("imu stopped")
    }

    /** One per start, so a stopped sense never posts and the classifier starts fresh. */
    private inner class Listener(private val post: (Percept) -> Unit) : SensorEventListener {
        private val classifier = MotionClassifier()
        private var gravityX = 0f
        private var gravityY = 0f
        private var gravityZ = 0f
        private var hasGravity = false
        private var rotationX = 0f
        private var rotationY = 0f
        private var rotationZ = 0f

        override fun onSensorChanged(event: SensorEvent) {
            if (this !== listener) return
            val v = event.values
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> {
                    gravityX = v[0]
                    gravityY = v[1]
                    gravityZ = v[2]
                    hasGravity = true
                    return
                }
                Sensor.TYPE_GYROSCOPE -> {
                    rotationX = v[0]
                    rotationY = v[1]
                    rotationZ = v[2]
                    return
                }
            }
            // A sample is published on each linear-acceleration reading, with the latest of the others.
            if (!hasGravity) return
            val sample = BodyMotion(gravityX, gravityY, gravityZ, v[0], v[1], v[2], rotationX, rotationY, rotationZ)
            current.value = sample
            classifier.update(sample, event.timestamp / 1_000_000)?.let(post)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
}
