package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.*
import com.aura.agent.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AutoModeTest {
    private fun setup(m: Mock): App {
        val app = ApplicationProvider.getApplicationContext<App>()
        Cooldowns.reset(); RateLimiter.reset()
        app.store.setSupabase("", "", false)
        app.cloudApi = m.server.url("/gh").toString().trimEnd('/')
        app.store.providers.value.forEach { app.store.deleteProvider(it.id) }
        val base = m.server.url("/").toString().trimEnd('/')
        app.store.upsertProvider(Provider("A", "Provider A", "$base/v1", "k", models = listOf("ma"), defaultModel = "ma"))
        app.store.upsertProvider(Provider("B", "Provider B", "$base/v2", "k", models = listOf("mb"), defaultModel = "mb"))
        app.store.select("A", "ma")
        app.store.setGithub("gho_x", "octo")
        return app
    }

    private fun run(app: App, text: String = "hello"): List<MessageEntity> {
        val dao = app.db.dao(); val chat = Ids.id()
        runBlocking {
            dao.upsertChat(ChatEntity(chat, "New chat", Ids.now(), Ids.now()))
            dao.upsertMessage(MessageEntity(Ids.id(), chat, "user", text, Ids.now()))
        }
        app.runner.start(chat)
        val end = System.currentTimeMillis() + 40000
        while (System.currentTimeMillis() < end && app.runner.active.value.isNotEmpty()) Thread.sleep(30)
        assertTrue("run did not finish", app.runner.active.value.isEmpty())
        Thread.sleep(300)
        return runBlocking { dao.messagesOnce(chat) }
    }

    private fun limited(retry: Int = 30) = MockResponse().setResponseCode(429).setHeader("Retry-After", retry.toString()).setBody("{\"error\":{\"message\":\"quota exceeded\"}}")

    @Test fun auto_is_off_by_default_and_choosing_a_model_turns_it_off() {
        val app = setup(Mock().start())
        assertFalse(app.store.autoMode.value)
        app.store.setAuto(true); assertTrue(app.store.autoMode.value)
        app.store.select("A", "ma"); assertFalse("picking a model switches Auto off", app.store.autoMode.value)
    }

    @Test fun auto_switches_on_429_and_then_skips_the_cooling_provider() {
        val m = Mock().start(); val app = setup(m)
        app.store.setAuto(true)
        m.script += limited()
        val r = run(app)
        assertEquals("from B", r.last { it.role == "assistant" }.content)
        assertTrue(r.any { it.role == "switch" && it.content.contains("Switched to mb") && it.content.contains("rate limited") })
        assertEquals(1, m.mainRequests.size)
        run(app, "second message")
        assertEquals("provider A is not called again while it cools down", 1, m.mainRequests.size)
        assertTrue(m.altRequests.size >= 2)
    }

    @Test fun auto_switches_away_from_a_provider_that_blocks_chat_apps() {
        val m = Mock().start(); val app = setup(m)
        app.store.setAuto(true)
        m.script += MockResponse().setResponseCode(403).setBody("{\"error\":{\"code\":\"client_not_allowed\",\"type\":\"permission_error\"}}")
        val r = run(app)
        assertEquals("from B", r.last { it.role == "assistant" }.content)
        assertTrue(r.any { it.role == "switch" && it.content.contains("refused this app") })
    }

    @Test fun without_auto_a_429_never_switches_provider() {
        val m = Mock().start(); val app = setup(m)
        repeat(4) { m.script += limited(1) }
        val r = run(app)
        val err = r.last { it.role == "assistant" }
        assertTrue(err.content, err.isError && err.content.contains("rate limiting"))
        assertEquals("tried the same provider again after waiting, 1 + 3 retries", 4, m.mainRequests.size)
        assertEquals("never touched provider B", 0, m.altRequests.size)
        assertTrue(r.none { it.role == "switch" })
    }

    @Test fun without_auto_a_429_waits_for_retry_after_and_then_succeeds() {
        val m = Mock().start(); val app = setup(m)
        m.script += limited(1)
        m.script += m.text("worked after waiting")
        val t0 = System.currentTimeMillis()
        val r = run(app)
        assertEquals("worked after waiting", r.last { it.role == "assistant" }.content)
        assertTrue("it really waited", System.currentTimeMillis() - t0 >= 1000)
        assertEquals(2, m.mainRequests.size)
    }

    @Test fun switch_notes_are_never_sent_to_the_model() {
        val m = Mock().start(); val app = setup(m)
        app.store.setAuto(true)
        m.script += limited()
        run(app)
        run(app, "next")
        assertTrue(m.altRequests.none { it.contains("Switched to") })
    }

    @Test fun limiter_spaces_requests_by_the_provider_rpm() = runBlocking {
        RateLimiter.reset()
        val p = Provider("rl", "x", "http://x", rpm = 600)   // 100 ms apart
        val t0 = System.currentTimeMillis()
        repeat(4) { RateLimiter.acquire(p) }
        assertTrue("4 requests need at least 3 gaps", System.currentTimeMillis() - t0 >= 280)
        val free = Provider("free", "x", "http://unknown.example")
        val t1 = System.currentTimeMillis()
        repeat(20) { RateLimiter.acquire(free) }
        assertTrue("unknown hosts are not throttled", System.currentTimeMillis() - t1 < 200)
    }

    @Test fun known_free_tiers_get_safe_defaults_and_can_be_overridden() {
        assertEquals(10, RateLimiter.rpmFor(Provider("g", "g", "https://generativelanguage.googleapis.com/v1beta/openai")))
        assertEquals(30, RateLimiter.rpmFor(Provider("n", "n", "https://integrate.api.nvidia.com/v1")))
        assertEquals(120, RateLimiter.rpmFor(Provider("g", "g", "https://generativelanguage.googleapis.com/v1beta/openai", rpm = 120)))
    }

    @Test fun retry_delay_is_read_from_provider_error_bodies() {
        assertEquals(18L, Llm.parseRetryDelay("Quota exceeded. Please retry in 17.2s."))
        assertEquals(17L, Llm.parseRetryDelay("{\"details\":[{\"retryDelay\": \"17s\"}]}"))
        assertNull(Llm.parseRetryDelay("{\"error\":\"nope\"}"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AutoPickerUiTest : UiBase() {
    @Test fun auto_row_in_the_model_picker_turns_auto_on() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        app.store.setAuto(false)
        app.store.providers.value.forEach { app.store.deleteProvider(it.id) }
        app.store.upsertProvider(Provider("P", "Prov", "http://x/v1", "k", models = listOf("model-one"), defaultModel = "model-one"))
        app.store.select("P", "model-one")
        rule.waitForIdle()
        assertTrue(text("model-one"))
        rule.onNodeWithText("model-one").performClick()
        assertTrue("picker shows Auto", text("Switches to another provider"))
        rule.onNodeWithText("Auto", substring = false).performClick()
        rule.waitForIdle()
        assertTrue(app.store.autoMode.value)
        assertTrue("composer chip says Auto", text("Auto"))
    }
}
