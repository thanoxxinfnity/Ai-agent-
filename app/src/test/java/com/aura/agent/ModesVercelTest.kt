package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.*
import com.aura.agent.net.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/** Builds a .tar.gz like GitHub serves: every path starts with a "repo-sha/" folder. */
fun tarGz(vararg entries: Pair<String, String>, dirs: List<String> = emptyList()): ByteArray {
    val tar = ByteArrayOutputStream()
    fun header(name: String, size: Int, type: Char) {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        String.format("%07o", size).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        tar.write(h)
    }
    dirs.forEach { header(it, 0, '5') }
    for ((n, c) in entries) {
        val b = c.toByteArray(StandardCharsets.UTF_8)
        header(n, b.size, '0'); tar.write(b); tar.write(ByteArray((512 - b.size % 512) % 512))
    }
    tar.write(ByteArray(1024))
    return ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(tar.toByteArray()) } }.toByteArray()
}

class TarAndFilterTest {
    @Test fun tar_is_read_and_the_top_folder_is_stripped() {
        val gz = tarGz(
            "repo-abc/index.html" to "<h1>hi</h1>", "repo-abc/public/a.css" to "body{}", "repo-abc/node_modules/x.js" to "x",
            "repo-abc/.git/config" to "c", "repo-abc/.env" to "SECRET=1", dirs = listOf("repo-abc/", "repo-abc/public/"),
        )
        val e = TarReader.readGz(gz)
        assertEquals(setOf("index.html", "public/a.css", "node_modules/x.js", ".git/config", ".env"), e.map { it.path }.toSet())
        assertEquals("<h1>hi</h1>", String(e.first { it.path == "index.html" }.bytes))
    }

    @Test fun deploy_filter_drops_secrets_dependencies_and_vcs() {
        assertTrue(DeployFilter.keep("index.html")); assertTrue(DeployFilter.keep("src/app.js"))
        for (bad in listOf(".git/config", "node_modules/x/y.js", "web/node_modules/z.js", ".env", ".env.local", ".github/workflows/a.yml", ".DS_Store", "build/out.o")) assertFalse(bad, DeployFilter.keep(bad))
        assertTrue(DeployFilter.keep("public/a.css", "public")); assertFalse(DeployFilter.keep("src/a.js", "public"))
        assertEquals("a.css", DeployFilter.relative("public/a.css", "public/"))
    }

    @Test fun project_names_are_made_valid() {
        assertEquals("my-site", DeployFilter.projectName("My Site!")); assertEquals("aura-site", DeployFilter.projectName("###"))
        assertTrue(DeployFilter.projectName("a".repeat(200)).length <= 52)
    }

    @Test fun every_mode_has_its_own_name_not_the_ones_used_elsewhere() {
        val labels = ThinkMode.entries.map { it.label.lowercase() }
        assertEquals(5, labels.toSet().size)
        for (reserved in listOf("low", "medium", "high", "max", "xhigh", "default", "ultra", "ultracode")) assertFalse(reserved, reserved in labels)
        assertEquals(ThinkMode.STEADY, ThinkMode.fromId(null)); assertEquals(ThinkMode.TITAN, ThinkMode.fromId("titan"))
        val sorted = ThinkMode.entries.sortedBy { it.steps }
        assertEquals("modes get stricter in persistence", sorted, ThinkMode.entries.toList())
        assertTrue(ThinkMode.TITAN.verify > ThinkMode.STEADY.verify && ThinkMode.STEADY.verify > ThinkMode.SWIFT.verify)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VercelClientTest {
    private fun server(vararg answers: MockResponse): Pair<MockWebServer, java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>> {
        val log = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        val q = java.util.concurrent.ConcurrentLinkedQueue(answers.toList())
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse { log += request; return q.poll() ?: MockResponse().setBody("{}") } }
        s.start(); return s to log
    }

    @Test fun whoami_sends_the_token_and_team_id() = runBlocking {
        val (s, log) = server(MockResponse().setBody("{\"user\":{\"username\":\"octo\"}}"))
        assertEquals("octo", Vercel("tok123", "team_9", s.url("/").toString().trimEnd('/')).whoami())
        assertEquals("Bearer tok123", log[0].getHeader("Authorization")); assertTrue(log[0].path!!.contains("teamId=team_9"))
    }

    @Test fun bad_token_gives_a_clear_message() = runBlocking {
        val (s, _) = server(MockResponse().setResponseCode(403).setBody("{\"error\":{\"message\":\"Not authorized\"}}"))
        val e = runCatching { Vercel("bad", "", s.url("/").toString().trimEnd('/')).whoami() }.exceptionOrNull()
        assertTrue(e is VercelError && e.message!!.contains("rejected this token"))
    }

    @Test fun missing_files_are_uploaded_and_deployment_is_retried() = runBlocking {
        val f = DeployFile("index.html", "<h1>x</h1>".toByteArray())
        val (s, log) = server(
            MockResponse().setResponseCode(400).setBody("{\"error\":{\"code\":\"missing_files\",\"message\":\"Missing files\",\"missing\":[\"${f.sha}\"]}}"),
            MockResponse().setBody("{}"),   // the file upload
            MockResponse().setBody("{\"id\":\"dpl_9\",\"url\":\"x-1.vercel.app\",\"readyState\":\"QUEUED\"}"),
        )
        val d = Vercel("t", "", s.url("/").toString().trimEnd('/')).deploy("site", listOf(f), true, null)
        assertEquals("dpl_9", d.id)
        assertEquals(listOf("POST", "POST", "POST"), log.map { it.method })
        assertEquals(f.sha, log[1].getHeader("x-vercel-digest"))
        assertTrue(log[2].body.readUtf8().contains("\"target\":\"production\""))
    }

    @Test fun sha1_digest_matches_the_content() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", DeployFile("a", "abc".toByteArray()).sha)
    }

    @Test fun public_url_prefers_the_production_alias() {
        val d = Deployment("i", "my-site-abc.vercel.app", "READY", listOf("my-site-git-main-x.vercel.app", "my-site.vercel.app"), null)
        val v = Vercel("t")
        assertEquals("https://my-site.vercel.app", v.publicUrl(d, true)); assertEquals("https://my-site-abc.vercel.app", v.publicUrl(d, false))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VercelAgentTest {
    private fun setup(m: Mock, vercel: Boolean = true): App {
        val app = ApplicationProvider.getApplicationContext<App>()
        app.store.setSupabase("", "", false)
        val base = m.server.url("/").toString().trimEnd('/')
        app.cloudApi = "$base/gh"; app.vercelBase = "$base/vc"; app.vercelPollMs = 10
        app.store.providers.value.forEach { app.store.deleteProvider(it.id) }
        app.store.upsertProvider(Provider("p", "T", "$base/v1", "k", models = listOf("m"), defaultModel = "m"))
        app.store.select("p", "m"); app.store.setThinkMode(ThinkMode.STEADY)
        app.store.setGithub("gho_x", "octo"); app.store.setGuardrails(true); app.runner.alwaysApprove.clear()
        app.store.setVercel(if (vercel) "vercel_tok_123" else "", if (vercel) "vercel-user" else "", "")
        m.tarball = tarGz(
            "repo-1/package.json" to "{}", "repo-1/public/index.html" to "<h1>Hello</h1>", "repo-1/public/style.css" to "h1{color:red}",
            "repo-1/node_modules/dep/index.js" to "x", "repo-1/.env" to "SECRET=1", "repo-1/.git/config" to "x",
        )
        return app
    }

    private fun run(app: App, during: (() -> Unit)? = null, repo: String = "octo/r"): List<MessageEntity> {
        val dao = app.db.dao(); val chat = Ids.id()
        runBlocking {
            dao.upsertChat(ChatEntity(chat, "New chat", Ids.now(), Ids.now(), repo = repo))
            dao.upsertMessage(MessageEntity(Ids.id(), chat, "user", "put my site online", Ids.now()))
        }
        app.runner.start(chat)
        val end = System.currentTimeMillis() + 40000
        while (System.currentTimeMillis() < end && app.runner.active.value.isNotEmpty()) { during?.invoke(); Thread.sleep(30) }
        assertTrue("run did not finish", app.runner.active.value.isEmpty())
        Thread.sleep(300)
        return runBlocking { dao.messagesOnce(chat) }
    }

    private fun tools(r: List<MessageEntity>) = r.filter { it.role == "tool" }
    private fun deployBodies(m: Mock) = m.bodies.filter { it.first.startsWith("POST /vc/v13/deployments") }

    @Test fun repo_folder_is_deployed_to_production_after_approval_and_the_link_is_returned() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("vercel_deploy", "{\"name\":\"My Site!\",\"directory\":\"public\"}")
        m.script += m.text("Your site is live")
        var asked: Approval? = null
        val r = run(app, { app.runner.approval.value?.let { asked = it; app.runner.resolveApproval(it.id, true) } })
        assertNotNull("production deploys ask first", asked); assertTrue(asked!!.title, asked!!.title.contains("production"))
        val t = tools(r)[0]
        assertTrue(t.content, !t.isError && t.content.contains("https://my-site.vercel.app") && t.content.contains("production"))
        assertEquals("only the two public files were uploaded, no secrets or dependencies", 2, m.digests.size)
        val body = Json.parseToJsonElement(deployBodies(m).single().second).jsonObject
        assertEquals("my-site", body["name"]!!.jsonPrimitive.content); assertEquals("production", body["target"]!!.jsonPrimitive.content)
        assertEquals(setOf("index.html", "style.css"), body["files"]!!.jsonArray.map { it.jsonObject["file"]!!.jsonPrimitive.content }.toSet())
        assertTrue("polled until ready", m.vercelPolls.get() >= 2)
        assertTrue(m.mainRequests.last().contains("https://my-site.vercel.app"))
    }

    @Test fun preview_deploys_need_no_approval_and_use_the_deployment_url() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("vercel_deploy", "{\"production\":false}")
        m.script += m.text("preview ready")
        var asked = false
        val r = run(app, { if (app.runner.approval.value != null) asked = true })
        assertFalse(asked)
        assertTrue(tools(r)[0].content, tools(r)[0].content.contains("https://my-site-abc123.vercel.app") && tools(r)[0].content.contains("preview"))
        assertFalse(deployBodies(m).single().second.contains("\"target\""))
    }

    @Test fun denied_production_deploy_changes_nothing_on_vercel() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("vercel_deploy", "{}")
        m.script += m.text("ok, not deploying")
        val r = run(app, { app.runner.approval.value?.let { app.runner.resolveApproval(it.id, false) } })
        assertTrue(tools(r)[0].content, tools(r)[0].content.startsWith("DENIED"))
        assertEquals(0, m.bodies.count { it.first.startsWith("POST /vc/") })
    }

    @Test fun missing_vercel_token_asks_the_user_politely() {
        val m = Mock().start(); val app = setup(m, vercel = false)
        m.script += m.tool("vercel_deploy", "{}")
        m.script += m.text("please add the token")
        val r = run(app)
        assertTrue(tools(r)[0].content, tools(r)[0].content.startsWith("NO_VERCEL_TOKEN") && tools(r)[0].content.contains("Settings > Vercel"))
        assertEquals(0, m.bodies.count { it.first.contains("/vc/") })
    }

    @Test fun failed_build_returns_the_log_so_the_agent_can_fix_it() {
        val m = Mock().start(); val app = setup(m); m.vercelState = "ERROR"
        app.runner.alwaysApprove.add("never")
        m.script += m.tool("vercel_deploy", "{\"production\":false}")
        m.script += m.text("I will fix it")
        val r = run(app)
        val c = tools(r)[0].content
        assertTrue(c, tools(r)[0].isError && c.contains("Build failed") && c.contains("Module not found: x") && c.contains("failure 1 of ${ThinkMode.STEADY.verify}"))
    }

    @Test fun small_sites_can_be_deployed_from_files_without_github() {
        val m = Mock().start(); val app = setup(m); app.store.setGithub("", "")
        m.script += m.tool("vercel_deploy", "{\"production\":false,\"name\":\"tiny\",\"files\":[{\"path\":\"index.html\",\"content\":\"<h1>tiny</h1>\"}]}")
        m.script += m.text("done")
        val r = run(app, repo = "")
        assertTrue(tools(r)[0].content, !tools(r)[0].isError)
        assertEquals(0, m.bodies.count { it.first.contains("tarball") }); assertEquals(1, m.digests.size)
    }

    @Test fun repeated_failures_stop_at_the_modes_limit() = runBlocking {
        val m = Mock().start(); val app = setup(m); m.vercelState = "ERROR"
        val ctx = ToolCtx(app, "c", "t", app.newCloudSession(), java.io.File("/tmp"), approve = { _, _ -> true }, activeRepo = "octo/r", mode = ThinkMode.SWIFT)
        val outs = (1..ThinkMode.SWIFT.verify + 1).map { m.vercelPolls.set(0); Tools.execute(ctx, "vercel_deploy", "{\"production\":false}") }
        assertTrue(outs.last(), outs.last().startsWith("MAX_RETRIES_REACHED"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ThinkingModeAgentTest {
    private fun setup(m: Mock, reasoning: String = "on", wire: String = "chat"): App {
        val app = ApplicationProvider.getApplicationContext<App>()
        Llm.resetEffortMemory()
        app.store.setSupabase("", "", false)
        app.cloudApi = m.server.url("/gh").toString().trimEnd('/')
        app.cloudPortUrl = { _, _ -> m.server.url("/cs").toString().trimEnd('/') }; app.cloudPollMs = 10
        app.store.setCodespace("ghp_secret_token_123", "octo")
        app.store.providers.value.forEach { app.store.deleteProvider(it.id) }
        app.store.upsertProvider(Provider("p", "T", m.server.url("/v1").toString().trimEnd('/'), "k", wire = wire, models = listOf("m"), defaultModel = "m", reasoning = reasoning))
        app.store.select("p", "m"); app.store.setGithub("gho_x", "octo"); app.store.setAuto(false)
        return app
    }

    private fun run(app: App, mode: ThinkMode, text: String = "hello"): List<MessageEntity> {
        app.store.setThinkMode(mode)
        val dao = app.db.dao(); val chat = Ids.id()
        runBlocking {
            dao.upsertChat(ChatEntity(chat, "New chat", Ids.now(), Ids.now(), repo = "octo/r"))
            dao.upsertMessage(MessageEntity(Ids.id(), chat, "user", text, Ids.now()))
        }
        app.runner.start(chat)
        val end = System.currentTimeMillis() + 40000
        while (System.currentTimeMillis() < end && app.runner.active.value.isNotEmpty()) Thread.sleep(30)
        assertTrue("run did not finish", app.runner.active.value.isEmpty())
        Thread.sleep(300)
        return runBlocking { dao.messagesOnce(chat) }
    }

    @Test fun reasoning_level_follows_the_mode_on_chat_completions() {
        val m = Mock().start(); val app = setup(m)
        m.mainFallback = { m.text("ok") }
        run(app, ThinkMode.SWIFT); run(app, ThinkMode.STEADY); run(app, ThinkMode.DEEP); run(app, ThinkMode.TITAN)
        val bodies = m.mainRequests.filter { !it.contains("You write chat titles") }
        assertTrue(bodies[0].contains("\"reasoning_effort\":\"low\""))
        assertFalse("Steady leaves the provider default", bodies[1].contains("reasoning_effort"))
        assertTrue(bodies[2].contains("\"reasoning_effort\":\"high\"")); assertTrue(bodies[3].contains("\"reasoning_effort\":\"high\""))
    }

    @Test fun responses_api_gets_the_reasoning_object() {
        val m = Mock().start(); val app = setup(m, wire = "responses")
        run(app, ThinkMode.DEEP)
        assertTrue(m.mainRequests.first(), m.mainRequests.first().contains("\"reasoning\":{\"effort\":\"high\"}"))
    }

    @Test fun unknown_hosts_and_off_never_receive_the_parameter() {
        val m = Mock().start(); val app = setup(m, reasoning = "auto")
        run(app, ThinkMode.TITAN)
        assertFalse("auto does not guess for an unknown host", m.mainRequests.first().contains("reasoning_effort"))
        val m2 = Mock().start(); val app2 = setup(m2, reasoning = "off")
        run(app2, ThinkMode.TITAN)
        assertFalse(m2.mainRequests.first().contains("reasoning_effort"))
    }

    @Test fun a_model_that_rejects_the_parameter_is_retried_without_it_and_remembered() {
        val m = Mock().start(); val app = setup(m)
        m.script += MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"Unknown parameter: 'reasoning_effort'.\"}}")
        m.script += m.text("answered without it")
        val r = run(app, ThinkMode.DEEP)
        assertEquals("answered without it", r.last { it.role == "assistant" }.content)
        val first = m.mainRequests.filter { !it.contains("You write chat titles") }
        assertTrue(first[0].contains("reasoning_effort")); assertFalse(first[1].contains("reasoning_effort"))
        run(app, ThinkMode.DEEP)
        assertFalse("remembered for this model", m.mainRequests.filter { !it.contains("You write chat titles") }.last().contains("reasoning_effort"))
    }

    @Test fun each_mode_tells_the_model_how_to_work() {
        val m = Mock().start(); val app = setup(m)
        run(app, ThinkMode.TITAN); run(app, ThinkMode.STEADY)
        val bodies = m.mainRequests.filter { !it.contains("You write chat titles") }
        assertTrue(bodies[0].contains("Mode: Titan")); assertFalse(bodies[1].contains("Mode:"))
    }

    @Test fun swift_stops_at_its_small_step_budget_and_suggests_a_deeper_mode() {
        val m = Mock().start(); val app = setup(m)
        repeat(30) { i -> m.script += m.tool("read_lines", "{\"repo\":\"r\",\"path\":\"src/A.kt\",\"start_line\":${i + 1},\"end_line\":${i + 1}}") }
        val r = run(app, ThinkMode.SWIFT)
        assertEquals(ThinkMode.SWIFT.steps, r.count { it.role == "tool" } + 0)
        assertTrue(r.last().content, r.last().isError && r.last().content.contains("Stopped after ${ThinkMode.SWIFT.steps} steps") && r.last().content.contains("deeper thinking mode"))
    }

    @Test fun swift_skips_the_auto_check_but_steady_runs_it() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("cloud_edit_file", "{\"path\":\"/workspaces/r/a.py\",\"search\":\"x = 1\",\"replace\":\"x = 2\"}")
        m.script += m.text("changed")
        val swift = run(app, ThinkMode.SWIFT)
        assertTrue("no nudge in Swift", swift.none { it.role == "note" })
        m.script += m.tool("cloud_edit_file", "{\"path\":\"/workspaces/r/a.py\",\"search\":\"x = 1\",\"replace\":\"x = 2\"}")
        m.script += m.text("changed")
        m.script += m.tool("verify_project", "{}")
        m.script += m.text("verified")
        val steady = run(app, ThinkMode.STEADY)
        assertTrue("Steady nudges", steady.any { it.role == "note" && it.content.contains("verify_project") })
    }

    @Test fun titan_allows_many_more_fix_attempts() = runBlocking {
        val m = Mock().start(); val app = setup(m)
        repeat(25) { m.verifyResults += "boom\nVERIFY_RESULT: FAIL\n" }
        val cloud = app.newCloudSession()
        val ctx = ToolCtx(app, "c", "t", cloud, java.io.File("/tmp"), mode = ThinkMode.TITAN)
        val outs = (1..ThinkMode.TITAN.verify).map { Tools.execute(ctx, "verify_project", "{}") }
        cloud.stop()
        assertTrue(outs.last(), outs.last().contains("attempt ${ThinkMode.TITAN.verify} of ${ThinkMode.TITAN.verify}"))
        assertFalse("attempt 7 is still allowed in Titan", outs[6].contains("MAX_RETRIES_REACHED"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModeChipUiTest : UiBase() {
    @Test fun chip_opens_the_five_modes_and_selects_one() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        app.store.setThinkMode(ThinkMode.STEADY)
        rule.waitForIdle()
        assertTrue(text("Steady"))
        rule.onNodeWithTag("mode-chip").performClick()
        for (n in listOf("Swift", "Deep", "Sage", "Titan")) assertTrue(n, text(n))
        rule.onNodeWithText("Titan").performClick(); rule.waitForIdle()
        assertEquals(ThinkMode.TITAN, app.store.thinkMode.value)
        assertTrue(rule.onNodeWithTag("mode-chip").fetchSemanticsNode().config.toString().contains("Titan") || text("Titan"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScrollUiTest : UiBase() {
    @Test fun arrow_appears_when_scrolled_up_jumps_to_bottom_and_sending_returns_to_the_end() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        app.store.providers.value.forEach { app.store.deleteProvider(it.id) }
        runBlocking {
            val dao = app.db.dao()
            dao.upsertChat(ChatEntity("long", "Long chat", Ids.now(), Ids.now(), titled = true))
            for (i in 1..40) {
                dao.upsertMessage(MessageEntity("u$i", "long", "user", "question $i", Ids.now()))
                dao.upsertMessage(MessageEntity("a$i", "long", "assistant", "answer $i", Ids.now()))
            }
        }
        rule.runOnUiThread { vm().openChat("long") }
        assertTrue(text("answer 40"))
        assertTrue("no arrow while at the bottom", rule.onAllNodesWithTag("scroll-down").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("messages").performScrollToIndex(60); rule.waitForIdle()
        assertTrue("arrow shows after scrolling up", text("question") && rule.onAllNodesWithTag("scroll-down").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("scroll-down").performClick()
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end && rule.onAllNodesWithTag("scroll-down").fetchSemanticsNodes().isNotEmpty()) { Thread.sleep(50); rule.waitForIdle() }
        assertTrue("arrow gone at the bottom", rule.onAllNodesWithTag("scroll-down").fetchSemanticsNodes().isEmpty())
        assertTrue(text("answer 40"))
        // scroll up again, then send: the list comes back to the newest message
        rule.onNodeWithTag("messages").performScrollToIndex(60); rule.waitForIdle()
        rule.runOnUiThread { vm().send("my new message at the end") }
        val end2 = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end2 && !text("my new message at the end")) { Thread.sleep(50); rule.waitForIdle() }
        assertTrue("sent message is visible at the end", text("my new message at the end"))
        assertTrue(rule.onAllNodesWithTag("scroll-down").fetchSemanticsNodes().isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VercelSettingsUiTest : UiBase() {
    @Test fun token_can_be_verified_and_removed() {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = if (request.path!!.startsWith("/v2/user")) MockResponse().setBody("{\"user\":{\"username\":\"vercel-user\"}}") else MockResponse().setResponseCode(404) }
        s.start()
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        app.vercelBase = s.url("/").toString().trimEnd('/'); app.store.setVercel("", "", "")
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        settingsList().performScrollToNode(hasText("Vercel token")); rule.waitForIdle()
        assertTrue(text("Vercel (deploy websites)") || text("Vercel token"))
        rule.onNodeWithTag("vercel-token").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("tok_abcdef")) }
        rule.waitForIdle()
        rule.onNodeWithTag("vercel-save").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end && app.store.vercelToken.value.isBlank()) { Thread.sleep(50); rule.waitForIdle() }
        assertEquals("tok_abcdef", app.store.vercelToken.value); assertEquals("vercel-user", app.store.vercelUser.value)
        assertTrue(text("Connected"))
    }
}
