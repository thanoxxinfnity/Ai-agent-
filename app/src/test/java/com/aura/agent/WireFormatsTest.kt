package com.aura.agent

import com.aura.agent.data.Provider
import com.aura.agent.net.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

private const val FAKE = "fake-test-key-not-real-0003"

class WireFormatsTest {
    private val s = MockWebServer().also { it.start() }
    @After fun stop() { s.shutdown() }
    private fun base(path: String = "") = s.url(path).toString().trimEnd('/')
    private fun prov(wire: String, url: String = base(), headers: Map<String, String> = emptyMap(), key: String = FAKE) = Provider("p", "P", url, key, wire = wire, headers = headers)
    private val tool = ToolDef("get_time", "Current time", buildJsonObject { put("type", "object"); putJsonObject("properties") { }; put("additionalProperties", false); put("\$schema", "x") })
    private fun run(p: Provider, msgs: List<LlmMsg> = listOf(LlmMsg("user", "hi")), tools: List<ToolDef> = emptyList()): Pair<String, List<ToolCall>> {
        val sb = StringBuilder()
        val calls = runBlocking { Llm.stream(p, "m1", "be brief", msgs, tools, { sb.append(it) }, maxRetries = 0) }
        return sb.toString() to calls
    }
    private fun sse(vararg events: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events.joinToString("") { "$it\n\n" })

    @Test fun anthropic_request_shape_headers_and_streamed_text_and_tool_call() {
        s.enqueue(sse(
            """event: message_start
data: {"type":"message_start","message":{"usage":{"input_tokens":42}}}""",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}""",
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"get_time","input":{}}}""",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"tz\":"}}""",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"UTC\"}"}}""",
            """data: {"type":"message_stop"}"""))
        var used = 0
        val sb = StringBuilder()
        val calls = runBlocking { Llm.stream(prov("anthropic", base("/v1")), "claude-x", "be brief", listOf(LlmMsg("user", "hi")), listOf(tool), { sb.append(it) }, maxRetries = 0, onUsage = { used = it }) }
        assertEquals("Hello", sb.toString()); assertEquals(42, used)
        assertEquals("get_time", calls.single().name); assertEquals("tu_1", calls.single().id); assertEquals("""{"tz":"UTC"}""", calls.single().args)
        val r = s.takeRequest()
        assertEquals("/v1/messages", r.path); assertEquals(FAKE, r.getHeader("x-api-key")); assertEquals("2023-06-01", r.getHeader("anthropic-version")); assertNull(r.getHeader("Authorization"))
        val b = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("be brief", b["system"]!!.jsonPrimitive.content); assertTrue(b["stream"]!!.jsonPrimitive.boolean); assertTrue(b["max_tokens"]!!.jsonPrimitive.int > 0)
        assertEquals("get_time", b["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content); assertNotNull(b["tools"]!!.jsonArray[0].jsonObject["input_schema"])
    }

    @Test fun anthropic_history_merges_tool_results_into_one_user_turn() {
        val b = WireBodies.anthropic("m", "sys", listOf(
            LlmMsg("user", "do it"),
            LlmMsg("assistant", "", toolCalls = listOf(ToolCall("a", "t1", """{"x":1}"""), ToolCall("b", "t2", ""))),
            LlmMsg("tool", "r1", toolCallId = "a", toolName = "t1"), LlmMsg("tool", "r2", toolCallId = "b", toolName = "t2"),
            LlmMsg("user", "thanks")), emptyList())
        val m = b["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), m.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val last = m[2].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("tool_result", "tool_result", "text"), last.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("tool_use", m[1].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), m[1].jsonObject["content"]!!.jsonArray[1].jsonObject["input"])
    }

    @Test fun gemini_request_shape_and_streamed_answer_with_a_function_call() {
        s.enqueue(sse(
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Hi "}]}}],"usageMetadata":{"promptTokenCount":9}}""",
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"there"},{"thought":true,"text":"hidden"},{"functionCall":{"name":"get_time","args":{"tz":"UTC"}}}]}}]}"""))
        var used = 0
        val sb = StringBuilder()
        val calls = runBlocking { Llm.stream(prov("gemini", base("/v1beta")), "gemini-x", "be brief", listOf(LlmMsg("user", "hi"), LlmMsg("assistant", "ok", toolCalls = listOf(ToolCall("c", "get_time", "{}"))), LlmMsg("tool", "12:00", toolCallId = "c", toolName = "get_time")), listOf(tool), { sb.append(it) }, maxRetries = 0, onUsage = { used = it }) }
        assertEquals("Hi there", sb.toString()); assertEquals(9, used)
        assertEquals("get_time", calls.single().name); assertEquals("""{"tz":"UTC"}""", calls.single().args)
        val r = s.takeRequest()
        assertEquals("/v1beta/models/gemini-x:streamGenerateContent?alt=sse", r.path); assertEquals(FAKE, r.getHeader("x-goog-api-key")); assertNull(r.getHeader("Authorization"))
        val b = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("be brief", b["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val roles = b["contents"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("user", "model", "user"), roles)
        val decl = b["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject["parameters"]!!.jsonObject
        assertFalse("schema keys Gemini rejects are removed", "additionalProperties" in decl || "\$schema" in decl || "properties" in decl)
    }

    @Test fun auto_picks_the_format_from_the_address_and_chat_otherwise() {
        fun d(url: String, wire: String = "auto") = WireBodies.detect(Provider("p", "P", url, wire = wire))
        assertEquals("anthropic", d("https://api.anthropic.com")); assertEquals("gemini", d("https://generativelanguage.googleapis.com/v1beta"))
        assertEquals("chat", d("https://generativelanguage.googleapis.com/v1beta/openai")); assertEquals("chat", d("https://api.groq.com/openai/v1"))
        assertEquals("responses", d("https://x.example/v1", "responses")); assertEquals("anthropic", d("https://x.example/v1", "anthropic"))
        assertEquals("https://api.anthropic.com/v1/messages", WireBodies.anthropicUrl(Provider("p", "P", "https://api.anthropic.com")))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/g:streamGenerateContent?alt=sse", WireBodies.geminiUrl(Provider("p", "P", "https://generativelanguage.googleapis.com"), "models/g"))
    }

    @Test fun custom_headers_win_over_defaults_but_never_replace_the_key_header() {
        s.enqueue(sse("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}"""))
        run(prov("anthropic", base("/v1"), mapOf("User-Agent" to "my-app/1.0", "anthropic-version" to "2024-01-01", "x-api-key" to "attacker", "X-Extra" to " v ")))
        val r = s.takeRequest()
        assertEquals("my-app/1.0", r.getHeader("User-Agent")); assertEquals("2024-01-01", r.getHeader("anthropic-version"))
        assertEquals(FAKE, r.getHeader("x-api-key")); assertEquals("v", r.getHeader("X-Extra"))
    }

    @Test fun without_a_key_a_custom_authorization_header_is_allowed() {
        s.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        run(prov("chat", key = "", headers = mapOf("Authorization" to "Custom abc")))
        assertEquals("Custom abc", s.takeRequest().getHeader("Authorization"))
    }

    @Test fun an_invalid_header_is_explained_not_a_crash() {
        val e = runCatching { run(prov("chat", headers = mapOf("Bad Name" to "v"))) }.exceptionOrNull()
        assertTrue("$e", e is LlmError && e.message!!.contains("not valid")); assertEquals(0, s.requestCount)
        assertNull(Llm.headerProblem("User-Agent: x\nX-A: b\n\n")); assertNotNull(Llm.headerProblem("no colon here")); assertNotNull(Llm.headerProblem("Bad Name: v")); assertNotNull(Llm.headerProblem("X-A: tö"))
    }

    @Test fun any_answer_shape_is_read_even_when_not_streamed_or_mislabelled() {
        val cases = mapOf(
            "chat" to """{"choices":[{"message":{"content":"A"}}]}""",
            "responses" to """{"output":[{"type":"message","content":[{"type":"output_text","text":"A"}]}]}""",
            "anthropic" to """{"type":"message","content":[{"type":"text","text":"A"}],"usage":{"input_tokens":3}}""",
            "gemini" to """{"candidates":[{"content":{"parts":[{"text":"A"}]}}]}""",
        )
        for ((wire, body) in cases) for (asked in listOf("chat", "anthropic")) {
            s.enqueue(MockResponse().setBody(body))
            assertEquals("$wire body asked as $asked", "A", run(prov(asked)).first)
        }
        // an event stream sent without the right content type, and a "data:" body
        s.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\ndata: [DONE]\n\n"))
        assertEquals("B", run(prov("chat")).first)
    }

    @Test fun web_pages_and_junk_give_a_readable_error() {
        s.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Please sign in</body></html>"))
        val e1 = runCatching { run(prov("chat")) }.exceptionOrNull()
        assertTrue("$e1", e1 is LlmError && e1.message!!.contains("web page"))
        s.enqueue(MockResponse().setBody("plain words, not json"))
        assertTrue(runCatching { run(prov("chat")) }.exceptionOrNull()!!.message!!.contains("did not answer in a format"))
        s.enqueue(MockResponse().setBody("""{"weird":true}"""))
        assertTrue(runCatching { run(prov("anthropic")) }.exceptionOrNull()!!.message!!.contains("not in a format Aura knows"))
        s.enqueue(MockResponse().setBody("""{"type":"error","error":{"type":"overloaded","message":"Overloaded right now"}}"""))
        assertTrue(runCatching { run(prov("anthropic")) }.exceptionOrNull()!!.message!!.contains("Overloaded right now"))
    }

    @Test fun model_lists_use_each_formats_own_address_and_key_header() = runBlocking {
        s.enqueue(MockResponse().setBody("""{"data":[{"id":"claude-a"},{"id":"claude-b"}]}"""))
        assertEquals(listOf("claude-a", "claude-b"), Llm.listModels(prov("anthropic", base("/v1"))))
        val r1 = s.takeRequest(); assertTrue(r1.path!!, r1.path!!.startsWith("/v1/models")); assertEquals(FAKE, r1.getHeader("x-api-key")); assertEquals("2023-06-01", r1.getHeader("anthropic-version"))
        s.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-a"}]}"""))
        assertEquals(listOf("gemini-a"), Llm.listModels(prov("gemini", base("/v1beta"))))
        val r2 = s.takeRequest(); assertTrue(r2.path!!, r2.path!!.startsWith("/v1beta/models")); assertEquals(FAKE, r2.getHeader("x-goog-api-key"))
    }
}
