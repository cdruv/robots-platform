package com.vadymsidorov.walky.core

import com.vadymsidorov.walky.core.inference.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InferenceTest {
    @Test
    fun unconfiguredInferenceReportsUnavailable() = runTest {
        val result = runCatching { Inference().complete(InferenceRequest(emptyList())) }
        assertTrue(result.exceptionOrNull() is InferenceException)
        assertEquals("No inference provider is connected", result.exceptionOrNull()?.message)
    }

    @Test
    fun futureProviderCanBeConnectedWithoutAutomaticRequests() = runTest {
        val requests = mutableListOf<InferenceRequest>()
        val backend = object : InferenceBackend {
            override val name = "test"
            override suspend fun complete(request: InferenceRequest): InferenceResponse {
                requests += request
                return InferenceResponse("connected")
            }
        }
        val inference = Inference(backend)
        assertTrue(requests.isEmpty())
        val request = InferenceRequest(listOf(ChatMessage.user("hello")), model = "test-model")
        assertEquals("connected", inference.complete(request).text)
        assertEquals(listOf(request), requests)
    }
}
