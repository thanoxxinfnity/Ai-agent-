package com.aura.agent.agent

import com.aura.agent.data.LocalFolder
import kotlinx.serialization.json.*
import java.util.Base64

/**
 * Tools for the folders the user connected on their phone. Writing there is the user's own backup space: nothing in this file
 * talks to GitHub except the two tools the user has to ask for (backup_repo_to_folder reads, push_folder_to_repo writes).
 */
object ToolsFolders {
    val NAMES = setOf("folder_list", "folder_read", "folder_write", "folder_edit", "folder_search", "folder_delete", "folder_copy", "folder_zip", "backup_repo_to_folder", "push_folder_to_repo")
    val READ_ONLY = setOf("folder_list", "folder_read", "folder_search")

    private const val MAX_READ = 60_000
    private const val MAX_COPY = 25 * 1024 * 1024
    private const val MAX_PUSH_FILES = 300
    private const val MAX_PUSH_FILE_BYTES = 2 * 1024 * 1024
    private val SKIP_DIRS = setOf(".git", "node_modules", ".gradle", "build", ".idea", "__pycache__", ".venv")

    private fun prop(type: String, desc: String) = buildJsonObject { put("type", type); put("description", desc) }
    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }
    private val folderProp = prop("string", "Name of a connected phone folder (see folder_list)")

    val defs: List<com.aura.agent.net.ToolDef> = listOf(
        com.aura.agent.net.ToolDef("folder_list", "Without arguments: show the phone folders the user connected. With folder (and optional path): list what is inside.", schema(emptyList(), "folder" to folderProp, "path" to prop("string", "Sub-folder, default the top"))),
        com.aura.agent.net.ToolDef("folder_read", "Read a text file from a connected phone folder, optionally only some lines.", schema(listOf("folder", "path"), "folder" to folderProp, "path" to prop("string", "File path inside the folder"), "start_line" to prop("number", "First line, default 1"), "end_line" to prop("number", "Last line"))),
        com.aura.agent.net.ToolDef("folder_write", "Create or replace a text file in a connected phone folder (sub-folders are created). Write the complete file.", schema(listOf("folder", "path", "content"), "folder" to folderProp, "path" to prop("string", "File path inside the folder"), "content" to prop("string", "Complete file content"))),
        com.aura.agent.net.ToolDef("folder_edit", "Exact search and replace inside one file of a connected phone folder. old_string must occur exactly once.", schema(listOf("folder", "path", "old_string", "new_string"), "folder" to folderProp, "path" to prop("string", "File path"), "old_string" to prop("string", "Exact existing text"), "new_string" to prop("string", "Replacement"))),
        com.aura.agent.net.ToolDef("folder_search", "Search for text inside the files of a connected phone folder.", schema(listOf("folder", "query"), "folder" to folderProp, "query" to prop("string", "Text to find (case-insensitive)"), "path" to prop("string", "Limit to a sub-folder"))),
        com.aura.agent.net.ToolDef("folder_delete", "Delete a file or sub-folder in a connected phone folder. The user approves first.", schema(listOf("folder", "path"), "folder" to folderProp, "path" to prop("string", "File or sub-folder path"))),
        com.aura.agent.net.ToolDef("folder_copy", "Copy a file or a whole sub-folder from one phone folder to another (or inside one), for example from the phone workspace to a connected folder.", schema(listOf("from_folder", "from_path", "to_folder"), "from_folder" to prop("string", "Source folder name"), "from_path" to prop("string", "File or sub-folder in the source"), "to_folder" to prop("string", "Destination folder name"), "to_path" to prop("string", "Path in the destination, default the same as the source"))),
        com.aura.agent.net.ToolDef("folder_zip", "Pack a phone folder (or a sub-folder) into one ZIP file and give it to the user in the chat with Download and Share. Use it to hand over a whole project.", schema(listOf("folder"), "folder" to folderProp, "path" to prop("string", "Sub-folder, default the whole folder"), "name" to prop("string", "ZIP file name, default the folder name"))),
        com.aura.agent.net.ToolDef(
            "backup_repo_to_folder", "Copy a GitHub repository into a connected phone folder as a private backup (nothing is pushed anywhere). Only when the user asks for a backup.",
            schema(listOf("folder"), "folder" to folderProp, "repo" to prop("string", "owner/name, default the chat's repository"), "branch" to prop("string", "Branch or tag, default the default branch"), "subfolder" to prop("string", "Where inside the folder, default the repository name")),
        ),
        com.aura.agent.net.ToolDef(
            "push_folder_to_repo", "Upload files from a connected phone folder into a GitHub repository. ONLY when the user explicitly asks to put these files on GitHub. The user approves first; secrets are refused.",
            schema(listOf("folder", "repo"), "folder" to folderProp, "repo" to prop("string", "owner/name to push to"), "path" to prop("string", "Sub-folder to upload, default the whole folder"), "repo_path" to prop("string", "Folder inside the repository, default the top"), "branch" to prop("string", "Branch, default the default branch"), "message" to prop("string", "Commit message")),
        ),
    )

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()

    /**
     * Finds the folder the model means. No name: the one connected folder, or the built-in phone workspace when none is connected.
     * "workspace" always works.
     */
    internal fun resolve(ctx: ToolCtx, name: String?): Pair<LocalFolder, FolderBackend>? {
        val user = ctx.app.extras.enabledFolders()
        val f = when {
            name.isNullOrBlank() -> when { user.isEmpty() -> Workspace.folder; user.size == 1 -> user[0]; else -> return null }
            name.equals(Workspace.NAME, true) -> Workspace.folder
            else -> user.firstOrNull { it.name.equals(name, true) } ?: return null
        }
        return f to FolderAccess.open(ctx.app, f)
    }

    private fun pick(ctx: ToolCtx, name: String?) = resolve(ctx, name)

    private fun notFound(ctx: ToolCtx, name: String?): String {
        val all = ctx.app.extras.folders.value
        val names = (listOf(Workspace.NAME) + all.filter { it.enabled }.map { it.name }).joinToString()
        return when {
            name.isNullOrBlank() -> "ERROR: several folders are connected, pass folder: $names"
            all.any { it.name.equals(name, true) && !it.enabled } -> "ERROR: the folder '$name' is disconnected. Ask the user to switch it on in Settings > Phone folders."
            else -> "ERROR: no connected folder named '$name'. Available: $names"
        }
    }

    private fun isText(b: ByteArray) = b.none { it.toInt() == 0 }

    private fun size(n: Long) = if (n >= 1024 * 1024) "%.1f MB".format(n / 1048576.0) else if (n >= 1024) "${n / 1024} KB" else "$n B"

    suspend fun run(ctx: ToolCtx, name: String, a: JsonObject): String {
        if (ctx.readOnly && name !in READ_ONLY) return "ERROR: workers are read-only and cannot use $name."
        if (name == "folder_list" && a.s("folder").isNullOrBlank()) {
            val all = ctx.app.extras.folders.value
            return "- ${Workspace.NAME}: built-in space inside Aura on this phone, always available (use it when the user wants work kept on the phone)\n" +
                if (all.isEmpty()) "No other phone folder is connected. The user can add one in Settings > Phone folders." else
                    all.joinToString("\n") { "- ${it.name}: " + if (it.enabled) "connected" else "disconnected by the user" }
        }
        val (folder, fs) = pick(ctx, a.s("folder")) ?: return notFound(ctx, a.s("folder"))
        return try {
            when (name) {
                "folder_list" -> {
                    val path = a.s("path").orEmpty()
                    val items = fs.list(path) ?: return "ERROR: ${path.ifBlank { "the folder" }} is not a folder or does not exist."
                    if (items.isEmpty()) "(empty)" else items.sortedWith(compareByDescending<FolderEntry> { it.isDir }.thenBy { it.name.lowercase() }).take(300)
                        .joinToString("\n") { if (it.isDir) "${it.name}/" else "${it.name}  (${size(it.size)})" }
                }
                "folder_read" -> {
                    val path = a.s("path") ?: return "ERROR: path required"
                    val bytes = fs.read(path, MAX_READ) ?: return "ERROR: $path does not exist in ${folder.name}."
                    if (!isText(bytes)) return "ERROR: $path is a binary file."
                    val cut = bytes.size > MAX_READ
                    val lines = String(bytes.copyOf(minOf(bytes.size, MAX_READ)), Charsets.UTF_8).lines()
                    val from = (a.n("start_line") ?: 1).coerceAtLeast(1); val to = (a.n("end_line") ?: lines.size).coerceAtMost(lines.size)
                    if (from > to) return "ERROR: start_line is past the end ($to lines)."
                    lines.subList(from - 1, to).mapIndexed { i, l -> "${from + i}: $l" }.joinToString("\n") + if (cut) "\n... [file cut at $MAX_READ bytes]" else ""
                }
                "folder_write" -> {
                    val path = a.s("path") ?: return "ERROR: path required"
                    val content = a.s("content").orEmpty()
                    Guard.looksTruncated(content)?.let { return "ERROR: the content contains a placeholder ($it). Write the complete file, or use folder_edit for a small change." }
                    val existed = fs.stat(path) != null
                    if (existed && ctx.store.guardrails.value && !ctx.approve("Replace a file in ${folder.name}", path)) return "DENIED: the user did not approve replacing $path."
                    fs.write(path, content.toByteArray())
                    "${if (existed) "Replaced" else "Created"} $path in ${folder.name} (${size(content.length.toLong())})."
                }
                "folder_edit" -> {
                    val path = a.s("path") ?: return "ERROR: path required"
                    val old = a.s("old_string") ?: return "ERROR: old_string required"
                    val new = a.s("new_string").orEmpty()
                    val text = fs.read(path, 2_000_000)?.let { String(it, Charsets.UTF_8) } ?: return "ERROR: $path does not exist in ${folder.name}."
                    val count = text.windowed(old.length.coerceAtLeast(1), 1).count { it == old }
                    if (old.isEmpty() || count == 0) return "ERROR: old_string was not found in $path. Read the file again and copy the exact text."
                    if (count > 1) return "ERROR: old_string appears $count times in $path. Include more surrounding lines so it is unique."
                    fs.write(path, text.replace(old, new).toByteArray())
                    "Edited $path in ${folder.name}."
                }
                "folder_search" -> search(fs, a.s("query") ?: return "ERROR: query required", a.s("path").orEmpty())
                "folder_delete" -> {
                    val path = a.s("path") ?: return "ERROR: path required"
                    if (fs.stat(path) == null) return "ERROR: $path does not exist in ${folder.name}."
                    if (!ctx.approve("Delete in ${folder.name}", path)) return "DENIED: the user did not approve deleting $path."
                    if (fs.delete(path)) "Deleted $path." else "ERROR: could not delete $path."
                }
                "folder_copy" -> copy(ctx, a)
                "folder_zip" -> zip(ctx, folder, fs, a)
                "backup_repo_to_folder" -> backup(ctx, folder, fs, a)
                "push_folder_to_repo" -> push(ctx, folder, fs, a)
                else -> "ERROR: unknown tool $name"
            }
        } catch (e: IllegalArgumentException) { "ERROR: ${e.message}"
        } catch (e: IllegalStateException) { "ERROR: ${e.message}"
        } catch (e: java.io.IOException) { "ERROR: ${e.message ?: "could not read or write the folder"}"
        } catch (e: SecurityException) { "ERROR: Aura lost access to this folder. Ask the user to connect it again in Settings > Phone folders." }
    }

    private fun walk(fs: FolderBackend, path: String, depth: Int = 0, out: MutableList<String> = ArrayList(), limit: Int = 2000): List<String> {
        if (depth > 10 || out.size >= limit) return out
        for (e in fs.list(path).orEmpty().sortedBy { it.name }) {
            if (out.size >= limit) break
            val p = if (path.isBlank()) e.name else "$path/${e.name}"
            if (e.isDir) { if (e.name !in SKIP_DIRS) walk(fs, p, depth + 1, out, limit) } else out += p
        }
        return out
    }

    private fun search(fs: FolderBackend, query: String, path: String): String {
        val q = query.lowercase(); val hits = ArrayList<String>()
        for (p in walk(fs, path)) {
            if (hits.size >= 60) break
            val bytes = fs.read(p, 1_000_000) ?: continue
            if (bytes.size > 1_000_000 || !isText(bytes)) continue
            String(bytes, Charsets.UTF_8).lines().forEachIndexed { i, l -> if (hits.size < 60 && l.lowercase().contains(q)) hits += "$p:${i + 1}: ${l.trim().take(160)}" }
        }
        return if (hits.isEmpty()) "No match." else hits.joinToString("\n")
    }

    private suspend fun copy(ctx: ToolCtx, a: JsonObject): String {
        val (fromF, from) = resolve(ctx, a.s("from_folder")) ?: return notFound(ctx, a.s("from_folder"))
        val (toF, to) = resolve(ctx, a.s("to_folder")) ?: return notFound(ctx, a.s("to_folder"))
        val src = (a.s("from_path") ?: return "ERROR: from_path required").trim('/')
        val dest = (a.s("to_path") ?: src).trim('/')
        val st = from.stat(src) ?: return "ERROR: $src does not exist in ${fromF.name}."
        val files = if (st.isDir) walk(from, src, limit = 501).map { it to (dest.ifBlank { "" } + "/" + it.removePrefix("$src/")).trim('/') } else listOf(src to dest)
        if (files.size > 500) return "ERROR: more than 500 files. Copy a smaller sub-folder."
        if (files.isEmpty()) return "ERROR: there is nothing to copy in $src."
        if (files.any { (_, d) -> to.stat(d) != null } && ctx.store.guardrails.value && !ctx.approve("Replace files in ${toF.name}", files.filter { (_, d) -> to.stat(d) != null }.take(8).joinToString("\n") { it.second })) {
            return "DENIED: the user did not approve replacing existing files. Nothing was copied."
        }
        var bytes = 0L
        for ((p, d) in files) {
            val b = from.read(p, MAX_COPY) ?: continue
            if (b.size > MAX_COPY) return "ERROR: $p is larger than ${MAX_COPY / 1024 / 1024} MB."
            to.write(d, b); bytes += b.size
        }
        return "Copied ${files.size} file(s) (${size(bytes)}) from ${fromF.name}/$src to ${toF.name}/$dest."
    }

    private fun zip(ctx: ToolCtx, folder: LocalFolder, fs: FolderBackend, a: JsonObject): String {
        val path = a.s("path").orEmpty().trim('/')
        val files = walk(fs, path, limit = 2001)
        if (files.isEmpty()) return "ERROR: there are no files to pack in ${folder.name}/$path."
        if (files.size > 2000) return "ERROR: more than 2000 files. Pack a smaller sub-folder."
        val base = com.aura.agent.data.ImageFiles.safeName((a.s("name")?.takeIf { it.isNotBlank() } ?: (path.substringAfterLast('/').ifBlank { folder.name })).removeSuffix(".zip")) + ".zip"
        val dir = java.io.File(ctx.app.filesDir, "generated").also { it.mkdirs() }
        var out = java.io.File(dir, base); var i = 2
        while (out.exists()) { out = java.io.File(dir, base.removeSuffix(".zip") + " ($i).zip"); i++ }
        var total = 0L
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
            for (p in files) {
                val b = fs.read(p, MAX_COPY) ?: continue
                total += b.size
                if (total > 60L * 1024 * 1024) { out.delete(); return "ERROR: the files add up to more than 60 MB. Pack a smaller sub-folder." }
                z.putNextEntry(java.util.zip.ZipEntry(if (path.isBlank()) p else p.removePrefix("$path/"))); z.write(b); z.closeEntry()
            }
        }
        ctx.produced += com.aura.agent.data.Attachment(out.name, "application/zip", out.length(), out.absolutePath, "file")
        return "Packed ${files.size} file(s) into ${out.name} (${size(out.length())}) and gave it to the user in the chat to download or share."
    }

    private suspend fun backup(ctx: ToolCtx, folder: LocalFolder, fs: FolderBackend, a: JsonObject): String {
        val token = ctx.store.githubToken.value
        if (token.isBlank()) return "ERROR: No GitHub token. Ask the user to sign in with GitHub first."
        val me = ctx.store.githubUser.value
        val repo = (a.s("repo") ?: ctx.activeRepo.ifBlank { null } ?: return "ERROR: no repository. Pass repo (owner/name) or choose one for the chat.").let { if ("/" in it) it else "$me/$it" }
        val sub = a.s("subfolder")?.trim('/')?.ifBlank { null } ?: repo.substringAfter('/')
        val ref = a.s("branch")?.let { "/$it" }.orEmpty()
        ctx.emit("Downloading $repo for the backup...\n")
        val tar = com.aura.agent.net.GitHub(token, ctx.app.cloudApi).bytes("/repos/$repo/tarball$ref")
        val files = TarReader.readGz(tar)
        if (files.isEmpty()) return "ERROR: the repository has no files to back up."
        files.forEachIndexed { i, f ->
            if (i % 50 == 0 && i > 0) ctx.emit("  saved $i of ${files.size}\n")
            fs.write("$sub/${f.path}", f.bytes)
        }
        return "Backed up $repo (${files.size} files, ${size(files.sumOf { it.bytes.size.toLong() })}) to ${folder.name}/$sub. Nothing was pushed anywhere."
    }

    private suspend fun push(ctx: ToolCtx, folder: LocalFolder, fs: FolderBackend, a: JsonObject): String {
        val token = ctx.store.githubToken.value
        if (token.isBlank()) return "ERROR: No GitHub token. Ask the user to sign in with GitHub first."
        val gh = com.aura.agent.net.GitHub(token, ctx.app.cloudApi)
        val me = ctx.store.githubUser.value.ifBlank { gh.login().also { ctx.store.setGithub(token, it) } }
        val repo = (a.s("repo") ?: return "ERROR: repo required").let { if ("/" in it) it else "$me/$it" }
        val path = a.s("path").orEmpty().trim('/')
        val dest = a.s("repo_path").orEmpty().trim('/')
        val all = walk(fs, path, limit = MAX_PUSH_FILES + 1)
        if (all.isEmpty()) return "ERROR: there are no files to upload in ${folder.name}/$path."
        if (all.size > MAX_PUSH_FILES) return "ERROR: more than $MAX_PUSH_FILES files. Upload a smaller sub-folder with path."
        // secrets first: nothing leaves the phone if a single file looks sensitive
        val files = ArrayList<Pair<String, ByteArray>>()
        for (p in all) {
            SecretScan.blockedPath(p)?.let { return "BLOCKED: $it. Secrets are never pushed to GitHub. Nothing was uploaded. Move that file out of the folder or ask the user to remove it." }
            val bytes = fs.read(p, MAX_PUSH_FILE_BYTES) ?: continue
            if (bytes.size > MAX_PUSH_FILE_BYTES) return "ERROR: $p is larger than ${MAX_PUSH_FILE_BYTES / 1024 / 1024} MB; GitHub pushes from the phone stay small. Leave it out."
            if (isText(bytes)) SecretScan.find(String(bytes, Charsets.UTF_8))?.let { return "BLOCKED: $p contains $it. Secrets are never pushed to GitHub. Nothing was uploaded. Remove the secret from that file first." }
            files += p to bytes
        }
        if (!ctx.approve("Upload ${files.size} file(s) to GitHub: $repo", "From the phone folder ${folder.name}/$path\nTo ${if (dest.isBlank()) "the top of the repository" else dest}\nThis makes the files part of the repository (public if the repository is public).")) {
            return "DENIED: the user did not approve uploading to GitHub. Nothing was pushed."
        }
        val rel = { p: String -> (if (path.isBlank()) p else p.removePrefix("$path/")).let { if (dest.isBlank()) it else "$dest/$it" } }
        val args = buildJsonObject {
            a.s("branch")?.let { put("branch", it) }
            put("message", a.s("message") ?: "Add files from phone folder ${folder.name}")
            putJsonArray("files") {
                files.forEach { (p, b) ->
                    add(buildJsonObject {
                        put("path", rel(p))
                        if (isText(b)) put("content", String(b, Charsets.UTF_8)) else { put("content", Base64.getEncoder().encodeToString(b)); put("encoding", "base64") }
                        put("overwrite", true)
                    })
                }
            }
        }
        return Tools.pushFiles(gh, repo, args, ctx)
    }
}
