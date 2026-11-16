package com.vadymsidorov.walky

import android.content.Context
import android.os.Build
import com.vadymsidorov.walky.body.PicoWifi
import com.vadymsidorov.walky.core.body.BodyLink
import com.vadymsidorov.walky.core.body.BodyRelay
import com.vadymsidorov.walky.core.body.PicoBody
import com.vadymsidorov.walky.core.events.Percept
import com.vadymsidorov.walky.core.inference.Inference
import com.vadymsidorov.walky.core.output.Legs
import com.vadymsidorov.walky.core.reflex.MotionReflex
import com.vadymsidorov.walky.core.senses.Sense
import com.vadymsidorov.walky.core.telemetry.DefaultTelemetry
import com.vadymsidorov.walky.core.telemetry.RecentLogsSink
import com.vadymsidorov.walky.core.telemetry.TcpServerSink
import com.vadymsidorov.walky.output.ComposeFace
import com.vadymsidorov.walky.senses.HearingSense
import com.vadymsidorov.walky.senses.ImuSense
import com.vadymsidorov.walky.telemetry.LogcatSink
import com.vadymsidorov.walky.telemetry.RelayWifi
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
    val recentLogs = RecentLogsSink()
    val face = ComposeFace()
    val inference = Inference()
    val picoWifi = PicoWifi(app) { telemetry.logger("pico-wifi") }
    private val relayWifi = RelayWifi(app) { telemetry.logger("relay-wifi") }
    private var session = Session()
    private var running = false
    private var used = false

    val telemetry get() = session.telemetry
    val telemetryServer get() = session.server
    val hearing get() = session.hearing
    val imu get() = session.imu
    val legs: Legs get() = session.body

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
        relayWifi.start(telemetryServer.status.value.wifiPort)
        telemetry.start()
        telemetry.logger("runtime").info(
            "resumed: inference ${inference.backendName}, reflexes imu->face, telemetry port ${telemetryServer.status.value.port}",
        )
        picoWifi.resume()
        session.body.start()
        session.bodyRelay.start()
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
        session.body.close()
        picoWifi.pause()
        relayWifi.stop()
        telemetryServer.close()
        session.scope.cancel()
    }

    /** The body link in one word: what it is waiting for, or that it is connected. */
    fun bodyPhase(link: BodyLink, wifi: PicoWifi.Status = picoWifi.status.value): String = when {
        link.phase == BodyLink.Phase.Connected -> "connected"
        wifi == PicoWifi.Status.Joined -> "connecting"
        else -> wifi.name.lowercase()
    }

    private fun versionName(): String? =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()

    private inner class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> android.util.Log.e("Walky", "session failure", e) })
        val server: TcpServerSink = TcpServerSink(
            hello = {
                buildJsonObject {
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("app", versionName())
                }
            },
            onLine = { bodyRelay.onLine(it) },
            wifi = relayWifi,
        )
        val telemetry = DefaultTelemetry(listOf(LogcatSink(), server, recentLogs), scope)
        val hearing = HearingSense(app, telemetry.logger("hearing"))
        val imu = ImuSense(app, telemetry.logger("imu"))
        val senses: List<Sense> = listOf(hearing, imu)
        val body = PicoBody(picoWifi::socket, scope, telemetry.logger("body"))
        val bodyRelay = BodyRelay(body, picoWifi::pair, server::send, scope) { bodyPhase(it) }

        fun onPercept(percept: Percept) {
            telemetry.emit("sense", percept::class.simpleName ?: "Percept", Percept.serializer(), percept)
        }
    }
}
