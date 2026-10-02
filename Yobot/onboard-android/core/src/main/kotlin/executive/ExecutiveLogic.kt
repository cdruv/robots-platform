package com.vadymsidorov.yobot.core.executive

import com.vadymsidorov.yobot.core.events.Command
import com.vadymsidorov.yobot.core.events.ExecutiveEvent
import com.vadymsidorov.yobot.core.events.HeardUtterance
import com.vadymsidorov.yobot.core.events.Heartbeat
import com.vadymsidorov.yobot.core.events.Motion
import com.vadymsidorov.yobot.core.events.MotionKind
import com.vadymsidorov.yobot.core.events.SayText
import com.vadymsidorov.yobot.core.events.SetExpression
import com.vadymsidorov.yobot.core.events.SpeechFailed
import com.vadymsidorov.yobot.core.events.SpeechFinished
import com.vadymsidorov.yobot.core.events.SpeechStarted
import com.vadymsidorov.yobot.core.events.SystemStatus
import com.vadymsidorov.yobot.core.events.ThinkNow
import com.vadymsidorov.yobot.core.events.ThoughtResult
import com.vadymsidorov.yobot.core.events.Touch
import com.vadymsidorov.yobot.core.events.TouchKind
import com.vadymsidorov.yobot.core.events.TouchRegion
import com.vadymsidorov.yobot.core.events.VisionSummary
import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.output.FaceState
import com.vadymsidorov.yobot.core.output.HapticPattern
import com.vadymsidorov.yobot.core.state.Gaze
import com.vadymsidorov.yobot.core.state.RobotState
import com.vadymsidorov.yobot.core.state.ThinkTrigger
import com.vadymsidorov.yobot.core.state.TimedUtterance
import com.vadymsidorov.yobot.core.state.TriggerKind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ExecutiveConfig(
    val idleIntervalMs: Long = 120_000,
    val touchThinkIntervalMs: Long = 5_000,
    val motionThinkIntervalMs: Long = 15_000,
    /** A thought older than this is abandoned, in case its result never arrives. */
    val thinkTimeoutMs: Long = 35_000,
    val reactionMs: Long = 1_500,
    val strongReactionMs: Long = 3_000,
    val maxRecentUtterances: Int = 8,
    val utteranceMaxAgeMs: Long = 5 * 60_000,
)

data class Step(val state: RobotState, val effects: List<Effect>)

/**
 * The robot's reflexes and policy as a pure function: `(state, event, now) -> (state, effects)`.
 * No I/O, no clocks, no threads; everything here is unit-testable.
 */
class ExecutiveLogic(private val config: ExecutiveConfig = ExecutiveConfig()) {

    fun step(state: RobotState, event: ExecutiveEvent, nowMs: Long): Step {
        val effects = mutableListOf<Effect>()
        var next = when (event) {
            is HeardUtterance -> onHeard(state, event, nowMs, effects)
            is Motion -> onMotion(state, event, nowMs, effects)
            is Touch -> onTouch(state, event, nowMs, effects)
            is VisionSummary -> state.copy(latestVision = event)
            is SystemStatus -> state.copy(system = event)
            is SpeechStarted -> onSpeechStarted(state, event, effects)
            is SpeechFinished -> onSpeechEnded(state, event.utteranceId, effects)
            is SpeechFailed -> onSpeechEnded(state, event.utteranceId, effects)
            is ThoughtResult -> onThought(state, event, nowMs, effects)
            is Heartbeat -> onHeartbeat(state, nowMs, effects)
            is Command -> onCommand(state, event, nowMs, effects)
        }
        if (next.reaction != null && nowMs >= next.reactionUntilMs) next = next.copy(reaction = null)
        val face = faceOf(next)
        if (face != faceOf(state)) effects.add(0, Effect.SetFace(face))
        return Step(next, effects)
    }

    fun faceOf(state: RobotState): FaceState = FaceState(
        expression = state.reaction ?: if (state.thinking != null) Expression.Thinking else state.expression,
        gazeX = state.gaze.x,
        gazeY = state.gaze.y,
        speaking = state.speaking != null,
        thinking = state.thinking != null,
        energy = energyOf(state),
    )

    private fun energyOf(state: RobotState): Float {
        val system = state.system ?: return 1f
        return when {
            system.thermalStatus >= THERMAL_SEVERE -> 0.3f
            !system.charging && system.batteryPct < 15 -> 0.4f
            !system.charging && system.batteryPct < 30 -> 0.7f
            else -> 1f
        }
    }

    private fun onHeard(s: RobotState, e: HeardUtterance, now: Long, fx: MutableList<Effect>): RobotState {
        val text = e.text.trim()
        if (text.isEmpty()) return s
        if (!e.isFinal) return react(s, Expression.Curious, now, config.reactionMs)
        val recent = (s.recentUtterances + TimedUtterance(text, now)).takeLast(config.maxRecentUtterances)
        return think(s.copy(recentUtterances = recent), ThinkTrigger(TriggerKind.Heard, text), now, fx)
    }

    private fun onMotion(s: RobotState, e: Motion, now: Long, fx: MutableList<Effect>): RobotState {
        val updated = s.copy(latestMotion = e)
        return when (e.kind) {
            MotionKind.Still -> if (s.latestMotion?.kind == MotionKind.Still) updated else updated.copy(gaze = Gaze())
            MotionKind.Tilted -> updated.copy(gaze = Gaze(clampUnit(-e.roll / 45f), clampUnit(e.pitch / 45f)))
            MotionKind.Moving -> updated
            MotionKind.Shaken, MotionKind.PickedUp -> {
                val reaction = if (e.kind == MotionKind.Shaken) Expression.Dizzy else Expression.Surprised
                val reacted = react(updated, reaction, now, config.strongReactionMs)
                val last = s.lastMotionThinkAtMs
                if (last == null || now - last >= config.motionThinkIntervalMs) {
                    think(reacted.copy(lastMotionThinkAtMs = now), ThinkTrigger(TriggerKind.Moved, e.kind.name), now, fx)
                } else {
                    reacted
                }
            }
        }
    }

    private fun onTouch(s: RobotState, e: Touch, now: Long, fx: MutableList<Effect>): RobotState {
        val reaction = when {
            e.kind == TouchKind.Stroke || e.region == TouchRegion.Forehead -> Expression.Happy
            e.region == TouchRegion.LeftEye || e.region == TouchRegion.RightEye -> Expression.Surprised
            e.kind == TouchKind.LongPress -> Expression.Happy
            else -> Expression.Surprised
        }
        fx += Effect.Vibrate(HapticPattern.Short)
        val gaze = Gaze(clampUnit((e.x - 0.5f) * 2f), clampUnit((e.y - 0.5f) * 2f))
        val reacted = react(s.copy(latestTouch = e, gaze = gaze), reaction, now, config.reactionMs)
        val last = s.lastTouchThinkAtMs
        if (last != null && now - last < config.touchThinkIntervalMs) return reacted
        return think(reacted.copy(lastTouchThinkAtMs = now), ThinkTrigger(TriggerKind.Touched, "${e.kind} on ${e.region}"), now, fx)
    }

    private fun onSpeechStarted(s: RobotState, e: SpeechStarted, fx: MutableList<Effect>): RobotState {
        if (s.hearingEnabled) fx += Effect.SetHearing(false)
        return s.copy(speaking = e.utteranceId, hearingEnabled = false)
    }

    private fun onSpeechEnded(s: RobotState, id: String, fx: MutableList<Effect>): RobotState {
        if (s.speaking != id) return s
        fx += Effect.SetHearing(true)
        return s.copy(speaking = null, hearingEnabled = true)
    }

    private fun onThought(s: RobotState, e: ThoughtResult, now: Long, fx: MutableList<Effect>): RobotState {
        if (e.requestId != s.thinking) {
            fx += Effect.Log("thought.stale", buildJsonObject {
                put("requestId", e.requestId)
                s.thinking?.let { put("current", it) }
            })
            return s
        }
        var next = s.copy(thinking = null)
        val intent = e.intent
        if (intent != null) {
            next = next.copy(expression = intent.expression)
            intent.speech?.let { next = say(next, it, fx) }
            intent.actions.forEach { fx += Effect.RunSkill(it) }
            fx += Effect.Remember(e.requestId, intent)
        } else {
            next = react(next, Expression.Confused, now, config.strongReactionMs)
            fx += Effect.Log("thought.failed", buildJsonObject {
                put("requestId", e.requestId)
                put("error", e.error ?: "unknown")
            })
        }
        val pending = next.pendingThink ?: return next
        return think(next.copy(pendingThink = null), pending, now, fx)
    }

    private fun onHeartbeat(s: RobotState, now: Long, fx: MutableList<Effect>): RobotState {
        var next = s
        val cutoff = now - config.utteranceMaxAgeMs
        if (s.recentUtterances.any { it.atMs < cutoff }) {
            next = next.copy(recentUtterances = s.recentUtterances.filter { it.atMs >= cutoff })
        }
        if (next.thinking != null && now - next.lastThinkAtMs > config.thinkTimeoutMs) {
            fx += Effect.Log("thought.abandoned", buildJsonObject { put("requestId", next.thinking) })
            next = react(next.copy(thinking = null), Expression.Confused, now, config.strongReactionMs)
            next.pendingThink?.let { pending -> return think(next.copy(pendingThink = null), pending, now, fx) }
        }
        val idle = next.speaking == null && next.thinking == null && now - next.lastThinkAtMs >= config.idleIntervalMs
        return if (idle) think(next, ThinkTrigger(TriggerKind.Idle), now, fx) else next
    }

    private fun onCommand(s: RobotState, e: Command, now: Long, fx: MutableList<Effect>): RobotState = when (e) {
        is ThinkNow -> think(s, ThinkTrigger(TriggerKind.Command, e.reason), now, fx)
        is SayText -> say(s, e.text, fx)
        is SetExpression -> s.copy(expression = e.expression, reaction = null)
    }

    /** Starts a thought, or queues it (latest trigger wins) while one is in flight. */
    private fun think(s: RobotState, trigger: ThinkTrigger, now: Long, fx: MutableList<Effect>): RobotState {
        if (s.thinking != null) return s.copy(pendingThink = trigger)
        val id = s.nextRequestId
        fx += Effect.Think(id, trigger)
        return s.copy(thinking = id, nextRequestId = id + 1, lastThinkAtMs = now)
    }

    private fun say(s: RobotState, text: String, fx: MutableList<Effect>): RobotState {
        fx += Effect.Say("u${s.nextUtteranceSeq}", text)
        return s.copy(nextUtteranceSeq = s.nextUtteranceSeq + 1)
    }

    private fun react(s: RobotState, expression: Expression, now: Long, durationMs: Long) =
        s.copy(reaction = expression, reactionUntilMs = now + durationMs)

    private fun clampUnit(v: Float) = v.coerceIn(-1f, 1f)

    private companion object {
        /** PowerManager.THERMAL_STATUS_SEVERE */
        const val THERMAL_SEVERE = 3
    }
}
