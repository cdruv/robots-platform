package com.vadymsidorov.yobot.senses

import android.Manifest
import android.content.Context
import android.content.Intent
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

/**
 * Continuous speech recognition, preferring the on-device recognizer. Listens in a loop,
 * restarting after each result or error. All recognizer calls happen on the main thread.
 */
class HearingSense(private val context: Context, private val log: Logger) : Sense, HearingControl {
    override val name = "hearing"

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private var post: ((Percept) -> Unit)? = null
    private var running = false
    private var enabled = true
    private var listening = false
    private var lastPartial = ""

    private val listen = Runnable {
        val r = recognizer ?: return@Runnable
        if (!running || !enabled || listening) return@Runnable
        listening = true
        lastPartial = ""
        r.startListening(recognizerIntent())
    }

    override fun start(post: (Percept) -> Unit) {
        main.post {
            if (running) return@post
            if (!context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                log.warn("hearing disabled: RECORD_AUDIO not granted")
                return@post
            }
            this.post = post
            running = true
            createRecognizer(preferOnDevice = true)
            listenSoon(0)
        }
    }

    override fun stop() {
        main.post {
            running = false
            listening = false
            main.removeCallbacks(listen)
            recognizer?.destroy()
            recognizer = null
            post = null
        }
    }

    override fun setEnabled(enabled: Boolean) {
        main.post {
            this.enabled = enabled
            if (enabled) {
                listenSoon(RESTART_DELAY_MS)
            } else {
                main.removeCallbacks(listen)
                if (listening) recognizer?.cancel()
                listening = false
            }
        }
    }

    private fun createRecognizer(preferOnDevice: Boolean) {
        recognizer?.destroy()
        onDevice = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        recognizer = (if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }).apply { setRecognitionListener(listener) }
        log.info("hearing using ${if (onDevice) "on-device" else "default"} recognizer")
    }

    private fun listenSoon(delayMs: Long) {
        main.removeCallbacks(listen)
        main.postDelayed(listen, delayMs)
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle) {
            val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty() && text != lastPartial) {
                lastPartial = text
                post?.invoke(HeardUtterance(text, isFinal = false))
            }
        }

        override fun onResults(results: Bundle) {
            listening = false
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
            if (!text.isNullOrEmpty()) post?.invoke(HeardUtterance(text, isFinal = true, confidence = confidence))
            listenSoon(RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenSoon(RESTART_DELAY_MS)
                SpeechRecognizer.ERROR_CLIENT -> listenSoon(RESTART_DELAY_MS)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    log.error("hearing stopped: insufficient permissions")
                    running = false
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                -> if (onDevice) {
                    log.warn("on-device recognizer error $error; falling back to default recognizer")
                    createRecognizer(preferOnDevice = false)
                    listenSoon(RESTART_DELAY_MS)
                } else {
                    log.warn("recognizer error $error")
                    listenSoon(ERROR_DELAY_MS)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> listenSoon(ERROR_DELAY_MS)
                else -> {
                    log.warn("recognizer error $error")
                    listenSoon(ERROR_DELAY_MS)
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val RESTART_DELAY_MS = 250L
        const val ERROR_DELAY_MS = 1_000L
    }
}
