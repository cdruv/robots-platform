package com.vadymsidorov.yobot

import android.content.Context
import com.vadymsidorov.yobot.core.events.HeardUtterance
import com.vadymsidorov.yobot.core.events.Percept
import com.vadymsidorov.yobot.core.inference.Inference
import com.vadymsidorov.yobot.core.senses.Sense
import com.vadymsidorov.yobot.core.telemetry.DefaultTelemetry
import com.vadymsidorov.yobot.core.telemetry.Logger
import com.vadymsidorov.yobot.core.telemetry.RecentEventsSink
import com.vadymsidorov.yobot.core.telemetry.TcpServerSink
import com.vadymsidorov.yobot.output.ComposeFace
import com.vadymsidorov.yobot.senses.HearingSense
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
 * There is no executive yet, so percepts are only telemetered.
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
    private val senses: List<Sense> = listOf(hearing)

    init {
        telemetryServer.start()
        telemetry.start()
        telemetry.emit("brain", "boot", buildJsonObject {
            put("inference", inference.backendName)
            put("telemetryPort", telemetryServer.status.value.port)
            put("addresses", telemetryServer.status.value.addresses.joinToString(","))
        })
    }

    /** Idempotent; each sense ignores a repeated start. Call once RECORD_AUDIO is granted. */
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
        // Partials arrive several times a second while someone talks; the overlay shows them live.
        if (percept is HeardUtterance && !percept.isFinal) return
        telemetry.emit("sense", percept::class.simpleName ?: "Percept", Percept.serializer(), percept)
    }
}
