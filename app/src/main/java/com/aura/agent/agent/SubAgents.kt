package com.aura.agent.agent

import com.aura.agent.App
import com.aura.agent.net.Llm
import com.aura.agent.net.LlmMsg
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Lightweight read-only research workers. Their transcripts are thrown away; only a concise summary returns. */
class SubAgents(private val app: App) {
    private val defs get() = Tools.defs.filter { it.name in ToolsPro.READ_ONLY }

    suspend fun run(tasks: List<String>, parent: ToolCtx): String = coroutineScope {
        tasks.take(MAX_WORKERS).mapIndexed { i, t ->
            async { "### Worker ${i + 1}: ${t.take(80)}\n" + runOne(t, parent) }
        }.awaitAll().joinToString("\n\n")
    }

    private suspend fun runOne(task: String, parent: ToolCtx): String {
        val (provider, model) = app.store.current() ?: return "ERROR: no model selected"
        val ctx = ToolCtx(app, parent.chatId, parent.turnId, parent.cloud, parent.outDir, RunState(), readOnly = true, approve = { _, _ -> false })
        val msgs = ArrayList<LlmMsg>().apply { add(LlmMsg("user", task)) }
        var lastText = ""
        repeat(MAX_STEPS) {
            val sb = StringBuilder()
            val calls = Llm.stream(provider, model, WORKER_SYSTEM, msgs, defs) { sb.append(it) }
            lastText = sb.toString()
            if (calls.isEmpty()) return lastText.trim().take(1800).ifBlank { "(worker returned nothing)" }
            msgs += LlmMsg("assistant", lastText, calls)
            for (c in calls) msgs += LlmMsg("tool", Tools.execute(ctx, c.name, c.args).take(3500), toolCallId = c.id, toolName = c.name)
        }
        return ("Worker ran out of steps. Last notes: " + lastText.trim()).take(1800)
    }

    companion object {
        const val MAX_WORKERS = 4
        const val MAX_STEPS = 10
        const val WORKER_SYSTEM = "You are a read-only research worker for a coding agent. Use search_code and find_files first, then read_lines for only the relevant lines. " +
            "You cannot change anything. Finish with a concise summary (under 250 words) listing file paths, line numbers and what matters for the task. No emoji."
    }
}
