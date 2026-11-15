package dev.openhost.app.ssh

data class SshHost(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: String = "password", // "password" or "key"
    val password: String = "",
    val privateKey: String = "",
)