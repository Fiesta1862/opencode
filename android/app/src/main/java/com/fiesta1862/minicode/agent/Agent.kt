package com.fiesta1862.minicode.agent

import com.fiesta1862.minicode.data.AppSettings
import com.fiesta1862.minicode.llm.ChatMessage
import com.fiesta1862.minicode.llm.LlmClient
import com.fiesta1862.minicode.llm.LlmEvent
import com.fiesta1862.minicode.llm.LlmRequest
import com.fiesta1862.minicode.llm.Role
import com.fiesta1862.minicode.llm.ToolCall
import com.fiesta1862.minicode.llm.ToolSpec
import com.fiesta1862.minicode.tools.ToolRegistry
import com.fiesta1862.minicode.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class AssistantTurn(val message: ChatMessage) : AgentEvent
    data class ToolStarted(val call: ToolCall) : AgentEvent
    data class ToolFinished(val call: ToolCall, val result: ToolResult) : AgentEvent
    data class ToolDenied(val call: ToolCall) : AgentEvent
    data object StepLimit : AgentEvent
    data class Done(val steps: Int) : AgentEvent
}

class Agent(
    private val client: LlmClient,
    private val tools: () -> ToolRegistry,
    private val settings: () -> AppSettings,
    private val systemPrompt: () -> String,
    private val approve: suspend (ToolCall, ToolSpec) -> Boolean,
) {
    fun run(history: MutableList<ChatMessage>, userText: String): Flow<AgentEvent> = flow {
        history += ChatMessage(Role.USER, userText)
        var steps = 0

        while (true) {
            val currentSettings = settings()
            if (steps >= currentSettings.maxSteps) {
                emit(AgentEvent.StepLimit)
                return@flow
            }
            steps++

            val registry = tools()
            val messages = buildList {
                add(ChatMessage(Role.SYSTEM, systemPrompt()))
                addAll(history)
            }
            val request = LlmRequest(
                baseUrl = currentSettings.baseUrl,
                apiKey = currentSettings.apiKey,
                model = currentSettings.model,
                messages = messages,
                tools = registry.specs,
            )

            val text = StringBuilder()
            val accumulator = ToolCallAccumulator()
            client.stream(request).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> {
                        text.append(event.text)
                        emit(AgentEvent.TextDelta(event.text))
                    }

                    is LlmEvent.ToolCallDelta -> accumulator.add(event)
                    is LlmEvent.Finished -> Unit
                }
            }

            val calls = accumulator.build()
            val assistantMessage = ChatMessage(
                role = Role.ASSISTANT,
                content = text.toString(),
                toolCalls = calls,
            )
            history += assistantMessage
            emit(AgentEvent.AssistantTurn(assistantMessage))

            if (calls.isEmpty()) {
                emit(AgentEvent.Done(steps))
                return@flow
            }

            for (call in calls) {
                emit(AgentEvent.ToolStarted(call))
                val spec = registry.specs.firstOrNull { it.name == call.name }

                if (spec == null) {
                    val result = ToolResult(
                        summary = "unknown tool",
                        output = "Unknown tool: ${call.name}",
                        isError = true,
                    )
                    history += ChatMessage(
                        role = Role.TOOL,
                        content = "ERROR: ${result.output}",
                        toolCallId = call.id,
                    )
                    emit(AgentEvent.ToolFinished(call, result))
                    continue
                }

                if (spec.requiresApproval && !currentSettings.autoApprove) {
                    if (!approve(call, spec)) {
                        history += ChatMessage(
                            role = Role.TOOL,
                            content = "User denied this action. Do not retry it without asking.",
                            toolCallId = call.id,
                        )
                        emit(AgentEvent.ToolDenied(call))
                        continue
                    }
                }

                val result = registry.execute(call)
                history += ChatMessage(
                    role = Role.TOOL,
                    content = if (result.isError) "ERROR: ${result.output}" else result.output,
                    toolCallId = call.id,
                )
                emit(AgentEvent.ToolFinished(call, result))
            }
        }
    }
}

private class CallBuilder {
    var id: String? = null
    var name: String? = null
    val arguments = StringBuilder()
}

class ToolCallAccumulator {
    private val builders = sortedMapOf<Int, CallBuilder>()

    fun add(event: LlmEvent.ToolCallDelta) {
        val builder = builders.getOrPut(event.index) { CallBuilder() }
        if (!event.id.isNullOrBlank()) builder.id = event.id
        if (!event.name.isNullOrBlank()) builder.name = event.name
        if (!event.arguments.isNullOrEmpty()) builder.arguments.append(event.arguments)
    }

    fun build(): List<ToolCall> = builders.map { (index, builder) ->
        ToolCall(
            id = builder.id ?: "call_$index",
            name = builder.name.orEmpty(),
            arguments = builder.arguments.toString().ifBlank { "{}" },
        )
    }
}
