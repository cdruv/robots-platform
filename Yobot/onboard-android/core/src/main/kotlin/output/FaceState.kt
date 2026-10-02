package com.vadymsidorov.yobot.core.output

import kotlinx.serialization.Serializable

@Serializable
enum class Expression { Neutral, Happy, Sad, Surprised, Curious, Thinking, Sleepy, Angry, Confused, Dizzy }

/**
 * Target for the face. Renderers animate toward it; they never jump.
 * Gaze is normalized -1..1 (+x screen right, +y screen down); [energy] is 0..1.
 */
@Serializable
data class FaceState(
    val expression: Expression = Expression.Neutral,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val speaking: Boolean = false,
    val thinking: Boolean = false,
    val energy: Float = 1f,
)
