package com.vadymsidorov.yobot

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.vadymsidorov.yobot.core.cognition.Character
import com.vadymsidorov.yobot.core.cognition.Cognition
import com.vadymsidorov.yobot.core.executive.Executive
import com.vadymsidorov.yobot.core.inference.Inference
import com.vadymsidorov.yobot.core.inference.OpenAiCompatibleBackend
import com.vadymsidorov.yobot.core.output.Output
import com.vadymsidorov.yobot.core.senses.Sense
import com.vadymsidorov.yobot.core.skills.BuiltinSkills
import com.vadymsidorov.yobot.core.skills.SkillRegistry
import com.vadymsidorov.yobot.core.telemetry.Logger
import com.vadymsidorov.yobot.core.telemetry.TcpSink
import com.vadymsidorov.yobot.core.telemetry.Telemetry
import com.vadymsidorov.yobot.core.telemetry.TelemetrySink
import com.vadymsidorov.yobot.output.AndroidHaptics
import com.vadymsidorov.yobot.output.AndroidSounds
import com.vadymsidorov.yobot.output.AndroidVoice
import com.vadymsidorov.yobot.output.ComposeFace
import com.vadymsidorov.yobot.senses.HearingSense
import com.vadymsidorov.yobot.senses.MotionSense
import com.vadymsidorov.yobot.senses.SystemSense
import com.vadymsidorov.yobot.senses.TouchSense
import com.vadymsidorov.yobot.senses.VisionSense
import com.vadymsidorov.yobot.telemetry.FileSink
import com.vadymsidorov.yobot.telemetry.LogcatSink
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Composition root: builds every subsystem once per process. The Executive and telemetry
 * live as long as the process; senses run only while the Activity is started.
 */
class Brain(context: Context) {
    private val app = context.applicationContext

    private val crashHandler = CoroutineExceptionHandler { _, e -> log.error("uncaught coroutine failure", e) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashHandler)

    val telemetry = Telemetry(sinks = telemetrySinks(), scope = scope)
    private val log: Logger = telemetry.logger("brain")

    val face = ComposeFace()
    private val voice = AndroidVoice(app, post = { executive.post(it) }, log = telemetry.logger("voice"))
    val output = Output(face = face, voice = voice, haptics = AndroidHaptics(app), sounds = AndroidSounds())

    private val skills = SkillRegistry(BuiltinSkills.all)
    private val cognition = Cognition(Character.Yobot, skills)
    private val inference = Inference(
        backend = OpenAiCompatibleBackend.openRouter(apiKey = BuildConfig.OPENROUTER_API_KEY),
        defaultModel = BuildConfig.MODEL,
    )

    private val hearing = HearingSense(app, telemetry.logger("hearing"))
    val touchSense = TouchSense()
    private val vision = VisionSense(app, telemetry.logger("vision"))
    private val senses: List<Sense> = listOf(
        hearing,
        MotionSense(app, telemetry.logger("motion")),
        vision,
        touchSense,
        SystemSense(app),
    )

    val executive: Executive = Executive(
        scope = scope,
        cognition = cognition,
        inference = inference,
        output = output,
        skills = skills,
        telemetry = telemetry,
        hearing = hearing,
    )

    private var sensesRunning = false

    init {
        telemetry.start()
        executive.start()
        telemetry.emit("brain", "boot", buildJsonObject {
            put("model", inference.model)
            put("backend", inference.backendName)
            put("apiKeyConfigured", BuildConfig.OPENROUTER_API_KEY.isNotBlank())
            put("telemetryHost", BuildConfig.TELEMETRY_HOST)
        })
    }

    /** Starts the senses; the camera binds to [lifecycleOwner]. Call from the main thread. */
    fun start(lifecycleOwner: LifecycleOwner) {
        if (sensesRunning) return
        sensesRunning = true
        vision.attach(lifecycleOwner)
        senses.forEach { sense ->
            runCatching { sense.start(executive::post) }.onFailure { log.error("${sense.name} failed to start", it) }
        }
        log.info("senses started")
    }

    /** Stops the senses and any speech; the Executive and telemetry keep running so logs flush. */
    fun stop() {
        if (!sensesRunning) return
        sensesRunning = false
        senses.forEach { sense ->
            runCatching { sense.stop() }.onFailure { log.error("${sense.name} failed to stop", it) }
        }
        voice.stop()
        log.info("senses stopped")
    }

    /** Restarts senses so newly granted permissions take effect. */
    fun restart(lifecycleOwner: LifecycleOwner) {
        stop()
        start(lifecycleOwner)
    }

    private fun telemetrySinks(): List<TelemetrySink> = buildList {
        add(LogcatSink())
        app.getExternalFilesDir("telemetry")?.let { add(FileSink(it)) }
        val host = BuildConfig.TELEMETRY_HOST
        if (host.isNotBlank()) add(TcpSink(host, BuildConfig.TELEMETRY_PORT.toIntOrNull() ?: 5555))
    }
}
