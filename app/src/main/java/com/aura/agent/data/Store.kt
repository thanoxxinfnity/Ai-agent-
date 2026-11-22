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
    private val _theme = MutableStateFlow(s("theme", "system"))
    val theme = _theme.asStateFlow()
    private val _supaUrl = MutableStateFlow(s("supaUrl", BuildConfig.SUPABASE_URL))
    val supaUrl = _supaUrl.asStateFlow()
    private val _supaKey = MutableStateFlow(s("supaKey", BuildConfig.SUPABASE_KEY))
    val supaKey = _supaKey.asStateFlow()
    private val _supaSync = MutableStateFlow(prefs.getBoolean("supaSync", true))
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

    fun select(providerId: String, model: String) {
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
