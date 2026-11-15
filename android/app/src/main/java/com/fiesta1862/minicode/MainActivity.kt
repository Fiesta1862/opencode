package com.fiesta1862.minicode

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fiesta1862.minicode.data.SettingsStore
import com.fiesta1862.minicode.ui.ApprovalDialog
import com.fiesta1862.minicode.ui.ChatScreen
import com.fiesta1862.minicode.ui.ChatViewModel
import com.fiesta1862.minicode.ui.MinicodeTheme
import com.fiesta1862.minicode.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Debug builds can be configured through intent extras for automated testing.
        SettingsStore(applicationContext).applyDebugIntent(intent)

        setContent {
            val state by viewModel.state.collectAsState()

            MinicodeTheme {
                val folderPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree(),
                ) { uri ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                        viewModel.setWorkspaceUri(uri)
                    }
                }

                if (state.showSettings) {
                    SettingsScreen(
                        state = state,
                        onBack = viewModel::closeSettings,
                        onSave = { baseUrl, apiKey, model, maxSteps, autoApprove ->
                            viewModel.saveSettings(baseUrl, apiKey, model, maxSteps, autoApprove)
                        },
                        onPickFolder = { folderPicker.launch(null) },
                        onUseAppStorage = { viewModel.setWorkspaceUri(null) },
                        onClearChat = viewModel::newChat,
                    )
                } else {
                    ChatScreen(
                        state = state,
                        onInputChange = viewModel::updateInput,
                        onSend = viewModel::send,
                        onStop = viewModel::stop,
                        onNewChat = viewModel::newChat,
                        onOpenSettings = viewModel::openSettings,
                    )
                }

                state.approval?.let { approval ->
                    ApprovalDialog(
                        request = approval,
                        onAnswer = { allow, alwaysAllow ->
                            viewModel.answerApproval(allow, alwaysAllow)
                        },
                    )
                }
            }
        }

        if (savedInstanceState == null) {
            intent.getStringExtra("debug_prompt")?.let(viewModel::runDebugPrompt)
        }
    }
}
