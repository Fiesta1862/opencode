package com.fiesta1862.minicode.data

import android.content.Context
import android.content.Intent
import com.fiesta1862.minicode.BuildConfig

data class AppSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val maxSteps: Int = 20,
    val autoApprove: Boolean = false,
    val workspaceUri: String? = null,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("minicode.settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        model = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
        maxSteps = prefs.getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS).coerceIn(1, 100),
        autoApprove = prefs.getBoolean(KEY_AUTO_APPROVE, false),
        workspaceUri = prefs.getString(KEY_WORKSPACE_URI, null),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putInt(KEY_MAX_STEPS, settings.maxSteps)
            .putBoolean(KEY_AUTO_APPROVE, settings.autoApprove)
            .apply {
                if (settings.workspaceUri == null) remove(KEY_WORKSPACE_URI)
                else putString(KEY_WORKSPACE_URI, settings.workspaceUri)
            }
            .apply()
    }

    /** Emulator/E2E testing only. Debug builds accept configuration through intent extras. */
    fun applyDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val editor = prefs.edit()
        var changed = false

        intent.getStringExtra("debug_base_url")?.takeIf { it.isNotBlank() }?.let {
            editor.putString(KEY_BASE_URL, it)
            changed = true
        }
        intent.getStringExtra("debug_api_key")?.let {
            editor.putString(KEY_API_KEY, it)
            changed = true
        }
        intent.getStringExtra("debug_model")?.takeIf { it.isNotBlank() }?.let {
            editor.putString(KEY_MODEL, it)
            changed = true
        }
        if (intent.hasExtra("debug_max_steps")) {
            editor.putInt(KEY_MAX_STEPS, intent.getIntExtra("debug_max_steps", DEFAULT_MAX_STEPS).coerceIn(1, 100))
            changed = true
        }
        if (intent.hasExtra("debug_auto_approve")) {
            editor.putBoolean(KEY_AUTO_APPROVE, intent.getBooleanExtra("debug_auto_approve", false))
            changed = true
        }
        if (changed) editor.apply()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_MAX_STEPS = 20

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_AUTO_APPROVE = "auto_approve"
        private const val KEY_WORKSPACE_URI = "workspace_uri"
    }
}
