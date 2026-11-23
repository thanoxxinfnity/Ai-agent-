package com.aura.agent.net

import com.aura.agent.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request

/**
 * Supabase holds only the developer's public app settings (the GitHub OAuth Client ID). Nothing about the user is ever sent
 * there: no chats, no memory, no keys, no sign-up or login. The phone asks for the settings only when it does not have them,
 * so the storage on Supabase never grows with users and the app never waits on it.
 */
class Supabase(private val store: Store) {
    @Volatile var lastStatus: String = ""

    private fun base() = store.supaUrl.value.trimEnd('/')

    /**
     * Reads the public settings. Requests are rare on purpose: a Client ID already on the phone is refreshed at most once a week;
     * while it is missing, at most once every ten minutes (so a missing network never makes every start slower). [force] is for
     * the moment the user taps Sign in and the value is still missing.
     */
    suspend fun loadConfig(force: Boolean = false) {
        if (store.supaUrl.value.isBlank() || store.supaKey.value.isBlank()) return
        val now = System.currentTimeMillis()
        val known = store.githubClientId.value.isNotBlank()
        if (!force) {
            if (known && now - store.configFetchedAt < KNOWN_REFRESH_MS) return
            if (!known && now - store.configTriedAt < RETRY_MISSING_MS) return
        }
        store.configTriedAt = now
        runCatching {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(base() + "/rest/v1/app_config?select=key,value")
                    .header("apikey", store.supaKey.value).get().build()
                Http.client.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { lastStatus = "Settings server answered ${r.code}"; return@use }
                    val arr = AppJsonLenient.parseToJsonElement(r.body?.string().orEmpty()) as? JsonArray ?: return@use
                    arr.forEach { e ->
                        val o = e as? JsonObject ?: return@forEach
                        val v = (o["value"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        if ((o["key"] as? JsonPrimitive)?.contentOrNull == "github_client_id" && v.isNotBlank()) store.setGithubClientId(v)
                    }
                    store.configFetchedAt = System.currentTimeMillis()
                    lastStatus = "Settings loaded"
                }
            }
        }
    }

    companion object {
        const val KNOWN_REFRESH_MS = 7L * 24 * 3600_000L
        const val RETRY_MISSING_MS = 10L * 60_000L
    }
}
