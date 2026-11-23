package com.aura.agent

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.LocalFolder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

private const val FAKE_SK2 = "sk-ZZfolderFAKEvalue1234567890abcdefXYZ"

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FolderToolsTest {
    private val m = Mock().start()
    private val app = Env.setup(m)
    private lateinit var dir: File
    private lateinit var dir2: File

    @Before fun folders() {
        app.extras.folders.value.forEach { app.extras.removeFolder(it.id) }
        dir = File(app.cacheDir, "docs-" + System.nanoTime()).also { it.mkdirs() }
        dir2 = File(app.cacheDir, "other-" + System.nanoTime()).also { it.mkdirs() }
        app.extras.addFolder("Docs", Uri.fromFile(dir).toString())
    }

    private fun ctx(approve: suspend (String, String) -> Boolean = { _, _ -> true }, readOnly: Boolean = false) =
        ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), approve = approve, readOnly = readOnly, activeRepo = "octo/r")
    private fun run(name: String, args: String, c: ToolCtx = ctx()) = runBlocking { Tools.execute(c, name, args) }
    private fun esc(s: String) = org.json.JSONObject.quote(s)

    @Test fun listing_shows_connected_and_disconnected_folders() {
        assertTrue(run("folder_list", "{}").contains("Docs: connected"))
        app.extras.setFolderEnabled(app.extras.folders.value.single().id, false)
        assertTrue(run("folder_list", "{}").contains("disconnected by the user"))
        app.extras.folders.value.forEach { app.extras.removeFolder(it.id) }
        assertTrue(run("folder_list", "{}").contains("No phone folder is connected"))
    }

    @Test fun write_read_edit_search_list_and_delete_work_end_to_end() {
        assertTrue(run("folder_write", """{"folder":"Docs","path":"notes/todo.md","content":${esc("line one\nline two\nline three")}}""").startsWith("Created notes/todo.md"))
        assertEquals("1: line one\n2: line two\n3: line three", run("folder_read", """{"folder":"Docs","path":"notes/todo.md"}"""))
        assertEquals("2: line two", run("folder_read", """{"folder":"Docs","path":"notes/todo.md","start_line":2,"end_line":2}"""))
        assertEquals("Edited notes/todo.md in Docs.", run("folder_edit", """{"folder":"Docs","path":"notes/todo.md","old_string":"line two","new_string":"line 2"}"""))
        assertEquals("line one\nline 2\nline three", File(dir, "notes/todo.md").readText())
        assertTrue(run("folder_list", """{"folder":"Docs"}""").contains("notes/"))
        assertTrue(run("folder_list", """{"folder":"Docs","path":"notes"}""").contains("todo.md"))
        assertEquals("notes/todo.md:2: line 2", run("folder_search", """{"folder":"docs","query":"LINE 2"}"""))
        assertEquals("No match.", run("folder_search", """{"folder":"Docs","query":"zebra"}"""))
        assertEquals("Deleted notes/todo.md.", run("folder_delete", """{"folder":"Docs","path":"notes/todo.md"}"""))
        assertFalse(File(dir, "notes/todo.md").exists())
    }

    @Test fun paths_cannot_leave_the_folder() {
        File(dir.parentFile, "outside.txt").writeText("private")
        for (p in listOf("../outside.txt", "a/../../outside.txt")) {
            assertTrue(run("folder_read", """{"folder":"Docs","path":"$p"}""").startsWith("ERROR: The path may not contain"))
            assertTrue(run("folder_write", """{"folder":"Docs","path":"$p","content":"x"}""").startsWith("ERROR"))
        }
        assertEquals("private", File(dir.parentFile, "outside.txt").readText())
    }

    @Test fun unknown_disconnected_and_ambiguous_folders_get_clear_errors() {
        assertTrue(run("folder_read", """{"folder":"Nope","path":"a"}""").contains("no connected folder named 'Nope'"))
        val id = app.extras.folders.value.single().id
        app.extras.setFolderEnabled(id, false)
        assertTrue(run("folder_read", """{"folder":"Docs","path":"a"}""").contains("disconnected"))
        app.extras.setFolderEnabled(id, true)
        app.extras.addFolder("Second", Uri.fromFile(dir2).toString())
        assertTrue(run("folder_list", """{"path":"x","folder":""}""").let { it.contains("Docs") && it.contains("Second") })
        assertTrue(run("folder_read", """{"folder":"","path":"a"}""").contains("several folders are connected"))
    }

    @Test fun several_folders_work_side_by_side() {
        app.extras.addFolder("Second", Uri.fromFile(dir2).toString())
        run("folder_write", """{"folder":"Docs","path":"a.txt","content":"in docs"}"""); run("folder_write", """{"folder":"Second","path":"a.txt","content":"in second"}""")
        assertEquals("1: in docs", run("folder_read", """{"folder":"Docs","path":"a.txt"}""")); assertEquals("1: in second", run("folder_read", """{"folder":"Second","path":"a.txt"}"""))
        assertEquals("duplicate names stay unique", "Docs (2)", app.extras.addFolder("Docs", "file:///tmp/zzz").name)
    }

    @Test fun replacing_and_deleting_ask_the_user_and_a_no_changes_nothing() {
        File(dir, "keep.txt").writeText("original")
        val asked = ArrayList<String>(); val no = ctx(approve = { t, _ -> asked += t; false })
        assertTrue(run("folder_write", """{"folder":"Docs","path":"keep.txt","content":"new"}""", no).startsWith("DENIED"))
        assertTrue(run("folder_delete", """{"folder":"Docs","path":"keep.txt"}""", no).startsWith("DENIED"))
        assertEquals("original", File(dir, "keep.txt").readText()); assertEquals(2, asked.size)
        val fresh = ArrayList<String>()
        run("folder_write", """{"folder":"Docs","path":"brand-new.txt","content":"x"}""", ctx(approve = { t, _ -> fresh += t; true }))
        assertTrue("a new file needs no question", fresh.isEmpty())
    }

    @Test fun placeholders_binary_files_and_ambiguous_edits_are_rejected() {
        assertTrue(run("folder_write", """{"folder":"Docs","path":"a.kt","content":${esc("fun a() {}\n// rest of code")}}""").startsWith("ERROR: the content contains a placeholder"))
        File(dir, "img.bin").writeBytes(byteArrayOf(1, 0, 2, 0))
        assertTrue(run("folder_read", """{"folder":"Docs","path":"img.bin"}""").contains("binary"))
        File(dir, "dup.txt").writeText("x x")
        assertTrue(run("folder_edit", """{"folder":"Docs","path":"dup.txt","old_string":"x","new_string":"y"}""").contains("appears 2 times"))
        assertTrue(run("folder_edit", """{"folder":"Docs","path":"dup.txt","old_string":"zzz","new_string":"y"}""").contains("not found"))
        assertEquals("x x", File(dir, "dup.txt").readText())
    }

    @Test fun workers_may_read_but_not_change_phone_folders() {
        File(dir, "r.txt").writeText("hello")
        val w = ctx(readOnly = true)
        assertEquals("1: hello", run("folder_read", """{"folder":"Docs","path":"r.txt"}""", w))
        assertTrue(run("folder_write", """{"folder":"Docs","path":"w.txt","content":"x"}""", w).startsWith("ERROR: workers are read-only"))
        assertFalse(File(dir, "w.txt").exists())
    }

    @Test fun backup_copies_a_repository_into_the_folder_without_pushing_anything() {
        m.tarball = tarGz("r-1/README.md" to "# hello", "r-1/src/A.kt" to "package a", "r-1/docs/guide/intro.md" to "intro")
        val out = run("backup_repo_to_folder", """{"folder":"Docs"}""")
        assertTrue(out, out.startsWith("Backed up octo/r (3 files") && out.contains("Nothing was pushed"))
        assertEquals("package a", File(dir, "r/src/A.kt").readText()); assertEquals("intro", File(dir, "r/docs/guide/intro.md").readText())
        assertTrue(m.log.none { it.startsWith("POST") || it.startsWith("PUT") || it.startsWith("PATCH") || it.startsWith("DELETE") })
        run("backup_repo_to_folder", """{"folder":"Docs","subfolder":"backups/2026"}""")
        assertTrue(File(dir, "backups/2026/README.md").exists())
    }

    @Test fun pushing_a_folder_needs_the_users_yes_and_uploads_only_what_was_asked() {
        File(dir, "site").mkdirs(); File(dir, "site/index.html").writeText("<h1>hi</h1>"); File(dir, "site/app.js").writeText("console.log(1)"); File(dir, "notes.md").writeText("private notes")
        val asked = ArrayList<String>()
        val out = run("push_folder_to_repo", """{"folder":"Docs","repo":"octo/r","path":"site","repo_path":"web"}""", ctx(approve = { t, d -> asked += "$t | $d"; true }))
        assertTrue(out, out.startsWith("Pushed 2 file(s)")); assertEquals(1, asked.size); assertTrue(asked[0].contains("Upload 2 file(s)"))
        assertEquals(2, m.count("POST /gh/repos/octo/r/git/blobs"))
        val tree = m.bodies.first { it.first == "POST /gh/repos/octo/r/git/trees" }.second
        assertTrue(tree, tree.contains("web/index.html") && tree.contains("web/app.js") && !tree.contains("notes.md"))
    }

    @Test fun declining_the_upload_pushes_nothing() {
        File(dir, "a.txt").writeText("hello")
        assertTrue(run("push_folder_to_repo", """{"folder":"Docs","repo":"octo/r"}""", ctx(approve = { _, _ -> false })).startsWith("DENIED"))
        assertEquals(0, m.count("POST /gh/repos/octo/r/git/blobs"))
    }

    @Test fun a_secret_anywhere_in_the_folder_stops_the_whole_upload_before_the_question() {
        File(dir, "ok.txt").writeText("fine"); File(dir, ".env").writeText("A=1")
        var asked = false
        val a = run("push_folder_to_repo", """{"folder":"Docs","repo":"octo/r"}""", ctx(approve = { _, _ -> asked = true; true }))
        assertTrue(a, a.startsWith("BLOCKED") && a.contains(".env") && a.contains("Nothing was uploaded")); assertFalse(asked)
        File(dir, ".env").delete(); File(dir, "config.kt").writeText("val k = \"$FAKE_SK2\"")
        val b = run("push_folder_to_repo", """{"folder":"Docs","repo":"octo/r"}""")
        assertTrue(b, b.startsWith("BLOCKED") && b.contains("config.kt") && !b.contains(FAKE_SK2))
        assertEquals(0, m.count("POST /gh/repos/octo/r/git/blobs"))
    }

    @Test fun hidden_build_folders_are_left_out_and_binary_files_go_as_base64() {
        File(dir, "node_modules/x").mkdirs(); File(dir, "node_modules/x/i.js").writeText("1"); File(dir, ".git").mkdirs(); File(dir, ".git/config").writeText("x")
        File(dir, "logo.png").writeBytes(byteArrayOf(-119, 80, 78, 71, 0, 1, 2)); File(dir, "main.kt").writeText("fun main() {}")
        val out = run("push_folder_to_repo", """{"folder":"Docs","repo":"octo/r"}""")
        assertTrue(out, out.startsWith("Pushed 2 file(s)"))
        assertTrue(m.bodies.filter { it.first == "POST /gh/repos/octo/r/git/blobs" }.any { it.second.contains("base64") })
    }

    @Test fun the_model_can_write_into_a_phone_folder_during_a_chat() {
        m.script += m.tool("folder_write", """{"folder":"Docs","path":"summary.md","content":"# done"}"""); m.script += m.text("Saved it to your phone.")
        val r = Env.run(app, "save a summary to my Docs folder")
        assertEquals("# done", File(dir, "summary.md").readText()); assertEquals("Saved it to your phone.", r.lastAssistant.content)
    }
}

class FolderPathsTest {
    @Test fun segments_are_cleaned_and_dotdot_is_refused() {
        assertEquals(listOf("a", "b", "c.txt"), FolderPaths.segments("/a//b/./c.txt")); assertEquals(listOf("a", "b"), FolderPaths.segments("a\\b"))
        assertTrue(runCatching { FolderPaths.segments("a/../b") }.isFailure)
    }
}
