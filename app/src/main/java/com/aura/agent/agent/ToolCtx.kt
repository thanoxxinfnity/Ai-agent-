package com.aura.agent.agent

import com.aura.agent.App
import com.aura.agent.data.Store
import java.io.File

/** What the agent changed and checked during one run; drives the auto-verification loop. */
class RunState {
    @Volatile var modified = false
    @Volatile var verified = false
    @Volatile var verifyFailures = 0
    @Volatile var nudges = 0
    val snapshotted = java.util.Collections.synchronizedSet(HashSet<String>())

    fun markModified() { modified = true; verified = false }
}

/** Everything a tool needs for one call. */
class ToolCtx(
    val app: App,
    val chatId: String,
    /** id of the user message that started this run; checkpoints are grouped by it */
    val turnId: String,
    val cloud: CloudSession,
    val outDir: File,
    val state: RunState = RunState(),
    /** live output (terminal block in the UI) */
    val emit: (String) -> Unit = {},
    /** asks the user; returns true when approved */
    val approve: suspend (title: String, detail: String) -> Boolean = { _, _ -> true },
    /** read-only worker sub-agents may not modify anything or ask the user */
    val readOnly: Boolean = false,
    val subAgents: (suspend (List<String>) -> String)? = null,
) {
    val store: Store get() = app.store
}
