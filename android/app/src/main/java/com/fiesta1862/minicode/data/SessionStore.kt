package com.fiesta1862.minicode.data

import android.content.Context
import com.fiesta1862.minicode.llm.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionStore(context: Context) {
    private val file = File(context.filesDir, "session.json")

    fun load(): MutableList<ChatMessage> {
        if (!file.isFile) return mutableListOf()
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("messages") ?: return mutableListOf()
            val messages = mutableListOf<ChatMessage>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                ChatMessage.fromJson(item)?.let { messages += it }
            }
            messages
        }.getOrElse { mutableListOf() }
    }

    fun save(messages: List<ChatMessage>) {
        runCatching {
            val trimmed = messages.takeLast(200).map { message ->
                message.copy(content = message.content.take(120_000))
            }
            val root = JSONObject().apply {
                put("version", 1)
                put(
                    "messages",
                    JSONArray().apply {
                        trimmed.forEach { put(it.toJson()) }
                    },
                )
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                file.writeText(root.toString())
                temp.delete()
            }
        }
    }

    fun clear() {
        file.delete()
    }
}
