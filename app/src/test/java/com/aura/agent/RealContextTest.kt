package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.ContextMeter
import com.aura.agent.data.*
import com.aura.agent.net.Llm
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelListFactsTest {
    private fun server(body: String, nativeBody: String = "{}"): MockWebServer {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.startsWith("/v1beta/models") -> MockResponse().setBody(nativeBody)
                else -> MockResponse().setBody(body)
            }
        }
        s.start(); return s
    }
    private val kilo = """{"data":[
      {"id":"free/chat:free","isFree":true,"context_length":131072,"architecture":{"output_modalities":["text"]}},
      {"id":"paid/fable-5.5","pricing":{"prompt":"0.000008","completion":"0.00004"},"top_provider":{"context_length":1000000},"architecture":{"output_modalities":["text"]}},
      {"id":"vendor/picture-maker","pricing":{"prompt":"0.00001","completion":"0.00004"},"architecture":{"output_modalities":["image"]}},
      {"id":"vendor/multi","isFree":true,"architecture":{"output_modalities":["text","image"]}}]}"""

    @Test fun context_windows_and_image_only_models_are_read_from_the_list() = runBlocking {
        val s = server(kilo)
        val l = Llm.listModelsFull(Provider("p", "P", s.url("/v1").toString().trimEnd('/'), "key"))
        assertEquals(131072, l.contexts["free/chat:free"]); assertEquals(1_000_000, l.contexts["paid/fable-5.5"]); assertNull(l.contexts["vendor/multi"])
        assertTrue(l.blocked["vendor/picture-maker"]!!.contains("image")); assertNull("a model that also writes text is a chat model", l.blocked["vendor/multi"])
        assertFalse(l.limitedToFree); assertTrue("paid/fable-5.5" in l.models)
    }

    @Test fun without_a_key_only_the_free_models_are_listed_and_the_note_flag_is_set() = runBlocking {
        val s = server(kilo)
        val l = Llm.listModelsFull(Provider("p", "P", s.url("/v1").toString().trimEnd('/'), ""))
        assertEquals(listOf("free/chat:free", "vendor/multi"), l.models); assertTrue(l.limitedToFree); assertFalse("paid/fable-5.5" in l.models)
    }

    @Test fun a_keyless_provider_that_states_no_prices_keeps_everything() = runBlocking {
        val s = server("""{"data":[{"id":"a"},{"id":"b"}]}""")
        val l = Llm.listModelsFull(Provider("p", "P", s.url("/v1").toString().trimEnd('/'), "")); assertEquals(listOf("a", "b"), l.models); assertFalse(l.limitedToFree)
    }

    @Test fun geminis_own_input_limit_is_the_window() = runBlocking {
        val s = server("""{"data":[{"id":"models/gemini-2.5-flash"}]}""", """{"models":[{"name":"models/gemini-2.5-flash","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent"]}]}""")
        val l = Llm.listModelsFull(Provider("g", "Gemini", s.url("/v1beta/openai").toString().trimEnd('/'), "FAKE_TEST_KEY"))
        assertEquals(1048576, l.contexts["gemini-2.5-flash"])
    }

    @Test fun the_store_uses_the_models_own_window_and_falls_back_to_the_setting() {
        val m = Mock().start(); val app = Env.setup(m)
        app.store.setContextTokens(64_000)
        assertEquals(64_000, app.store.contextLimit())
        app.store.upsertProvider(Provider("p", "T", "http://x/v1", "k", models = listOf("m", "n"), defaultModel = "m", contexts = mapOf("m" to 1_000_000)))
        app.store.select("p", "m"); assertEquals(1_000_000, app.store.contextLimit())
        app.store.select("p", "n"); assertEquals("a model without a listed window uses the setting", 64_000, app.store.contextLimit())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RealUsageTest {
    private fun msg(role: String, n: Int) = MessageEntity(Ids.id(), "c", role, "x".repeat(n), Ids.now())

    @Test fun the_providers_own_count_is_used_and_only_what_came_after_is_estimated() {
        val msgs = listOf(msg("user", 100), msg("assistant", 100), msg("user", 3500))
        val (est, e1) = ContextMeter.used(msgs, null); assertTrue(e1); assertEquals(ContextMeter.usedTokens(msgs), est)
        val (t, e2) = ContextMeter.used(msgs, ContextMeter.Real(5000, 2)); assertFalse(e2)
        assertEquals("5000 plus the 3500 new characters", 5000 + com.aura.agent.agent.Tokens.estimate(3500), t)
        assertTrue("a count from a longer history is not trusted", ContextMeter.used(msgs, ContextMeter.Real(5000, 9)).second)
    }

    @Test fun a_streamed_reply_with_a_usage_chunk_reports_the_prompt_tokens() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":1234,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n"))
        s.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"yo\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":777,\"output_tokens\":3}}}\n\n"))
        s.start()
        var got = 0
        Llm.stream(Provider("p", "P", s.url("/v1").toString().trimEnd('/'), "k"), "m", "sys", listOf(com.aura.agent.net.LlmMsg("user", "hello")), emptyList(), {}, onUsage = { got = it })
        assertEquals(1234, got)
        Llm.stream(Provider("p", "P", s.url("/v1").toString().trimEnd('/'), "k", wire = "responses"), "m", "sys", listOf(com.aura.agent.net.LlmMsg("user", "hello")), emptyList(), {}, onUsage = { got = it })
        assertEquals(777, got)
    }

    @Test fun after_a_real_chat_turn_the_runner_holds_the_providers_count() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Done.\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":4321}}\n\ndata: [DONE]\n\n")
        val r = Env.run(app, "hello there")
        val real = app.runner.realUsage.value[r.chatId]
        assertNotNull(real); assertEquals(4321, real!!.tokens)
        val (used, estimated) = ContextMeter.used(runBlocking { app.db.dao().messagesOnce(r.chatId) }, real)
        assertFalse(estimated); assertTrue(used >= 4321)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ContextRealUiTest : UiBase() {
    @Test fun the_meter_shows_the_models_own_window_and_drops_the_estimate_mark_with_a_real_count() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        vm().store.providers.value.forEach { vm().store.deleteProvider(it.id) }
        vm().store.upsertProvider(Provider("p", "T", "http://x/v1", "k", models = listOf("big"), defaultModel = "big", contexts = mapOf("big" to 1_000_000)))
        vm().store.select("p", "big"); vm().store.setAuto(false)
        val msgs = listOf(MessageEntity("a1", "rc", "user", "hello ".repeat(500), Ids.now()), MessageEntity("a2", "rc", "assistant", "answer ".repeat(300), Ids.now() + 1))
        runBlocking { val dao = app.db.dao(); dao.upsertChat(ChatEntity("rc", "Real ctx", Ids.now(), Ids.now(), titled = true)); msgs.forEach { dao.upsertMessage(it) } }
        rule.runOnUiThread { vm().openChat("rc") }; rule.waitForIdle()
        val est = ContextMeter.usedTokens(msgs)
        assertTrue("estimate with the model's window", text("Context ≈${ContextMeter.format(est)} / 1M"))
        rule.runOnUiThread { vm().runner.realUsage.value = mapOf("rc" to ContextMeter.Real(9000, 2)) }; rule.waitForIdle()
        assertTrue("real count, no mark", text("Context 9k / 1M"))
    }
}
