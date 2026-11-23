package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.MemoryRepo
import com.aura.agent.agent.MemoryRules
import com.aura.agent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class MemoryRulesTest {
    private fun mem(k: String, v: String, t: Long, c: String = "fact") = MemoryEntity(k, v, c, t)

    @Test fun keys_and_categories_are_normalised() {
        assertEquals("preferred_language", MemoryRules.normKey("  Preferred Language! "))
        assertEquals("app_package_name", MemoryRules.normKey("App.Package-Name"))
        assertEquals(60, MemoryRules.normKey("a".repeat(200)).length)
        assertEquals("note", MemoryRules.normCategory(null)); assertEquals("note", MemoryRules.normCategory("  "))
        assertEquals("preference", MemoryRules.normCategory("Preference")); assertEquals(20, MemoryRules.normCategory("x".repeat(50)).length)
    }

    @Test fun secrets_are_recognised_but_normal_text_is_not() {
        // all of these are made-up shapes of real credentials, not real credentials
        val secrets = listOf("sk-FAKEFAKEFAKEFAKEFAKEFAKEFAKE1234", "ghp_FAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKE1234", "github_pat_FAKEFAKEFAKEFAKEFAKE_FAKEFAKEFAKE",
            "AIzaFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKE12", "AQ.FAKEFAKEFAKEFAKEFAKEFAKEFAKE", "xoxb-1234567890-fakefake", "-----BEGIN RSA PRIVATE KEY-----",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.fake", "0123456789abcdef0123456789abcdef0123456789abcdef")
        for (s in secrets) assertTrue("should be refused: $s", MemoryRules.looksSecret(s))
        for (ok in listOf("I prefer short answers in Hinglish", "task-list app with package com.example.todo", "My repo is octo/notes-app and I use Android 14", "Deadline is 2026-12-01")) assertFalse(ok, MemoryRules.looksSecret(ok))
    }

    @Test fun prompt_block_is_newest_first_and_capped() {
        assertEquals("", MemoryRules.block(emptyList()))
        val many = (1..60).map { mem("key_$it", "value $it", it.toLong()) }
        val b = MemoryRules.block(many)
        assertTrue(b.contains("Saved memory about the user") && b.contains("save_memory") && b.contains("Never save secrets"))
        assertTrue("newest first", b.indexOf("key_60") < b.indexOf("key_59") && b.indexOf("key_59") < b.indexOf("key_30"))
        assertEquals(40, Regex("- \\[fact] key_").findAll(b).count()); assertTrue(b.contains("(+20 more saved memories"))
        val huge = (1..10).map { mem("big_$it", "x".repeat(900), it.toLong()) }
        assertTrue("character budget respected", MemoryRules.block(huge).length < 3000 + 600)
        assertFalse("multi-line values stay on one line", MemoryRules.block(listOf(mem("k", "line1\nline2", 1))).contains("line1\nline2"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryAgentTest {
    @Test fun agent_saves_searches_and_deletes_memories_with_its_tools() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += m.tool("save_memory", """{"key":"Preferred Language","value":"Hinglish, short answers","category":"Preference"}""")
        m.script += m.tool("search_memory", """{"query":"hinglish"}""")
        m.script += m.tool("delete_memory", """{"key":"preferred language"}""")
        m.script += m.tool("search_memory", """{"query":"hinglish"}""")
        m.script += m.text("done")
        val t = Env.run(app).tools
        assertEquals("Saved memory 'preferred_language' [preference].", t[0].content)
        assertTrue(t[1].content, t[1].content.contains("- [preference] preferred_language: Hinglish, short answers"))
        assertEquals("Deleted memory 'preferred_language'.", t[2].content)
        assertEquals("No saved memory matches.", t[3].content)
        assertEquals(0, runBlocking { app.db.dao().memoryCount() })
    }

    @Test fun a_brand_new_chat_starts_with_everything_saved_earlier() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += m.tool("save_memory", """{"key":"app_package","value":"com.example.todo","category":"project"}""")
        m.script += m.text("saved")
        Env.run(app, "remember my package name")
        m.script += m.text("hello again")
        Env.run(app, "hi, new chat")   // a different chat id
        val second = m.mainRequests.last()
        assertTrue(second, second.contains("Saved memory about the user") && second.contains("- [project] app_package: com.example.todo"))
    }

    @Test fun without_memories_the_prompt_has_no_memory_block() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += m.text("hi")
        Env.run(app)
        assertFalse(m.mainRequests.last().contains("Saved memory about the user"))
        assertTrue("the agent is still told about its memory tools", m.mainRequests.last().contains("save_memory"))
    }

    @Test fun secrets_and_bad_input_are_refused_by_the_tool_and_nothing_is_stored() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += m.tool("save_memory", """{"key":"my_key","value":"sk-FAKEFAKEFAKEFAKEFAKEFAKEFAKE1234","category":"fact"}""")
        m.script += m.tool("save_memory", """{"key":"  ","value":"x"}""")
        m.script += m.tool("save_memory", """{"key":"long","value":"${"y".repeat(1200)}"}""")
        m.script += m.tool("delete_memory", """{"key":"never_saved"}""")
        m.script += m.text("ok")
        val t = Env.run(app).tools
        assertTrue(t[0].content, t[0].isError && t[0].content.contains("Secrets are never saved"))
        assertTrue(t[1].isError); assertTrue(t[2].content, t[2].content.contains("longer than"))
        assertTrue(t[3].content, t[3].isError && t[3].content.contains("no memory"))
        assertEquals(0, runBlocking { app.db.dao().memoryCount() })
    }

    @Test fun memory_is_capped_but_existing_entries_can_still_be_updated() = runBlocking {
        val m = Mock().start(); val app = Env.setup(m)
        val repo = MemoryRepo(app); val dao = app.db.dao()
        repeat(MemoryRules.MAX_ENTRIES) { dao.upsertMemory(MemoryEntity("k$it", "v", "fact", it.toLong())) }
        assertTrue(repo.save("one_too_many", "x", null).exceptionOrNull()!!.message!!.contains("full"))
        assertTrue(repo.save("k5", "updated", "note").isSuccess); assertEquals("updated", dao.memory("k5")!!.value)
    }

    @Test fun search_ranks_by_matching_words_and_respects_the_limit() = runBlocking {
        val m = Mock().start(); val app = Env.setup(m); val repo = MemoryRepo(app)
        repo.save("editor", "I use Android Studio on Linux", "preference"); repo.save("os", "My laptop runs Linux", "fact"); repo.save("food", "likes paneer", "preference")
        val hits = repo.search("android linux")
        assertEquals(listOf("editor", "os"), hits.map { it.key })
        assertEquals(1, repo.search("linux", limit = 1).size); assertEquals(3, repo.search("").size)
        assertTrue(repo.search("zebra").isEmpty())
    }

    @Test fun the_pinned_repository_is_kept_in_memory_and_in_the_settings() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += m.tool("save_memory", """{"key":"pinned_repo","value":"octo/r","category":"fact"}""")
        m.script += m.text("pinned")
        val r = Env.run(app, "always use octo/r")
        assertEquals("octo/r", app.store.pinnedRepo.value); assertEquals("octo/r", app.store.defaultRepo())
        assertEquals("pinned repo entries are always filed under project", "project", runBlocking { app.db.dao().memory("pinned_repo") }!!.category)
        m.script += m.text("ok"); Env.run(app, "next chat")
        assertTrue(m.mainRequests.last().contains("pinned_repo: octo/r"))
        runBlocking { MemoryRepo(app).delete("pinned_repo") }
        assertEquals("", app.store.pinnedRepo.value); assertEquals("", app.store.defaultRepo())
        assertEquals(1, r.tools.size)
    }

    @Test fun new_chats_start_with_the_pinned_or_the_last_used_repository() {
        val m = Mock().start(); val app = Env.setup(m); val s = app.store
        assertEquals("", s.defaultRepo())
        s.lastRepo = "octo/last"; assertEquals("octo/last", s.defaultRepo())
        s.setPinnedRepo("octo/pinned"); assertEquals("a pinned repository wins", "octo/pinned", s.defaultRepo())
        s.setPinnedRepo(""); s.setRememberLastRepo(false); assertEquals("", s.defaultRepo())
        s.setRememberLastRepo(true); assertEquals("octo/last", s.defaultRepo())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryUiTest : UiBase() {
    private fun seed(app: App) = runBlocking {
        val dao = app.db.dao()
        dao.memoriesOnce().forEach { dao.deleteMemory(it.key) }
        dao.upsertMemory(MemoryEntity("preferred_language", "Hinglish, short answers", "preference", 3))
        dao.upsertMemory(MemoryEntity("app_package", "com.example.todo", "project", 2))
        dao.upsertMemory(MemoryEntity("editor", "Android Studio", "fact", 1))
    }

    private fun setText(tag: String, v: String) = rule.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString(v)) }

    private fun waitFor(c: () -> Boolean): Boolean { val end = System.currentTimeMillis() + 6000; while (System.currentTimeMillis() < end) { rule.waitForIdle(); if (c()) return true; Thread.sleep(50) }; return c() }

    @Test fun memory_screen_lists_edits_adds_and_deletes() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App; val dao = app.db.dao()
        seed(app)
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        settingsList().performScrollToNode(hasTestTag("memory-entry")); rule.waitForIdle()
        assertTrue(text("3 saved"))
        rule.onNodeWithTag("memory-entry").performClick(); rule.waitForIdle()
        assertTrue("list", text("preferred_language") || text("Hinglish, short answers") || text("Android Studio"))
        // edit the value
        rule.onNodeWithTag("memory-list").performScrollToNode(hasText("Android Studio")); rule.waitForIdle()
        rule.onNodeWithText("Android Studio").performClick(); rule.waitForIdle()
        assertTrue(text("Edit memory"))
        setText("memory-value", "Android Studio and VS Code"); rule.waitForIdle()
        rule.onNodeWithTag("memory-save").performClick()
        assertTrue(waitFor { runBlocking { dao.memory("editor") }?.value == "Android Studio and VS Code" })
        // rename a key: the old entry goes away
        rule.onNodeWithTag("memory-list").performScrollToNode(hasText("com.example.todo")); rule.waitForIdle()
        rule.onNodeWithText("com.example.todo").performClick(); rule.waitForIdle()
        setText("memory-key", "Package Name"); rule.waitForIdle()
        rule.onNodeWithTag("memory-save").performClick()
        assertTrue(waitFor { runBlocking { dao.memory("package_name") } != null && runBlocking { dao.memory("app_package") } == null })
        // add
        rule.onNodeWithTag("memory-add").performClick(); rule.waitForIdle()
        assertTrue(text("Add a memory"))
        setText("memory-key", "Birthday"); setText("memory-value", "12 March"); rule.waitForIdle()
        rule.onNodeWithTag("memory-save").performClick()
        assertTrue(waitFor { runBlocking { dao.memory("birthday") }?.value == "12 March" })
        // delete needs a confirmation
        rule.onNodeWithTag("memory-list").performScrollToNode(hasTestTag("delete-birthday")); rule.waitForIdle()
        rule.onNodeWithTag("delete-birthday").performClick(); rule.waitForIdle()
        assertTrue(text("Forget this?")); assertNotNull(runBlocking { dao.memory("birthday") })
        rule.onNodeWithTag("memory-confirm-delete").performClick()
        assertTrue(waitFor { runBlocking { dao.memory("birthday") } == null })
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryPinUiTest : UiBase() {
    private fun waitFor(c: () -> Boolean): Boolean { val end = System.currentTimeMillis() + 6000; while (System.currentTimeMillis() < end) { rule.waitForIdle(); if (c()) return true; Thread.sleep(50) }; return c() }

    @Test fun search_empty_state_and_pinned_repository() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        runBlocking { app.db.dao().upsertMemory(MemoryEntity("editor", "Android Studio", "fact", 1)) }; app.store.setPinnedRepo("octo/pinned-app")
        rule.runOnUiThread { vm().go(Route.Memory) }; rule.waitForIdle()
        if (!text("Pinned repository")) println("TREE: " + rule.onRoot().printToString(60) + " ROUTE=" + vm().route.value + " MEM=" + runBlocking { app.db.dao().memoriesOnce().size })
        assertTrue(text("Pinned repository") && text("octo/pinned-app"))
        rule.onNodeWithText("Unpin").performClick()
        assertTrue(waitFor { app.store.pinnedRepo.value == "" })
        assertFalse(text("Pinned repository"))
        runBlocking { app.db.dao().memoriesOnce().forEach { app.db.dao().deleteMemory(it.key) } }
        assertTrue(waitFor { text("No memories yet") })
    }
}
