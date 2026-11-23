package com.aura.agent.data

/** Which files Aura can show inside the app. Everything else can still be downloaded or shared, just not previewed. */
object Previewable {
    const val MAX_TEXT = 150_000
    private val markdown = setOf("md", "markdown")
    private val textLike = setOf(
        "txt", "json", "xml", "csv", "tsv", "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "html", "htm", "css", "scss", "yml", "yaml", "toml", "ini", "cfg",
        "sh", "bat", "gradle", "properties", "sql", "c", "h", "cpp", "hpp", "cs", "go", "rs", "swift", "rb", "php", "dart", "lua", "r", "log", "env", "gitignore",
        "dockerfile", "vue", "svelte", "tf", "svg", "gitattributes", "editorconfig", "conf", "rst", "tex", "diff", "patch", "lock", "mk", "cmake",
    )
    private val raster = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val video = setOf("mp4", "webm", "3gp", "mkv", "m4v")
    private val audio = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr")

    /** "image", "markdown", "text", "pdf", "video" or "audio" when the app can show the file, otherwise null. */
    fun kind(name: String, mime: String, sample: ByteArray = ByteArray(0)): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = name.lowercase()
        return when {
            (mime.startsWith("image/") && !mime.contains("svg") && mime != "image/heic" && mime != "image/avif") || ext in raster -> "image"
            mime == "application/pdf" || ext == "pdf" -> "pdf"
            mime.startsWith("video/") || ext in video -> "video"
            mime.startsWith("audio/") || ext in audio -> "audio"
            ext in markdown || mime == "text/markdown" -> "markdown"
            ext in textLike || mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" || mime == "application/javascript" ||
                base in setOf("dockerfile", "makefile", "readme", "license", "procfile", "gemfile") -> "text"
            ext == "apk" || ext == "zip" || ext == "gz" || ext == "tar" || ext == "7z" || ext == "rar" || ext == "jar" || ext == "aab" -> null
            ext.isEmpty() && sample.isNotEmpty() && looksText(sample) -> "text"
            else -> null
        }
    }

    /** True for text that decodes as UTF-8 with no NUL bytes. */
    fun looksText(sample: ByteArray): Boolean {
        if (sample.isEmpty() || sample.any { it.toInt() == 0 }) return false
        val cut = sample.copyOf(minOf(sample.size, 8192))
        return runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(cut)); true }.getOrElse {
            // a multi-byte character may be cut at the end of the sample
            runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(cut.copyOf(cut.size - 3))); true }.getOrDefault(false)
        }
    }
}
