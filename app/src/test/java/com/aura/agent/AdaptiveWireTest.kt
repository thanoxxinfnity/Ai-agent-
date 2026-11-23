package com.aura.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.net.Llm
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class WireRulesTest {
    @Test fun the_error_text_decides_which_api_style_to_try_next() {
        assertTrue(Llm.wantsOtherWire(false, 400, """{"error":{"code":"chat_completions_disabled","message":"use /v1/responses"}}"""))
        assertTrue(Llm.wantsOtherWire(false, 404, "404 page not found"))
        assertTrue(Llm.wantsOtherWire(true, 404, "404 page not found"))
        assertTrue(Llm.wantsOtherWire(true, 400, "this endpoint only supports /chat/completions"))
        assertFalse("a client restriction is not a wire problem", Llm.wantsOtherWire(false, 403, """{"error":{"code":"client_not_allowed"}}"""))
        assertFalse(Llm.wantsOtherWire(false, 401, "bad key")); assertFalse(Llm.wantsOtherWire(false, 429, "slow down")); assertFalse(Llm.wantsOtherWire(false, 503, "x"))
        assertFalse(Llm.wantsOtherWire(false, 400, "model not found"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AdaptiveWireTest {
    @Test fun a_server_that_disabled_chat_completions_is_switched_to_responses_and_remembered() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += MockResponse().setResponseCode(400).setBody("""{"error":{"code":"chat_completions_disabled","message":"use /v1/responses instead"}}""")
        val r = Env.run(app)
        assertEquals("responses ok", r.lastAssistant.content)
        assertFalse(r.lastAssistant.isError)
        assertEquals("one try on chat, then the same request on responses", 1, m.mainRequests.size - m.log.count { it.startsWith("POST /v1/responses") })
        assertTrue(m.log.any { it.startsWith("POST /v1/responses") })
        assertEquals("responses", app.store.providers.value.first().wire)
    }

    @Test fun a_restricted_client_error_is_not_worked_around() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += MockResponse().setResponseCode(403).setBody("""{"error":{"code":"client_not_allowed","message":"only coding clients","type":"permission_error"}}""")
        val e = Env.run(app).lastAssistant
        assertTrue(e.content, e.isError && e.content.contains("blocks chat apps"))
        assertFalse("no second style is tried and nothing pretends to be another client", m.log.any { it.startsWith("POST /v1/responses") })
        assertEquals(1, m.mainRequests.size)
    }
}
