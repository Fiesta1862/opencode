package com.fiesta1862.minicode.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fiesta1862.minicode.BuildConfig

@Composable
fun SettingsScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onSave: (baseUrl: String, apiKey: String, model: String, maxSteps: Int, autoApprove: Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onUseAppStorage: () -> Unit,
    onClearChat: () -> Unit,
) {
    BackHandler { onBack() }

    val settings = state.settings
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by rememberSaveable(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var model by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var maxSteps by rememberSaveable(settings.maxSteps) { mutableStateOf(settings.maxSteps.toString()) }
    var autoApprove by rememberSaveable(settings.autoApprove) { mutableStateOf(settings.autoApprove) }
    var showKey by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MinicodeColors.Background,
        topBar = {
            Surface(color = MinicodeColors.Background) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = MinicodeColors.Text,
                            )
                        }
                        Text(
                            text = "Settings",
                            color = MinicodeColors.Text,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                val steps = maxSteps.toIntOrNull() ?: settings.maxSteps
                                onSave(baseUrl, apiKey, model, steps, autoApprove)
                            },
                        ) {
                            Text(
                                text = "Save",
                                color = MinicodeColors.Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.settingsError != null) {
                Surface(
                    color = MinicodeColors.DangerDark,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MinicodeColors.Danger.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                ) {
                    Text(
                        text = state.settingsError,
                        color = MinicodeColors.Danger,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            SettingsSection(title = "MODEL") {
                FieldLabel("BASE URL")
                SettingsField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = "https://api.openai.com/v1",
                )
                Hint("OpenAI-compatible. Local servers can use http:// and a blank API key.")
                Spacer(modifier = Modifier.height(6.dp))
                FieldLabel("API KEY")
                SettingsField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "sk-…",
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password,
                    trailing = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(
                                text = if (showKey) "Hide" else "Show",
                                color = MinicodeColors.Muted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                        }
                    },
                )
                Hint("Stored in this app's private storage on this device. Not synced.")
                Spacer(modifier = Modifier.height(6.dp))
                FieldLabel("MODEL")
                SettingsField(
                    value = model,
                    onValueChange = { model = it },
                    placeholder = "gpt-4o-mini",
                )
            }

            SettingsSection(title = "AGENT") {
                FieldLabel("MAX STEPS PER MESSAGE")
                SettingsField(
                    value = maxSteps,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() } && input.length <= 3) maxSteps = input
                    },
                    placeholder = "20",
                    keyboardType = KeyboardType.Number,
                )
                Hint("Each model response and tool batch counts as one step. 1–100.")
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-approve writes",
                            color = MinicodeColors.Text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Skip the approval dialog for write_file and edit_file.",
                            color = MinicodeColors.Muted,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = autoApprove,
                        onCheckedChange = { autoApprove = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF04140A),
                            checkedTrackColor = MinicodeColors.Accent,
                            uncheckedThumbColor = MinicodeColors.Muted,
                            uncheckedTrackColor = MinicodeColors.SurfaceHigh,
                            uncheckedBorderColor = MinicodeColors.Border,
                        ),
                    )
                }
            }

            SettingsSection(title = "WORKSPACE") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Current",
                            color = MinicodeColors.Muted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                        Text(
                            text = state.workspaceName,
                            color = MinicodeColors.Text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPickFolder,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MinicodeColors.AccentDark,
                            contentColor = MinicodeColors.Accent,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Choose folder", fontSize = 12.sp)
                    }
                    if (settings.workspaceUri != null) {
                        TextButton(onClick = onUseAppStorage) {
                            Text("Use app storage", color = MinicodeColors.Muted, fontSize = 12.sp)
                        }
                    }
                }
                Hint(
                    "The agent can only read and write inside this folder. " +
                        "Without a folder, files live in app-private storage.",
                )
            }

            SettingsSection(title = "ABOUT") {
                AboutRow("Version", BuildConfig.VERSION_NAME)
                AboutRow("Tools", "read · write · edit · list · glob · grep")
                AboutRow("Execution", "No shell, no builds, no tests")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "minicode is an OpenAI-compatible coding agent. " +
                        "Conversation history is kept on this device.",
                    color = MinicodeColors.Muted,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                )
            }

            Surface(
                color = MinicodeColors.Surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MinicodeColors.Danger.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .clickable { confirmClear = true },
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Clear conversation",
                        color = MinicodeColors.Danger,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Deletes the saved chat from this device.",
                        color = MinicodeColors.Muted,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Text(
                text = "minicode for Android",
                color = MinicodeColors.Muted.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = MinicodeColors.SurfaceHigh,
            title = { Text("Clear conversation?", color = MinicodeColors.Text) },
            text = {
                Text(
                    text = "The saved messages will be deleted. Files in the workspace are not touched.",
                    color = MinicodeColors.Muted,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearChat()
                        onBack()
                    },
                ) {
                    Text("Clear", color = MinicodeColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel", color = MinicodeColors.Muted)
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            color = MinicodeColors.Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 3.dp, bottom = 7.dp),
        )
        Surface(
            color = MinicodeColors.Surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MinicodeColors.Border, RoundedCornerShape(14.dp)),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = MinicodeColors.Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.5.sp,
        modifier = Modifier.padding(bottom = 5.dp, start = 1.dp),
    )
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = MinicodeColors.Muted.copy(alpha = 0.6f), fontSize = 13.sp) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MinicodeColors.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        ),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailing,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MinicodeColors.Background,
            focusedContainerColor = MinicodeColors.Background,
            unfocusedBorderColor = MinicodeColors.Border,
            focusedBorderColor = MinicodeColors.Accent.copy(alpha = 0.7f),
            cursorColor = MinicodeColors.Accent,
        ),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = MinicodeColors.Muted.copy(alpha = 0.85f),
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(top = 5.dp, start = 1.dp),
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = MinicodeColors.Muted,
            fontSize = 11.5.sp,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            color = MinicodeColors.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
    }
}
