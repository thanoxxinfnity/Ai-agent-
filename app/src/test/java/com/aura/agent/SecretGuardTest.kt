package com.aura.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Every secret below is a made-up string shaped like the real thing. */
private const val FAKE_SK = "sk-ZZtestFAKEvalue1234567890abcdefXYZ"
private const val FAKE_GH = "ghp_ZZfakeFAKEfakeFAKEfakeFAKEfake123456"
private const val FAKE_GOOGLE = "AIzaSyZZfakeFAKEfakeFAKEfakeFAKEfake1234"

class SecretScanUnitTest {
    @Test fun common_secret_shapes_are_found_without_echoing_them() {
        for (t in listOf("key = \"$FAKE_SK\"", "x $FAKE_GH y", "k=$FAKE_GOOGLE", "AKIAZZFAKEFAKEFAKE12 id", "-----BEGIN RSA PRIVATE KEY-----\nabc", "xoxb-1234567890-abcdefghij",
            "const apiKey = \"abcdEFGH1234567890abcdEFGH\"", "password: 'S3cretP4ssw0rdValue99'")) {
            val r = SecretScan.find(t)
            assertNotNull("should be flagged: $t", r)
            assertFalse("the report never repeats the secret", r!!.contains("ZZ") || r.contains("1234567890"))
        }
    }

    @Test fun ordinary_code_and_placeholders_pass() {
        for (t in listOf("val x = 5\nfun main() { println(\"hello\") }", "apiKey = \"YOUR_API_KEY_HERE_PLEASE_REPLACE\"", "token = process.env.API_TOKEN", "password = \"\${DB_PASSWORD}\"",
            "const key = \"<your-api-key-goes-here-1234>\"", "see the docs for tokens", "api_key = os.getenv(\"OPENAI_API_KEY\")")) {
            assertNull("must pass: $t", SecretScan.find(t))
        }
    }

    @Test fun secret_file_names_are_blocked_but_examples_are_allowed() {
        for (p in listOf(".env", "app/.env.production", "keys/id_rsa", "certs/server.pem", "credentials.json", "config/secrets.yml", "service-account-prod.json", ".npmrc")) assertNotNull(p, SecretScan.blockedPath(p))
        for (p in listOf(".env.example", "src/Main.kt", "README.md", "env.sample", "docs/secrets-guide.md", ".gitignore")) assertNull(p, SecretScan.blockedPath(p))
    }

    @Test fun check_reports_the_file_and_the_kind() {
        val r = SecretScan.check(mapOf("src/a.kt" to "ok", "src/b.kt" to "val k = \"$FAKE_SK\""))!!
        assertTrue(r, r.startsWith("BLOCKED") && r.contains("src/b.kt") && r.contains("API key") && !r.contains(FAKE_SK))
        assertNull(SecretScan.check(mapOf("a" to "fine")))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretPushTest {
    private val m = Mock().start()
    private val app = Env.setup(m)
    private fun ctx() = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), activeRepo = "octo/r")
    private fun run(name: String, args: String) = runBlocking { Tools.execute(ctx(), name, args) }
    private fun esc(s: String) = org.json.JSONObject.quote(s)

    @Test fun a_push_with_an_api_key_is_refused_before_anything_is_sent() {
        val out = run("github_write_files", """{"repo":"r","files":[{"path":"app/Config.kt","content":${esc("val key = \"$FAKE_SK\"")}}]}""")
        assertTrue(out, out.startsWith("BLOCKED") && out.contains("Nothing was pushed") && !out.contains(FAKE_SK))
        assertEquals(0, m.count("POST /gh/repos/octo/r/git/blobs")); assertEquals(0, m.count("PATCH /gh/repos/octo/r/git/refs"))
    }

    @Test fun env_files_and_private_keys_cannot_be_pushed_but_the_example_file_can() {
        assertTrue(run("github_write_files", """{"repo":"r","files":[{"path":".env","content":"A=1"}]}""").startsWith("BLOCKED"))
        assertTrue(run("github_write_files", """{"repo":"r","files":[{"path":"deploy/key.pem","content":"x"}]}""").startsWith("BLOCKED"))
        assertEquals(0, m.count("POST /gh/repos/octo/r/git/blobs"))
        val ok = run("github_write_files", """{"repo":"r","files":[{"path":".env.example","content":"API_KEY="},{"path":"docs/new.md","content":"hello"}]}""")
        assertFalse(ok, ok.startsWith("BLOCKED") || ok.startsWith("ERROR"))
        assertEquals(2, m.count("POST /gh/repos/octo/r/git/blobs"))
    }

    @Test fun editing_a_file_to_add_a_secret_is_refused() {
        val out = run("edit_file_diff", """{"repo":"r","path":"src/A.kt","search":"package a","replace":${esc("package a\nval t = \"$FAKE_GH\"")}}""")
        assertTrue(out, out.startsWith("BLOCKED"))
        assertEquals("nothing was written", 0, m.log.count { it.startsWith("PUT /gh/repos/octo/r/contents/src/A.kt") })
    }

    @Test fun committing_bytes_with_a_secret_is_refused() {
        val out = runBlocking { ToolsPro.commitBytes(ctx(), "octo/r", "app/Keys.kt", "val k = \"$FAKE_SK\"".toByteArray(), "x", null) }
        assertTrue(out, out.startsWith("BLOCKED")); assertEquals(0, m.log.count { it.startsWith("PUT /gh/repos/octo/r/contents/app/Keys.kt") })
        val okOut = runBlocking { ToolsPro.commitBytes(ctx(), "octo/r", "app/data.bin", byteArrayOf(0, 1, 2, 3), "x", null) }
        assertFalse(okOut, okOut.startsWith("BLOCKED"))
    }

    @Test fun git_push_in_the_codespace_is_blocked_when_the_commits_hold_a_secret() {
        m.gitDiff = "+++ b/app/Config.kt\n+val key = \"$FAKE_SK\"\n"
        val out = run("cloud_exec", """{"command":"git push origin main"}""")
        assertTrue(out, out.startsWith("BLOCKED") && !out.contains(FAKE_SK))
        assertEquals("the push itself never ran", 0, m.bodies.count { it.first == "POST /cs/exec_stream" && it.second.contains("git push") })
    }

    @Test fun git_push_is_blocked_when_a_secrets_file_is_in_the_commits() {
        m.gitDiff = "+++ b/.env\n+A=1\n"
        assertTrue(run("cloud_exec", """{"command":"git push"}""").startsWith("BLOCKED"))
    }

    @Test fun a_clean_git_push_goes_through() {
        m.gitDiff = "+++ b/src/Main.kt\n+fun main() {}\n"
        val out = run("cloud_exec", """{"command":"git push origin main"}""")
        assertFalse(out, out.startsWith("BLOCKED"))
        assertEquals(1, m.bodies.count { it.first == "POST /cs/exec_stream" && it.second.contains("git push") })
    }

    @Test fun the_agent_gets_the_refusal_and_the_run_continues() {
        m.script += m.tool("github_write_files", """{"repo":"r","files":[{"path":"Secrets.kt","content":${esc("val k = \"$FAKE_SK\"")}}]}""")
        m.script += m.text("I kept the key out of the repository.")
        val r = Env.run(app, repo = "octo/r")
        assertTrue(r.tools[0].content, r.tools[0].content.startsWith("BLOCKED"))
        assertEquals("I kept the key out of the repository.", r.lastAssistant.content)
    }
}
