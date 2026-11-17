package com.zyro.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Very fast free AI services with an OpenAI-style API (Groq first, Cerebras second). Text-only calls try them first
 * and anything that fails (limit, network, bad answer) falls through to the next one and finally to Gemini.
 */
object Groq {
    private class Prov(val name: String, val url: String, val key: () -> String, val models: List<String>, val tokenField: String)

    private val PROVS = listOf(
        Prov("groq", "https://api.groq.com/openai/v1/chat/completions", { Prefs.groqKey },
            listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "llama-3.1-8b-instant"), "max_tokens"),
        Prov("cerebras", "https://api.cerebras.ai/v1/chat/completions", { Prefs.cerebrasKey },
            listOf("gpt-oss-120b", "llama3.1-8b", "qwen-3-32b"), "max_completion_tokens")
    )

    private val restUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val offUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** HTTP code of the last failed call (0 = network, 401/403 = bad key, 429 = limit). */
    @Volatile var lastFail = 0
    /** Which service answered last, for the log. */
    @Volatile var lastProvider = ""

    private fun usable(p: Prov): Boolean = p.key().isNotBlank() && System.currentTimeMillis() >= (offUntil[p.name] ?: 0L)

    val ready: Boolean get() = PROVS.any { usable(it) }

    fun chat(prompt: String, maxTokens: Int, timeoutMs: Int, json: Boolean): String? {
        for (prov in PROVS) {
            if (!usable(prov)) continue
            val out = ask(prov, prompt, maxTokens, timeoutMs, json)
            if (out != null) { lastProvider = prov.name; return out }
        }
        return null
    }

    /** Used by "Zyro check": asks one service directly and returns the time in ms, or null. */
    fun ping(which: String): Long? {
        val prov = PROVS.firstOrNull { it.name == which } ?: return null
        if (prov.key().isBlank()) return null
        offUntil.remove(prov.name)
        val t0 = System.currentTimeMillis()
        return if (ask(prov, "Reply with exactly {\"a\":1}", 20, 12000, true) != null) System.currentTimeMillis() - t0 else null
    }

    private fun ask(prov: Prov, prompt: String, maxTokens: Int, timeoutMs: Int, json: Boolean): String? {
        for (model in prov.models) {
            val rk = prov.name + "/" + model
            if ((restUntil[rk] ?: 0L) > System.currentTimeMillis()) continue
            val reasoning = model.startsWith("openai/") || model.startsWith("gpt-oss")
            for (withFormat in if (json) listOf(true, false) else listOf(false)) {
                try {
                    val body = JSONObject()
                        .put("model", model)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                        .put(prov.tokenField, if (reasoning) maxTokens + 400 else maxTokens)
                        .put("temperature", 0.2)
                    if (reasoning) body.put("reasoning_effort", "low")
                    if (withFormat) body.put("response_format", JSONObject().put("type", "json_object"))
                    val c = java.net.URL(prov.url).openConnection() as java.net.HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 6000
                    c.readTimeout = timeoutMs
                    c.doOutput = true
                    c.setRequestProperty("content-type", "application/json")
                    c.setRequestProperty("authorization", "Bearer " + prov.key().trim())
                    c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    if (code in 200..299) {
                        val out = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
                        if (out.isNotBlank()) { lastFail = 0; return out }
                        break
                    }
                    lastFail = code
                    when (code) {
                        401, 403 -> { offUntil[prov.name] = System.currentTimeMillis() + 10 * 60_000L; return null } // wrong key: stop trying for a while
                        429 -> { restUntil[rk] = System.currentTimeMillis() + 30_000L; break }
                        400 -> { if (withFormat) continue else { restUntil[rk] = System.currentTimeMillis() + 10 * 60_000L; break } }
                        404 -> { restUntil[rk] = System.currentTimeMillis() + 30 * 60_000L; break }
                        else -> break
                    }
                } catch (e: Exception) {
                    lastFail = 0
                    break
                }
            }
        }
        return null
    }
}
