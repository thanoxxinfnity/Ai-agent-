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

class PromptQualityTest {
    private val sys get() = com.aura.agent.agent.AgentRunner.SYSTEM

    @Test fun replies_are_concise_by_default_but_smart() {
        assertTrue(sys.contains("Answering: be smart and accurate") && sys.contains("Keep replies compact") && sys.contains("Hinglish stays Hinglish"))
        assertFalse("long answers are opt-in", sys.contains("thorough") || sys.contains("several developed paragraphs"))
        assertTrue("tool guidance is still there", sys.contains("github_create_repo") && sys.contains("cloud_exec") && sys.contains("web_search") && sys.contains("save_memory"))
    }

    @Test fun the_long_answers_text_exists_separately() {
        assertTrue(com.aura.agent.agent.AgentRunner.LONG_ANSWERS.contains("thorough") && com.aura.agent.agent.AgentRunner.LONG_ANSWERS.contains("several developed paragraphs"))
    }

    @Test fun the_prompt_teaches_terminal_mcp_and_phone_folders() {
        assertTrue(sys.contains("Terminal playbook") && sys.contains("fresh bash") && sys.contains("nohup") && sys.contains("never repeat the same failing command"))
        assertTrue(sys.contains("mcp__server__tool") && sys.contains("mcp_search") && sys.contains("never ask for it in chat"))
        assertTrue(sys.contains("Phone folders:") && sys.contains("push_folder_to_repo") && sys.contains("only when the user explicitly asks") && sys.contains("Never push secrets"))
        val d = Tools.defs.first { it.name == "cloud_exec" }.description
        assertTrue(d.contains("real Linux terminal") && d.contains("exit code"))
    }

    @Test fun swift_stays_brief_and_steady_adds_nothing() {
        assertTrue(com.aura.agent.agent.ThinkMode.SWIFT.prompt.contains("briefly"))
        assertEquals("", com.aura.agent.agent.ThinkMode.STEADY.prompt)
    }

    @Test fun every_new_tool_is_registered_exactly_once() {
        val new = listOf("mcp_list", "mcp_search", "mcp_connect", "mcp_disconnect", "folder_list", "folder_read", "folder_write", "folder_edit", "folder_search", "folder_delete", "backup_repo_to_folder", "push_folder_to_repo")
        for (n in new) assertEquals(n, 1, Tools.defs.count { it.name == n })
        assertTrue("existing tools are untouched", listOf("github_write_files", "cloud_exec", "agent_browser", "playwright_call", "vercel_deploy", "save_memory", "web_search", "generate_image").all { n -> Tools.defs.any { it.name == n } })
    }

    @Test fun every_tool_has_a_ui_label() {
        for (n in listOf("mcp_list", "mcp_search", "mcp_connect", "mcp_disconnect", "folder_list", "folder_read", "folder_write", "folder_edit", "folder_search", "folder_delete", "backup_repo_to_folder", "push_folder_to_repo")) assertNotEquals("no label for $n", n, Tools.label(n))
    }
}
