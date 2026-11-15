package dev.openhost.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.openhost.app.data.SettingsStore
import dev.openhost.app.ui.ChatRole
import dev.openhost.app.ui.ChatSession
import dev.openhost.app.ui.ChatTurn
import dev.openhost.app.ui.components.ApprovalDialog

@Composable
fun ChatScreen(onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var settings by remember { mutableStateOf(store.load()) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { settings = store.load() }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || ChatSession.isRunning) return
        val latest = store.load()
        settings = latest
        if (latest.apiKey.isBlank() || latest.baseUrl.isBlank()) return
        input = ""
        ChatSession.send(context, text, latest)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Chat", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (ChatSession.isRunning) "Working..." else "Agent on this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { ChatSession.newChat() },
                enabled = !ChatSession.isRunning,
            ) { Text("New") }
        }

        if (settings.apiKey.isBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("API key missing", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Add your OpenAI-compatible API key in Settings before chatting.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ChatSession.turns.isEmpty() && ChatSession.streamingText.isBlank()) {
                item(key = "intro") {
                    Text(
                        "Ask the agent to read, search, or edit files in its workspace. " +
                            "File changes ask for your approval first unless auto-approve is on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ChatSession.turns, key = { it.id }) { turn ->
                MessageBubble(turn)
            }
            if (ChatSession.streamingText.isNotBlank()) {
                item(key = "streaming") {
                    MessageBubble(
                        ChatTurn(-1L, ChatRole.Agent, ChatSession.streamingText),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message the agent...") },
                maxLines = 4,
                enabled = !ChatSession.isRunning,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (ChatSession.isRunning) ChatSession.stop() else submit()
                },
                enabled = ChatSession.isRunning || input.isNotBlank(),
                modifier = Modifier.height(56.dp),
            ) {
                Text(if (ChatSession.isRunning) "Stop" else "Send")
            }
        }
    }

    ChatSession.pendingApproval?.let { pending ->
        ApprovalDialog(
            call = pending.call,
            preview = pending.preview,
            onAllow = { ChatSession.resolveApproval(true) },
            onDeny = { ChatSession.resolveApproval(false) },
        )
    }

    LaunchedEffect(ChatSession.turns.size, ChatSession.streamingText.length) {
        val count = ChatSession.turns.size + if (ChatSession.streamingText.isNotBlank()) 1 else 0
        if (count > 0) listState.scrollToItem(count - 1)
    }
}

@Composable
private fun MessageBubble(turn: ChatTurn) {
    val isUser = turn.role == ChatRole.User
    val isTool = turn.role == ChatRole.Tool
    val colors = when {
        turn.isError -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        isTool -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = colors.first,
            contentColor = colors.second,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (isTool) {
                    Text("TOOL", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    text = if (turn.text.length > 6000) turn.text.take(6000) + "\n... (truncated)" else turn.text,
                    style = if (isTool) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                )
            }
        }
    }
}
