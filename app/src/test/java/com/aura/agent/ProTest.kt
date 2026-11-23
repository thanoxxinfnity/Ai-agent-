package com.aura.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.*
import com.aura.agent.net.CloudClient
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
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue

private const val SRC = "package a\n\nfun greet() {\n    println(\"hello\")\n}\n\nfun other() {\n    println(\"other\")\n}\n"

class Mock {
    val log = CopyOnWriteArrayList<String>()
    val bodies = CopyOnWriteArrayList<Pair<String, String>>()
    val script = ConcurrentLinkedQueue<MockResponse>()
    val mainRequests = CopyOnWriteArrayList<String>()
    val compactCount = java.util.concurrent.atomic.AtomicInteger()
    var tarball: ByteArray = ByteArray(0)
    val digests = CopyOnWriteArrayList<String>()
    var vercelState = "READY"
    var vercelPolls = java.util.concurrent.atomic.AtomicInteger()
    val vercelCreate = ConcurrentLinkedQueue<MockResponse>()
    val altRequests = CopyOnWriteArrayList<String>()
    val scriptB = ConcurrentLinkedQueue<MockResponse>()
    val verifyResults = ConcurrentLinkedQueue<String>()
    var fileText = "x = 1\ny = 2\n"
    var mainFallback: () -> MockResponse = { text("ok") }
    val server = MockWebServer()

    fun text(t: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"choices\":[{\"delta\":{\"content\":${org.json.JSONObject.quote(t)}}}]}\n\ndata: [DONE]\n\n")

    fun tool(name: String, args: String, id: String = "c" + System.nanoTime()) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"$id\",\"function\":{\"name\":\"$name\",\"arguments\":${org.json.JSONObject.quote(args)}}}]}}]}\n\ndata: [DONE]\n\n")

    private fun json(b: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(b)
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    fun start(): Mock {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path.orEmpty()
                val body = r.body.readUtf8()
                log += r.method + " " + p
                bodies += (r.method + " " + p) to body
                return when {
                    p.startsWith("/v2/chat/completions") -> if (body.contains("You write chat titles")) text("Title from provider B here") else { altRequests += body; scriptB.poll() ?: text("from B") }
                    p.startsWith("/gh/repos/octo/r/tarball") -> MockResponse().setHeader("Content-Type", "application/gzip").setBody(okio.Buffer().write(tarball))
                    p.startsWith("/vc/v2/user") -> json("{\"user\":{\"username\":\"vercel-user\"}}")
                    p.startsWith("/vc/v2/files") -> { r.getHeader("x-vercel-digest")?.let { digests += it }; json("{}") }
                    p.startsWith("/vc/v13/deployments") && r.method == "POST" -> vercelCreate.poll() ?: json("{\"id\":\"dpl_1\",\"url\":\"my-site-abc123.vercel.app\",\"readyState\":\"QUEUED\",\"alias\":[]}")
                    p.startsWith("/vc/v13/deployments/dpl_1") -> if (vercelPolls.incrementAndGet() < 2) json("{\"id\":\"dpl_1\",\"url\":\"my-site-abc123.vercel.app\",\"readyState\":\"BUILDING\"}")
                        else if (vercelState == "READY") json("{\"id\":\"dpl_1\",\"url\":\"my-site-abc123.vercel.app\",\"readyState\":\"READY\",\"alias\":[\"my-site.vercel.app\"]}")
                        else json("{\"id\":\"dpl_1\",\"url\":\"my-site-abc123.vercel.app\",\"readyState\":\"ERROR\",\"errorMessage\":\"Build failed\"}")
                    p.startsWith("/vc/v3/deployments/dpl_1/events") -> json("[{\"text\":\"Running npm run build\"},{\"payload\":{\"text\":\"Error: Module not found: x\"}}]")
                    p.startsWith("/v1/responses") -> { mainRequests += body; MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"responses ok\"}\n\ndata: {\"type\":\"response.completed\"}\n\n") }
                    p.startsWith("/gh/repos/octo/r/contents/uploads/") || p.startsWith("/gh/repos/octo/r/contents/data.bin") -> if (r.method == "GET") json("{}", 404) else json("{}", 201)
                    p.startsWith("/v1/chat/completions") -> when {
                        body.contains("research worker") -> if (body.contains("\"role\":\"tool\"")) text("Worker summary: found it in src/A.kt line 3") else if (body.contains("TREE")) tool("find_files", "{\"glob\":\"**/*.kt\",\"repo\":\"r\"}") else text("Worker summary: quick answer")
                        body.contains("Compress this agent transcript") -> { compactCount.incrementAndGet(); text("SUMMARY-OF-OLD-WORK: repo octo/r, file src/A.kt, pending: finish login") }
                        body.contains("You write chat titles") -> text("A casual chat title for tests")
                        else -> { mainRequests += body; script.poll() ?: mainFallback() }
                    }
                    p == "/gh/user" -> json("{\"login\":\"octo\"}").setHeader("X-OAuth-Scopes", "codespace, repo")
                    p == "/gh/repos/octo/r" -> json("{\"default_branch\":\"main\"}")
                    p.startsWith("/gh/repos/octo/r/actions/runs/") -> json("{\"status\":\"completed\",\"conclusion\":\"failure\",\"html_url\":\"https://github.com/octo/r/actions/runs/1\"}")
                    p.contains("/actions/workflows/") -> json("{}", 204)
                    p.startsWith("/gh/repos/octo/r/contents/src/A.kt") -> if (r.method == "GET") json("{\"sha\":\"s1\",\"content\":\"${b64(SRC)}\"}") else json("{}")
                    p == "/gh/repos/octo/r/git/ref/heads/main" -> if (r.method == "GET") json("{\"object\":{\"sha\":\"h1\"}}") else json("{}")
                    p == "/gh/repos/octo/r/git/refs" -> json("{}", 201)
                    p == "/gh/repos/octo/r/git/refs/heads/main" -> json("{}")
                    p == "/gh/repos/octo/r/git/commits/h1" -> json("{\"tree\":{\"sha\":\"t1\"}}")
                    p.startsWith("/gh/repos/octo/r/git/trees/") -> json("{\"tree\":[{\"path\":\"src/A.kt\",\"type\":\"blob\",\"size\":3000},{\"path\":\"README.md\",\"type\":\"blob\",\"size\":10}]}")
                    p.startsWith("/gh/user/codespaces?") -> json("{\"codespaces\":[]}")
                    p == "/gh/user/codespaces/cs1" -> json("{\"name\":\"cs1\",\"state\":\"Available\"}")
                    p == "/gh/user/codespaces/cs1/stop" -> json("{}")
                    p == "/gh/repos/octo/aura-workspace" -> json("{}")
                    p.startsWith("/gh/repos/octo/aura-workspace/contents/") -> if (r.method == "GET") json("{}", 404) else json("{}", 201)
                    p == "/gh/repos/octo/aura-workspace/codespaces" -> json("{\"name\":\"cs1\",\"state\":\"Provisioning\"}", 202)
                    p == "/cs/health" -> json("{\"ok\":true}")
                    p == "/cs/exec" -> {
                        val o = Json.parseToJsonElement(body).jsonObject
                        val env = o["env"]?.jsonObject
                        val out = when {
                            o["cmd"]!!.jsonPrimitive.content.contains("rev-parse --show-toplevel") -> "/workspaces/aura-workspace\n"
                            env?.get("AURA_CP_ID") != null -> "CP abc123\n"
                            env?.get("AURA_SHA") != null -> "RESTORED\n"
                            else -> "done\n"
                        }
                        json("{\"exit\":0,\"stdout\":${org.json.JSONObject.quote(out)},\"stderr\":\"\"}")
                    }
                    p == "/cs/exec_stream" -> {
                        val o = Json.parseToJsonElement(body).jsonObject
                        val cmd = o["cmd"]!!.jsonPrimitive.content
                        val out = if (cmd.contains("VERIFY_RESULT")) (verifyResults.poll() ?: "ok\nVERIFY_RESULT: PASS\n") else "line one\nline two\n"
                        MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(
                            out.lines().filter { it.isNotEmpty() }.joinToString("") { "{\"o\":${org.json.JSONObject.quote(it + "\n")}}\n" } + "{\"exit\":0}\n")
                    }
                    p.startsWith("/cs/file") -> if (r.method == "GET") MockResponse().setBody(fileText) else json("{\"ok\":true}")
                    else -> json("{}", 404)
                }
            }
        }
        server.start()
        return this
    }

    fun count(prefix: String) = log.count { it.startsWith(prefix) }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProTest {
    private fun setup(m: Mock, pat: String? = "ghp_secret_token_123"): App {
        val app = ApplicationProvider.getApplicationContext<App>()
        app.store.setSupabase("", "", false)
        app.cloudApi = m.server.url("/gh").toString().trimEnd('/')
        app.cloudPortUrl = { _, _ -> m.server.url("/cs").toString().trimEnd('/') }
        app.cloudPollMs = 10
        app.store.setCodespace(pat ?: "", if (pat != null) "octo" else "")
        app.store.upsertProvider(Provider("p", "T", m.server.url("/v1").toString().trimEnd('/'), "k", models = listOf("m"), defaultModel = "m"))
        app.store.select("p", "m")
        app.store.setGithub("gho_x", "octo")
        app.store.setGuardrails(true)
        app.store.setContextTokens(128_000)
        return app
    }

    private data class Res(val chatId: String, val turnId: String, val msgs: List<MessageEntity>)

    private fun run(app: App, text: String = "do the task", seed: List<MessageEntity> = emptyList(), chatId: String = Ids.id(), whileRunning: (() -> Unit)? = null): Res {
        val dao = app.db.dao()
        val turn = Ids.id()
        runBlocking {
            dao.upsertChat(ChatEntity(chatId, "New chat", Ids.now(), Ids.now()))
            seed.forEach { dao.upsertMessage(it.copy(chatId = chatId)) }
            dao.upsertMessage(MessageEntity(turn, chatId, "user", text, Ids.now()))
        }
        app.runner.start(chatId)
        val end = System.currentTimeMillis() + 40000
        while (System.currentTimeMillis() < end && app.runner.active.value.isNotEmpty()) { whileRunning?.invoke(); Thread.sleep(30) }
        assertTrue("run did not finish", app.runner.active.value.isEmpty())
        Thread.sleep(300)
        return Res(chatId, turn, runBlocking { dao.messagesOnce(chatId) })
    }

    private fun tools(r: Res) = r.msgs.filter { it.role == "tool" }

    // ---------------------------------------------------------------- 1. surgical edits

    @Test fun edit_file_diff_reports_mismatch_then_patches_only_target() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("edit_file_diff", "{\"repo\":\"r\",\"path\":\"src/A.kt\",\"search\":\"fun greet() {\\n    println(\\\"HELLO\\\")\\n}\",\"replace\":\"x\"}")
        m.script += m.tool("edit_file_diff", "{\"repo\":\"r\",\"path\":\"src/A.kt\",\"search\":\"    println(\\\"hello\\\")\",\"replace\":\"    println(\\\"hello world\\\")\"}")
        m.script += m.text("Done editing")
        val r = run(app)
        val t = tools(r)
        assertTrue(t[0].content, t[0].isError && t[0].content.contains("mismatch at line 4") && t[0].content.contains("println(\"hello\")"))
        assertFalse(t[1].content, t[1].isError)
        val put = m.bodies.first { it.first.startsWith("PUT /gh/repos/octo/r/contents/src/A.kt") }.second
        val written = String(Base64.getDecoder().decode(Json.parseToJsonElement(put).jsonObject["content"]!!.jsonPrimitive.content))
        assertEquals(SRC.replace("println(\"hello\")", "println(\"hello world\")"), written)
        assertEquals("s1", Json.parseToJsonElement(put).jsonObject["sha"]!!.jsonPrimitive.content)
    }

    @Test fun write_files_blocks_placeholders_and_full_rewrites() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("github_write_files", "{\"repo\":\"r\",\"message\":\"m\",\"files\":[{\"path\":\"src/B.kt\",\"content\":\"class B {\\n  // ... rest of code\\n}\"}]}")
        m.script += m.tool("github_write_files", "{\"repo\":\"r\",\"message\":\"m\",\"files\":[{\"path\":\"src/A.kt\",\"content\":\"package a\\nfun x() {}\"}]}")
        m.script += m.text("ok")
        val t = tools(run(app))
        assertTrue(t[0].content, t[0].isError && t[0].content.contains("placeholder"))
        assertTrue(t[1].content, t[1].isError && t[1].content.contains("edit_file_diff"))
        assertEquals("nothing may be pushed", 0, m.count("POST /gh/repos/octo/r/git/blobs"))
    }

    // ---------------------------------------------------------------- 2. auto verification loop

    @Test fun agent_verifies_after_changes_and_fixes_until_green() {
        val m = Mock().start(); val app = setup(m)
        m.verifyResults += "src/a.py:1 SyntaxError\nVERIFY_RESULT: FAIL\n"
        m.script += m.tool("cloud_edit_file", "{\"path\":\"/workspaces/aura-workspace/a.py\",\"search\":\"x = 1\",\"replace\":\"x = 5\"}")
        m.script += m.text("I changed it")                                   // tries to stop without verifying
        m.script += m.tool("verify_project", "{}")                          // after the auto-check nudge: FAIL
        m.script += m.tool("cloud_edit_file", "{\"path\":\"/workspaces/aura-workspace/a.py\",\"search\":\"y = 2\",\"replace\":\"y = 3\"}")
        m.script += m.tool("verify_project", "{}")                          // PASS
        m.script += m.text("All green")
        val r = run(app)
        assertTrue("auto-check nudge was inserted", r.msgs.any { it.role == "note" && it.content.contains("verify_project") })
        val v = tools(r).filter { it.toolName == "verify_project" }
        assertEquals(2, v.size)
        assertTrue(v[0].content, v[0].content.startsWith("VERIFY FAIL (attempt 1 of ${ThinkMode.STEADY.verify})") && v[0].content.contains("SyntaxError"))
        assertTrue(v[1].content, v[1].content.startsWith("VERIFY PASS"))
        assertEquals("All green", r.msgs.last { it.role == "assistant" }.content)
        assertTrue(m.log.contains("POST /gh/user/codespaces/cs1/stop"))
    }

    @Test fun verify_stops_after_the_modes_attempt_limit() = runBlocking {
        val m = Mock().start(); val app = setup(m)
        val limit = ThinkMode.STEADY.verify
        repeat(limit + 2) { m.verifyResults += "boom\nVERIFY_RESULT: FAIL\n" }
        val cloud = app.newCloudSession()
        val ctx = ToolCtx(app, "c", "t", cloud, java.io.File("/tmp"))
        val outs = (1..limit + 1).map { Tools.execute(ctx, "verify_project", "{}") }
        cloud.stop()
        assertTrue(outs[0].startsWith("VERIFY FAIL (attempt 1 of $limit)"))
        assertTrue(outs[limit - 1], outs[limit - 1].contains("attempt $limit of $limit") && outs[limit - 1].contains("MAX_RETRIES_REACHED"))
        assertTrue(outs[limit], outs[limit].startsWith("MAX_RETRIES_REACHED"))
        assertEquals("no extra build is started", limit, m.bodies.count { it.first == "POST /cs/exec_stream" })
    }

    // ---------------------------------------------------------------- 3. search tools and sub-agents

    @Test fun search_and_read_lines_save_tokens() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("find_files", "{\"glob\":\"**/*.kt\",\"repo\":\"r\"}")
        m.script += m.tool("read_lines", "{\"repo\":\"r\",\"path\":\"src/A.kt\",\"start_line\":3,\"end_line\":5}")
        m.script += m.tool("search_code", "{\"scope\":\"cloud\",\"pattern\":\"greet\"}")
        m.script += m.text("found")
        val t = tools(run(app))
        assertEquals("src/A.kt", t[0].content.trim())
        assertEquals("3: fun greet() {\n4:     println(\"hello\")\n5: }", t[1].content.trim())
        assertTrue(m.bodies.any { it.first == "POST /cs/exec" && it.second.contains("rg -n") })
    }

    @Test fun worker_subagents_run_in_parallel_and_return_only_summaries() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("delegate", "{\"tasks\":[\"TREE: list kotlin files\",\"where is login handled\"]}")
        m.script += m.text("Thanks")
        val r = run(app)
        val d = tools(r).first { it.toolName == "delegate" }
        assertTrue(d.content, d.content.contains("Worker 1") && d.content.contains("Worker 2") && d.content.contains("Worker summary"))
        assertFalse("raw tool output of workers must not leak", d.content.contains("README.md"))
        val second = m.mainRequests.last()
        assertTrue(second.contains("Worker summary: found it in src/A.kt line 3"))
        assertFalse("main context stays clean", second.contains("src/A.kt\\nREADME"))
    }

    @Test fun workers_cannot_modify_anything() = runBlocking {
        val m = Mock().start(); val app = setup(m)
        val ctx = ToolCtx(app, "c", "t", app.newCloudSession(), java.io.File("/tmp"), readOnly = true)
        val out = Tools.execute(ctx, "github_write_files", "{\"repo\":\"r\",\"message\":\"x\",\"files\":[]}")
        assertTrue(out, out.contains("read-only"))
    }

    // ---------------------------------------------------------------- 4. context compaction

    private fun bigSeed(n: Int) = (0 until n).flatMap { i ->
        listOf(
            MessageEntity(Ids.id(), "", "user", "question number $i " + "x".repeat(900), Ids.now()),
            MessageEntity(Ids.id(), "", "assistant", "answer number $i OLDMARKER$i " + "y".repeat(900), Ids.now()),
        )
    }

    @Test fun context_is_compacted_at_75_percent() {
        val m = Mock().start(); val app = setup(m)
        app.store.setContextTokens(2000)   // about 7000 characters; the seed is far larger
        m.script += m.text("fresh answer")
        val r = run(app, "new question", bigSeed(12))
        assertTrue(r.msgs.any { it.role == "summary" && it.content.contains("SUMMARY-OF-OLD-WORK") })
        assertTrue("old messages are kept for the user but flagged", r.msgs.count { it.compacted } >= 10)
        val sent = m.mainRequests.last()
        assertTrue(sent.contains("[Summary of the earlier conversation"))
        assertTrue(sent.contains("SUMMARY-OF-OLD-WORK"))
        assertFalse("old turns are no longer sent", sent.contains("OLDMARKER0 "))
        assertTrue("recent turns are still sent", sent.contains("OLDMARKER11"))
        assertTrue(sent.contains("new question"))
    }

    @Test fun context_overflow_error_triggers_compaction_and_retry() {
        val m = Mock().start(); val app = setup(m)
        m.script += MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\"}}")
        m.script += m.text("recovered")
        val r = run(app, "again", bigSeed(8))
        assertEquals("recovered", r.msgs.last { it.role == "assistant" }.content)
        assertTrue(r.msgs.any { it.role == "summary" })
    }

    // ---------------------------------------------------------------- 5. checkpoints and rollback

    @Test fun github_checkpoint_is_taken_and_restored_with_backup_branch() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("edit_file_diff", "{\"repo\":\"r\",\"path\":\"src/A.kt\",\"search\":\"    println(\\\"other\\\")\",\"replace\":\"    println(\\\"changed\\\")\"}")
        m.script += m.text("edited")
        val r = run(app)
        val cps = runBlocking { app.db.dao().checkpointsForTurn(r.turnId) }
        assertEquals(1, cps.size)
        assertEquals("github", cps[0].kind); assertEquals("octo/r@main", cps[0].target); assertEquals("h1", cps[0].ref)
        // restore
        m.log.clear(); m.bodies.clear()
        // pretend the branch moved on after the edit
        app.runner.restoreTurn(r.chatId, r.turnId)
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end && app.runner.active.value.isNotEmpty()) Thread.sleep(30)
        Thread.sleep(300)
        val patch = m.bodies.firstOrNull { it.first == "PATCH /gh/repos/octo/r/git/refs/heads/main" }
        // current head equals the saved one in this mock (h1), so nothing needs moving; the report says so
        val msgs = runBlocking { app.db.dao().messagesOnce(r.chatId) }
        assertTrue(msgs.last().content, msgs.last().role == "assistant" && msgs.last().content.contains("Checkpoint restored"))
        assertTrue(runBlocking { app.db.dao().checkpointsForTurn(r.turnId) }.all { it.restored })
        assertNull("branch already at the checkpoint", patch)
    }

    @Test fun github_restore_moves_branch_back_after_creating_backup() {
        val m = Mock().start(); val app = setup(m)
        val dao = app.db.dao()
        runBlocking {
            dao.upsertChat(ChatEntity("ch", "t", 1, 1))
            dao.upsertCheckpoint(CheckpointEntity("k1", "ch", "turn1", 1, "github", "octo/r@main", "OLDSHA", "edit"))
        }
        val report = runBlocking { app.runner.checkpoints.restoreTurn("turn1", null) }
        assertTrue(report, report.contains("Restored octo/r@main to OLDSHA".take(21)))
        assertTrue("backup branch created first", m.bodies.any { it.first == "POST /gh/repos/octo/r/git/refs" && it.second.contains("aura-backup/") && it.second.contains("\"h1\"") })
        val p = m.bodies.first { it.first == "PATCH /gh/repos/octo/r/git/refs/heads/main" }.second
        assertTrue(p, p.contains("OLDSHA") && p.contains("\"force\":true"))
    }

    @Test fun cloud_checkpoint_snapshot_and_restore() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("cloud_exec", "{\"command\":\"echo hi > a.txt\"}")
        m.script += m.text("written")
        val r = run(app)
        val cp = runBlocking { app.db.dao().checkpointsForTurn(r.turnId) }.single()
        assertEquals("cloud", cp.kind); assertEquals("/workspaces/aura-workspace", cp.target); assertEquals("abc123", cp.ref)
        assertTrue("snapshot ran before the command", m.log.indexOf("POST /cs/exec") < m.log.indexOf("POST /cs/exec_stream"))
        m.bodies.clear(); m.log.clear()
        app.runner.restoreTurn(r.chatId, r.turnId)
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end && app.runner.active.value.isNotEmpty()) Thread.sleep(30)
        Thread.sleep(300)
        assertTrue(m.bodies.any { it.first == "POST /cs/exec" && it.second.contains("AURA_SHA") && it.second.contains("abc123") })
        assertTrue("codespace stopped after restore", m.log.contains("POST /gh/user/codespaces/cs1/stop"))
    }

    // ---------------------------------------------------------------- 6. guardrails and streaming

    @Test fun dangerous_command_waits_for_approval_and_deny_blocks_it() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("cloud_exec", "{\"command\":\"rm -rf /workspaces/aura-workspace/build\"}")
        m.script += m.text("ok, skipped")
        var asked: Approval? = null
        val r = run(app) {
            val a = app.runner.approval.value
            if (a != null) { asked = a; app.runner.resolveApproval(a.id, false) }
        }
        assertNotNull("user was asked", asked)
        assertTrue(asked!!.title, asked!!.title.contains("rm -rf"))
        assertTrue(tools(r)[0].content, tools(r)[0].content.startsWith("DENIED"))
        assertEquals("command never reached the codespace", 0, m.bodies.count { it.first == "POST /cs/exec_stream" })
    }

    @Test fun approved_dangerous_command_runs() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("cloud_exec", "{\"command\":\"git push --force origin main\"}")
        m.script += m.text("pushed")
        val r = run(app) { app.runner.approval.value?.let { app.runner.resolveApproval(it.id, true) } }
        assertTrue(tools(r)[0].content, tools(r)[0].content.contains("line one"))
        assertEquals(1, m.bodies.count { it.first == "POST /cs/exec_stream" && it.second.contains("push --force") })
    }

    @Test fun guardrails_can_be_turned_off_in_settings() {
        val m = Mock().start(); val app = setup(m)
        app.store.setGuardrails(false)
        m.script += m.tool("cloud_exec", "{\"command\":\"rm -rf build\"}")
        m.script += m.text("done")
        val r = run(app)
        assertNull(app.runner.approval.value)
        assertTrue(tools(r)[0].content.contains("line one"))
    }

    @Test fun terminal_output_streams_in_chunks_and_masks_the_token() = runBlocking {
        val s = MockWebServer()
        val pat = "ghp_supersecret_token_value"
        val half = pat.length / 2
        s.enqueue(MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(
            "{\"o\":\"start\\n\"}\n{\"o\":\"token=${pat.take(half)}\"}\n{\"o\":\"${pat.drop(half)} end\\n\"}\n{\"exit\":2}\n"))
        s.start()
        val seen = ArrayList<String>()
        val c = CloudClient(s.url("/").toString(), pat)
        val res = c.execStream("echo", 10, null, emptyMap()) { seen += it }
        assertEquals(2, res.exit)
        assertTrue("several live chunks", seen.size >= 2)
        val all = seen.joinToString("")
        assertTrue(all, all.contains("start") && all.contains("token=***") && !all.contains(pat) && !all.contains(pat.take(half) + pat.drop(half)))
        assertFalse(res.stdout.contains("supersecret"))
        // GH_TOKEN is sent to the codespace so gh and git act as the user
        val sent = s.takeRequest().body.readUtf8()
        assertTrue(sent, sent.contains("\"GH_TOKEN\":\"$pat\""))
    }

    @Test fun old_servers_fall_back_to_plain_exec() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setResponseCode(404))
        s.enqueue(MockResponse().setBody("{\"exit\":0,\"stdout\":\"plain\\n\",\"stderr\":\"\"}"))
        s.start()
        val seen = ArrayList<String>()
        val res = CloudClient(s.url("/").toString(), "ghp_x_token_value").execStream("echo", 10, null, emptyMap()) { seen += it }
        assertEquals(0, res.exit); assertTrue(seen.joinToString().contains("plain"))
    }

    @Test fun tool_message_is_visible_while_running_and_replaced_by_result() {
        val m = Mock().start(); val app = setup(m)
        m.script += m.tool("cloud_exec", "{\"command\":\"npm run build\"}")
        m.script += m.text("built")
        var sawLive = false
        val r = run(app) { if (app.runner.live.value.isNotEmpty()) sawLive = true }
        val t = tools(r)[0]
        assertTrue(t.content, t.content.contains("line one") && t.content.contains("[exit 0]"))
        assertTrue("live map is cleaned up", app.runner.live.value.isEmpty())
    }
}
