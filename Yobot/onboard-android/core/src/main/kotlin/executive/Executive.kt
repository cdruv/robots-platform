package com.vadymsidorov.yobot.core.executive

import com.vadymsidorov.yobot.core.Clock
import com.vadymsidorov.yobot.core.cognition.Cognition
import com.vadymsidorov.yobot.core.events.ExecutiveEvent
import com.vadymsidorov.yobot.core.events.Heartbeat
import com.vadymsidorov.yobot.core.events.RequestId
import com.vadymsidorov.yobot.core.events.ThoughtResult
import com.vadymsidorov.yobot.core.inference.Inference
import com.vadymsidorov.yobot.core.inference.InferenceResponse
import com.vadymsidorov.yobot.core.output.Output
import com.vadymsidorov.yobot.core.senses.HearingControl
import com.vadymsidorov.yobot.core.skills.SkillRegistry
import com.vadymsidorov.yobot.core.state.RobotState
import com.vadymsidorov.yobot.core.telemetry.Telemetry
import com.vadymsidorov.yobot.core.telemetry.YobotJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/**
 * The robot's single-threaded actor. Events arrive through [post] from any thread; one
 * coroutine applies [ExecutiveLogic] to each in order and carries out the resulting effects.
 * It never blocks: inference runs in child jobs that report back as [ThoughtResult] events.
 */
class Executive(
    private val scope: CoroutineScope,
    private val cognition: Cognition,
    private val inference: Inference,
    private val output: Output,
    private val skills: SkillRegistry,
    private val telemetry: Telemetry,
    private val hearing: HearingControl? = null,
    private val clock: Clock = Clock.System,
    config: ExecutiveConfig = ExecutiveConfig(),
    private val inferenceTimeoutMs: Long = 30_000,
    private val heartbeatMs: Long = 1_000,
    private val loopDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialState: RobotState = RobotState(lastThinkAtMs = clock.nowMs()),
) {
    private val logic = ExecutiveLogic(config)
    private val dropped = AtomicLong(0)
    private val inbox = Channel<ExecutiveEvent>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { dropped.incrementAndGet() },
    )
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<RobotState> = _state.asStateFlow()

    /** Situation text of each in-flight request, kept so the exchange can be remembered. Loop thread only. */
    private val situations = HashMap<RequestId, String>()

    fun start() {
        scope.launch(loopDispatcher) {
            output.face.setTarget(logic.faceOf(_state.value))
            hearing?.setEnabled(_state.value.hearingEnabled)
            for (event in inbox) handle(event)
        }
        scope.launch(loopDispatcher) {
            while (isActive) {
                delay(heartbeatMs)
                post(Heartbeat(clock.nowMs()))
                val lost = dropped.getAndSet(0)
                if (lost > 0) telemetry.emit(SOURCE, "dropped", buildJsonObject { put("events", lost) })
            }
        }
    }

    /** Thread-safe and non-blocking; under overload the oldest queued event is dropped. */
    fun post(event: ExecutiveEvent) {
        inbox.trySend(event)
    }

    private fun handle(event: ExecutiveEvent) {
        val now = clock.nowMs()
        telemetry.emit(SOURCE, "event.${event::class.simpleName}", ExecutiveEvent.serializer(), event)
        val step = try {
            logic.step(_state.value, event, now)
        } catch (e: Exception) {
            telemetry.logger(SOURCE).error("step failed for ${event::class.simpleName}", e)
            return
        }
        _state.value = step.state
        for (effect in step.effects) {
            telemetry.emit(SOURCE, "effect.${effect::class.simpleName}", Effect.serializer(), effect)
            try {
                run(effect, step.state, now)
            } catch (e: Exception) {
                telemetry.logger(SOURCE).error("effect failed: ${effect::class.simpleName}", e)
            }
        }
    }

    private fun run(effect: Effect, state: RobotState, now: Long) {
        when (effect) {
            is Effect.Think -> think(effect, state, now)
            is Effect.Remember -> {
                situations.remove(effect.requestId)?.let { cognition.remember(it, effect.intent) }
                situations.keys.removeAll { it < effect.requestId }
            }
            is Effect.SetFace -> output.face.setTarget(effect.face)
            is Effect.Say -> output.voice.say(effect.utteranceId, effect.text)
            Effect.StopSpeaking -> output.voice.stop()
            is Effect.Vibrate -> output.haptics.vibrate(effect.pattern)
            is Effect.PlaySound -> output.sounds.play(effect.name)
            is Effect.RunSkill -> skills.execute(effect.call, output).onFailure {
                telemetry.logger(SOURCE).warn("skill ${effect.call.name} failed", it)
            }
            is Effect.SetHearing -> hearing?.setEnabled(effect.enabled)
            is Effect.Log -> telemetry.emit(SOURCE, effect.kind, effect.payload)
        }
    }

    private fun think(effect: Effect.Think, state: RobotState, now: Long) {
        val request = cognition.compose(state, effect.trigger, now)
        val situation = request.messages.last().content
        situations[effect.requestId] = situation
        telemetry.emit(INFERENCE_SOURCE, "request", buildJsonObject {
            put("requestId", effect.requestId)
            put("model", inference.model)
            put("messages", request.messages.size)
            put("situation", situation)
        })
        scope.launch(inferenceDispatcher) {
            val result = try {
                val response = withTimeout(inferenceTimeoutMs) { inference.complete(request) }
                telemetry.emit(INFERENCE_SOURCE, "response", buildJsonObject {
                    put("requestId", effect.requestId)
                    put("response", YobotJson.encodeToJsonElement(InferenceResponse.serializer(), response))
                })
                Result.success(cognition.interpret(response.text))
            } catch (e: TimeoutCancellationException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onFailure { telemetry.logger(INFERENCE_SOURCE).warn("request ${effect.requestId} failed", it) }
            post(ThoughtResult.of(effect.requestId, result))
        }
    }

    companion object {
        const val SOURCE = "executive"
        const val INFERENCE_SOURCE = "inference"
    }
}
