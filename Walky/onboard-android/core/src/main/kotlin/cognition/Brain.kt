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
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * A substitutable policy deciding how the robot responds to events. The Executive calls
 * [step] for every event, in arrival order and never concurrently; [step] must not block.
 * Each activation of a brain starts from a fresh `RobotState()`.
 *
 * Sustained outputs (face target, hearing on/off) are expressed through the returned state;
 * one-shot actions through effects.
 */
fun interface Brain {
    fun step(state: RobotState, event: ExecutiveEvent, nowMs: Long): Step
}

/** The selectable brains; settings list [entries]. [summary] is shown to the user. */
@Serializable
enum class BrainMode(val summary: String) {
    Idle("Ignores every input"),
    Parrot("Repeats each sentence it hears");

    fun create(): Brain = when (this) {
        Idle -> IdleBrain
        Parrot -> ParrotBrain()
    }
}

object IdleBrain : Brain {
    override fun step(state: RobotState, event: ExecutiveEvent, nowMs: Long) = Step(state)
}

/** Says back each final sentence it hears, not listening while it speaks. */
class ParrotBrain : Brain {
    override fun step(state: RobotState, event: ExecutiveEvent, nowMs: Long): Step = when {
        event is HeardUtterance && event.isFinal && event.text.isNotBlank() && state.speaking == null -> {
            val id = "parrot-${ids.incrementAndGet()}"
            Step(state.copy(speaking = id, listening = false), listOf(Effect.Say(id, event.text)))
        }
        event is SpeechStarted && event.utteranceId == state.speaking ->
            Step(state.copy(face = state.face.copy(mouth = MouthMode.Speaking)))
        event is SpeechFinished && event.utteranceId == state.speaking -> Step(RobotState())
        event is SpeechFailed && event.utteranceId == state.speaking -> Step(RobotState())
        else -> Step(state)
    }

    private companion object {
        // Shared by every instance: feedback for a replaced brain's utterance must not match the new one's.
        val ids = AtomicLong()
    }
}
