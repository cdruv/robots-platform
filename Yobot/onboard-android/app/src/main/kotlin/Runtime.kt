package com.vadymsidorov.yobot

import android.content.Context
import com.vadymsidorov.yobot.core.events.Percept
import com.vadymsidorov.yobot.core.inference.Inference
import com.vadymsidorov.yobot.core.reflex.MotionReflex
import com.vadymsidorov.yobot.core.senses.Sense
import com.vadymsidorov.yobot.core.telemetry.DefaultTelemetry
import com.vadymsidorov.yobot.core.telemetry.RecentEventsSink
import com.vadymsidorov.yobot.core.telemetry.TcpServerSink
import com.vadymsidorov.yobot.output.ComposeFace
import com.vadymsidorov.yobot.senses.HearingSense
import com.vadymsidorov.yobot.senses.ImuSense
import com.vadymsidorov.yobot.telemetry.LogcatSink
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Owns a foreground session. Construction alone starts no work. */
class Runtime(context: Context) {
    private val app = context.applicationContext
    val recentEvents = RecentEventsSink()
    val face = ComposeFace()
    val inference = Inference()
    private var session = Session()
    private var running = false
    private var used = false

    val telemetry get() = session.telemetry
    val telemetryServer get() = session.server
    val hearing get() = session.hearing
    val imu get() = session.imu

    /** The sole reflex: IMU -> face, bypassing the executive. */
    val faceMotionReflex: MotionReflex get() = imu

    // Called only by the activity's foreground gate on the main thread.
    fun resume() {
        if (running) return
        if (used) {
            val muted = hearing.status.value.muted
            session = Session()
            hearing.setEnabled(!muted)
        }
        used = true
        running = true
        telemetryServer.start()
        telemetry.start()
        telemetry.emit("runtime", "resume", buildJsonObject {
            put("inference", inference.backendName)
            put("reflexes", "imu->face")
            put("telemetryPort", telemetryServer.status.value.port)
        })
        session.senses.forEach { sense ->
            runCatching { sense.start(session::onPercept) }
                .onFailure { telemetry.logger("runtime").error("${sense.name} failed to start", it) }
        }
    }

    fun pause() {
        if (!running) return
        running = false
        session.senses.forEach { sense ->
            runCatching { sense.stop() }
                .onFailure { telemetry.logger("runtime").error("${sense.name} failed to stop", it) }
        }
        // Close sockets immediately and cancel all workers/timers, without draining old work.
        telemetryServer.close()
        session.scope.cancel()
    }

    private inner class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> android.util.Log.e("Yobot", "session failure", e) })
        val server = TcpServerSink()
        val telemetry = DefaultTelemetry(listOf(LogcatSink(), server, recentEvents), scope)
        val hearing = HearingSense(app, telemetry.logger("hearing"))
        val imu = ImuSense(app, telemetry.logger("imu"))
        val senses: List<Sense> = listOf(hearing, imu)

        fun onPercept(percept: Percept) {
            telemetry.emit("sense", percept::class.simpleName ?: "Percept", Percept.serializer(), percept)
        }
    }
}
