package com.aura.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.Extras
import com.aura.agent.net.McpServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExtrasStoreTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun long_answers_is_off_by_default_and_survives_a_restart() {
        val e = Extras(ctx); e.setLongAnswers(false)
        assertFalse(Extras(ctx).longAnswers.value)
        e.setLongAnswers(true); assertTrue(Extras(ctx).longAnswers.value)
        e.setLongAnswers(false); assertFalse(Extras(ctx).longAnswers.value)
    }

    @Test fun mcp_servers_and_their_tokens_survive_a_restart_and_can_be_switched_and_removed() {
        val e = Extras(ctx); e.mcpServers.value.forEach { e.removeMcp(it.id) }
        e.upsertMcp(McpServer("a", "Alpha", "https://a.test/mcp", mapOf("Authorization" to "Bearer FAKE-123")))
        e.upsertMcp(McpServer("b", "Beta", "https://b.test/mcp"))
        e.upsertMcp(McpServer("a", "Alpha 2", "https://a.test/mcp", mapOf("Authorization" to "Bearer FAKE-456")))
        val again = Extras(ctx).mcpServers.value
        assertEquals(listOf("Alpha 2", "Beta"), again.map { it.name }); assertEquals("Bearer FAKE-456", again[0].headers["Authorization"])
        e.setMcpEnabled("b", false); assertFalse(Extras(ctx).mcpServers.value.first { it.id == "b" }.enabled)
        e.removeMcp("a"); assertEquals(listOf("b"), Extras(ctx).mcpServers.value.map { it.id })
    }

    @Test fun phone_folders_keep_unique_names_and_the_connected_flag() {
        val e = Extras(ctx); e.folders.value.forEach { e.removeFolder(it.id) }
        val a = e.addFolder("Backups", "content://x/tree/a"); val b = e.addFolder("backups", "content://x/tree/b"); val same = e.addFolder("Other", "content://x/tree/a")
        assertEquals("Backups", a.name); assertEquals("backups (2)", b.name); assertEquals("the same folder is not added twice", a.id, same.id)
        e.setFolderEnabled(a.id, false)
        val again = Extras(ctx)
        assertEquals(listOf(b.id), again.enabledFolders().map { it.id }); assertEquals(2, again.folders.value.size)
        e.removeFolder(a.id); assertEquals(1, Extras(ctx).folders.value.size)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LongAnswersAgentTest {
    @Test fun the_model_gets_the_long_answers_instruction_only_when_the_switch_is_on() {
        val m = Mock().start(); val app = Env.setup(m)
        app.extras.setLongAnswers(false)
        Env.run(app, "explain coroutines")
        assertFalse("off: concise default", m.mainRequests.last().contains("Long answers are switched on"))
        assertTrue(m.mainRequests.last().contains("Keep replies compact"))
        app.extras.setLongAnswers(true)
        Env.run(app, "explain coroutines again")
        assertTrue("on: thorough", m.mainRequests.last().contains("Long answers are switched on") && m.mainRequests.last().contains("Keep replies compact"))
        app.extras.setLongAnswers(false)
    }
}
