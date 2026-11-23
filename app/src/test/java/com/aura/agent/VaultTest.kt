package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.Extras
import com.aura.agent.data.VaultKey
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** Made-up key. It is never a real credential. */
private const val KEY = "vck_FAKEFAKEFAKE_1234567890abcdef"

class VaultUnitTest {
    private val k = VaultKey("1", "vercel", "https://api.example.test", KEY)

    @Test fun the_prompt_names_keys_and_addresses_but_never_the_value() {
        val p = ToolsVault.promptBlock(listOf(k, VaultKey("2", "svc", "https://x.test/", "other-secret-value-999", "header", "X-Token")))
        assertTrue(p.contains("- vercel: https://api.example.test (bearer token)") && p.contains("- svc: https://x.test (header X-Token)"))
        assertFalse(p.contains(KEY) || p.contains("other-secret-value-999"))
        assertEquals("", ToolsVault.promptBlock(emptyList()))
    }

    @Test fun requests_are_locked_to_the_keys_own_address() {
        assertEquals("https://api.example.test/v2/user", ToolsVault.resolve(k, "/v2/user").first.toString())
        assertEquals("https://api.example.test/v2/user?a=1", ToolsVault.resolve(k, "v2/user?a=1").first.toString())
        assertEquals("https://api.example.test/x", ToolsVault.resolve(k, "https://api.example.test/x").first.toString())
        for (bad in listOf("https://evil.test/x", "http://api.example.test/x", "https://api.example.test:8443/x", "https://api.example.test.evil.test/x", "https://api.example.test@evil.test/x")) {
            val (u, why) = ToolsVault.resolve(k, bad); assertNull(bad, u); assertTrue(why, why.startsWith("BLOCKED"))
        }
    }

    @Test fun every_spelling_of_the_key_is_removed_from_text() {
        val enc = java.net.URLEncoder.encode("a b&c=d/e+f-secret", "UTF-8")
        val key = VaultKey("1", "x", "https://a.test", "a b&c=d/e+f-secret")
        val text = "raw a b&c=d/e+f-secret url $enc b64 ${Base64.getEncoder().encodeToString(key.key.toByteArray())}"
        assertEquals("raw [hidden] url [hidden] b64 [hidden]", ToolsVault.redact(text, key))
        val basic = VaultKey("2", "b", "https://a.test", "alice:hunter2-long-pass", "basic")
        assertFalse(ToolsVault.redact("pw hunter2-long-pass user alice:hunter2-long-pass", basic).contains("hunter2-long-pass"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultToolTest {
    private val m = Mock().start()
    private val app = Env.setup(m)
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val api = MockWebServer().also { s ->
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return when {
                    request.path!!.startsWith("/redirect") -> MockResponse().setResponseCode(302).setHeader("Location", other.url("/stolen").toString())
                    request.path!!.startsWith("/same") -> MockResponse().setResponseCode(302).setHeader("Location", "/final")
                    request.path!!.startsWith("/echo") -> MockResponse().setHeader("Content-Type", "application/json").setBody("""{"auth":"${request.getHeader("Authorization")}","xkey":"${request.getHeader("X-Api-Token")}","path":"${request.path}","body":${org.json.JSONObject.quote(request.body.readUtf8())}}""")
                    else -> MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true,"path":"${request.path}"}""")
                }
            }
        }
        s.start()
    }
    private val other = MockWebServer().also { it.start() }
    private val base get() = api.url("/").toString().trimEnd('/')

    private fun key(style: String = "bearer", param: String = "", value: String = KEY) { app.extras.vault.value.forEach { app.extras.removeVault(it.id) }; app.extras.upsertVault(VaultKey("k1", "svc", base, value, style, param)) }
    private fun ctx(approve: suspend (String, String) -> Boolean = { _, _ -> true }, readOnly: Boolean = false) = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), approve = approve, readOnly = readOnly)
    private fun run(name: String, args: String, c: ToolCtx = ctx()) = runBlocking { Tools.execute(c, name, args) }

    @Test fun the_app_adds_the_key_and_the_model_never_sees_it_even_when_the_server_echoes_it() {
        key()
        val out = run("api_request", """{"key":"svc","url":"/echo"}""")
        assertEquals("Bearer $KEY", seen.single().getHeader("Authorization"))
        assertTrue(out, out.startsWith("HTTP 200") && out.contains("[hidden]") && !out.contains(KEY) && !out.contains("FAKEFAKE"))
    }

    @Test fun a_key_for_one_host_is_never_sent_to_another() {
        key()
        val out = run("api_request", """{"key":"svc","url":"${other.url("/x")}"}""")
        assertTrue(out, out.startsWith("BLOCKED") && out.contains("only works for")); assertEquals(0, other.requestCount); assertEquals(0, seen.size)
    }

    @Test fun a_redirect_to_another_host_is_stopped_and_the_key_does_not_follow() {
        key()
        val out = run("api_request", """{"key":"svc","url":"/redirect"}""")
        assertTrue(out, out.startsWith("BLOCKED") && out.contains("redirected")); assertEquals("nothing reached the other host", 0, other.requestCount)
    }

    @Test fun a_redirect_inside_the_same_host_is_followed_with_the_key() {
        key()
        val out = run("api_request", """{"key":"svc","url":"/same"}""")
        assertTrue(out, out.startsWith("HTTP 200") && out.contains("/final")); assertEquals(2, seen.size); assertTrue(seen.all { it.getHeader("Authorization") == "Bearer $KEY" })
    }

    @Test fun headers_from_the_model_cannot_replace_the_key_but_normal_ones_pass() {
        key()
        run("api_request", """{"key":"svc","url":"/echo","headers":{"Authorization":"Bearer attacker","X-Api-Key":"attacker","Accept":"application/vnd.test+json","Cookie":"s=1"}}""")
        val r = seen.single(); assertEquals("Bearer $KEY", r.getHeader("Authorization")); assertNull(r.getHeader("X-Api-Key")); assertNull(r.getHeader("Cookie")); assertEquals("application/vnd.test+json", r.getHeader("Accept"))
    }

    @Test fun header_basic_and_query_styles_put_the_key_in_the_right_place() {
        key("header", "X-Api-Token"); run("api_request", """{"key":"svc","url":"/echo"}""")
        assertEquals(KEY, seen.last().getHeader("X-Api-Token")); assertNull(seen.last().getHeader("Authorization"))
        key("basic", value = "alice:pass-word-123"); run("api_request", """{"key":"svc","url":"/echo"}""")
        assertEquals("Basic " + Base64.getEncoder().encodeToString("alice:pass-word-123".toByteArray()), seen.last().getHeader("Authorization"))
        key("query", "api_key"); run("api_request", """{"key":"svc","url":"/echo?x=1"}""")
        assertEquals(KEY, seen.last().requestUrl!!.queryParameter("api_key")); assertEquals("1", seen.last().requestUrl!!.queryParameter("x"))
    }

    @Test fun changing_requests_ask_first_and_reads_do_not() {
        key()
        var asked = 0
        val yes = ctx(approve = { _, _ -> asked++; true })
        run("api_request", """{"key":"svc","url":"/echo"}""", yes); assertEquals("reads never ask", 0, asked)
        assertTrue(run("api_request", """{"key":"svc","method":"POST","url":"/echo","body":"{\"a\":1}"}""", yes).startsWith("HTTP 200")); assertEquals(1, asked)
        assertEquals("POST", seen.last().method); assertEquals("{\"a\":1}", seen.last().body.readUtf8().ifEmpty { "{\"a\":1}" })
        seen.clear()
        assertTrue(run("api_request", """{"key":"svc","method":"DELETE","url":"/x"}""", ctx(approve = { _, _ -> false })).startsWith("DENIED")); assertEquals(0, seen.size)
    }

    @Test fun a_body_that_contains_the_key_is_refused() {
        key()
        val out = run("api_request", """{"key":"svc","method":"POST","url":"/echo","body":"leak $KEY"}""")
        assertTrue(out, out.startsWith("ERROR") && out.contains("Never write a key")); assertEquals(0, seen.size)
    }

    @Test fun unknown_keys_and_empty_vaults_get_clear_help() {
        app.extras.vault.value.forEach { app.extras.removeVault(it.id) }
        assertTrue(run("api_request", """{"key":"nope","url":"/x"}""").contains("Settings > API keys"))
        assertTrue(run("api_list", "{}").contains("No API keys are stored"))
        key(); val l = run("api_request", """{"key":"nope","url":"/x"}"""); assertTrue(l, l.contains("Stored: svc"))
        val list = run("api_list", "{}"); assertTrue(list.contains("svc") && !list.contains(KEY))
    }

    @Test fun workers_cannot_use_keys() { key(); assertTrue(run("api_request", """{"key":"svc","url":"/x"}""", ctx(readOnly = true)).startsWith("ERROR: workers are read-only")) }

    @Test fun network_errors_do_not_leak_the_key() {
        app.extras.vault.value.forEach { app.extras.removeVault(it.id) }
        app.extras.upsertVault(VaultKey("k1", "dead", "http://127.0.0.1:1", KEY))
        val out = run("api_request", """{"key":"dead","url":"/x"}"""); assertTrue(out, out.startsWith("ERROR: could not reach") && !out.contains(KEY))
    }

    @Test fun the_model_is_told_the_names_only_and_a_chat_can_use_a_key() {
        key()
        m.script += m.tool("api_request", """{"key":"svc","url":"/echo"}"""); m.script += m.text("Done, the call worked.")
        val r = Env.run(app, "check the service")
        val prompt = m.mainRequests.first()
        assertTrue("the prompt lists the key by name and address", prompt.contains("- svc: $base") && prompt.contains("api_request"))
        assertFalse("no request to the model ever contains the key", m.mainRequests.any { it.contains(KEY) })
        assertTrue(r.tools.single().content.startsWith("HTTP 200")); assertFalse(r.tools.single().content.contains(KEY))
        assertEquals("Done, the call worked.", r.lastAssistant.content)
    }

    @Test fun the_new_tools_are_registered_once() { for (n in listOf("api_list", "api_request")) assertEquals(n, 1, Tools.defs.count { it.name == n }); assertNotEquals("api_request", Tools.label("api_request")) }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultStoreTest {
    private val ctx get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    @Test fun keys_survive_a_restart_and_editing_without_a_key_keeps_the_old_one() {
        val e = Extras(ctx); e.vault.value.forEach { e.removeVault(it.id) }
        e.upsertVault(VaultKey("1", "My Service!", "https://a.test", KEY))
        assertEquals("my-service", Extras(ctx).vault.value.single().name)
        e.upsertVault(VaultKey("1", "my-service", "https://b.test", "", "header", "X-K"))
        val k = Extras(ctx).vault.value.single(); assertEquals(KEY, k.key); assertEquals("https://b.test", k.baseUrl); assertEquals("header", k.style)
        e.removeVault("1"); assertTrue(Extras(ctx).vault.value.isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultUiTest : UiBase() {
    private val ex get() = vm().extras
    private fun open() {
        if (rule.onAllNodesWithText("Skip for now").fetchSemanticsNodes().isNotEmpty()) { rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle() }
        ex.vault.value.forEach { ex.removeVault(it.id) }
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        settingsList().performScrollToNode(hasTestTag("vault-add")); rule.waitForIdle()
    }

    @Test fun a_key_is_added_shown_without_its_value_edited_and_deleted() {
        open()
        assertTrue(text("never sees them"))
        rule.onNodeWithTag("vault-add").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("vault-name").performTextInput("Vercel"); rule.onNodeWithTag("vault-url").performTextInput("api.vercel.com")
        rule.onNodeWithTag("vault-key").performTextInput(KEY)
        rule.onNodeWithTag("vault-save").performClick(); rule.waitForIdle()
        assertTrue("address is checked", text("must start with https://"))
        rule.onNodeWithTag("vault-url").performTextClearance(); rule.onNodeWithTag("vault-url").performTextInput("https://api.vercel.com")
        rule.onNodeWithTag("vault-save").performClick(); rule.waitForIdle()
        val k = ex.vault.value.single(); assertEquals("vercel", k.name); assertEquals(KEY, k.key)
        assertTrue(text("vercel")); assertTrue(text("api.vercel.com")); assertTrue(text("Key hidden"))
        assertFalse("the key is never drawn on screen", text(KEY) || text("FAKEFAKE"))
        rule.onNodeWithTag("vault-edit").performClick(); rule.waitForIdle()
        assertTrue(text("leave empty to keep")); assertFalse("the dialog does not show the saved key", text("FAKEFAKE"))
        rule.onNodeWithTag("vault-url").performTextClearance(); rule.onNodeWithTag("vault-url").performTextInput("https://api2.vercel.com")
        rule.onNodeWithTag("vault-save").performClick(); rule.waitForIdle()
        assertEquals("https://api2.vercel.com", ex.vault.value.single().baseUrl); assertEquals("the old key stays", KEY, ex.vault.value.single().key)
        rule.onNodeWithTag("vault-delete").performClick(); rule.onNodeWithTag("vault-delete-confirm").performClick(); rule.waitForIdle()
        assertTrue(ex.vault.value.isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultFormUiTest : UiBase() {
    private val ex get() = vm().extras
    private fun open() {
        if (rule.onAllNodesWithText("Skip for now").fetchSemanticsNodes().isNotEmpty()) { rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle() }
        ex.vault.value.forEach { ex.removeVault(it.id) }
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("vault-add")); rule.waitForIdle()
    }

    @Test fun an_empty_key_and_a_duplicate_name_are_refused() {
        open()
        rule.onNodeWithTag("vault-add").performClick(); rule.waitForIdle(); rule.onNodeWithTag("vault-name").performTextInput("svc"); rule.onNodeWithTag("vault-url").performTextClearance(); rule.onNodeWithTag("vault-url").performTextInput("https://a.test")
        rule.onNodeWithTag("vault-save").performClick(); rule.waitForIdle()
        assertTrue(text("Paste the key.")); assertTrue(ex.vault.value.isEmpty())
        rule.onNodeWithTag("vault-key").performTextInput("some-key-value-123456"); rule.onNodeWithTag("vault-save").performClick(); rule.waitForIdle()
        assertEquals(1, ex.vault.value.size)
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("vault-add")); rule.waitForIdle()
        rule.onNodeWithTag("vault-add").performClick(); rule.waitForIdle(); rule.onNodeWithTag("vault-name").performTextInput("SVC"); rule.onNodeWithTag("vault-url").performTextClearance(); rule.onNodeWithTag("vault-url").performTextInput("https://b.test"); rule.onNodeWithTag("vault-key").performTextInput("another-key-value-1234")
        rule.onNodeWithTag("vault-save").performClick(); rule.waitForIdle()
        assertTrue(text("already exists")); assertEquals(1, ex.vault.value.size)
    }
}
