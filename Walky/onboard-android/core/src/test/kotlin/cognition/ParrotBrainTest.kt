package com.vadymsidorov.walky.core.cognition

import com.vadymsidorov.walky.core.events.ExecutiveEvent
import com.vadymsidorov.walky.core.events.HeardUtterance
import com.vadymsidorov.walky.core.events.SpeechFailed
import com.vadymsidorov.walky.core.events.SpeechFinished
import com.vadymsidorov.walky.core.events.SpeechStarted
import com.vadymsidorov.walky.core.executive.Effect
import com.vadymsidorov.walky.core.executive.Step
import com.vadymsidorov.walky.core.output.MouthMode
import com.vadymsidorov.walky.core.state.RobotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParrotBrainTest {
    private val brain = ParrotBrain()

    private fun step(state: RobotState, event: ExecutiveEvent): Step = brain.step(state, event, 0)

    private fun heard(text: String) = step(RobotState(), HeardUtterance(text, isFinal = true))

    @Test
    fun repeatsAFinalSentenceAndStopsListening() {
        val step = heard("hello robot")
        val say = step.effects.single() as Effect.Say
        assertEquals("hello robot", say.text)
        assertEquals(say.utteranceId, step.state.speaking)
        assertFalse(step.state.listening)
    }

    @Test
    fun ignoresPartialAndBlankUtterances() {
        assertEquals(Step(RobotState()), step(RobotState(), HeardUtterance("hel", isFinal = false)))
        assertEquals(Step(RobotState()), step(RobotState(), HeardUtterance("  ", isFinal = true)))
    }

    @Test
    fun ignoresWhatItHearsWhileSpeaking() {
        val speaking = heard("one").state
        assertEquals(Step(speaking), step(speaking, HeardUtterance("two", isFinal = true)))
    }

    @Test
    fun opensTheMouthWhenSpeechStartsAndResetsWhenItEnds() {
        val speaking = heard("one").state
        val id = speaking.speaking!!
        val started = step(speaking, SpeechStarted(id)).state
        assertEquals(MouthMode.Speaking, started.face.mouth)
        assertEquals(RobotState(), step(started, SpeechFinished(id)).state)
    }

    @Test
    fun failedSpeechRestoresListening() {
        val speaking = heard("one").state
        val after = step(speaking, SpeechFailed(speaking.speaking!!, "stopped")).state
        assertTrue(after.listening)
        assertEquals(RobotState(), after)
    }

    @Test
    fun ignoresFeedbackForAnotherUtterance() {
        val speaking = heard("one").state
        assertEquals(speaking, step(speaking, SpeechStarted("other")).state)
        assertEquals(speaking, step(speaking, SpeechFinished("other")).state)
        assertEquals(speaking, step(speaking, SpeechFailed("other", "stopped")).state)
    }

    @Test
    fun utteranceIdsDifferAcrossBrainInstances() {
        val first = heard("one").state.speaking
        val second = ParrotBrain().step(RobotState(), HeardUtterance("one", isFinal = true), 0).state.speaking
        assertTrue(first != second)
    }
}
