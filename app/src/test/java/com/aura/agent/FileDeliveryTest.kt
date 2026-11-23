package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

class PreviewableTest {
    private fun k(name: String, mime: String = "application/octet-stream", sample: ByteArray = ByteArray(0)) = Previewable.kind(name, mime, sample)

    @Test fun supported_types_are_previewed() {
        assertEquals("image", k("a.png", "image/png")); assertEquals("image", k("b.JPG", "image/jpeg")); assertEquals("image", k("c.webp", "image/webp")); assertEquals("image", k("d.gif"))
        assertEquals("pdf", k("r.pdf", "application/pdf")); assertEquals("pdf", k("x.PDF"))
        assertEquals("markdown", k("README.md", "text/markdown")); assertEquals("markdown", k("notes.markdown"))
        for (n in listOf("a.txt", "Main.kt", "app.py", "data.json", "t.csv", "page.html", "style.css", "build.gradle", "conf.yaml", "x.log", "icon.svg", "Dockerfile", "Makefile", "run.sh", ".gitignore")) assertEquals(n, "text", k(n))
        assertEquals("text", k("data", "application/json")); assertEquals("text", k("notes", "text/plain"))
    }

    @Test fun unsupported_types_are_not_previewed() {
        for (n in listOf("app.apk", "bundle.zip", "doc.docx", "sheet.xlsx", "slides.pptx", "movie.mp4", "song.mp3", "lib.so", "photo.heic", "a.tar.gz", "tool.jar", "x.aab", "blob.bin")) assertNull(n, k(n))
        assertNull(k("pic.heic", "image/heic")); assertNull(k("doc.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }

    @Test fun an_unknown_extension_is_decided_by_its_content() {
        assertEquals("text", k("notes", sample = "just words\nand more".toByteArray())); assertNull(k("notes", sample = byteArrayOf(1, 0, 2, 3)))
        assertEquals("text", k("hindi", sample = "नमस्ते दुनिया".toByteArray()))
        assertNull(k("something.weird", sample = "words".toByteArray()))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SendFileToolTest {
    private val m = Mock().start()
    private val app = Env.setup(m)
    private fun ctx(readOnly: Boolean = false) = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), readOnly = readOnly, activeRepo = "octo/r")
    private fun run(args: String, c: ToolCtx = ctx()) = runBlocking { Tools.execute(c, "send_file", args) }
    private fun esc(s: String) = org.json.JSONObject.quote(s)

    @Test fun text_becomes_a_file_in_the_chat_that_can_be_previewed() {
        val c = ctx()
        val out = run("""{"name":"report.md","content":${esc("# Title\n\nHello")}}""", c)
        assertTrue(out, out.contains("report.md") && out.contains("preview it (markdown)"))
        val a = c.produced.single(); assertEquals("report.md", a.name); assertEquals("file", a.kind); assertEquals("# Title\n\nHello", File(a.path).readText())
        assertTrue(AndroidActions.isAllowed(app, File(a.path)))
    }

    @Test fun binary_files_of_any_type_are_given_without_a_preview() {
        val c = ctx()
        val bytes = byteArrayOf(80, 75, 3, 4, 0, 1, 2, 3)
        val out = run("""{"name":"app-debug.apk","content_base64":"${Base64.getEncoder().encodeToString(bytes)}"}""", c)
        assertTrue(out, out.contains("cannot be previewed") && out.contains("download or share"))
        val a = c.produced.single(); assertEquals("application/vnd.android.package-archive", a.mime); assertArrayEquals(bytes, File(a.path).readBytes())
    }

    @Test fun images_are_marked_as_images_and_names_stay_unique_and_safe() {
        val c = ctx(); val png = Base64.getEncoder().encodeToString(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0))
        run("""{"name":"../../etc/pic.png","content_base64":"$png"}""", c); run("""{"name":"pic.png","content_base64":"$png"}""", c); run("""{"name":"pic.png","content_base64":"$png"}""", c)
        assertEquals(listOf("pic.png", "pic (2).png", "pic (3).png"), c.produced.map { it.name }); assertTrue(c.produced.all { it.kind == "image" })
        assertTrue(c.produced.all { File(it.path).parentFile!!.name == "generated" })
    }

    @Test fun a_phone_folder_file_a_repo_file_and_a_codespace_file_can_be_given() {
        val dir = File(app.cacheDir, "sf").also { it.mkdirs() }; File(dir, "notes.txt").writeText("from the phone")
        app.extras.folders.value.forEach { app.extras.removeFolder(it.id) }; app.extras.addFolder("Docs", android.net.Uri.fromFile(dir).toString())
        val c = ctx()
        assertTrue(run("""{"name":"notes.txt","folder":"Docs","folder_path":"notes.txt"}""", c).contains("notes.txt")); assertEquals("from the phone", File(c.produced.last().path).readText())
        assertTrue(run("""{"name":"A.kt","repo":"r","repo_path":"src/A.kt"}""", c).contains("A.kt")); assertTrue(File(c.produced.last().path).readText().contains("package a"))
        m.fileText = "from the codespace"
        assertTrue(run("""{"name":"out.log","cloud_path":"/workspaces/x/out.log"}""", c).contains("out.log")); assertEquals("from the codespace", File(c.produced.last().path).readText())
    }

    @Test fun clear_errors_for_missing_input_and_missing_sources() {
        assertTrue(run("""{"content":"x"}""").startsWith("ERROR: name required"))
        assertTrue(run("""{"name":"a.txt"}""").contains("give the file content"))
        assertTrue(run("""{"name":"a.bin","content_base64":"%%%"}""").contains("not valid base64"))
        app.extras.folders.value.forEach { app.extras.removeFolder(it.id) }
        assertTrue("with no folder connected the built-in workspace is used", run("""{"name":"a.txt","folder_path":"x"}""").contains("does not exist in workspace"))
        assertTrue(run("""{"name":"a.txt","repo":"r","repo_path":"nope/missing.txt"}""").startsWith("ERROR"))
        assertEquals(0, ctx().produced.size)
    }

    @Test fun workers_cannot_hand_out_files() { assertTrue(run("""{"name":"a.txt","content":"x"}""", ctx(readOnly = true)).startsWith("ERROR: workers are read-only")) }

    @Test fun in_a_chat_the_file_is_attached_to_the_tool_step() {
        m.script += m.tool("send_file", """{"name":"todo.csv","content":"a,b\n1,2"}"""); m.script += m.text("Here is your file.")
        val r = Env.run(app, "give me a csv")
        val att = Attachments.parse(r.tools.single().attachments).single()
        assertEquals("todo.csv", att.name); assertEquals("a,b\n1,2", File(att.path).readText()); assertEquals("Here is your file.", r.lastAssistant.content)
    }

    @Test fun the_tool_is_registered_and_the_prompt_explains_it() {
        assertEquals(1, Tools.defs.count { it.name == "send_file" }); assertNotEquals("send_file", Tools.label("send_file"))
        assertTrue(AgentRunner.SYSTEM.contains("call send_file") && AgentRunner.SYSTEM.contains("Preview for text, code, Markdown, JSON, CSV, images and PDF"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FileCardUiTest : UiBase() {
    private fun seed(id: String, f: File, mime: String, kind: String) {
        val app = rule.activity.application as App
        val att = Attachment(f.name, mime, f.length(), f.absolutePath, kind)
        runBlocking {
            val dao = app.db.dao()
            dao.upsertChat(ChatEntity(id, "Chat $id", Ids.now(), Ids.now(), titled = true))
            dao.upsertMessage(MessageEntity("u$id", id, "user", "give me ${f.name}", Ids.now()))
            dao.upsertMessage(MessageEntity("t$id", id, "tool", "Gave the user ${f.name}", Ids.now(), toolCallId = "c$id", toolName = "send_file", toolArgs = "{}", attachments = Attachments.toJson(listOf(att))))
        }
        rule.runOnUiThread { vm().openChat(id) }; rule.waitForIdle()
        assertTrue("chat $id is on screen", text("Gave the user ${f.name}") || text("give me ${f.name}") || text(f.name))
    }
    private fun file(name: String, bytes: ByteArray) = File((rule.activity.application as App).filesDir, "generated/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    @Test fun files_show_a_preview_only_when_the_type_is_supported_and_the_preview_works() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        // text: code
        seed("t1", file("Main.kt", "fun main() {\n    println(\"hi\")\n}".toByteArray()), "text/x-kotlin", "file")
        assertTrue(text("Main.kt")); rule.onNodeWithTag("file-preview").assertExists(); rule.onNodeWithTag("file-save").assertExists(); rule.onNodeWithTag("file-share").assertExists()
        assertTrue(text("Download"))
        rule.onNodeWithTag("file-preview").performClick(); rule.waitForIdle()
        assertTrue("dialog", text("preview: text")); assertTrue("content", text("println"))
        rule.onNodeWithTag("file-preview-close").performClick(); rule.waitForIdle()
        assertTrue("closed", rule.onAllNodesWithTag("file-preview-dialog").fetchSemanticsNodes().isEmpty())
        // json is pretty printed
        seed("t2", file("data.json", """{"a":1,"b":[1,2]}""".toByteArray()), "application/json", "file")
        rule.onNodeWithTag("file-preview").performClick(); rule.waitForIdle()
        assertTrue(text("\"a\": 1")); rule.onNodeWithTag("file-preview-close").performClick(); rule.waitForIdle()
        // markdown is rendered
        seed("t3", file("notes.md", "# Big heading\n\nSome words".toByteArray()), "text/markdown", "file")
        rule.onNodeWithTag("file-preview").performClick(); rule.waitForIdle()
        assertTrue("md dialog", text("preview: markdown")); assertTrue("md body", text("Some words")); rule.onNodeWithTag("file-preview-close").performClick(); rule.waitForIdle()
        // long text is cut with a note
        seed("t4", file("big.log", ("x".repeat(79) + "\n").repeat(Previewable.MAX_TEXT / 80 + 100).toByteArray()), "text/plain", "file")
        rule.onNodeWithTag("file-preview").performClick(); rule.waitForIdle()
        assertTrue(text("Preview cut at")); rule.onNodeWithTag("file-preview-close").performClick(); rule.waitForIdle()
        // an apk cannot be previewed but can be downloaded and shared
        seed("t5", file("app.apk", byteArrayOf(80, 75, 3, 4, 1, 2)), "application/vnd.android.package-archive", "file")
        assertTrue(text("app.apk")); assertTrue("no preview button for an unsupported type", rule.onAllNodesWithTag("file-preview").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("file-save").assertExists(); rule.onNodeWithTag("file-share").assertExists()
    }
}
