package com.aura.agent.agent

import com.aura.agent.data.Store
import com.aura.agent.net.CloudClient
import com.aura.agent.net.Codespaces
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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

    suspend fun client(): CloudClient {
        client?.let { return it }
        val pat = patProvider()?.takeIf { it.isNotBlank() } ?: throw NoCodespaceToken()
        val c = Codespaces(pat, api, portUrl, pollMs)
        cs = c
        val repo = c.ensureWorkspace(workspaceFiles())
        val n = c.ensureRunning(repo)
        name = n
        store.activeCodespace = n   // lets the next app start stop it if this process dies
        val cl = c.client(n)
        c.waitHealthy(cl)
        client = cl
        return cl
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
        /** Used at app start to stop a codespace left running by a process that died. */
        suspend fun recover(store: Store, patProvider: suspend () -> String?, api: String = "https://api.github.com") {
            val n = store.activeCodespace.takeIf { it.isNotBlank() } ?: return
            val pat = patProvider()?.takeIf { it.isNotBlank() } ?: return
            if (runCatching { Codespaces(pat, api).stop(n) }.isSuccess) store.activeCodespace = ""
        }
    }
}
