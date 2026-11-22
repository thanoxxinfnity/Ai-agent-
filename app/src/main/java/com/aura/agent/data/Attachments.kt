package com.aura.agent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.aura.agent.net.ImagePart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.File

/** An image or file the user attached to a message. The bytes live in the app's private storage. */
@Serializable
data class Attachment(val name: String, val mime: String, val size: Long, val path: String, val kind: String)

object Attachments {
    const val MAX_FILES = 6
    const val MAX_COPY = 25L * 1024 * 1024
    const val MAX_TEXT_INLINE = 100_000
    const val MAX_IMAGE_SIDE = 1568

    private val textExt = setOf(
        "txt", "md", "json", "xml", "csv", "tsv", "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "html", "htm", "css", "scss", "yml", "yaml", "toml", "ini", "cfg",
        "sh", "bat", "gradle", "properties", "sql", "c", "h", "cpp", "hpp", "cs", "go", "rs", "swift", "rb", "php", "dart", "lua", "r", "log", "env", "gitignore", "dockerfile", "vue", "svelte", "tf",
    )

    fun parse(json: String?): List<Attachment> =
        if (json.isNullOrBlank()) emptyList() else runCatching { AppJson.decodeFromString<List<Attachment>>(json) }.getOrDefault(emptyList())

    fun toJson(list: List<Attachment>): String? = if (list.isEmpty()) null else AppJson.encodeToString(list)

    fun kindOf(name: String, mime: String, sample: ByteArray): String {
        if (mime.startsWith("image/") && !mime.contains("svg")) return "image"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (mime.startsWith("text/") || ext in textExt || name.lowercase() in setOf("dockerfile", "makefile", "readme", "license")) return "text"
        if (sample.isNotEmpty() && sample.none { it.toInt() == 0 } && runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(sample)); true }.getOrDefault(false)) return "text"
        return "binary"
    }

    /** Downscales and recompresses a photo so it is cheap to send to a vision model. Falls back to the original bytes. */
    fun prepareImage(bytes: ByteArray): ByteArray = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / sample > MAX_IMAGE_SIDE * 2 || opts.outHeight / sample > MAX_IMAGE_SIDE * 2) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching bytes
        val scale = minOf(1f, MAX_IMAGE_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray().takeIf { it.isNotEmpty() } ?: bytes
    }.getOrDefault(bytes)

    suspend fun import(ctx: Context, uri: Uri): Result<Attachment> = withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            var name = "file"
            var size = -1L
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                }
            }
            name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "file" }
            var mime = cr.getType(uri) ?: "application/octet-stream"
            if (size > MAX_COPY) error("$name is larger than 25 MB.")
            val dir = File(ctx.filesDir, "attachments/${Ids.id().take(8)}").also { it.mkdirs() }
            val sample = cr.openInputStream(uri)?.use { ins -> ByteArray(8192).let { b -> b.copyOf(ins.read(b).coerceAtLeast(0)) } } ?: error("Cannot read $name")
            val kind = kindOf(name, mime, sample)
            var fileName = name
            val target: File
            if (kind == "image") {
                val raw = cr.openInputStream(uri)!!.use { it.readBytes() }
                val jpg = prepareImage(raw)
                if (jpg !== raw) { mime = "image/jpeg"; fileName = name.substringBeforeLast('.') + ".jpg" } else if (!mime.startsWith("image/")) mime = "image/jpeg"
                target = File(dir, fileName).also { it.writeBytes(jpg) }
            } else {
                target = File(dir, fileName)
                var total = 0L
                cr.openInputStream(uri)!!.use { ins -> target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = ins.read(buf); if (n < 0) break; total += n; if (total > MAX_COPY) error("$name is larger than 25 MB."); out.write(buf, 0, n) }
                } }
            }
            Attachment(fileName, mime, target.length(), target.absolutePath, kind)
        }
    }

    /** The text the model sees for a text file; large files are cut with a clear note. */
    fun inlineText(a: Attachment): String {
        val raw = runCatching { File(a.path).readText() }.getOrDefault("")
        val body = if (raw.length > MAX_TEXT_INLINE) raw.take(MAX_TEXT_INLINE) + "\n...[file cut: showing the first $MAX_TEXT_INLINE of ${raw.length} characters. Use cloud_upload tools or read_lines for the rest]" else raw
        return "\n\n--- Attached file: ${a.name} (${a.size} bytes) ---\n$body\n--- end of ${a.name} ---"
    }

    fun image(a: Attachment): ImagePart? = runCatching {
        ImagePart(a.mime.ifBlank { "image/jpeg" }, Base64.encodeToString(File(a.path).readBytes(), Base64.NO_WRAP))
    }.getOrNull()

    fun note(a: Attachment) = "\n\n[Attached file: ${a.name} (${a.mime}, ${a.size} bytes). It cannot be read as text. Use attachment_to_cloud or attachment_to_repo to put it where it is needed.]"

    fun human(size: Long) = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "%.1f MB".format(size / 1048576.0)
    }
}
