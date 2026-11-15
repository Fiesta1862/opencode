package com.fiesta1862.minicode.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fiesta1862.minicode.BuildConfig
import com.fiesta1862.minicode.agent.Agent
import com.fiesta1862.minicode.agent.AgentEvent
import com.fiesta1862.minicode.agent.systemPrompt
import com.fiesta1862.minicode.data.AppSettings
import com.fiesta1862.minicode.data.SessionStore
import com.fiesta1862.minicode.data.SettingsStore
import com.fiesta1862.minicode.llm.ChatMessage
import com.fiesta1862.minicode.llm.OpenAiClient
import com.fiesta1862.minicode.llm.Role
import com.fiesta1862.minicode.llm.ToolCall
import com.fiesta1862.minicode.llm.ToolSpec
import com.fiesta1862.minicode.tools.ToolRegistry
import com.fiesta1862.minicode.workspace.LocalWorkspace
import com.fiesta1862.minicode.workspace.Workspace
import com.fiesta1862.minicode.workspace.WorkspaceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface ChatItem {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatItem

    data class Assistant(override val id: Long, val text: String) : ChatItem

    data class Tool(
        override val id: Long,
        val call: ToolCall,
        val result: String? = null,
        val isError: Boolean = false,
        val running: Boolean = true,
    ) : ChatItem

    data class Notice(
        override val id: Long,
        val text: String,
        val isError: Boolean = true,
    ) : ChatItem
}

data class ApprovalRequest(
    val call: ToolCall,
    val spec: ToolSpec,
    val preview: String,
)

data class ChatUiState(
    val items: List<ChatItem> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val workspaceName: String = "app storage",
    val input: String = "",
    val isRunning: Boolean = false,
    val streamingText: String = "",
    val status: String = "",
    val approval: ApprovalRequest? = null,
    val showSettings: Boolean = false,
    val settingsError: String? = null,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val sessionStore = SessionStore(application)
    private val client = OpenAiClient()

    private var settings: AppSettings = settingsStore.load()
    private var workspace: Workspace? = null
    private var history = mutableListOf<ChatMessage>()
    private var runJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private var nextId = 1L

    private val _state = MutableStateFlow(ChatUiState(settings = settings))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        history = sessionStore.load()
        rebuildWorkspace(reportError = false)
        _state.update {
            it.copy(
                items = itemsFromHistory(history),
                workspaceName = workspace?.displayName ?: "unavailable",
            )
        }
    }

    // ---------------------------------------------------------------- composing

    fun updateInput(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.isRunning) return
        _state.update { it.copy(input = "") }
        startAgent(text)
    }

    fun runDebugPrompt(text: String) {
        if (!BuildConfig.DEBUG || text.isBlank() || _state.value.isRunning) return
        viewModelScope.launch {
            delay(700)
            startAgent(text)
        }
    }

    private fun startAgent(text: String) {
        if (_state.value.isRunning) return
        val userItem = ChatItem.User(nextId++, text)
        _state.update {
            it.copy(
                items = it.items + userItem,
                isRunning = true,
                streamingText = "",
                status = "Thinking…",
                approval = null,
            )
        }

        val agent = Agent(
            client = client,
            tools = { ToolRegistry(workspace) },
            settings = { settings },
            systemPrompt = {
                systemPrompt(
                    workspaceName = workspace?.displayName ?: "app storage",
                    hasWorkspace = workspace != null,
                )
            },
            approve = { call, spec -> requestApproval(call, spec) },
        )

        runJob = viewModelScope.launch {
            var sawError = false
            try {
                agent.run(history, text).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            _state.update {
                                it.copy(streamingText = it.streamingText + event.text, status = "Responding…")
                            }
                        }

                        is AgentEvent.AssistantTurn -> {
                            val message = event.message
                            _state.update { current ->
                                var items = current.items
                                if (message.content.isNotBlank()) {
                                    items = items + ChatItem.Assistant(nextId++, message.content)
                                }
                                message.toolCalls.forEach { call ->
                                    items = items + ChatItem.Tool(id = nextId++, call = call, running = true)
                                }
                                current.copy(items = items, streamingText = "", status = "Working…")
                            }
                            sessionStore.save(history)
                        }

                        is AgentEvent.ToolStarted -> {
                            _state.update { it.copy(status = "Running ${event.call.name}…") }
                        }

                        is AgentEvent.ToolFinished -> {
                            updateTool(event.call, event.result.output, event.result.isError, running = false)
                            sessionStore.save(history)
                        }

                        is AgentEvent.ToolDenied -> {
                            updateTool(
                                event.call,
                                "Denied by the user. The agent was asked not to retry without permission.",
                                isError = false,
                                running = false,
                            )
                            sessionStore.save(history)
                        }

                        is AgentEvent.StepLimit -> {
                            _state.update { current ->
                                current.copy(
                                    items = current.items + ChatItem.Notice(
                                        id = nextId++,
                                        text = "Step limit reached (${settings.maxSteps}). Send another message to continue.",
                                        isError = false,
                                    ),
                                    status = "",
                                )
                            }
                            sessionStore.save(history)
                        }

                        is AgentEvent.Done -> {
                            _state.update { it.copy(status = "") }
                            sessionStore.save(history)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                sawError = true
                _state.update { current ->
                    current.copy(
                        items = current.items + ChatItem.Notice(
                            id = nextId++,
                            text = friendlyError(error),
                        ),
                        status = "",
                    )
                }
            } finally {
                approvalDeferred?.complete(false)
                approvalDeferred = null
                _state.update {
                    it.copy(
                        isRunning = false,
                        streamingText = "",
                        approval = null,
                        status = "",
                    )
                }
                if (!sawError) sessionStore.save(history)
            }
        }
    }

    fun stop() {
        approvalDeferred?.complete(false)
        runJob?.cancel()
        runJob = null
        _state.update { it.copy(isRunning = false, streamingText = "", approval = null, status = "") }
        sessionStore.save(history)
    }

    private suspend fun requestApproval(call: ToolCall, spec: ToolSpec): Boolean {
        if (settings.autoApprove) return true
        val deferred = CompletableDeferred<Boolean>()
        approvalDeferred = deferred
        _state.update {
            it.copy(
                approval = ApprovalRequest(
                    call = call,
                    spec = spec,
                    preview = ToolRegistry.approvalPreview(call),
                ),
            )
        }
        return try {
            deferred.await()
        } finally {
            approvalDeferred = null
            _state.update { it.copy(approval = null) }
        }
    }

    fun answerApproval(allow: Boolean, alwaysAllow: Boolean = false) {
        if (alwaysAllow) {
            settings = settings.copy(autoApprove = true)
            settingsStore.save(settings)
            _state.update { it.copy(settings = settings) }
        }
        approvalDeferred?.complete(allow || alwaysAllow)
    }

    private fun updateTool(call: ToolCall, output: String, isError: Boolean, running: Boolean) {
        _state.update { current ->
            val items = current.items.toMutableList()
            val index = items.indexOfLast { it is ChatItem.Tool && it.call.id == call.id }
            if (index >= 0) {
                val existing = items[index] as ChatItem.Tool
                items[index] = existing.copy(
                    result = output.take(6000),
                    isError = isError,
                    running = running,
                )
            }
            current.copy(items = items, status = if (running) current.status else "Thinking…")
        }
    }

    // ---------------------------------------------------------------- sessions

    fun newChat() {
        stop()
        history.clear()
        sessionStore.clear()
        nextId = 1
        _state.update {
            it.copy(
                items = emptyList(),
                streamingText = "",
                input = "",
                status = "",
                approval = null,
            )
        }
    }

    // ---------------------------------------------------------------- settings

    fun openSettings() {
        _state.update { it.copy(showSettings = true, settingsError = null) }
    }

    fun closeSettings() {
        _state.update { it.copy(showSettings = false, settingsError = null) }
    }

    fun saveSettings(
        baseUrl: String,
        apiKey: String,
        model: String,
        maxSteps: Int,
        autoApprove: Boolean,
    ): Boolean {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        val normalizedModel = model.trim()
        val error = when {
            normalizedUrl.isBlank() -> "Base URL is required"
            !normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://") ->
                "Base URL must start with http:// or https://"
            normalizedModel.isBlank() -> "Model name is required"
            maxSteps !in 1..100 -> "Max steps must be between 1 and 100"
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(settingsError = error) }
            return false
        }

        settings = settings.copy(
            baseUrl = normalizedUrl,
            apiKey = apiKey.trim(),
            model = normalizedModel,
            maxSteps = maxSteps,
            autoApprove = autoApprove,
        )
        settingsStore.save(settings)
        _state.update {
            it.copy(settings = settings, showSettings = false, settingsError = null)
        }
        return true
    }

    fun setWorkspaceUri(uri: Uri?) {
        val newSettings = settings.copy(workspaceUri = uri?.toString())
        settings = newSettings
        settingsStore.save(newSettings)
        rebuildWorkspace(reportError = true)
        _state.update {
            it.copy(settings = newSettings, workspaceName = workspace?.displayName ?: "unavailable")
        }
    }

    fun clearSettingsError() {
        _state.update { it.copy(settingsError = null) }
    }

    private fun rebuildWorkspace(reportError: Boolean) {
        val application = getApplication<Application>()
        workspace = try {
            WorkspaceFactory.create(application, settings)
        } catch (error: Exception) {
            if (reportError) {
                _state.update { current ->
                    current.copy(
                        items = current.items + ChatItem.Notice(
                            id = nextId++,
                            text = "Could not open the selected folder: ${error.message}. Using app storage instead.",
                        ),
                    )
                }
            }
            LocalWorkspace(File(application.filesDir, "workspace"), displayName = "app storage")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun itemsFromHistory(messages: List<ChatMessage>): List<ChatItem> {
        val items = mutableListOf<ChatItem>()
        val pendingToolIndices = mutableMapOf<String, Int>()
        for (message in messages) {
            when (message.role) {
                Role.USER -> {
                    if (message.content.isNotBlank()) {
                        items += ChatItem.User(nextId++, message.content)
                    }
                }

                Role.ASSISTANT -> {
                    if (message.content.isNotBlank()) {
                        items += ChatItem.Assistant(nextId++, message.content)
                    }
                    message.toolCalls.forEach { call ->
                        pendingToolIndices[call.id] = items.size
                        items += ChatItem.Tool(id = nextId++, call = call, running = false)
                    }
                }

                Role.TOOL -> {
                    val toolCallId = message.toolCallId
                    val index = toolCallId?.let { pendingToolIndices.remove(it) }
                    val isError = message.content.startsWith("ERROR:")
                    val text = message.content.removePrefix("ERROR:")
                    if (index != null && index < items.size) {
                        val tool = items[index] as ChatItem.Tool
                        items[index] = tool.copy(
                            result = text.trim(),
                            isError = isError,
                            running = false,
                        )
                    }
                }

                Role.SYSTEM -> Unit
            }
        }
        return items
    }

    private fun friendlyError(error: Exception): String {
        val message = error.message ?: error.javaClass.simpleName
        return when {
            message.contains("HTTP 401") ->
                "Authentication failed. Check your API key in Settings.\n\n$message"
            message.contains("HTTP 404") ->
                "The endpoint was not found. Check the base URL and model name.\n\n$message"
            message.contains("Failed to connect") || message.contains("Connection refused") ->
                "Could not reach the server. Check the base URL and your internet connection.\n\n$message"
            else -> message
        }
    }

    override fun onCleared() {
        runJob?.cancel()
        super.onCleared()
    }
}
