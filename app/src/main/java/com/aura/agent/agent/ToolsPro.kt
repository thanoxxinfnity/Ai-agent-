package com.aura.agent.agent

import com.aura.agent.net.*
import kotlinx.serialization.json.*
import java.util.Base64

/** Editing, verification, search and delegation tools. */
object ToolsPro {
    const val MAX_VERIFY = 5
    const val WORKSPACE = "/workspaces/aura-workspace"

    private fun prop(type: String, desc: String) = buildJsonObject { put("type", type); put("description", desc) }
    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

    val defs: List<ToolDef> = listOf(
        ToolDef(
            "edit_file_diff",
            "Surgically change an existing file in a GitHub repo by replacing an exact block of text. ALWAYS prefer this over rewriting a file. `search` must be copied exactly (including indentation) from the current file; read it first with read_lines. If it does not match you get the exact mismatching line back so you can retry. Never write placeholders such as '// ... rest of code'.",
            schema(
                listOf("repo", "path", "search", "replace"),
                "repo" to prop("string", "owner/name or just name"), "path" to prop("string", "File path"),
                "search" to prop("string", "Exact existing text to replace (a few lines, unique in the file)"),
                "replace" to prop("string", "New text"), "replace_all" to prop("boolean", "Replace every occurrence"),
                "branch" to prop("string", "Branch, default is the default branch"), "message" to prop("string", "Commit message"),
            ),
        ),
        ToolDef(
            "cloud_edit_file", "Same exact search and replace, for a file inside the user's codespace.",
            schema(
                listOf("path", "search", "replace"), "path" to prop("string", "Absolute path in the codespace"),
                "search" to prop("string", "Exact existing text"), "replace" to prop("string", "New text"), "replace_all" to prop("boolean", "Replace every occurrence"),
            ),
        ),
        ToolDef(
            "verify_project",
            "Build, typecheck or lint the project in the codespace (npm build / tsc --noEmit / lint, python py_compile, gradle assembleDebug, cargo check, go build). Run it after every code change. On FAIL read the errors, fix them with the edit tools and run it again until it prints PASS (at most 5 attempts).",
            schema(
                emptyList(), "dir" to prop("string", "Project directory in the codespace, default $WORKSPACE"),
                "commands" to buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Optional custom commands instead of auto-detection") },
            ),
        ),
        ToolDef(
            "search_code",
            "Search code without reading whole files. scope=cloud uses ripgrep (regex) in the codespace. scope=repo uses GitHub code search (literal terms) on a repository. Read only the matching lines afterwards with read_lines.",
            schema(
                listOf("pattern"), "scope" to prop("string", "cloud or repo (default repo when a repo is given, else cloud)"),
                "pattern" to prop("string", "Regex (cloud) or search terms (repo)"), "repo" to prop("string", "owner/name, for scope=repo"),
                "path" to prop("string", "Directory (cloud) or path prefix (repo)"), "glob" to prop("string", "File glob such as *.kt (cloud)"),
                "context" to prop("number", "Context lines around each match (cloud), default 1"), "max_results" to prop("number", "Default 40"),
            ),
        ),
        ToolDef(
            "find_files", "List files by glob pattern (for example src/**/*.ts or *.kt).",
            schema(listOf("glob"), "glob" to prop("string", "Glob pattern"), "scope" to prop("string", "cloud or repo"), "repo" to prop("string", "owner/name, for scope=repo"), "path" to prop("string", "Directory (cloud)")),
        ),
        ToolDef(
            "read_lines", "Read only a line range of a file (with line numbers). Use it after search_code to read just the function or imports you need.",
            schema(
                listOf("path", "start_line", "end_line"), "path" to prop("string", "File path"), "scope" to prop("string", "cloud or repo"),
                "repo" to prop("string", "owner/name, for scope=repo"), "start_line" to prop("number", "First line (1-based)"), "end_line" to prop("number", "Last line"),
            ),
        ),
        ToolDef(
            "attachment_to_cloud", "Copy a file the user attached in the chat into the codespace.",
            schema(listOf("name"), "name" to prop("string", "Attachment file name"), "dest" to prop("string", "Destination path in the codespace, default <project>/uploads/<name>")),
        ),
        ToolDef(
            "attachment_to_repo", "Commit a file the user attached in the chat into a GitHub repository.",
            schema(
                listOf("name"), "name" to prop("string", "Attachment file name"), "repo" to prop("string", "owner/name"),
                "path" to prop("string", "Path in the repo, default the file name"), "message" to prop("string", "Commit message"), "branch" to prop("string", "Branch"),
            ),
        ),
        ToolDef(
            "cloud_clone", "Clone any GitHub repository the user can access into the codespace (uses the user's token). Returns the folder.",
            schema(listOf("repo"), "repo" to prop("string", "owner/name"), "dir" to prop("string", "Target folder, default /workspaces/<name>")),
        ),
        ToolDef(
            "delegate",
            "Hand research to read-only worker sub-agents that run in parallel (max 4) and return only a concise summary, which keeps your own context small. Give each worker one focused question, for example 'find where login is implemented and list the functions involved'.",
            schema(listOf("tasks"), "tasks" to buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "One task per worker") }),
        ),
    )

    val NAMES = defs.map { it.name }.toSet()

    /** Tools a worker sub-agent may use. */
    val READ_ONLY = setOf(
        "search_code", "find_files", "read_lines", "github_read_file", "github_list_files", "github_whoami",
        "github_list_repos", "github_list_runs", "github_latest_release", "use_skill",
    )

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
    private fun JsonElement?.s(k: String) = (this as? JsonObject)?.get(k)?.let { (it as? JsonPrimitive)?.contentOrNull }

    private fun decode(b64: String?) = String(Base64.getMimeDecoder().decode(b64.orEmpty()))

    private suspend fun gh(ctx: ToolCtx): Pair<GitHub, String>? {
        val token = ctx.store.githubToken.value
        if (token.isBlank()) return null
        val g = GitHub(token, ctx.app.cloudApi)
        val me = ctx.store.githubUser.value.ifBlank { g.login().also { ctx.store.setGithub(token, it) } }
        return g to me
    }

    suspend fun run(ctx: ToolCtx, name: String, a: JsonObject): String {
        return try {
            when (name) {
                "edit_file_diff" -> editRepo(ctx, a)
                "cloud_edit_file" -> editCloud(ctx, a)
                "verify_project" -> verify(ctx, a)
                "search_code" -> search(ctx, a)
                "find_files" -> find(ctx, a)
                "read_lines" -> readLines(ctx, a)
                "attachment_to_cloud" -> attachToCloud(ctx, a)
                "attachment_to_repo" -> attachToRepo(ctx, a)
                "cloud_clone" -> {
                    val c = ctx.cloud.client()
                    val repo = a.s("repo") ?: return "ERROR: repo required"
                    val dir = a.s("dir") ?: "/workspaces/" + repo.substringAfter('/')
                    ctx.emit("$ gh repo clone $repo $dir\n")
                    val r = c.execStream("if [ -d ${Sh.q(dir)}/.git ]; then echo already cloned; git -C ${Sh.q(dir)} pull --ff-only -q || true; else gh repo clone ${Sh.q(repo)} ${Sh.q(dir)}; fi", 300, null, emptyMap(), ctx.emit)
                    if (r.exit == 0) "Cloned $repo into $dir" else "ERROR: clone failed\n" + r.render()
                }
                "delegate" -> {
                    val tasks = (a["tasks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    if (tasks.isEmpty()) "ERROR: tasks required"
                    else ctx.subAgents?.invoke(tasks) ?: "ERROR: sub-agents are not available here"
                }
                else -> "ERROR: unknown tool $name"
            }
        } catch (e: NoCodespaceToken) {
            NO_TOKEN
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: GhError) {
            "ERROR: ${e.message}"
        } catch (e: Exception) {
            "ERROR: ${e.message ?: e.javaClass.simpleName}"
        }.take(9000)
    }

    const val NO_TOKEN = "NO_CODESPACE_TOKEN: the user has not added a GitHub Codespace token. Politely ask them to open Settings > GitHub Codespace Token (PAT) and add one, then try again."

    private suspend fun editRepo(ctx: ToolCtx, a: JsonObject): String {
        val (g, me) = gh(ctx) ?: return "ERROR: No GitHub token. Ask the user to sign in first."
        val repo = (a.s("repo") ?: return "ERROR: repo required").let { if ("/" in it) it else "$me/$it" }
        val path = a.s("path") ?: return "ERROR: path required"
        val branch = a.s("branch") ?: g.call("GET", "/repos/$repo").s("default_branch")!!
        val cur = try {
            g.call("GET", "/repos/$repo/contents/$path?ref=$branch") as JsonObject
        } catch (e: GhError) {
            if (e.code == 404) return "ERROR: $path does not exist on $branch. Use github_write_files to create new files."
            throw e
        }
        val text = decode(cur.s("content"))
        val r = Patch.apply(text, a.s("search").orEmpty(), a.s("replace").orEmpty(), a.b("replace_all") == true)
        val ok = r as? PatchResult.Ok ?: return "ERROR: edit_file_diff failed - ${(r as PatchResult.Fail).message}"
        val head = g.call("GET", "/repos/$repo/git/ref/heads/$branch").let { (it as JsonObject)["object"].s("sha")!! }
        Checkpoints(ctx.app).recordGithub(ctx, repo, branch, head, "edit $path")
        g.call("PUT", "/repos/$repo/contents/$path", buildJsonObject {
            put("message", a.s("message") ?: "Edit $path"); put("branch", branch); put("sha", cur.s("sha")!!)
            put("content", Base64.getEncoder().encodeToString(ok.text.toByteArray()))
        })
        ctx.state.markModified()
        val delta = ok.text.lines().size - text.lines().size
        return "Edited $path on $repo@$branch: ${ok.count} replacement(s), ${if (delta >= 0) "+" else ""}$delta lines. Only the matched text changed."
    }

    private suspend fun editCloud(ctx: ToolCtx, a: JsonObject): String {
        val path = a.s("path") ?: return "ERROR: path required"
        val c = ctx.cloud.client()
        val text = String(c.download(path))
        val r = Patch.apply(text, a.s("search").orEmpty(), a.s("replace").orEmpty(), a.b("replace_all") == true)
        val ok = r as? PatchResult.Ok ?: return "ERROR: cloud_edit_file failed - ${(r as PatchResult.Fail).message}"
        Checkpoints(ctx.app).recordCloud(ctx, c, path.substringBeforeLast('/', WORKSPACE), "edit $path")
        c.upload(path, ok.text.toByteArray())
        ctx.state.markModified()
        return "Edited $path: ${ok.count} replacement(s). Only the matched text changed."
    }

    private suspend fun findAttachment(ctx: ToolCtx, name: String): com.aura.agent.data.Attachment? =
        ctx.app.db.dao().messagesOnce(ctx.chatId).asReversed().flatMap { com.aura.agent.data.Attachments.parse(it.attachments) }
            .firstOrNull { it.name.equals(name, true) || java.io.File(it.path).name.equals(name, true) }

    private suspend fun attachToCloud(ctx: ToolCtx, a: JsonObject): String {
        val att = findAttachment(ctx, a.s("name") ?: return "ERROR: name required") ?: return "ERROR: no attachment with that name in this chat."
        val c = ctx.cloud.client()
        val dest = a.s("dest") ?: (ctx.workDir(c) + "/uploads/" + att.name)
        Checkpoints(ctx.app).recordCloud(ctx, c, dest.substringBeforeLast('/', ToolsPro.WORKSPACE), "attachment ${att.name}")
        c.upload(dest, java.io.File(att.path).readBytes())
        ctx.state.markModified()
        return "Copied ${att.name} (${att.size} bytes) to $dest in the codespace."
    }

    private suspend fun attachToRepo(ctx: ToolCtx, a: JsonObject): String {
        val att = findAttachment(ctx, a.s("name") ?: return "ERROR: name required") ?: return "ERROR: no attachment with that name in this chat."
        val (g, me) = gh(ctx) ?: return "ERROR: No GitHub token. Ask the user to sign in first."
        val repo = (a.s("repo") ?: return "ERROR: repo required").let { if ("/" in it) it else "$me/$it" }
        val path = a.s("path") ?: att.name
        val branch = a.s("branch") ?: g.call("GET", "/repos/$repo").s("default_branch")!!
        val sha = try { (g.call("GET", "/repos/$repo/contents/$path?ref=$branch") as JsonObject).s("sha") } catch (e: GhError) { if (e.code == 404) null else throw e }
        val head = (g.call("GET", "/repos/$repo/git/ref/heads/$branch") as JsonObject)["object"].s("sha")!!
        Checkpoints(ctx.app).recordGithub(ctx, repo, branch, head, "add ${att.name}")
        g.call("PUT", "/repos/$repo/contents/$path", buildJsonObject {
            put("message", a.s("message") ?: "Add ${att.name}"); put("branch", branch)
            put("content", Base64.getEncoder().encodeToString(java.io.File(att.path).readBytes())); sha?.let { put("sha", it) }
        })
        ctx.state.markModified()
        return "Committed ${att.name} to $repo@$branch as $path."
    }

    private suspend fun verify(ctx: ToolCtx, a: JsonObject): String {
        val c = ctx.cloud.client()
        val cmds = (a["commands"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (ctx.state.verifyFailures >= MAX_VERIFY) {
            return "MAX_RETRIES_REACHED: verification already failed $MAX_VERIFY times. Stop retrying; explain the remaining error to the user and ask how to proceed."
        }
        ctx.emit("$ verify_project\n")
        val r = c.execStream(VerifyScript.SCRIPT, 1500, null, mapOf("DIR" to (a.s("dir") ?: ctx.workDir(c)), "CMDS" to cmds.joinToString("\n")), ctx.emit)
        val out = r.stdout
        return when {
            out.contains("VERIFY_RESULT: PASS") -> { ctx.state.verified = true; ctx.state.verifyFailures = 0; "VERIFY PASS. The build is clean.\n" + out.takeLast(1200) }
            out.contains("NO_CHECKS_FOUND") -> out.takeLast(600)
            else -> {
                ctx.state.verifyFailures++
                val n = ctx.state.verifyFailures
                "VERIFY FAIL (attempt $n of $MAX_VERIFY)\n" + out.takeLast(5000) +
                    (if (n >= MAX_VERIFY) "\nMAX_RETRIES_REACHED: stop and report the remaining error to the user." else "\nRead the errors above, fix them with edit_file_diff or cloud_edit_file, then call verify_project again.")
            }
        }
    }

    private fun scopeOf(a: JsonObject) = a.s("scope") ?: if (a.s("repo") != null) "repo" else "cloud"

    private suspend fun search(ctx: ToolCtx, a: JsonObject): String {
        val pattern = a.s("pattern") ?: return "ERROR: pattern required"
        val max = (a.n("max_results") ?: 40).toInt().coerceIn(1, 200)
        if (scopeOf(a) == "repo") {
            val (g, me) = gh(ctx) ?: return "ERROR: No GitHub token. Ask the user to sign in first."
            val repo = (a.s("repo") ?: return "ERROR: repo required").let { if ("/" in it) it else "$me/$it" }
            val q = java.net.URLEncoder.encode("$pattern repo:$repo" + (a.s("path")?.let { " path:$it" } ?: ""), "UTF-8")
            val r = g.call("GET", "/search/code?q=$q&per_page=${max.coerceAtMost(30)}", accept = "application/vnd.github.text-match+json") as JsonObject
            val items = (r["items"] as? JsonArray).orEmpty()
            if (items.isEmpty()) return "No matches. (GitHub code search is literal and covers only the default branch.)"
            return items.joinToString("\n") { it ->
                val frag = ((it as JsonObject)["text_matches"] as? JsonArray)?.firstOrNull().s("fragment")?.replace("\n", " ")?.take(160).orEmpty()
                "${it.s("path")}: $frag"
            }
        }
        val c = ctx.cloud.client()
        val ctxLines = (a.n("context") ?: 1).toInt().coerceIn(0, 5)
        val dir = a.s("path") ?: ctx.workDir(c)
        val glob = a.s("glob")?.let { " -g ${Sh.q(it)}" }.orEmpty()
        val cmd = "if command -v rg >/dev/null; then rg -n --no-heading -C $ctxLines$glob -e ${Sh.q(pattern)} ${Sh.q(dir)}; " +
            "else grep -rn -C $ctxLines ${a.s("glob")?.let { "--include=${Sh.q(it)} " }.orEmpty()}-e ${Sh.q(pattern)} ${Sh.q(dir)}; fi | head -n $max"
        val r = c.exec(cmd, 60)
        return r.stdout.ifBlank { "No matches." }
    }

    private suspend fun find(ctx: ToolCtx, a: JsonObject): String {
        val glob = a.s("glob") ?: return "ERROR: glob required"
        if (scopeOf(a) == "repo") {
            val (g, me) = gh(ctx) ?: return "ERROR: No GitHub token. Ask the user to sign in first."
            val repo = (a.s("repo") ?: return "ERROR: repo required").let { if ("/" in it) it else "$me/$it" }
            val br = g.call("GET", "/repos/$repo").s("default_branch")!!
            val tree = (g.call("GET", "/repos/$repo/git/trees/$br?recursive=1") as JsonObject)["tree"] as JsonArray
            val re = Glob.toRegex(glob)
            val hits = tree.filter { it.s("type") == "blob" && re.matches(it.s("path").orEmpty()) }.mapNotNull { it.s("path") }
            return if (hits.isEmpty()) "No files match." else hits.take(200).joinToString("\n") + if (hits.size > 200) "\n... ${hits.size - 200} more" else ""
        }
        val c = ctx.cloud.client()
        val dir = a.s("path") ?: ctx.workDir(c)
        val cmd = "(command -v rg >/dev/null && rg --files -g ${Sh.q(glob)} ${Sh.q(dir)} || find ${Sh.q(dir)} -type f -name ${Sh.q(glob.substringAfterLast('/'))} -not -path '*/node_modules/*' -not -path '*/.git/*') | head -n 200"
        return c.exec(cmd, 60).stdout.ifBlank { "No files match." }
    }

    private suspend fun readLines(ctx: ToolCtx, a: JsonObject): String {
        val path = a.s("path") ?: return "ERROR: path required"
        val start = (a.n("start_line") ?: 1).toInt().coerceAtLeast(1)
        val end = (a.n("end_line") ?: (start + 80L)).toInt().coerceAtLeast(start).coerceAtMost(start + 399)
        if (scopeOf(a) == "repo") {
            val (g, me) = gh(ctx) ?: return "ERROR: No GitHub token. Ask the user to sign in first."
            val repo = (a.s("repo") ?: return "ERROR: repo required").let { if ("/" in it) it else "$me/$it" }
            val text = decode((g.call("GET", "/repos/$repo/contents/$path") as JsonObject).s("content"))
            return numbered(text, start, end)
        }
        val c = ctx.cloud.client()
        val r = c.exec("awk 'NR>=$start && NR<=$end {printf \"%d: %s\\n\", NR, $0}' ${Sh.q(path)}", 30)
        return r.stdout.ifBlank { if (r.exit != 0) "ERROR: ${r.stderr.ifBlank { "cannot read $path" }}" else "(no lines in that range)" }
    }

    fun numbered(text: String, start: Int, end: Int): String {
        val lines = text.split("\n")
        if (start > lines.size) return "(file has only ${lines.size} lines)"
        return lines.subList(start - 1, minOf(end, lines.size)).mapIndexed { i, l -> "${start + i}: $l" }.joinToString("\n")
    }
}
