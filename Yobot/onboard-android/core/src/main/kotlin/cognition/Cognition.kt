package com.vadymsidorov.yobot.core.cognition

import com.vadymsidorov.yobot.core.inference.ChatMessage
import com.vadymsidorov.yobot.core.inference.InferenceRequest
import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.skills.SkillRegistry
import com.vadymsidorov.yobot.core.state.RobotState
import com.vadymsidorov.yobot.core.state.ThinkTrigger
import com.vadymsidorov.yobot.core.telemetry.YobotJson
import java.time.ZoneId

/**
 * Pure translation between robot state and the language model: [compose] builds a request,
 * [interpret] reads a reply. Memory is appended via [remember] from the Executive thread only.
 */
class Cognition(
    private val character: Character,
    private val skills: SkillRegistry,
    private val memory: ConversationMemory = ConversationMemory(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val parser = ResponseParser(skills)

    val systemPrompt: String by lazy { buildSystemPrompt() }

    fun compose(state: RobotState, trigger: ThinkTrigger, nowMs: Long): InferenceRequest {
        val situation = Situation.of(state, trigger, nowMs, zone)
        val messages = buildList {
            add(ChatMessage.system(systemPrompt))
            addAll(memory.messages())
            add(ChatMessage.user(YobotJson.encodeToString(Situation.serializer(), situation)))
        }
        return InferenceRequest(messages = messages)
    }

    fun interpret(text: String): Intent = parser.parse(text)

    fun remember(situation: String, intent: Intent) {
        memory.append(situation, YobotJson.encodeToString(Intent.serializer(), intent))
    }

    private fun buildSystemPrompt(): String = buildString {
        appendLine(character.persona)
        appendLine()
        appendLine("Rules:")
        character.rules.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Each user message is a JSON snapshot of your situation, not words someone said to you;")
        appendLine("only the `heard` entries are speech, with how many seconds ago it was heard.")
        appendLine()
        appendLine("Skills you may request in `actions` (JSON Schema for args):")
        appendLine(skills.catalogue().toString())
        appendLine()
        appendLine("Reply with exactly one JSON object and nothing else:")
        appendLine("""{"speech": string or null, "expression": one of ${Expression.entries.joinToString("|")}, "actions": [{"skill": name, "args": {...}}], "thought": short private note or null}""")
    }
}
