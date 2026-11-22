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
        }
        putJsonArray("required") { add("path"); add("content") }
    }

    val defs: List<ToolDef> = listOf(
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
            "Create or update many files in ONE commit (also deletes files). This is how code is pushed. Workflow files under .github/workflows need the token's Workflows permission.",
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
            schema(listOf("repo", "path"), "repo" to prop("string", "owner/name or name"), "path" to prop("string", "File path"), "ref" to prop("string", "Branch or sha")),
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

    suspend fun execute(store: Store, name: String, argsJson: String): String {
        val token = store.githubToken.value
        if (token.isBlank()) return "ERROR: No GitHub token. Ask the user to add one in Settings > GitHub."
        val gh = GitHub(token)
        val a = runCatching { AppJsonLenient.parseToJsonElement(argsJson.ifBlank { "{}" }).o() }.getOrElse { return "ERROR: invalid JSON arguments" }
        return try {
            val me = store.githubUser.value.ifBlank { gh.login().also { store.setGithub(token, it) } }
            fun full(r: String) = if ("/" in r) r else "$me/$r"
            when (name) {
                "github_whoami" -> "Signed in as $me"
                "github_list_repos" -> (gh.call("GET", "/user/repos?per_page=30&sort=updated") as JsonArray).joinToString("\n") {
                    "${it.s("full_name")}${if (it.o()["private"]?.jsonPrimitive?.booleanOrNull == true) " (private)" else ""}"
                }.ifBlank { "No repositories yet" }
                "github_create_repo" -> {
                    val r = gh.call("POST", "/user/repos", buildJsonObject {
                        put("name", a.s("name") ?: return "ERROR: name required")
                        put("description", a.s("description").orEmpty())
                        put("private", a.b("private") ?: false)
                        put("auto_init", true)
                    })
                    "Created ${r.s("full_name")} - ${r.s("html_url")} (default branch ${r.s("default_branch")})"
                }
                "github_write_files" -> writeFiles(gh, full(a.s("repo") ?: return "ERROR: repo required"), a)
                "github_read_file" -> {
                    val ref = a.s("ref")?.let { "?ref=$it" }.orEmpty()
                    val r = gh.call("GET", "/repos/${full(a.s("repo")!!)}/contents/${a.s("path")}$ref")
                    val enc = r.s("content").orEmpty().replace("\n", "")
                    String(Base64.getDecoder().decode(enc)).take(12000)
                }
                "github_list_files" -> {
                    val repo = full(a.s("repo")!!)
                    val br = a.s("branch") ?: gh.call("GET", "/repos/$repo").s("default_branch")!!
                    val t = gh.call("GET", "/repos/$repo/git/trees/$br?recursive=1").o()
                    (t["tree"] as JsonArray).filter { it.s("type") == "blob" }.map { it.s("path") }.take(400).joinToString("\n")
                }
                "github_run_workflow" -> {
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
                "github_wait_run" -> waitRun(gh, full(a.s("repo")!!), a.n("run_id") ?: return "ERROR: run_id required")
                "github_run_logs" -> runLogs(gh, full(a.s("repo")!!), a.n("run_id") ?: return "ERROR: run_id required")
                "github_latest_release" -> {
                    val r = gh.call("GET", "/repos/${full(a.s("repo")!!)}/releases/latest").o()
                    val assets = (r["assets"] as JsonArray).joinToString("\n") { "- ${it.s("name")}: ${it.s("browser_download_url")}" }
                    "Release ${r.s("tag_name")} ${r.s("html_url")}\n$assets"
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

    private suspend fun writeFiles(gh: GitHub, repo: String, a: JsonObject): String {
        val files = a["files"] as? JsonArray ?: return "ERROR: files required"
        val deletes = (a["delete_paths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val branch = a.s("branch") ?: gh.call("GET", "/repos/$repo").s("default_branch")!!
        val parent = gh.call("GET", "/repos/$repo/git/ref/heads/$branch").o()["object"].s("sha")!!
        val baseTree = gh.call("GET", "/repos/$repo/git/commits/$parent").o()["tree"].s("sha")!!
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
        return "Pushed ${files.size} file(s) and ${deletes.size} deletion(s) to $repo@$branch in commit ${commit.take(7)}"
    }

    private suspend fun waitRun(gh: GitHub, repo: String, id: Long): String {
        repeat(36) {
            val r = gh.call("GET", "/repos/$repo/actions/runs/$id").o()
            if (r.s("status") == "completed") return "Run $id finished: ${r.s("conclusion")} ${r.s("html_url")}"
            delay(10_000)
        }
        return "Run $id is still running after 6 minutes. Call github_wait_run again."
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
            out.append(log.takeLast(3500)).append("\n\n")
        }
        return out.toString().ifBlank { "No failed jobs for run $id" }
    }

    /** One-line description for the UI and notifications. */
    fun label(name: String) = when (name) {
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
