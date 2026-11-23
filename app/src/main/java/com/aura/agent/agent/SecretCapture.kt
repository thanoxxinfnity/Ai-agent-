package com.aura.agent.agent

import com.aura.agent.data.Extras
import com.aura.agent.data.Ids
import com.aura.agent.data.VaultKey

/**
 * When the user pastes a key into the chat, it goes straight into the hidden key box and is replaced by a short note before the
 * message is saved or sent anywhere. So the model never sees it, it is not in the chat history, and it cannot end up in a file.
 */
object SecretCapture {
    class Result(val text: String, val saved: List<String>, val removed: Int)

    fun capture(text: String, extras: Extras): Result {
        val found = SecretScan.extract(text)
        if (found.isEmpty()) return Result(text, emptyList(), 0)
        var out = text
        val saved = ArrayList<String>(); var removed = 0
        // longest first, so a value that contains another one is replaced whole
        for (f in found.sortedByDescending { it.value.length }) {
            if (f.isBlock) { out = out.replace(f.value, "[private key removed: Aura never keeps private keys in chat]"); removed++; continue }
            val existing = extras.vault.value.firstOrNull { it.key == f.value }
            val name = existing?.name ?: uniqueName(f.slug, extras)
            if (existing == null) extras.upsertVault(VaultKey(Ids.id(), name, f.host, f.value, f.style, f.param))
            out = out.replace(f.value, "[key saved in the key box as '$name']")
            if (name !in saved) saved += name
        }
        return Result(out, saved, removed)
    }

    private fun uniqueName(slug: String, extras: Extras): String {
        val base = extras.vaultName(slug).ifBlank { "key" }
        var n = base; var i = 2
        while (extras.vault.value.any { it.name == n }) { n = "$base-$i"; i++ }
        return n
    }
}
