package com.vadymsidorov.walky.core.executive

import com.vadymsidorov.walky.core.cognition.Brain
import com.vadymsidorov.walky.core.cognition.BrainMode
import com.vadymsidorov.walky.core.cognition.ParrotBrain
import com.vadymsidorov.walky.core.events.HeardUtterance
import com.vadymsidorov.walky.core.events.SetBrain
import com.vadymsidorov.walky.core.events.SetMuted
import com.vadymsidorov.walky.core.output.Face
import com.vadymsidorov.walky.core.output.FaceState
import com.vadymsidorov.walky.core.output.MouthMode
import com.vadymsidorov.walky.core.output.Reaction
import com.vadymsidorov.walky.core.output.StubVoice
import com.vadymsidorov.walky.core.senses.HearingControl
import com.vadymsidorov.walky.core.state.RobotState
import com.vadymsidorov.walky.core.telemetry.FakeTelemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutiveTest {
    private class RecordingFace : Face {
        val targets = mutableListOf<FaceState>()
        val mouth get() = targets.last().mouth
        override fun setTarget(state: FaceState) { targets += state }
        override fun react(reaction: Reaction) {}
    }

    private class RecordingHearing : HearingControl {
        val calls = mutableListOf<Boolean>()
        val enabled get() = calls.last()
        override fun setEnabled(enabled: Boolean) { calls += enabled }
    }

    private val telemetry = FakeTelemetry()
    private val face = RecordingFace()
    private val hearing = RecordingHearing()

    // advanceUntilIdle ignores backgroundScope, where the voice and the loop run.
    private fun TestScope.finishSpeech() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun TestScope.executive(
        brain: BrainMode,
        muted: Boolean = false,
        create: (BrainMode) -> Brain = BrainMode::create,
    ): Executive {
        lateinit var executive: Executive
        val voice = StubVoice(backgroundScope, telemetry.logger("voice")) { executive.post(it) }
        executive = Executive(backgroundScope, face, voice, hearing, brain, muted, telemetry, { testScheduler.currentTime }, create)
        executive.start()
        return executive
    }

    private fun heard(text: String) = HeardUtterance(text, isFinal = true)
    private fun said() = telemetry.lines.filter { it.startsWith("info say ") }

    @Test
    fun parrotPausesHearingAndOpensTheMouthWhileItSpeaks() = runTest {
        val executive = executive(BrainMode.Parrot)
        assertEquals(listOf(true), hearing.calls)
        assertEquals(MouthMode.Idle, face.mouth)

        executive.post(heard("hello there"))
        runCurrent()
        assertEquals(listOf("info say “hello there”"), said())
        assertFalse(hearing.enabled)
        assertEquals(MouthMode.Speaking, face.mouth)

        finishSpeech()
        assertTrue(hearing.enabled)
        assertEquals(MouthMode.Idle, face.mouth)
    }

    @Test
    fun idleSaysNothing() = runTest {
        val executive = executive(BrainMode.Idle)
        executive.post(heard("hello there"))
        finishSpeech()
        assertTrue(said().isEmpty())
        assertEquals(listOf(true), hearing.calls)
    }

    @Test
    fun switchingBrainStopsSpeechAndRestoresNeutralOutputs() = runTest {
        val executive = executive(BrainMode.Parrot)
        executive.post(heard("one"))
        runCurrent()
        assertEquals(MouthMode.Speaking, face.mouth)

        executive.post(SetBrain(BrainMode.Idle))
        runCurrent()
        assertTrue(hearing.enabled)
        assertEquals(MouthMode.Idle, face.mouth)
        assertEquals(RobotState(), executive.state.value)
    }

    @Test
    fun staleFeedbackFromTheOldBrainDoesNotDisturbTheNewOne() = runTest {
        val executive = executive(BrainMode.Parrot)
        executive.post(heard("one"))
        runCurrent()
        // The old utterance's SpeechFailed arrives after the new brain has started speaking.
        executive.post(SetBrain(BrainMode.Idle))
        executive.post(SetBrain(BrainMode.Parrot))
        executive.post(heard("two"))
        runCurrent()
        assertEquals(listOf("info say “one”", "info say “two”"), said())
        assertFalse(hearing.enabled)
        assertEquals(MouthMode.Speaking, face.mouth)

        finishSpeech()
        assertTrue(hearing.enabled)
        assertEquals(MouthMode.Idle, face.mouth)
    }

    @Test
    fun userMuteWinsOverTheBrain() = runTest {
        val executive = executive(BrainMode.Parrot, muted = true)
        assertEquals(listOf(false), hearing.calls)

        executive.post(heard("hello"))
        finishSpeech()
        assertEquals(1, said().size)
        assertEquals(listOf(false), hearing.calls)

        executive.post(SetMuted(false))
        runCurrent()
        assertTrue(hearing.enabled)
    }

    @Test
    fun aThrowingBrainDoesNotStopLaterEvents() = runTest {
        val executive = executive(BrainMode.Parrot) {
            val parrot = ParrotBrain()
            Brain { state, event, now ->
                if (event == heard("boom")) error("boom")
                parrot.step(state, event, now)
            }
        }
        executive.post(heard("boom"))
        executive.post(heard("hello"))
        finishSpeech()
        assertTrue(telemetry.lines.toString(), telemetry.lines.any { it.startsWith("error Parrot brain failed") })
        assertEquals(listOf("info say “hello”"), said())
    }
}
