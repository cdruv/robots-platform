package com.vadymsidorov.yobot.core.skills

import com.vadymsidorov.yobot.core.output.Output
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A bounded action the LLM may request. [paramsSchema] is a JSON Schema object shown to
 * the model and used for validation (`required` and string `enum`s are enforced).
 */
class Skill(
    val name: String,
    val description: String,
    val paramsSchema: JsonObject,
    val execute: (args: JsonObject, output: Output) -> Unit,
)

@Serializable
data class SkillCall(val name: String, val args: JsonObject = JsonObject(emptyMap()))
