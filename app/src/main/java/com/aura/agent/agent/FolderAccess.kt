package com.aura.agent.agent

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.aura.agent.data.LocalFolder
import java.io.File

class FolderEntry(val name: String, val isDir: Boolean, val size: Long, val modified: Long)

/** Everything the agent can do inside one connected folder. Paths are always relative to the folder root. */
interface FolderBackend {
    fun list(path: String): List<FolderEntry>?
    fun stat(path: String): FolderEntry?
    fun read(path: String, maxBytes: Int): ByteArray?
    fun write(path: String, bytes: ByteArray)
    fun delete(path: String): Boolean
}

/** Reads at most maxBytes + 1 bytes, so callers can tell a file was cut. (InputStream.readNBytes needs a newer Android.) */
internal fun readCapped(ins: java.io.InputStream, maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var left = maxBytes + 1
    while (left > 0) {
        val n = ins.read(buf, 0, minOf(buf.size, left)); if (n < 0) break
        out.write(buf, 0, n); left -= n
    }
    return out.toByteArray()
}

object FolderPaths {
    /** Splits a relative path and refuses anything that tries to leave the folder. */
    fun segments(path: String): List<String> {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "The path may not contain '..'." }
        return parts
    }
}

/** Folders picked with the system file manager (Storage Access Framework). Works on any provider: internal storage, SD card, cloud drives. */
class SafBackend(ctx: Context, private val tree: Uri) : FolderBackend {
    private val cr = ctx.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(tree)

    private class Doc(val id: String, val name: String, val mime: String, val size: Long, val modified: Long) {
        val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun docUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun children(parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Doc>()
        val cursor = cr.query(uri, cols, android.os.Bundle.EMPTY, null) ?: throw java.io.FileNotFoundException("The folder is not available (its app did not answer).")
        cursor.use { c ->
            while (c.moveToNext()) out += Doc(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty(), if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
        }
        return out
    }

    private fun root() = Doc(rootId, "", DocumentsContract.Document.MIME_TYPE_DIR, 0, 0)

    private fun find(path: String): Doc? {
        var cur = root()
        for (seg in FolderPaths.segments(path)) {
            if (!cur.isDir) return null
            cur = children(cur.id).firstOrNull { it.name == seg } ?: return null
        }
        return cur
    }

    private fun entry(d: Doc) = FolderEntry(d.name, d.isDir, d.size, d.modified)

    override fun list(path: String): List<FolderEntry>? = find(path)?.takeIf { it.isDir }?.let { d -> children(d.id).map(::entry) }
    override fun stat(path: String): FolderEntry? = find(path)?.let(::entry)

    override fun read(path: String, maxBytes: Int): ByteArray? {
        val d = find(path)?.takeIf { !it.isDir } ?: return null
        return cr.openInputStream(docUri(d.id))?.use { ins -> readCapped(ins, maxBytes) }
    }

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun ensureDir(parent: Doc, name: String): Doc {
        children(parent.id).firstOrNull { it.name == name }?.let { return if (it.isDir) it else throw IllegalStateException("$name is a file, not a folder.") }
        val uri = DocumentsContract.createDocument(cr, docUri(parent.id), DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw IllegalStateException("Could not create the folder $name.")
        return Doc(DocumentsContract.getDocumentId(uri), name, DocumentsContract.Document.MIME_TYPE_DIR, 0, 0)
    }

    override fun write(path: String, bytes: ByteArray) {
        val segs = FolderPaths.segments(path)
        require(segs.isNotEmpty()) { "A file path is required." }
        var parent = root()
        for (s in segs.dropLast(1)) parent = ensureDir(parent, s)
        val name = segs.last()
        val existing = children(parent.id).firstOrNull { it.name == name }
        val target = when {
            existing == null -> DocumentsContract.createDocument(cr, docUri(parent.id), mimeFor(name), name) ?: throw IllegalStateException("Could not create $name.")
            existing.isDir -> throw IllegalStateException("$name is a folder.")
            else -> docUri(existing.id)
        }
        cr.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: throw IllegalStateException("Could not open $name for writing.")
    }

    override fun delete(path: String): Boolean {
        if (FolderPaths.segments(path).isEmpty()) return false
        val d = find(path) ?: return false
        return DocumentsContract.deleteDocument(cr, docUri(d.id))
    }
}

/** A plain directory (used for file:// folders and in tests). */
class FileBackend(private val root: File) : FolderBackend {
    private fun resolve(path: String): File {
        val f = FolderPaths.segments(path).fold(root) { acc, s -> File(acc, s) }
        require(f.canonicalPath == root.canonicalPath || f.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "The path leaves the folder." }
        return f
    }
    private fun entry(f: File) = FolderEntry(f.name, f.isDirectory, if (f.isFile) f.length() else 0, f.lastModified())
    override fun list(path: String) = resolve(path).takeIf { it.isDirectory }?.listFiles()?.map(::entry)
    override fun stat(path: String) = resolve(path).takeIf { it.exists() }?.let(::entry)
    override fun read(path: String, maxBytes: Int) = resolve(path).takeIf { it.isFile }?.inputStream()?.use { readCapped(it, maxBytes) }
    override fun write(path: String, bytes: ByteArray) { val f = resolve(path); f.parentFile?.mkdirs(); f.writeBytes(bytes) }
    override fun delete(path: String): Boolean { val f = resolve(path); return f != root && f.exists() && f.deleteRecursively() }
}

/** The built-in phone workspace: a private folder inside Aura that is always there, so work can stay on the phone without picking a folder first. */
object Workspace {
    const val NAME = "workspace"
    const val URI = "aura://workspace"
    val folder = LocalFolder("workspace", NAME, URI, true)
    fun dir(ctx: Context): File = File(ctx.filesDir, "workspace").also { it.mkdirs() }
}

object FolderAccess {
    fun open(ctx: Context, folder: LocalFolder): FolderBackend {
        if (folder.uri == Workspace.URI) return FileBackend(Workspace.dir(ctx))
        val uri = Uri.parse(folder.uri)
        return if (uri.scheme == "file") FileBackend(File(requireNotNull(uri.path))) else SafBackend(ctx, uri)
    }

    /** A readable name for a picked folder (the last part of its document id, e.g. "Backups"). */
    fun displayName(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        return id.substringAfterLast(':').substringAfterLast('/').ifBlank { "Phone folder" }
    }
}
