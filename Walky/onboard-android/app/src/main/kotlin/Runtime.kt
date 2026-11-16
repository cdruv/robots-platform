package com.vadymsidorov.walky

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.vadymsidorov.walky.body.PicoWifi
import com.vadymsidorov.walky.core.body.BodyLink
import com.vadymsidorov.walky.core.body.BodyRelay
import com.vadymsidorov.walky.core.body.PicoBody
import com.vadymsidorov.walky.core.events.Percept
import com.vadymsidorov.walky.core.events.SetBrain
import com.vadymsidorov.walky.core.events.SetMuted
import com.vadymsidorov.walky.core.executive.Executive
import com.vadymsidorov.walky.core.inference.Inference
import com.vadymsidorov.walky.core.output.Legs
import com.vadymsidorov.walky.core.output.StubVoice
import com.vadymsidorov.walky.core.reflex.MotionReflex
import com.vadymsidorov.walky.core.senses.Sense
import com.vadymsidorov.walky.core.telemetry.DefaultTelemetry
import com.vadymsidorov.walky.core.telemetry.RecentLogsSink
import com.vadymsidorov.walky.core.telemetry.Recorder
import com.vadymsidorov.walky.core.telemetry.Topics
import com.vadymsidorov.walky.core.telemetry.TcpServerSink
import com.vadymsidorov.walky.output.ComposeFace
import com.vadymsidorov.walky.senses.CameraSense
import com.vadymsidorov.walky.senses.HearingSense
import com.vadymsidorov.walky.senses.ImuSense
import com.vadymsidorov.walky.senses.SystemSense
import com.vadymsidorov.walky.telemetry.LogcatSink
import com.vadymsidorov.walky.telemetry.Recordings
import com.vadymsidorov.walky.telemetry.RelayWifi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/** Owns a foreground session. Construction alone starts no work. */
class Runtime(context: Context) {
    private val app = context.applicationContext
    val settings = Settings(app)
    val recentLogs = RecentLogsSink()
    val face = ComposeFace { telemetry }
    val inference = Inference()
    val picoWifi = PicoWifi(app) { telemetry.logger("pico-wifi") }
    private val relayWifi = RelayWifi(app) { telemetry.logger("relay-wifi") }
    private val recordings = Recordings(app)
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
        if (used) session = Session()
        used = true
        running = true
        session.recorder.start()
        telemetryServer.start()
        relayWifi.start(telemetryServer.status.value.wifiPort)
        telemetry.start()
        face.recordState()
        telemetry.logger("runtime").info(
            "resumed: brain ${settings.brain.value}, inference ${inference.backendName}, reflexes imu->face, telemetry port ${telemetryServer.status.value.port}",
        )
        val session = session
        session.executive.start()
        session.scope.launch { settings.brain.collect { session.executive.post(SetBrain(it)) } }
        session.scope.launch { settings.muted.collect { session.executive.post(SetMuted(it)) } }
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
        // Finishes the file on its own thread; anything written after this is not recorded.
        session.recorder.close()
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
        val id = UUID.randomUUID().toString()
        private val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val recorder = Recorder(
            open = { recordings.create(id, System.currentTimeMillis()) },
            metadata = mapOf(
                "session" to id,
                "device" to device,
                "app" to versionName().orEmpty(),
                "clock" to "boottime",
                "startWallMs" to System.currentTimeMillis().toString(),
                "startBootNs" to SystemClock.elapsedRealtimeNanos().toString(),
                "mount" to "portrait; screen and front camera face forward",
            ),
            log = { telemetry.logger("recording") },
        )
        val server: TcpServerSink = TcpServerSink(
            hello = { mapOf("device" to device, "app" to versionName().orEmpty()) },
            onLine = { bodyRelay.onLine(it) },
            wifi = relayWifi,
            session = id,
            requestKeyframe = { camera.requestKeyframe() },
            monoNs = SystemClock::elapsedRealtimeNanos,
        )
        // One clock for every timestamp: boottime, the clock of sensor and camera events.
        val telemetry: DefaultTelemetry = DefaultTelemetry(
            listOf(LogcatSink(), recentLogs), scope,
            streams = listOf(recorder, server),
            monoNs = SystemClock::elapsedRealtimeNanos,
        )
        val hearing = HearingSense(app, telemetry.logger("hearing"), telemetry.channel(Topics.Hearing))
        val imu = ImuSense(app, telemetry)
        val camera = CameraSense(app, telemetry)
        val senses: List<Sense> = listOf(hearing, imu, camera, SystemSense(app))
        val body = PicoBody(picoWifi::socket, scope, telemetry)
        val bodyRelay = BodyRelay(body, picoWifi::pair, telemetry.channel(Topics.BodyLink), scope) { bodyPhase(it) }
        val voice = StubVoice(scope, telemetry.logger("voice")) { executive.post(it) }
        val executive: Executive = Executive(scope, face, voice, hearing, settings.brain.value, settings.muted.value, telemetry)

        fun onPercept(percept: Percept) {
            telemetry.emit("sense", percept::class.simpleName ?: "Percept", Percept.serializer(), percept)
            executive.post(percept)
        }
    }
}
