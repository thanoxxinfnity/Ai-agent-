package com.aura.agent.agent

import com.aura.agent.data.Store
import com.aura.agent.net.CloudClient
import com.aura.agent.net.Codespaces
import com.aura.agent.net.LlmError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NoCodespaceToken : Exception("NO_CODESPACE_TOKEN")

/**
 * One cloud session per agent run. The user's own codespace is started lazily on the first cloud tool call and is
 * always stopped in [stop], which the runner calls from a finally block (error, crash or user stop).
 */
class CloudSession(
    private val store: Store,
    private val patProvider: suspend () -> String?,
    private val workspaceFiles: () -> Map<String, String>,
    private val api: String = "https://api.github.com",
    private val portUrl: (String, Int) -> String = { n, p -> "https://$n-$p.app.github.dev" },
    private val pollMs: Long = 5000,
) {
    private var cs: Codespaces? = null
    private var name: String? = null
    private var client: CloudClient? = null
    var browserReady = false

    val isOpen get() = name != null

    private val mutex = Mutex()

    private var failedAt = 0L
    private var failedWhy = ""

    suspend fun client(): CloudClient = mutex.withLock {
        client?.let { return@withLock it }
        // a start that just failed is not repeated for a while: every try can take minutes, and the agent would loop on it
        if (failedAt > 0 && System.currentTimeMillis() - failedAt < failureCooldownMs) {
            throw LlmError("The codespace could not be started a moment ago ($failedWhy). Not trying again right now. Do not use cloud_exec or other codespace tools: work with the GitHub tools or the phone workspace, or tell the user what blocks you.")
        }
        val pat = patProvider()?.takeIf { it.isNotBlank() } ?: throw NoCodespaceToken()
        try {
            val c = Codespaces(pat, api, portUrl, pollMs)
            cs = c
            val repo = c.ensureWorkspace(workspaceFiles())
            val n = c.ensureRunning(repo)
            name = n
            store.activeCodespace = n   // lets the next app start stop it if this process dies
            val cl = c.client(n)
            c.waitHealthy(cl)
            client = cl
            failedAt = 0
            cl
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt = System.currentTimeMillis(); failedWhy = (e.message ?: e.javaClass.simpleName).take(160)
            throw e
        }
    }

    /** Idempotent. Retries because a failed stop would waste the user's free core-hours. */
    suspend fun stop() = withContext(NonCancellable) {
        val c = cs ?: return@withContext
        val n = name ?: return@withContext
        var ok = false
        repeat(3) { i ->
            if (!ok) {
                ok = runCatching { c.stop(n) }.isSuccess
                if (!ok && i < 2) delay(2000)
            }
        }
        if (ok) { store.activeCodespace = ""; name = null; client = null }
    }

    companion object {
        /** how long a failed codespace start is remembered (0 = always try again) */
        @Volatile var failureCooldownMs = 5 * 60_000L

        /** Used at app start to stop a codespace left running by a process that died. */
        suspend fun recover(store: Store, patProvider: suspend () -> String?, api: String = "https://api.github.com") {
            val n = store.activeCodespace.takeIf { it.isNotBlank() } ?: return
            val pat = patProvider()?.takeIf { it.isNotBlank() } ?: return
            if (runCatching { Codespaces(pat, api).stop(n) }.isSuccess) store.activeCodespace = ""
        }
    }
}
