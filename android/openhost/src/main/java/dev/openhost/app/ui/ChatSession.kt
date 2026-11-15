package dev.openhost.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.openhost.app.agent.Agent
import dev.openhost.app.agent.AgentEvent
import dev.openhost.app.agent.ChatMessage
import dev.openhost.app.agent.OpenAiClient
import dev.openhost.app.agent.Role
import dev.openhost.app.agent.ToolCall
import dev.openhost.app.agent.systemPrompt
import dev.openhost.app.data.AppSettings
import dev.openhost.app.tools.ToolRegistry
import dev.openhost.app.tools.WorkspaceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class ChatRole { User, Agent, Tool }

data class ChatTurn(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val isError: Boolean = false,
)

data class PendingApproval(
    val call: ToolCall,
    val preview: String,
    val deferred: CompletableDeferred<Boolean>,
)

object ChatSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OpenAiClient()
    private val history = mutableListOf<ChatMessage>()
    private var runJob: Job? = null
    private var nextId = 1L
    private var generation = 0L

    val turns = mutableStateListOf<ChatTurn>()

    var isRunning by mutableStateOf(false)
        private set

    var streamingText by mutableStateOf("")
        private set

    var pendingApproval by mutableStateOf<PendingApproval?>(null)
        private set

    fun send(context: Context, text: String, settings: AppSettings) {
        if (isRunning || text.isBlank()) return
        sanitizeHistory()

        turns += ChatTurn(nextId++, ChatRole.User, text.trim())
        isRunning = true
        streamingText = ""
        val myGeneration = ++generation

        runJob = scope.launch {
            try {
                val workspace = WorkspaceFactory.create(context.applicationContext, settings)
                val registry = ToolRegistry(workspace)
                val agent = Agent(
                    client = client,
                    tools = { registry },
                    settings = { settings },
                    systemPrompt = {
                        systemPrompt(workspace?.displayName ?: "app storage", workspace != null)
                    },
                    approve = { call, _ ->
                        if (settings.autoApprove) {
                            true
                        } else {
                            val deferred = CompletableDeferred<Boolean>()
                            pendingApproval = PendingApproval(
                                call = call,
                                preview = ToolRegistry.approvalPreview(call),
                                deferred = deferred,
                            )
                            val allowed = deferred.await()
                            pendingApproval = null
                            allowed
                        }
                    },
                )

                agent.run(history, text.trim()).collect { event ->
                    if (generation != myGeneration) return@collect
                    when (event) {
                        is AgentEvent.TextDelta -> streamingText += event.text
                        is AgentEvent.AssistantTurn -> {
                            val content = event.message.content.trim()
                            if (content.isNotEmpty()) {
                                turns += ChatTurn(nextId++, ChatRole.Agent, content)
                            }
                            streamingText = ""
                        }
                        is AgentEvent.ToolStarted -> Unit
                        is AgentEvent.ToolFinished -> {
                            val result = event.result
                            val details = result.output.ifBlank { result.summary }
                            turns += ChatTurn(
                                id = nextId++,
                                role = ChatRole.Tool,
                                text = "${event.call.name} - ${result.summary}\n\n$details",
                                isError = result.isError,
                            )
                        }
                        is AgentEvent.ToolDenied -> turns += ChatTurn(
                            id = nextId++,
                            role = ChatRole.Tool,
                            text = "${event.call.name} - denied by you",
                            isError = true,
                        )
                        is AgentEvent.StepLimit -> turns += ChatTurn(
                            id = nextId++,
                            role = ChatRole.Agent,
                            text = "Stopped: reached the step limit (${settings.maxSteps}).",
                            isError = true,
                        )
                        is AgentEvent.Done -> Unit
                    }
                }
            } catch (_: CancellationException) {
                // User stopped the run.
            } catch (e: Exception) {
                if (generation == myGeneration) {
                    val message = e.message ?: e.javaClass.simpleName
                    turns += ChatTurn(nextId++, ChatRole.Agent, "Error: $message", isError = true)
                }
            } finally {
                if (generation == myGeneration) {
                    sanitizeHistory()
                    streamingText = ""
                    pendingApproval = null
                    isRunning = false
                    runJob = null
                }
            }
        }
    }

    fun stop() {
        generation++
        pendingApproval?.deferred?.complete(false)
        pendingApproval = null
        runJob?.cancel()
        runJob = null
        isRunning = false
        streamingText = ""
    }

    fun newChat() {
        stop()
        turns.clear()
        history.clear()
    }

    fun resolveApproval(allow: Boolean) {
        pendingApproval?.deferred?.complete(allow)
        pendingApproval = null
    }

    private fun sanitizeHistory() {
        val answered = history
            .filter { it.role == Role.TOOL }
            .mapNotNull { it.toolCallId }
            .toSet()
        val keptToolIds = history
            .filter { message ->
                message.role == Role.ASSISTANT &&
                    message.toolCalls.all { call -> call.id in answered }
            }
            .flatMap { message -> message.toolCalls.map { it.id } }
            .toSet()
        val cleaned = history.filter { message ->
            when (message.role) {
                Role.ASSISTANT -> message.toolCalls.all { call -> call.id in answered }
                Role.TOOL -> message.toolCallId == null || message.toolCallId in keptToolIds
                else -> true
            }
        }
        if (cleaned.size != history.size) {
            history.clear()
            history.addAll(cleaned)
        }
    }
}
