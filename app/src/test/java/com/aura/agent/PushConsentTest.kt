package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.Extras
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PushConsentTest {
    private val m = Mock().start()
    private val app = Env.setup(m).also { it.extras.setAskBeforePush(true) }
    private val asked = ArrayList<Pair<String, String>>()
    private fun ctx(answer: Boolean = true) = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), approve = { t, d -> asked += t to d; answer }, activeRepo = "octo/r")
    private fun run(name: String, args: String, c: ToolCtx) = runBlocking { Tools.execute(c, name, args) }
    private val push = """{"repo":"r","files":[{"path":"docs/new.md","content":"hello"},{"path":"src/B.kt","content":"fun b() {}"}]}"""

    @Test fun the_default_is_to_ask() { assertTrue(Extras(app).askBeforePush.value) }

    @Test fun a_yes_pushes_and_the_question_names_the_repo_and_files_once_per_run() {
        val c = ctx()
        val out = run("github_write_files", push, c)
        assertTrue(out, out.startsWith("Pushed 2 file(s)")); assertEquals(1, asked.size)
        assertEquals("Push to GitHub?", asked[0].first); assertTrue(asked[0].second.contains("octo/r") && asked[0].second.contains("docs/new.md") && asked[0].second.contains("keep everything on your phone"))
        run("github_write_files", push, c); assertEquals("the same yes covers the rest of the run", 1, asked.size)
    }

    @Test fun a_no_pushes_nothing_and_tells_the_model_to_keep_files_on_the_phone() {
        val c = ctx(answer = false)
        val out = run("github_write_files", push, c)
        assertTrue(out, out.startsWith("DENIED") && out.contains("send_file") && out.contains("folder_write") && out.contains("their own language"))
        assertEquals(0, m.count("POST /gh/repos/octo/r/git/blobs"))
        assertTrue(run("github_create_repo", """{"name":"x"}""", c).startsWith("DENIED"))
        assertTrue(run("edit_file_diff", """{"repo":"r","path":"src/A.kt","search":"package a","replace":"package a // x"}""", c).startsWith("DENIED"))
        assertEquals("after a no the user is not asked again in this run", 1, asked.size)
        assertEquals(0, m.log.count { it.startsWith("PUT /gh") || it.startsWith("POST /gh/user/repos") })
    }

    @Test fun every_way_of_writing_to_github_asks() {
        val q = { c: ToolCtx -> ctx(false).also { } }
        for ((name, args) in listOf(
            "github_create_repo" to """{"name":"brand-new"}""",
            "edit_file_diff" to """{"repo":"r","path":"src/A.kt","search":"package a","replace":"package a // y"}""",
        )) { asked.clear(); assertTrue(name, run(name, args, ctx(false)).startsWith("DENIED")); assertEquals(name, 1, asked.size) }
        asked.clear()
        val out = runBlocking { ToolsPro.commitBytes(ctx(false), "octo/r", "img/a.png", byteArrayOf(1, 2, 3), "x", null) }
        assertTrue(out, out.startsWith("DENIED")); assertEquals(1, asked.size)
        assertEquals(0, m.log.count { it.startsWith("PUT /gh/repos/octo/r/contents/img") })
    }

    @Test fun reading_never_asks() {
        run("github_read_file", """{"repo":"r","path":"src/A.kt"}""", ctx(false)); run("github_list_repos", "{}", ctx(false)); assertTrue(asked.isEmpty())
    }

    @Test fun with_the_setting_off_nothing_is_asked() {
        app.extras.setAskBeforePush(false)
        assertTrue(run("github_write_files", push, ctx(false)).startsWith("Pushed 2 file(s)")); assertTrue(asked.isEmpty())
    }

    @Test fun in_a_real_chat_the_model_asks_in_the_dialog_and_a_no_keeps_the_run_going() {
        m.script += m.tool("github_write_files", """{"repo":"r","files":[{"path":"a.md","content":"x"}]}"""); m.script += m.text("Theek hai, files phone par hi hain.")
        var title: String? = null
        val r = Env.run(app, "app bana do", repo = "octo/r", during = { app.runner.approval.value?.let { title = it.title; app.runner.resolveApproval(it.id, false) } })
        assertEquals("Push to GitHub?", title); assertTrue(r.tools.single().content.startsWith("DENIED"))
        assertEquals("Theek hai, files phone par hi hain.", r.lastAssistant.content); assertEquals(0, m.count("POST /gh/repos/octo/r/git/blobs"))
    }

    @Test fun the_prompt_tells_the_model_to_ask_in_the_users_language_first() {
        val s = AgentRunner.SYSTEM
        assertTrue(s.contains("GitHub consent") && s.contains("never push or commit to GitHub unless the user asked") && s.contains("in the language they are writing in") && s.contains("keep the files here on your phone"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PushConsentUiTest : UiBase() {
    @Test fun the_settings_switch_is_on_by_default_and_turns_the_question_off_and_on() {
        if (rule.onAllNodesWithText("Skip for now").fetchSemanticsNodes().isNotEmpty()) { rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle() }
        vm().extras.setAskBeforePush(true)
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("ask-push")); rule.waitForIdle()
        assertTrue(text("Ask before pushing to GitHub"))
        rule.onNodeWithTag("ask-push").assertIsOn().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle()
        assertFalse(vm().extras.askBeforePush.value)
        rule.onNodeWithTag("ask-push").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle()
        assertTrue(vm().extras.askBeforePush.value)
    }
}
