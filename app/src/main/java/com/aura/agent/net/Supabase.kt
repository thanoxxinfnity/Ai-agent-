package com.aura.agent.net

import com.aura.agent.data.ChatEntity
import com.aura.agent.data.MessageEntity
import com.aura.agent.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Best-effort backup to Supabase using an anonymous session. Never throws to callers. */
class Supabase(private val store: Store) {
    @Volatile var lastStatus: String = ""

    private fun enabled() = store.supaSync.value && store.supaUrl.value.isNotBlank() && store.supaKey.value.isNotBlank()
    private fun base() = store.supaUrl.value.trimEnd('/')

    private suspend fun post(path: String, body: JsonElement, headers: Map<String, String> = emptyMap()): JsonObject? = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(base() + path).header("apikey", store.supaKey.value)
            .post(body.toString().toRequestBody(Http.JSON))
        headers.forEach { (k, v) -> b.header(k, v) }
        Http.client.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw LlmError("Supabase ${r.code}: ${t.take(200)}")
            if (t.isBlank()) null else AppJsonLenient.parseToJsonElement(t) as? JsonObject
        }
    }

    private fun adopt(o: JsonObject?): Boolean {
        val at = (o?.get("access_token") as? JsonPrimitive)?.contentOrNull ?: return false
        store.supaAccess = at
        (o["refresh_token"] as? JsonPrimitive)?.contentOrNull?.let { store.supaRefresh = it }
        return true
    }

    private suspend fun ensureSession() {
        if (store.supaRefresh.isNotBlank()) {
            val ok = runCatching {
                adopt(post("/auth/v1/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", store.supaRefresh) }))
            }.getOrDefault(false)
            if (ok) return
        }
        if (!adopt(post("/auth/v1/signup", buildJsonObject {}))) throw LlmError("Anonymous sign-in failed")
    }

    private suspend fun upsert(table: String, rows: JsonArray, conflict: String) {
        if (rows.isEmpty()) return
        post(
            "/rest/v1/$table?on_conflict=$conflict", rows,
            mapOf("Authorization" to "Bearer ${store.supaAccess}", "Prefer" to "resolution=merge-duplicates,return=minimal"),
        )
    }

    private suspend fun <T> guarded(block: suspend () -> T) {
        if (!enabled()) return
        try {
            ensureSession()
            block()
            lastStatus = "Synced"
        } catch (e: Exception) {
            lastStatus = e.message.orEmpty().take(120)
        }
    }

    suspend fun pushChat(chat: ChatEntity, msgs: List<MessageEntity>) = guarded {
        upsert("chats", buildJsonArray {
            add(buildJsonObject {
                put("id", chat.id); put("title", chat.title); put("created_at", chat.createdAt); put("updated_at", chat.updatedAt)
            })
        }, "id")
        upsert("messages", buildJsonArray {
            msgs.filter { it.role != "tool" || it.content.isNotBlank() }.forEach { m ->
                add(buildJsonObject {
                    put("id", m.id); put("chat_id", m.chatId); put("role", m.role)
                    put("content", m.content); put("created_at", m.createdAt)
                })
            }
        }, "id")
    }

    suspend fun pushSecret(name: String, value: String) = guarded {
        upsert("user_secrets", buildJsonArray {
            add(buildJsonObject { put("name", name); put("value", value) })
        }, "user_id,name")
    }

    /** Used by Settings to test the connection. */
    suspend fun test(): String {
        guarded { }
        return lastStatus.ifBlank { "Sync is turned off" }
    }
}
