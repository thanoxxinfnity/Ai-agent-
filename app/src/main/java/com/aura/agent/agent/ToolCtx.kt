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
    val callCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile var repoDir: String? = null
    /** (call + result) -> how many times it happened: identical results mean no progress */
    val sameResult = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile var errorStreak = 0
    /** consecutive failures per tool: a tool that keeps failing is switched off for the rest of the turn */
    val toolFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile var ciFailures = 0
    @Volatile var deployFailures = 0
    /** the user already said yes (or no) to GitHub pushes during this run, so they are not asked again */
    @Volatile var pushAllowed = false
    @Volatile var pushDenied = false
    val snapshotted = java.util.Collections.synchronizedSet(HashSet<String>())

    fun markModified() { modified = true; verified = false }
}

/** What the model asks for in the secure key box. */
class SecretAsk(val name: String, val baseUrl: String, val style: String, val param: String, val reason: String)

/** What the user entered. The key stays inside the app. */
class SecretReply(val name: String, val baseUrl: String, val key: String, val style: String, val param: String)

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
    /** shows the secure key box and returns what the user typed (null when they decline); the model never receives the key itself */
    val askSecret: suspend (SecretAsk) -> SecretReply? = { null },
    /** read-only worker sub-agents may not modify anything or ask the user */
    val readOnly: Boolean = false,
    val subAgents: (suspend (List<String>) -> String)? = null,
    /** owner/name chosen when the chat started; tools use it when no repo is given */
    val activeRepo: String = "",
    /** other repositories attached to the chat */
    val extraRepos: List<String> = emptyList(),
    val mode: ThinkMode = ThinkMode.STEADY,
) {
    /** files this call created (generated images, downloads); they appear on the tool step in the chat */
    val produced = java.util.concurrent.CopyOnWriteArrayList<com.aura.agent.data.Attachment>()

    val store: Store get() = app.store

    /**
     * GitHub only receives what the user agreed to. Returns null when the push may go ahead, or the message to give the model
     * (which tells it to keep the work on the phone instead).
     */
    suspend fun confirmGithubPush(what: String, detail: String = ""): String? {
        if (!app.extras.askBeforePush.value || state.pushAllowed) return null
        if (state.pushDenied) return PUSH_DENIED
        if (!approve("Push to GitHub?", (what + "\n" + detail).trim() + "\n\nAllow to put this on GitHub, or deny to keep everything on your phone.")) {
            state.pushDenied = true
            return PUSH_DENIED
        }
        state.pushAllowed = true
        return null
    }

    companion object {
        const val PUSH_DENIED = "DENIED: the user does not want this on GitHub, so nothing was pushed. Do not try to push again in this chat unless they ask. " +
            "Keep the work on the phone instead: send_file shows a file in the chat with preview and download, and folder_write saves into a connected phone folder. " +
            "Tell the user where the files are, in their own language."
    }

    /** Working directory in the codespace: the active repo (cloned on first use) or the workspace root. */
    suspend fun workDir(c: com.aura.agent.net.CloudClient): String {
        state.repoDir?.let { return it }
        if (activeRepo.isBlank()) return ToolsPro.WORKSPACE.also { state.repoDir = it }
        val dir = "/workspaces/" + activeRepo.substringAfter('/')
        emit("Cloning $activeRepo into the codespace...\n")
        val r = c.exec("if [ -d ${Sh.q(dir)}/.git ]; then git -C ${Sh.q(dir)} pull --ff-only -q || true; else gh repo clone ${Sh.q(activeRepo)} ${Sh.q(dir)}; fi", 300)
        state.repoDir = if (r.exit == 0) dir else { emit("Could not clone: ${r.render().take(300)}\n"); ToolsPro.WORKSPACE }
        return state.repoDir!!
    }
}
