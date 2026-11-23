package com.aura.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.Ids
import com.aura.agent.net.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** A small fake MCP server (Streamable HTTP): tools echo, add (read-only) and boom (returns a tool error). */
class FakeMcp(val requireAuth: String? = null, val sse: Boolean = false) {
    val server = MockWebServer()
    val seen = CopyOnWriteArrayList<RecordedRequest>()
    val bodies = CopyOnWriteArrayList<JsonObject>()
    val url get() = server.url("/mcp").toString()

    private fun reply(id: JsonElement?, result: JsonObject): MockResponse {
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", id ?: JsonNull); put("result", result) }.toString()
        return if (sse) MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: message\ndata: $body\n\n")
        else MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }

    fun start(): FakeMcp {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                if (requireAuth != null && request.getHeader("Authorization") != requireAuth) return MockResponse().setResponseCode(401).setBody("unauthorized")
                val o = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                bodies += o
                val id = o["id"]
                return when (o["method"]?.jsonPrimitive?.content) {
                    "initialize" -> reply(id, buildJsonObject { put("protocolVersion", "2025-06-18"); putJsonObject("capabilities") { }; putJsonObject("serverInfo") { put("name", "fake"); put("version", "1") } }).setHeader("Mcp-Session-Id", "sess-42")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> reply(id, buildJsonObject {
                        putJsonArray("tools") {
                            add(buildJsonObject { put("name", "echo"); put("description", "Echo the text back"); putJsonObject("inputSchema") { put("type", "object"); putJsonObject("properties") { putJsonObject("text") { put("type", "string") } }; putJsonArray("required") { add("text") } } })
                            add(buildJsonObject { put("name", "add"); put("description", "Add two numbers"); putJsonObject("inputSchema") { put("type", "object"); putJsonObject("properties") { } }; putJsonObject("annotations") { put("readOnlyHint", true) } })
                            add(buildJsonObject { put("name", "boom"); put("description", "Always fails"); putJsonObject("inputSchema") { put("type", "object") } })
                        }
                    })
                    "tools/call" -> {
                        val p = o["params"]!!.jsonObject; val args = p["arguments"]!!.jsonObject
                        when (p["name"]!!.jsonPrimitive.content) {
                            "echo" -> reply(id, buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "echo: " + args["text"]!!.jsonPrimitive.content) }) } })
                            "add" -> reply(id, buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", ((args["a"]?.jsonPrimitive?.intOrNull ?: 0) + (args["b"]?.jsonPrimitive?.intOrNull ?: 0)).toString()) }) } })
                            else -> reply(id, buildJsonObject { put("isError", true); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "it broke") }) } })
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(); return this
    }
}

class McpClientTest {
    private fun srv(f: FakeMcp, headers: Map<String, String> = emptyMap()) = McpServer("s1", "Fake", f.url, headers)

    @Test fun connect_lists_the_tools_and_calls_them_with_the_session_id() = runBlocking {
        val f = FakeMcp().start(); val c = McpClient(srv(f))
        val tools = c.connect()
        assertEquals(listOf("echo", "add", "boom"), tools.map { it.name })
        assertTrue(tools.first { it.name == "add" }.readOnly); assertFalse(tools.first { it.name == "echo" }.readOnly)
        assertEquals("echo: hi", c.call("echo", buildJsonObject { put("text", "hi") }))
        val init = f.seen.first(); assertTrue(init.getHeader("Accept")!!.contains("text/event-stream")); assertNull(init.getHeader("Mcp-Session-Id"))
        assertEquals("every request after initialize carries the session id", "sess-42", f.seen.last().getHeader("Mcp-Session-Id"))
        assertTrue(f.bodies.any { it["method"]?.jsonPrimitive?.content == "notifications/initialized" })
    }

    @Test fun event_stream_replies_are_understood() = runBlocking {
        val f = FakeMcp(sse = true).start(); val c = McpClient(srv(f))
        assertEquals(3, c.connect().size); assertEquals("echo: x", c.call("echo", buildJsonObject { put("text", "x") }))
    }

    @Test fun the_users_header_is_sent_and_a_wrong_one_is_explained() = runBlocking {
        val f = FakeMcp(requireAuth = "Bearer FAKE-TOKEN-123").start()
        assertEquals(3, McpClient(srv(f, mapOf("Authorization" to "Bearer FAKE-TOKEN-123"))).connect().size)
        val e = runCatching { McpClient(srv(f, mapOf("Authorization" to "Bearer wrong"))).connect() }.exceptionOrNull()
        assertTrue(e?.message, e is McpException && e.message!!.contains("refused access (401)"))
    }

    @Test fun tool_errors_come_back_as_ERROR_text_for_the_agent() = runBlocking {
        val f = FakeMcp().start(); val c = McpClient(srv(f)); c.connect()
        assertEquals("ERROR: it broke", c.call("boom", buildJsonObject { }))
    }

    @Test fun an_address_that_is_not_mcp_gets_a_clear_message() = runBlocking {
        val s = MockWebServer(); s.enqueue(MockResponse().setResponseCode(404)); s.start()
        val e = runCatching { McpClient(McpServer("x", "X", s.url("/sse").toString())).connect() }.exceptionOrNull()
        assertTrue(e?.message, e is McpException && e.message!!.contains("Streamable HTTP"))
    }

    @Test fun an_unreachable_server_is_reported_not_crashed() = runBlocking {
        val e = runCatching { McpClient(McpServer("x", "X", "http://127.0.0.1:1/mcp")).connect() }.exceptionOrNull()
        assertTrue(e?.message, e is McpException && e.message!!.startsWith("Could not reach"))
    }
}

class RegistryParseTest {
    private val sample = """{"servers":[
      {"server":{"name":"io.example/open","description":"Open one","version":"1.0","remotes":[{"type":"streamable-http","url":"https://open.example.test/mcp"}],"repository":{"url":"https://github.com/e/open"}}},
      {"server":{"name":"io.example/keyed","description":"Needs a key","remotes":[{"type":"streamable-http","url":"https://k.example.test/mcp","headers":[{"name":"Authorization","description":"Bearer token","isRequired":true,"isSecret":true},{"name":"X-Opt","isRequired":false}]}]}},
      {"server":{"name":"io.example/sse-only","description":"old","remotes":[{"type":"sse","url":"https://old.example.test/sse"}]}},
      {"server":{"name":"io.example/local-only","description":"npm package","packages":[{"registryType":"npm"}]}},
      {"server":{"name":"io.example/templated","description":"t","remotes":[{"type":"streamable-http","url":"https://{tenant}.example.test/mcp"}]}},
      {"server":{"name":"io.example/open","description":"duplicate version","remotes":[{"type":"streamable-http","url":"https://open.example.test/mcp"}]}}]}"""

    @Test fun only_reachable_servers_are_listed_with_their_needed_headers() {
        val r = McpRegistry.parse(sample)
        assertEquals(listOf("io.example/open", "io.example/keyed"), r.map { it.name })
        assertTrue(r[0].headers.isEmpty()); assertEquals("https://github.com/e/open", r[0].repo)
        val h = r[1].headers; assertEquals(2, h.size); assertTrue(h[0].required && h[0].secret && h[0].name == "Authorization"); assertFalse(h[1].required)
    }

    @Test fun broken_json_gives_an_empty_list() { assertTrue(McpRegistry.parse("not json").isEmpty()); assertTrue(McpRegistry.parse("{}").isEmpty()) }

    @Test fun search_sends_the_query_to_the_registry() = runBlocking {
        val s = MockWebServer(); s.enqueue(MockResponse().setBody(sample)); s.start()
        McpRegistry.base = s.url("/").toString().trimEnd('/')
        try {
            assertEquals(2, McpRegistry.search("git hub").size)
            val req = s.takeRequest(); assertEquals("/v0/servers", req.requestUrl!!.encodedPath); assertEquals("git hub", req.requestUrl!!.queryParameter("search"))
        } finally { McpRegistry.base = "https://registry.modelcontextprotocol.io" }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class McpAgentTest {
    private val m = Mock().start()
    private val app = Env.setup(m).also { a -> a.extras.mcpServers.value.forEach { a.mcp.forget(it.id); a.extras.removeMcp(it.id) } }
    private fun ctx(approve: suspend (String, String) -> Boolean = { _, _ -> true }, readOnly: Boolean = false) = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), approve = approve, readOnly = readOnly)
    private fun run(name: String, args: String, c: ToolCtx = ctx()) = runBlocking { Tools.execute(c, name, args) }

    @Test fun connected_tools_reach_the_model_with_safe_names_and_can_be_called() {
        val f = FakeMcp().start()
        val s = McpServer(Ids.id(), "My Fake!", f.url)
        assertTrue(runBlocking { app.mcp.connect(s) }.isSuccess)
        val names = app.mcp.defs().map { it.name }
        assertEquals(listOf("mcp__my_fake__echo", "mcp__my_fake__add", "mcp__my_fake__boom"), names)
        assertTrue(names.all { it.length <= 64 && it.matches(Regex("[a-zA-Z0-9_-]+")) })
        assertEquals("echo: hello", run("mcp__my_fake__echo", """{"text":"hello"}"""))
        assertEquals("5", run("mcp__my_fake__add", """{"a":2,"b":3}"""))
        assertEquals("ERROR: it broke", run("mcp__my_fake__boom", "{}"))
    }

    @Test fun the_user_approves_a_server_once_and_a_no_stops_the_call() {
        val f = FakeMcp().start(); runBlocking { app.mcp.connect(McpServer(Ids.id(), "Fake", f.url)) }
        val asked = ArrayList<String>()
        val c = ctx(approve = { t, _ -> asked += t; false })
        assertTrue(run("mcp__fake__echo", """{"text":"x"}""", c).startsWith("DENIED"))
        assertEquals(0, f.bodies.count { it["method"]?.jsonPrimitive?.content == "tools/call" })
        val yes = ArrayList<String>(); val c2 = ctx(approve = { t, _ -> yes += t; true })
        run("mcp__fake__echo", """{"text":"1"}""", c2); run("mcp__fake__echo", """{"text":"2"}""", c2)
        assertEquals("asked only the first time", 1, yes.size)
        run("mcp__fake__add", """{"a":1,"b":1}""", ctx(approve = { _, _ -> error("read-only tools never ask") }))
    }

    @Test fun disconnecting_removes_the_tools_and_calls_fail_politely() {
        val f = FakeMcp().start(); val s = McpServer(Ids.id(), "Fake", f.url)
        runBlocking { app.mcp.connect(s) }; assertEquals(3, app.mcp.defs().size)
        app.mcp.disconnect(s.id)
        assertTrue(app.mcp.defs().isEmpty()); assertTrue(app.mcp.status.value[s.id] is McpStatus.Off)
        assertTrue(run("mcp__fake__echo", """{"text":"x"}""").startsWith("ERROR: the MCP tool"))
    }

    @Test fun ensureConnected_follows_the_switches_and_survives_a_dead_server() = runBlocking {
        val good = FakeMcp().start(); val on = McpServer(Ids.id(), "Good", good.url); val off = McpServer(Ids.id(), "Off", good.url, enabled = false)
        val dead = McpServer(Ids.id(), "Dead", "http://127.0.0.1:1/mcp")
        listOf(on, off, dead).forEach { app.extras.upsertMcp(it) }
        app.mcp.ensureConnected()
        assertEquals(setOf("mcp__good__echo", "mcp__good__add", "mcp__good__boom"), app.mcp.defs().map { it.name }.toSet())
        assertTrue(app.mcp.status.value[dead.id] is McpStatus.Failed)
        app.extras.setMcpEnabled(on.id, false); app.mcp.ensureConnected()
        assertTrue("switching a server off disconnects it", app.mcp.defs().isEmpty())
    }

    @Test fun workers_cannot_use_mcp_tools() {
        val f = FakeMcp().start(); runBlocking { app.mcp.connect(McpServer(Ids.id(), "Fake", f.url)) }
        assertTrue(run("mcp__fake__echo", """{"text":"x"}""", ctx(readOnly = true)).startsWith("ERROR: workers are read-only"))
    }

    @Test fun the_model_can_use_an_mcp_tool_in_a_normal_chat() {
        val f = FakeMcp().start(); app.extras.upsertMcp(McpServer(Ids.id(), "Fake", f.url))
        m.script += m.tool("mcp__fake__echo", """{"text":"from the model"}"""); m.script += m.text("The tool said hi.")
        var asked: String? = null
        val r = Env.run(app, "use the echo tool", during = { app.runner.approval.value?.let { asked = it.title; app.runner.resolveApproval(it.id, true) } })
        assertTrue("the user was asked before a server's tool ran: $asked", asked?.contains("Fake") == true)
        assertEquals("echo: from the model", r.tools.single().content); assertEquals("The tool said hi.", r.lastAssistant.content)
        assertTrue("the tool list the model saw includes the MCP tool", m.mainRequests.first().contains("mcp__fake__echo"))
    }

    @Test fun mcp_connect_needs_approval_and_never_takes_a_secret() {
        val f = FakeMcp().start()
        assertTrue(run("mcp_connect", """{"url":"${f.url}","name":"Public"}""", ctx(approve = { _, _ -> false })).startsWith("DENIED"))
        assertTrue(app.extras.mcpServers.value.isEmpty())
        val out = run("mcp_connect", """{"url":"${f.url}","name":"Public"}""")
        assertTrue(out, out.startsWith("Connected") && out.contains("echo"))
        assertEquals(1, app.extras.mcpServers.value.size); assertTrue(app.extras.mcpServers.value.single().headers.isEmpty())
        assertTrue(run("mcp_connect", """{"url":"ftp://x"}""").startsWith("ERROR"))
    }

    @Test fun a_failed_connect_is_not_left_in_the_list() {
        val out = run("mcp_connect", """{"url":"http://127.0.0.1:1/mcp","name":"Nope"}""")
        assertTrue(out, out.startsWith("ERROR")); assertTrue(app.extras.mcpServers.value.isEmpty())
    }

    @Test fun mcp_list_and_disconnect_describe_the_state() {
        val f = FakeMcp().start(); app.extras.upsertMcp(McpServer(Ids.id(), "Fake", f.url))
        val l = run("mcp_list", "{}"); assertTrue(l, l.contains("Fake") && l.contains("connected") && l.contains("echo"))
        assertTrue(run("mcp_disconnect", """{"name":"fake"}""").startsWith("Disconnected"))
        assertFalse(app.extras.mcpServers.value.single().enabled)
        assertTrue(run("mcp_list", "{}").contains("disconnected by the user"))
        assertTrue(run("mcp_disconnect", """{"name":"nothing"}""").startsWith("ERROR"))
    }

    @Test fun mcp_search_shows_servers_and_what_they_need() {
        val s = MockWebServer(); s.start()
        s.enqueue(MockResponse().setBody("""{"servers":[{"server":{"name":"io.x/open","description":"Open one","remotes":[{"type":"streamable-http","url":"https://o.test/mcp"}]}},{"server":{"name":"io.x/keyed","description":"Keyed","remotes":[{"type":"streamable-http","url":"https://k.test/mcp","headers":[{"name":"Authorization","isRequired":true,"isSecret":true}]}]}}]}"""))
        McpRegistry.base = s.url("/").toString().trimEnd('/')
        try {
            val out = run("mcp_search", """{"query":"x"}""")
            assertTrue(out, out.contains("io.x/open") && out.contains("needs no secret") && out.contains("needs: Authorization") && out.contains("Settings > MCP"))
        } finally { McpRegistry.base = "https://registry.modelcontextprotocol.io" }
    }

    @Test fun tokens_are_stored_only_in_the_extras_storage_and_survive_a_reload() {
        val f = FakeMcp(requireAuth = "Bearer FAKE-TOKEN-123").start()
        val s = McpServer(Ids.id(), "Keyed", f.url, mapOf("Authorization" to "Bearer FAKE-TOKEN-123"))
        app.extras.upsertMcp(s)
        val again = com.aura.agent.data.Extras(app)
        assertEquals("Bearer FAKE-TOKEN-123", again.mcpServers.value.single { it.id == s.id }.headers["Authorization"])
        assertTrue(runBlocking { app.mcp.connect(s) }.isSuccess)
    }
}
