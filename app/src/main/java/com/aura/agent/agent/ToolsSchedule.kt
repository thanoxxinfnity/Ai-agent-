package com.aura.agent.agent

import com.aura.agent.data.Ids
import com.aura.agent.data.ScheduledCheck
import kotlinx.serialization.json.*

/**
 * Lets the agent ask to be woken up later (to look at a build, a deploy or a long job again) so the user does not have to type "continue".
 * Limits keep it harmless: a few waiting checks per chat, at most 12 hours ahead, and a chain of wake-ups ends after [MAX_CHAIN] without a message from the user.
 */
object ToolsSchedule {
    val NAMES = setOf("schedule_check", "schedule_cancel")
    const val MAX_PER_CHAT = 3
    const val MAX_TOTAL = 10
    const val MAX_CHAIN = 8
    const val MAX_MINUTES = 720

    val defs: List<com.aura.agent.net.ToolDef> = listOf(
        com.aura.agent.net.ToolDef(
            "schedule_check",
            "Ask Aura to wake you up in this chat after some minutes, so you can look again without the user typing continue. Use it when you must wait for something slow (a build, a deploy, a long job): start it, call schedule_check with the number of minutes and a note saying exactly what to check, then end your turn with a short message. When the time comes you get a message with your note. Do not poll in a loop and do not wait inside a tool.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("minutes") { put("type", "number"); put("description", "Minutes from now, 1 to $MAX_MINUTES") }
                    putJsonObject("note") { put("type", "string"); put("description", "What to check and what to do next, written for yourself") }
                }
                putJsonArray("required") { add("minutes"); add("note") }
            },
        ),
        com.aura.agent.net.ToolDef("schedule_cancel", "Cancel the wake-ups you scheduled in this chat (for example when the job already finished).", buildJsonObject { put("type", "object"); putJsonObject("properties") { } }),
    )

    suspend fun run(ctx: ToolCtx, name: String, a: JsonObject): String {
        val ex = ctx.app.extras
        return when (name) {
            "schedule_check" -> {
                val minutes = (a["minutes"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: return "ERROR: minutes required (1 to $MAX_MINUTES)."
                val note = (a["note"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (minutes !in 1..MAX_MINUTES) return "ERROR: minutes must be between 1 and $MAX_MINUTES."
                if (note.length < 3) return "ERROR: note required: say what to check when you wake up."
                if (ex.chainOf(ctx.chatId) >= MAX_CHAIN) return "ERROR: this chat already woke itself up $MAX_CHAIN times without a message from the user. Stop scheduling: tell the user the current state and what they should do next."
                val mine = ex.scheduled.value.count { it.chatId == ctx.chatId }
                if (mine >= MAX_PER_CHAT) return "ERROR: $MAX_PER_CHAT wake-ups are already waiting in this chat. Use schedule_cancel first, or wait for one."
                if (ex.scheduled.value.size >= MAX_TOTAL) return "ERROR: too many wake-ups are waiting across chats ($MAX_TOTAL). Tell the user instead."
                val at = System.currentTimeMillis() + minutes * 60_000L
                ex.addScheduled(ScheduledCheck(Ids.id(), ctx.chatId, at, note.take(400)))
                Scheduler.arm(ctx.app)
                "Scheduled: Aura will wake you up in this chat in $minutes minute(s) with your note. Now end your turn with a short message telling the user you will check again then; do not keep working or polling."
            }
            else -> {
                val n = ex.clearScheduledFor(ctx.chatId)
                Scheduler.arm(ctx.app)
                if (n == 0) "No wake-ups were waiting in this chat." else "Cancelled $n wake-up(s)."
            }
        }
    }
}
