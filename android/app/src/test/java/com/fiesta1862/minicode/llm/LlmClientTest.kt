package com.fiesta1862.minicode.llm

import com.fiesta1862.minicode.agent.ToolCallAccumulator
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmClientTest {

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

    private val request = LlmRequest(
        baseUrl = "",
        apiKey = "key",
        model = "mock",
        messages = listOf(ChatMessage(Role.USER, "hi")),
        tools = emptyList(),
    )

    @Test
    fun `parses streaming text deltas`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}

                    data: {"choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val events = OpenAiClient()
            .stream(request.copy(baseUrl = server.url("/v1").toString()))
            .toList()

        val text = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello", text)
        assertTrue(events.any { it is LlmEvent.Finished })
    }

    @Test
    fun `falls back to a plain json response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"plain answer"},"finish_reason":"stop"}]}""",
                ),
        )

        val events = OpenAiClient()
            .stream(request.copy(baseUrl = server.url("/v1").toString()))
            .toList()

        assertEquals("plain answer", events.filterIsInstance<LlmEvent.TextDelta>().single().text)
    }

    @Test
    fun `sends tools and authorization`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        val spec = ToolSpec(
            name = "read_file",
            description = "read",
            parameters = JSONObject().put("type", "object"),
        )
        OpenAiClient()
            .stream(
                request.copy(
                    baseUrl = server.url("/v1").toString(),
                    tools = listOf(spec),
                ),
            )
            .toList()

        val recorded = server.takeRequest()
        assertEquals("Bearer key", recorded.getHeader("Authorization"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("read_file", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("auto", body.getString("tool_choice"))
    }

    @Test
    fun `tool call deltas accumulate`() {
        val accumulator = ToolCallAccumulator()
        accumulator.add(
            LlmEvent.ToolCallDelta(
                index = 0,
                id = "call_1",
                name = "write_file",
                arguments = """{"path":"a.txt",""",
            ),
        )
        accumulator.add(
            LlmEvent.ToolCallDelta(index = 0, id = null, name = null, arguments = """"content":"hi"}"""),
        )

        val calls = accumulator.build()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("write_file", calls[0].name)
        assertEquals("a.txt", calls[0].args().getString("path"))
        assertEquals("hi", calls[0].args().getString("content"))
    }
}
