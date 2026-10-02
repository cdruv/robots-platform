package com.vadymsidorov.yobot.core.inference

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
    }
}

/** [model] null means the [Inference] manager's default. */
@Serializable
data class InferenceRequest(
    val messages: List<ChatMessage>,
    val model: String? = null,
    val temperature: Double = 0.8,
    val jsonMode: Boolean = true,
)

@Serializable
data class Usage(val promptTokens: Int, val completionTokens: Int)

@Serializable
data class InferenceResponse(
    val text: String,
    val usage: Usage? = null,
    val latencyMs: Long = 0,
)
