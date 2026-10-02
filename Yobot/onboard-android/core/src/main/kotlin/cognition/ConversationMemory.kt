package com.vadymsidorov.yobot.core.cognition

import com.vadymsidorov.yobot.core.inference.ChatMessage

/**
 * Bounded rolling window of past exchanges (situation in, reply out).
 * Not thread-safe: only the Executive thread touches it.
 */
class ConversationMemory(private val maxTurns: Int = 12) {
    private val turns = ArrayDeque<Pair<String, String>>()

    fun append(user: String, assistant: String) {
        turns.addLast(user to assistant)
        while (turns.size > maxTurns) turns.removeFirst()
    }

    fun messages(): List<ChatMessage> = turns.flatMap { (u, a) -> listOf(ChatMessage.user(u), ChatMessage.assistant(a)) }

    val size: Int get() = turns.size

    fun clear() = turns.clear()
}
