package com.vadymsidorov.yobot.core.inference

/**
 * Picks the backend and model for a request and times it. Single backend for now;
 * this is the seam for routing work types to different providers later.
 */
class Inference(
    private val backend: InferenceBackend,
    private val defaultModel: String,
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun complete(request: InferenceRequest): InferenceResponse {
        val resolved = request.copy(model = request.model ?: defaultModel)
        val start = monoMs()
        val response = backend.complete(resolved)
        return response.copy(latencyMs = monoMs() - start)
    }

    val model: String get() = defaultModel
    val backendName: String get() = backend.name
}
