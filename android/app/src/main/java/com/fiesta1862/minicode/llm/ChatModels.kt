package com.fiesta1862.minicode.llm

import org.json.JSONArray
import org.json.JSONObject

enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    fun args(): JSONObject = runCatching {
        JSONObject(arguments.ifBlank { "{}" })
    }.getOrElse { JSONObject() }
}

data class ChatMessage(
    val role: Role,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role.wire)
        put("content", content)
        if (toolCalls.isNotEmpty()) {
            put(
                "tool_calls",
                JSONArray().apply {
                    toolCalls.forEach { call ->
                        put(
                            JSONObject().apply {
                                put("id", call.id)
                                put("type", "function")
                                put(
                                    "function",
                                    JSONObject().apply {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        toolCallId?.let { put("tool_call_id", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): ChatMessage? {
            val role = Role.entries.firstOrNull { it.wire == json.optString("role") } ?: return null
            val calls = mutableListOf<ToolCall>()
            json.optJSONArray("tool_calls")?.let { array ->
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val function = item.optJSONObject("function") ?: continue
                    calls += ToolCall(
                        id = item.optString("id").ifBlank { "call_$index" },
                        name = function.optString("name"),
                        arguments = function.optString("arguments", "{}"),
                    )
                }
            }
            val toolCallId = if (json.has("tool_call_id") && !json.isNull("tool_call_id")) {
                json.optString("tool_call_id").takeIf { it.isNotBlank() }
            } else {
                null
            }
            return ChatMessage(
                role = role,
                content = json.optString("content", ""),
                toolCalls = calls,
                toolCallId = toolCallId,
            )
        }
    }
}

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
    val requiresApproval: Boolean = false,
) {
    fun asApiJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
            },
        )
    }
}

data class LlmRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>,
    val temperature: Double = 0.2,
)

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String?,
    ) : LlmEvent

    data class Finished(val reason: String) : LlmEvent
}
