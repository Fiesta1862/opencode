package dev.openhost.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.openhost.app.core.HostService
import dev.openhost.app.core.ServerHolder
import dev.openhost.app.data.SettingsStore

@Composable
fun HostScreen() {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val settings = remember { settingsStore.load() }
    val state by ServerHolder.state.collectAsState()
    var portText by remember { mutableStateOf(settings.serverPort.toString()) }
    var portError by remember { mutableStateOf("") }

    fun copy(label: String, value: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OpenHost", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Your phone is the server and the agent.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.running) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Server running", style = MaterialTheme.typography.titleMedium)
                    Text("Port ${state.port}", style = MaterialTheme.typography.bodyMedium)

                    UrlRow("On this phone", "http://127.0.0.1:${state.port}/") { copy("URL", it) }
                    if (state.lanIp.isNotBlank()) {
                        UrlRow("Wi-Fi / LAN", "http://${state.lanIp}:${state.port}/") { copy("URL", it) }
                    }
                    if (state.tailscaleIp.isNotBlank()) {
                        UrlRow("Tailscale", "http://${state.tailscaleIp}:${state.port}/") { copy("URL", it) }
                    }
                    if (state.lanIp.isBlank() && state.tailscaleIp.isBlank()) {
                        Text(
                            "No network address detected yet. Connect Wi-Fi or Tailscale.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${state.port}/"))
                            runCatching { context.startActivity(intent) }
                        }) { Text("Open web UI") }
                        TextButton(onClick = {
                            context.stopService(Intent(context, HostService::class.java))
                        }) { Text("Stop server") }
                    }
                }
            }
        } else {
            Text("Server stopped", style = MaterialTheme.typography.titleMedium)
            if (state.message.isNotBlank()) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit); portError = "" },
                label = { Text("Port") },
                singleLine = true,
                isError = portError.isNotBlank(),
                supportingText = if (portError.isNotBlank()) ({ Text(portError) }) else null,
            )
            Button(onClick = {
                val port = portText.toIntOrNull()
                if (port == null || port !in 1024..65535) {
                    portError = "Use a port between 1024 and 65535"
                    return@Button
                }
                val current = settingsStore.load().copy(serverPort = port)
                settingsStore.save(current)
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, HostService::class.java),
                )
            }) { Text("Start server") }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What you can do", style = MaterialTheme.typography.titleSmall)
                Text("Chat tab: talk to the agent on this phone.", style = MaterialTheme.typography.bodySmall)
                Text("Browser: open the URL above for the same agent on the web.", style = MaterialTheme.typography.bodySmall)
                Text("Settings tab: add your API key, model, and workspace.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Access token", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(
                        settings.serverToken,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                TextButton(onClick = { copy("Token", settings.serverToken) }) { Text("Copy token") }
                Text(
                    "The web page already includes this token. Keep it private: it gives access to the agent and its workspace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            "24/7 tip: in Android Settings, set OpenHost battery usage to Unrestricted. " +
                "The foreground notification keeps the server alive after you leave the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UrlRow(label: String, url: String, onCopy: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            TextButton(onClick = { onCopy(url) }) { Text("Copy") }
        }
    }
}
