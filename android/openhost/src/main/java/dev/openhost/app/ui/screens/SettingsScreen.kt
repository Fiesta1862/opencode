package dev.openhost.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.openhost.app.core.ServerHolder
import dev.openhost.app.data.SettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var saved by remember { mutableStateOf(store.load()) }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var maxSteps by remember { mutableStateOf(saved.maxSteps.toString()) }
    var port by remember { mutableStateOf(saved.serverPort.toString()) }
    var autoApprove by remember { mutableStateOf(saved.autoApprove) }
    var showKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val running = ServerHolder.state.collectAsState().value.running

    fun persist() {
        val portValue = port.toIntOrNull()
        val stepsValue = maxSteps.toIntOrNull()
        val url = baseUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            status = "Base URL must start with http:// or https://"
            return
        }
        if (portValue == null || portValue !in 1024..65535) {
            status = "Port must be between 1024 and 65535"
            return
        }
        if (stepsValue == null || stepsValue !in 1..100) {
            status = "Max steps must be between 1 and 100"
            return
        }
        if (model.isBlank()) {
            status = "Model cannot be empty"
            return
        }
        val next = saved.copy(
            baseUrl = url,
            apiKey = apiKey.trim(),
            model = model.trim(),
            maxSteps = stepsValue,
            autoApprove = autoApprove,
            serverPort = portValue,
        )
        store.save(next)
        saved = next
        baseUrl = next.baseUrl
        status = if (running) "Saved. Restart the server if you changed the port." else "Saved"
    }

    fun copyToClipboard(label: String, value: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, value))
        status = "$label copied"
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val next = store.load().copy(workspaceUri = uri.toString())
            store.save(next)
            saved = next
            status = "Workspace folder selected"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The agent talks to any OpenAI-compatible API. Your key never leaves this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("API base URL") },
            placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            placeholder = { Text("gpt-4o-mini") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = maxSteps,
                onValueChange = { maxSteps = it.filter(Char::isDigit) },
                label = { Text("Max steps") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Server port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-approve file changes", style = MaterialTheme.typography.titleSmall)
                Text(
                    "When off, the agent asks before writing or editing a file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoApprove, onCheckedChange = { autoApprove = it })
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Workspace", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (saved.workspaceUri == null) "App private storage (default)"
                    else "Custom folder selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }
                    if (saved.workspaceUri != null) {
                        TextButton(onClick = {
                            val next = store.load().copy(workspaceUri = null)
                            store.save(next)
                            saved = next
                            status = "Using app private storage"
                        }) { Text("Use app storage") }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Server", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Port ${saved.serverPort} - ${if (running) "running" else "stopped"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Access token", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(
                        saved.serverToken,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { copyToClipboard("Token", saved.serverToken) }) { Text("Copy token") }
                    TextButton(onClick = {
                        val next = store.load().copy(serverToken = SettingsStore.generateToken())
                        store.save(next)
                        saved = next
                        status = "New token generated. Reload the web page to reconnect."
                    }) { Text("Regenerate") }
                }
            }
        }

        Button(onClick = { persist() }, modifier = Modifier.fillMaxWidth()) {
            Text("Save settings")
        }
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            "Tip: change the port here, then stop and start the server from the Server tab. " +
                "For 24/7 uptime, set OpenHost battery usage to Unrestricted in Android settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
