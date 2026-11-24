package com.aura.agent.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aura.agent.App
import com.aura.agent.data.Ids
import com.aura.agent.data.MessageEntity

/** Sets one phone alarm for the earliest scheduled check, and when it rings wakes the chat up. */
object Scheduler {
    const val ACTION_FIRE = "com.aura.agent.SCHEDULE_FIRE"
    @Volatile var fireDelayMs = 2 * 60_000L   // a chat that is still busy is asked again after this long

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 7, Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION_FIRE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** (Re)sets the alarm for the earliest waiting check; cancels it when none is waiting. Safe to call any time. */
    fun arm(app: App) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val next = app.extras.scheduled.value.minByOrNull { it.atMillis }
        val pi = pending(app)
        runCatching { am.cancel(pi) }
        if (next == null) return
        val at = maxOf(next.atMillis, System.currentTimeMillis() + 1000)
        runCatching {
            // an exact alarm is allowed to start the background service; without that permission an inexact one is used
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Wakes every chat whose check is due: adds the note as a message from Aura's scheduler and starts the run. Returns how many chats were woken. */
    suspend fun fireDue(app: App, now: Long = System.currentTimeMillis()): Int {
        val due = app.extras.takeDue(now)
        var woken = 0
        for (c in due) {
            val chat = app.db.dao().chat(c.chatId) ?: continue
            if (app.runner.isActive(c.chatId)) { app.extras.addScheduled(c.copy(atMillis = now + fireDelayMs)); continue }   // still working: look again shortly
            app.extras.bumpChain(c.chatId)
            val n = app.extras.chainOf(c.chatId)
            app.db.dao().upsertMessage(MessageEntity(Ids.id(), c.chatId, "user",
                "[Scheduled check $n of ${ToolsSchedule.MAX_CHAIN}] You asked to be woken up. Your note: ${c.note}\nDo it now. If the job is still running and needs more time, schedule another check; if it is done, give the user the result.", Ids.now()))
            app.db.dao().touch(c.chatId, Ids.now())
            app.runner.start(c.chatId)
            woken++
        }
        arm(app)
        return woken
    }
}

/** Receives the alarm (and the phone restarting, which clears alarms) and hands over to the background service. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? App ?: return
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) { Scheduler.arm(app); return }
        AgentService.start(app, scheduled = true)
    }
}
