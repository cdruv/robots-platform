package com.vadymsidorov.walky.core.skills

import com.vadymsidorov.walky.core.output.Output
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Contract for future skill discovery, validation, and execution. */
interface SkillRegistry {
    val names: Set<String>
    fun validate(call: SkillCall): String?
    fun execute(call: SkillCall, output: Output): Result<Unit>
    fun catalogue(): JsonArray
}

/**
 * Contract for a future bounded action. No skills or schema validation are implemented yet.
 */
class Skill(
    val name: String,
    val description: String,
    val paramsSchema: JsonObject,
    val execute: (args: JsonObject, output: Output) -> Unit,
)

@Serializable
data class SkillCall(val name: String, val args: JsonObject = JsonObject(emptyMap()))
