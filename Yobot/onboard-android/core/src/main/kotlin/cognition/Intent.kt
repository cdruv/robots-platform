package com.vadymsidorov.yobot.core.cognition

import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.skills.SkillCall
import kotlinx.serialization.Serializable

/** What the robot decided to do after one thought. [actions] are already validated. */
@Serializable
data class Intent(
    val speech: String? = null,
    val expression: Expression = Expression.Neutral,
    val actions: List<SkillCall> = emptyList(),
    val thought: String? = null,
)
