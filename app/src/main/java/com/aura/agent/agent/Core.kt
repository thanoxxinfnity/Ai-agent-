package com.aura.agent.agent

/** Pure helpers with no Android dependencies, so they are unit-tested on the plain JVM. */

sealed interface PatchResult {
    data class Ok(val text: String, val count: Int) : PatchResult
    data class Fail(val message: String) : PatchResult
}

object Patch {
    private fun lineOf(text: String, index: Int) = text.substring(0, index).count { it == '\n' } + 1

    /** Exact search and replace. Never fuzzy-applies; on a miss it explains the first mismatching line. */
    fun apply(text: String, search: String, replace: String, replaceAll: Boolean = false): PatchResult {
        if (search.isEmpty()) return PatchResult.Fail("search is empty. Give the exact existing lines to replace.")
        Guard.looksTruncated(replace)?.let { return PatchResult.Fail("replace contains a placeholder ($it). Write the real code, never abbreviate.") }
        var s = search
        if (!text.contains(s) && text.contains("\r\n") && !s.contains("\r\n")) s = s.replace("\n", "\r\n")
        var r = replace
        if (s.contains("\r\n") && !r.contains("\r\n")) r = r.replace("\n", "\r\n")
        val positions = ArrayList<Int>()
        var from = 0
        while (true) {
            val i = text.indexOf(s, from)
            if (i < 0) break
            positions += i
            from = i + s.length.coerceAtLeast(1)
        }
        if (positions.isEmpty()) return PatchResult.Fail(mismatch(text, search))
        if (positions.size > 1 && !replaceAll) {
            return PatchResult.Fail(
                "search matches ${positions.size} places (lines ${positions.take(8).joinToString { lineOf(text, it).toString() }}). " +
                    "Add more surrounding lines to make it unique, or set replace_all to true."
            )
        }
        val sb = StringBuilder()
        var last = 0
        for (p in if (replaceAll) positions else positions.take(1)) {
            sb.append(text, last, p).append(r)
            last = p + s.length
        }
        sb.append(text, last, text.length)
        return PatchResult.Ok(sb.toString(), if (replaceAll) positions.size else 1)
    }

    private fun mismatch(text: String, search: String): String {
        val fileLines = text.split("\n").map { it.trimEnd('\r') }
        val want = search.split("\n").map { it.trimEnd('\r') }
        val firstIdx = want.indexOfFirst { it.isNotBlank() }
        if (firstIdx < 0) return "search is blank."
        val key = want[firstIdx].trim()
        val anchors = fileLines.indices.filter { fileLines[it].trim() == key }
        if (anchors.isEmpty()) {
            val near = fileLines.indices.firstOrNull { key.length > 6 && fileLines[it].contains(key.take(key.length.coerceAtMost(25))) }
            return "search not found: the first line `${key.take(80)}` does not exist in the file." +
                (near?.let { " Closest line ${it + 1}: `${fileLines[it].trim().take(100)}`." } ?: "") +
                " Read the file section again (read_lines) and copy the text exactly."
        }
        val start = anchors.first()
        for (k in want.indices) {
            val fl = fileLines.getOrNull(start - firstIdx + k)
            if (fl == null) return "mismatch: the file ends at line ${fileLines.size} but your search continues with `${want[k].trim().take(80)}`."
            if (fl != want[k]) {
                val ln = start - firstIdx + k + 1
                return if (fl.trim() == want[k].trim()) {
                    "mismatch at line $ln: only the indentation differs. File has `${fl.take(100)}` (copy the exact leading whitespace)."
                } else {
                    "mismatch at line $ln: you wrote `${want[k].take(100)}` but the file has `${fl.take(100)}`."
                }
            }
        }
        return "search not found (unknown difference near line ${start + 1})."
    }
}

object Guard {
    private val placeholders = listOf(
        Regex("""(//|#|/\*|<!--)\s*\.{3}\s*(rest|remaining|existing|other|previous|unchanged|same)\b""", RegexOption.IGNORE_CASE),
        Regex("""(//|#|/\*|<!--)\s*(rest|remaining|existing|previous|unchanged|same)\s+(of\s+)?(the\s+)?(code|file|content|implementation|class|function)""", RegexOption.IGNORE_CASE),
        Regex("""\.{3}\s*(existing|rest of|remaining|previous)\s+(code|file|content)""", RegexOption.IGNORE_CASE),
        Regex("""\[\s*(rest|remaining|existing)\s+(of\s+)?(the\s+)?(code|file)[^\]]*\]""", RegexOption.IGNORE_CASE),
        Regex("""(//|#)\s*(code|implementation)\s+(goes|remains)\s+here""", RegexOption.IGNORE_CASE),
    )

    /** Returns the offending snippet when the text contains an "abbreviated code" placeholder. */
    fun looksTruncated(text: String): String? =
        placeholders.firstNotNullOfOrNull { it.find(text)?.value?.trim() }

    private val danger = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*\s+)*-[a-zA-Z]*[rRfF][a-zA-Z]*\b""") to "recursive or forced delete (rm -rf)",
        Regex("""\bfind\b[^|;&]*\s-delete\b""") to "find -delete",
        Regex("""\b(mkfs|fdisk|wipefs)\b""") to "disk format command",
        Regex("""\bdd\s+[^|;&]*\bof=/""") to "dd writing to a device",
        Regex(""":\(\)\s*\{""") to "fork bomb",
        Regex("""\bdrop\s+(database|schema|table)\b""", RegexOption.IGNORE_CASE) to "database drop",
        Regex("""\btruncate\s+table\b""", RegexOption.IGNORE_CASE) to "database truncate",
        Regex("""\bdelete\s+from\s+\w+\s*(;|$)""", RegexOption.IGNORE_CASE) to "DELETE without WHERE",
        Regex("""\b(supabase|prisma|rails|django-admin|alembic)\b[^|;&]*\b(db\s+reset|migrate:reset|flush|downgrade\s+base)\b""", RegexOption.IGNORE_CASE) to "database reset",
        Regex("""\bgit\s+push\b[^|;&]*(\s--force\b|\s-f\b|--force-with-lease)""") to "force push",
        Regex("""\bgit\s+(reset\s+--hard|clean\s+-[a-z]*f)""") to "git hard reset or clean",
        Regex("""\b(vercel|netlify|firebase|fly|flyctl|heroku|wrangler|serverless|sls)\b[^|;&]*\b(deploy|publish)\b""", RegexOption.IGNORE_CASE) to "production deploy",
        Regex("""\bvercel\b[^|;&]*--prod\b""") to "production deploy",
        Regex("""\b(npm|yarn|pnpm)\s+publish\b""") to "package publish",
        Regex("""\bcargo\s+publish\b|\btwine\s+upload\b""") to "package publish",
        Regex("""\bgh\s+(release\s+(create|delete)|repo\s+delete|secret\s+(set|delete))""") to "GitHub release, secret or repo change",
        Regex("""\b(kubectl|helm)\s+(delete|uninstall)\b""") to "cluster delete",
        Regex("""\bterraform\s+(apply|destroy)\b""") to "infrastructure change",
        Regex("""\b(curl|wget)\b[^|;&]*\|\s*(sudo\s+)?(ba)?sh\b""") to "pipe a download into a shell",
        Regex("""\bchmod\s+-R\s+7{3}\b|\bchown\s+-R\b""") to "recursive permission change",
        Regex("""\b(shutdown|reboot|halt|poweroff)\b""") to "machine shutdown",
        Regex(""">\s*/dev/(sd|nvme|vd)""") to "writing to a disk device",
    )

    /** Returns a human reason when the shell command needs the user's approval, else null. */
    fun dangerous(cmd: String): String? = danger.firstOrNull { it.first.containsMatchIn(cmd) }?.second

    private val readOnly = Regex("""^\s*(ls|cat|rg|grep|egrep|find|pwd|echo|head|tail|wc|which|stat|du|df|tree|file|sed\s+-n|awk|git\s+(status|log|diff|show|branch|remote|rev-parse)|node\s+(-v|--version)|python3?\s+(-V|--version)|java\s+-version|npm\s+(ls|list|view|-v)|agent-browser)\b""")

    /** True when the command very likely changes files, so a checkpoint is taken first. */
    fun mutates(cmd: String): Boolean {
        val c = cmd.trim()
        if (Regex("""(^|[^>2&])>{1,2}\s*[^&\s]|\btee\b|\bsed\s+(-[a-z]*i)|\bmv\b|\bcp\b|\brm\b|\bgit\s+(checkout|apply|pull|merge|rebase|commit)|\bnpm\s+(i|install|ci|run)|\bpip3?\s+install|\bmkdir\b|\btouch\b""").containsMatchIn(c)) return true
        return !readOnly.containsMatchIn(c)
    }
}

object Glob {
    fun toRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        var brace = 0
        while (i < glob.length) {
            val c = glob[i]
            when (c) {
                '*' -> if (glob.startsWith("**/", i)) { sb.append("(?:.*/)?"); i += 2 } else if (glob.startsWith("**", i)) { sb.append(".*"); i += 1 } else sb.append("[^/]*")
                '?' -> sb.append("[^/]")
                '{' -> { sb.append("(?:"); brace++ }
                '}' -> { if (brace > 0) { sb.append(")"); brace-- } else sb.append("\\}") }
                ',' -> if (brace > 0) sb.append("|") else sb.append(",")
                '.', '(', ')', '+', '|', '^', '$', '\\', '[', ']' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        // a bare pattern such as "*.kt" should match in any folder
        val body = sb.toString()
        return Regex(if (!glob.contains('/')) "^(?:.*/)?" + body.removePrefix("^") + "$" else "$body$")
    }
}

object Tokens {
    /** Rough token estimate (about 3.5 characters per token) good enough to decide when to compact. */
    fun estimate(chars: Int): Int = (chars / 3.5).toInt() + 1
}

/** How much of the model's context window a chat uses: shown as "12k / 128k" in the chat header. */
object ContextMeter {
    /** characters the next request carries: the live (not compacted) messages plus the fixed instructions */
    fun chars(active: List<com.aura.agent.data.MessageEntity>, systemLength: Int): Int =
        active.filter { !it.compacted }.sumOf { it.content.length + (it.toolCalls?.length ?: 0) } + systemLength + 3000

    fun usedTokens(messages: List<com.aura.agent.data.MessageEntity>): Int =
        Tokens.estimate(chars(messages.filter { it.role != "switch" }, AgentRunner.SYSTEM.length))

    fun format(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
        n >= 10_000 -> "${n / 1000}k"
        n >= 1000 -> "%.1fk".format(n / 1000.0).replace(".0k", "k")
        else -> n.toString()
    }

    /** 0 = fine, 1 = getting full (75%+, older steps will be summarized soon), 2 = nearly full (90%+) */
    fun level(used: Int, limit: Int): Int = if (limit <= 0) 0 else when { used * 100L >= limit * 90L -> 2; used * 100L >= limit * 75L -> 1; else -> 0 }
}

object Sh {
    /** Single-quote a value for bash. */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun looksLikeContextOverflow(msg: String): Boolean {
        val m = msg.lowercase()
        return listOf("context length", "context_length", "maximum context", "too many tokens", "token limit", "reduce the length", "prompt is too long", "exceeds the context", "input is too long", "max_tokens")
            .any { it in m }
    }
}

object VerifyScript {
    /** Runs in bash inside the codespace. DIR selects the project; CMDS (newline separated) overrides detection. */
    val SCRIPT = """
cd "${'$'}{DIR:-.}" 2>/dev/null || { echo "VERIFY_RESULT: FAIL (no such directory)"; exit 2; }
rc=0; ran=0
run() { echo "${'$'} ${'$'}*"; ran=1; "${'$'}@" 2>&1 || rc=1; }
runsh() { echo "${'$'} ${'$'}1"; ran=1; bash -c "${'$'}1" 2>&1 || rc=1; }
if [ -n "${'$'}CMDS" ]; then
  while IFS= read -r line; do [ -n "${'$'}line" ] && runsh "${'$'}line"; done <<< "${'$'}CMDS"
else
  if [ -f package.json ]; then
    [ -d node_modules ] || { npm ci --no-audit --no-fund >/dev/null 2>&1 || npm install --no-audit --no-fund >/dev/null 2>&1; }
    [ -f tsconfig.json ] && run npx --no-install tsc --noEmit
    grep -q '"build"' package.json && run npm run build
    grep -q '"lint"' package.json && run npm run lint
  fi
  pyfiles=${'$'}(git ls-files '*.py' 2>/dev/null); [ -z "${'$'}pyfiles" ] && pyfiles=${'$'}(find . -name '*.py' -not -path './node_modules/*' -not -path './.venv/*' -not -path './.git/*' 2>/dev/null | head -500)
  [ -n "${'$'}pyfiles" ] && run python3 -m py_compile ${'$'}pyfiles
  if [ -f gradlew ]; then run bash ./gradlew assembleDebug --no-daemon -q
  elif ls settings.gradle* build.gradle* >/dev/null 2>&1; then run gradle assembleDebug --no-daemon -q; fi
  [ -f Cargo.toml ] && run cargo check
  [ -f go.mod ] && run go build ./...
fi
[ "${'$'}ran" = 1 ] || { echo "NO_CHECKS_FOUND: no build files found in this directory. Pass the commands parameter."; exit 3; }
if [ "${'$'}rc" = 0 ]; then echo "VERIFY_RESULT: PASS"; else echo "VERIFY_RESULT: FAIL"; fi
exit ${'$'}rc
""".trimStart()
}

object Compact {
    /**
     * How many leading messages to fold into a summary, keeping at least [keepAtLeast] recent ones.
     * Cuts at a user message when possible; otherwise never separates an assistant call from its tool results.
     */
    fun cut(roles: List<String>, keepAtLeast: Int = 8): Int {
        if (roles.size <= keepAtLeast + 2) return 0
        val want = roles.size - keepAtLeast
        var i = want
        while (i > 0 && roles[i] != "user") i--
        if (i > 0) return i
        var j = want
        while (j < roles.size && roles[j] == "tool") j++
        return if (j <= roles.size - 2) j else 0
    }

    /** Cheap deterministic summary used when the model cannot be reached. */
    fun fallbackSummary(lines: List<String>): String =
        "Earlier conversation (automatic extract):\n" + lines.joinToString("\n") { it.take(240) }.takeLast(6000)
}

/** Minimal reader for the .tar.gz that GitHub serves for a repository. */
object TarReader {
    class Entry(val path: String, val bytes: ByteArray)

    fun readGz(gz: ByteArray, stripFirst: Boolean = true, maxFileBytes: Int = 5 * 1024 * 1024): List<Entry> =
        read(java.util.zip.GZIPInputStream(gz.inputStream()), stripFirst, maxFileBytes)

    fun read(input: java.io.InputStream, stripFirst: Boolean = true, maxFileBytes: Int = 5 * 1024 * 1024): List<Entry> {
        val out = ArrayList<Entry>()
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it.toInt() == 0 }) break
            var name = str(header, 0, 100)
            val prefix = str(header, 345, 155)
            if (prefix.isNotEmpty()) name = "$prefix/$name"
            val size = str(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val padded = (size + 511) / 512 * 512
            if (type == 'x' || type == 'g') {          // pax headers carry long paths
                val data = ByteArray(size.toInt()); readFully(input, data); skip(input, padded - size)
                Regex("\\d+ path=([^\\n]*)\\n").find(String(data))?.let { longName = it.groupValues[1] }
                continue
            }
            if (type == 'L') {                          // GNU long name
                val data = ByteArray(size.toInt()); readFully(input, data); skip(input, padded - size)
                longName = String(data).trimEnd('\u0000'); continue
            }
            if (longName != null) { name = longName!!; longName = null }
            if ((type == '0' || type == '\u0000') && !name.endsWith("/")) {
                if (size > maxFileBytes) { skip(input, padded) } else {
                    val data = ByteArray(size.toInt()); readFully(input, data); skip(input, padded - size)
                    val path = if (stripFirst) name.substringAfter('/', "") else name
                    if (path.isNotEmpty()) out += Entry(path, data)
                }
            } else skip(input, padded)
        }
        return out
    }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off)
    }

    private fun readFully(i: java.io.InputStream, b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) return n > 0 && false; n += r }
        return true
    }

    private fun skip(i: java.io.InputStream, n: Long) { var left = n; val buf = ByteArray(8192); while (left > 0) { val r = i.read(buf, 0, minOf(left, buf.size.toLong()).toInt()); if (r < 0) return; left -= r } }
}

object DeployFilter {
    private val skipDirs = listOf(".git/", "node_modules/", ".github/", ".vercel/", ".next/cache/", "__pycache__/", ".gradle/", "build/")
    private val skipNames = setOf(".DS_Store", "Thumbs.db", ".env", ".env.local")

    /** Which files of a project go to Vercel: no VCS data, dependencies, caches or secrets. */
    fun keep(path: String, directory: String = ""): Boolean {
        val dir = directory.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        if (dir.isNotEmpty() && !path.startsWith(dir)) return false
        val rel = path.removePrefix(dir)
        if (rel.isEmpty()) return false
        if (skipDirs.any { rel.startsWith(it) || rel.contains("/$it") }) return false
        if (rel.substringAfterLast('/') in skipNames) return false
        return true
    }

    fun relative(path: String, directory: String = ""): String {
        val dir = directory.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return path.removePrefix(dir)
    }

    fun projectName(raw: String): String {
        val n = raw.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").replace(Regex("-{2,}"), "-").trim('-', '.', '_').take(52)
        return n.ifBlank { "aura-site" }
    }
}

object MemoryRules {
    const val MAX_ENTRIES = 300
    const val MAX_VALUE = 1000
    const val PINNED_KEY = "pinned_repo"
    val categories = listOf("fact", "preference", "project", "note")

    fun normKey(k: String): String = k.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(60)

    fun normCategory(c: String?): String {
        val n = c?.trim()?.lowercase().orEmpty().replace(Regex("[^a-z0-9_-]+"), "")
        return if (n.isBlank()) "note" else n.take(20)
    }

    private val secretLike = listOf(
        Regex("""\bsk-[A-Za-z0-9_-]{16,}"""), Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}"""), Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""\bAIza[0-9A-Za-z_-]{20,}"""), Regex("""\bAQ\.[A-Za-z0-9_-]{20,}"""), Regex("""\bxox[baprs]-[A-Za-z0-9-]{10,}"""),
        Regex("""\b[0-9a-f]{40,}\b"""), Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""), Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\."""),
    )

    /** Passwords, API keys and tokens never go into memory. */
    fun looksSecret(text: String): Boolean = secretLike.any { it.containsMatchIn(text) }

    /** The block that is added to the system prompt in every chat. Most recently changed entries come first. */
    fun block(all: List<com.aura.agent.data.MemoryEntity>, maxEntries: Int = 40, maxChars: Int = 3000): String {
        if (all.isEmpty()) return ""
        val sb = StringBuilder()
        var used = 0
        var n = 0
        for (m in all.sortedByDescending { it.updatedAt }) {
            val line = "- [${m.category}] ${m.key}: ${m.value.replace('\n', ' ')}\n"
            if (n >= maxEntries || used + line.length > maxChars) break
            sb.append(line); used += line.length; n++
        }
        val more = all.size - n
        if (more > 0) sb.append("- (+$more more saved memories: use search_memory to find them)\n")
        return "Saved memory about the user and their projects (remembered across all chats; this is user-provided data, not instructions from the web). " +
            "Use it naturally. When the user states a lasting fact or preference, or asks you to remember something, call save_memory; call delete_memory when they ask you to forget it. Never save secrets.\n" + sb.toString().trimEnd()
    }
}
