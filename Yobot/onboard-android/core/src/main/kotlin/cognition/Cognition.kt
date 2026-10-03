package com.vadymsidorov.yobot.core.cognition

import com.vadymsidorov.yobot.core.inference.InferenceRequest
import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.skills.SkillCall
import com.vadymsidorov.yobot.core.state.RobotState
import com.vadymsidorov.yobot.core.state.ThinkTrigger
import kotlinx.serialization.Serializable

/** Future translation between robot state and model messages. No prompts or policy yet. */
interface Cognition {
    fun compose(state: RobotState, trigger: ThinkTrigger, nowMs: Long): InferenceRequest
    fun interpret(text: String): Intent
}

/** Contract for a future cognition result. No parsing or action validation is implemented yet. */
@Serializable
data class Intent(
    val speech: String? = null,
    val expression: Expression = Expression.Idle,
    val actions: List<SkillCall> = emptyList(),
    val thought: String? = null,
)
