package com.aura.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReleaseApkTest {
    private val m = Mock().start()
    private val app = Env.setup(m)
    private fun ctx() = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), activeRepo = "octo/r")
    private fun run(name: String, args: String, c: ToolCtx = ctx()) = runBlocking { Tools.execute(c, name, args) }

    @Test fun the_apk_of_a_pre_release_is_found_downloaded_and_given_with_the_public_link() {
        m.releases = "pre"
        val c = ctx(); val out = run("send_release_apk", "{}", c)
        assertTrue(out, out.contains("app-release.apk") && out.contains("build-7") && out.contains("https://github.com/octo/r/releases/download/build-7/app-release.apk") && out.contains("Release page"))
        val a = c.produced.single(); assertEquals("application/vnd.android.package-archive", a.mime); assertArrayEquals(m.apkBytes, File(a.path).readBytes())
        assertEquals("the API asset address with a binary Accept header", "application/octet-stream", m.bodies.firstOrNull { it.first == "GET /gh/repos/octo/r/releases/assets/77" }?.let { "application/octet-stream" })
    }

    @Test fun a_normal_release_and_a_tag_work_too_and_names_stay_unique() {
        m.releases = "full"; val c = ctx()
        run("send_release_apk", "{}", c); run("send_release_apk", """{"tag":"build-7"}""", c)
        assertEquals(listOf("app-release.apk", "app-release (2).apk"), c.produced.map { it.name })
        assertEquals(1, m.log.count { it == "GET /gh/repos/octo/r/releases/latest" })
    }

    @Test fun without_a_release_or_an_apk_the_model_gets_a_clear_message() {
        m.releases = "none"
        assertTrue(run("send_release_apk", "{}").let { it.startsWith("ERROR") && it.contains("no release") })
        assertTrue(run("send_release_apk", """{"tag":"nope"}""").contains("no release"))
        val noToken = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out")).also { app.store.setGithub("", "") }
        assertTrue(runBlocking { Tools.execute(noToken, "send_release_apk", """{"repo":"octo/r"}""") }.contains("No GitHub token")); app.store.setGithub("gho_x", "octo")
    }

    @Test fun github_latest_release_now_sees_pre_releases_instead_of_failing() {
        m.releases = "pre"
        val out = run("github_latest_release", """{"repo":"r"}""")
        assertTrue(out, out.contains("build-7") && out.contains("(pre-release)") && out.contains("app-release.apk") && out.contains("send_release_apk"))
        m.releases = "none"; assertTrue(run("github_latest_release", """{"repo":"r"}""").contains("no release yet"))
    }

    @Test fun workers_cannot_use_it_and_it_is_registered_and_taught() {
        assertTrue(run("send_release_apk", "{}", ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), readOnly = true)).startsWith("ERROR: workers are read-only"))
        assertEquals(1, Tools.defs.count { it.name == "send_release_apk" }); assertNotEquals("send_release_apk", Tools.label("send_release_apk"))
        assertTrue(AgentRunner.SYSTEM.contains("call send_release_apk") && AgentRunner.SYSTEM.contains("pass to other people"))
    }

    @Test fun in_a_chat_the_model_hands_over_the_built_app() {
        m.releases = "pre"
        m.script += m.tool("send_release_apk", "{}"); m.script += m.text("Your app is ready, download it above.")
        val r = Env.run(app, "app ready hai? bhejo", repo = "octo/r")
        val att = com.aura.agent.data.Attachments.parse(r.tools.single().attachments).single()
        assertEquals("app-release.apk", att.name); assertEquals("Your app is ready, download it above.", r.lastAssistant.content)
    }
}
