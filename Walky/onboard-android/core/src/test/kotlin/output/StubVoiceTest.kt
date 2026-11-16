package com.vadymsidorov.walky.core.output

import com.vadymsidorov.walky.core.events.OutputFeedback
import com.vadymsidorov.walky.core.events.SpeechFailed
import com.vadymsidorov.walky.core.events.SpeechFinished
import com.vadymsidorov.walky.core.events.SpeechStarted
import com.vadymsidorov.walky.core.telemetry.FakeTelemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StubVoiceTest {
    private val telemetry = FakeTelemetry()
    private val feedback = mutableListOf<OutputFeedback>()

    // advanceUntilIdle ignores backgroundScope, where the voice runs.
    private fun TestScope.finishSpeech() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun TestScope.voice() = StubVoice(backgroundScope, telemetry.logger("voice")) { feedback += it }

    @Test
    fun reportsStartedThenFinished() = runTest {
        val voice = voice()
        voice.say("a", "hello")
        runCurrent()
        assertEquals(listOf(SpeechStarted("a")), feedback)
        finishSpeech()
        assertEquals(listOf(SpeechStarted("a"), SpeechFinished("a")), feedback)
        assertTrue(telemetry.lines.contains("info say “hello”"))
    }

    @Test
    fun stopFailsTheCurrentUtterance() = runTest {
        val voice = voice()
        voice.say("a", "hello")
        advanceTimeBy(100)
        voice.stop()
        finishSpeech()
        assertEquals(listOf(SpeechStarted("a"), SpeechFailed("a", "stopped")), feedback)
        voice.stop()
        assertEquals(2, feedback.size)
    }

    @Test
    fun aNewerSayFailsThePreviousOne() = runTest {
        val voice = voice()
        voice.say("a", "first")
        voice.say("b", "second")
        finishSpeech()
        assertEquals(
            listOf(SpeechStarted("a"), SpeechFailed("a", "stopped"), SpeechStarted("b"), SpeechFinished("b")),
            feedback,
        )
    }
}
