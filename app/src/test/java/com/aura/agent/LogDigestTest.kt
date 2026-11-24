package com.aura.agent

import com.aura.agent.agent.Tools
import org.junit.Assert.*
import org.junit.Test

class LogDigestTest {
    private val log = buildString {
        appendLine("2026-11-24T08:00:01.1Z > Task :app:compileReleaseKotlin")
        appendLine("2026-11-24T08:00:09.1Z e: file:///home/runner/work/x/app/src/main/java/com/x/VaultViewModel.kt:42:17 Unresolved reference: vaultRepo")
        appendLine("2026-11-24T08:00:09.2Z FAILURE: Build failed with an exception.")
        appendLine("2026-11-24T08:00:09.3Z * What went wrong:")
        appendLine("2026-11-24T08:00:09.4Z Execution failed for task ':app:compileReleaseKotlin'.")
        appendLine("2026-11-24T08:00:09.5Z > Compilation error. See log for more details")
        repeat(80) { appendLine("2026-11-24T08:00:10Z Gradle cache warning number $it: Daemon will be stopped, configuration cache entry discarded") }
    }

    @Test fun the_compile_error_is_found_even_when_cache_warnings_fill_the_end_of_the_log() {
        val d = Tools.LogDigest.digest(log, tail = 300)
        assertTrue(d, d.contains("VaultViewModel.kt:42:17 Unresolved reference: vaultRepo"))
        assertTrue(d.contains("What went wrong") && d.contains("Execution failed for task"))
        assertFalse("timestamps are removed", d.contains("2026-11-24T08:00:09"))
        assertTrue("the tail alone would have hidden it", !log.takeLast(300).contains("Unresolved reference"))
        assertTrue(d.length < 3000)
    }

    @Test fun a_log_without_errors_says_so_and_still_shows_the_end() {
        val d = Tools.LogDigest.digest("all fine\nbuild ok\n", tail = 50)
        assertTrue(d.startsWith("(no error line found")); assertTrue(d.contains("build ok"))
    }
}
