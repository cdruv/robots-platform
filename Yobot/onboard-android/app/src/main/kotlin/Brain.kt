package com.vadymsidorov.yobot

import android.content.Context
import com.vadymsidorov.yobot.core.events.Percept
import com.vadymsidorov.yobot.core.inference.Inference
import com.vadymsidorov.yobot.core.reflex.MotionReflex
import com.vadymsidorov.yobot.core.senses.Sense
import com.vadymsidorov.yobot.core.telemetry.DefaultTelemetry
import com.vadymsidorov.yobot.core.telemetry.Logger
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Composition root. Telemetry lives as long as the process; senses run only while the
 * activity is started (Android blocks microphone access for background apps anyway).
 * There is no executive yet, so percepts are only telemetered. Reflexes, the sense-to-output
 * paths that bypass the executive by design, are all connected in the "Reflexes" block below.
 */
class Brain(context: Context) {
    private val app = context.applicationContext

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> log.error("uncaught coroutine failure", e) },
    )

    val recentEvents = RecentEventsSink()
    val telemetryServer = TcpServerSink()
    val telemetry: DefaultTelemetry = DefaultTelemetry(listOf(LogcatSink(), telemetryServer, recentEvents), scope)
    private val log: Logger = telemetry.logger("brain")

    val face = ComposeFace()
    val inference = Inference()
    val hearing = HearingSense(app, telemetry.logger("hearing"))
    val imu = ImuSense(app, telemetry.logger("imu"))
    private val senses: List<Sense> = listOf(hearing, imu)

    // ── Reflexes ────────────────────────────────────────────────────────────────────────
    // The ONLY sense-to-output connections that do not go through the executive. Each one
    // is listed here and in the "reflexes" field of the boot event. Rules: core/reflex/MotionReflex.kt.

    /** imu -> face: every IMU sample, read by the face renderer each frame to move the liquid and the features. */
    val faceMotionReflex: MotionReflex = imu
    // ────────────────────────────────────────────────────────────────────────────────────

    init {
        telemetryServer.start()
        telemetry.start()
        telemetry.emit("brain", "boot", buildJsonObject {
            put("inference", inference.backendName)
            put("reflexes", "imu->face")
            put("telemetryPort", telemetryServer.status.value.port)
            put("addresses", telemetryServer.status.value.addresses.joinToString(","))
        })
    }

    /** Idempotent; each sense ignores a repeated start. Hearing stays off until RECORD_AUDIO is granted. */
    fun startSenses() {
        senses.forEach { sense ->
            runCatching { sense.start(::onPercept) }.onFailure { log.error("${sense.name} failed to start", it) }
        }
    }

    fun stopSenses() {
        senses.forEach { sense ->
            runCatching { sense.stop() }.onFailure { log.error("${sense.name} failed to stop", it) }
        }
    }

    private fun onPercept(percept: Percept) {
        // Keep partials in the bounded telemetry history; the debug view filters them by default.
        telemetry.emit("sense", percept::class.simpleName ?: "Percept", Percept.serializer(), percept)
    }
}
