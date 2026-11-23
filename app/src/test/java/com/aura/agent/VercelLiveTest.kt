package com.aura.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real end-to-end check of the Vercel deploy tool against vercel.com.
 * It only runs when AURA_LIVE_VERCEL_TOKEN is set in the environment of the test run; the token is never written anywhere.
 * It deploys a one-page site to a throwaway project and deletes that project at the end.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VercelLiveTest {
    private val token = System.getenv("AURA_LIVE_VERCEL_TOKEN").orEmpty()
    private val team = System.getenv("AURA_LIVE_VERCEL_TEAM").orEmpty()
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    @Test fun a_real_deploy_goes_live_and_is_cleaned_up() {
        assumeTrue("set AURA_LIVE_VERCEL_TOKEN to run the live Vercel check", token.isNotBlank())
        val m = Mock().start(); val app = Env.setup(m)
        app.vercelBase = "https://api.vercel.com"; app.vercelPollMs = 3000
        app.store.setVercel(token, "live-check", team)
        val project = "aura-live-check-" + System.currentTimeMillis().toString(36)
        val ctx = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), approve = { _, _ -> true })
        val marker = "Aura live check " + System.nanoTime()
        try {
            val out = runBlocking { Tools.execute(ctx, "vercel_deploy", """{"name":"$project","framework":"static","production":true,"files":[{"path":"index.html","content":"<!doctype html><title>t</title><h1>$marker</h1>"}]}""") }
            println("LIVE vercel_deploy result: " + out.lines().take(6).joinToString(" | ").replace(token, "[token]"))
            assertFalse(out, out.startsWith("ERROR") || out.startsWith("DENIED"))
            val link = Regex("""https://[A-Za-z0-9.-]+\.vercel\.app""").find(out)?.value
            assertEquals("the tool gives the short public address, not the team address that shows a login page", "https://$project.vercel.app", link)
            // the link must really serve the page (retry a few seconds while the alias settles)
            var body = ""; var code = 0
            repeat(8) { i ->
                if (code != 200) {
                    http.newCall(Request.Builder().url(link!!).build()).execute().use { r -> code = r.code; body = r.body?.string().orEmpty() }
                    if (code != 200) Thread.sleep(2500)
                }
            }
            println("LIVE page status: $code, has marker: ${body.contains(marker)}")

            assertEquals("the production link is public", 200, code); assertTrue(body.contains(marker))
        } finally {
            val team = if (team.isBlank()) "" else "?teamId=$team"
            runCatching {
                http.newCall(Request.Builder().url("https://api.vercel.com/v9/projects/$project$team").delete().header("Authorization", "Bearer $token").build()).execute().use { println("LIVE cleanup delete status: " + it.code) }
            }
        }
    }

    @Test fun a_wrong_token_gives_a_clear_message_not_a_crash() {
        val m = Mock().start(); val app = Env.setup(m)
        app.vercelBase = "https://api.vercel.com"; app.vercelPollMs = 1000
        app.store.setVercel("vcp_not_a_real_token_000000000000000000000000000000000000", "x", "")
        val ctx = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), approve = { _, _ -> true })
        val out = runBlocking { Tools.execute(ctx, "vercel_deploy", """{"name":"aura-never-created","files":[{"path":"index.html","content":"x"}]}""") }
        assertTrue(out, out.startsWith("ERROR") && (out.contains("rejected this token") || out.contains("Vercel error")))
    }
}

class VercelLinkChoiceTest {
    private fun d(vararg alias: String) = com.aura.agent.net.Deployment("i", "proj-abc123-team.vercel.app", "READY", alias.toList(), null)
    private val v = com.aura.agent.net.Vercel("t")

    @Test fun the_short_project_address_wins_over_the_team_address_that_shows_a_login_page() {
        assertEquals("https://proj.vercel.app", v.publicUrl(d("proj-team.vercel.app", "proj.vercel.app", "proj-git-main-team.vercel.app"), true, "proj"))
        assertEquals("without the project name the shortest clean alias is still chosen", "https://proj.vercel.app", v.publicUrl(d("proj-team.vercel.app", "proj.vercel.app"), true))
    }

    @Test fun the_projects_own_domain_list_fixes_a_missing_alias() {
        assertEquals("https://proj.vercel.app", v.publicUrl(d("proj-team.vercel.app"), true, "proj", listOf("proj.vercel.app")))
        assertEquals("a taken name gets a suffix from Vercel: the shortest domain is used", "https://proj-x1.vercel.app", v.publicUrl(d("proj-x1-team.vercel.app"), true, "proj", listOf("proj-x1.vercel.app")))
        assertEquals("a custom domain is not preferred over the vercel.app address", "https://proj.vercel.app", v.publicUrl(d(), true, "proj", listOf("www.example.com", "proj.vercel.app")))
    }

    @Test fun branch_addresses_and_previews_are_handled() {
        assertEquals("https://proj-team.vercel.app", v.publicUrl(d("proj-git-main-team.vercel.app", "proj-team.vercel.app"), true, "proj"))
        assertEquals("https://proj-abc123-team.vercel.app", v.publicUrl(d("proj.vercel.app"), false, "proj"))
        assertEquals("https://proj-abc123-team.vercel.app", v.publicUrl(d(), true, "proj"))
    }
}
