package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Made-up secrets: the right shape, never real. */
private const val GH = "ghp_ZZtestFAKEfakeFAKEfakeFAKEfake1234567"
private const val OAI = "sk-ZZtestFAKEvalue1234567890abcdefXYZ12"
private const val VERCEL = "vcp_ZZtestFAKEfakeFAKEfakeFAKEfake1234567890"
private const val GOOGLE = "AIzaSyZZfakeFAKEfakeFAKEfakeFAKEfake1234"

class SecretExtractTest {
    @Test fun provider_keys_are_found_with_their_service_name_and_address() {
        val f = SecretScan.extract("use $GH and $VERCEL and $GOOGLE and $OAI please")
        assertEquals(setOf("github", "vercel", "google", "openai"), f.map { it.slug }.toSet())
        assertEquals("https://api.github.com", f.first { it.slug == "github" }.host); assertEquals("https://api.vercel.com", f.first { it.slug == "vercel" }.host)
        assertEquals("header", f.first { it.slug == "google" }.style); assertEquals("x-goog-api-key", f.first { it.slug == "google" }.param); assertEquals("", f.first { it.slug == "openai" }.host)
    }

    @Test fun one_key_is_found_once_and_longer_provider_patterns_win() {
        val a = "sk-ant-ZZtestFAKEvalue1234567890abcdefXYZ12"
        val f = SecretScan.extract("my key is $a ok"); assertEquals(1, f.size); assertEquals("anthropic", f[0].slug)
        assertEquals(1, SecretScan.extract("$OAI and again $OAI").size)
    }

    @Test fun placeholders_and_normal_text_give_nothing() {
        assertTrue(SecretScan.extract("set apiKey = \"YOUR_API_KEY_HERE_PLEASE_REPLACE\" and use sk-your-key-goes-here-12345678 or sk-xxxxxxxxxxxxxxxxxxxxxxxxx").isEmpty())
        assertTrue(SecretScan.extract("hello, build me a todo app in kotlin").isEmpty())
    }

    @Test fun a_private_key_block_is_found_whole_and_marked_as_a_block() {
        val block = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAAB3NzaC1\nxyz\n-----END OPENSSH PRIVATE KEY-----"
        val f = SecretScan.extract("here: $block done"); assertEquals(1, f.size); assertTrue(f[0].isBlock); assertEquals(block, f[0].value)
    }

    @Test fun the_new_providers_are_blocked_from_github_too() {
        for (t in listOf(VERCEL, "nvapi-ZZtestFAKEfakeFAKEfakeFAKEfake123456789", "gsk_ZZtestFAKEfakeFAKEfakeFAKEfake123456789", "hf_ZZtestFAKEfakeFAKEfakeFAKEfake123456789"))
            assertNotNull(t, SecretScan.find("token = $t"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretCaptureTest {
    private val ctx get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun extras() = Extras(ctx).also { e -> e.vault.value.forEach { e.removeVault(it.id) } }

    @Test fun a_pasted_key_moves_to_the_key_box_and_the_message_shows_only_a_note() {
        val e = extras()
        val r = SecretCapture.capture("deploy with my vercel token $VERCEL now", e)
        assertEquals("deploy with my vercel token [key saved in the key box as 'vercel'] now", r.text); assertEquals(listOf("vercel"), r.saved)
        val k = e.vault.value.single(); assertEquals(VERCEL, k.key); assertEquals("https://api.vercel.com", k.baseUrl)
        assertFalse(r.text.contains("ZZtest"))
    }

    @Test fun names_stay_unique_the_same_key_is_not_saved_twice_and_unknown_hosts_stay_blank() {
        val e = extras()
        val r = SecretCapture.capture("a $OAI b sk-ZZtestSECONDvalue1234567890abcdefXYZ", e)
        assertEquals(setOf("openai", "openai-2"), e.vault.value.map { it.name }.toSet()); assertTrue(e.vault.value.all { it.baseUrl.isBlank() })
        SecretCapture.capture("again $OAI", e); assertEquals("no duplicate", 2, e.vault.value.size)
        assertEquals(2, r.saved.size)
    }

    @Test fun private_keys_are_removed_and_not_kept() {
        val e = extras(); val block = "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"
        val r = SecretCapture.capture("key:\n$block\nthanks", e)
        assertFalse(r.text.contains("BEGIN") || r.text.contains("abc")); assertEquals(1, r.removed); assertTrue(e.vault.value.isEmpty())
    }

    @Test fun text_without_secrets_is_untouched() {
        val e = extras(); val t = "Please build a todo app. Use the API key from settings."
        val r = SecretCapture.capture(t, e); assertEquals(t, r.text); assertTrue(r.saved.isEmpty() && e.vault.value.isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretChatFlowTest {
    private val m = Mock().start()
    private val app = Env.setup(m).also { a -> a.extras.vault.value.forEach { a.extras.removeVault(it.id) } }
    private fun ctx(answer: SecretReply?, approve: Boolean = true, asked: MutableList<SecretAsk> = ArrayList()) =
        ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), approve = { _, _ -> approve }, askSecret = { asked += it; answer })
    private fun run(name: String, args: String, c: ToolCtx) = runBlocking { Tools.execute(c, name, args) }

    @Test fun the_model_opens_the_key_box_picks_the_address_and_never_sees_the_key() {
        val asked = ArrayList<SecretAsk>()
        val c = ctx(SecretReply("vercel", "https://api.vercel.com", VERCEL, "bearer", ""), asked = asked)
        val out = run("request_api_key", """{"name":"Vercel","base_url":"https://api.vercel.com","reason":"to deploy your site"}""", c)
        assertTrue(out, out.startsWith("Saved the key 'vercel'") && !out.contains(VERCEL) && out.contains("api_request"))
        assertEquals(1, asked.size); assertEquals("vercel", asked[0].name); assertEquals("https://api.vercel.com", asked[0].baseUrl); assertEquals("to deploy your site", asked[0].reason)
        assertEquals(VERCEL, app.extras.vault.value.single().key)
    }

    @Test fun declining_the_box_stores_nothing_and_the_model_is_told_not_to_nag() {
        val out = run("request_api_key", """{"name":"x","base_url":"https://api.example.test"}""", ctx(null))
        assertTrue(out, out.startsWith("DENIED") && out.contains("Settings > API keys")); assertTrue(app.extras.vault.value.isEmpty())
    }

    @Test fun a_key_that_is_already_stored_for_that_address_is_not_asked_for_again() {
        app.extras.upsertVault(VaultKey("1", "vercel", "https://api.vercel.com", VERCEL))
        val asked = ArrayList<SecretAsk>()
        val out = run("request_api_key", """{"name":"vercel","base_url":"https://api.vercel.com"}""", ctx(null, asked = asked))
        assertTrue(out, out.contains("already stored")); assertTrue(asked.isEmpty())
    }

    @Test fun bad_requests_are_refused_before_anything_is_shown() {
        val asked = ArrayList<SecretAsk>(); val c = ctx(null, asked = asked)
        assertTrue(run("request_api_key", """{"name":"x","base_url":"not-a-url"}""", c).startsWith("ERROR")); assertTrue(run("request_api_key", """{"base_url":"https://a.test"}""", c).startsWith("ERROR"))
        assertTrue(run("request_api_key", """{"name":"x","base_url":"https://a.test","style":"weird"}""", c).startsWith("ERROR")); assertTrue(asked.isEmpty())
    }

    @Test fun a_key_pasted_in_chat_gets_its_address_from_the_model_only_with_the_users_yes() {
        app.extras.upsertVault(VaultKey("p", "openai", "", OAI))
        assertTrue(run("api_request", """{"key":"openai","url":"/v1/models"}""", ctx(null)).contains("api_configure"))
        assertTrue(run("api_configure", """{"key":"openai","base_url":"https://api.openai.com"}""", ctx(null, approve = false)).startsWith("DENIED"))
        assertEquals("", app.extras.vault.value.single().baseUrl)
        val out = run("api_configure", """{"key":"openai","base_url":"https://api.openai.com","style":"bearer"}""", ctx(null))
        assertTrue(out, out.contains("https://api.openai.com") && !out.contains(OAI))
        val k = app.extras.vault.value.single(); assertEquals("https://api.openai.com", k.baseUrl); assertEquals("the key itself is untouched", OAI, k.key)
    }

    @Test fun a_key_that_already_has_an_address_cannot_be_re_pointed_by_the_model() {
        app.extras.upsertVault(VaultKey("p", "svc", "https://good.test", "vck_not_a_real_key_0000000000"))
        val out = run("api_configure", """{"key":"svc","base_url":"https://evil.test"}""", ctx(null))
        assertTrue(out, out.startsWith("ERROR") && out.contains("Only the user")); assertEquals("https://good.test", app.extras.vault.value.single().baseUrl)
    }

    @Test fun the_model_is_told_what_to_do_and_workers_cannot_open_the_box() {
        val s = AgentRunner.SYSTEM
        assertTrue(s.contains("call request_api_key") && s.contains("no .env") && s.contains("api_configure"))
        for (n in listOf("request_api_key", "api_configure")) { assertEquals(n, 1, Tools.defs.count { it.name == n }); assertNotEquals(n, Tools.label(n)) }
        val ro = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), readOnly = true)
        assertTrue(run("request_api_key", """{"name":"x","base_url":"https://a.test"}""", ro).startsWith("ERROR: workers are read-only"))
    }

    @Test fun in_a_chat_the_box_opens_the_user_saves_and_the_key_never_reaches_a_model_request() {
        m.script += m.tool("request_api_key", """{"name":"weather","base_url":"https://api.weather.test","reason":"to read the forecast"}""")
        m.script += m.text("Saved. I can now call the weather service.")
        var seen: Pair<String, SecretAsk>? = null
        val r = Env.run(app, "get me the forecast", during = { app.runner.secretAsk.value?.let { seen = it; app.runner.resolveSecret(it.first, SecretReply(it.second.name, it.second.baseUrl, "wk_FAKE_FORECAST_KEY_1234567890", "bearer", "")) } })
        assertEquals("weather", seen!!.second.name); assertEquals("https://api.weather.test", seen!!.second.baseUrl)
        assertEquals("wk_FAKE_FORECAST_KEY_1234567890", app.extras.vault.value.single { it.name == "weather" }.key)
        assertFalse("the key is nowhere in what the model received", m.mainRequests.any { it.contains("wk_FAKE_FORECAST_KEY") })
        assertFalse(r.msgs.any { it.content.contains("wk_FAKE_FORECAST_KEY") || (it.toolArgs ?: "").contains("wk_FAKE_FORECAST_KEY") })
        assertEquals("Saved. I can now call the weather service.", r.lastAssistant.content)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PastedKeyChatUiTest : UiBase() {
    @Test fun a_key_typed_into_the_message_box_is_saved_hidden_and_never_sent_or_shown() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        vm().extras.vault.value.forEach { vm().extras.removeVault(it.id) }
        vm().store.upsertProvider(Provider("p", "T", "http://127.0.0.1:1/v1", "k", models = listOf("m"), defaultModel = "m")); vm().store.select("p", "m")
        rule.onNodeWithTag("input").performTextInput("my github token is $GH, remember it")
        rule.onNodeWithTag("send").performClick(); rule.waitForIdle()
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end && vm().extras.vault.value.isEmpty()) Thread.sleep(50)
        assertEquals(GH, vm().extras.vault.value.single().key); assertEquals("github", vm().extras.vault.value.single().name)
        val shown = System.currentTimeMillis() + 6000; var note = false
        while (System.currentTimeMillis() < shown && !note) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); rule.waitForIdle(); note = rule.onAllNodesWithTag("notice").fetchSemanticsNodes().isNotEmpty(); Thread.sleep(60) }
        assertTrue("the user is told the key is safe", note); assertTrue(text("cannot see it"))
        assertFalse("the key is not drawn anywhere", text("ZZtest"))
        val msgs = runBlocking { app.db.dao().messagesOnce(vm().currentId.value!!) }
        assertTrue(msgs.none { it.content.contains(GH) }); assertTrue(msgs.first { it.role == "user" }.content.contains("key saved in the key box as 'github'"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretDialogUiTest : UiBase() {
    @Test fun the_secure_key_box_shows_the_address_hides_the_key_and_returns_it_only_to_the_app() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        var got: SecretReply? = null; var cancelled = false
        val ask = SecretAsk("vercel", "https://api.vercel.com", "bearer", "", "to deploy your site")
        val id = "ask1"
        rule.activity.runOnUiThread { vm().runner.secretAsk.value = id to ask }; rule.waitForIdle()
        assertTrue(text("Secure key box")); assertTrue(text("to deploy your site")); assertTrue(text("only ever sent to api.vercel.com")); assertTrue(text("cannot see this key"))
        rule.onNodeWithTag("secret-save").performClick(); rule.waitForIdle(); assertTrue("an empty key is refused", text("Paste the key."))
        rule.onNodeWithTag("secret-key").performTextInput("vck_FAKE_TYPED_KEY_1234567890"); rule.waitForIdle()
        rule.onNodeWithTag("secret-key").assert(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))   // typed as a password field
        rule.onNodeWithTag("secret-url").performTextClearance(); rule.onNodeWithTag("secret-url").performTextInput("ftp://x"); rule.onNodeWithTag("secret-save").performClick(); rule.waitForIdle()
        assertTrue(text("must start with https://"))
    }
}
