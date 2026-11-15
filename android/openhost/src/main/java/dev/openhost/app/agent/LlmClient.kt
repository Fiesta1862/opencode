package dev.openhost.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LlmException(message: String) : Exception(message)

interface LlmClient {
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

class OpenAiClient(
    private val http: OkHttpClient = defaultClient(),
) : LlmClient {

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val url = endpoint(request.baseUrl)
        val payload = buildRequest(request)
        val httpRequest = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Accept", "text/event-stream")
            .header("User-Agent", "openhost-android/0.3.0")
            .apply {
                if (request.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${request.apiKey}")
                }
            }
            .build()

        val call = http.newCall(httpRequest)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        suspend fun emitMessage(message: JSONObject, finishReason: String) {
            val content = if (message.has("content") && !message.isNull("content")) {
                message.optString("content")
            } else ""
            if (content.isNotEmpty()) emit(LlmEvent.TextDelta(content))

            message.optJSONArray("tool_calls")?.let { calls ->
                for (i in 0 until calls.length()) {
                    val item = calls.optJSONObject(i) ?: continue
                    val function = item.optJSONObject("function") ?: continue
                    val rawArguments = function.opt("arguments")
                    val arguments = when (rawArguments) {
                        null, JSONObject.NULL -> null
                        is String -> rawArguments
                        else -> rawArguments.toString()
                    }
                    emit(
                        LlmEvent.ToolCallDelta(
                            index = item.optInt("index", i),
                            id = item.optString("id").takeIf { it.isNotBlank() },
                            name = function.optString("name").takeIf { it.isNotBlank() },
                            arguments = arguments,
                        ),
                    )
                }
            }
            if (finishReason.isNotBlank() && finishReason != "null") {
                emit(LlmEvent.Finished(finishReason))
            }
        }

        suspend fun emitChunk(json: JSONObject) {
            json.optJSONObject("error")?.let { error ->
                throw LlmException(error.optString("message", "The server returned an error"))
            }
            val choices = json.optJSONArray("choices") ?: return
            for (i in 0 until choices.length()) {
                val choice = choices.optJSONObject(i) ?: continue
                val delta = choice.optJSONObject("delta")
                if (delta == null) {
                    choice.optJSONObject("message")?.let {
                        emitMessage(it, choice.optString("finish_reason"))
                    }
                    continue
                }
                if (delta.has("content") && !delta.isNull("content")) {
                    val text = delta.optString("content")
                    if (text.isNotEmpty()) emit(LlmEvent.TextDelta(text))
                }
                delta.optJSONArray("tool_calls")?.let { calls ->
                    for (ci in 0 until calls.length()) {
                        val item = calls.optJSONObject(ci) ?: continue
                        val function = item.optJSONObject("function")
                        emit(
                            LlmEvent.ToolCallDelta(
                                index = item.optInt("index", ci),
                                id = item.optString("id").takeIf { it.isNotBlank() },
                                name = function?.optString("name")?.takeIf { it.isNotBlank() },
                                arguments = function?.let { fn ->
                                    if (fn.has("arguments") && !fn.isNull("arguments")) {
                                        val raw = fn.opt("arguments")
                                        if (raw is String) raw else raw?.toString()
                                    } else null
                                },
                            ),
                        )
                    }
                }
                val finish = choice.optString("finish_reason")
                if (finish.isNotBlank() && finish != "null") emit(LlmEvent.Finished(finish))
            }
        }

        suspend fun emitFull(raw: String) {
            val root = runCatching { JSONObject(raw) }.getOrElse {
                throw LlmException("The server returned invalid JSON")
            }
            root.optJSONObject("error")?.let { error ->
                throw LlmException(error.optString("message", "The server returned an error"))
            }
            val choices = root.optJSONArray("choices")
                ?: throw LlmException("The server response has no choices")
            val choice = choices.optJSONObject(0)
                ?: throw LlmException("The server response has no choices")
            val message = choice.optJSONObject("message")
                ?: throw LlmException("The server response has no message")
            emitMessage(message, choice.optString("finish_reason", "stop"))
        }

        suspend fun parseSse(source: BufferedSource) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                emitChunk(json)
            }
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty().take(4000)
                    throw LlmException("HTTP ${response.code}${if (body.isBlank()) "" else ": $body"}")
                }
                val body = response.body ?: throw LlmException("The server returned an empty response")
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.contains("application/json", ignoreCase = true)) {
                    emitFull(body.string())
                } else {
                    parseSse(body.source())
                }
            }
        } finally {
            cancellation?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) throw LlmException("Base URL is empty")
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw LlmException("Base URL must start with http:// or https://")
        }
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun buildRequest(request: LlmRequest): JSONObject = JSONObject().apply {
        put("model", request.model)
        put(
            "messages",
            JSONArray().apply {
                request.messages.forEach { put(it.toJson()) }
            },
        )
        put("stream", true)
        put("temperature", request.temperature)
        if (request.tools.isNotEmpty()) {
            put(
                "tools",
                JSONArray().apply {
                    request.tools.forEach { put(it.asApiJson()) }
                },
            )
            put("tool_choice", "auto")
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
