package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AttachmentsAndReposTest {
    private fun setup(m: Mock, wire: String = "chat"): App {
        val app = ApplicationProvider.getApplicationContext<App>()
        app.store.setSupabase("", "", false)
        app.cloudApi = m.server.url("/gh").toString().trimEnd('/')
        app.cloudPortUrl = { _, _ -> m.server.url("/cs").toString().trimEnd('/') }
        app.cloudPollMs = 10
        app.store.setCodespace("ghp_secret_token_123", "octo")
        app.store.upsertProvider(Provider("p", "T", m.server.url("/v1").toString().trimEnd('/'), "k", wire = wire, models = listOf("m"), defaultModel = "m"))
        app.store.select("p", "m")
        app.store.setGithub("gho_x", "octo")
        app.store.setGuardrails(true); app.extras.setAskBeforePush(false)
        app.runner.alwaysApprove.clear()
        return app
    }

    private fun file(name: String, bytes: ByteArray): File = File(createTempDir(), name).apply { writeBytes(bytes) }

    private fun att(name: String, kind: String, bytes: ByteArray, mime: String = "text/plain") =
        file(name, bytes).let { Attachment(name, mime, it.length(), it.absolutePath, kind) }

    private data class Res(val chatId: String, val turn: String, val msgs: List<MessageEntity>)

    private fun run(app: App, text: String, atts: List<Attachment> = emptyList(), repo: String = "", seed: List<MessageEntity> = emptyList(), during: (() -> Unit)? = null): Res {
        val dao = app.db.dao()
        val chat = Ids.id(); val turn = Ids.id()
        runBlocking {
            dao.upsertChat(ChatEntity(chat, "New chat", Ids.now(), Ids.now(), repo = repo))
            seed.forEach { dao.upsertMessage(it.copy(chatId = chat)) }
            dao.upsertMessage(MessageEntity(turn, chat, "user", text, Ids.now(), attachments = Attachments.toJson(atts)))
        }
        app.runner.start(chat)
        val end = System.currentTimeMillis() + 40000
        while (System.currentTimeMillis() < end && app.runner.active.value.isNotEmpty()) { during?.invoke(); Thread.sleep(30) }
        assertTrue("run did not finish", app.runner.active.value.isEmpty())
        Thread.sleep(300)
        return Res(chat, turn, runBlocking { dao.messagesOnce(chat) })
    }

    private fun tools(r: Res) = r.msgs.filter { it.role == "tool" }

    // ------------------------------------------------------------ attachments

    @Test fun text_file_is_inlined_binary_is_noted_and_image_goes_as_vision_input() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.text("got them")
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
        run(app, "look at these", listOf(
            att("notes.md", "text", "# My notes\nbuy milk".toByteArray()),
            att("app.apk", "binary", byteArrayOf(0, 1, 2, 3), "application/vnd.android.package-archive"),
            att("shot.jpg", "image", jpg, "image/jpeg"),
        ))
        val body = m.mainRequests.last()
        assertTrue(body, body.contains("Attached file: notes.md") && body.contains("buy milk"))
        assertTrue(body.contains("app.apk") && body.contains("attachment_to_cloud"))
        assertTrue("image sent as image_url", body.contains("\"type\":\"image_url\"") && body.contains("data:image/jpeg;base64,"))
    }

    @Test fun responses_api_gets_input_image() {
        val m = Mock().start(); val app = setup(m, wire = "responses")
        val r = run(app, "what is this", listOf(att("a.png", "image", byteArrayOf(1, 2, 3), "image/png")))
        val body = m.mainRequests.first()
        assertTrue(body, body.contains("\"type\":\"input_image\"") && body.contains("data:image/png;base64,"))
        assertEquals("responses ok", r.msgs.last { it.role == "assistant" }.content)
    }

    @Test fun only_recent_images_are_sent_to_save_tokens() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.text("ok")
        val old = (1..4).map { i -> MessageEntity(Ids.id(), "", "user", "old $i", Ids.now(), attachments = Attachments.toJson(listOf(att("old$i.jpg", "image", byteArrayOf(9, 9, i.toByte()), "image/jpeg")))) }
        run(app, "newest", listOf(att("new.jpg", "image", byteArrayOf(7, 7, 7), "image/jpeg")), seed = old)
        val body = m.mainRequests.last()
        assertEquals("only the last 3 user messages carry images", 3, Regex("\"type\":\"image_url\"").findAll(body).count())
        assertTrue(body.contains("old1.jpg omitted"))
    }

    @Test fun agent_can_push_attachment_to_repo_and_to_codespace() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("attachment_to_repo", "{\"name\":\"data.bin\",\"path\":\"uploads/data.bin\"}")
        m.script += m.tool("attachment_to_cloud", "{\"name\":\"data.bin\",\"dest\":\"/workspaces/aura-workspace/uploads/data.bin\"}")
        m.script += m.text("sent")
        val payload = byteArrayOf(5, 6, 7, 8, 9)
        val r = run(app, "put this file in the repo", listOf(att("data.bin", "binary", payload, "application/octet-stream")), repo = "octo/r")
        val t = tools(r)
        assertTrue(t[0].content, t[0].content.startsWith("Committed data.bin to octo/r@main"))
        val put = m.bodies.first { it.first.startsWith("PUT /gh/repos/octo/r/contents/uploads/data.bin") }.second
        assertArrayEquals(payload, java.util.Base64.getDecoder().decode(Json.parseToJsonElement(put).jsonObject["content"]!!.jsonPrimitive.content))
        assertTrue(t[1].content, t[1].content.contains("Copied data.bin"))
        assertTrue(m.bodies.any { it.first.startsWith("PUT /cs/file") })
        assertTrue("codespace stopped", m.log.contains("POST /gh/user/codespaces/cs1/stop"))
    }

    // ------------------------------------------------------------ active repo

    @Test fun repo_chosen_at_chat_start_is_used_by_default_everywhere() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("edit_file_diff", "{\"path\":\"src/A.kt\",\"search\":\"    println(\\\"other\\\")\",\"replace\":\"    println(\\\"x\\\")\"}")   // no repo argument
        m.script += m.tool("cloud_exec", "{\"command\":\"ls\"}")
        m.script += m.text("done")
        val r = run(app, "change it", repo = "octo/r")
        assertFalse(tools(r)[0].content, tools(r)[0].isError)
        assertTrue(m.bodies.any { it.first.startsWith("PUT /gh/repos/octo/r/contents/src/A.kt") })
        assertTrue("system prompt names the repo", m.mainRequests.first().contains("Active repository for this chat: octo/r"))
        assertTrue("repo cloned into the codespace first", m.bodies.any { it.first == "POST /cs/exec" && it.second.contains("gh repo clone") && it.second.contains("octo/r") })
        val ls = m.bodies.first { it.first == "POST /cs/exec_stream" }.second
        assertTrue(ls, ls.contains("/workspaces/r"))
    }

    @Test fun chat_without_repo_tells_the_model() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.text("hi")
        run(app, "hello")
        assertTrue(m.mainRequests.last().contains("No repository is attached to this chat"))
    }

    @Test fun cloud_clone_tool_clones_any_repo() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("cloud_clone", "{\"repo\":\"someone/else\"}")
        m.script += m.text("cloned")
        val r = run(app, "clone it")
        assertTrue(tools(r)[0].content, tools(r)[0].content.contains("Cloned someone/else into /workspaces/else"))
        assertTrue(m.bodies.any { it.first == "POST /cs/exec_stream" && it.second.contains("gh repo clone") })
        assertTrue(m.bodies.first { it.first == "POST /cs/exec_stream" }.second.contains("\"GH_TOKEN\":\"ghp_secret_token_123\""))
    }

    // ------------------------------------------------------------ skills with @

    @Test fun at_mention_injects_skill_instructions_even_if_switched_off() {
        val m = Mock().start(); val app = setup(m)
        app.store.setSkillEnabled("skill-creator", false)
        m.script += m.text("ok")
        run(app, "@skill-creator make me a release notes skill")
        val body = m.mainRequests.last()
        assertTrue(body, body.contains("Skill @skill-creator was requested") && body.contains("create_skill tool"))
        app.store.setSkillEnabled("skill-creator", true)
    }

    @Test fun skill_creator_is_a_builtin_skill() {
        val sk = Skills.builtin.first { it.name == "skill-creator" }
        assertTrue(sk.instructions.contains("create_skill") && sk.instructions.contains("@name"))
        assertEquals(listOf("skill-creator"), Skills.mentioned("use @skill-creator now, not email@skill-creator.com", Skills.builtin).map { it.name }.take(1))
    }

    // ------------------------------------------------------------ always approve, loops, retries, breaker

    @Test fun always_approve_this_session_stops_further_prompts() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("cloud_exec", "{\"command\":\"rm -rf build\"}")
        m.script += m.tool("cloud_exec", "{\"command\":\"rm -rf dist\"}")
        m.script += m.tool("cloud_exec", "{\"command\":\"git push --force origin x\"}")
        m.script += m.text("all done")
        var prompts = 0
        val r = run(app, "clean up") {
            app.runner.approval.value?.let { prompts++; app.runner.resolveApproval(it.id, true, always = true) }
        }
        assertEquals("asked only once", 1, prompts)
        assertEquals(3, m.bodies.count { it.first == "POST /cs/exec_stream" })
        assertTrue(tools(r).none { it.isError })
    }

    @Test fun repeating_the_same_step_is_cut_off() {
        val m = Mock().start(); val app = setup(m)
        repeat(8) { m.script += m.tool("read_lines", "{\"repo\":\"r\",\"path\":\"src/A.kt\",\"start_line\":1,\"end_line\":2}") }
        val r = run(app, "loop")
        val t = tools(r)
        assertEquals(6, t.size)
        assertTrue(t.last().content, t.last().content.startsWith("ERROR: LOOP_DETECTED"))
        assertTrue(r.msgs.last().content, r.msgs.last().isError && r.msgs.last().content.contains("kept repeating"))
    }

    @Test fun provider_that_blocks_chat_apps_gives_a_clear_message() {
        val m = Mock().start(); val app = setup(m)
        m.script += MockResponse().setResponseCode(403).setBody("{\"error\":{\"code\":\"client_not_allowed\",\"message\":\"only coding clients\",\"type\":\"permission_error\"}}")
        val r = run(app, "hi")
        val err = r.msgs.last { it.role == "assistant" }
        assertTrue(err.content, err.isError && err.content.contains("blocks chat apps like Aura"))
        assertEquals("one request, no retry storm", 1, m.mainRequests.size)
    }

    @Test fun transient_503_is_retried_then_succeeds() {
        val m = Mock().start(); val app = setup(m)
        m.script += MockResponse().setResponseCode(503).setHeader("Retry-After", "1").setBody("busy")
        m.script += m.text("recovered after retry")
        val r = run(app, "hi")
        assertEquals("recovered after retry", r.msgs.last { it.role == "assistant" }.content)
        assertEquals(2, m.mainRequests.size)
    }

    @Test fun crash_loop_breaker_stops_resuming() {
        val m = Mock().start(); val app = setup(m)
        app.store.prefsReset()
        assertTrue(app.store.allowResume()); assertTrue(app.store.allowResume()); assertTrue(app.store.allowResume())
        assertFalse("4th resume within 5 minutes is refused", app.store.allowResume())
    }

    @Test fun resume_is_refused_and_chat_is_unstuck_after_repeated_restarts() {
        val m = Mock().start(); val app = setup(m)
        app.store.prefsReset()
        repeat(3) { app.store.allowResume() }
        val dao = app.db.dao()
        runBlocking {
            dao.upsertChat(ChatEntity("stuck", "t", 1, 1, running = true))
            dao.upsertMessage(MessageEntity("su", "stuck", "user", "hi", 1))
        }
        val resumed = runBlocking { app.runner.resumeAll() }
        assertFalse(resumed)
        assertEquals("no request reached the provider", 0, m.mainRequests.size)
        assertTrue(runBlocking { dao.runningChats() }.none { it.id == "stuck" })
    }

    @Test fun nothing_secret_is_ever_sent_to_supabase() {
        val m = Mock().start(); val app = setup(m)
        app.store.setSupabase(m.server.url("/supa").toString().trimEnd('/'), "anon-key", true)   // even if somebody enables sync
        m.script += m.text("hello")
        run(app, "hi")
        runBlocking { app.supabase.loadConfig() }
        Thread.sleep(200)
        val supa = m.bodies.filter { it.first.contains("/supa/") }
        assertTrue("no secrets in any Supabase request", supa.none { it.second.contains("ghp_secret_token_123") || it.second.contains("gho_x") || it.first.contains("user_secrets") })
        assertFalse("sync is off by default", Store(ApplicationProvider.getApplicationContext()).supaSync.value)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ComposerUiTest : UiBase() {
    @Test fun at_popup_attachment_chip_and_placeholder() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        assertTrue("placeholder", text("type @ for skills"))
        rule.onNodeWithTag("input").performTextInput("hello @skill")
        assertTrue("skill popup", text("@skill-creator"))
        rule.onNodeWithText("@skill-creator", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("input").assertTextContains("hello @skill-creator ")
        // attachment chip
        val f = File.createTempFile("report", ".txt").apply { writeText("hi") }
        rule.runOnUiThread { vm().pending.value = listOf(Attachment("report.txt", "text/plain", 2, f.absolutePath, "text")) }
        assertTrue("chip", text("report.txt"))
        rule.onNodeWithTag("send").performClick()
        rule.waitForIdle()
        assertTrue("sent message shows the file", text("report.txt"))
        val end = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < end && vm().pending.value.isNotEmpty()) Thread.sleep(30)
        assertTrue("attachments are cleared after sending", vm().pending.value.isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepoPickerUiTest : UiBase() {
    @Test fun choose_existing_and_create_new_repository() {
        val s = MockWebServer()
        s.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path!!.startsWith("/user/repos") ->
                    MockResponse().setBody("[{\"full_name\":\"octo/alpha\",\"private\":true,\"description\":\"first\"},{\"full_name\":\"octo/beta\",\"private\":false,\"description\":null}]")
                request.method == "POST" && request.path == "/user/repos" -> MockResponse().setResponseCode(201).setBody("{\"full_name\":\"octo/fresh\",\"default_branch\":\"main\"}")
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        s.start()
        val app = rule.activity.application as App
        app.cloudApi = s.url("/").toString().trimEnd('/')
        app.store.setGithub("tok_value_123", "octo")
        rule.waitForIdle()
        assertTrue("chip", text("Choose repository"))
        rule.onNodeWithText("Choose repository").performClick()
        val until = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < until && !text("octo/alpha")) Thread.sleep(100)
        assertTrue("repo list", text("octo/alpha") && text("first"))
        rule.onNodeWithText("octo/alpha").performClick()
        assertEquals("octo/alpha", vm().pendingRepo.value)
        assertTrue("chip shows pick", text("octo/alpha"))
        // create a new one
        rule.onNode(hasText("octo/alpha") and hasClickAction()).performClick()
        assertTrue(text("Create a new repository"))
        rule.onNodeWithText("Create a new repository").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("repo-name").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("fresh")) }
        rule.waitForIdle()
        rule.onNodeWithText("Create and use").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end && vm().pendingRepo.value != "octo/fresh") { Thread.sleep(50); rule.waitForIdle() }
        assertEquals("octo/fresh", vm().pendingRepo.value)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GithubExpiredUiTest : UiBase() {
    @Test fun a_rejected_login_is_forgotten_and_the_picker_offers_to_sign_in_again() {
        val s = MockWebServer()
        s.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = MockResponse().setResponseCode(401).setBody("{\"message\":\"Bad credentials\"}")
        }
        s.start()
        val app = rule.activity.application as App
        app.cloudApi = s.url("/").toString().trimEnd('/')
        app.store.setGithub("tok_value_old", "octo")
        rule.waitForIdle()
        assertTrue("chip", text("Choose repository"))
        rule.onNodeWithText("Choose repository").performClick()
        val until = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < until && rule.onAllNodesWithTag("github-expired", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(100) }
        assertTrue("sign-in-again box", text("no longer accepts your login") && text("Sign in with GitHub again"))
        assertEquals("the dead token is forgotten, the name is kept", "", app.store.githubToken.value)
        assertEquals("octo", app.store.githubUser.value)
        s.shutdown()
    }
}
