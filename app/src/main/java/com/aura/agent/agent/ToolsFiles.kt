package com.aura.agent.agent

import com.aura.agent.data.Attachment
import com.aura.agent.data.ImageFiles
import com.aura.agent.data.Previewable
import com.aura.agent.net.GitHub
import com.aura.agent.net.ToolDef
import kotlinx.serialization.json.*
import java.io.File
import java.util.Base64

/**
 * Gives the user a file inside the app, whatever its type: it shows in the chat with Download and Share, and with Preview
 * when the app can display it (text, code, Markdown, images, PDF).
 */
object ToolsFiles {
    val NAMES = setOf("send_file", "send_release_apk")
    private const val MAX_BYTES = 25L * 1024 * 1024

    private fun prop(type: String, desc: String) = buildJsonObject { put("type", type); put("description", desc) }

    val defs = listOf(
        ToolDef(
            "send_release_apk",
            "Download the APK of a GitHub release (the newest one, or a tag) and give it to the user inside the app with Download and Share buttons, plus the public download link to pass on to other people. Use it after a successful build.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    put("repo", prop("string", "owner/name, default the chat's repository"))
                    put("tag", prop("string", "Release tag, default the newest release (pre-releases count)"))
                }
                putJsonArray("required") { }
            },
        ),
        ToolDef(
            "send_file",
            "Give the user a file inside the app: it appears in the chat with Download and Share buttons, and a Preview button when the type can be shown (text, code, Markdown, JSON, CSV, images, PDF). " +
                "Works for any type (apk, zip, docx, pdf...). Give the content directly for text, or a source: a phone folder file, a repository file or a codespace file.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    put("name", prop("string", "File name with extension, e.g. report.md"))
                    put("content", prop("string", "Text content of the file"))
                    put("content_base64", prop("string", "Binary content, base64 encoded"))
                    put("folder", prop("string", "Connected phone folder to read the file from"))
                    put("folder_path", prop("string", "Path of the file inside that phone folder"))
                    put("repo", prop("string", "owner/name to read a file from (files up to 1 MB)"))
                    put("repo_path", prop("string", "Path of the file in the repository"))
                    put("ref", prop("string", "Branch or tag for repo_path"))
                    put("cloud_path", prop("string", "Absolute path of a file in the codespace"))
                }
                putJsonArray("required") { add("name") }
            },
        ),
    )

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** The release to use: the one with the tag, else GitHub's "latest", else (when every release is a pre-release, as with CI builds) the newest one that is not a draft. */
    suspend fun findRelease(gh: GitHub, repo: String, tag: String?): JsonObject? {
        if (!tag.isNullOrBlank()) return runCatching { gh.call("GET", "/repos/$repo/releases/tags/$tag") as? JsonObject }.getOrNull()
        runCatching { (gh.call("GET", "/repos/$repo/releases/latest") as? JsonObject)?.let { return it } }
        val list = runCatching { gh.call("GET", "/repos/$repo/releases?per_page=10") as? JsonArray }.getOrNull() ?: return null
        return list.mapNotNull { it as? JsonObject }.firstOrNull { (it["draft"] as? JsonPrimitive)?.booleanOrNull != true }
    }

    private suspend fun releaseApk(ctx: ToolCtx, a: JsonObject): String {
        val token = ctx.store.githubToken.value
        if (token.isBlank()) return "ERROR: No GitHub token. Ask the user to sign in with GitHub first."
        val gh = GitHub(token, ctx.app.cloudApi)
        val me = ctx.store.githubUser.value
        val repo = (a.s("repo") ?: ctx.activeRepo.ifBlank { null } ?: return "ERROR: repo required (choose one for the chat or pass repo).").let { if ("/" in it) it else "$me/$it" }
        val rel = findRelease(gh, repo, a.s("tag")) ?: return "ERROR: no release found in $repo${a.s("tag")?.let { " with the tag $it" }.orEmpty()}. Run the build workflow first."
        val assets = (rel["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val apks = assets.filter { (it["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().endsWith(".apk", true) }
        if (apks.isEmpty()) return "ERROR: the release ${rel.s("tag_name")} has no .apk file. Files: ${assets.joinToString { it.s("name").orEmpty() }.ifBlank { "none" }}."
        val apk = apks.firstOrNull { it.s("name").orEmpty().contains("release", true) } ?: apks.first()
        val id = (apk["id"] as? JsonPrimitive)?.contentOrNull ?: return "ERROR: GitHub did not give the file's id."
        val link = apk.s("browser_download_url").orEmpty(); val page = rel.s("html_url").orEmpty()
        ctx.emit("Downloading ${apk.s("name")}...\n")
        val bytes = try { gh.bytes("/repos/$repo/releases/assets/$id", 100 * 1024 * 1024, "application/octet-stream") } catch (e: com.aura.agent.net.GhError) { return "ERROR: ${e.message}\nThe public link still works: $link" }
        val dir = File(ctx.app.filesDir, "generated").also { it.mkdirs() }
        val fileName = ImageFiles.safeName(apk.s("name").orEmpty().ifBlank { "app.apk" })
        var dest = File(dir, fileName); var i = 2
        while (dest.exists()) { dest = File(dir, fileName.removeSuffix(".apk") + " ($i).apk"); i++ }
        dest.writeBytes(bytes)
        ctx.produced += Attachment(dest.name, "application/vnd.android.package-archive", dest.length(), dest.absolutePath, "file")
        return "Gave the user ${dest.name} (${com.aura.agent.data.Attachments.human(dest.length())}) from release ${rel.s("tag_name")} of $repo, ready to download or share in the chat.\n" +
            "Public download link for other people: $link\nRelease page: $page"
    }

    suspend fun run(ctx: ToolCtx, name: String, a: JsonObject): String {
        if (ctx.readOnly) return "ERROR: workers are read-only and cannot use $name."
        if (name == "send_release_apk") return try { releaseApk(ctx, a) } catch (e: com.aura.agent.net.GhError) { "ERROR: ${e.message}" } catch (e: java.io.IOException) { "ERROR: ${e.message}" }
        val wanted = a.s("name")?.trim().orEmpty()
        if (wanted.isBlank()) return "ERROR: name required (a file name such as report.md)"
        val fileName = ImageFiles.safeName(wanted.substringAfterLast('/'))
        val bytes: ByteArray = try {
            when {
                a.s("content") != null -> a.s("content")!!.toByteArray()
                a.s("content_base64") != null -> try { Base64.getDecoder().decode(a.s("content_base64")!!.replace(Regex("\\s"), "")) } catch (e: IllegalArgumentException) { return "ERROR: content_base64 is not valid base64." }
                a.s("folder_path") != null -> {
                    val (f, fs) = ToolsFolders.resolve(ctx, a.s("folder"))
                        ?: return "ERROR: pass folder with the name of a connected phone folder (folder_list shows them)."
                    fs.read(a.s("folder_path")!!, MAX_BYTES.toInt()) ?: return "ERROR: ${a.s("folder_path")} does not exist in ${f.name}."
                }
                a.s("repo_path") != null -> {
                    val token = ctx.store.githubToken.value
                    if (token.isBlank()) return "ERROR: No GitHub token. Ask the user to sign in with GitHub first."
                    val gh = GitHub(token, ctx.app.cloudApi)
                    val me = ctx.store.githubUser.value
                    val repo = (a.s("repo") ?: ctx.activeRepo.ifBlank { null } ?: return "ERROR: repo required").let { if ("/" in it) it else "$me/$it" }
                    val ref = a.s("ref")?.let { "?ref=$it" }.orEmpty()
                    val r = gh.call("GET", "/repos/$repo/contents/${a.s("repo_path")}$ref") as? JsonObject ?: return "ERROR: ${a.s("repo_path")} is a folder, not a file."
                    val enc = r["content"]?.jsonPrimitive?.contentOrNull.orEmpty().replace("\n", "")
                    if (enc.isEmpty()) return "ERROR: GitHub does not return files over 1 MB this way. Copy it with cloud_download or the codespace instead."
                    Base64.getDecoder().decode(enc)
                }
                a.s("cloud_path") != null -> ctx.cloud.client().download(a.s("cloud_path")!!)
                else -> return "ERROR: give the file content (content or content_base64) or a source (folder_path, repo_path or cloud_path)."
            }
        } catch (e: com.aura.agent.net.GhError) { return "ERROR: ${if (e.code == 404) "that file does not exist" else e.message}"
        } catch (e: java.io.IOException) { return "ERROR: could not read the source: ${e.message}"
        } catch (e: IllegalArgumentException) { return "ERROR: ${e.message}" }
        if (bytes.size > MAX_BYTES) return "ERROR: the file is ${bytes.size / 1024 / 1024} MB; files up to ${MAX_BYTES / 1024 / 1024} MB can be given in the app."
        val dir = File(ctx.app.filesDir, "generated").also { it.mkdirs() }
        var dest = File(dir, fileName)
        var i = 2
        while (dest.exists()) { dest = File(dir, fileName.substringBeforeLast('.', fileName) + " ($i)" + (fileName.substringAfterLast('.', "").let { if (it.isEmpty() || !fileName.contains('.')) "" else ".$it" })); i++ }
        dest.writeBytes(bytes)
        val mime = AndroidActions.mimeOf(dest)
        val kind = Previewable.kind(dest.name, mime, bytes.copyOf(minOf(bytes.size, 8192)))
        ctx.produced += Attachment(dest.name, mime, dest.length(), dest.absolutePath, if (kind == "image") "image" else "file")
        return "Gave the user ${dest.name} (${Attachments0.human(dest.length())}) in the chat. " +
            (if (kind != null) "They can preview it ($kind), download it or share it." else "It cannot be previewed in the app, but they can download or share it.")
    }

    private object Attachments0 { fun human(n: Long) = com.aura.agent.data.Attachments.human(n) }
}
