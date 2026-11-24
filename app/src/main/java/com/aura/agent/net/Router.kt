package com.aura.agent.net

import com.aura.agent.data.Provider
import com.aura.agent.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Keeps requests to one provider at least 60/rpm seconds apart, shared by every chat and sub-agent. */
object RateLimiter {
    private val next = ConcurrentHashMap<String, Long>()
    private val mutex = Mutex()

    fun rpmFor(p: Provider): Int = if (p.rpm > 0) p.rpm else when {
        "generativelanguage.googleapis.com" in p.baseUrl -> 10
        "integrate.api.nvidia.com" in p.baseUrl -> 30
        "api.groq.com" in p.baseUrl -> 25
        "pollinations.ai" in p.baseUrl -> 6
        else -> 0   // unknown host: no throttling
    }

    suspend fun acquire(p: Provider) {
        val rpm = rpmFor(p)
        if (rpm <= 0) return
        val gap = 60_000L / rpm
        val wait = mutex.withLock {
            val now = System.currentTimeMillis()
            val start = maxOf(now, next[p.id] ?: 0L)
            next[p.id] = start + gap
            start - now
        }
        if (wait > 0) delay(wait)
    }

    fun reset() = next.clear()
}

/** Providers (and models) that recently said "busy" or "not allowed" are skipped for a while in Auto mode. */
object Cooldowns {
    private val until = ConcurrentHashMap<String, Long>()
    fun key(p: Provider, model: String) = p.id + "|" + model
    fun mark(key: String, ms: Long) { until[key] = System.currentTimeMillis() + ms }
    fun remaining(key: String): Long = ((until[key] ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0)
    fun reset() = until.clear()
}

object LlmRouter {
    class Hooks(
        val onSwitch: suspend (String) -> Unit = {},
        val onWait: suspend (Int) -> Unit = {},
        /** the provider's own count of the prompt tokens of the request that just ran */
        val onUsage: (Int) -> Unit = {},
    )

    private fun switchable(e: LlmError) = e.code == 0 || e.code in setOf(401, 403, 408, 429) || e.code in 500..599

    private fun cooldownMs(e: LlmError) = when {
        e.code == 429 -> (e.retryAfterSec ?: 60L) * 1000
        e.code == 401 || e.code == 403 -> 30 * 60_000L
        else -> 30_000L
    }

    private fun reason(e: LlmError) = when {
        e.code == 429 -> "it is rate limited"
        e.code == 401 -> "its key was rejected"
        e.code == 403 -> "it refused this app"
        e.code >= 500 -> "it had a server problem"
        else -> "it did not answer"
    }

    /**
     * Normal mode: one provider, waits out a 429 with a countdown.
     * Auto mode (only when the user picked it): a provider that fails with 429, auth, block or server errors before
     * producing text is put on cooldown and the next one is tried, so the chat keeps going.
     */
    suspend fun stream(
        store: Store, system: String, msgs: List<LlmMsg>, tools: List<ToolDef>,
        hooks: Hooks = Hooks(), effort: String? = null, onText: (String) -> Unit,
    ): List<ToolCall> {
        val auto = store.autoMode.value
        val cands = if (auto) store.candidates() else listOfNotNull(store.current())
        if (cands.isEmpty()) throw LlmError("No model selected. Open Settings, add a provider and pick a model.")
        if (!auto) cands[0].let { (p, m) -> ModelGuard.reason(p, m)?.let { throw LlmError(ModelGuard.warning(p, m, it), 400) } }
        if (!auto) return Llm.stream(cands[0].first, cands[0].second, system, msgs, tools, onText, 3, hooks.onWait, effort, hooks.onUsage)

        val ready = cands.filter { Cooldowns.remaining(Cooldowns.key(it.first, it.second)) == 0L }
        val cooling = cands.filter { Cooldowns.remaining(Cooldowns.key(it.first, it.second)) > 0 }.sortedBy { Cooldowns.remaining(Cooldowns.key(it.first, it.second)) }
        val queue = ready + cooling.take(1)
        var emitted = false
        var lastErr: Exception? = null
        var why = ""
        for ((i, c) in queue.withIndex()) {
            val key = Cooldowns.key(c.first, c.second)
            val rem = Cooldowns.remaining(key)
            if (rem > 0) for (left in (rem / 1000).toInt().coerceIn(1, 60) downTo 1) { hooks.onWait(left); delay(1000) }
            if (i > 0) hooks.onSwitch("Switched to ${c.second} (${c.first.name}) because ${queue[i - 1].second} $why.")
            try {
                return Llm.stream(c.first, c.second, system, msgs, tools, { emitted = true; onText(it) }, if (i == queue.lastIndex) 3 else 0, hooks.onWait, effort, hooks.onUsage)
            } catch (e: LlmError) {
                if (emitted || !switchable(e)) throw e
                Cooldowns.mark(key, cooldownMs(e)); lastErr = e; why = "is not available: " + reason(e).removePrefix("it ")
            } catch (e: IOException) {
                if (emitted) throw e
                Cooldowns.mark(key, 30_000L); lastErr = e; why = "did not answer (network)"
            }
        }
        throw lastErr ?: LlmError("No provider answered.")
    }
}
