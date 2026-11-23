package com.aura.agent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.agent.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    /** "chat" = /chat/completions, "responses" = /responses */
    val wire: String = "chat",
    val modelsUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    /** requests per minute this provider allows; 0 = Aura's built-in default for known hosts */
    val rpm: Int = 0,
    /** "auto" sends the reasoning level only to hosts known to accept it; "on" always; "off" never */
    val reasoning: String = "auto",
)

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

/** Everything persistent that is small: encrypted at rest. */
class Store(ctx: Context) {
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "aura_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Throwable) {
        ctx.getSharedPreferences("aura_plain", Context.MODE_PRIVATE)
    }

    private fun s(k: String, d: String = "") = prefs.getString(k, d) ?: d

    private val _providers = MutableStateFlow(
        runCatching { AppJson.decodeFromString<List<Provider>>(s("providers", "[]")) }.getOrDefault(emptyList())
    )
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    private val _activeProvider = MutableStateFlow(s("activeProvider"))
    val activeProvider = _activeProvider.asStateFlow()
    private val _activeModel = MutableStateFlow(s("activeModel"))
    val activeModel = _activeModel.asStateFlow()
    private val _githubToken = MutableStateFlow(s("githubToken"))
    val githubToken = _githubToken.asStateFlow()
    private val _githubUser = MutableStateFlow(s("githubUser"))
    val githubUser = _githubUser.asStateFlow()
    private val _githubClientId = MutableStateFlow(s("githubClientId"))
    val githubClientId = _githubClientId.asStateFlow()
    fun setGithubClientId(v: String) { _githubClientId.value = v.trim(); prefs.edit().putString("githubClientId", v.trim()).apply() }
    private val _csPat = MutableStateFlow(s("codespacePat"))
    val codespacePat = _csPat.asStateFlow()
    private val _csUser = MutableStateFlow(s("codespaceUser"))
    val codespaceUser = _csUser.asStateFlow()
    fun setCodespace(pat: String, user: String) {
        _csPat.value = pat; _csUser.value = user
        prefs.edit().putString("codespacePat", pat).putString("codespaceUser", user).apply()
    }
    /** Name of a codespace this app started and has not stopped yet (crash recovery). */
    var activeCodespace: String
        get() = s("activeCodespace"); set(v) { prefs.edit().putString("activeCodespace", v).apply() }

    private val _guardrails = MutableStateFlow(prefs.getBoolean("guardrails", true))
    val guardrails = _guardrails.asStateFlow()
    fun setGuardrails(v: Boolean) { _guardrails.value = v; prefs.edit().putBoolean("guardrails", v).apply() }
    private val _contextTokens = MutableStateFlow(prefs.getInt("contextTokens", 128_000))
    val contextTokens = _contextTokens.asStateFlow()
    fun setContextTokens(v: Int) { _contextTokens.value = v; prefs.edit().putInt("contextTokens", v).apply() }

    // ---- read aloud (Gemini voice): the user's own key, kept only on this device
    private val _voiceKey = MutableStateFlow(s("voiceKey"))
    val voiceKeyOwn = _voiceKey.asStateFlow()
    fun setVoiceKey(k: String) { _voiceKey.value = k.trim(); prefs.edit().putString("voiceKey", k.trim()).apply() }
    private val _voiceName = MutableStateFlow(s("voiceName", "Kore"))
    val voiceNameFlow = _voiceName.asStateFlow()
    fun voiceName(): String = _voiceName.value.ifBlank { "Kore" }
    fun setVoiceName(n: String) { _voiceName.value = n; prefs.edit().putString("voiceName", n).apply() }

    /** The voice key, or the key of the user's own Gemini provider when no separate one was entered. */
    fun voiceKey(): String = _voiceKey.value.ifBlank {
        _providers.value.firstOrNull { "generativelanguage.googleapis.com" in it.baseUrl && it.apiKey.isNotBlank() }?.apiKey.orEmpty()
    }

    private val _thinkMode = MutableStateFlow(com.aura.agent.agent.ThinkMode.fromId(s("thinkMode")))
    val thinkMode = _thinkMode.asStateFlow()
    fun setThinkMode(m: com.aura.agent.agent.ThinkMode) { _thinkMode.value = m; prefs.edit().putString("thinkMode", m.id).apply() }

    private val _vercelToken = MutableStateFlow(s("vercelToken"))
    val vercelToken = _vercelToken.asStateFlow()
    private val _vercelUser = MutableStateFlow(s("vercelUser"))
    val vercelUser = _vercelUser.asStateFlow()
    private val _vercelTeam = MutableStateFlow(s("vercelTeam"))
    val vercelTeam = _vercelTeam.asStateFlow()
    fun setVercel(token: String, user: String, team: String) {
        _vercelToken.value = token; _vercelUser.value = user; _vercelTeam.value = team
        prefs.edit().putString("vercelToken", token).putString("vercelUser", user).putString("vercelTeam", team).apply()
    }

    private val _theme = MutableStateFlow(s("theme", "system"))
    val theme = _theme.asStateFlow()
    private val _supaUrl = MutableStateFlow(s("supaUrl", BuildConfig.SUPABASE_URL))
    val supaUrl = _supaUrl.asStateFlow()
    private val _supaKey = MutableStateFlow(s("supaKey", BuildConfig.SUPABASE_KEY))
    val supaKey = _supaKey.asStateFlow()
    /** Chat backup is off: it caused needless backend traffic. Users' keys and tokens are never uploaded. */
    private val _supaSync = MutableStateFlow(false)
    val supaSync = _supaSync.asStateFlow()

    private val _userSkills = MutableStateFlow(
        runCatching { AppJson.decodeFromString<List<Skill>>(s("userSkills", "[]")) }.getOrDefault(emptyList())
    )
    val userSkills: StateFlow<List<Skill>> = _userSkills.asStateFlow()
    private val _skillsOff = MutableStateFlow(s("skillsOff").split(",").filter { it.isNotBlank() }.toSet())
    val skillsOff: StateFlow<Set<String>> = _skillsOff.asStateFlow()

    fun allSkills(): List<Skill> = Skills.builtin + _userSkills.value
    fun enabledSkills(): List<Skill> = allSkills().filter { it.id !in _skillsOff.value }
    fun setSkillEnabled(id: String, on: Boolean) {
        _skillsOff.value = if (on) _skillsOff.value - id else _skillsOff.value + id
        prefs.edit().putString("skillsOff", _skillsOff.value.joinToString(",")).apply()
    }
    fun upsertSkill(sk: Skill) {
        val cur = _userSkills.value.filterNot { it.id == sk.id || it.name == sk.name }
        _userSkills.value = cur + sk.copy(builtin = false)
        prefs.edit().putString("userSkills", AppJson.encodeToString(_userSkills.value)).apply()
    }
    fun deleteSkill(id: String) {
        _userSkills.value = _userSkills.value.filterNot { it.id == id }
        prefs.edit().putString("userSkills", AppJson.encodeToString(_userSkills.value)).apply()
    }

    var supaAccess: String
        get() = s("supaAccess"); set(v) { prefs.edit().putString("supaAccess", v).apply() }
    var supaRefresh: String
        get() = s("supaRefresh"); set(v) { prefs.edit().putString("supaRefresh", v).apply() }

    var configFetchedAt: Long
        get() = prefs.getLong("configFetchedAt", 0L); set(v) { prefs.edit().putLong("configFetchedAt", v).apply() }

    /** test helper */
    fun prefsReset() { prefs.edit().remove("resumeLog").apply() }

    /** True when a background resume is allowed; false after 3 resumes within 5 minutes (a crash loop). */
    fun allowResume(): Boolean {
        val now = System.currentTimeMillis()
        val recent = s("resumeLog").split(",").mapNotNull { it.toLongOrNull() }.filter { now - it < 5 * 60_000L }
        if (recent.size >= 3) { prefs.edit().putString("resumeLog", recent.joinToString(",")).apply(); return false }
        prefs.edit().putString("resumeLog", (recent + now).joinToString(",")).apply()
        return true
    }

    var modelsRefreshedAt: Long
        get() = prefs.getLong("modelsRefreshedAt", 0L); set(v) { prefs.edit().putLong("modelsRefreshedAt", v).apply() }

    fun saveProviders(list: List<Provider>) {
        _providers.value = list
        prefs.edit().putString("providers", AppJson.encodeToString(list)).apply()
    }

    fun upsertProvider(p: Provider) {
        val cur = _providers.value
        saveProviders(if (cur.any { it.id == p.id }) cur.map { if (it.id == p.id) p else it } else cur + p)
        if (_activeProvider.value.isBlank()) select(p.id, p.defaultModel.ifBlank { p.models.firstOrNull().orEmpty() })
    }

    fun deleteProvider(id: String) {
        saveProviders(_providers.value.filterNot { it.id == id })
        if (_activeProvider.value == id) select("", "")
    }

    private val _autoMode = MutableStateFlow(prefs.getBoolean("autoMode", false))
    /** Opt-in: when the user picks Auto, a busy or limited provider is skipped and the next one is used. */
    val autoMode = _autoMode.asStateFlow()
    fun setAuto(on: Boolean) { _autoMode.value = on; prefs.edit().putBoolean("autoMode", on).apply() }

    /** Auto mode order: the current choice first, then each other provider's default model. */
    fun candidates(): List<Pair<Provider, String>> {
        val out = ArrayList<Pair<Provider, String>>()
        current()?.let { out += it }
        for (p in _providers.value) {
            val m = p.defaultModel.ifBlank { p.models.firstOrNull().orEmpty() }
            if (m.isNotBlank() && out.none { it.first.id == p.id }) out += p to m
        }
        return out
    }

    fun select(providerId: String, model: String) {
        setAuto(false)
        _activeProvider.value = providerId; _activeModel.value = model
        prefs.edit().putString("activeProvider", providerId).putString("activeModel", model).apply()
    }

    fun setGithub(token: String, user: String) {
        _githubToken.value = token; _githubUser.value = user
        prefs.edit().putString("githubToken", token).putString("githubUser", user).apply()
    }

    fun setTheme(v: String) { _theme.value = v; prefs.edit().putString("theme", v).apply() }
    fun setSupabase(url: String, key: String, sync: Boolean) {
        _supaUrl.value = url; _supaKey.value = key; _supaSync.value = sync
        prefs.edit().putString("supaUrl", url).putString("supaKey", key).putBoolean("supaSync", sync).apply()
    }

    fun current(): Pair<Provider, String>? {
        val p = _providers.value.firstOrNull { it.id == _activeProvider.value } ?: return null
        val m = _activeModel.value.ifBlank { p.defaultModel.ifBlank { p.models.firstOrNull().orEmpty() } }
        return if (m.isBlank()) null else p to m
    }
}
