package com.aura.agent.agent

import com.aura.agent.data.Store
import com.aura.agent.net.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.util.Base64

object Tools {
    private fun prop(type: String, desc: String) = buildJsonObject { put("type", type); put("description", desc) }
    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

    private val fileItem = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("path", prop("string", "File path inside the repo, e.g. app/build.gradle.kts"))
            put("content", prop("string", "Full file content"))
            put("encoding", prop("string", "utf-8 (default) or base64"))
            put("executable", prop("boolean", "Set true for scripts such as gradlew"))
            put("overwrite", prop("boolean", "Only for deliberately replacing a whole existing file. Otherwise use edit_file_diff"))
        }
        putJsonArray("required") { add("path"); add("content") }
    }

    val defs: List<ToolDef> by lazy { (baseDefs + ToolsPro.defs + ToolsVercel.defs + ToolsMemory.defs + ToolsLive.defs + ToolsMcp.defs + ToolsFolders.defs + ToolsVault.defs + ToolsFiles.defs).map(::relaxRepo) }

    /** `repo` stops being required: it defaults to the repository chosen when the chat started. */
    private fun relaxRepo(t: ToolDef): ToolDef {
        val req = t.params["required"] as? JsonArray ?: return t
        if (req.none { (it as? JsonPrimitive)?.contentOrNull == "repo" }) return t
        return ToolDef(
            t.name, t.description + " The repo argument is optional: it defaults to the chat's active repository.",
            JsonObject(t.params + ("required" to JsonArray(req.filter { (it as? JsonPrimitive)?.contentOrNull != "repo" }))),
        )
    }

    private val REPO_TOOLS = setOf(
        "github_write_files", "github_read_file", "github_list_files", "github_run_workflow", "github_list_runs", "github_wait_run",
        "github_run_logs", "github_latest_release", "edit_file_diff", "attachment_to_repo",
    )

    private val baseDefs: List<ToolDef> = listOf(
        ToolDef(
            "use_skill", "Load the full instructions of one of the enabled skills listed in the system prompt. Call it before starting work that matches a skill.",
            schema(listOf("name"), "name" to prop("string", "Skill name")),
        ),
        ToolDef(
            "create_skill", "Save a reusable skill (a named set of instructions) so it is available in future chats.",
            schema(
                listOf("name", "description", "instructions"),
                "name" to prop("string", "Short slug like release-notes"),
                "description" to prop("string", "One sentence: when to use it"),
                "instructions" to prop("string", "Step by step instructions in Markdown"),
            ),
        ),
        ToolDef(
            "cloud_exec",
            "Run a bash command in the user's own GitHub Codespace, a real Linux terminal (Node, Python, Java 17, git, curl installed; you may install more with apt-get or pip). Use it for builds, tests, installs, scripts, servers, git and any shell work. Every call is a fresh shell (cd and exports do not persist), so pass cwd or chain with &&. Output streams live and the result shows the exit code. Run servers in the background with nohup ... &. The codespace starts on first use and is stopped automatically when the task ends.",
            schema(
                listOf("command"),
                "command" to prop("string", "Bash command"),
                "timeout_seconds" to prop("number", "Max seconds, default 300"),
                "cwd" to prop("string", "Working directory, default is the active repository or /workspaces/aura-workspace"),
            ),
        ),
        ToolDef(
            "cloud_upload", "Write a text file inside the codespace.",
            schema(listOf("path", "content"), "path" to prop("string", "Absolute path in the codespace"), "content" to prop("string", "File content")),
        ),
        ToolDef(
            "cloud_download", "Fetch a file from the codespace onto the phone (saved in the app's cloud_out folder). Small text files are also returned inline.",
            schema(listOf("path"), "path" to prop("string", "Absolute path in the codespace")),
        ),
        ToolDef(
            "agent_browser",
            "Drive a real headless browser with the Vercel agent-browser CLI inside the codespace. Pass the CLI arguments, for example: `open https://example.com`, `snapshot -i` (accessibility tree with @refs), `click @e2`, `fill @e3 \"text\"`, `get text @e1`, `screenshot /tmp/a.png`, `close`. Use it to open live sites, fill forms, test UIs and verify deployed apps.",
            schema(listOf("args"), "args" to prop("string", "Arguments after `agent-browser`")),
        ),
        ToolDef("playwright_tools", "List the tools of the Playwright MCP server running headless in the codespace (browser_navigate, browser_snapshot, browser_click ...).", schema(emptyList())),
        ToolDef(
            "playwright_call",
            "Call one Playwright MCP tool in the codespace. Use playwright_tools first to see names and schemas. Good for scraping, form filling, preview testing and web research.",
            schema(
                listOf("tool"),
                "tool" to prop("string", "Tool name, e.g. browser_navigate"),
                "arguments" to buildJsonObject { put("type", "object"); put("description", "Arguments object for that tool") },
            ),
        ),
        ToolDef("github_whoami", "Show the authenticated GitHub user.", schema(emptyList())),
        ToolDef("github_list_repos", "List the user's most recently updated repositories.", schema(emptyList())),
        ToolDef(
            "github_create_repo", "Create a new repository (initialised with a README).",
            schema(
                listOf("name"),
                "name" to prop("string", "Repository name"),
                "description" to prop("string", "Short description"),
                "private" to prop("boolean", "Private repository, default false"),
            ),
        ),
        ToolDef(
            "github_write_files",
            "Create NEW files (or deliberately replace one) in ONE commit and delete files. Existing files must be changed with edit_file_diff, not rewritten. Workflow files under .github/workflows need the token's Workflows permission.",
            schema(
                listOf("repo", "message", "files"),
                "repo" to prop("string", "owner/name or just name for the signed-in user"),
                "message" to prop("string", "Commit message"),
                "files" to buildJsonObject { put("type", "array"); put("items", fileItem); put("description", "Files to write") },
                "delete_paths" to buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Paths to delete") },
                "branch" to prop("string", "Branch, default is the repo default branch"),
            ),
        ),
        ToolDef(
            "github_read_file", "Read a text file from a repository.",
            schema(listOf("repo", "path"), "repo" to prop("string", "owner/name or name"), "path" to prop("string", "File path"), "ref" to prop("string", "Branch or sha"), "start_line" to prop("number", "Optional first line"), "end_line" to prop("number", "Optional last line")),
        ),
        ToolDef(
            "github_list_files", "List all file paths in a repository branch.",
            schema(listOf("repo"), "repo" to prop("string", "owner/name or name"), "branch" to prop("string", "Branch")),
        ),
        ToolDef(
            "github_run_workflow", "Trigger a workflow that has the workflow_dispatch trigger.",
            schema(listOf("repo", "workflow"), "repo" to prop("string", "owner/name or name"), "workflow" to prop("string", "Workflow file name, e.g. build.yml"), "ref" to prop("string", "Branch")),
        ),
        ToolDef("github_list_runs", "List the latest GitHub Actions runs.", schema(listOf("repo"), "repo" to prop("string", "owner/name or name"))),
        ToolDef(
            "github_wait_run", "Wait (up to about 6 minutes) until a workflow run finishes and report its conclusion.",
            schema(listOf("repo", "run_id"), "repo" to prop("string", "owner/name or name"), "run_id" to prop("number", "Run id from github_list_runs")),
        ),
        ToolDef(
            "github_run_logs", "Get the failing steps and the tail of the logs for a workflow run. Use it to fix build errors.",
            schema(listOf("repo", "run_id"), "repo" to prop("string", "owner/name or name"), "run_id" to prop("number", "Run id")),
        ),
        ToolDef("github_latest_release", "Show the latest release and its downloadable files (APK link).", schema(listOf("repo"), "repo" to prop("string", "owner/name or name"))),
    )

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
    private fun JsonElement.o() = this as JsonObject
    private fun JsonElement?.s(k: String) = (this as? JsonObject)?.s(k)

    suspend fun execute(ctx: ToolCtx, name: String, argsJson: String): String {
        val store = ctx.store
        ctx.cloud.progress = { line -> ctx.emit(line + "\n") }   // codespace start-up shows what it is doing instead of a silent "Starting..."
        var a = runCatching { AppJsonLenient.parseToJsonElement(argsJson.ifBlank { "{}" }).o() }.getOrElse { return "ERROR: invalid JSON arguments" }
        if (ctx.activeRepo.isNotBlank() && a.s("repo").isNullOrBlank() &&
            (name in REPO_TOOLS || (name in setOf("search_code", "find_files", "read_lines") && a.s("scope") == "repo"))
        ) a = JsonObject(a + ("repo" to JsonPrimitive(ctx.activeRepo)))
        if (name == "use_skill") {
            val n = a.s("name").orEmpty()
            val sk = store.enabledSkills().firstOrNull { it.name.equals(n, true) || it.id == n }
            return sk?.instructions ?: "ERROR: no enabled skill named '$n'. Available: ${store.enabledSkills().joinToString { it.name }}"
        }
        if (name == "create_skill") {
            val sk = com.aura.agent.data.Skill(
                com.aura.agent.data.Ids.id(),
                (a.s("name") ?: return "ERROR: name required").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
                a.s("description").orEmpty(), a.s("instructions") ?: return "ERROR: instructions required",
            )
            store.upsertSkill(sk)
            return "Skill '${sk.name}' saved. It is now listed under Settings > Skills."
        }
        if (ctx.readOnly && name !in ToolsPro.READ_ONLY) return "ERROR: workers are read-only and cannot use $name."
        if (name in ToolsPro.NAMES) return ToolsPro.run(ctx, name, a)
        if (name in ToolsVercel.NAMES) return ToolsVercel.run(ctx, name, a)
        if (name in ToolsMemory.NAMES) return ToolsMemory.run(ctx, name, a)
        if (name in ToolsLive.NAMES) return ToolsLive.run(ctx, name, a)
        if (name in ToolsMcp.NAMES) return ToolsMcp.run(ctx, name, a)
        if (name in ToolsFolders.NAMES) return ToolsFolders.run(ctx, name, a)
        if (name in ToolsVault.NAMES) return ToolsVault.run(ctx, name, a)
        if (name in ToolsFiles.NAMES) return ToolsFiles.run(ctx, name, a)
        if (ctx.app.mcp.owns(name)) return ctx.app.mcp.call(ctx, name, a)
        if (name in CLOUD_TOOLS) return runCloud(name, a, ctx)
        val token = store.githubToken.value
        if (token.isBlank()) return "ERROR: No GitHub token. Ask the user to add one in Settings > GitHub."
        val gh = GitHub(token, ctx.app.cloudApi)
        return try {
            val me = store.githubUser.value.ifBlank { gh.login().also { store.setGithub(token, it) } }
            fun full(r: String) = if ("/" in r) r else "$me/$r"
            when (name) {
                "github_whoami" -> "Signed in as $me"
                "github_list_repos" -> (gh.call("GET", "/user/repos?per_page=30&sort=updated") as JsonArray).joinToString("\n") {
                    "${it.s("full_name")}${if (it.o()["private"]?.jsonPrimitive?.booleanOrNull == true) " (private)" else ""}"
                }.ifBlank { "No repositories yet" }
                "github_create_repo" -> {
                    ctx.confirmGithubPush("Create the repository ${a.s("name").orEmpty()} (${if (a.b("private") == true) "private" else "public"}) on your GitHub account.")?.let { return it }
                    val r = gh.call("POST", "/user/repos", buildJsonObject {
                        put("name", a.s("name") ?: return "ERROR: name required")
                        put("description", a.s("description").orEmpty())
                        put("private", a.b("private") ?: false)
                        put("auto_init", true)
                    })
                    "Created ${r.s("full_name")} - ${r.s("html_url")} (default branch ${r.s("default_branch")})"
                }
                "github_write_files" -> writeFiles(gh, full(a.s("repo") ?: return "ERROR: repo required"), a, ctx)
                "github_read_file" -> {
                    val ref = a.s("ref")?.let { "?ref=$it" }.orEmpty()
                    val r = gh.call("GET", "/repos/${full(a.s("repo")!!)}/contents/${a.s("path")}$ref")
                    val enc = r.s("content").orEmpty().replace("\n", "")
                    val text = String(Base64.getDecoder().decode(enc))
                    val st = a.n("start_line")?.toInt(); val en = a.n("end_line")?.toInt()
                    if (st != null) ToolsPro.numbered(text, st.coerceAtLeast(1), (en ?: (st + 120)).coerceAtLeast(st)) else text.take(12000)
                }
                "github_list_files" -> {
                    val repo = full(a.s("repo")!!)
                    val br = a.s("branch") ?: gh.call("GET", "/repos/$repo").s("default_branch")!!
                    val t = gh.call("GET", "/repos/$repo/git/trees/$br?recursive=1").o()
                    (t["tree"] as JsonArray).filter { it.s("type") == "blob" }.map { it.s("path") }.take(400).joinToString("\n")
                }
                "github_run_workflow" -> {
                    if (ctx.state.ciFailures >= ctx.mode.verify) return "MAX_RETRIES_REACHED: the build already failed ${ctx.mode.verify} times in this task. Do not start it again; explain the remaining error to the user."
                    val repo = full(a.s("repo")!!)
                    val ref = a.s("ref") ?: gh.call("GET", "/repos/$repo").s("default_branch")!!
                    gh.call("POST", "/repos/$repo/actions/workflows/${a.s("workflow")}/dispatches", buildJsonObject { put("ref", ref) })
                    "Workflow dispatched on $ref. Use github_list_runs to find the run."
                }
                "github_list_runs" -> {
                    val r = gh.call("GET", "/repos/${full(a.s("repo")!!)}/actions/runs?per_page=6").o()
                    (r["workflow_runs"] as JsonArray).joinToString("\n") {
                        "id=${it.o()["id"]} ${it.s("name")} status=${it.s("status")} conclusion=${it.s("conclusion")} branch=${it.s("head_branch")} ${it.s("html_url")}"
                    }.ifBlank { "No runs yet" }
                }
                "github_wait_run" -> waitRun(gh, full(a.s("repo")!!), a.n("run_id") ?: return "ERROR: run_id required", ctx)
                "github_run_logs" -> runLogs(gh, full(a.s("repo")!!), a.n("run_id") ?: return "ERROR: run_id required")
                "github_latest_release" -> {
                    val r = ToolsFiles.findRelease(gh, full(a.s("repo")!!), a.s("tag")) ?: return "ERROR: this repository has no release yet. Run the build workflow first, then check again."
                    val assets = (r["assets"] as JsonArray).joinToString("\n") { "- ${it.s("name")}: ${it.s("browser_download_url")}" }
                    "Release ${r.s("tag_name")}${if (r["prerelease"]?.jsonPrimitive?.booleanOrNull == true) " (pre-release)" else ""} ${r.s("html_url")}\n$assets\nShare these links so other people can download the app; send_release_apk gives the user the APK file itself inside Aura."
                }
                else -> "ERROR: unknown tool $name"
            }
        } catch (e: GhError) {
            "ERROR: ${e.message}"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "ERROR: ${e.message ?: e.javaClass.simpleName}"
        }.take(9000)
    }

    val CLOUD_TOOLS = setOf("cloud_exec", "cloud_upload", "cloud_download", "agent_browser", "playwright_tools", "playwright_call")

    private suspend fun runCloud(name: String, a: JsonObject, ctx: ToolCtx): String {
        return try {
            val c = ctx.cloud.client()
            val cp = Checkpoints(ctx.app)
            when (name) {
                "cloud_exec" -> {
                    val cmd = a.s("command") ?: return "ERROR: command required"
                    val cwd = a.s("cwd") ?: ctx.workDir(c)
                    Guard.dangerous(cmd)?.let { reason ->
                        if (ctx.store.guardrails.value && !ctx.approve("Dangerous command: $reason", cmd)) {
                            return "DENIED: the user did not approve this command ($reason). Do not retry it; choose a safer approach or ask the user."
                        }
                    }
                    if (Regex("""\bgit\s+push\b""").containsMatchIn(cmd)) {
                        // look at what is about to leave the codespace; secrets block the push
                        val diff = c.exec("cd ${Sh.q(cwd)} && git diff --no-color \$(git merge-base HEAD @{u} 2>/dev/null || git hash-object -t tree /dev/null)..HEAD 2>/dev/null | head -c 2000000", 60).stdout
                        SecretScan.find(diff)?.let { return "BLOCKED: the commits to be pushed contain $it. Secrets are never pushed to GitHub. Remove it from the files and the commit history (git reset --soft, then recommit without it), then push again." }
                        Regex("""(?m)^\+\+\+ b/(.+)$""").findAll(diff).map { it.groupValues[1] }.firstNotNullOfOrNull { SecretScan.blockedPath(it) }?.let { return "BLOCKED: $it. Secrets are never pushed to GitHub. Remove the file from the commit and add it to .gitignore." }
                    }
                    if (Guard.mutates(cmd)) cp.recordCloud(ctx, c, cwd, "command: ${cmd.take(60)}")
                    ctx.emit("$ $cmd\n")
                    c.execStream(cmd, (a.n("timeout_seconds") ?: 300L).toInt().coerceIn(5, 1800), cwd, emptyMap(), ctx.emit).render()
                }
                "cloud_upload" -> {
                    val path = a.s("path") ?: return "ERROR: path required"
                    val content = a.s("content").orEmpty()
                    Guard.looksTruncated(content)?.let { return "ERROR: the content contains a placeholder ($it). Write the complete file, or use cloud_edit_file for a small change." }
                    cp.recordCloud(ctx, c, path.substringBeforeLast('/', ToolsPro.WORKSPACE), "upload $path")
                    c.upload(path, content.toByteArray())
                    ctx.state.markModified()
                    "Uploaded $path"
                }
                "cloud_download" -> {
                    val path = a.s("path") ?: return "ERROR: path required"
                    val bytes = c.download(path)
                    val dir = ctx.outDir.also { it.mkdirs() }
                    val f = java.io.File(dir, path.substringAfterLast('/').ifBlank { "file" })
                    f.writeBytes(bytes)
                    val text = runCatching { String(bytes, Charsets.UTF_8).takeIf { b -> bytes.size < 6000 && b.none { it == '\u0000' } } }.getOrNull()
                    "Saved ${bytes.size} bytes to ${f.absolutePath}" + (text?.let { "\n---\n$it" } ?: "")
                }
                "agent_browser" -> {
                    if (!ctx.cloud.browserReady) {
                        ctx.emit("Preparing the browser (first use)...\n")
                        val r = c.execStream("command -v agent-browser >/dev/null || (npm i -g agent-browser && agent-browser install --with-deps)", 900, null, emptyMap(), ctx.emit)
                        if (r.exit != 0) return "ERROR: could not install agent-browser\n" + r.render()
                        ctx.cloud.browserReady = true
                    }
                    val args = a.s("args") ?: return "ERROR: args required"
                    ctx.emit("$ agent-browser $args\n")
                    c.execStream("agent-browser $args", 180, null, emptyMap(), ctx.emit).render()
                }
                "playwright_tools" -> c.mcpTools()
                else -> {
                    val tool = a.s("tool") ?: return "ERROR: tool required"
                    ctx.emit("playwright: $tool ${(a["arguments"] as? JsonObject) ?: ""}\n")
                    c.mcpCall(tool, (a["arguments"] as? JsonObject) ?: JsonObject(emptyMap())).also { ctx.emit(it.take(600) + "\n") }
                }
            }
        } catch (e: com.aura.agent.agent.NoCodespaceToken) {
            ToolsPro.NO_TOKEN
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "ERROR: ${e.message ?: e.javaClass.simpleName}"
        }.take(9000)
    }

    internal suspend fun pushFiles(gh: GitHub, repo: String, a: JsonObject, ctx: ToolCtx): String = writeFiles(gh, repo, a, ctx)

    private suspend fun writeFiles(gh: GitHub, repo: String, a: JsonObject, ctx: ToolCtx): String {
        val files = a["files"] as? JsonArray ?: return "ERROR: files required"
        val deletes = (a["delete_paths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        // secrets never reach GitHub: refuse the whole push before anything is sent
        for (f in files) {
            val fo = f.o(); val p = fo.s("path").orEmpty()
            SecretScan.blockedPath(p)?.let { return "BLOCKED: $it. Secrets are never pushed to GitHub. Nothing was pushed. Remove it, add it to .gitignore and commit a .env.example with empty values instead." }
            if (fo.s("encoding") != "base64") SecretScan.checkOne(p, fo.s("content").orEmpty())?.let { return "$it Nothing was pushed." }
        }
        // never accept abbreviated code
        for (f in files) {
            val fo = f.o()
            if (fo.s("encoding") == "base64") continue
            Guard.looksTruncated(fo.s("content").orEmpty())?.let { return "ERROR: ${fo.s("path")} contains a placeholder ($it). Nothing was pushed. Write the complete file, or use edit_file_diff for a small change." }
        }
        ctx.confirmGithubPush("Push ${files.size} file(s) to $repo.", files.mapNotNull { it.o().s("path") }.take(12).joinToString("\n") { "  $it" })?.let { return it }
        val branch = a.s("branch") ?: gh.call("GET", "/repos/$repo").s("default_branch")!!
        val parent = gh.call("GET", "/repos/$repo/git/ref/heads/$branch").o()["object"].s("sha")!!
        val baseTree = gh.call("GET", "/repos/$repo/git/commits/$parent").o()["tree"].s("sha")!!
        // do not overwrite existing files wholesale
        val existing = runCatching {
            (gh.call("GET", "/repos/$repo/git/trees/$baseTree?recursive=1").o()["tree"] as JsonArray)
                .filter { it.s("type") == "blob" }.associate { it.s("path")!! to ((it as JsonObject)["size"] as? JsonPrimitive)?.intOrNull }
        }.getOrDefault(emptyMap())
        for (f in files) {
            val fo = f.o(); val path = fo.s("path") ?: continue
            val size = existing[path]
            if (size != null && size > 1500 && fo.b("overwrite") != true) {
                return "ERROR: $path already exists ($size bytes). Do not rewrite whole files: use edit_file_diff with the exact lines to change. If a full replacement is really needed, set overwrite=true on that file. Nothing was pushed."
            }
        }
        if (deletes.isNotEmpty() && ctx.store.guardrails.value && !ctx.approve("Delete ${deletes.size} file(s) in $repo", deletes.joinToString("\n"))) {
            return "DENIED: the user did not approve deleting these files. Nothing was pushed."
        }
        Checkpoints(ctx.app).recordGithub(ctx, repo, branch, parent, "push ${files.size} file(s)")
        val entries = ArrayList<JsonObject>()
        for (f in files) {
            val fo = f.o()
            val path = fo.s("path") ?: continue
            val blob = gh.call("POST", "/repos/$repo/git/blobs", buildJsonObject {
                put("content", fo.s("content").orEmpty()); put("encoding", if (fo.s("encoding") == "base64") "base64" else "utf-8")
            })
            entries += buildJsonObject {
                put("path", path); put("mode", if (fo.b("executable") == true) "100755" else "100644")
                put("type", "blob"); put("sha", blob.s("sha")!!)
            }
        }
        for (d in deletes) entries += buildJsonObject {
            put("path", d); put("mode", "100644"); put("type", "blob"); put("sha", JsonNull)
        }
        val tree = gh.call("POST", "/repos/$repo/git/trees", buildJsonObject {
            put("base_tree", baseTree); put("tree", JsonArray(entries))
        }).s("sha")!!
        val commit = gh.call("POST", "/repos/$repo/git/commits", buildJsonObject {
            put("message", a.s("message") ?: "Update from Aura"); put("tree", tree)
            putJsonArray("parents") { add(parent) }
        }).s("sha")!!
        gh.call("PATCH", "/repos/$repo/git/refs/heads/$branch", buildJsonObject { put("sha", commit) })
        ctx.state.markModified()
        return "Pushed ${files.size} file(s) and ${deletes.size} deletion(s) to $repo@$branch in commit ${commit.take(7)}"
    }

    private suspend fun waitRun(gh: GitHub, repo: String, id: Long, ctx: ToolCtx): String {
        repeat(36) {
            val r = gh.call("GET", "/repos/$repo/actions/runs/$id").o()
            ctx.emit("run $id: ${r.s("status")} ${r.s("conclusion").orEmpty()}\n")
            if (r.s("status") == "completed") {
                val conclusion = r.s("conclusion")
                if (conclusion == "failure" || conclusion == "timed_out") {
                    ctx.state.ciFailures++
                    val n = ctx.state.ciFailures
                    return "Run $id finished: $conclusion ${r.s("html_url")}\nBuild failure $n of ${ctx.mode.verify}." +
                        if (n >= ctx.mode.verify) "\nMAX_RETRIES_REACHED: stop fixing and tell the user what is still failing." else " Read the logs with github_run_logs and fix the cause."
                }
                return "Run $id finished: $conclusion ${r.s("html_url")}"
            }
            delay(10_000)
        }
        return "Run $id is still running after 6 minutes. Call github_wait_run again."
    }

    /**
     * A CI log ends with cache and daemon warnings, so the tail alone hides the compile error. The digest pulls out the error lines
     * (compiler errors, "What went wrong", missing resources ...) with a little context, then adds a shorter tail.
     */
    object LogDigest {
        private val stamp = Regex("""^\d{4}-\d{2}-\d{2}T[\d:.]+Z\s?""")
        private val hit = Regex("""(?i)(^e: |\berror:|\bFAILURE:|What went wrong|Execution failed|Unresolved reference|AAPT|resource .* not found|Could not (resolve|find|determine)|\bFAILED\b|Compilation error|Duplicate class|Manifest merger|Caused by:|Exception in|npm ERR!|##\[error\])""")
        private val noise = Regex("""(?i)(gradle.*cache|daemon|configuration cache|deprecat|build scan|you can use|--warning-mode|welcome to gradle)""")

        fun digest(raw: String, tail: Int = 1500, maxErrors: Int = 40): String {
            val lines = raw.lineSequence().map { stamp.replace(it, "").trimEnd() }.toList()
            val picked = LinkedHashSet<Int>()
            lines.forEachIndexed { i, l ->
                if (hit.containsMatchIn(l) && !(noise.containsMatchIn(l) && !l.contains("error", true))) {
                    picked += i
                    if (l.contains("What went wrong")) { for (k in 1..3) if (i + k < lines.size) picked += i + k }
                }
            }
            val errs = picked.sorted().take(maxErrors).joinToString("\n") { lines[it].take(300) }
            val last = raw.takeLast(tail)
            return if (errs.isBlank()) "(no error line found; end of the log)\n$last" else "Error lines (first ${minOf(picked.size, maxErrors)} of ${picked.size}):\n$errs\n\n--- end of the log ---\n$last"
        }
    }

    private suspend fun runLogs(gh: GitHub, repo: String, id: Long): String {
        val jobs = (gh.call("GET", "/repos/$repo/actions/runs/$id/jobs").o()["jobs"] as JsonArray)
        val out = StringBuilder()
        for (j in jobs) {
            val jo = j.o()
            if (jo.s("conclusion") != "failure") continue
            val failed = (jo["steps"] as? JsonArray)?.filter { it.s("conclusion") == "failure" }?.joinToString { it.s("name").orEmpty() }
            out.append("Job ${jo.s("name")} failed at step: $failed\n")
            val log = runCatching { gh.raw("/repos/$repo/actions/jobs/${jo["id"]}/logs") }.getOrDefault("")
            out.append(LogDigest.digest(log)).append("\n\n")
        }
        return out.toString().ifBlank { "No failed jobs for run $id" }
    }

    /** One-line description for the UI and notifications. */
    fun label(name: String) = when (name) {
        "cloud_exec" -> "Running in your codespace"
        "cloud_clone" -> "Cloning repository"
        "attachment_to_cloud" -> "Sending file to codespace"
        "attachment_to_repo" -> "Adding file to repository"
        "cloud_upload" -> "Uploading to codespace"
        "cloud_download" -> "Fetching file from codespace"
        "agent_browser" -> "Browsing with agent-browser"
        "playwright_tools" -> "Listing browser tools"
        "playwright_call" -> "Browsing with Playwright"
        "vercel_deploy" -> "Deploying to Vercel"
        "save_memory" -> "Saving to memory"
        "search_memory" -> "Searching memory"
        "delete_memory" -> "Forgetting"
        "send_file" -> "Preparing a file for you"
        "send_release_apk" -> "Getting the APK for you"
        "request_api_key" -> "Asking you for an API key"
        "api_configure" -> "Setting up a stored key"
        "api_list" -> "Checking stored API keys"
        "api_request" -> "Calling an API"
        "mcp_list" -> "Checking MCP servers"
        "mcp_search" -> "Searching the MCP registry"
        "mcp_connect" -> "Connecting an MCP server"
        "mcp_disconnect" -> "Disconnecting an MCP server"
        "folder_list" -> "Looking in your phone folder"
        "folder_read" -> "Reading a phone file"
        "folder_write" -> "Writing to your phone folder"
        "folder_edit" -> "Editing a phone file"
        "folder_search" -> "Searching your phone folder"
        "folder_delete" -> "Deleting from your phone folder"
        "folder_copy" -> "Copying between phone folders"
        "folder_zip" -> "Packing a ZIP for you"
        "backup_repo_to_folder" -> "Backing up to your phone"
        "push_folder_to_repo" -> "Uploading a folder to GitHub"
        "web_search" -> "Searching the web"
        "web_fetch" -> "Reading a web page"
        "generate_image" -> "Generating an image"
        "share_file" -> "Opening the share sheet"
        "save_to_phone" -> "Saving to the phone"
        "open_url" -> "Opening a link"
        "download_file" -> "Downloading a file"
        "edit_file_diff" -> "Editing file"
        "cloud_edit_file" -> "Editing file in codespace"
        "verify_project" -> "Building and checking"
        "search_code" -> "Searching code"
        "find_files" -> "Finding files"
        "read_lines" -> "Reading lines"
        "delegate" -> "Research sub-agents"
        "use_skill" -> "Loading skill"
        "create_skill" -> "Saving skill"
        "github_whoami" -> "Checking GitHub account"
        "github_list_repos" -> "Listing repositories"
        "github_create_repo" -> "Creating repository"
        "github_write_files" -> "Pushing files"
        "github_read_file" -> "Reading file"
        "github_list_files" -> "Listing files"
        "github_run_workflow" -> "Starting workflow"
        "github_list_runs" -> "Checking workflow runs"
        "github_wait_run" -> "Waiting for build"
        "github_run_logs" -> "Reading build logs"
        "github_latest_release" -> "Looking up release"
        else -> name
    }
}
