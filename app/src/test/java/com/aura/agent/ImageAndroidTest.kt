package com.aura.agent

import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.*
import com.aura.agent.net.ImageGen
import com.aura.agent.net.Web
import com.aura.agent.net.WebConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** A real, decodable JPEG made with Android's own encoder (Robolectric runs the native graphics code). */
private fun jpegBytes(w: Int = 64, h: Int = 64): ByteArray {
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    for (x in 0 until w) for (y in 0 until h) bmp.setPixel(x, y, if ((x / 8 + y / 8) % 2 == 0) 0xFFE0703A.toInt() else 0xFF262624.toInt())
    return ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
}

private val FAKE_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)

private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

class ImageFilesTest {
    @Test fun png_detection_slugs_and_safe_names() {
        assertTrue(ImageFiles.isPng(PNG_SIG + ByteArray(20))); assertFalse(ImageFiles.isPng(FAKE_JPEG + ByteArray(20)))
        assertEquals("flat-orange-asterisk-app-icon", ImageFiles.slug("Flat orange asterisk, app icon!!"))
        assertEquals("image", ImageFiles.slug("???")); assertTrue(ImageFiles.slug("a".repeat(200)).length <= 40)
        assertEquals("a_b_c.png", ImageFiles.safeName("a/b\\c.png")); assertEquals("file", ImageFiles.safeName("  "))
    }

    @Test fun size_is_clamped_and_the_url_is_encoded() {
        assertEquals(2048, ImageGen.clampSize(99999)); assertEquals(64, ImageGen.clampSize(5)); assertEquals(1024, ImageGen.clampSize(null)); assertEquals(512, ImageGen.clampSize(512))
        val u = ImageGen.urlFor("a red & blue icon/ 100%", 512, 512, 7, "flux")
        assertTrue(u, u.contains("/prompt/a%20red%20%26%20blue%20icon%2F%20100%25?width=512&height=512&nologo=true&seed=7&model=flux"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageToolTest {
    private val queue = ConcurrentLinkedQueue<MockResponse>()

    private fun ok() = MockResponse().setHeader("Content-Type", "image/jpeg").setBody(okio.Buffer().write(jpegBytes()))

    private fun env(): Pair<Mock, App> {
        val m = Mock().start()
        m.webRoutes = { r, _ -> if (r.path!!.startsWith("/img/prompt/")) queue.poll() ?: ok() else null }
        return m to Env.setup(m)
    }

    private fun imgRequests(m: Mock) = m.log.filter { it.startsWith("GET /img/prompt/") }

    @Test fun icon_is_generated_converted_to_png_saved_and_shown_in_the_chat() {
        val (m, app) = env()
        m.script += m.tool("generate_image", """{"prompt":"flat orange asterisk app icon, dark background","width":512,"height":512}""")
        m.script += m.text("here is your icon")
        val r = Env.run(app, "make an icon")
        val t = r.tools.single()
        assertTrue(t.content, !t.isError && t.content.contains("Generated 512x512 PNG"))
        val att = Attachments.parse(t.attachments).single()
        assertEquals("image", att.kind); assertEquals("image/png", att.mime); assertTrue(att.name, att.name.endsWith("-512x512.png"))
        val bytes = File(att.path).readBytes()
        assertTrue("a real PNG file", ImageFiles.isPng(bytes)); assertTrue(File(att.path).path.contains("/generated/"))
        val req = imgRequests(m).single()
        assertTrue(req, req.contains("width=512&height=512&nologo=true") && req.contains("flat%20orange%20asterisk%20app%20icon%2C%20dark%20background"))
    }

    @Test fun sizes_are_clamped_before_the_request() {
        val (m, app) = env()
        m.script += m.tool("generate_image", """{"prompt":"banner","width":99999,"height":5}""")
        m.script += m.text("ok")
        Env.run(app)
        assertTrue(imgRequests(m).single(), imgRequests(m).single().contains("width=2048&height=64"))
    }

    @Test fun png_can_be_committed_straight_into_the_repository() {
        val (m, app) = env()
        m.script += m.tool("generate_image", """{"prompt":"orange asterisk icon","width":1024,"height":1024,"save_to_repo_path":"app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"}""")
        m.script += m.text("committed")
        val r = Env.run(app, "icon into my repo", repo = "octo/r")
        assertTrue(r.tools.single().content, r.tools.single().content.contains("Committed ic_launcher.png to octo/r@main as app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"))
        val put = m.bodies.first { it.first.startsWith("PUT /gh/repos/octo/r/contents/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png") }.second
        val content = java.util.Base64.getDecoder().decode(kotlinx.serialization.json.Json.parseToJsonElement(put).let { (it as kotlinx.serialization.json.JsonObject)["content"]!!.toString().trim('"') })
        assertTrue("the committed file is a PNG", ImageFiles.isPng(content))
        assertEquals("github", runBlocking { app.db.dao().checkpointsForTurn(r.turnId) }.single().kind)
    }

    @Test fun it_can_also_be_saved_to_the_phone() {
        val (m, app) = env()
        m.script += m.tool("generate_image", """{"prompt":"splash banner","save_to_phone":true,"filename":"splash"}""")
        m.script += m.text("saved")
        val c = Env.run(app).tools.single().content
        assertTrue(c, c.contains("Saved to") && c.contains("splash.png"))
    }

    @Test fun busy_service_is_retried_with_backoff_then_succeeds() {
        val (m, app) = env()
        queue += MockResponse().setResponseCode(503); queue += MockResponse().setResponseCode(429); queue += ok()
        m.script += m.tool("generate_image", """{"prompt":"retry me please"}""")
        m.script += m.text("done")
        val c = Env.run(app).tools.single()
        assertTrue(c.content, !c.isError); assertEquals(3, imgRequests(m).size)
    }

    @Test fun it_gives_up_with_a_clear_message_after_four_tries() {
        val (m, app) = env()
        repeat(6) { queue += MockResponse().setResponseCode(503) }
        m.script += m.tool("generate_image", """{"prompt":"never works"}""")
        m.script += m.text("sorry")
        val c = Env.run(app).tools.single()
        assertTrue(c.content, c.isError && c.content.contains("even after 4 tries") && c.content.contains("HTTP 503"))
        assertEquals(4, imgRequests(m).size)
    }

    @Test fun a_slow_service_times_out_and_the_request_is_repeated() {
        val (m, app) = env()
        ImageGen.timeoutSec = 1
        queue += MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE); queue += ok()
        m.script += m.tool("generate_image", """{"prompt":"slow one"}""")
        m.script += m.text("done")
        val t0 = System.currentTimeMillis()
        val c = Env.run(app).tools.single()
        assertTrue(c.content, !c.isError); assertEquals(2, imgRequests(m).size); assertTrue("the first try really waited", System.currentTimeMillis() - t0 >= 900)
    }

    @Test fun a_page_instead_of_an_image_is_not_accepted() {
        val (m, app) = env()
        repeat(6) { queue += MockResponse().setHeader("Content-Type", "text/html").setBody("<html>oops</html>") }
        m.script += m.tool("generate_image", """{"prompt":"html answer"}""")
        m.script += m.text("sorry")
        val c = Env.run(app).tools.single()
        assertTrue(c.content, c.isError && c.content.contains("did not return an image"))
    }

    @Test fun a_refused_request_is_not_retried() {
        val (m, app) = env()
        queue += MockResponse().setResponseCode(400)
        m.script += m.tool("generate_image", """{"prompt":"bad request here"}""")
        m.script += m.text("sorry")
        val c = Env.run(app).tools.single()
        assertTrue(c.content, c.isError && c.content.contains("refused")); assertEquals(1, imgRequests(m).size)
    }

    @Test fun undecodable_images_are_kept_as_they_are_with_a_note() {
        val (m, app) = env()
        queue += MockResponse().setHeader("Content-Type", "image/jpeg").setBody("not really an image at all")
        m.script += m.tool("generate_image", """{"prompt":"broken image data"}""")
        m.script += m.text("ok")
        val t = Env.run(app).tools.single()
        assertTrue(t.content, !t.isError && t.content.contains("could not be converted to PNG"))
        assertTrue(Attachments.parse(t.attachments).single().name.endsWith(".jpg"))
    }

    @Test fun a_very_short_prompt_is_refused() {
        val (m, app) = env()
        m.script += m.tool("generate_image", """{"prompt":"a"}""")
        m.script += m.text("ok")
        assertTrue(Env.run(app).tools.single().isError); assertEquals(0, imgRequests(m).size)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImagePreviewUiTest : UiBase() {
    @Test fun generated_image_shows_in_the_chat_with_share_and_save_buttons() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        val f = File(app.filesDir, "generated/preview-512x512.png").apply { parentFile!!.mkdirs(); writeBytes(PNG_SIG + ByteArray(30)) }
        val att = Attachment(f.name, "image/png", f.length(), f.absolutePath, "image")
        runBlocking {
            val dao = app.db.dao()
            dao.upsertChat(ChatEntity("img", "Image chat", Ids.now(), Ids.now(), titled = true))
            dao.upsertMessage(MessageEntity("u", "img", "user", "make an icon", Ids.now()))
            dao.upsertMessage(MessageEntity("t", "img", "tool", "Generated 512x512 PNG", Ids.now(), toolCallId = "c", toolName = "generate_image", toolArgs = "{}", attachments = Attachments.toJson(listOf(att))))
        }
        rule.runOnUiThread { vm().openChat("img") }
        assertTrue(text("preview-512x512.png"))
        fun tap(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle() }
        tap("file-menu"); tap("file-share")
        tap("file-menu"); tap("file-save")
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end && !vm().attachMsg.value.startsWith("Saved to")) { Thread.sleep(50); rule.waitForIdle() }
        assertTrue(vm().attachMsg.value, vm().attachMsg.value.startsWith("Saved to"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidToolsTest {
    private val m = Mock().start()
    private val app = Env.setup(m).also {
        // FileProvider keeps its folder map in a static cache; every test has a new temporary files folder
        runCatching { androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null).let { c -> (c as MutableMap<*, *>).clear() } }
    }
    private fun ctx(approve: suspend (String, String) -> Boolean = { _, _ -> true }) = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), approve = approve)
    private fun run(name: String, args: String, c: ToolCtx = ctx()) = runBlocking { Tools.execute(c, name, args) }
    private fun gen(name: String, bytes: ByteArray = PNG_SIG + ByteArray(40), dir: String = "generated") = File(app.filesDir, "$dir/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    @Test fun share_sheet_is_opened_for_a_file_with_a_safe_content_uri() {
        gen("pic.png"); app.foreground = true
        val out = run("share_file", """{"name":"pic.png"}""")
        assertTrue(out, out.contains("opened now"))
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action); assertEquals("image/png", send.type)
        val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${app.packageName}.files", uri.authority); assertTrue(uri.scheme == "content")
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun apks_get_the_package_mime_type() {
        gen("app.apk", ByteArray(100), "downloads"); app.foreground = true
        val out = run("share_file", """{"name":"app.apk"}""")
        assertTrue(out, out.contains("opened now"))
        assertEquals("application/vnd.android.package-archive", shadowOf(app).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.type)
    }

    @Test fun in_the_background_a_notification_is_posted_instead_of_a_blocked_screen() {
        gen("pic.png"); app.foreground = false
        val out = run("share_file", """{"name":"pic.png"}""")
        assertTrue(out, out.contains("notification"))
        assertNull("no screen is opened from the background", shadowOf(app).nextStartedActivity)
        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        assertTrue("a notification with a tap action was posted", posted.isNotEmpty() && posted.first().contentIntent != null)
    }

    @Test fun only_files_inside_aura_storage_can_be_shared_or_saved() {
        val secret = File(app.filesDir, "secret.txt").apply { writeText("private") }
        val inside = gen("ok.png")
        val link = File(app.filesDir, "generated/link.png")
        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), secret.toPath()) }
        app.foreground = true
        for (args in listOf("""{"name":"secret.txt"}""", """{"name":"${secret.absolutePath}"}""", """{"name":"${app.filesDir.absolutePath}/generated/../secret.txt"}""", """{"name":"/etc/hosts"}""", """{"name":"../secret.txt"}""", """{"name":"link.png"}""")) {
            val out = run("share_file", args)
            assertTrue("$args -> $out", out.startsWith("ERROR")); assertTrue(run("save_to_phone", args).startsWith("ERROR"))
        }
        assertNull("nothing was opened", shadowOf(app).nextStartedActivity)
        val ok = run("share_file", """{"name":"${inside.absolutePath}"}""")
        assertTrue(ok, ok.contains("opened now"))
    }

    @Test fun the_newest_file_with_that_name_is_used() {
        val old = gen("same.png", PNG_SIG + ByteArray(5), "generated").also { it.setLastModified(1000) }
        val newer = gen("same.png", PNG_SIG + ByteArray(50), "downloads").also { it.setLastModified(5000) }
        assertEquals(newer.canonicalPath, AndroidActions.resolve(app, "same.png")!!.canonicalPath); assertTrue(old.exists())
    }

    @Test fun links_need_approval_and_only_http_https_is_allowed() {
        app.foreground = true
        val asked = mutableListOf<String>()
        val c = ctx { _, d -> asked += d; true }
        val out = run("open_url", """{"url":"https://example.com/docs"}""", c)
        assertTrue(out, out.contains("opened now")); assertEquals(listOf("https://example.com/docs"), asked)
        val view = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, view.action); assertEquals("https://example.com/docs", view.dataString)
        for (bad in listOf("javascript:alert(1)", "file:///etc/hosts", "intent://x#Intent;end", "content://x/y", "tel:12345", "ftp://x.y", "example.com")) {
            assertTrue(bad, run("open_url", """{"url":"$bad"}""", c).startsWith("ERROR")); 
        }
        assertEquals("rejected schemes never reach the approval prompt", 1, asked.size); assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun a_denied_link_is_not_opened_and_guardrails_can_be_switched_off() {
        app.foreground = true
        assertTrue(run("open_url", """{"url":"https://example.com"}""", ctx { _, _ -> false }).startsWith("DENIED")); assertNull(shadowOf(app).nextStartedActivity)
        app.store.setGuardrails(false)
        val c = ctx { _, _ -> error("must not ask") }
        assertTrue(run("open_url", """{"url":"https://example.com"}""", c).contains("opened now"))
    }

    @Test fun files_and_images_can_be_saved_to_the_phone() {
        gen("pic.png"); gen("tool.apk", ByteArray(500), "downloads")
        val a = run("save_to_phone", """{"name":"pic.png"}"""); val b = run("save_to_phone", """{"name":"tool.apk"}""")
        assertTrue(a, a.startsWith("Saved to") && a.contains("pic.png")); assertTrue(b, b.startsWith("Saved to") && b.contains("tool.apk"))
    }

    @Test fun apk_download_then_share_flow() {
        val apk = ByteArray(3000) { (it % 251).toByte() }
        m.webRoutes = { r, _ -> if (r.path == "/dl/Aura.apk") MockResponse().setHeader("Content-Type", "application/vnd.android.package-archive").setBody(okio.Buffer().write(apk)) else null }
        val base = m.server.url("/").toString().trimEnd('/')
        val c = ctx()
        val out = run("download_file", """{"url":"$base/dl/Aura.apk"}""", c)
        assertTrue(out, out.startsWith("Downloaded Aura.apk"))
        assertArrayEquals(apk, File(app.filesDir, "downloads/Aura.apk").readBytes())
        val att = c.produced.single(); assertEquals("binary", att.kind); assertEquals("application/vnd.android.package-archive", att.mime)
        app.foreground = true
        val shared = run("share_file", """{"name":"Aura.apk"}""")
        assertTrue(shared, shared.contains("opened now"))
    }

    @Test fun downloads_cannot_reach_the_local_network() {
        Web.config = WebConfig(allowPrivate = false)
        val base = m.server.url("/").toString().trimEnd('/')
        val out = run("download_file", """{"url":"$base/dl/x.apk"}""")
        assertTrue(out, out.startsWith("ERROR") && out.contains("blocked", true)); assertFalse(File(app.filesDir, "downloads/x.apk").exists())
        assertTrue(run("download_file", """{"url":"file:///etc/passwd"}""").startsWith("ERROR"))
    }

    @Test fun workers_can_research_but_cannot_use_phone_actions() {
        val c = ToolCtx(app, "c", "t", app.newCloudSession(), File("/tmp"), readOnly = true)
        for (n in listOf("share_file", "open_url", "download_file", "generate_image", "save_memory")) assertTrue(n, run(n, """{"name":"x","url":"https://a.b","prompt":"abc","key":"k","value":"v"}""", c).contains("read-only"))
        m.webRoutes = { _, _ -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body><p>research text</p></body></html>") }
        val base = m.server.url("/").toString().trimEnd('/')
        assertTrue(run("web_fetch", """{"url":"$base/web/page"}""", c).contains("research text"))
    }
}
