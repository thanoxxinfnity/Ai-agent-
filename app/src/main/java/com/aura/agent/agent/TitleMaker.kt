package com.aura.agent.agent

/**
 * Chat names in the style of Gemini, Claude and ChatGPT: a short title for the TOPIC the user raised, made from what the user
 * wrote, never from the assistant's answer.
 */
object TitleMaker {
    const val SYSTEM = "You write chat titles."
    const val MAX = 50

    private val smallTalk = setOf(
        "hi", "hii", "hiii", "hello", "hlo", "hey", "heyy", "yo", "hola", "namaste", "namaskar", "salam", "sup", "ok", "okay", "thanks", "thank", "you", "thx", "test", "testing",
        "good", "morning", "evening", "afternoon", "night", "there", "bro", "bhai", "yaar", "how", "are", "u", "kaise", "ho", "kya", "haal", "hal", "hai", "aur", "bol", "bolo",
        "नमस्ते", "हेलो", "हाय", "हैलो", "कैसे", "हो", "भाई", "यार", "धन्यवाद", "शुक्रिया",
    )

    /** True when everything the user wrote so far is a greeting or thanks, so there is no topic to name yet. */
    fun onlySmallTalk(userMessages: List<String>): Boolean {
        val words = userMessages.joinToString(" ").lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotBlank() }
        return words.isEmpty() || words.all { it in smallTalk }
    }

    fun prompt(userMessages: List<String>, attachmentNames: List<String> = emptyList()): String {
        val msgs = userMessages.take(2).mapIndexed { i, m -> "${i + 1}. ${m.trim().take(400)}" }.joinToString("\n")
        return "Write the title of a chat, the way Gemini, Claude and ChatGPT name their chats.\n" +
            "Rules: name the TOPIC or TASK the user wants, in 2 to 7 words (about 18 to 45 characters). Base it on what the USER asked. " +
            "Do not repeat the question word for word and do not start with Help, Chat, Question or Conversation. " +
            "Use the language and script the user writes in (Hindi in Devanagari stays Devanagari, Hinglish stays Roman Hinglish); in English use Title Case. " +
            "No quotes, no emoji, no ending punctuation, no explanation. Reply with the title only.\n" +
            "Examples: Fix login crash in Android app | Python script to rename files | GitHub Actions APK build | ऐप का लॉगिन क्रैश ठीक करना\n\n" +
            "User messages:\n$msgs" + if (attachmentNames.isNotEmpty()) "\nAttached files: ${attachmentNames.take(5).joinToString(", ")}" else ""
    }

    private val answerStarts = Regex("""(?i)^(sure|certainly|of course|here('s| is| are)|i('ll| will| can| am|'m)|as an ai|okay|ok,|yes|no,|sorry|great|हाँ|जी हाँ|ज़रूर|बिल्कुल)\b""")
    private val genericTitles = setOf("chat", "new chat", "conversation", "title", "untitled", "chat title", "question")

    /** Cleans what the model returned. Empty when it is not usable (an answer instead of a title, a copy of the reply, too generic). */
    fun clean(raw: String, firstReply: String = ""): String {
        var t = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        t = t.replace(Regex("""(?i)^(title|chat title|नाम|शीर्षक)\s*[:\-]\s*"""), "")
        t = t.trim('"', '\'', '`', '*', '#', '_', '“', '”', '‘', '’', ' ').trimEnd('.', '!', '?', ',', ':', ';', '।')
        t = t.replace(Regex("""\s+"""), " ")
        t = t.replace(Regex("""[\p{So}\p{Cs}]"""), "").trim()
        if (t.length > MAX) { val cut = t.take(MAX); t = cut.substringBeforeLast(' ', cut).ifBlank { cut }.trimEnd('.', ',', ' ') }
        if (t.length < 3 || t.lowercase() in genericTitles || answerStarts.containsMatchIn(t)) return ""
        val reply = firstReply.trim().lowercase()
        if (reply.length >= 12 && t.length >= 12 && (reply.startsWith(t.lowercase()) || reply.take(60).contains(t.lowercase()))) return ""   // a title copied from the answer
        return t
    }

    private val fillerStart = Regex(
        """(?i)^(please|pls|plz|kindly|can you|could you|would you|will you|can u|i want to|i want|i need to|i need|i would like to|help me to|help me|let's|lets|tell me|show me|give me|make me|mujhe|mere liye|bhai|yaar|bro|hey|hi|hello|ok|okay|so|अरे|कृपया|मुझे|भाई|यार|क्या तुम|क्या आप)\b[\s,]*"""
    )

    /** A title made from the user's own words when the model cannot be used. */
    fun fallback(userMessages: List<String>): String {
        var s = userMessages.firstOrNull { it.isNotBlank() }.orEmpty().trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        s = s.replace(Regex("""https?://\S+"""), "").trim()
        repeat(4) { s = s.replace(fillerStart, "").trim() }
        val words = s.split(Regex("""\s+""")).filter { it.isNotBlank() }.take(7)
        var t = words.joinToString(" ").trim('"', '\'', '`', ' ').trimEnd('.', '!', '?', ',', ':', ';', '।')
        if (t.length > 40) { val cut = t.take(40); t = cut.substringBeforeLast(' ', cut).ifBlank { cut } }
        if (t.length < 3) return "Quick chat"
        return t.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
