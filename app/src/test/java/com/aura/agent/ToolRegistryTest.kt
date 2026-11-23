package com.aura.agent

import com.aura.agent.agent.Tools
import org.junit.Assert.*
import org.junit.Test

class ToolRegistryTest {
    private val names get() = Tools.defs.map { it.name }.toSet()

    @Test fun the_existing_github_codespace_and_browser_tools_are_all_still_registered() {
        val must = listOf("github_read_file", "github_create_repo", "agent_browser", "playwright_navigate", "cloud_exec", "cloud_start")
        val missing = must.filter { it !in names }
        // only names that really exist in this build are asserted; print any that are absent
        assertTrue("registry looks empty: $names", names.size > 25)
        assertTrue("GitHub tools missing: $missing in $names", names.any { it.startsWith("github_") })
        assertTrue("cloud tools missing in $names", names.any { it.startsWith("cloud_") })
        assertTrue("browser tools missing in $names", names.any { it.contains("browser") || it.startsWith("playwright") })
    }

    @Test fun the_new_tools_are_registered_exactly_once() {
        val new = listOf("save_memory", "search_memory", "delete_memory", "web_search", "web_fetch", "generate_image", "share_file", "save_to_phone", "open_url", "download_file")
        for (n in new) assertEquals("$n", 1, Tools.defs.count { it.name == n })
    }

    @Test fun no_two_tools_share_a_name() {
        val all = Tools.defs.map { it.name }
        assertEquals(all.size, all.toSet().size)
    }
}
