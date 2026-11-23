package com.aura.agent.agent

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.aura.agent.App
import com.aura.agent.MainActivity
import com.aura.agent.R
import java.io.File

/** Safe phone actions: share a file, open a link, save to the gallery or Downloads. */
object AndroidActions {
    val ROOTS = listOf("generated", "downloads", "cloud_out", "attachments")

    fun roots(app: App): List<File> = ROOTS.map { File(app.filesDir, it).canonicalFile }

    /** Only files the app itself created or downloaded may be shared; nothing else on the phone is reachable. */
    fun isAllowed(app: App, f: File): Boolean = runCatching {
        val c = f.canonicalFile
        c.isFile && roots(app).any { c.path.startsWith(it.path + File.separator) }
    }.getOrDefault(false)

    /** A file by exact path (inside the allowed folders) or by name (newest match). */
    fun resolve(app: App, nameOrPath: String): File? {
        val t = nameOrPath.trim()
        if (t.isEmpty()) return null
        if (t.contains('/')) return File(t).takeIf { isAllowed(app, it) }
        return roots(app).flatMap { r -> r.walkTopDown().maxDepth(3).filter { it.isFile && it.name.equals(t, true) }.toList() }
            .maxByOrNull { it.lastModified() }?.takeIf { isAllowed(app, it) }
    }

    private val known = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif", "svg" to "image/svg+xml",
        "pdf" to "application/pdf", "apk" to "application/vnd.android.package-archive", "zip" to "application/zip", "json" to "application/json",
        "txt" to "text/plain", "md" to "text/markdown", "html" to "text/html", "csv" to "text/csv", "mp4" to "video/mp4", "mp3" to "audio/mpeg", "webm" to "video/webm", "3gp" to "video/3gpp", "mkv" to "video/x-matroska", "m4a" to "audio/mp4", "wav" to "audio/wav", "ogg" to "audio/ogg", "flac" to "audio/flac", "opus" to "audio/ogg", "aac" to "audio/aac",
    )

    fun mimeOf(f: File): String {
        val ext = f.extension.lowercase()
        return known[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun launch(app: App, intent: Intent, title: String, text: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (app.foreground) {
            return try { app.startActivity(intent); "opened now" } catch (e: Exception) { "could not open it: ${e.message}" }
        }
        // Android does not let a background app open screens; a tap on a notification does the same job
        val pi = PendingIntent.getActivity(app, (title + text).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, App.CH_ASK).setSmallIcon(R.drawable.ic_sparkle).setContentTitle(title).setContentText(text.take(120))
            .setContentIntent(pi).setAutoCancel(true).build()
        app.getSystemService(NotificationManager::class.java).notify((title + text).hashCode(), n)
        return "Aura is in the background, so a notification was posted. Tap it to continue"
    }

    fun share(app: App, f: File): String {
        if (!isAllowed(app, f)) return "ERROR: that file cannot be shared."
        val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Share ${f.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        chooser.clipData = android.content.ClipData.newRawUri(f.name, uri)
        return "Share sheet for ${f.name}: " + launch(app, chooser, "Share ${f.name}", "Tap to choose an app")
    }

    fun openUrl(app: App, url: String): String {
        val u = Uri.parse(url.trim())
        if (u.scheme != "http" && u.scheme != "https") return "ERROR: only http and https links can be opened."
        return "Link ${url.trim().take(100)}: " + launch(app, Intent(Intent.ACTION_VIEW, u), "Open link", url.trim())
    }

    /** Images go to Pictures/Aura, everything else (APKs, zips) to Downloads/Aura. */
    fun saveToPhone(app: App, f: File): String {
        if (!isAllowed(app, f)) return "ERROR: that file cannot be saved."
        val mime = mimeOf(f)
        val image = mime.startsWith("image/")
        if (Build.VERSION.SDK_INT >= 29) {
            val r = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/Aura")
                }
                val coll = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri = app.contentResolver.insert(coll, values) ?: error("the gallery did not accept the file")
                app.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                (if (image) "Pictures" else "Downloads") + "/Aura/" + f.name
            }
            r.getOrNull()?.let { return "Saved to $it on the phone." }
        }
        // older Android (or a failed gallery insert): the app's own public folder, which needs no permission
        val dir = app.getExternalFilesDir(if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) ?: return "ERROR: no storage available."
        dir.mkdirs()
        val dest = File(dir, f.name)
        f.copyTo(dest, overwrite = true)
        return "Saved to ${dest.absolutePath} on the phone."
    }
}
