package com.aura.agent

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhoneWorkspaceTest {
    private val m = Mock().start()
    private val app = Env.setup(m).also { a -> a.extras.folders.value.forEach { a.extras.removeFolder(it.id) }; Workspace.dir(a).deleteRecursively() }
    private fun ctx(approve: suspend (String, String) -> Boolean = { _, _ -> true }) = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), approve = approve, activeRepo = "octo/r")
    private fun run(name: String, args: String, c: ToolCtx = ctx()) = runBlocking { Tools.execute(c, name, args) }
    private fun esc(s: String) = org.json.JSONObject.quote(s)

    @Test fun the_workspace_is_always_there_and_is_the_default_when_no_folder_is_connected() {
        assertTrue(run("folder_list", "{}").let { it.contains("workspace") && it.contains("always available") })
        assertTrue(run("folder_write", """{"path":"my-app/index.html","content":${esc("<h1>hi</h1>")}}""").startsWith("Created my-app/index.html in workspace"))
        assertEquals("<h1>hi</h1>", File(Workspace.dir(app), "my-app/index.html").readText())
        assertEquals("1: <h1>hi</h1>", run("folder_read", """{"path":"my-app/index.html"}"""))
        assertTrue(run("folder_list", """{"folder":"workspace"}""").contains("my-app/"))
        assertTrue(run("folder_search", """{"query":"HI"}""").contains("my-app/index.html:1"))
        assertEquals("Deleted my-app/index.html.", run("folder_delete", """{"folder":"Workspace","path":"my-app/index.html"}"""))
    }

    @Test fun with_one_connected_folder_it_is_the_default_and_with_two_the_model_must_name_one() {
        val dir = File(app.cacheDir, "wd1").also { it.mkdirs() }; app.extras.addFolder("Docs", Uri.fromFile(dir).toString())
        run("folder_write", """{"path":"a.txt","content":"to docs"}"""); assertEquals("to docs", File(dir, "a.txt").readText())
        run("folder_write", """{"folder":"workspace","path":"b.txt","content":"to workspace"}"""); assertEquals("to workspace", File(Workspace.dir(app), "b.txt").readText())
        val dir2 = File(app.cacheDir, "wd2").also { it.mkdirs() }; app.extras.addFolder("More", Uri.fromFile(dir2).toString())
        val out = run("folder_write", """{"path":"c.txt","content":"x"}""")
        assertTrue(out, out.startsWith("ERROR: several folders are connected") && out.contains("workspace") && out.contains("Docs") && out.contains("More"))
    }

    @Test fun files_can_be_copied_between_the_workspace_and_a_connected_folder() {
        val dir = File(app.cacheDir, "wd3").also { it.mkdirs() }; app.extras.addFolder("Docs", Uri.fromFile(dir).toString())
        run("folder_write", """{"folder":"workspace","path":"site/index.html","content":"<h1>1</h1>"}"""); run("folder_write", """{"folder":"workspace","path":"site/css/a.css","content":"h1{}"}""")
        val out = run("folder_copy", """{"from_folder":"workspace","from_path":"site","to_folder":"Docs","to_path":"backup/site"}""")
        assertTrue(out, out.startsWith("Copied 2 file(s)"))
        assertEquals("<h1>1</h1>", File(dir, "backup/site/index.html").readText()); assertEquals("h1{}", File(dir, "backup/site/css/a.css").readText())
        assertTrue(run("folder_copy", """{"from_folder":"Docs","from_path":"backup/site/index.html","to_folder":"workspace","to_path":"copy.html"}""").contains("Copied 1 file(s)"))
        assertEquals("<h1>1</h1>", File(Workspace.dir(app), "copy.html").readText())
        assertTrue(run("folder_copy", """{"from_folder":"workspace","from_path":"nope","to_folder":"Docs"}""").contains("does not exist"))
    }

    @Test fun copying_over_existing_files_asks_and_a_no_keeps_them() {
        val dir = File(app.cacheDir, "wd4").also { it.mkdirs() }; app.extras.addFolder("Docs", Uri.fromFile(dir).toString()); File(dir, "x.txt").writeText("old")
        run("folder_write", """{"folder":"workspace","path":"x.txt","content":"new"}""")
        val asked = ArrayList<String>()
        assertTrue(run("folder_copy", """{"from_folder":"workspace","from_path":"x.txt","to_folder":"Docs"}""", ctx(approve = { t, _ -> asked += t; false })).startsWith("DENIED"))
        assertEquals("old", File(dir, "x.txt").readText()); assertEquals(1, asked.size)
        assertTrue(run("folder_copy", """{"from_folder":"workspace","from_path":"x.txt","to_folder":"Docs"}""").contains("Copied 1"))
        assertEquals("new", File(dir, "x.txt").readText())
    }

    @Test fun a_project_is_packed_into_a_zip_the_user_can_download() {
        run("folder_write", """{"path":"proj/a.txt","content":"alpha"}"""); run("folder_write", """{"path":"proj/src/b.kt","content":"fun b() {}"}""")
        val c = ctx()
        val out = run("folder_zip", """{"folder":"workspace","path":"proj","name":"my-project"}""", c)
        assertTrue(out, out.contains("my-project.zip") && out.contains("2 file(s)"))
        val a = c.produced.single(); assertEquals("application/zip", a.mime); assertEquals("my-project.zip", a.name)
        val names = ArrayList<String>(); ZipInputStream(File(a.path).inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { names += it.name } }
        assertEquals(listOf("a.txt", "src/b.kt"), names.sorted())
        assertTrue(run("folder_zip", """{"folder":"workspace","path":"empty"}""").contains("no files"))
    }

    @Test fun a_workspace_file_can_be_given_with_send_file_and_the_tools_are_labelled() {
        run("folder_write", """{"path":"notes/todo.md","content":"# todo"}""")
        val c = ctx(); assertTrue(runBlocking { Tools.execute(c, "send_file", """{"name":"todo.md","folder":"workspace","folder_path":"notes/todo.md"}""") }.contains("todo.md"))
        assertEquals("# todo", File(c.produced.single().path).readText())
        for (n in listOf("folder_copy", "folder_zip")) { assertEquals(n, 1, Tools.defs.count { it.name == n }); assertNotEquals(n, Tools.label(n)) }
    }

    @Test fun in_a_chat_the_model_builds_a_project_on_the_phone_without_touching_github() {
        m.script += m.tool("folder_write", """{"path":"portfolio/index.html","content":"<h1>Me</h1>"}"""); m.script += m.tool("folder_zip", """{"folder":"workspace","path":"portfolio"}"""); m.script += m.text("Your site is ready on the phone.")
        val r = Env.run(app, "mera portfolio site banao, phone me hi rakhna")
        assertEquals("<h1>Me</h1>", File(Workspace.dir(app), "portfolio/index.html").readText())
        assertTrue(Attachments.parse(r.tools.last().attachments).single().name.endsWith(".zip"))
        assertTrue("no GitHub write happened", m.log.none { it.startsWith("POST /gh/") || it.startsWith("PUT /gh/") || it.startsWith("PATCH /gh/") })
        assertTrue(m.mainRequests.first().contains("workspace") && m.mainRequests.first().contains("never push to GitHub"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ChatReposAgentTest {
    @Test fun the_model_is_told_about_every_repository_attached_to_the_chat() {
        val m = Mock().start(); val app = Env.setup(m)
        Env.run(app, "hello", repo = "octo/r", extraRepos = "octo/api,octo/web")
        val p = m.mainRequests.last()
        assertTrue(p.contains("Active repository for this chat: octo/r") && p.contains("Also attached to this chat: octo/api, octo/web") && p.contains("cloud_clone"))
        Env.run(app, "hello", repo = "octo/r")
        assertFalse(m.mainRequests.last().contains("Also attached"))
        Env.run(app, "hello")
        assertTrue(m.mainRequests.last().contains("No repository is attached") && m.mainRequests.last().contains("phone"))
    }

    @Test fun chats_keep_the_main_repo_first_and_the_list_is_clean() {
        assertEquals(listOf("a/x", "b/y", "c/z"), ChatEntity("1", "t", 1, 1, repo = "a/x", extraRepos = "b/y, c/z ,a/x,,").allRepos())
        assertEquals(listOf("b/y"), ChatEntity("1", "t", 1, 1, extraRepos = "b/y").allRepos()); assertTrue(ChatEntity("1", "t", 1, 1).allRepos().isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ChatRepoMenuUiTest : UiBase() {
    @Test fun repositories_are_added_from_the_three_dots_menu_before_and_after_the_first_message_and_removed_again() {
        val s = MockWebServer()
        s.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path!!.startsWith("/user/repos") -> MockResponse().setBody("[{\"full_name\":\"octo/alpha\",\"private\":true,\"description\":\"first\"},{\"full_name\":\"octo/beta\",\"private\":false,\"description\":\"second\"}]")
                request.method == "POST" && request.path == "/user/repos" -> MockResponse().setResponseCode(201).setBody("{\"full_name\":\"octo/fresh\",\"default_branch\":\"main\"}")
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        s.start()
        val app = rule.activity.application as App
        app.cloudApi = s.url("/").toString().trimEnd('/'); vm().store.setGithub("tok_value_123", "octo"); rule.waitForIdle()
        fun tap(n: SemanticsNodeInteraction) { n.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle() }
        fun menu(tag: String) { tap(rule.onNodeWithTag("chat-menu")); tap(rule.onNodeWithTag(tag)) }
        fun waitText(t: String): Boolean { val end = System.currentTimeMillis() + 6000; while (System.currentTimeMillis() < end) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); rule.waitForIdle(); if (text(t)) return true; Thread.sleep(80) }; return false }
        fun waitGone(t: String) { val end = System.currentTimeMillis() + 6000; while (System.currentTimeMillis() < end) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); rule.waitForIdle(); if (rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isEmpty()) return; Thread.sleep(80) } }
        // before the first message: add two repositories
        menu("menu-add-repo"); assertTrue(waitText("octo/alpha"))
        rule.onNodeWithText("octo/alpha").performClick(); rule.waitForIdle()
        assertEquals("octo/alpha", vm().pendingRepo.value); waitGone("Choose a repository")
        menu("menu-add-repo"); assertTrue(waitText("octo/alpha")); rule.onNodeWithTag("repo-list").performScrollToNode(hasText("octo/beta")); rule.waitForIdle(); rule.onNodeWithText("octo/beta").performClick(); rule.waitForIdle(); waitGone("Choose a repository")
        assertEquals(listOf("octo/beta"), vm().pendingExtra.value); assertTrue("label shows the count", waitText("+1"))
        // the first message keeps both on the chat
        vm().send("hello there"); val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end && vm().currentId.value == null) Thread.sleep(50)
        val chatId = vm().currentId.value!!
        val chat = runBlocking { app.db.dao().chat(chatId)!! }; assertEquals(listOf("octo/alpha", "octo/beta"), chat.allRepos())
        // later: create a new repository from the menu and it joins the chat
        menu("menu-new-repo"); assertTrue(waitText("Create and use"))
        rule.onNodeWithTag("repo-name").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("fresh")) }; rule.waitForIdle()
        tap(rule.onNodeWithText("Create and use"))
        waitGone("Choose a repository")
        val e2 = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < e2 && runBlocking { app.db.dao().chat(chatId)!!.allRepos() }.size < 3) Thread.sleep(80)
        assertEquals(listOf("octo/alpha", "octo/beta", "octo/fresh"), runBlocking { app.db.dao().chat(chatId)!!.allRepos() })
        // and a repository can be removed from the menu
        tap(rule.onNodeWithTag("chat-menu"))
        tap(rule.onAllNodesWithTag("menu-remove-repo")[1])
        val e3 = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < e3 && runBlocking { app.db.dao().chat(chatId)!!.allRepos() }.size > 2) Thread.sleep(80)
        assertEquals(listOf("octo/alpha", "octo/fresh"), runBlocking { app.db.dao().chat(chatId)!!.allRepos() })
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WorkspaceSettingsUiTest : UiBase() {
    @Test fun settings_show_the_built_in_workspace_with_a_zip_button_and_the_folders_sheet_opens_from_the_chat_menu() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("chat-menu").performClick(); rule.waitForIdle(); rule.onNodeWithTag("menu-folders").performClick(); rule.waitForIdle()
        assertTrue(text("Aura workspace")); rule.onNodeWithTag("workspace-zip").assertExists(); rule.onNodeWithTag("folder-add").assertExists()
    }
}
