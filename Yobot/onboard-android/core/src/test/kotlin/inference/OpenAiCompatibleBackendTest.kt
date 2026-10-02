package com.vadymsidorov.yobot.core.inference

import com.vadymsidorov.yobot.core.telemetry.YobotJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleBackendTest {
    private val server = MockWebServer()
    private val request = InferenceRequest(
        messages = listOf(ChatMessage.system("sys"), ChatMessage.user("hi")),
        model = "test/model",
        temperature = 0.5,
    )

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun backend(key: String = "secret") =
        OpenAiCompatibleBackend.openRouter(apiKey = key, baseUrl = server.url("/api/v1").toString())

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun sendsHeadersAndBodyAndParsesResponse() = runBlocking {
        respond(200, """{"choices":[{"message":{"role":"assistant","content":"{\"speech\":\"hi\"}"}}],"usage":{"prompt_tokens":12,"completion_tokens":3}}""")

        val response = backend().complete(request)

        assertEquals("{\"speech\":\"hi\"}", response.text)
        assertEquals(Usage(12, 3), response.usage)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer secret", recorded.headers["Authorization"])
        assertEquals("Yobot", recorded.headers["X-Title"])
        assertTrue(recorded.headers["HTTP-Referer"]!!.isNotBlank())

        val body = YobotJson.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("test/model", body["model"]!!.jsonPrimitive.content)
        assertEquals("0.5", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("json_object", (body["response_format"] as JsonObject)["type"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("hi", messages[1]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun mapsHttpErrorsWithProviderMessage() = runBlocking {
        respond(401, """{"error":{"message":"No auth credentials found","code":401}}""")
        val e = expectFailure { backend().complete(request) }
        assertEquals(401, e.status)
        assertTrue(e.message!!.contains("No auth credentials found"))
    }

    @Test
    fun mapsMissingContent() = runBlocking {
        respond(200, """{"choices":[]}""")
        val e = expectFailure { backend().complete(request) }
        assertTrue(e.message!!.contains("no message content"))
    }

    @Test
    fun mapsMalformedBody() = runBlocking {
        respond(200, "<html>gateway</html>")
        expectFailure { backend().complete(request) }
        Unit
    }

    @Test
    fun blankKeyFailsWithoutRequest() = runBlocking {
        expectFailure { backend(key = "").complete(request) }
        assertEquals(0, server.requestCount)
    }

    private suspend fun expectFailure(block: suspend () -> Unit): InferenceException {
        try {
            block()
        } catch (e: InferenceException) {
            return e
        }
        fail("expected InferenceException")
        error("unreachable")
    }
}
