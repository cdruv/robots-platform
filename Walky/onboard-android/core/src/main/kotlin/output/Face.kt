package com.vadymsidorov.walky.core.output

import kotlinx.serialization.Serializable

interface Face {
    /** Sustained look: held until the next target replaces it. */
    fun setTarget(state: FaceState)

    /** One-shot gesture layered over the current target. Scaffold: nothing requests reactions yet. */
    fun react(reaction: Reaction)
}

/** Sustained eye expressions. Nothing selects them yet; the default is [Idle]. */
@Serializable
enum class Expression { Idle, Happy, Thinking, Alert, Sleep }

/** What the mouth bar shows. [FaceState.mouthLevel] drives [Listening] and [Speaking]. */
@Serializable
enum class MouthMode { Idle, Listening, Speaking, Processing }

/** Momentary gestures that play once and return to the current expression. Scaffold: not rendered yet. */
@Serializable
enum class Reaction { Blink, DoubleBlink, Glance, Startle }

/** Target for the face renderer. [mouthLevel] is the 0..1 input or output audio level. */
@Serializable
data class FaceState(
    val expression: Expression = Expression.Idle,
    val mouth: MouthMode = MouthMode.Idle,
    val mouthLevel: Float = 0f,
)
