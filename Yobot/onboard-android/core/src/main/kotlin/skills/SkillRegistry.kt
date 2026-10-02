package com.vadymsidorov.yobot.core.skills

import com.vadymsidorov.yobot.core.output.Output
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SkillRegistry(skills: List<Skill>) {
    private val byName: Map<String, Skill> = skills.associateBy { it.name }

    init {
        require(byName.size == skills.size) { "duplicate skill names" }
    }

    val names: Set<String> get() = byName.keys

    /** Returns null when [call] is acceptable, otherwise the reason it is not. */
    fun validate(call: SkillCall): String? {
        val skill = byName[call.name] ?: return "unknown skill '${call.name}'"
        val schema = skill.paramsSchema
        val required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        required.firstOrNull { it !in call.args }?.let { return "missing param '$it' for '${call.name}'" }
        val properties = schema["properties"] as? JsonObject ?: return null
        for ((param, value) in call.args) {
            val allowed = (properties[param] as? JsonObject)?.get("enum") as? JsonArray ?: continue
            val text = (value as? JsonPrimitive)?.content
            if (allowed.none { it.jsonPrimitive.content == text }) {
                return "invalid value $value for '${call.name}.$param'"
            }
        }
        return null
    }

    fun execute(call: SkillCall, output: Output): Result<Unit> {
        validate(call)?.let { return Result.failure(IllegalArgumentException(it)) }
        return runCatching { byName.getValue(call.name).execute(call.args, output) }
    }

    /** Catalogue for the system prompt, in OpenAI tool-like shape. */
    fun catalogue(): JsonArray = buildJsonArray {
        byName.values.forEach { skill ->
            add(buildJsonObject {
                put("name", skill.name)
                put("description", skill.description)
                put("parameters", skill.paramsSchema)
            })
        }
    }
}

/** Builds a flat schema of required string-enum parameters. */
fun enumParams(vararg params: Pair<String, List<String>>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        params.forEach { (name, values) ->
            put(name, buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
            })
        }
    })
    put("required", buildJsonArray { params.forEach { add(JsonPrimitive(it.first)) } })
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

