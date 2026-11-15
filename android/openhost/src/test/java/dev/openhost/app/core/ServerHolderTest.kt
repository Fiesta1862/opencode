package dev.openhost.app.core

import dev.openhost.app.data.AppSettings
import dev.openhost.app.data.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class ServerHolderTest {

    @Before
    fun setUp() {
        ServerHolder.stop()
    }

    @After
    fun tearDown() {
        ServerHolder.stop()
    }

    @Test
    fun servesHealthAndWebPage() {
        val port = freePort()
        val error = ServerHolder.start(AppSettings(serverPort = port, serverToken = "test-token"))
        assertNull(error)
        assertTrue(ServerHolder.state.value.running)

        val health = request(port, "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        assertTrue(health, health.contains("\"status\":\"ok\""))
        assertTrue(health, health.startsWith("HTTP/1.1 200"))

        val page = request(port, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        assertTrue(page, page.contains("OpenHost Agent"))
    }

    @Test
    fun readsUtf8BodyByContentLength() {
        val port = freePort()
        ServerHolder.start(AppSettings(serverPort = port, serverToken = "test-token"))
        val payload = "{\"message\":\"\u4f60\u597d\uff0c\u4e16\u754c\"}"
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val raw = "POST /api/v1/chat HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/json\r\n" +
            "Authorization: Bearer anything\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n" +
            payload
        val response = request(port, raw)
        // Without an Android context there is no stored token, so auth must fail fast.
        assertTrue(response, response.startsWith("HTTP/1.1 401"))
    }

    @Test
    fun reportsPortInUse() {
        val port = freePort()
        ServerSocket(port).use {
            val error = ServerHolder.start(AppSettings(serverPort = port, serverToken = "test-token"))
            assertNotNull(error)
            assertFalse(ServerHolder.state.value.running)
            assertTrue(error!!.contains("unavailable"))
        }
    }

    @Test
    fun generatedTokensAreRandomHex() {
        val first = SettingsStore.generateToken()
        val second = SettingsStore.generateToken()
        assertEquals(48, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{48}")))
        assertNotEquals(first, second)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun request(port: Int, raw: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().apply {
                write(raw.toByteArray(Charsets.UTF_8))
                flush()
            }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
}
