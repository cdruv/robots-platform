package com.vadymsidorov.yobot.core.inference

import kotlinx.serialization.Serializable

/** Provider seam for manual requests. Nothing schedules or calls it automatically. */
class Inference(private val backend: InferenceBackend = StubInferenceBackend) {
    suspend fun complete(request: InferenceRequest): InferenceResponse = backend.complete(request)

    val backendName: String get() = backend.name
}

/** Implement this interface to connect a model provider in a later iteration. */
interface InferenceBackend {
    val name: String
    suspend fun complete(request: InferenceRequest): InferenceResponse
}

object StubInferenceBackend : InferenceBackend {
    override val name = "unconfigured"

    override suspend fun complete(request: InferenceRequest): InferenceResponse =
        throw InferenceException("No inference provider is connected")
}

class InferenceException(message: String, val status: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

@Serializable
data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
    }
}

/** A future provider determines whether an explicit [model] is required. */
@Serializable
data class InferenceRequest(
    val messages: List<ChatMessage>,
    val model: String? = null,
    val temperature: Double = 0.8,
    val jsonMode: Boolean = false,
)

@Serializable
data class Usage(val promptTokens: Int, val completionTokens: Int)

@Serializable
data class InferenceResponse(
    val text: String,
    val usage: Usage? = null,
    val latencyMs: Long = 0,
)
