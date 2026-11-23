package com.aura.agent.agent

/**
 * Stops secrets from ever being pushed to GitHub. Every tool that writes to a repository (and git push in the codespace)
 * runs its content through here first. Nothing in this object is reversible, so it only reports what it found by kind and
 * never echoes the secret itself.
 */
object SecretScan {
    /** pattern, what it is, whether obvious placeholder words may excuse it (only for loose "key = value" matches), and how a captured key is filed */
    private class P(
        val re: Regex, val kind: String, val loose: Boolean = false,
        val slug: String = "key", val host: String = "", val style: String = "bearer", val param: String = "",
    )

    private val patterns = listOf(
        P(Regex("""sk-ant-[A-Za-z0-9_\-]{20,}"""), "an Anthropic API key", slug = "anthropic", host = "https://api.anthropic.com", style = "header", param = "x-api-key"),
        P(Regex("""sk-or-[A-Za-z0-9_\-]{20,}"""), "an OpenRouter API key", slug = "openrouter", host = "https://openrouter.ai/api"),
        P(Regex("""sk-[A-Za-z0-9_\-]{20,}"""), "an API key (sk-...)", slug = "openai"),
        P(Regex("""AIza[0-9A-Za-z_\-]{35}"""), "a Google API key", slug = "google", host = "https://generativelanguage.googleapis.com", style = "header", param = "x-goog-api-key"),
        P(Regex("""AQ\.[A-Za-z0-9_\-]{30,}"""), "a Google API key", slug = "google", host = "https://generativelanguage.googleapis.com", style = "header", param = "x-goog-api-key"),
        P(Regex("""\bgh[pousr]_[A-Za-z0-9]{30,}"""), "a GitHub token", slug = "github", host = "https://api.github.com"),
        P(Regex("""github_pat_[A-Za-z0-9_]{30,}"""), "a GitHub token", slug = "github", host = "https://api.github.com"),
        P(Regex("""\bvcp_[A-Za-z0-9]{30,}"""), "a Vercel token", slug = "vercel", host = "https://api.vercel.com"),
        P(Regex("""\bnvapi-[A-Za-z0-9_\-]{30,}"""), "an NVIDIA API key", slug = "nvidia", host = "https://integrate.api.nvidia.com"),
        P(Regex("""\bgsk_[A-Za-z0-9]{30,}"""), "a Groq API key", slug = "groq", host = "https://api.groq.com/openai"),
        P(Regex("""\bxai-[A-Za-z0-9]{30,}"""), "an xAI API key", slug = "xai", host = "https://api.x.ai"),
        P(Regex("""\bhf_[A-Za-z0-9]{30,}"""), "a Hugging Face token", slug = "huggingface", host = "https://huggingface.co"),
        P(Regex("""\bglpat-[A-Za-z0-9_\-]{20,}"""), "a GitLab token", slug = "gitlab", host = "https://gitlab.com/api"),
        P(Regex("""\b[sr]k_live_[A-Za-z0-9]{20,}"""), "a Stripe key", slug = "stripe", host = "https://api.stripe.com"),
        P(Regex("""\bAKIA[0-9A-Z]{16}\b"""), "an AWS access key", slug = "aws"),
        P(Regex("""\bxox[baprs]-[A-Za-z0-9\-]{10,}"""), "a Slack token", slug = "slack", host = "https://slack.com/api"),
        P(Regex("""-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"""), "a private key", slug = "private-key"),
        P(Regex("""(?i)\b(?:api[_\-]?key|secret|token|password|passwd|bearer)\b["']?\s*[:=]\s*["']([A-Za-z0-9_\-/+=.]{20,})["']"""), "a hard-coded credential", loose = true),
        P(Regex("""(?i)authorization["']?\s*[:=]\s*["']bearer\s+([A-Za-z0-9_\-/+=.]{20,})["']"""), "a bearer token", loose = true),
    )

    /** Words that mark an example value in a doc or template. Prefixed keys are only excused by the strictest of these. */
    private val looseHint = Regex("""(?i)(your|example|placeholder|changeme|xxxx|dummy|sample|fake|test|<|\$\{|\{\{|process\.env|getenv|env\.)""")
    private val strictHint = Regex("""(?i)(your[-_]|example|placeholder|changeme|x{6,}|<|\.\.\.)""")

    private val blockedNames = Regex("""(?i)(^|/)(\.env(\.[a-z0-9_-]+)?|id_rsa|id_ed25519|id_ecdsa|[^/]*\.pem|credentials\.json|secrets?\.(json|ya?ml|toml|properties)|service[-_]?account[^/]*\.json|\.npmrc|\.netrc)$""")
    private val allowedNames = Regex("""(?i)\.(example|sample|template|dist)$""")

    /** Why this file name must not be pushed, or null. */
    fun blockedPath(path: String): String? {
        val p = path.replace('\\', '/')
        if (allowedNames.containsMatchIn(p)) return null
        return if (blockedNames.containsMatchIn(p)) "$p is a secrets file (keys, tokens or credentials)" else null
    }

    /** What kind of secret the text holds, or null. The text itself is never returned. */
    fun find(text: String): String? {
        for (p in patterns) {
            for (m in p.re.findAll(text)) {
                val v = if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.value
                if ((if (p.loose) looseHint else strictHint).containsMatchIn(v)) continue
                return p.kind
            }
        }
        return null
    }

    /** A secret found in text, with how to file it in the key box. */
    class Found(val kind: String, val value: String, val slug: String, val host: String, val style: String, val param: String, val isBlock: Boolean = false)

    /** Every distinct secret in the text (longest provider-specific patterns win, so one key is never found twice). */
    fun extract(text: String): List<Found> {
        val out = ArrayList<Found>(); val seen = HashSet<String>()
        for (p in patterns) {
            for (m in p.re.findAll(text)) {
                val v = if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.value
                if ((if (p.loose) looseHint else strictHint).containsMatchIn(v)) continue
                if (seen.any { it.contains(v) || v.contains(it) }) continue
                seen += v
                if (p.slug == "private-key") {
                    val end = Regex("""-----END (?:[A-Z]+ )?PRIVATE KEY-----""").find(text, m.range.last)
                    out += Found(p.kind, text.substring(m.range.first, (end?.range?.last ?: (text.length - 1)) + 1), p.slug, "", "bearer", "", isBlock = true)
                } else out += Found(p.kind, v, p.slug, p.host, p.style, p.param)
            }
        }
        return out
    }

    /** A refusal message for a set of files about to be pushed, or null when they are clean. */
    fun check(files: Map<String, String>): String? {
        for ((path, content) in files) {
            blockedPath(path)?.let { return refusal(it) }
            find(content)?.let { return refusal("$path contains $it") }
        }
        return null
    }

    fun checkOne(path: String, content: String) = check(mapOf(path to content))

    private fun refusal(what: String) =
        "BLOCKED: $what. Secrets are never pushed to GitHub. Keep it out of the repository: read it from an environment variable or the app's settings, " +
            "add the file to .gitignore, and commit only a .env.example with empty values. Do not retry with the secret in the files."
}
