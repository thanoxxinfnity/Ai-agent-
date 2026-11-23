package com.aura.agent.agent

import com.aura.agent.net.*
import kotlinx.serialization.json.*

/** Website deployment on Vercel with the user's own token. */
object ToolsVercel {
    val NAMES = setOf("vercel_deploy")

    const val NO_TOKEN = "NO_VERCEL_TOKEN: the user has not added a Vercel token. Politely ask them to open Settings > Vercel, paste a token from vercel.com/account/tokens, and try again."
    private const val MAX_FILES = 1500
    private const val MAX_BYTES = 60L * 1024 * 1024

    private fun prop(type: String, desc: String) = buildJsonObject { put("type", type); put("description", desc) }

    val defs: List<ToolDef> = listOf(
        ToolDef(
            "vercel_deploy",
            "Deploy a website to Vercel with the user's token and get the live link. The source is the chat's repository (optionally one folder of it, such as public or dist) or files you pass in. Vercel builds frameworks like Next.js and Vite by itself. Always give the user the returned link.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    put("name", prop("string", "Project name, default the repository name"))
                    put("repo", prop("string", "owner/name, default the chat's active repository"))
                    put("branch", prop("string", "Branch or ref, default the default branch"))
                    put("directory", prop("string", "Folder inside the repo to deploy, for example public or dist. Empty = repository root"))
                    put("production", prop("boolean", "true (default) gives a public production link; false makes a preview that may be login-protected"))
                    put("framework", prop("string", "Optional Vercel framework preset such as nextjs or vite; use static for plain HTML"))
                    putJsonObject("files") {
                        put("type", "array"); put("description", "Alternative to a repo: small files to deploy directly")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") { put("path", prop("string", "File path, e.g. index.html")); put("content", prop("string", "Text content")) }
                            putJsonArray("required") { add("path"); add("content") }
                        }
                    }
                }
                putJsonArray("required") {}
            },
        ),
    )

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

    suspend fun run(ctx: ToolCtx, name: String, a: JsonObject): String {
        return try {
            deploy(ctx, a)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: VercelError) {
            "ERROR: ${e.message}"
        } catch (e: GhError) {
            "ERROR: ${e.message}"
        } catch (e: Exception) {
            "ERROR: ${e.message ?: e.javaClass.simpleName}"
        }.take(9000)
    }

    private suspend fun deploy(ctx: ToolCtx, a: JsonObject): String {
        val token = ctx.store.vercelToken.value
        if (token.isBlank()) return NO_TOKEN
        if (ctx.state.deployFailures >= ctx.mode.verify) {
            return "MAX_RETRIES_REACHED: the deployment already failed ${ctx.mode.verify} times in this task. Stop and explain the remaining error to the user (a deeper thinking mode allows more attempts)."
        }
        val production = a.b("production") ?: true
        val directory = a.s("directory").orEmpty()
        val inline = (a["files"] as? JsonArray)?.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val p = o.s("path")?.trim('/') ?: return@mapNotNull null
            DeployFile(p, (o.s("content") ?: "").toByteArray())
        }.orEmpty()

        val files: List<DeployFile>
        val source: String
        var repoName: String? = null
        if (inline.isNotEmpty()) {
            files = inline; source = "files you provided"
        } else {
            val ghToken = ctx.store.githubToken.value
            if (ghToken.isBlank()) return "ERROR: No GitHub token. Ask the user to sign in with GitHub first, or pass the files directly."
            val me = ctx.store.githubUser.value
            val repo = (a.s("repo") ?: ctx.activeRepo.ifBlank { null } ?: return "ERROR: no repository. Choose one when the chat starts, pass repo, or pass files.")
                .let { if ("/" in it) it else "$me/$it" }
            repoName = repo.substringAfter('/')
            ctx.emit("Downloading $repo...\n")
            val ref = a.s("branch")?.let { "/$it" }.orEmpty()
            val tar = GitHub(ghToken, ctx.app.cloudApi).bytes("/repos/$repo/tarball$ref")
            files = TarReader.readGz(tar).filter { DeployFilter.keep(it.path, directory) }
                .map { DeployFile(DeployFilter.relative(it.path, directory), it.bytes) }
            source = "$repo" + if (directory.isNotBlank()) "/$directory" else ""
        }
        if (files.isEmpty()) return "ERROR: no files to deploy from $source. Check the folder name (directory)."
        if (files.size > MAX_FILES) return "ERROR: ${files.size} files is too many (limit $MAX_FILES). Deploy a build folder with the directory argument."
        val total = files.sumOf { it.bytes.size.toLong() }
        if (total > MAX_BYTES) return "ERROR: the files add up to ${total / 1024 / 1024} MB (limit ${MAX_BYTES / 1024 / 1024} MB)."

        val project = DeployFilter.projectName(a.s("name") ?: repoName ?: "aura-site")
        if (production && ctx.store.guardrails.value && !ctx.approve("Deploy to Vercel production: $project", "${files.size} files (${total / 1024} KB) from $source\nThis publishes a public website.")) {
            return "DENIED: the user did not approve the production deployment. You may offer a preview deployment (production=false) instead."
        }

        val vercel = Vercel(token, ctx.store.vercelTeam.value, ctx.app.vercelBase)
        ctx.emit("Uploading ${files.size} files (${total / 1024} KB)...\n")
        files.forEachIndexed { i, f -> if (i % 25 == 0 && i > 0) ctx.emit("  uploaded $i of ${files.size}\n"); vercel.upload(f) }
        val created = vercel.deploy(project, files, production, a.s("framework")) { ctx.emit("$it\n") }
        ctx.emit("Deployment ${created.id} created. Building on Vercel...\n")
        val done = vercel.waitReady(created, 240, ctx.app.vercelPollMs) { ctx.emit("  state: ${it.lowercase()}\n") }
        if (done.state != "READY") {
            ctx.state.deployFailures++
            val log = vercel.buildLog(done.id)
            return "ERROR: Vercel build ${done.state.lowercase()} (failure ${ctx.state.deployFailures} of ${ctx.mode.verify})." +
                (done.error?.let { "\n$it" } ?: "") + (if (log.isNotBlank()) "\n--- build log ---\n$log" else "") +
                "\nFix the cause (read the log above) and call vercel_deploy again."
        }
        val url = vercel.publicUrl(done, production, project, if (production) vercel.projectDomains(project) else emptyList())
        return "Deployed to Vercel (${if (production) "production" else "preview"}). Live link: $url\nProject: $project, deployment ${done.id}." +
            if (production) "" else "\nNote: preview links can be protected by Vercel login; use production=true for a public link."
    }
}
