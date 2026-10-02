package com.vadymsidorov.yobot.core.inference

/** One model provider. Receives requests with [InferenceRequest.model] already resolved. */
interface InferenceBackend {
    val name: String
    suspend fun complete(request: InferenceRequest): InferenceResponse
}

class InferenceException(message: String, val status: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)
