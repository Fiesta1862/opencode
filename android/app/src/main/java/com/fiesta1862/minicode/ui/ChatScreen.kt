package com.fiesta1862.minicode.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatScreen(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        containerColor = MinicodeColors.Background,
        topBar = {
            ChatTopBar(
                state = state,
                onNewChat = onNewChat,
                onOpenSettings = onOpenSettings,
            )
        },
        bottomBar = {
            Composer(
                state = state,
                onInputChange = onInputChange,
                onSend = onSend,
                onStop = onStop,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.isRunning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MinicodeColors.Accent,
                    trackColor = MinicodeColors.Border,
                )
            }
            if (state.items.isEmpty() && state.streamingText.isEmpty()) {
                EmptyState(
                    settings = state.settings,
                    onSuggestion = onInputChange,
                    onOpenSettings = onOpenSettings,
                )
            } else {
                MessageList(state = state)
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    state: ChatUiState,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(color = MinicodeColors.Background) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MinicodeColors.AccentDark),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ">_",
                        color = MinicodeColors.Accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "minicode",
                        color = MinicodeColors.Text,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                    Text(
                        text = if (state.isRunning) {
                            state.status.ifBlank { "Working…" }
                        } else {
                            "${state.workspaceName} · ${state.settings.model}"
                        },
                        color = if (state.isRunning) MinicodeColors.Accent else MinicodeColors.Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onNewChat, enabled = !state.isRunning) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New chat",
                        tint = if (state.isRunning) MinicodeColors.Muted else MinicodeColors.Text,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MinicodeColors.Text,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageList(state: ChatUiState) {
    val listState = rememberLazyListState()
    val streaming = state.streamingText
    val totalRows = state.items.size + if (streaming.isNotEmpty()) 1 else 0

    LaunchedEffect(totalRows, streaming.length) {
        if (totalRows > 0) {
            listState.scrollToItem(totalRows - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(state.items, key = { it.id }) { item ->
            when (item) {
                is ChatItem.User -> UserBubble(item.text)
                is ChatItem.Assistant -> AssistantBubble(item.text)
                is ChatItem.Tool -> ToolCard(item)
                is ChatItem.Notice -> NoticeCard(item)
            }
        }
        if (streaming.isNotEmpty()) {
            item(key = "streaming") {
                AssistantBubble(streaming, streamingCursor = true)
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MinicodeColors.UserBubble,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 330.dp),
        ) {
            Text(
                text = text,
                color = MinicodeColors.Text,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubble(text: String, streamingCursor: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "minicode",
            color = MinicodeColors.Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 5.dp),
        )
        MarkdownText(text = text)
        if (streamingCursor) {
            Text(
                text = "▍",
                color = MinicodeColors.Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ToolCard(item: ChatItem.Tool) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val accent = when {
        item.running -> MinicodeColors.Accent
        item.isError -> MinicodeColors.Danger
        item.result?.startsWith("Denied") == true -> MinicodeColors.Muted
        else -> MinicodeColors.Accent
    }
    val status = when {
        item.running -> "running…"
        item.isError -> "failed"
        item.result?.startsWith("Denied") == true -> "denied"
        else -> "done"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MinicodeColors.Surface)
            .border(1.dp, MinicodeColors.Border, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = MinicodeColors.Accent,
                    strokeWidth = 1.6.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent),
                )
            }
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text = item.call.name,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = status,
                color = MinicodeColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }

        val path = runCatching { item.call.args().optString("path") }.getOrNull()
        if (!path.isNullOrBlank()) {
            Text(
                text = path,
                color = MinicodeColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 17.dp, top = 3.dp),
            )
        }

        if (expanded) {
            val details = buildString {
                if (!path.isNullOrBlank()) {
                    append(path).append("\n\n")
                }
                append(item.result ?: item.call.arguments)
            }.ifBlank { "No output" }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MinicodeColors.Code)
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    text = details,
                    color = if (item.isError) MinicodeColors.Danger else Color(0xFFC9D6E2),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(item: ChatItem.Notice) {
    val color = if (item.isError) MinicodeColors.Danger else MinicodeColors.Muted
    Surface(
        color = if (item.isError) MinicodeColors.DangerDark else MinicodeColors.Surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (item.isError) "!" else "i",
                color = color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.width(18.dp),
            )
            Text(
                text = item.text,
                color = MinicodeColors.Text,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun EmptyState(
    settings: com.fiesta1862.minicode.data.AppSettings,
    onSuggestion: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MinicodeColors.AccentDark),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ">_",
                color = MinicodeColors.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 25.sp,
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "minicode",
            color = MinicodeColors.Text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "An AI coding agent in your pocket.\nReads, writes and edits files in a workspace.\nNo shell — files only.",
            color = MinicodeColors.Muted,
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(26.dp))

        if (settings.apiKey.isBlank() && settings.baseUrl.contains("api.openai.com")) {
            Surface(
                color = MinicodeColors.Surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MinicodeColors.Accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenSettings),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Set your API key to get started",
                        color = MinicodeColors.Accent,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Settings →",
                        color = MinicodeColors.Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        Text(
            text = "TRY",
            color = MinicodeColors.Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp, start = 2.dp),
        )
        Suggestion(
            title = "List the workspace",
            subtitle = "See which files the agent can reach.",
            onClick = { onSuggestion("List the files in my workspace.") },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Suggestion(
            title = "Create a starter README",
            subtitle = "Generate a README.md for the project.",
            onClick = { onSuggestion("Create a README.md that describes this workspace.") },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Suggestion(
            title = "What can you do?",
            subtitle = "Learn the available tools and limits.",
            onClick = { onSuggestion("What can you do, and what are your limits on Android?") },
        )
    }
}

@Composable
private fun Suggestion(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        color = MinicodeColors.Surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MinicodeColors.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                text = title,
                color = MinicodeColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                color = MinicodeColors.Muted,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MinicodeColors.Background) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Ask minicode to change a file…",
                            color = MinicodeColors.Muted,
                            fontSize = 14.sp,
                        )
                    },
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MinicodeColors.Text,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MinicodeColors.Surface,
                        focusedContainerColor = MinicodeColors.Surface,
                        unfocusedBorderColor = MinicodeColors.Border,
                        focusedBorderColor = MinicodeColors.Accent.copy(alpha = 0.7f),
                        cursorColor = MinicodeColors.Accent,
                    ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = if (state.isRunning) onStop else onSend,
                    enabled = state.isRunning || state.input.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (state.isRunning) MinicodeColors.Danger else MinicodeColors.Accent,
                        contentColor = if (state.isRunning) Color.White else Color(0xFF04140A),
                        disabledContainerColor = MinicodeColors.SurfaceHigh,
                        disabledContentColor = MinicodeColors.Muted,
                    ),
                ) {
                    if (state.isRunning) {
                        Box(
                            modifier = Modifier
                                .size(13.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Text(
                text = "files only · no shell · ${state.settings.model}",
                color = MinicodeColors.Muted.copy(alpha = 0.75f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                modifier = Modifier.padding(top = 6.dp, start = 6.dp),
            )
        }
    }
}

@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    onAnswer: (allow: Boolean, alwaysAllow: Boolean) -> Unit,
) {
    val isEdit = request.call.name == "edit_file"
    AlertDialog(
        onDismissRequest = { onAnswer(false, false) },
        containerColor = MinicodeColors.SurfaceHigh,
        titleContentColor = MinicodeColors.Text,
        textContentColor = MinicodeColors.Text,
        title = {
            Column {
                Text(
                    text = if (isEdit) "Apply this edit?" else "Write this file?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = request.call.name,
                    color = MinicodeColors.Accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "minicode wants to modify your workspace.",
                    color = MinicodeColors.Muted,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(MinicodeColors.Code)
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        text = request.preview.ifBlank { request.call.arguments },
                        color = Color(0xFFCFE3D6),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAnswer(true, false) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MinicodeColors.Accent,
                    contentColor = Color(0xFF04140A),
                ),
            ) {
                Text("Allow")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onAnswer(true, true) }) {
                    Text("Always allow", color = MinicodeColors.Accent, fontSize = 12.sp)
                }
                TextButton(onClick = { onAnswer(false, false) }) {
                    Text("Deny", color = MinicodeColors.Muted, fontSize = 12.sp)
                }
            }
        },
    )
}
