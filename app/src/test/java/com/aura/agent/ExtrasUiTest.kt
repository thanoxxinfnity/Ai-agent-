package com.aura.agent

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.Provider
import com.aura.agent.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

private fun kiloModels() = """{"data":[
 {"id":"kilo-auto/free","name":"Auto Free","isFree":true,"architecture":{"output_modalities":["text"]}},
 {"id":"vendor/small:free","isFree":true,"architecture":{"output_modalities":["text"]}},
 {"id":"vendor/paid-big","isFree":false,"architecture":{"output_modalities":["text"]}},
 {"id":"vendor/free-image","isFree":true,"architecture":{"output_modalities":["image"]}},
 {"id":"google/gemini-live-x:free","isFree":true,"architecture":{"output_modalities":["text"]}},
 {"id":"vendor/tagged:free","architecture":{"output_modalities":["text"]}}]}"""

class FreeCatalogTest {
    private fun server(body: String, code: Int = 200): MockWebServer { val s = MockWebServer(); s.enqueue(MockResponse().setResponseCode(code).setBody(body)); s.start(); return s }
    private fun entry(s: MockWebServer) = FreeCatalog.Entry("kilo", "Kilo Code (free models)", s.url("/api/gateway").toString().trimEnd('/'), s.url("/api/gateway/models").toString(), "x", "kilo-auto/free")

    @Test fun only_free_text_chat_models_are_kept_and_the_auto_model_comes_first() = runBlocking {
        val l = FreeCatalog.models(entry(server(kiloModels())))
        assertEquals(listOf("kilo-auto/free", "vendor/small:free", "vendor/tagged:free"), l.models)
    }

    @Test fun an_empty_or_failing_list_is_a_clear_error() = runBlocking {
        assertTrue(runCatching { FreeCatalog.models(entry(server("""{"data":[{"id":"x/paid","isFree":false}]}"""))) }.exceptionOrNull()!!.message!!.contains("no free models"))
        assertTrue(runCatching { FreeCatalog.models(entry(server("err", 503))) }.exceptionOrNull()!!.message!!.contains("503"))
    }

    @Test fun connected_is_found_by_the_base_url() {
        val e = FreeCatalog.all.first { it.id == "kilo" }
        assertNull(FreeCatalog.connected(e, emptyList()))
        assertNotNull(FreeCatalog.connected(e, listOf(Provider("p", "x", e.baseUrl + "/"))))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExtrasUiTest : UiBase() {
    private val original = FreeCatalog.all
    private val mcp = FakeMcp().start()
    private val kilo = MockWebServer().also { s -> s.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody(kiloModels()) }; s.start() }
    private val registry = MockWebServer().also { s ->
        s.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"servers":[
          {"server":{"name":"io.demo/open","description":"An open demo server","remotes":[{"type":"streamable-http","url":"${mcp.url}"}]}},
          {"server":{"name":"io.demo/keyed","description":"Needs a token","remotes":[{"type":"streamable-http","url":"${mcp.url}2","headers":[{"name":"Authorization","isRequired":true,"isSecret":true}]}]}}]}""") }
        s.start()
    }

    @After fun restore() { FreeCatalog.all = original; McpRegistry.base = "https://registry.modelcontextprotocol.io" }

    // Robolectric keeps the first test's ViewModel for the whole class, so every test talks to the objects the screen really uses.
    private val ex get() = vm().extras
    private val hub get() = vm().mcp
    private val st get() = vm().store

    private fun skipLogin() { if (rule.onAllNodesWithText("Skip for now").fetchSemanticsNodes().isNotEmpty()) { rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle() } }

    private fun openSettings() {
        skipLogin()
        ex.folders.value.forEach { ex.removeFolder(it.id) }
        ex.mcpServers.value.forEach { hub.forget(it.id); ex.removeMcp(it.id) }
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
    }
    private fun toggle(n: SemanticsNodeInteraction) { n.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle() }
    private fun scrollTo(tag: String) { settingsList().performScrollToNode(hasTestTag(tag)); rule.waitForIdle() }
    private fun waitFor(ms: Long = 5000, cond: () -> Boolean): Boolean { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); rule.waitForIdle(); if (cond()) return true; Thread.sleep(50) }; return false }

    @Test fun the_long_answers_switch_is_off_by_default_and_remembers_its_state() {
        openSettings(); ex.setLongAnswers(false)
        scrollTo("long-answers")
        assertTrue(text("Long, detailed answers"))
        toggle(rule.onNodeWithTag("long-answers").assertIsOff())
        assertTrue(ex.longAnswers.value); rule.onNodeWithTag("long-answers").assertIsOn()
        toggle(rule.onNodeWithTag("long-answers")); assertFalse(ex.longAnswers.value)
    }

    @Test fun a_free_provider_is_connected_and_disconnected_with_one_switch() {
        FreeCatalog.all = listOf(FreeCatalog.Entry("kilo", "Kilo Code (free models)", kilo.url("/api/gateway").toString().trimEnd('/'), kilo.url("/api/gateway/models").toString(), "Free models, no key.", "kilo-auto/free"))
        openSettings(); st.providers.value.forEach { st.deleteProvider(it.id) }
        scrollTo("free-kilo"); assertTrue(text("Kilo Code (free models)"))
        toggle(rule.onNodeWithTag("free-kilo").assertIsOff())
        assertTrue("connects", waitFor { st.providers.value.any { it.name.startsWith("Kilo") } })
        val p = st.providers.value.first { it.name.startsWith("Kilo") }
        assertEquals(listOf("kilo-auto/free", "vendor/small:free", "vendor/tagged:free"), p.models); assertEquals("kilo-auto/free", p.defaultModel); assertEquals("", p.apiKey)
        assertEquals("kilo-auto/free", st.activeModel.value)
        assertTrue(waitFor { text("Connected - 3 models") })
        toggle(rule.onNodeWithTag("free-kilo").assertIsOn())
        assertTrue("disconnects", waitFor { st.providers.value.none { it.name.startsWith("Kilo") } })
    }

    @Test fun a_server_is_added_connected_switched_off_and_removed_in_the_mcp_screen() {
        openSettings()
        scrollTo("mcp-entry"); rule.onNodeWithTag("mcp-entry").performClick(); rule.waitForIdle()
        assertTrue(text("Nothing added yet"))
        rule.onNodeWithTag("mcp-add").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("mcp-name").performTextInput("Demo"); rule.onNodeWithTag("mcp-url").performTextInput(mcp.url)
        rule.onNodeWithTag("mcp-headers").performTextInput("X-Api-Key: FAKE-KEY-123")
        rule.onNodeWithTag("mcp-save").performClick()
        assertTrue("connects", waitFor { text("Connected - 3 tools") })
        val s = ex.mcpServers.value.single(); assertEquals("Demo", s.name); assertEquals("FAKE-KEY-123", s.headers["X-Api-Key"])
        assertEquals("the header reached the server", "FAKE-KEY-123", mcp.seen.first().getHeader("X-Api-Key"))
        toggle(rule.onNodeWithTag("mcp-switch").assertIsOn())
        assertTrue(waitFor { text("Disconnected") }); assertFalse(ex.mcpServers.value.single().enabled); assertTrue(hub.defs().isEmpty())
        toggle(rule.onNodeWithTag("mcp-switch"))
        assertTrue("reconnects", waitFor { text("Connected - 3 tools") })
        rule.onNodeWithTag("mcp-delete").performClick(); rule.onNodeWithTag("mcp-delete-confirm").performClick(); rule.waitForIdle()
        assertTrue(ex.mcpServers.value.isEmpty()); assertTrue(hub.defs().isEmpty())
    }

    @Test fun a_bad_address_is_refused_in_the_form() {
        openSettings(); scrollTo("mcp-entry"); rule.onNodeWithTag("mcp-entry").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("mcp-add").performClick(); rule.onNodeWithTag("mcp-url").performTextInput("not-a-url"); rule.onNodeWithTag("mcp-save").performClick(); rule.waitForIdle()
        assertTrue(text("must start with https://"))
    }

    @Test fun browsing_the_registry_connects_an_open_server_and_asks_for_the_token_of_a_keyed_one() {
        openSettings(); McpRegistry.base = registry.url("/").toString().trimEnd('/')
        scrollTo("mcp-entry"); rule.onNodeWithTag("mcp-entry").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("mcp-browse").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("mcp-search").performTextInput("demo"); rule.onNodeWithTag("mcp-search-go").performClick(); rule.waitForIdle()   // no featured server matches "demo", so the first rows are the registry's
        assertTrue("results", waitFor { text("An open demo server") }); assertTrue(text("Open, no account needed"))
        rule.onAllNodesWithTag("mcp-connect")[0].performClick()
        assertTrue("open server connected", waitFor { ex.mcpServers.value.any { it.name == "open" } && hub.defs().isNotEmpty() })
        rule.onNodeWithTag("mcp-results").performScrollToNode(hasText("Needs your token: Authorization")); rule.waitForIdle(); assertTrue(text("Needs your token: Authorization"))
        rule.onAllNodesWithTag("mcp-connect").onLast().performClick(); rule.waitForIdle()
        assertTrue("asks for the secret instead of connecting", text("Authorization"))
        rule.onNodeWithTag("mcp-save").performClick(); rule.waitForIdle()
        assertTrue("an empty token is refused", text("Fill in Authorization"))
        assertEquals(1, ex.mcpServers.value.size)
    }

    @Test fun phone_folders_are_listed_switched_and_removed_in_settings() {
        openSettings(); val dir = File(rule.activity.cacheDir, "ui-folder").also { it.mkdirs() }
        ex.addFolder("Backups", Uri.fromFile(dir).toString()); ex.addFolder("Projects", Uri.fromFile(File(rule.activity.cacheDir, "p")).toString())
        scrollTo("folder-add"); println("DBGTREE " + rule.onRoot().printToString(maxDepth = 12)); println("DBGF " + ex.folders.value + " vm=" + vm().extras.folders.value); assertTrue(text("Backups")); assertTrue(text("Projects"))
        toggle(rule.onAllNodesWithTag("folder-switch")[0].assertIsOn())
        assertFalse(ex.folders.value.first { it.name == "Backups" }.enabled); assertTrue(ex.folders.value.first { it.name == "Projects" }.enabled)
        assertTrue(text("Disconnected"))
        rule.onAllNodesWithTag("folder-remove")[0].performClick(); rule.onNodeWithTag("folder-remove-confirm").performClick(); rule.waitForIdle()
        assertEquals(listOf("Projects"), ex.folders.value.map { it.name }); assertTrue("the files stay", dir.exists())
    }

    @Test fun the_folder_choice_sits_beside_the_repository_choice() {
        ex.folders.value.forEach { ex.removeFolder(it.id) }
        st.setGithub("tok_value_123", "octo")
        rule.waitForIdle()
        rule.onNodeWithText("Choose repository").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("repo-list").performScrollToNode(hasTestTag("picker-folders-toggle")); rule.waitForIdle()
        assertTrue("section is there", text("Phone folders")); assertFalse("collapsed by default", text("Add a phone folder"))
        rule.onNodeWithTag("picker-folders-toggle").performClick(); rule.waitForIdle()
        assertTrue(text("Add a phone folder"))
        assertTrue(text("Files stay on the phone unless you ask to upload them"))
        ex.addFolder("Backups", Uri.fromFile(File(rule.activity.cacheDir, "pf")).toString()); rule.waitForIdle()
        assertTrue(text("Backups"))
        toggle(rule.onNodeWithTag("picker-folder-switch").assertIsOn())
        assertFalse("switching it off disconnects it", ex.folders.value.single().enabled)
        assertEquals("choosing folders does not choose a repository", "", vm().pendingRepo.value)
    }
}
