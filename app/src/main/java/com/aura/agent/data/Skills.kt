package com.aura.agent.data

import com.aura.agent.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val builtin: Boolean = false,
)

object Skills {
    val builtin = listOf(
        Skill(
            "android-app-builder", "android-app-builder",
            "Build a complete Android app (Kotlin + Jetpack Compose), push it to GitHub and produce an APK with Actions.",
            """
1. Ask at most one clarifying question, otherwise decide: app name, package id, screens, data storage.
2. Project layout: settings.gradle.kts, build.gradle.kts (AGP 8.5.2, Kotlin 1.9.24, compose compiler 1.5.14), gradle.properties (android.useAndroidX=true), app/build.gradle.kts (compileSdk 34, minSdk 26, Compose BOM 2024.06.00), AndroidManifest.xml, res/values themes and strings, a launcher icon (adaptive vector), and Kotlin sources.
3. Use Material 3, one ViewModel per screen, state hoisting, LazyColumn with stable keys, no heavy work on the main thread. Never use emoji.
4. Sign release builds with a keystore committed in the repo (or build assembleDebug) so the APK installs.
5. Workflow .github/workflows/build.yml: checkout, setup-java temurin 17, gradle/actions/setup-gradle with gradle-version 8.7, run `gradle assembleRelease --no-daemon`, upload-artifact, then softprops/action-gh-release with files app/build/outputs/apk/release/*.apk and permissions contents: write.
6. Push everything in one github_write_files call, then github_wait_run. On failure read github_run_logs, fix, push again, repeat.
7. Reply with repo URL and APK link from github_latest_release.
""".trimIndent(), true,
        ),
        Skill(
            "build-fixer", "build-fixer",
            "Diagnose and fix a failing GitHub Actions or Gradle build.",
            """
1. Call github_list_runs, pick the newest failed run, then github_run_logs.
2. Find the FIRST real error (not the summary line). Typical causes: missing import, wrong plugin or Kotlin/Compose version pairing, missing resource, Java version, missing permission in the workflow (contents: write), gradle version mismatch.
3. Read the affected files with github_read_file, change only what is needed, push with github_write_files and a clear commit message.
4. Run github_run_workflow if the workflow is not push-triggered, then github_wait_run. Repeat until green or after 5 attempts, then explain the blocker.
""".trimIndent(), true,
        ),
        Skill(
            "code-reviewer", "code-reviewer",
            "Review code for bugs, security problems and clarity, and propose concrete fixes.",
            """
Read the code first (github_read_file / github_list_files if it is in a repo). Report findings ordered by severity: correctness bugs, security (secrets, injection, unsafe storage), concurrency and lifecycle problems, performance, then style. For each finding give file, line or snippet, why it matters, and a corrected snippet. Finish with a short summary. Do not rewrite code the user did not ask about.
""".trimIndent(), true,
        ),
        Skill(
            "ui-designer", "ui-designer",
            "Design clean, modern, calm mobile UI with strong typography and spacing.",
            """
Use a warm neutral palette with one accent colour, generous spacing (8dp grid), rounded corners (14-26dp), a serif face for headings and a sans face for body, subtle 1dp borders instead of heavy shadows, and clear empty states. Provide light and dark themes. Use vector drawables, never emoji. Keep animations short (150-300ms) and run them in the draw phase (graphicsLayer) so scrolling stays at 60 fps. Describe the screen hierarchy before writing code.
""".trimIndent(), true,
        ),
        Skill(
            "hinglish-tutor", "hinglish-tutor",
            "Explain things simply in Hinglish (Hindi written in English letters) with short examples.",
            """
Reply in friendly Hinglish when the user writes Hinglish. Explain in small steps, one idea per paragraph, give one small example, and finish with the next action the user can take. Keep code and technical terms in English.
""".trimIndent(), true,
        ),
        Skill(
            "skill-creator", "skill-creator",
            "Create a new reusable skill from what the user describes, then save it so it can be called with @name.",
            """
1. Understand the job: ask at most two short questions (what should the skill do, when should it trigger). If the user already described it, do not ask.
2. Write the skill:
   - name: short lowercase slug with hyphens, for example release-notes.
   - description: ONE sentence saying when to use it. This is what is shown in the skill list, so make it specific.
   - instructions: numbered steps in Markdown. Be concrete: which tools to call, what to check, what the final answer looks like. Include rules that must never be broken. Keep it under 40 lines.
3. Save it by calling the create_skill tool with name, description and instructions. Do not just print it.
4. Tell the user it is saved and that they can use it any time by typing @name in the message box.
5. If the user wants to change a skill later, call create_skill again with the same name; it replaces the old one.
""".trimIndent(), true,
        ),
        Skill(
            "project-planner", "project-planner",
            "Turn a vague idea into a short plan with milestones before building.",
            """
Produce: goal in one sentence, must-have features (max 6), data model, screens or API list, risks, and a numbered build order. Keep it under 25 lines. Ask the user to confirm only if a choice is expensive to change later.
""".trimIndent(), true,
        ),
    )

    private val mention = Regex("(?<![\\w@])@([a-z0-9][a-z0-9-]*)")

    /** Skills named with @name in a message, whether or not they are switched on in Settings. */
    fun mentioned(text: String, all: List<Skill>): List<Skill> =
        mention.findAll(text).mapNotNull { m -> all.firstOrNull { it.name.equals(m.groupValues[1], true) } }.distinctBy { it.name }.toList()

    /** Parses a SKILL.md style document: optional front matter with name and description. */
    fun parse(text: String, fallbackName: String = ""): Skill? {
        var body = text.trim()
        var name = fallbackName
        var desc = ""
        if (body.startsWith("---")) {
            val end = body.indexOf("\n---", 3)
            if (end > 0) {
                body.substring(3, end).lines().forEach { l ->
                    val i = l.indexOf(':')
                    if (i > 0) when (l.substring(0, i).trim().lowercase()) {
                        "name" -> name = l.substring(i + 1).trim().trim('"', '\'')
                        "description" -> desc = l.substring(i + 1).trim().trim('"', '\'')
                    }
                }
                body = body.substring(end + 4).trim()
            }
        }
        if (name.isBlank()) name = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim('#', ' ')?.take(40).orEmpty()
        if (name.isBlank() || body.isBlank()) return null
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "skill" }
        if (desc.isBlank()) desc = body.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(140).orEmpty()
        return Skill(Ids.id(), slug, desc, body)
    }

    suspend fun fetch(url: String): Skill? = withContext(Dispatchers.IO) {
        var u = url.trim()
        u = u.replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/blob/", "/")
        Http.client.newCall(Request.Builder().url(u).header("User-Agent", "Aura-Agent").build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            parse(r.body?.string().orEmpty(), u.substringAfterLast('/').substringBefore('.'))
        }
    }
}
