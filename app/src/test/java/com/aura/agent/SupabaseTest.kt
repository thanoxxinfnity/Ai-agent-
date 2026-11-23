package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.MemoryRepo
import com.aura.agent.data.*
import com.aura.agent.net.Supabase
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SupabaseRequestsTest {
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private var code = 200
    private val server = MockWebServer().also { s ->
        s.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse { seen += request; return if (code == 200) MockResponse().setBody("""[{"key":"github_client_id","value":"Iv1.public-client-id"}]""") else MockResponse().setResponseCode(code) } }
        s.start()
    }
    private val m = Mock().start()
    private val app = Env.setup(m).also { a -> a.store.setSupabase(server.url("/").toString().trimEnd('/'), "anon-public-key", false); reset(a) }
    private fun reset(a: App = app) { a.store.setGithubClientId(""); a.store.configFetchedAt = 0; a.store.configTriedAt = 0; seen.clear() }
    private fun load(force: Boolean = false) = runBlocking { app.supabase.loadConfig(force) }
    private val day = 24 * 3600_000L

    @Test fun the_settings_are_fetched_once_and_then_the_phone_stays_quiet() {
        load(); assertEquals(1, seen.size); assertEquals("Iv1.public-client-id", app.store.githubClientId.value)
        assertEquals("/rest/v1/app_config", seen[0].requestUrl!!.encodedPath); assertEquals("only the public anon key is sent", "anon-public-key", seen[0].getHeader("apikey")); assertNull(seen[0].getHeader("Authorization"))
        repeat(5) { load() }; assertEquals("nothing more while the value is known and fresh", 1, seen.size)
    }

    @Test fun a_known_value_is_refreshed_at_most_once_a_week() {
        app.store.setGithubClientId("Iv1.old"); app.store.configFetchedAt = System.currentTimeMillis() - 3 * day
        load(); assertEquals("3 days old: no request", 0, seen.size)
        app.store.configFetchedAt = System.currentTimeMillis() - 8 * day
        load(); assertEquals("8 days old: one request", 1, seen.size); assertEquals("Iv1.public-client-id", app.store.githubClientId.value)
    }

    @Test fun while_the_value_is_missing_a_failing_server_is_asked_at_most_every_ten_minutes() {
        code = 500
        load(); assertEquals(1, seen.size)
        repeat(4) { load() }; assertEquals("no hammering after a failure", 1, seen.size)
        load(force = true); assertEquals("tapping Sign in forces one try", 2, seen.size)
        app.store.configTriedAt = System.currentTimeMillis() - 11 * 60_000L
        code = 200; load(); assertEquals("after ten minutes it tries again", 3, seen.size); assertEquals("Iv1.public-client-id", app.store.githubClientId.value)
    }

    @Test fun without_a_server_address_nothing_is_sent() {
        app.store.setSupabase("", "", false); load(force = true); assertEquals(0, seen.size)
    }

    @Test fun chatting_memory_titles_and_keys_never_touch_supabase_even_if_the_old_sync_flag_is_on() {
        app.store.setSupabase(server.url("/").toString().trimEnd('/'), "anon-public-key", true)
        app.store.setGithubClientId("Iv1.known"); app.store.configFetchedAt = System.currentTimeMillis()
        runBlocking { MemoryRepo(app).save("note", "likes dark mode", "preference") }
        app.extras.upsertVault(VaultKey("k", "svc", "https://a.test", "vck_not_a_real_key_000000000000"))
        m.script += m.text("Done, I wrote the code.")
        Env.run(app, "Please build me a todo app in Kotlin")
        Thread.sleep(300)
        assertEquals("not a single request reached Supabase: no chat, no sign-up, no storage", 0, seen.size)
    }

    @Test fun the_supabase_client_cannot_send_user_data_at_all() {
        val names = Supabase::class.java.declaredMethods.map { it.name.lowercase() }
        for (n in names) assertFalse("no method may handle user data: $n", Regex("push|secret|signup|session|upsert|auth|chat|message|delete").containsMatchIn(n))
        assertTrue(names.any { it.contains("loadconfig") })
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LogoutKeepsDataUiTest : UiBase() {
    @Test fun signing_out_of_github_removes_only_the_token_and_keeps_chats_memory_keys_and_providers() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        vm().store.setGithub("gho_fake_token_for_test", "octo")
        runBlocking {
            val dao = app.db.dao()
            dao.upsertChat(ChatEntity("keep", "Keep me", Ids.now(), Ids.now(), titled = true)); dao.upsertMessage(MessageEntity("m1", "keep", "user", "my work", Ids.now()))
            MemoryRepo(app).save("pref", "short answers", "preference")
        }
        vm().extras.upsertVault(VaultKey("k1", "svc", "https://a.test", "vck_not_a_real_key_000000000000")); vm().extras.addFolder("Docs", "file:///tmp/docs")
        vm().store.upsertProvider(Provider("pp", "Mine", "http://x/v1", "k", models = listOf("m"), defaultModel = "m"))
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Sign out")); rule.waitForIdle()
        rule.onNodeWithText("Sign out").performClick(); rule.waitForIdle()
        assertEquals("the token is gone", "", vm().store.githubToken.value)
        runBlocking {
            val dao = app.db.dao()
            assertEquals("the chat is still there", "Keep me", dao.chat("keep")!!.title); assertEquals("my work", dao.messagesOnce("keep").single().content)
            assertEquals("short answers", dao.memory("pref")!!.value)
        }
        assertEquals("svc", vm().extras.vault.value.single().name); assertEquals("Docs", vm().extras.folders.value.single().name)
        assertTrue(vm().store.providers.value.any { it.id == "pp" })
    }
}
