package com.vadymsidorov.yobot.senses

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.vadymsidorov.yobot.core.events.HeardUtterance
import com.vadymsidorov.yobot.core.events.Percept
import com.vadymsidorov.yobot.core.senses.HearingControl
import com.vadymsidorov.yobot.core.senses.Sense
import com.vadymsidorov.yobot.core.telemetry.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

enum class HearingState { Stopped, NoPermission, Starting, Listening, Speech, Muted, Error }

/** Live view of the recognizer for the debug overlay; percepts still flow through [Sense.start]. */
data class HearingStatus(
    val state: HearingState = HearingState.Stopped,
    val muted: Boolean = false,
    val engine: String = "",
    val partial: String = "",
    val lastFinal: String = "",
    val lastError: String? = null,
    val rmsDb: Float = -2f,
    val sessions: Int = 0,
)

/**
 * Continuous on-device speech recognition. Android ends a recognizer session after each
 * utterance or silence, so this listens in a loop. Recoverable errors retry after a fixed
 * delay; missing permissions or language models stop hearing. Partial and final transcripts
 * are posted as [HeardUtterance]. All recognizer calls run on the main thread, as the
 * platform requires; [start] and [stop] can be called from anywhere.
 *
 * Callbacks from destroyed recognizers or received while no session is active are ignored.
 */
class HearingSense(private val context: Context, private val log: Logger) : Sense, HearingControl {
    override val name = "hearing"

    private val current = MutableStateFlow(HearingStatus())
    val status: StateFlow<HearingStatus> = current.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var post: ((Percept) -> Unit)? = null
    private var running = false
    private var enabled = true
    private var listening = false

    // Set once the service reports onReadyForSpeech. Cancelling before that leaves the
    // on-device service holding the microphone open with no session (seen on Pixel 8).
    private var ready = false
    private var lastPartial = ""

    private val listen = Runnable {
        val r = recognizer ?: return@Runnable
        if (!running || !enabled || listening) return@Runnable
        listening = true
        ready = false
        lastPartial = ""
        current.update { it.copy(state = HearingState.Starting, partial = "", sessions = it.sessions + 1) }
        r.startListening(recognizerIntent())
    }

    override fun start(post: (Percept) -> Unit) {
        main.post {
            if (running) return@post
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                log.warn("hearing not started: RECORD_AUDIO not granted")
                current.update { it.copy(state = HearingState.NoPermission) }
                return@post
            }
            this.post = post
            running = true
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                fail("ON_DEVICE_UNAVAILABLE")
                return@post
            }
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            r.setRecognitionListener(Listener(r))
            recognizer = r
            current.update { it.copy(engine = "on-device", lastError = null) }
            log.info("hearing using on-device recognizer, locale ${Locale.getDefault().toLanguageTag()}")
            listenSoon(0)
        }
    }

    override fun stop() {
        main.post {
            running = false
            listening = false
            ready = false
            main.removeCallbacks(listen)
            recognizer?.destroy()
            recognizer = null
            post = null
            current.update { it.copy(state = HearingState.Stopped, partial = "", rmsDb = -2f) }
            log.info("hearing stopped")
        }
    }

    override fun setEnabled(enabled: Boolean) {
        main.post {
            if (this.enabled == enabled) return@post
            this.enabled = enabled
            log.info(if (enabled) "hearing unmuted" else "hearing muted")
            current.update { it.copy(muted = !enabled) }
            if (enabled) {
                if (running && !listening) listenSoon(RESTART_DELAY_MS)
            } else {
                main.removeCallbacks(listen)
                // A session still starting is cancelled from onReadyForSpeech instead.
                if (listening && ready) endSession()
                if (running) current.update { it.copy(state = HearingState.Muted, partial = "", rmsDb = -2f) }
            }
        }
    }

    /** Cancels the current session and ignores callbacks while no session is active. */
    private fun endSession() {
        listening = false
        ready = false
        recognizer?.cancel()
    }

    private fun listenSoon(delayMs: Long) {
        main.removeCallbacks(listen)
        main.postDelayed(listen, delayMs)
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    /** Drops callbacks from destroyed recognizers or received while no session is active. */
    private inner class Listener(private val owner: SpeechRecognizer) : RecognitionListener {
        private val active get() = owner === recognizer && listening

        override fun onReadyForSpeech(params: Bundle?) {
            if (!active) return
            ready = true
            if (!enabled) {
                endSession()
                return
            }
            current.update { it.copy(state = HearingState.Listening, lastError = null) }
        }

        override fun onBeginningOfSpeech() {
            if (!active || !enabled) return
            current.update { it.copy(state = HearingState.Speech) }
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (!active || !enabled) return
            current.update { it.copy(rmsDb = rmsdB) }
        }

        override fun onPartialResults(partialResults: Bundle) {
            if (!active || !enabled) return
            val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty() && text != lastPartial) {
                lastPartial = text
                current.update { it.copy(partial = text) }
                post?.invoke(HeardUtterance(text, isFinal = false))
            }
        }

        override fun onResults(results: Bundle) {
            if (!active) return
            listening = false
            ready = false
            if (!enabled) return // Muted while the session was starting.
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
            current.update { it.copy(partial = "") }
            if (!text.isNullOrEmpty()) postFinal(text, confidence)
            listenSoon(RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            if (!active) return
            listening = false
            ready = false
            if (!enabled) return // Muted while the session was starting; unmute restarts it.
            val name = errorName(error)
            when (error) {
                // Silence is normal in a continuous listening loop.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    current.update { it.copy(partial = "") }
                    listenSoon(RESTART_DELAY_MS)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail(name, HearingState.NoPermission)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                -> fail(name)
                else -> {
                    log.warn("recognizer error $name; retrying in ${ERROR_DELAY_MS}ms")
                    current.update { it.copy(state = HearingState.Error, partial = "", lastError = name) }
                    listenSoon(ERROR_DELAY_MS)
                }
            }
        }

        override fun onEndOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun postFinal(text: String, confidence: Float?) {
        current.update { it.copy(partial = "", lastFinal = text) }
        post?.invoke(HeardUtterance(text, isFinal = true, confidence = confidence))
    }

    private fun fail(error: String, state: HearingState = HearingState.Error) {
        running = false
        listening = false
        ready = false
        main.removeCallbacks(listen)
        recognizer?.destroy()
        recognizer = null
        post = null
        current.update { it.copy(state = state, partial = "", rmsDb = -2f, lastError = error) }
        log.error("hearing stopped: $error")
    }

    private companion object {
        const val RESTART_DELAY_MS = 150L
        const val ERROR_DELAY_MS = 1_000L

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "ERROR_$code"
        }
    }
}
