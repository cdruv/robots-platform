package com.vadymsidorov.walky.core.executive

import com.vadymsidorov.walky.core.cognition.Brain
import com.vadymsidorov.walky.core.cognition.BrainMode
import com.vadymsidorov.walky.core.events.ExecutiveEvent
import com.vadymsidorov.walky.core.events.Percept
import com.vadymsidorov.walky.core.events.RequestId
import com.vadymsidorov.walky.core.events.SetBrain
import com.vadymsidorov.walky.core.events.SetMuted
import com.vadymsidorov.walky.core.events.UtteranceId
import com.vadymsidorov.walky.core.output.Face
import com.vadymsidorov.walky.core.output.FaceState
import com.vadymsidorov.walky.core.output.HapticPattern
import com.vadymsidorov.walky.core.output.Reaction
import com.vadymsidorov.walky.core.output.SoundName
import com.vadymsidorov.walky.core.output.Voice
import com.vadymsidorov.walky.core.senses.HearingControl
import com.vadymsidorov.walky.core.skills.SkillCall
import com.vadymsidorov.walky.core.state.RobotState
import com.vadymsidorov.walky.core.state.ThinkTrigger
import com.vadymsidorov.walky.core.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Owns [RobotState] and runs the selected brain. Events are handled one at a time in arrival
 * order by a single coroutine in [scope]; cancelling the scope stops it. After each step the
 * state's sustained outputs are applied (face target, hearing = `listening && !muted`), then
 * its effects are run. [SetBrain] and [SetMuted] are handled here and never reach the brain.
 *
 * Non-percept events and effects are emitted on `/events` from [SOURCE]; percepts are
 * emitted by whoever posts them.
 */
class Executive(
    private val scope: CoroutineScope,
    private val face: Face,
    private val voice: Voice,
    private val hearing: HearingControl,
    brain: BrainMode,
    private var muted: Boolean,
    private val telemetry: Telemetry,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val create: (BrainMode) -> Brain = BrainMode::create,
) {
    private val log = telemetry.logger(SOURCE)
    private val inbox = Channel<ExecutiveEvent>(Channel.UNLIMITED)
    private val current = MutableStateFlow(RobotState())
    val state: StateFlow<RobotState> = current.asStateFlow()

    // Touched only by start() and then the loop coroutine.
    private var mode = brain
    private var brain = create(brain)
    private var appliedFace: FaceState? = null
    private var appliedHearing: Boolean? = null
    private var started = false

    /** Applies the initial state to the outputs on the caller's thread, then starts the loop. */
    fun start() {
        if (started) return
        started = true
        apply(current.value)
        scope.launch { for (event in inbox) handle(event) }
    }

    /** From any thread; never blocks. */
    fun post(event: ExecutiveEvent) {
        inbox.trySend(event)
    }

    private fun handle(event: ExecutiveEvent) {
        if (event !is Percept) telemetry.emit(SOURCE, event::class.simpleName ?: "Event", ExecutiveEvent.serializer(), event)
        when (event) {
            is SetBrain -> if (event.mode != mode) {
                output("voice.stop") { voice.stop() }
                mode = event.mode
                brain = create(event.mode)
                current.value = RobotState()
                apply(current.value)
                log.info("brain ${event.mode}")
            }
            is SetMuted -> {
                muted = event.muted
                apply(current.value)
            }
            else -> {
                val step = try {
                    brain.step(current.value, event, nowMs())
                } catch (e: Exception) {
                    log.error("$mode brain failed on ${event::class.simpleName}; event skipped", e)
                    return
                }
                current.value = step.state
                apply(step.state)
                step.effects.forEach(::run)
            }
        }
    }

    private fun apply(state: RobotState) {
        if (state.face != appliedFace) {
            appliedFace = state.face
            output("face") { face.setTarget(state.face) }
        }
        val hear = state.listening && !muted
        if (hear != appliedHearing) {
            appliedHearing = hear
            output("hearing") { hearing.setEnabled(hear) }
        }
    }

    private fun run(effect: Effect) {
        if (effect is Effect.Log) {
            telemetry.emit(SOURCE, effect.kind, effect.payload)
            return
        }
        telemetry.emit(SOURCE, effect::class.simpleName ?: "Effect", Effect.serializer(), effect)
        when (effect) {
            is Effect.Say -> output("voice.say") { voice.say(effect.utteranceId, effect.text) }
            Effect.StopSpeaking -> output("voice.stop") { voice.stop() }
            is Effect.React -> output("face.react") { face.react(effect.reaction) }
            else -> log.warn("no output for ${effect::class.simpleName} yet; dropped")
        }
    }

    /** An output failing must not stop the loop. */
    private inline fun output(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.error("$name failed", e)
        }
    }

    companion object {
        const val SOURCE = "executive"
    }
}

data class Step(val state: RobotState, val effects: List<Effect> = emptyList())

/** One-shot actions a brain requests. Sustained outputs are [RobotState], not effects. */
@Serializable
sealed interface Effect {
    @Serializable
    @SerialName("Think")
    data class Think(val requestId: RequestId, val trigger: ThinkTrigger) : Effect

    @Serializable
    @SerialName("React")
    data class React(val reaction: Reaction) : Effect

    @Serializable
    @SerialName("Say")
    data class Say(val utteranceId: UtteranceId, val text: String) : Effect

    @Serializable
    @SerialName("StopSpeaking")
    data object StopSpeaking : Effect

    @Serializable
    @SerialName("Vibrate")
    data class Vibrate(val pattern: HapticPattern) : Effect

    @Serializable
    @SerialName("PlaySound")
    data class PlaySound(val name: SoundName) : Effect

    @Serializable
    @SerialName("RunSkill")
    data class RunSkill(val call: SkillCall) : Effect

    @Serializable
    @SerialName("Log")
    data class Log(val kind: String, val payload: JsonElement = JsonNull) : Effect
}
