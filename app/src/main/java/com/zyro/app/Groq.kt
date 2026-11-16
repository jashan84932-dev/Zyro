package com.zyro.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Groq: a very fast free AI service (OpenAI-style API). Text-only calls try it first,
 * and anything that fails (limit, network, bad answer) falls back to Gemini.
 */
object Groq {
    private val MODELS = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "llama-3.1-8b-instant")
    private val restUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var allOffUntil = 0L

    /** HTTP code of the last failed call (0 = network, 401/403 = bad key, 429 = limit). */
    @Volatile var lastFail = 0

    val ready: Boolean get() = Prefs.groqKey.isNotBlank() && System.currentTimeMillis() >= allOffUntil

    fun chat(prompt: String, maxTokens: Int, timeoutMs: Int, json: Boolean): String? {
        if (!ready) return null
        val now0 = System.currentTimeMillis()
        for (model in MODELS) {
            if ((restUntil[model] ?: 0L) > now0) continue
            for (withFormat in if (json) listOf(true, false) else listOf(false)) {
                try {
                    val body = JSONObject()
                        .put("model", model)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                        .put("max_tokens", if (model.startsWith("openai/")) maxTokens + 400 else maxTokens)
                        .put("temperature", 0.2)
                    if (model.startsWith("openai/")) body.put("reasoning_effort", "low")
                    if (withFormat) body.put("response_format", JSONObject().put("type", "json_object"))
                    val c = java.net.URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as java.net.HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 6000
                    c.readTimeout = timeoutMs
                    c.doOutput = true
                    c.setRequestProperty("content-type", "application/json")
                    c.setRequestProperty("authorization", "Bearer " + Prefs.groqKey.trim())
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
                        401, 403 -> { allOffUntil = System.currentTimeMillis() + 10 * 60_000L; return null } // wrong key: stop trying for a while
                        429 -> { restUntil[model] = System.currentTimeMillis() + 30_000L; break }
                        400 -> { if (withFormat) continue else { restUntil[model] = System.currentTimeMillis() + 10 * 60_000L; break } }
                        404 -> { restUntil[model] = System.currentTimeMillis() + 30 * 60_000L; break }
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
