package com.aura.agent

import android.content.Context
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.Approval
import com.aura.agent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private fun seedChat(app: App, chatId: String, rows: List<MessageEntity>) = runBlocking {
    val dao = app.db.dao()
    dao.upsertChat(ChatEntity(chatId, "Seeded chat", Ids.now(), Ids.now(), titled = true))
    rows.forEach { dao.upsertMessage(it) }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CheckpointUiTest : UiBase() {
    @Test fun restore_checkpoint_button_confirms_and_restores() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        app.store.setGithub("", "")   // restore will skip GitHub work, no network needed
        seedChat(app, "c1", listOf(
            MessageEntity("u1", "c1", "user", "change the file", Ids.now()),
            MessageEntity("a1", "c1", "assistant", "All done, file changed.", Ids.now()),
        ))
        runBlocking { app.db.dao().upsertCheckpoint(CheckpointEntity("k1", "c1", "u1", Ids.now(), "github", "o/r@main", "abc", "edit")) }
        rule.runOnUiThread { vm().openChat("c1") }
        assertTrue("chip", text("Restore checkpoint"))
        rule.onNodeWithText("Restore checkpoint").performClick()
        assertTrue("confirm dialog", text("aura-backup"))
        rule.onNodeWithText("Restore").performClick()
        assertTrue("result message", text("Checkpoint restored"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TerminalUiTest : UiBase() {
    @Test fun live_terminal_approval_and_summary_render() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        seedChat(app, "c2", listOf(
            MessageEntity("u", "c2", "user", "build it", Ids.now()),
            MessageEntity("s", "c2", "summary", "SUMMARY BODY TEXT", Ids.now()),
            MessageEntity("n", "c2", "note", "HIDDEN NUDGE TEXT", Ids.now()),
            MessageEntity("t1", "c2", "tool", "", Ids.now(), toolCallId = "x", toolName = "cloud_exec", toolArgs = "{}"),
        ))
        rule.runOnUiThread { vm().openChat("c2") }
        app.runner.live.value = mapOf("t1" to "npm run build\nCompiling module 3 of 9")
        assertTrue("live output", text("Compiling module 3"))
        assertTrue("running badge", text("Running"))
        assertTrue("summary divider", text("compacted to save context"))
        assertFalse("hidden notes never show", rule.onAllNodesWithText("HIDDEN NUDGE", substring = true).fetchSemanticsNodes().isNotEmpty())
        app.runner.approval.value = Approval("a1", "c2", "Dangerous command: recursive or forced delete (rm -rf)", "rm -rf build")
        assertTrue("approval dialog", text("Approve this action?") && text("rm -rf build"))
        rule.onNodeWithText("Deny").performClick()
        app.runner.approval.value = null
        assertTrue(text("Compiling module 3"))
        app.runner.live.value = emptyMap()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AgentSettingsUiTest : UiBase() {
    @Test fun safety_and_memory_settings() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        settingsList().performScrollToNode(hasText("32k")); rule.waitForIdle()
        assertTrue(text("Agent safety and memory") && text("Ask before dangerous commands"))
        assertTrue("guardrails default on", app.store.guardrails.value)
        rule.onNodeWithText("32k").performClick(); rule.waitForIdle()
        assertEquals(32000, app.store.contextTokens.value)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationTest {
    @Test fun v1_database_upgrades_to_v2_and_keeps_every_chat() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migrate-test.db").also { it.parentFile?.mkdirs(); it.delete() }
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL("CREATE TABLE chats (id TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, titled INTEGER NOT NULL, running INTEGER NOT NULL, PRIMARY KEY(id))")
        raw.execSQL("CREATE TABLE messages (id TEXT NOT NULL, chatId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, toolCalls TEXT, toolCallId TEXT, toolName TEXT, toolArgs TEXT, isError INTEGER NOT NULL, PRIMARY KEY(id))")
        raw.execSQL("CREATE INDEX index_messages_chatId ON messages (chatId)")
        raw.execSQL("INSERT INTO chats VALUES ('old1','My old chat',1,2,1,0)")
        raw.execSQL("INSERT INTO messages VALUES ('m1','old1','user','hello from v1',1,NULL,NULL,NULL,NULL,0)")
        raw.version = 1
        raw.close()
        // strict: no destructive fallback, so a schema mismatch would crash here exactly as it would for users
        val db = Room.databaseBuilder(ctx, AppDb::class.java, "migrate-test.db").addMigrations(AppDb.MIGRATION_1_2, AppDb.MIGRATION_2_3, AppDb.MIGRATION_3_4, AppDb.MIGRATION_4_5).allowMainThreadQueries().build()
        val dao = db.dao()
        val msgs = runBlocking { dao.messagesOnce("old1") }
        assertEquals("hello from v1", msgs.single().content)
        assertFalse(msgs.single().compacted)
        assertEquals("My old chat", runBlocking { dao.chat("old1") }!!.title)
        assertEquals("", runBlocking { dao.chat("old1") }!!.repo)
        assertNull(msgs.single().attachments)
        runBlocking {
            dao.upsertCheckpoint(CheckpointEntity("k", "old1", "t", 1, "github", "o/r@main", "sha", "x"))
            dao.markCompacted(listOf("m1"))
        }
        assertTrue(runBlocking { dao.messagesOnce("old1") }.single().compacted)
        assertEquals(1, runBlocking { dao.checkpointsForTurn("t") }.size)
        // v4: the memories table exists after the upgrade and the old chat is untouched
        runBlocking { dao.upsertMemory(MemoryEntity("pref", "dark mode", "preference", 1)) }
        assertEquals("dark mode", runBlocking { dao.memory("pref") }!!.value)
        assertEquals(1, runBlocking { dao.memoryCount() })
        assertEquals("hello from v1", runBlocking { dao.messagesOnce("old1") }.single().content)
        // v5: several repositories per chat, empty for old chats
        assertEquals("", runBlocking { dao.chat("old1") }!!.extraRepos)
        runBlocking { dao.setRepos("old1", "octo/a", "octo/b,octo/c") }
        assertEquals(listOf("octo/a", "octo/b", "octo/c"), runBlocking { dao.chat("old1") }!!.allRepos())
        db.close()
    }
}
