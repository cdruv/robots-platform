package com.vadymsidorov.yobot.core.output

import kotlinx.serialization.Serializable

interface Face {
    fun setTarget(state: FaceState)
}

@Serializable
enum class Expression { Neutral }

/** Only a static neutral face is implemented. Add expression controls incrementally. */
@Serializable
data class FaceState(val expression: Expression = Expression.Neutral)
