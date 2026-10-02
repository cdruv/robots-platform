package com.vadymsidorov.yobot.core.inference

import com.vadymsidorov.yobot.core.telemetry.YobotJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Chat Completions over HTTP, as spoken by OpenAI, OpenRouter, vLLM, llama.cpp, Ollama and others. */
class OpenAiCompatibleBackend(
    private val baseUrl: String,
    private val apiKey: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = defaultClient(),
    override val name: String = "openai-compatible",
) : InferenceBackend {

    override suspend fun complete(request: InferenceRequest): InferenceResponse {
        if (apiKey.isBlank()) throw InferenceException("no API key configured for $name")
        val httpRequest = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(requestBody(request).toString().toRequestBody(JSON))
            .build()
        val call = client.newCall(httpRequest)
        val (code, body) = call.await()
        if (code !in 200..299) throw InferenceException("HTTP $code: ${errorMessage(body)}", status = code)
        return parse(body)
    }

    private fun requestBody(request: InferenceRequest): JsonObject = buildJsonObject {
        put("model", requireNotNull(request.model) { "model not resolved" })
        put("temperature", request.temperature)
        put("messages", buildJsonArray {
            request.messages.forEach { m ->
                add(buildJsonObject {
                    put("role", m.role)
                    put("content", m.content)
                })
            }
        })
        if (request.jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
    }

    private fun parse(body: String): InferenceResponse {
        val root = try {
            YobotJson.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw InferenceException("malformed response body", cause = e)
        }
        val text = runCatching {
            root.getValue("choices").jsonArray[0].jsonObject
                .getValue("message").jsonObject
                .getValue("content").jsonPrimitive.contentOrNull
        }.getOrNull() ?: throw InferenceException("response has no message content")
        val usage = (root["usage"] as? JsonObject)?.let { u ->
            val prompt = u["prompt_tokens"]?.jsonPrimitive?.intOrNull
            val completion = u["completion_tokens"]?.jsonPrimitive?.intOrNull
            if (prompt != null && completion != null) Usage(prompt, completion) else null
        }
        return InferenceResponse(text = text, usage = usage)
    }

    private fun errorMessage(body: String): String = runCatching {
        YobotJson.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
            .getValue("message").jsonPrimitive.content
    }.getOrElse { body.take(200) }

    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(InferenceException("network error: ${e.message}", cause = e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { it.code to it.body.string() } }
                result.fold(cont::resume) {
                    cont.resumeWithException(InferenceException("read error: ${it.message}", cause = it))
                }
            }
        })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** OpenRouter adds app attribution headers on top of the plain protocol. */
        fun openRouter(
            apiKey: String,
            client: OkHttpClient = defaultClient(),
            baseUrl: String = OPENROUTER_BASE_URL,
            appUrl: String = "https://yobot.local",
            appTitle: String = "Yobot",
        ) =
            OpenAiCompatibleBackend(
                baseUrl = baseUrl,
                apiKey = apiKey,
                extraHeaders = mapOf(
                    "HTTP-Referer" to appUrl,
                    "X-Title" to appTitle,
                ),
                client = client,
                name = "openrouter",
            )
    }
}
