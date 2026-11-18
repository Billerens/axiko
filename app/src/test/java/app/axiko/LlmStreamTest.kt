package app.axiko

import app.axiko.agent.Llm
import app.axiko.agent.LlmError
import app.axiko.agent.Models
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The model's streamed answer, as providers really send it, becomes text and whole tool calls. */
class LlmStreamTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() = server.shutdown()

    private fun target(options: JSONObject = JSONObject()) =
        Models.Target("test", server.url("/v1").toString().trimEnd('/'), "key", "model-x", options)

    private fun events(vararg deltas: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            deltas.joinToString("") { "data: {\"choices\":[{\"index\":0,\"delta\":$it}]}\n\n" } + "data: [DONE]\n\n",
        )

    private fun ask(target: Models.Target, onText: (String) -> Unit = {}) =
        Llm.stream(target, JSONArray(), JSONArray(), { false }, {}, {}, onText)

    @Test
    fun textArrivesPieceByPiece() {
        server.enqueue(events("""{"role":"assistant","content":""}""", """{"content":"При"}""", """{"content":"вет"}"""))
        val pieces = ArrayList<String>()
        val reply = ask(target()) { pieces.add(it) }
        assertEquals("Привет", reply.text)
        assertEquals(listOf("При", "вет"), pieces)
        assertTrue(reply.calls.isEmpty())
        assertNull(reply.reasoning)
    }

    @Test
    fun toolCallArgumentsAreJoinedFromFragments() {
        server.enqueue(
            events(
                """{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"fs_write","arguments":""}}]}""",
                """{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"a.txt\","}}]}""",
                """{"tool_calls":[{"index":0,"function":{"arguments":"\"content\":\"x\"}"}}]}""",
                """{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"ui_open","arguments":"{}"}}]}""",
            ),
        )
        val reply = ask(target())
        assertEquals(2, reply.calls.size)
        assertEquals("call_a", reply.calls[0].id)
        assertEquals("fs_write", reply.calls[0].name)
        assertEquals("a.txt", JSONObject(reply.calls[0].arguments).getString("path"))
        assertEquals("ui_open", reply.calls[1].name)
    }

    @Test
    fun thinkingBlocksAreMergedByIndex() {
        server.enqueue(
            events(
                """{"reasoning_details":[{"type":"reasoning.text","index":0,"text":"Ду"}]}""",
                """{"reasoning_details":[{"type":"reasoning.text","index":0,"text":"маю","signature":"sig"}]}""",
                """{"content":"Ответ"}""",
            ),
        )
        val reply = ask(target())
        val block = reply.reasoning!!.getJSONObject(0)
        assertEquals("Думаю", block.getString("text"))
        assertEquals("sig", block.getString("signature"))
    }

    @Test
    fun effortGoesInTheProvidersDialect() {
        server.enqueue(events("""{"content":"ok"}"""))
        ask(target(JSONObject().put("effort", "high")))
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("high", sent.getString("reasoning_effort"))
        assertEquals("model-x", sent.getString("model"))
        assertTrue(sent.getBoolean("stream"))
    }

    @Test
    fun completeReadsAStreamAndGivesThePlainShape() {
        server.enqueue(
            events("""{"reasoning_content":"Хм"}""", """{"content":"{\"a\":"}""", """{"content":"1}"}"""),
        )
        val request = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "x")))
            .put("response_format", JSONObject().put("type", "json_object"))
        val answer = Llm.complete(target(), request)
        val message = answer.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertEquals("{\"a\":1}", message.getString("content"))
        assertEquals("Хм", message.getString("reasoning_content"))
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(sent.getBoolean("stream"))
        assertEquals("json_object", sent.getJSONObject("response_format").getString("type"))
    }

    @Test
    fun completeTakesAPlainAnswerWhenTheProviderDoesNotStream() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"целиком"}}],"usage":{"total_tokens":7}}"""),
        )
        val answer = Llm.complete(target(), JSONObject().put("messages", JSONArray()))
        assertEquals("целиком", answer.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertEquals(7, answer.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun completeAsksForThePlainCallWhenTold() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""),
        )
        Llm.complete(target(), JSONObject().put("messages", JSONArray()).put("stream", false))
        assertTrue(!JSONObject(server.takeRequest().body.readUtf8()).getBoolean("stream"))
    }

    @Test
    fun providerErrorIsReportedWithItsMessage() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"No auth credentials found"}}"""))
        try {
            ask(target())
            throw AssertionError("an error was expected")
        } catch (error: LlmError) {
            assertTrue(error.message!!.contains("401"))
            assertTrue(error.message!!.contains("No auth credentials found"))
        }
    }
}
