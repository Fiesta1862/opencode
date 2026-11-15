package com.fiesta1862.minicode.agent

import com.fiesta1862.minicode.data.AppSettings
import com.fiesta1862.minicode.llm.ChatMessage
import com.fiesta1862.minicode.llm.OpenAiClient
import com.fiesta1862.minicode.llm.Role
import com.fiesta1862.minicode.tools.ToolRegistry
import com.fiesta1862.minicode.workspace.LocalWorkspace
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(vararg chunks: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream; charset=utf-8")
        .setBody(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    @Test
    fun `agent streams tool calls writes a file and answers`() = runBlocking {
        val workspaceDir = temp.newFolder("workspace")

        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"write_file","arguments":"{\"path\":\"hello.md\","}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"content\":\"# Hi\\n\\nFrom the test.\"}"}}]},"finish_reason":"tool_calls"}]}""",
            ),
        )
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"content":"Done. "}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"I wrote `hello.md`."},"finish_reason":"stop"}]}""",
            ),
        )

        val settings = AppSettings(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "test-key",
            model = "mock-model",
            maxSteps = 5,
            autoApprove = true,
        )
        val history = mutableListOf<ChatMessage>()
        val agent = Agent(
            client = OpenAiClient(),
            tools = { ToolRegistry(LocalWorkspace(workspaceDir)) },
            settings = { settings },
            systemPrompt = { "You are a test agent." },
            approve = { _, _ -> true },
        )

        val events = agent.run(history, "Create hello.md").toList()

        assertTrue(events.any { it is AgentEvent.TextDelta })
        assertTrue(events.any { it is AgentEvent.ToolFinished })
        assertTrue(events.any { it is AgentEvent.Done })

        val written = File(workspaceDir, "hello.md")
        assertTrue("hello.md should exist", written.isFile)
        assertEquals("# Hi\n\nFrom the test.", written.readText())

        assertEquals(2, server.requestCount)

        val firstRequest = server.takeRequest()
        assertEquals("/v1/chat/completions", firstRequest.path)
        assertEquals("Bearer test-key", firstRequest.getHeader("Authorization"))
        val firstBody = JSONObject(firstRequest.body.readUtf8())
        assertEquals("mock-model", firstBody.getString("model"))
        assertEquals(true, firstBody.getBoolean("stream"))

        val secondRequest = server.takeRequest()
        val secondBody = JSONObject(secondRequest.body.readUtf8())
        val messages = secondBody.getJSONArray("messages")
        val toolMessage = (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .firstOrNull { it.optString("role") == "tool" }
        assertEquals("call_1", toolMessage?.optString("tool_call_id"))
        assertTrue(toolMessage?.optString("content").orEmpty().contains("Wrote 3 lines"))

        val assistant = history.last { it.role == Role.ASSISTANT }
        assertEquals("Done. I wrote `hello.md`.", assistant.content)
    }

    @Test
    fun `agent reports step limit`() = runBlocking {
        repeat(3) {
            server.enqueue(
                sse(
                    """{"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_$it","type":"function","function":{"name":"list_files","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
                ),
            )
        }

        val settings = AppSettings(
            baseUrl = server.url("/v1").toString(),
            apiKey = "",
            model = "mock",
            maxSteps = 2,
            autoApprove = true,
        )
        val agent = Agent(
            client = OpenAiClient(),
            tools = { ToolRegistry(LocalWorkspace(temp.newFolder())) },
            settings = { settings },
            systemPrompt = { "test" },
            approve = { _, _ -> true },
        )

        val events = agent.run(mutableListOf(), "loop").toList()
        assertTrue(events.any { it is AgentEvent.StepLimit })
        assertEquals(2, server.requestCount)
    }
}
