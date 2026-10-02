package com.vadymsidorov.yobot.output

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.vadymsidorov.yobot.core.events.OutputFeedback
import com.vadymsidorov.yobot.core.events.SpeechFailed
import com.vadymsidorov.yobot.core.events.SpeechFinished
import com.vadymsidorov.yobot.core.events.SpeechStarted
import com.vadymsidorov.yobot.core.output.Voice
import com.vadymsidorov.yobot.core.telemetry.Logger

/** Android TextToSpeech; progress is reported back as [OutputFeedback]. */
class AndroidVoice(
    context: Context,
    private val post: (OutputFeedback) -> Unit,
    private val log: Logger,
) : Voice {
    @Volatile
    private var ready = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            configure()
            ready = true
            log.info("tts ready")
        } else {
            log.error("tts init failed: $status")
        }
    }

    private fun configure() {
        tts.setPitch(1.35f)
        tts.setSpeechRate(1.05f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = post(SpeechStarted(utteranceId))
            override fun onDone(utteranceId: String) = post(SpeechFinished(utteranceId))
            override fun onStop(utteranceId: String, interrupted: Boolean) = post(SpeechFinished(utteranceId))

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = post(SpeechFailed(utteranceId, "tts error"))

            override fun onError(utteranceId: String, errorCode: Int) =
                post(SpeechFailed(utteranceId, "tts error $errorCode"))
        })
    }

    override fun say(id: String, text: String) {
        if (!ready) {
            post(SpeechFailed(id, "tts not ready"))
            return
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) post(SpeechFailed(id, "speak returned $result"))
    }

    override fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
