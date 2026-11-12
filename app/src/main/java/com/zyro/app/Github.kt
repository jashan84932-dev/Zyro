package com.zyro.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * "GitHub par repo banao aur ye files daalo" - done through the GitHub API,
 * no screen tapping. Zyro writes the files itself from the instruction.
 */
object Github {

    @Volatile private var busy = false

    private val GH = listOf("github", "गिटहब", "गिट हब", "git hub", "गिथब", "गितहब")
    private val DO = listOf("repo", "repository", "रेपो", "रिपो", "upload", "अपलोड", "push", "पुश", "daal", "डाल", "bana", "बना", "commit")

    fun handle(ctx: android.content.Context, t: String, raw: String): String? {
        val tn = Local.strip(raw)
        if (GH.none { tn.contains(Local.strip(it)) }) return null
        if (DO.none { tn.contains(Local.strip(it)) }) return null
        // "claude se baat karke ..." style jobs need the phone agent, not the API
        if (tn.contains("claude") || tn.contains("क्लॉड") || tn.contains("क्लाउड") || tn.contains("chatgpt")) return null
        if (Prefs.githubToken.isBlank()) return "GitHub token चाहिए। Settings में GITHUB token डालो।"
        if (Prefs.geminiKey.isBlank()) return "Gemini key चाहिए। Settings में डालो।"
        if (busy) return "पिछला GitHub काम चल रहा है।"
        busy = true
        Thread {
            val msg = try { run(raw) } catch (e: Exception) { "GitHub का काम नहीं हुआ: ${e.message}" } finally { busy = false }
            Assistant.get(ctx).announce(msg)
        }.start()
        return "" // working silently; I will report when done
    }

    private fun run(raw: String): String {
        val prompt = """The user said (Hinglish voice command): "$raw"
They want a GitHub repository created and files uploaded to it. Decide the repo name (lowercase, hyphens, no spaces), and WRITE COMPLETE, WORKING contents for every file that makes sense for the request. Reply as strict JSON only:
{"repo":"name","private":false,"description":"short","files":[{"path":"README.md","content":"..."}]}
Use only what was asked. If they named a repo, use exactly that name."""
        val j = Brain.quickJson(prompt, 8000, 90000) ?: return "Gemini से जवाब नहीं आया।"
        val o = JSONObject(j.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        val repo = o.optString("repo").ifBlank { "zyro-" + System.currentTimeMillis() / 1000 }
            .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "-")
        val files = o.optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return "कोई फाइल नहीं बनी।"

        val me = JSONObject(api("GET", "/user", null).second).optString("login")
        if (me.isBlank()) return "GitHub token गलत है।"
        val (code, body) = api("POST", "/user/repos", JSONObject().put("name", repo)
            .put("private", o.optBoolean("private", false)).put("description", o.optString("description")))
        if (code != 201 && code != 422) return "Repo नहीं बना: ${JSONObject(body).optString("message")}"

        var ok = 0
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val path = f.optString("path").trimStart('/')
            if (path.isBlank()) continue
            val enc = Uri(path)
            var sha: String? = null
            val g = api("GET", "/repos/$me/$repo/contents/$enc", null)
            if (g.first == 200) sha = JSONObject(g.second).optString("sha")
            val put = JSONObject().put("message", "Add $path")
                .put("content", Base64.encodeToString(f.optString("content").toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (sha != null) put.put("sha", sha)
            val r = api("PUT", "/repos/$me/$repo/contents/$enc", put)
            if (r.first in 200..201) ok++
        }
        return "हो गया। $repo में $ok फाइल डाल दीं।"
    }

    private fun Uri(path: String) = path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun api(method: String, path: String, body: JSONObject?): Pair<Int, String> {
        val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.setRequestProperty("Authorization", "Bearer ${Prefs.githubToken}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Zyro")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val s = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to s
        } finally {
            c.disconnect()
        }
    }
}
