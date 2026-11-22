package com.aura.agent.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

class ExecResult(val exit: Int, val stdout: String, val stderr: String) {
    fun render(): String = buildString {
        if (stdout.isNotBlank()) append(stdout.trimEnd()).append('\n')
        if (stderr.isNotBlank()) append("[stderr]\n").append(stderr.trimEnd()).append('\n')
        append("[exit ").append(exit).append(']')
    }
}

/** Talks to the exec server inside a codespace through its private forwarded port. GitHub authenticates the PAT header. */
class CloudClient(private val base: String, private val pat: String) {
    private fun req(path: String) = Request.Builder().url(base.trimEnd('/') + path).header("X-Github-Token", pat)

    private fun client(readSeconds: Long) = Http.client.newBuilder().readTimeout(readSeconds, TimeUnit.SECONDS).build()

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client(15).newCall(req("/health").get().build()).execute().use { it.isSuccessful && it.body?.string().orEmpty().contains("ok") }
        }.getOrDefault(false)
    }

    private suspend fun json(path: String, body: JsonObject, readSeconds: Long): JsonElement = withContext(Dispatchers.IO) {
        val r = req(path).post(body.toString().toRequestBody(Http.JSON)).build()
        client(readSeconds).newCall(r).execute().use { resp ->
            val t = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmError("Cloud ${resp.code}: ${t.take(300)}")
            AppJsonLenient.parseToJsonElement(t)
        }
    }

    private fun mask(t: String) = if (pat.length >= 8) t.replace(pat, "***") else t

    /** The user's own Codespace PAT is passed as GH_TOKEN so `gh` and git inside the codespace act as that user. */
    private fun body(cmd: String, timeoutSec: Int, cwd: String?, env: Map<String, String>) = buildJsonObject {
        put("cmd", cmd); put("timeout", timeoutSec); cwd?.let { put("cwd", it) }
        putJsonObject("env") { put("GH_TOKEN", pat); put("GITHUB_TOKEN", pat); env.forEach { (k, v) -> put(k, v) } }
    }

    suspend fun exec(cmd: String, timeoutSec: Int = 300, cwd: String? = null, env: Map<String, String> = emptyMap()): ExecResult {
        val j = json("/exec", body(cmd, timeoutSec, cwd, env), timeoutSec + 30L).jsonObject
        return ExecResult(
            j["exit"]?.jsonPrimitive?.intOrNull ?: -1, mask(j["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            mask(j["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty()),
        )
    }

    /** Runs a command and reports output as it is produced. Falls back to [exec] against an older server. */
    suspend fun execStream(cmd: String, timeoutSec: Int = 300, cwd: String? = null, env: Map<String, String> = emptyMap(), onChunk: (String) -> Unit): ExecResult =
        withContext(Dispatchers.IO) {
            val req = req("/exec_stream").post(body(cmd, timeoutSec, cwd, env).toString().toRequestBody(Http.JSON)).build()
            val call = client(timeoutSec + 30L).newCall(req)
            val handle = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { resp ->
                    if (resp.code == 404) {
                        val r = exec(cmd, timeoutSec, cwd, env)
                        onChunk(r.render()); return@withContext r
                    }
                    if (!resp.isSuccessful) throw LlmError("Cloud ${resp.code}: ${resp.body?.string().orEmpty().take(300)}")
                    val full = StringBuilder()
                    var pending = StringBuilder()
                    var exit = -1
                    val src = resp.body!!.source()
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        val o = runCatching { AppJsonLenient.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                        o["o"]?.jsonPrimitive?.contentOrNull?.let { chunk ->
                            // mask first, then hold back only a tail that could still be the start of the token
                            val masked = mask(pending.toString() + chunk)
                            var keep = 0
                            for (k in minOf(masked.length, pat.length - 1) downTo 1) {
                                if (pat.startsWith(masked.substring(masked.length - k))) { keep = k; break }
                            }
                            val safe = masked.substring(0, masked.length - keep)
                            pending = StringBuilder(masked.substring(masked.length - keep))
                            if (safe.isNotEmpty()) { full.append(safe); onChunk(safe) }
                        }
                        o["exit"]?.jsonPrimitive?.intOrNull?.let { exit = it }
                    }
                    if (pending.isNotEmpty()) { val m = mask(pending.toString()); full.append(m); onChunk(m) }
                    ExecResult(exit, full.toString(), "")
                }
            } finally { handle.dispose() }
        }

    suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(path, "UTF-8")
        client(120).newCall(req("/file?path=$enc").get().build()).execute().use { r ->
            if (!r.isSuccessful) throw LlmError("Download failed (${r.code}): ${r.body?.string().orEmpty().take(200)}")
            r.body!!.bytes()
        }
    }

    suspend fun upload(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(path, "UTF-8")
        client(120).newCall(req("/file?path=$enc").put(bytes.toRequestBody()).build()).execute().use { r ->
            if (!r.isSuccessful) throw LlmError("Upload failed (${r.code}): ${r.body?.string().orEmpty().take(200)}")
        }
    }

    suspend fun mcpTools(): String = withContext(Dispatchers.IO) {
        client(330).newCall(req("/mcp/tools").get().build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw LlmError("MCP ${r.code}: ${t.take(300)}")
            (AppJsonLenient.parseToJsonElement(t) as JsonArray).joinToString("\n") {
                "- ${it.jsonObject["name"]?.jsonPrimitive?.content}: ${it.jsonObject["description"]?.jsonPrimitive?.content} schema=${it.jsonObject["inputSchema"]}"
            }.take(7000)
        }
    }

    suspend fun mcpCall(name: String, args: JsonObject): String =
        json("/mcp/call", buildJsonObject { put("name", name); put("arguments", args) }, 200).jsonObject["result"]?.jsonPrimitive?.contentOrNull.orEmpty()
}

/** Codespace lifecycle through the official REST API (the phone cannot run the gh CLI or ssh). */
class Codespaces(
    private val pat: String,
    private val api: String = "https://api.github.com",
    private val portUrl: (String, Int) -> String = { n, p -> "https://$n-$p.app.github.dev" },
    private val pollMs: Long = 5000,
) {
    private val gh = GitHub(pat, api)

    companion object {
        const val REPO = "aura-workspace"
        const val EXEC_PORT = 8787

        /** Verifies a classic PAT: valid, has the codespace scope, and can call GET /user/codespaces. Returns the login. */
        suspend fun verify(pat: String, api: String = "https://api.github.com"): String {
            val gh = GitHub(pat, api)
            val (login, scopes) = gh.userWithScopes()
            if (scopes != null) {
                val have = scopes.split(",").map { it.trim() }
                if ("codespace" !in have) throw GhError(403, "This token is valid but has no 'codespace' permission. Create a classic token with the codespace and repo scopes.")
            }
            try {
                gh.call("GET", "/user/codespaces?per_page=1")
            } catch (e: GhError) {
                if (e.code == 401) throw GhError(401, "Bad credentials: GitHub rejected this token.")
                throw GhError(e.code, "This token cannot use Codespaces (${e.message}). Give it the codespace and repo scopes.")
            }
            return login
        }
    }

    suspend fun login() = gh.login()

    /** Creates the private workspace repo and keeps its devcontainer files current. Returns "owner/aura-workspace". */
    suspend fun ensureWorkspace(files: Map<String, String>): String {
        val full = "${gh.login()}/$REPO"
        try {
            gh.call("GET", "/repos/$full")
        } catch (e: GhError) {
            if (e.code != 404) throw e
            gh.call("POST", "/user/repos", buildJsonObject {
                put("name", REPO); put("description", "Aura cloud workspace"); put("private", true); put("auto_init", true)
            })
            delay(pollMs.coerceAtMost(2000))
        }
        for ((path, content) in files) {
            var sha: String? = null
            var same = false
            try {
                val cur = gh.call("GET", "/repos/$full/contents/$path").jsonObject
                sha = cur["sha"]?.jsonPrimitive?.contentOrNull
                same = String(Base64.getMimeDecoder().decode(cur["content"]?.jsonPrimitive?.contentOrNull.orEmpty())) == content
            } catch (e: GhError) {
                if (e.code != 404) throw e
            }
            if (!same) gh.call("PUT", "/repos/$full/contents/$path", buildJsonObject {
                put("message", "Aura: update $path")
                put("content", Base64.getEncoder().encodeToString(content.toByteArray()))
                sha?.let { put("sha", it) }
            })
        }
        return full
    }

    private suspend fun state(name: String) = gh.call("GET", "/user/codespaces/$name").jsonObject["state"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private suspend fun waitFor(name: String, want: String, maxMs: Long) {
        var waited = 0L
        while (true) {
            val s = state(name)
            if (s == want) return
            if (s == "Failed" || s == "Deleted") throw LlmError("Codespace $name is $s")
            if (waited >= maxMs) throw LlmError("Codespace $name did not reach $want in time (state $s)")
            delay(pollMs); waited += pollMs
        }
    }

    /** Equivalent of: gh codespace list, then start the stopped one or create a new one. Returns the codespace name. */
    suspend fun ensureRunning(repoFull: String, maxMs: Long = 480_000): String {
        val list = (gh.call("GET", "/user/codespaces?per_page=100").jsonObject["codespaces"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .filter { it["repository"]?.jsonObject?.get("full_name")?.jsonPrimitive?.contentOrNull.equals(repoFull, true) }
        val cs = list.firstOrNull { it["state"]?.jsonPrimitive?.contentOrNull == "Available" }
            ?: list.firstOrNull { it["state"]?.jsonPrimitive?.contentOrNull in setOf("Shutdown", "ShuttingDown", "Starting", "Queued", "Provisioning") }
        if (cs != null) {
            val name = cs["name"]!!.jsonPrimitive.content
            when (cs["state"]!!.jsonPrimitive.content) {
                "Available" -> {}
                "Shutdown" -> gh.call("POST", "/user/codespaces/$name/start")
                "ShuttingDown" -> { waitFor(name, "Shutdown", maxMs); gh.call("POST", "/user/codespaces/$name/start") }
            }
            waitFor(name, "Available", maxMs)
            return name
        }
        val created = gh.call("POST", "/repos/$repoFull/codespaces", buildJsonObject {
            put("display_name", "aura-workspace"); put("idle_timeout_minutes", 10)
        }).jsonObject
        val name = created["name"]!!.jsonPrimitive.content
        waitFor(name, "Available", maxMs)
        return name
    }

    /** Equivalent of: gh codespace stop -c NAME. */
    suspend fun stop(name: String) {
        gh.call("POST", "/user/codespaces/$name/stop")
    }

    fun client(name: String) = CloudClient(portUrl(name, EXEC_PORT), pat)

    suspend fun waitHealthy(c: CloudClient, maxMs: Long = 240_000) {
        var waited = 0L
        while (!c.health()) {
            if (waited >= maxMs) throw LlmError("The codespace is running but its exec server did not answer. Check the port 8787 forwarding.")
            delay(pollMs); waited += pollMs
        }
    }
}
