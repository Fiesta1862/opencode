package dev.openhost.app.data

import android.content.Context
import java.security.SecureRandom

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("openhost.settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        // Generate and persist the token once so the UI and server always agree.
        val token = prefs.getString(KEY_SERVER_TOKEN, null)
            ?: generateToken().also { prefs.edit().putString(KEY_SERVER_TOKEN, it).apply() }
        return AppSettings(
            baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            model = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
            maxSteps = prefs.getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS).coerceIn(1, 100),
            autoApprove = prefs.getBoolean(KEY_AUTO_APPROVE, false),
            serverPort = prefs.getInt(KEY_SERVER_PORT, DEFAULT_PORT).coerceIn(1024, 65535),
            serverToken = token,
            workspaceUri = prefs.getString(KEY_WORKSPACE_URI, null),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putInt(KEY_MAX_STEPS, settings.maxSteps)
            .putBoolean(KEY_AUTO_APPROVE, settings.autoApprove)
            .putInt(KEY_SERVER_PORT, settings.serverPort)
            .putString(KEY_SERVER_TOKEN, settings.serverToken)
            .apply {
                if (settings.workspaceUri == null) remove(KEY_WORKSPACE_URI)
                else putString(KEY_WORKSPACE_URI, settings.workspaceUri)
            }
            .apply()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_MAX_STEPS = 20
        const val DEFAULT_PORT = 8080

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_AUTO_APPROVE = "auto_approve"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_SERVER_TOKEN = "server_token"
        private const val KEY_WORKSPACE_URI = "workspace_uri"

        private val RAND = SecureRandom()

        fun generateToken(): String {
            val bytes = ByteArray(24)
            RAND.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
