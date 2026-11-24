package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.*
import com.aura.agent.net.Llm
import com.aura.agent.net.ModelGuard
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

class ModelGuardUnitTest {
    @Test fun live_speech_embedding_and_media_models_are_recognised() {
        val blocked = listOf(
            "gemini-2.0-flash-live-001", "gemini-live-2.5-flash-preview", "gemini-2.5-flash-native-audio-preview-09-2025", "models/gemini-live-x",
            "gemini-2.5-flash-preview-tts", "gemini-3.8-flash-tts", "text-embedding-004", "gemini-embedding-001", "nvidia/nv-embedqa-e5-v5",
            "imagen-4.0-generate-001", "veo-3.0-generate-preview", "gpt-image-1", "dall-e-3", "sora-2", "flux-pro", "gpt-4o-realtime-preview",
            "whisper-large-v3", "gpt-4o-transcribe", "llama-guard-3-8b", "some-bidi-model",
            "gemini-2.5-flash-image", "gemini-3-pro-image-preview", "gemini-2.0-flash-preview-image-generation", "gemini-nano-banana-2.1", "lyria-3-pro-preview", "google/gemini-3.1-flash-image", "openai/gpt-5-image-mini",
        )
        for (m in blocked) assertNotNull("must be blocked: $m", ModelGuard.heuristic(m))
        val fine = listOf(
            "gemini-2.5-flash", "gemini-2.5-pro", "gemini-3.8-flash-lite", "gpt-4o", "gpt-5.6", "llama-3.3-70b-versatile", "minimaxai/minimax-m3.1-flash",
            "deepseek-r1", "qwen3-coder", "claude-sonnet-4", "mistral-large-latest", "openai/gpt-oss-120b", "olive-7b", "deliver-chat-1", "alive-model",
        )
        for (m in fine) assertNull("must be allowed: $m", ModelGuard.heuristic(m))
    }

    @Test fun reasons_explain_the_problem_in_plain_words() {
        assertTrue(ModelGuard.heuristic("gemini-live-2.5-flash-preview")!!.contains("WebSocket"))
        assertTrue(ModelGuard.heuristic("text-embedding-004")!!.contains("Embedding"))
        val p = Provider("g", "Google Gemini", "https://x", blocked = mapOf("odd-name" to "Live model: it only works over a WebSocket"))
        assertEquals("Live model: it only works over a WebSocket", ModelGuard.reason(p, "odd-name"))
        assertNull(ModelGuard.reason(p, "gemini-2.5-flash"))
        val w = ModelGuard.warning(p, "odd-name", "Live model")
        assertTrue(w, w.contains("cannot be used here") && w.contains("Pick a regular chat model"))
    }

    private fun server(log: CopyOnWriteArrayList<RecordedRequest>, nativeCode: Int = 200): MockWebServer {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                log += request
                val p = request.path.orEmpty()
                return when {
                    p.startsWith("/v1beta/openai/models") -> MockResponse().setBody("""{"data":[{"id":"models/gemini-2.5-flash"},{"id":"models/gemini-2.0-flash-live-001"},{"id":"models/gemini-embedding-001"},{"id":"models/mystery-bidi-model"},{"id":"models/gemini-2.5-flash-preview-tts"},{"id":"models/odd-text-model"}]}""")
                    p.startsWith("/v1beta/models") -> if (nativeCode != 200) MockResponse().setResponseCode(nativeCode) else MockResponse().setBody("""{"models":[
                        {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
                        {"name":"models/gemini-2.0-flash-live-001","supportedGenerationMethods":["bidiGenerateContent","countTokens"]},
                        {"name":"models/gemini-embedding-001","supportedGenerationMethods":["embedContent"]},
                        {"name":"models/mystery-bidi-model","supportedGenerationMethods":["bidiGenerateContent"]},
                        {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
                        {"name":"models/odd-text-model","supportedGenerationMethods":["predict"]}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start(); return s
    }

    @Test fun geminis_own_method_list_decides_which_models_are_blocked() = runBlocking {
        val log = CopyOnWriteArrayList<RecordedRequest>()
        val s = server(log)
        val p = Provider("g", "Gemini", s.url("/v1beta/openai").toString().trimEnd('/'), "FAKE_TEST_KEY")
        val full = Llm.listModelsFull(p)
        assertEquals(listOf("gemini-2.0-flash-live-001", "gemini-2.5-flash", "gemini-2.5-flash-preview-tts", "gemini-embedding-001", "mystery-bidi-model", "odd-text-model"), full.models)
        assertNull("a normal chat model stays usable", full.blocked["gemini-2.5-flash"])
        assertTrue(full.blocked["gemini-2.0-flash-live-001"]!!.contains("WebSocket"))
        assertTrue("a name without 'live' is still caught by its methods", full.blocked["mystery-bidi-model"]!!.contains("WebSocket (bidiGenerateContent)"))
        assertTrue(full.blocked["gemini-embedding-001"]!!.contains("Not a chat model")); assertTrue(full.blocked["odd-text-model"]!!.contains("Not a chat model"))
        assertTrue(full.blocked["gemini-2.5-flash-preview-tts"]!!.contains("Speech"))
        assertEquals("FAKE_TEST_KEY", log.first { it.path!!.startsWith("/v1beta/models") }.getHeader("x-goog-api-key"))
        assertFalse("the key never goes in a URL", log.any { it.path!!.contains("FAKE_TEST_KEY") })
    }

    @Test fun if_the_native_list_fails_the_name_rules_still_protect() = runBlocking {
        val s = server(CopyOnWriteArrayList(), nativeCode = 500)
        val full = Llm.listModelsFull(Provider("g", "Gemini", s.url("/v1beta/openai").toString().trimEnd('/'), "FAKE_TEST_KEY"))
        assertTrue(full.blocked.keys.containsAll(listOf("gemini-2.0-flash-live-001", "gemini-embedding-001", "gemini-2.5-flash-preview-tts")))
        assertNull(full.blocked["gemini-2.5-flash"])
    }

    @Test fun other_providers_use_the_name_rules_only() = runBlocking {
        val log = CopyOnWriteArrayList<RecordedRequest>()
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse { log += request; return MockResponse().setBody("""{"data":[{"id":"gpt-4o"},{"id":"gpt-4o-realtime-preview"},{"id":"text-embedding-3-small"}]}""") } }
        s.start()
        val full = Llm.listModelsFull(Provider("o", "OpenAI-like", s.url("/v1").toString().trimEnd('/'), "k"))
        assertEquals(setOf("gpt-4o-realtime-preview", "text-embedding-3-small"), full.blocked.keys); assertEquals(1, log.size)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelGuardAgentTest {
    @Test fun a_live_only_model_is_refused_before_any_request_is_sent() {
        val m = Mock().start(); val app = Env.setup(m)
        val base = m.server.url("/").toString().trimEnd('/')
        app.store.upsertProvider(Provider("p", "Gemini", "$base/v1", "k", models = listOf("m", "gemini-live-2.5-flash-preview"), defaultModel = "m"))
        app.store.select("p", "gemini-live-2.5-flash-preview")
        val r = Env.run(app)
        val e = r.lastAssistant
        assertTrue(e.content, e.isError && e.content.contains("cannot be used here") && e.content.contains("WebSocket") && e.content.contains("Pick a regular chat model"))
        assertEquals("no API call was made", 0, m.mainRequests.size)
    }

    @Test fun a_model_blocked_by_the_providers_own_list_is_refused_too() {
        val m = Mock().start(); val app = Env.setup(m)
        val base = m.server.url("/").toString().trimEnd('/')
        app.store.upsertProvider(Provider("p", "Gemini", "$base/v1", "k", models = listOf("m", "odd-name"), defaultModel = "m", blocked = mapOf("odd-name" to "Not a chat model (no generateContent)")))
        app.store.select("p", "odd-name")
        val e = Env.run(app).lastAssistant
        assertTrue(e.content, e.isError && e.content.contains("no generateContent")); assertEquals(0, m.mainRequests.size)
    }

    @Test fun auto_mode_skips_blocked_models_and_uses_the_next_provider() {
        val m = Mock().start(); val app = Env.setup(m)
        val base = m.server.url("/").toString().trimEnd('/')
        app.store.providers.value.forEach { app.store.deleteProvider(it.id) }
        app.store.upsertProvider(Provider("A", "Provider A", "$base/v1", "k", models = listOf("foo-live-001"), defaultModel = "foo-live-001"))
        app.store.upsertProvider(Provider("B", "Provider B", "$base/v2", "k", models = listOf("good-chat"), defaultModel = "good-chat"))
        app.store.select("A", "foo-live-001"); app.store.setAuto(true)
        assertEquals("only the usable model is a candidate", listOf("good-chat"), app.store.candidates().map { it.second })
        val r = Env.run(app)
        assertEquals("from B", r.lastAssistant.content); assertEquals(0, m.mainRequests.size); assertEquals(1, m.altRequests.size)
    }

    @Test fun the_default_model_is_never_a_blocked_one() {
        val m = Mock().start(); val app = Env.setup(m)
        val p = Provider("x", "X", "http://x/v1", "k", models = listOf("text-embedding-004", "real-chat"), defaultModel = "text-embedding-004")
        app.store.providers.value.forEach { app.store.deleteProvider(it.id) }
        app.store.upsertProvider(p)
        assertEquals("real-chat", app.store.candidates().single().second)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelPickerBlockedUiTest : UiBase() {
    @Test fun blocked_models_are_hidden_from_the_picker_and_only_chat_models_are_listed() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        app.store.providers.value.forEach { app.store.deleteProvider(it.id) }
        app.store.upsertProvider(Provider("P", "Prov", "http://x/v1", "k", models = listOf("good-chat", "gemini-live-x", "gemini-2.5-flash-image", "text-embedding-004", "other-chat"), defaultModel = "good-chat"))
        app.store.select("P", "good-chat"); app.store.setAuto(false)
        rule.waitForIdle()
        rule.onNodeWithText("good-chat").performClick()
        rule.onNodeWithTag("model-list").performScrollToNode(hasText("other-chat")); rule.waitForIdle()
        assertTrue(text("good-chat") && text("other-chat"))
        for (hidden in listOf("gemini-live-x", "gemini-2.5-flash-image", "text-embedding-004")) assertTrue("$hidden is not offered", rule.onAllNodesWithText(hidden).fetchSemanticsNodes().isEmpty())
    }
}
