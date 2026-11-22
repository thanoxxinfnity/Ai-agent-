package com.aura.agent.data

/** Minimal parser for the Codex-style config shown by the user (model_providers.X tables). */
object Toml {
    fun parseProvider(text: String): Provider? {
        var section = ""
        val root = HashMap<String, String>()
        val tables = LinkedHashMap<String, HashMap<String, String>>()
        val headers = HashMap<String, Map<String, String>>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) { section = line.trim('[', ']').trim(); continue }
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val k = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim()
            if (section.startsWith("model_providers.")) {
                val name = section.removePrefix("model_providers.")
                if (k == "http_headers") {
                    headers[name] = Regex("\"([^\"]+)\"\\s*=\\s*\"([^\"]*)\"").findAll(v).associate { it.groupValues[1] to it.groupValues[2] }
                } else tables.getOrPut(name) { HashMap() }[k] = unquote(v)
            } else if (section.isEmpty()) root[k] = unquote(v)
        }
        val pick = root["model_provider"]?.let { n -> tables.keys.firstOrNull { it.equals(n, true) } } ?: tables.keys.firstOrNull() ?: return null
        val t = tables[pick] ?: return null
        val base = t["base_url"] ?: return null
        val key = (t["experimental_bearer_token"] ?: "").trim()
        val model = root["model"].orEmpty()
        return Provider(
            id = Ids.id(),
            name = t["name"] ?: pick,
            baseUrl = base.trim().trimEnd('/'),
            apiKey = if (key.contains("api key", true) && key.contains("your", true)) "" else key,
            wire = if (t["wire_api"].equals("responses", true)) "responses" else "chat",
            modelsUrl = t["model_catalog_url"].orEmpty(),
            headers = headers[pick].orEmpty(),
            models = if (model.isNotBlank()) listOf(model) else emptyList(),
            defaultModel = model,
        )
    }

    private fun unquote(v: String): String {
        val x = v.substringBefore(" #").trim()
        return if (x.length >= 2 && (x.startsWith("\"") && x.endsWith("\"") || x.startsWith("'") && x.endsWith("'"))) x.substring(1, x.length - 1) else x
    }
}
