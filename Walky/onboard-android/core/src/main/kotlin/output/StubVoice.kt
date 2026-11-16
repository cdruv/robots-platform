package com.vadymsidorov.walky.core.output

import com.vadymsidorov.walky.core.events.OutputFeedback
import com.vadymsidorov.walky.core.events.SpeechFailed
import com.vadymsidorov.walky.core.events.SpeechFinished
import com.vadymsidorov.walky.core.events.SpeechStarted
import com.vadymsidorov.walky.core.events.UtteranceId
import com.vadymsidorov.walky.core.telemetry.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Silent [Voice]: logs each utterance and reports it as spoken after a time proportional to
 * its length, so the whole speaking path runs until a real TTS replaces it.
 */
class StubVoice(
    private val scope: CoroutineScope,
    private val log: Logger,
    private val feedback: (OutputFeedback) -> Unit,
) : Voice {
    private class Utterance(val id: UtteranceId, val job: Job)

    private val lock = Any()
    private var current: Utterance? = null

    override fun say(id: String, text: String) {
        synchronized(lock) {
            stopCurrent()
            log.info("say “$text”")
            feedback(SpeechStarted(id))
            val job = scope.launch {
                delay(durationMs(text))
                synchronized(lock) {
                    if (current?.id == id) {
                        current = null
                        feedback(SpeechFinished(id))
                    }
                }
            }
            current = Utterance(id, job)
        }
    }

    override fun stop() {
        synchronized(lock) { stopCurrent() }
    }

    private fun stopCurrent() {
        val utterance = current ?: return
        current = null
        utterance.job.cancel()
        feedback(SpeechFailed(utterance.id, "stopped"))
    }

    companion object {
        fun durationMs(text: String): Long = 400L + 60L * text.length
    }
}
