package com.vadymsidorov.yobot.core.cognition

import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.skills.SkillCall
import com.vadymsidorov.yobot.core.skills.SkillRegistry
import com.vadymsidorov.yobot.core.telemetry.YobotJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Turns model output into an [Intent]. Lenient by design: strips code fences, tolerates
 * missing fields, drops invalid skill calls, and treats non-JSON as plain speech.
 */
class ResponseParser(private val skills: SkillRegistry) {

    fun parse(raw: String): Intent {
        val obj = extractObject(raw) ?: return Intent(speech = raw.trim().ifBlank { null })
        return Intent(
            speech = obj.text("speech"),
            expression = parseExpression(obj.text("expression")),
            actions = parseActions(obj["actions"]),
            thought = obj.text("thought"),
        )
    }

    private fun extractObject(raw: String): JsonObject? {
        val unfenced = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { YobotJson.parseToJsonElement(unfenced.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }

    private fun parseExpression(name: String?): Expression =
        Expression.entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: Expression.Neutral

    private fun parseActions(element: JsonElement?): List<SkillCall> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.text("skill") ?: obj.text("name") ?: return@mapNotNull null
            val args = obj["args"] as? JsonObject ?: JsonObject(emptyMap())
            SkillCall(name, args).takeIf { skills.validate(it) == null }
        }
    }

    private fun JsonObject.text(key: String): String? {
        val value = this[key]
        if (value == null || value is JsonNull) return null
        return (value as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
    }
}
