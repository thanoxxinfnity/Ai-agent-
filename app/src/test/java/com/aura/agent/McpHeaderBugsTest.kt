package com.aura.agent

import com.aura.agent.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Bugs found by running custom MCP headers for real: bad header text, CRLF event streams, a server that forgets the session. */
class McpHeaderBugsTest {
    @Test fun a_header_with_a_line_break_or_space_in_its_name_is_explained_not_a_crash() = runBlocking {
        val f = FakeMcp().start()
        for (h in listOf(mapOf("X Token" to "v"), mapOf("X-Token" to "a\nb"), mapOf("X-Tökén" to "v"))) {
            val e = runCatching { McpClient(McpServer("s", "S", f.url, h)).connect() }.exceptionOrNull()
            assertTrue("$h -> $e", e is McpException && e.message!!.contains("not valid"))
        }
        assertTrue("nothing was sent", f.seen.isEmpty())
    }

    @Test fun header_values_are_trimmed_and_blank_names_ignored() = runBlocking {
        val f = FakeMcp().start()
        McpClient(McpServer("s", "S", f.url, mapOf("X-Api-Key" to "  fake-test-1  ", "" to "ignored"))).connect()
        assertEquals("fake-test-1", f.seen.first().getHeader("X-Api-Key"))
    }

    @Test fun crlf_event_streams_are_read() = runBlocking {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val o = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()) as kotlinx.serialization.json.JsonObject
                val id = o["id"]?.toString()
                return when ((o["method"] as kotlinx.serialization.json.JsonPrimitive).content) {
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "initialize" -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: message\r\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{}}\r\n\r\n")
                    else -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: message\r\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"t1\",\"inputSchema\":{\"type\":\"object\"}}]}}\r\n\r\n")
                }
            }
        }
        s.start()
        assertEquals(listOf("t1"), McpClient(McpServer("s", "S", s.url("/mcp").toString())).connect().map { it.name })
    }

    @Test fun a_forgotten_session_is_started_again_once() = runBlocking {
        val f = FakeMcp().start()
        val c = McpClient(McpServer("s", "S", f.url)); c.connect()
        // the server restarts: the old session id is now unknown (404) until we initialise again
        val real = f.server.dispatcher
        var expired = true
        f.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (expired && request.getHeader("Mcp-Session-Id") == "sess-42") { expired = false; return MockResponse().setResponseCode(404) }
                return real.dispatch(request)
            }
        }
        assertEquals("echo: hi", c.call("echo", kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive("hi")) }))
        assertEquals(2, f.bodies.count { it["method"].toString().contains("initialize") && !it["method"].toString().contains("notifications") })
    }

    /** Opt-in: `AURA_LIVE=1` runs this against a real public MCP server (DeepWiki, no account) with a custom header attached. */
    @Test fun live_public_server_accepts_our_client_and_a_custom_header() = runBlocking {
        assumeTrue("set AURA_LIVE=1 to run", System.getenv("AURA_LIVE") == "1")
        val c = McpClient(McpServer("s", "DeepWiki", "https://mcp.deepwiki.com/mcp", mapOf("X-Aura-Test" to "header-check")))
        val tools = c.connect()
        assertTrue(tools.map { it.name }.toString(), tools.any { it.name == "read_wiki_structure" })
        val out = c.call("read_wiki_structure", kotlinx.serialization.json.buildJsonObject { put("repoName", kotlinx.serialization.json.JsonPrimitive("square/okhttp")) })
        assertTrue(out.take(300), out.length > 50 && !out.startsWith("ERROR"))
    }
}
