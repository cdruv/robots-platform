package com.vadymsidorov.walky.senses

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.vadymsidorov.walky.core.events.Percept
import com.vadymsidorov.walky.core.reflex.MotionReflex
import com.vadymsidorov.walky.core.senses.BodyMotion
import com.vadymsidorov.walky.core.senses.MotionClassifier
import com.vadymsidorov.walky.core.senses.Sense
import com.vadymsidorov.walky.core.telemetry.ImuAccuracyChange
import com.vadymsidorov.walky.core.telemetry.ImuBatch
import com.vadymsidorov.walky.core.telemetry.ImuInfoMessage
import com.vadymsidorov.walky.core.telemetry.ImuSensorInfo
import com.vadymsidorov.walky.core.telemetry.Telemetry
import com.vadymsidorov.walky.core.telemetry.Topic
import com.vadymsidorov.walky.core.telemetry.Topics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Body motion from Android's fused gravity and linear-acceleration sensors and the gyroscope,
 * read on its own thread at game rate (about 50 Hz). It has two outputs:
 *
 * - the normal path: coarse [com.vadymsidorov.walky.core.events.Motion] percepts, one per
 *   change of kind, posted to the Executive;
 * - a reflex: every sample on [bodyMotion], which the face reads directly, bypassing the
 *   Executive. See [MotionReflex].
 *
 * For the recording, a second thread also reads the raw accelerometer, gyroscope and game
 * rotation vector as fast as the hardware goes (`HIGH_SAMPLING_RATE_SENSORS` lifts the 200 Hz
 * cap), with no batching in the sensor hub. Every event of those and of the fused gravity and
 * linear acceleration is recorded with its own timestamp and accuracy, see [Topics.ImuAccel].
 */
class ImuSense(context: Context, private val telemetry: Telemetry) : Sense, MotionReflex {
    override val name = "imu"

    private val current = MutableStateFlow(BodyMotion())
    override val bodyMotion: StateFlow<BodyMotion> = current.asStateFlow()

    private val log = telemetry.logger("imu")
    private val accuracy = telemetry.channel(Topics.ImuAccuracy)
    private val info = telemetry.channel(Topics.ImuInfo)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private var rawThread: HandlerThread? = null
    private var listener: Listener? = null
    private var rawListener: RawListener? = null
    private var fused: List<Recorded> = emptyList()
    private var raw: List<Recorded> = emptyList()

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
        val fused = listOf(
            Recorded(gravity, Topics.ImuGravity, GAME_PERIOD_US, "m/s²"),
            Recorded(acceleration, Topics.ImuLinear, GAME_PERIOD_US, "m/s²"),
        )
        val l = Listener(post, fused.associateBy { it.sensor.type })
        sensors.registerListener(l, gravity, SensorManager.SENSOR_DELAY_GAME, handler)
        sensors.registerListener(l, acceleration, SensorManager.SENSOR_DELAY_GAME, handler)
        // Optional: without a gyroscope the rotation rates stay zero.
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?.let { sensors.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME, handler) }
            ?: log.warn("imu has no gyroscope; rotation is not sensed")
        thread = t
        listener = l

        val rt = HandlerThread("imu-raw", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        val rawHandler = Handler(rt.looper)
        val raw = listOfNotNull(
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { Recorded(it, Topics.ImuAccel, 0, "m/s²") },
            sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { Recorded(it, Topics.ImuGyro, 0, "rad/s") },
            sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { Recorded(it, Topics.ImuRotation, 0, "unit quaternion") },
        )
        val rl = RawListener(raw.associateBy { it.sensor.type })
        raw.forEach { sensors.registerListener(rl, it.sensor, SensorManager.SENSOR_DELAY_FASTEST, 0, rawHandler) }
        rawThread = rt
        rawListener = rl
        this.fused = fused
        this.raw = raw
        publishInfo()
        rawHandler.postDelayed({ publishInfo() }, MEASURE_MS)
        log.info("imu started; recording ${(fused + raw).joinToString { it.topic.name }}")
    }

    @Synchronized
    override fun stop() {
        val t = thread ?: return
        listener?.let { sensors.unregisterListener(it) }
        rawListener?.let { sensors.unregisterListener(it) }
        // Each sensor's last batch is written on its own thread; quitSafely still runs these.
        val fused = fused
        val raw = raw
        Handler(t.looper).post { fused.forEach { it.flush() } }
        rawThread?.let { rt -> Handler(rt.looper).post { raw.forEach { it.flush() } } }
        t.quitSafely()
        rawThread?.quitSafely()
        thread = null
        rawThread = null
        listener = null
        rawListener = null
        this.fused = emptyList()
        this.raw = emptyList()
        // The face should not keep reacting to the last sample while the sense is off.
        current.value = BodyMotion()
        log.info("imu stopped")
    }

    private fun publishInfo() {
        val sensors = synchronized(this) { fused + raw }.map { it.info() }
        if (sensors.isNotEmpty()) info.write(ImuInfoMessage(sensors))
    }

    private fun accuracyChanged(sensor: Sensor, value: Int, of: Map<Int, Recorded>) {
        val topic = of[sensor.type]?.topic ?: return
        accuracy.write(ImuAccuracyChange(topic.name, value))
    }

    /** One per start, so a stopped sense never posts and the classifier starts fresh. */
    private inner class Listener(private val post: (Percept) -> Unit, private val record: Map<Int, Recorded>) : SensorEventListener {
        private val classifier = MotionClassifier()
        private var gravityX = 0f
        private var gravityY = 0f
        private var gravityZ = 0f
        private var hasGravity = false
        private var rotationX = 0f
        private var rotationY = 0f
        private var rotationZ = 0f

        override fun onSensorChanged(event: SensorEvent): Unit = synchronized(this@ImuSense) {
            if (this !== listener) return
            record[event.sensor.type]?.add(event)
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

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = accuracyChanged(sensor, accuracy, record)
    }

    /** Records only; kept off the reflex thread so a burst of raw events cannot delay it. */
    private inner class RawListener(private val record: Map<Int, Recorded>) : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (this !== rawListener) return
            record[event.sensor.type]?.add(event)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = accuracyChanged(sensor, accuracy, record)
    }

    /**
     * Batches one sensor's events into messages that span less than [BATCH_NS], or change
     * accuracy, logged at their first sample. Used only on the sensor's listener thread.
     */
    private inner class Recorded(val sensor: Sensor, val topic: Topic<ImuBatch>, private val requestedUs: Int, private val units: String) {
        private val channel = telemetry.channel(topic)
        private val components = if (sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) 4 else 3
        private val t = LongArray(CAPACITY)
        private val values = Array(components) { FloatArray(CAPACITY) }
        private var n = 0
        private var batchAccuracy = 0
        @Volatile private var count = 0L
        @Volatile private var firstNs = 0L
        @Volatile private var lastNs = 0L

        fun add(event: SensorEvent) {
            if (n > 0 && (event.accuracy != batchAccuracy || event.timestamp - t[0] >= BATCH_NS || n == CAPACITY)) flush()
            if (n == 0) batchAccuracy = event.accuracy
            t[n] = event.timestamp
            for (i in 0 until components) values[i][n] = event.values[i]
            n++
            if (count == 0L) firstNs = event.timestamp
            lastNs = event.timestamp
            count++
        }

        fun flush() {
            if (n == 0) return
            channel.write(t[0], ImuBatch(
                accuracy = batchAccuracy,
                t = t.copyOf(n),
                x = values[0].copyOf(n),
                y = values[1].copyOf(n),
                z = values[2].copyOf(n),
                w = if (components == 4) values[3].copyOf(n) else null,
            ))
            n = 0
        }

        fun info() = ImuSensorInfo(
            topic = topic.name,
            type = sensor.type,
            name = sensor.name,
            vendor = sensor.vendor,
            version = sensor.version,
            maxRange = sensor.maximumRange,
            resolution = sensor.resolution,
            minDelayUs = sensor.minDelay,
            maxDelayUs = sensor.maxDelay,
            fifoMax = sensor.fifoMaxEventCount,
            fifoReserved = sensor.fifoReservedEventCount,
            requestedPeriodUs = requestedUs,
            axes = "Android device frame: x right, y up, z out of the screen; right-handed",
            units = units,
            measuredHz = (count - 1).takeIf { it > 0 && lastNs > firstNs }?.let { it * 1e9f / (lastNs - firstNs) },
        )
    }

    private companion object {
        const val GAME_PERIOD_US = 20_000
        const val BATCH_NS = 20_000_000L
        /** Well above 20 ms of the fastest sensor. */
        const val CAPACITY = 64
        const val MEASURE_MS = 2_000L
    }
}
