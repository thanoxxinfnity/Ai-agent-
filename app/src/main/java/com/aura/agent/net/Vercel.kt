package com.aura.agent.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

class VercelError(msg: String, val code: Int = 0) : Exception(msg)

class DeployFile(val path: String, val bytes: ByteArray) {
    val sha: String by lazy { MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) } }
}

class Deployment(val id: String, val url: String, val state: String, val alias: List<String>, val error: String?)

/** Vercel REST API with the user's own token (kept only on the device). */
class Vercel(private val token: String, private val team: String = "", private val base: String = "https://api.vercel.com") {
    private fun url(path: String, vararg q: String): String {
        val params = q.toMutableList()
        if (team.isNotBlank()) params += "teamId=$team"
        return base.trimEnd('/') + path + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    private fun req(u: String) = Request.Builder().url(u).header("Authorization", "Bearer ${token.trim()}").header("User-Agent", "Aura-Agent")

    private fun fail(code: Int, body: String): Nothing {
        val msg = runCatching { (AppJsonLenient.parseToJsonElement(body) as JsonObject)["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
        throw VercelError(when (code) {
            401, 403 -> "Vercel rejected this token (${code}). Create a token at vercel.com/account/tokens and check the Team ID."
            429 -> "Vercel is rate limiting requests (429). Wait a minute and try again."
            else -> "Vercel error $code: ${msg ?: body.take(200)}"
        }, code)
    }

    private suspend fun call(r: Request): JsonObject = withContext(Dispatchers.IO) {
        Http.client.newCall(r).execute().use { resp ->
            val b = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) fail(resp.code, b)
            if (b.isBlank()) JsonObject(emptyMap()) else AppJsonLenient.parseToJsonElement(b) as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    suspend fun whoami(): String {
        val u = call(req(url("/v2/user")).get().build())["user"]?.jsonObject ?: throw VercelError("Unexpected answer from Vercel.")
        return u["username"]?.jsonPrimitive?.contentOrNull ?: u["email"]?.jsonPrimitive?.contentOrNull ?: "Vercel account"
    }

    suspend fun upload(f: DeployFile) = withContext(Dispatchers.IO) {
        val r = req(url("/v2/files")).header("x-vercel-digest", f.sha).header("Content-Type", "application/octet-stream")
            .post(f.bytes.toRequestBody("application/octet-stream".toMediaType())).build()
        Http.client.newCall(r).execute().use { resp -> if (!resp.isSuccessful && resp.code != 409) fail(resp.code, resp.body?.string().orEmpty()) }
    }

    private fun parse(o: JsonObject) = Deployment(
        o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        (o["readyState"] ?: o["status"])?.jsonPrimitive?.contentOrNull.orEmpty(),
        (o["alias"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        (o["errorMessage"] as? JsonPrimitive)?.contentOrNull ?: (o["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull,
    )

    /** Uploads missing files, then creates the deployment. Returns once Vercel has accepted it. */
    suspend fun deploy(name: String, files: List<DeployFile>, production: Boolean, framework: String?, onProgress: (String) -> Unit = {}): Deployment {
        var uploaded = false
        while (true) {
            val body = buildJsonObject {
                put("name", name)
                putJsonArray("files") { files.forEach { f -> add(buildJsonObject { put("file", f.path); put("sha", f.sha); put("size", f.bytes.size) }) } }
                if (production) put("target", "production")
                if (!framework.isNullOrBlank()) putJsonObject("projectSettings") { put("framework", if (framework == "static") JsonNull else JsonPrimitive(framework)) }
            }
            val r = req(url("/v13/deployments", "forceNew=1", "skipAutoDetectionConfirmation=1")).post(body.toString().toRequestBody(Http.JSON)).build()
            val (code, text) = withContext(Dispatchers.IO) { Http.client.newCall(r).execute().use { it.code to it.body?.string().orEmpty() } }
            if (code in 200..299) return parse(AppJsonLenient.parseToJsonElement(text) as JsonObject)
            val missing = runCatching { (AppJsonLenient.parseToJsonElement(text) as JsonObject)["error"]?.jsonObject?.get("missing")?.jsonArray?.map { it.jsonPrimitive.content } }.getOrNull()
            if (code == 400 && !missing.isNullOrEmpty() && !uploaded) {
                var n = 0
                for (f in files.filter { it.sha in missing }) { upload(f); n++; if (n % 10 == 0) onProgress("Uploaded $n of ${missing.size} files") }
                uploaded = true
                continue
            }
            fail(code, text)
        }
    }

    suspend fun status(id: String): Deployment = parse(call(req(url("/v13/deployments/$id")).get().build()))

    suspend fun buildLog(id: String): String = runCatching {
        withContext(Dispatchers.IO) {
            Http.client.newCall(req(url("/v3/deployments/$id/events", "builds=1", "limit=300")).get().build()).execute().use { r ->
                val arr = AppJsonLenient.parseToJsonElement(r.body?.string().orEmpty()) as? JsonArray ?: return@use ""
                arr.mapNotNull { e -> val o = e as? JsonObject; o?.get("text")?.jsonPrimitive?.contentOrNull ?: o?.get("payload")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull }
                    .joinToString("\n").takeLast(2500)
            }
        }
    }.getOrDefault("")

    suspend fun waitReady(d: Deployment, timeoutSec: Int = 240, pollMs: Long = 3000, onState: (String) -> Unit = {}): Deployment {
        var cur = d
        var waited = 0L
        var last = ""
        while (cur.state !in setOf("READY", "ERROR", "CANCELED")) {
            if (waited >= timeoutSec * 1000L) throw VercelError("The deployment is still ${cur.state} after $timeoutSec seconds. Check it at vercel.com.")
            if (cur.state != last) { last = cur.state; onState(cur.state) }
            delay(pollMs); waited += pollMs
            cur = status(d.id)
        }
        return cur
    }

    /** The addresses Vercel gave the project (for example proj.vercel.app); empty when they cannot be read. */
    suspend fun projectDomains(project: String): List<String> = runCatching {
        val o = call(req(url("/v9/projects/$project/domains")).get().build())
        (o["domains"] as? JsonArray).orEmpty().mapNotNull { d ->
            val x = d as? JsonObject ?: return@mapNotNull null
            if ((x["redirect"] as? JsonPrimitive)?.contentOrNull != null) null else (x["name"] as? JsonPrimitive)?.contentOrNull
        }
    }.getOrDefault(emptyList())

    /**
     * The link to give the user. A production deployment has several addresses; the project's own address (project.vercel.app)
     * serves the site, while the longer project-team address shows Vercel's own login page. So: the project's own address when
     * it is known, otherwise the shortest address that is not a branch address.
     */
    fun publicUrl(d: Deployment, production: Boolean, project: String = "", domains: List<String> = emptyList()): String {
        val host = if (production) {
            val clean = (domains + d.alias).map { it.removePrefix("https://") }.filter { !it.contains("-git-") }.distinct()
            clean.firstOrNull { project.isNotBlank() && it.equals("$project.vercel.app", true) }
                ?: clean.filter { it.endsWith(".vercel.app") }.minByOrNull { it.length }
                ?: clean.minByOrNull { it.length } ?: d.alias.firstOrNull() ?: d.url
        } else d.url
        return "https://" + host.removePrefix("https://")
    }
}
