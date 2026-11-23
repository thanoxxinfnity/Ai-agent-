package com.aura.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.net.Llm
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetryTest {
    @Test fun temporary_server_errors_are_retried_with_growing_pauses_and_the_chat_continues() {
        val m = Mock().start(); val app = Env.setup(m)
        Llm.backoffBaseMs = 150
        m.script += MockResponse().setResponseCode(502); m.script += MockResponse().setResponseCode(503); m.script += m.text("answer after two failures")
        val t0 = System.currentTimeMillis()
        val r = Env.run(app)
        assertEquals("answer after two failures", r.lastAssistant.content)
        assertEquals(3, m.mainRequests.size)
        assertTrue("backoff 150 ms then 300 ms", System.currentTimeMillis() - t0 >= 400)
    }

    @Test fun after_three_retries_the_error_is_shown_in_plain_words() {
        val m = Mock().start(); val app = Env.setup(m)
        repeat(6) { m.script += MockResponse().setResponseCode(503) }
        val e = Env.run(app).lastAssistant
        assertTrue(e.content, e.isError && e.content.contains("had a problem") && e.content.contains("503"))
        assertEquals("the first try plus 3 retries", 4, m.mainRequests.size)
    }

    @Test fun rate_limits_wait_for_the_providers_retry_after() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += MockResponse().setResponseCode(429).setHeader("Retry-After", "1"); m.script += m.text("ok after the wait")
        val t0 = System.currentTimeMillis()
        assertEquals("ok after the wait", Env.run(app).lastAssistant.content)
        assertTrue(System.currentTimeMillis() - t0 >= 900); assertEquals(2, m.mainRequests.size)
    }

    @Test fun a_dropped_connection_before_any_text_is_retried() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START); m.script += m.text("second try worked")
        val r = Env.run(app)
        assertEquals("second try worked", r.lastAssistant.content); assertEquals(2, m.mainRequests.size)
    }

    @Test fun a_connection_that_keeps_failing_ends_with_a_network_message() {
        val m = Mock().start(); val app = Env.setup(m)
        repeat(6) { m.script += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        val e = Env.run(app).lastAssistant
        assertTrue(e.content, e.isError && e.content.contains("Could not reach the provider")); assertEquals(4, m.mainRequests.size)
    }

    @Test fun a_drop_in_the_middle_of_an_answer_is_not_repeated() {
        val m = Mock().start(); val app = Env.setup(m)
        val first = "data: {\"choices\":[{\"delta\":{\"content\":\"half an ans\"}}]}\n\n"
        val body = first + ": " + "x".repeat(4000) + "\n\n" + "data: [DONE]\n\n"
        m.script += MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        val r = Env.run(app)
        assertEquals("text was already shown, so no second request that would repeat it", 1, m.mainRequests.size)
        assertTrue(r.lastAssistant.content, r.lastAssistant.isError)
    }

    @Test fun a_wrong_key_is_not_retried() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}")
        val e = Env.run(app).lastAssistant
        assertTrue(e.content, e.isError && e.content.contains("rejected the API key")); assertEquals(1, m.mainRequests.size)
    }

    @Test fun github_reads_are_retried_after_temporary_errors() {
        val m = Mock().start(); val app = Env.setup(m)
        m.ghFirst += MockResponse().setResponseCode(503); m.ghFirst += MockResponse().setResponseCode(502)
        m.script += m.tool("github_read_file", """{"repo":"r","path":"src/A.kt"}""")
        m.script += m.text("done")
        val r = Env.run(app)
        assertTrue(r.tools[0].content, !r.tools[0].isError && r.tools[0].content.contains("package a"))
        assertEquals("two failures and then the answer", 3, m.log.count { it.startsWith("GET /gh/repos/octo/r/contents/src/A.kt") })
    }

    @Test fun github_write_failures_are_reported_after_a_single_attempt() {
        val m = Mock().start(); val app = Env.setup(m)
        m.ghFirst += MockResponse().setResponseCode(503)
        m.script += m.tool("github_create_repo", """{"name":"brand-new"}""")
        m.script += m.text("ok")
        val t = Env.run(app).tools.single()
        assertTrue(t.content, t.isError && t.content.contains("503"))
        assertEquals("a POST is never repeated", 1, m.log.count { it == "POST /gh/user/repos" })
    }
}
