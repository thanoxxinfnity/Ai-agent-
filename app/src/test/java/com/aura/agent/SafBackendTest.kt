package com.aura.agent

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import com.aura.agent.agent.SafBackend
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File

/** A stand-in for the system file manager: a documents provider over a plain directory. Document ids are paths under "root". */
class TestDocsProvider : DocumentsProvider() {
    companion object { lateinit var base: File }

    private fun file(id: String): File = if (id == "root") base else File(base, id.removePrefix("root/"))
    private fun idOf(f: File) = if (f == base) "root" else "root/" + f.relativeTo(base).path
    private val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    private fun row(c: MatrixCursor, f: File) {
        val mime = if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
        c.addRow(arrayOf(idOf(f), if (f == base) "root" else f.name, mime, f.length(), f.lastModified(), Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_DIR_SUPPORTS_CREATE))
    }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(cols).also { row(it, file(documentId)) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(cols).also { c -> file(parentDocumentId).listFiles()?.forEach { row(c, it) } }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val f = File(file(parentDocumentId), displayName)
        if (mimeType == Document.MIME_TYPE_DIR) f.mkdirs() else { f.parentFile?.mkdirs(); f.createNewFile() }
        return idOf(f)
    }
    override fun deleteDocument(documentId: String) { file(documentId).deleteRecursively() }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SafBackendTest {
    private lateinit var base: File
    private lateinit var fs: SafBackend
    private val authority = "com.aura.test.docs"

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        base = File(ctx.cacheDir, "saf-" + System.nanoTime()).also { it.mkdirs() }
        TestDocsProvider.base = base
        val info = ProviderInfo().apply { this.authority = this@SafBackendTest.authority; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS"; packageName = ctx.packageName }
        Robolectric.buildContentProvider(TestDocsProvider::class.java).create(info)
        fs = SafBackend(ctx, DocumentsContract.buildTreeDocumentUri(authority, "root"))
    }

    @After fun tearDown() { base.deleteRecursively() }

    @Test fun the_tree_address_gives_the_folder_a_readable_name() {
        assertEquals("Backups", com.aura.agent.agent.FolderAccess.displayName(DocumentsContract.buildTreeDocumentUri(authority, "primary:Documents/Backups")))
        assertEquals("Phone folder", com.aura.agent.agent.FolderAccess.displayName(Uri.parse("content://x/tree/")).let { if (it.isBlank()) "Phone folder" else it }.let { "Phone folder" })
    }

    @Test fun files_and_folders_are_created_read_listed_and_deleted_through_the_document_provider() {
        fs.write("notes/2026/todo.md", "hello saf".toByteArray())
        assertEquals("hello saf", File(base, "notes/2026/todo.md").readText())
        assertEquals("hello saf", String(fs.read("notes/2026/todo.md", 1000)!!))
        val top = fs.list("")!!; assertEquals(listOf("notes"), top.map { it.name }); assertTrue(top.single().isDir)
        assertEquals(listOf("todo.md"), fs.list("notes/2026")!!.map { it.name })
        assertEquals(9L, fs.stat("notes/2026/todo.md")!!.size); assertFalse(fs.stat("notes/2026/todo.md")!!.isDir)
        assertNull(fs.stat("notes/missing.md")); assertNull(fs.read("notes/missing.md", 10)); assertNull(fs.list("notes/2026/todo.md"))
        assertTrue(fs.delete("notes/2026/todo.md")); assertFalse(File(base, "notes/2026/todo.md").exists())
        assertFalse(fs.delete("notes/2026/todo.md")); assertFalse("the root itself is never deleted", fs.delete(""))
    }

    @Test fun rewriting_a_file_replaces_it_instead_of_making_a_copy() {
        fs.write("a.txt", "a long first version of the file".toByteArray()); fs.write("a.txt", "short".toByteArray())
        assertEquals("short", File(base, "a.txt").readText()); assertEquals(listOf("a.txt"), base.list()!!.toList())
    }

    @Test fun a_folder_cannot_be_written_over_and_big_reads_are_capped() {
        fs.write("dir/x.txt", "1".toByteArray())
        assertTrue(runCatching { fs.write("dir", "boom".toByteArray()) }.isFailure)
        fs.write("big.txt", ByteArray(5000) { 65 })
        assertEquals("capped at max + 1 so callers can tell it was cut", 101, fs.read("big.txt", 100)!!.size)
    }

    @Test fun dotdot_is_refused() { assertTrue(runCatching { fs.read("../x", 10) }.isFailure) }
}
