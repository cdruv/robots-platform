package com.vadymsidorov.yobot.core.state

import com.vadymsidorov.yobot.core.events.Motion
import com.vadymsidorov.yobot.core.events.RequestId
import com.vadymsidorov.yobot.core.events.SystemStatus
import com.vadymsidorov.yobot.core.events.Touch
import com.vadymsidorov.yobot.core.events.UtteranceId
import com.vadymsidorov.yobot.core.events.VisionSummary
import com.vadymsidorov.yobot.core.output.Expression
import kotlinx.serialization.Serializable

@Serializable
enum class TriggerKind { Heard, Touched, Moved, Idle, Command }

/** Why the robot is thinking; [detail] is a short human-readable hint for the prompt. */
@Serializable
data class ThinkTrigger(val kind: TriggerKind, val detail: String? = null)

/** Normalized gaze, -1..1 on both axes; +x is screen right, +y is screen down. */
@Serializable
data class Gaze(val x: Float = 0f, val y: Float = 0f)

@Serializable
data class TimedUtterance(val text: String, val atMs: Long)

/**
 * The robot's world and self model. Owned and mutated only by the Executive;
 * everyone else sees immutable snapshots.
 */
@Serializable
data class RobotState(
    /** Mood chosen by cognition or a command. */
    val expression: Expression = Expression.Neutral,
    /** Short-lived reflex expression that overrides [expression] until [reactionUntilMs]. */
    val reaction: Expression? = null,
    val reactionUntilMs: Long = 0,
    val gaze: Gaze = Gaze(),
    val speaking: UtteranceId? = null,
    val thinking: RequestId? = null,
    val pendingThink: ThinkTrigger? = null,
    val lastThinkAtMs: Long = 0,
    val lastTouchThinkAtMs: Long? = null,
    val lastMotionThinkAtMs: Long? = null,
    val recentUtterances: List<TimedUtterance> = emptyList(),
    val latestMotion: Motion? = null,
    val latestVision: VisionSummary? = null,
    val latestTouch: Touch? = null,
    val system: SystemStatus? = null,
    val hearingEnabled: Boolean = true,
    val nextRequestId: RequestId = 1,
    val nextUtteranceSeq: Long = 1,
)
