package dev.openhost.app.data

data class AppSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val maxSteps: Int = 20,
    val autoApprove: Boolean = false,
    val serverPort: Int = 8080,
    val serverToken: String = "",
    val workspaceUri: String? = null,
)