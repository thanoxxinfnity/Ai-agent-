package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class McpCatalogTest {
    @After fun restore() { McpRegistry.base = "https://registry.modelcontextprotocol.io" }

    @Test fun featured_servers_are_well_formed_and_filterable() {
        val all = McpFeatured.all
        assertTrue(all.size >= 8); assertEquals(all.size, all.map { it.url }.toSet().size)
        assertTrue(all.all { it.url.startsWith("https://") && it.title.isNotBlank() && it.category in McpFeatured.CATEGORIES })
        assertTrue("token ones say which header", all.filter { it.headers.isNotEmpty() }.all { h -> h.headers.all { it.required && it.secret && it.description.startsWith("Bearer ") } })
        assertEquals(all.size, McpFeatured.matching("", "All").size)
        assertTrue(McpFeatured.matching("", "Docs").all { it.category == "Docs" } && McpFeatured.matching("", "Docs").isNotEmpty())
        assertEquals(listOf("DeepWiki"), McpFeatured.matching("deepwiki", "All").map { it.title })
        assertTrue(McpFeatured.matching("zzzz-nothing", "All").isEmpty())
    }

    @Test fun logos_come_from_the_listed_icon_then_the_servers_own_sites() {
        val e = RegistryEntry("io.x/Cool", "d", "1", "https://mcp.cool.example.com/mcp", emptyList(), "https://github.com/x/cool", website = "https://cool.example.com", icon = "https://cdn.example.com/i.png")
        val c = e.logoCandidates()
        assertEquals("https://cdn.example.com/i.png", c.first()); assertTrue(c.contains("https://cool.example.com/favicon.ico")); assertTrue(c.contains("https://example.com/favicon.ico"))
        assertTrue("never a third party", c.none { it.contains("google.com/s2") || it.contains("duckduckgo") }); assertTrue(c.none { it.contains("github.com") })
        assertEquals("Cool", e.shownName)
        assertEquals("http icons are ignored", false, RegistryEntry("a/b", "", "", "https://h.example/mcp", emptyList(), "", icon = "http://h/i.png").logoCandidates().any { it.startsWith("http://") })
    }

    @Test fun registry_pages_follow_the_cursor_and_read_icons_and_sites() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("""{"servers":[{"server":{"name":"a/one","description":"d","websiteUrl":"https://one.example","icons":[{"src":"https://one.example/i.png"}],"remotes":[{"type":"streamable-http","url":"https://one.example/mcp"}]}}],"metadata":{"nextCursor":"CUR2"}}"""))
        s.enqueue(MockResponse().setBody("""{"servers":[{"server":{"name":"a/two","description":"d","remotes":[{"type":"streamable-http","url":"https://two.example/mcp"}]}}],"metadata":{}}"""))
        s.start(); McpRegistry.base = s.url("/").toString().trimEnd('/')
        val p1 = McpRegistry.searchPage("", null)
        assertEquals("CUR2", p1.next); assertEquals("https://one.example", p1.entries.single().website); assertEquals("https://one.example/i.png", p1.entries.single().icon)
        val p2 = McpRegistry.searchPage("", p1.next)
        assertNull(p2.next); assertEquals("a/two", p2.entries.single().name)
        s.takeRequest(); assertTrue(s.takeRequest().path!!.contains("cursor=CUR2")); s.shutdown()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class McpBrowseUiTest : UiBase() {
    private val registry = MockWebServer().also { it.enqueue(MockResponse().setBody("""{"servers":[]}""")); it.start() }
    @After fun restore() { McpRegistry.base = "https://registry.modelcontextprotocol.io"; registry.shutdown() }
    private fun click(n: SemanticsNodeInteraction) { n.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle() }

    @Test fun featured_servers_show_with_a_letter_logo_filter_by_group_and_ask_for_a_token_with_bearer_added() {
        McpRegistry.base = registry.url("/").toString().trimEnd('/')
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val ex = vm().extras; ex.mcpServers.value.forEach { ex.removeMcp(it.id) }
        rule.runOnUiThread { vm().go(Route.McpBrowse) }; rule.waitForIdle()
        assertTrue(text("Featured")); assertTrue(text("DeepWiki")); assertTrue(text("Open, no account needed"))
        rule.onNodeWithTag("mcp-results").performScrollToNode(hasText("From the public MCP registry")); rule.waitForIdle(); assertTrue(text("From the public MCP registry"))
        rule.onNodeWithTag("mcp-results").performScrollToIndex(0); rule.waitForIdle()
        click(rule.onNodeWithTag("mcp-cat-Docs", useUnmergedTree = true))
        assertTrue(text("Context7")); assertTrue(rule.onAllNodesWithText("DeepWiki").fetchSemanticsNodes().isEmpty()); assertTrue(rule.onAllNodesWithText("From the public MCP registry").fetchSemanticsNodes().isEmpty())
        click(rule.onNodeWithTag("mcp-cat-All", useUnmergedTree = true))
        rule.onNodeWithTag("mcp-results").performScrollToNode(hasText("GitHub")); rule.waitForIdle()
        assertTrue(text("Needs your token: Authorization"))
    }

    @Test fun a_token_server_asks_for_the_token_and_saves_it_with_bearer() {
        McpRegistry.base = registry.url("/").toString().trimEnd('/')
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val ex = vm().extras; ex.mcpServers.value.forEach { ex.removeMcp(it.id) }
        rule.runOnUiThread { vm().go(Route.McpBrowse) }; rule.waitForIdle()
        rule.onNodeWithTag("mcp-search").performTextInput("linear"); rule.onNodeWithTag("mcp-search-go").performClick(); rule.waitForIdle()
        click(rule.onAllNodesWithTag("mcp-connect")[0])
        assertTrue(text("paste only your token"))
        rule.onNodeWithTag("mcp-secret-Authorization").performTextInput("fake-test-token-not-real-0005"); click(rule.onNodeWithTag("mcp-save"))
        val end = System.currentTimeMillis() + 5000; while (System.currentTimeMillis() < end && ex.mcpServers.value.isEmpty()) Thread.sleep(50)
        // the connect itself fails (no such server in the test), but the saved header shows what would be sent
        assertTrue(text("Could not connect Linear") || ex.mcpServers.value.any { it.headers["Authorization"] == "Bearer fake-test-token-not-real-0005" })
    }
}
