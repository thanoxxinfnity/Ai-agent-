package com.aura.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LoopGuardTest {
    private fun setup(m: Mock): App {
        val app = ApplicationProvider.getApplicationContext<App>()
        app.store.setSupabase("", "", false)
        app.cloudApi = m.server.url("/gh").toString().trimEnd('/')
        app.cloudPortUrl = { _, _ -> m.server.url("/cs").toString().trimEnd('/') }
        app.cloudPollMs = 10
        app.store.setCodespace("ghp_secret_token_123", "octo")
        app.store.providers.value.forEach { app.store.deleteProvider(it.id) }
        app.store.upsertProvider(Provider("p", "T", m.server.url("/v1").toString().trimEnd('/'), "k", models = listOf("m"), defaultModel = "m"))
        app.store.select("p", "m")
        app.store.setGithub("gho_x", "octo")
        app.store.setGuardrails(true); app.extras.setAskBeforePush(false); app.store.setContextTokens(128_000)
        return app
    }

    private fun run(app: App, seed: List<MessageEntity> = emptyList()): List<MessageEntity> {
        val dao = app.db.dao(); val chat = Ids.id()
        runBlocking {
            dao.upsertChat(ChatEntity(chat, "New chat", Ids.now(), Ids.now(), repo = "octo/r"))
            seed.forEach { dao.upsertMessage(it.copy(chatId = chat)) }
            dao.upsertMessage(MessageEntity(Ids.id(), chat, "user", "go", Ids.now()))
        }
        app.runner.start(chat)
        val end = System.currentTimeMillis() + 40000
        while (System.currentTimeMillis() < end && app.runner.active.value.isNotEmpty()) Thread.sleep(30)
        assertTrue("run did not finish", app.runner.active.value.isEmpty())
        Thread.sleep(300)
        return runBlocking { dao.messagesOnce(chat) }
    }

    private fun tools(r: List<MessageEntity>) = r.filter { it.role == "tool" }

    @Test fun the_same_command_with_the_same_output_is_stopped_even_for_cloud_tools() {
        val m = Mock().start(); val app = setup(m)
        repeat(9) { m.script += m.tool("cloud_exec", "{\"command\":\"ls\"}") }
        val r = run(app)
        val t = tools(r)
        assertEquals("stopped at the 5th identical result, not after 50 steps", 5, t.size)
        assertTrue(t[2].content, t[2].content.contains("LOOP_DETECTED"))
        assertTrue(r.last().content, r.last().isError && r.last().content.contains("without progress"))
        assertTrue("the codespace was still stopped", m.log.contains("POST /gh/user/codespaces/cs1/stop"))
        assertTrue("far fewer model requests than the step limit", m.mainRequests.size <= 6)
    }

    @Test fun changing_results_are_not_mistaken_for_a_loop() {
        val m = Mock().start(); val app = setup(m)
        repeat(8) { i -> m.script += m.tool("read_lines", "{\"repo\":\"r\",\"path\":\"src/A.kt\",\"start_line\":${i + 1},\"end_line\":${i + 1}}") }
        m.script += m.text("read everything")
        val r = run(app)
        assertEquals(8, tools(r).size)
        assertEquals("read everything", r.last { it.role == "assistant" }.content)
    }

    @Test fun six_failures_in_a_row_stop_the_run() {
        val m = Mock().start(); val app = setup(m)
        val limit = ThinkMode.STEADY.streak
        repeat(limit + 3) { i -> m.script += m.tool("github_read_file", "{\"repo\":\"r\",\"path\":\"missing$i.txt\"}") }
        val r = run(app)
        assertEquals(limit, tools(r).size)
        assertTrue(r.last().content, r.last().isError && r.last().content.contains("$limit steps in a row failed"))
    }

    @Test fun a_tool_that_keeps_failing_is_switched_off_for_the_rest_of_the_answer() {
        val m = Mock().start(); val app = setup(m)
        repeat(6) { i -> m.script += m.tool("github_read_file", "{\"repo\":\"r\",\"path\":\"gone$i.txt\"}") }
        val t = tools(run(app))
        assertFalse(t[3].content, t[3].content.contains("switched off"))
        assertTrue(t[4].content, t[4].content.contains("switched off for this answer"))
        assertEquals("the switched-off tool was not called for real again", 4, m.log.count { it.contains("/contents/gone") })
    }

    @Test fun a_success_resets_the_failure_streak() {
        val m = Mock().start(); val app = setup(m)
        repeat(5) { i -> m.script += m.tool("github_read_file", "{\"repo\":\"r\",\"path\":\"missing$i.txt\"}") }
        m.script += m.tool("read_lines", "{\"repo\":\"r\",\"path\":\"src/A.kt\",\"start_line\":1,\"end_line\":2}")
        repeat(5) { i -> m.script += m.tool("github_read_file", "{\"repo\":\"r\",\"path\":\"other$i.txt\"}") }
        m.script += m.text("gave up politely")
        val r = run(app)
        assertEquals(11, tools(r).size)
        assertEquals("gave up politely", r.last { it.role == "assistant" }.content)
    }

    @Test fun build_fix_attempts_are_capped_by_the_thinking_mode() {
        val m = Mock().start(); val app = setup(m)
        val limit = ThinkMode.STEADY.verify
        for (id in 1..limit) m.script += m.tool("github_wait_run", "{\"repo\":\"r\",\"run_id\":$id}")
        m.script += m.tool("github_run_workflow", "{\"repo\":\"r\",\"workflow\":\"build.yml\"}")
        m.script += m.text("the build still fails, here is why")
        val r = run(app)
        val t = tools(r)
        assertTrue(t[0].content, t[0].content.contains("Build failure 1 of $limit"))
        assertTrue(t[limit - 1].content, t[limit - 1].content.contains("Build failure $limit of $limit") && t[limit - 1].content.contains("MAX_RETRIES_REACHED"))
        assertTrue(t[limit].content, t[limit].content.startsWith("MAX_RETRIES_REACHED"))
        assertEquals("the workflow was not started again", 0, m.bodies.count { it.first.contains("/dispatches") })
    }

    @Test fun context_compaction_runs_once_not_at_every_step() {
        val m = Mock().start(); val app = setup(m)
        app.store.setContextTokens(1000)   // so small that the history always looks too big
        repeat(6) { i -> m.script += m.tool("read_lines", "{\"repo\":\"r\",\"path\":\"src/A.kt\",\"start_line\":${i + 1},\"end_line\":${i + 1}}") }
        m.script += m.text("done reading")
        val seed = (0 until 12).flatMap { i ->
            listOf(MessageEntity(Ids.id(), "", "user", "question $i " + "x".repeat(900), Ids.now()), MessageEntity(Ids.id(), "", "assistant", "answer $i " + "y".repeat(900), Ids.now()))
        }
        val r = run(app, seed)
        assertEquals("done reading", r.last { it.role == "assistant" }.content)
        assertEquals("one summary request in total", 1, m.compactCount.get())
        assertEquals(1, r.count { it.role == "summary" })
    }
}
