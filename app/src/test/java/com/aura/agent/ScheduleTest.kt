package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.ScheduledCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScheduleTest {
    private fun clear(app: com.aura.agent.App) { app.extras.scheduled.value.forEach { app.extras.removeScheduled(it.id) } }

    @Test fun the_agent_can_schedule_a_check_and_the_limits_hold() {
        val m = Mock().start(); val app = Env.setup(m); clear(app)
        m.script += m.tool("schedule_check", """{"minutes":10,"note":"look at the build run and fix errors"}""")
        m.script += m.text("I will check again in 10 minutes.")
        val r = Env.run(app, "build it and keep going")
        val t = r.tools.single()
        assertTrue(t.content, !t.isError && t.content.contains("Scheduled") && t.content.contains("10 minute"))
        val c = app.extras.scheduled.value.single()
        assertEquals(r.chatId, c.chatId); assertEquals("look at the build run and fix errors", c.note)
        assertTrue(c.atMillis > System.currentTimeMillis() + 9 * 60_000 && c.atMillis < System.currentTimeMillis() + 11 * 60_000)

        fun call(args: String): String { val ctx = ToolCtx(app, r.chatId, "t", app.newCloudSession(), java.io.File(app.filesDir, "o"), approve = { _, _ -> true }); return runBlocking { Tools.execute(ctx, "schedule_check", args) } }
        assertTrue(call("""{"minutes":0,"note":"x y z"}""").startsWith("ERROR"))
        assertTrue(call("""{"minutes":9999,"note":"x y z"}""").startsWith("ERROR"))
        assertTrue(call("""{"minutes":5,"note":""}""").startsWith("ERROR"))
        assertTrue(!call("""{"minutes":5,"note":"second one"}""").startsWith("ERROR")); assertTrue(!call("""{"minutes":6,"note":"third one"}""").startsWith("ERROR"))
        assertTrue("only 3 per chat", call("""{"minutes":7,"note":"fourth one"}""").contains("already waiting"))
        val ctx = ToolCtx(app, r.chatId, "t", app.newCloudSession(), java.io.File(app.filesDir, "o"), approve = { _, _ -> true })
        assertTrue(runBlocking { Tools.execute(ctx, "schedule_cancel", "{}") }.contains("Cancelled 3"))
        assertTrue(app.extras.scheduled.value.isEmpty())
        clear(app)
    }

    @Test fun a_due_check_wakes_the_chat_with_the_note_and_a_busy_chat_is_asked_again_later() {
        val m = Mock().start(); val app = Env.setup(m); clear(app)
        m.script += m.text("first answer")
        val r = Env.run(app, "start")
        app.extras.resetChain(r.chatId)
        app.extras.addScheduled(ScheduledCheck("c1", r.chatId, System.currentTimeMillis() - 1000, "check the deploy"))
        app.extras.addScheduled(ScheduledCheck("c2", r.chatId, System.currentTimeMillis() + 3_600_000, "later one"))
        m.script += m.text("the deploy is live")
        val woken = runBlocking { Scheduler.fireDue(app) }
        assertEquals(1, woken)
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end && app.runner.active.value.isNotEmpty()) Thread.sleep(30)
        Thread.sleep(300)
        val msgs = runBlocking { app.db.dao().messagesOnce(r.chatId) }
        val wake = msgs.last { it.role == "user" }
        assertTrue(wake.content, wake.content.contains("[Scheduled check 1 of ${ToolsSchedule.MAX_CHAIN}]") && wake.content.contains("check the deploy"))
        assertEquals("the agent answered the wake-up", "the deploy is live", msgs.last { it.role == "assistant" }.content)
        assertEquals("only the not-yet-due one is left", listOf("c2"), app.extras.scheduled.value.map { it.id })
        assertEquals(1, app.extras.chainOf(r.chatId))
        // a message from the user restarts the count
        app.extras.resetChain(r.chatId); assertEquals(0, app.extras.chainOf(r.chatId))
        clear(app)
    }

    @Test fun a_chain_of_wake_ups_without_the_user_is_cut_off_and_workers_cannot_schedule() {
        val m = Mock().start(); val app = Env.setup(m); clear(app)
        val chat = "chain-chat"
        repeat(ToolsSchedule.MAX_CHAIN) { app.extras.bumpChain(chat) }
        val ctx = ToolCtx(app, chat, "t", app.newCloudSession(), java.io.File(app.filesDir, "o"), approve = { _, _ -> true })
        assertTrue(runBlocking { Tools.execute(ctx, "schedule_check", """{"minutes":5,"note":"again please"}""") }.contains("woke itself up"))
        val worker = ToolCtx(app, chat, "t", app.newCloudSession(), java.io.File(app.filesDir, "o"), readOnly = true)
        assertTrue(runBlocking { Tools.execute(worker, "schedule_check", """{"minutes":5,"note":"again please"}""") }.startsWith("ERROR"))
        app.extras.resetChain(chat); clear(app)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScheduleUiTest : UiBase() {
    @Test fun waiting_checks_are_listed_in_settings_and_can_be_cancelled() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val ex = vm().extras; ex.scheduled.value.forEach { ex.removeScheduled(it.id) }
        ex.addScheduled(ScheduledCheck("u1", "chat-x", System.currentTimeMillis() + 600_000, "look at the build"))
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        settingsList().performScrollToNode(hasTestTag("sched-row")); rule.waitForIdle()
        assertTrue(text("look at the build"))
        rule.onNodeWithTag("sched-cancel").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle()
        assertTrue(ex.scheduled.value.isEmpty()); assertTrue(text("Nothing is waiting"))
    }
}
