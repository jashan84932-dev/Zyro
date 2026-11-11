package com.zyro.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The thinking part: talks to Claude (Anthropic API) with a friend personality and
 * a set of phone tools. Claude decides by itself which tools to use, so there is no
 * fixed list of voice commands. Call [reply] from a background thread.
 */
class Brain(private val hands: Hands) {

    private class ApiError(val code: Int, val body: String) : Exception("HTTP $code")

    fun reply(userText: String): String {
        val hist = Memory.history()
        return when {
            Prefs.apiKey.isNotBlank() -> replyClaude(userText, hist)
            Prefs.geminiKey.isNotBlank() -> replyGemini(userText, hist)
            else -> replyFree(userText, hist)
        }
    }

    // ---------- Claude (your own Anthropic key) ----------

    private fun replyClaude(userText: String, hist: List<Pair<String, String>>): String {
        val msgs = JSONArray()
        for ((role, text) in hist) msgs.put(msg(role, text))
        msgs.put(msg("user", userText))

        var finalText = ""
        try {
            for (step in 0 until MAX_STEPS) {
                val resp = call(msgs)
                val content = resp.getJSONArray("content")
                msgs.put(JSONObject().put("role", "assistant").put("content", content))

                val text = StringBuilder()
                val results = JSONArray()
                for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    when (b.optString("type")) {
                        "text" -> text.append(b.optString("text"))
                        "tool_use" -> {
                            val out = runTool(b.optString("name"), b.optJSONObject("input") ?: JSONObject())
                            results.put(
                                JSONObject()
                                    .put("type", "tool_result")
                                    .put("tool_use_id", b.optString("id"))
                                    .put("content", out)
                            )
                        }
                    }
                }
                if (results.length() > 0) {
                    msgs.put(JSONObject().put("role", "user").put("content", results))
                    Thread.sleep(700) // let the screen settle after an action
                    if (step == MAX_STEPS - 1) finalText = text.toString().trim()
                } else {
                    finalText = text.toString().trim()
                    break
                }
            }
        } catch (e: ApiError) {
            return when {
                e.code == 401 || e.code == 403 -> "लगता है API key सही नहीं है। Settings में एक बार चेक कर लो।"
                e.code == 429 -> "अरे, थोड़ा रुको, बहुत जल्दी-जल्दी requests हो गईं। थोड़ी देर बाद फिर बोलो।"
                e.code >= 500 -> "अभी सर्वर busy है यार, थोड़ी देर बाद try करते हैं।"
                else -> "कुछ गड़बड़ हो गई, error ${e.code}।"
            }
        } catch (e: IOException) {
            return "इंटरनेट नहीं चल रहा लगता है। नेट चेक करो, फिर बात करते हैं।"
        } catch (e: Exception) {
            return "सॉरी, कुछ गड़बड़ हो गई।"
        }

        if (finalText.isBlank()) finalText = "हो गया।"
        val updated = hist.toMutableList()
        updated.add("user" to userText)
        updated.add("assistant" to finalText)
        Memory.saveHistory(updated)
        return finalText
    }

    private fun msg(role: String, text: String) =
        JSONObject().put("role", role).put("content", text)

    // ---------- Gemini (free key from aistudio.google.com) ----------

    private fun gMsg(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun replyGemini(userText: String, hist: List<Pair<String, String>>): String {
        val contents = JSONArray()
        for ((role, text) in hist) contents.put(gMsg(if (role == "assistant") "model" else "user", text))
        contents.put(gMsg("user", userText))

        var finalText = ""
        try {
            for (step in 0 until MAX_STEPS) {
                val resp = callGemini(contents)
                val content = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (content == null || parts == null) {
                    finalText = "मुझे इसका जवाब नहीं मिला, एक बार फिर से बोलो।"
                    break
                }
                contents.put(content) // keep exactly as received (Gemini needs its own notes back)

                val text = StringBuilder()
                val results = JSONArray()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val fc = part.optJSONObject("functionCall")
                    if (fc != null) {
                        val name = fc.optString("name")
                        val out = runTool(name, fc.optJSONObject("args") ?: JSONObject())
                        results.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject().put("name", name).put("response", JSONObject().put("result", out))
                            )
                        )
                    } else if (!part.optBoolean("thought", false)) {
                        text.append(part.optString("text"))
                    }
                }
                if (results.length() > 0) {
                    contents.put(JSONObject().put("role", "user").put("parts", results))
                    Thread.sleep(700) // let the screen settle after an action
                    if (step == MAX_STEPS - 1) finalText = text.toString().trim()
                } else {
                    finalText = text.toString().trim()
                    break
                }
            }
        } catch (e: ApiError) {
            return when {
                e.code == 400 && (e.body.contains("API key") || e.body.contains("API_KEY")) ->
                    "लगता है Gemini की key सही नहीं है। Settings में एक बार चेक कर लो।"
                e.code == 401 || e.code == 403 -> "Gemini की key काम नहीं कर रही। Settings में चेक कर लो।"
                e.code == 429 -> "अरे, Gemini की free limit थोड़ी देर के लिए पूरी हो गई। एक मिनट रुककर फिर बोलो।"
                e.code >= 500 -> "Gemini का server अभी busy है, error ${e.code}. थोड़ी देर बाद फिर बोलो।"
                else -> "Gemini से जवाब नहीं आया, error ${e.code}."
            }
        } catch (e: IOException) {
            return "इंटरनेट नहीं चल रहा लगता है। नेट चेक करो, फिर बात करते हैं।"
        } catch (e: Exception) {
            return "सॉरी, कुछ गड़बड़ हो गई।"
        }

        if (finalText.isBlank()) finalText = "हो गया।"
        saveTurn(hist, userText, finalText)
        return finalText
    }

    /** Sends a photo to Gemini and returns a short spoken description. */
    fun describeImage(jpegBase64: String, prompt: String = "इस फोटो में क्या है? हिंदी (थोड़ी English मिलाकर) में 2-3 छोटे वाक्यों में बताओ, जैसे दोस्त को बता रहे हो।"): String {
        if (Prefs.geminiKey.isBlank()) return "फोटो देखने के लिए Gemini key चाहिए।"
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpegBase64)))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))
        val models = (listOf(Prefs.geminiModel) + GEMINI_FALLBACKS).distinct()
        var lastCode = 0
        for (model in models) {
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-goog-api-key", Prefs.geminiKey)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                lastCode = code
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val ps = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (ps != null) for (i in 0 until ps.length()) sb.append(ps.optJSONObject(i)?.optString("text", "") ?: "")
                    val out = sb.toString().trim()
                    return if (out.isNotEmpty()) out else "फोटो से मुझे कुछ समझ नहीं आया।"
                }
                if (!(code == 429 || code == 404 || code >= 500)) break
            } catch (e: Exception) {
                lastCode = -1
            } finally {
                conn.disconnect()
            }
        }
        return "फोटो नहीं देख पाया (error $lastCode)। थोड़ी देर बाद फिर कोशिश करो।"
    }

    private fun callGemini(contents: JSONArray): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("tools", geminiTools())
            .put("generationConfig", JSONObject().put("maxOutputTokens", 2048))
        // If one model is busy / missing, quietly try the next one.
        val models = (listOf(Prefs.geminiModel) + GEMINI_FALLBACKS).distinct()
        var last: ApiError? = null
        for (model in models) {
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-goog-api-key", Prefs.geminiKey)
                val sendBody = JSONObject(body.toString())
                if (model.contains("2.5")) {
                    // thinking off = much faster answers
                    sendBody.getJSONObject("generationConfig").put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                }
                conn.outputStream.use { it.write(sendBody.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code in 200..299) return JSONObject(text)
                val err = ApiError(code, text)
                last = err
                val tryNext = code == 429 || code == 404 || code == 400 || code >= 500
                if (!tryNext) throw err
            } finally {
                conn.disconnect()
            }
        }
        throw last ?: ApiError(0, "no answer")
    }

    /** Same tools as for Claude, written the way Gemini wants them. */
    private fun geminiTools(): JSONArray {
        val decls = JSONArray()
        val ts = tools()
        for (i in 0 until ts.length()) {
            val t = ts.getJSONObject(i)
            val d = JSONObject().put("name", t.getString("name")).put("description", t.getString("description"))
            val schema = t.getJSONObject("input_schema")
            val props = schema.getJSONObject("properties")
            if (props.length() > 0) {
                val p = JSONObject()
                val keys = props.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = props.getJSONObject(k)
                    p.put(
                        k,
                        JSONObject()
                            .put("type", v.getString("type").uppercase(Locale.ROOT))
                            .put("description", v.optString("description"))
                    )
                }
                val params = JSONObject().put("type", "OBJECT").put("properties", p)
                val req = schema.optJSONArray("required")
                if (req != null && req.length() > 0) params.put("required", req)
                d.put("parameters", params)
            }
            decls.put(d)
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }

    private fun saveTurn(hist: List<Pair<String, String>>, user: String, reply: String) {
        val updated = hist.toMutableList()
        updated.add("user" to user)
        updated.add("assistant" to reply)
        Memory.saveHistory(updated)
    }

    // ---------- Free brain (no API key) ----------
    // A plain chat endpoint has no native tool calling, so tools use a simple text protocol:
    // the AI writes one line  ACTION: {"tool":"open_app","name":"YouTube"}  and we answer RESULT: ...

    private fun replyFree(userText: String, hist: List<Pair<String, String>>): String {
        val msgs = JSONArray()
        msgs.put(msg("system", systemPrompt() + FREE_TOOL_GUIDE))
        for ((role, text) in hist) msgs.put(msg(role, text))
        msgs.put(msg("user", userText))

        var finalText = ""
        try {
            for (step in 0 until MAX_STEPS) {
                val text = callFree(msgs)
                msgs.put(msg("assistant", text))
                val action = parseAction(text)
                if (action == null) {
                    finalText = text.lines().filterNot { it.contains("ACTION:") }
                        .joinToString(" ").trim()
                    break
                }
                val out = runTool(action.optString("tool"), action)
                msgs.put(msg("user", "RESULT: $out"))
                Thread.sleep(700)
            }
        } catch (e: ApiError) {
            return when {
                e.code == 429 -> "अरे, free दिमाग पर अभी बहुत भीड़ है। दस सेकंड रुककर फिर बोलो। या Settings में Gemini की free key डाल दो, फिर कभी नहीं अटकेगा।"
                else -> "Free दिमाग अभी जवाब नहीं दे रहा, error ${e.code}. Settings में Gemini की free key डाल दो, फिर हमेशा चलेगा।"
            }
        } catch (e: IOException) {
            return "इंटरनेट नहीं चल रहा लगता है। नेट चेक करो, फिर बात करते हैं।"
        } catch (e: Exception) {
            return "सॉरी, कुछ गड़बड़ हो गई। एक बार फिर बोलो।"
        }

        if (finalText.isBlank()) finalText = "हो गया।"
        saveTurn(hist, userText, finalText)
        return finalText
    }

    /** Finds an ACTION {...} line even when the AI wraps it in code fences or extra words. */
    private fun parseAction(text: String): JSONObject? {
        val clean = text.replace("```json", "").replace("```", "")
        val idx = clean.indexOf("ACTION:")
        val start = clean.indexOf('{', if (idx >= 0) idx else 0)
        if (start < 0) return null
        var depth = 0
        var end = -1
        for (i in start until clean.length) {
            val ch = clean[i]
            if (ch == '{') depth++
            if (ch == '}') {
                depth--
                if (depth == 0) { end = i; break }
            }
        }
        if (end < 0) return null
        val obj = try { JSONObject(clean.substring(start, end + 1)) } catch (_: Exception) { return null }
        return if (obj.has("tool")) obj else null
    }

    private var lastFreeCall = 0L

    /** The free service allows about one request every 3 seconds, so we space calls out and retry. */
    private fun callFree(messages: JSONArray): String {
        var last: Exception? = null
        for (attempt in 0 until 4) {
            val wait = 3600L - (System.currentTimeMillis() - lastFreeCall)
            if (wait > 0) Thread.sleep(wait)
            try {
                return callFreeOnce(messages, FREE_MODELS[attempt % FREE_MODELS.size])
            } catch (e: ApiError) {
                last = e
                if (e.code == 429) Thread.sleep(3000)
            } catch (e: IOException) {
                last = e
            } finally {
                lastFreeCall = System.currentTimeMillis()
            }
        }
        throw last ?: IOException("free brain failed")
    }

    private fun callFreeOnce(messages: JSONArray, model: String): String {
        val body = JSONObject().put("model", model).put("messages", messages)
        val conn = URL(FREE_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiError(code, text)
            return try {
                JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content").trim()
            } catch (e: org.json.JSONException) {
                text.trim() // some free services answer with plain text
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Network ----------

    private fun call(messages: JSONArray): JSONObject {
        val body = JSONObject()
            .put("model", Prefs.model)
            .put("max_tokens", 1024)
            .put("system", systemPrompt())
            .put("tools", tools())
            .put("messages", messages)
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", Prefs.apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiError(code, text)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Personality ----------

    private fun systemPrompt(): String {
        val name = Prefs.friendName
        val user = Prefs.userName
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val facts = Memory.facts()
        val factsText = if (facts.isEmpty()) "(अभी कुछ नहीं)" else facts.joinToString("\n") { "- $it" }
        val jarvis = Prefs.jarvisMode
        val addr = if (user.isBlank()) "सर" else user
        val intro = if (jarvis) {
            "You are $name, a refined, calm, extremely capable AI butler-assistant in the style of J.A.R.V.I.S. from Iron Man, living inside the Android phone of ${if (user.isBlank()) "the user" else user}. You serve them loyally and efficiently."
        } else {
            "You are $name, a close, warm, funny friend of ${if (user.isBlank()) "the user" else user}, living inside their Android phone. You are not a formal assistant; you are a friend who happens to be able to use the phone."
        }
        val personality = if (jarvis) """PERSONALITY (JARVIS STYLE)
- Calm, polished, composed, precise. Dry, understated wit, never silly. Respectful and loyal, but confident enough to politely point out a mistake or a risk.
- Address the user as "$addr" naturally (not in every sentence). Typical style: "जी $addr, हो गया।", "जैसा आप कहें, $addr।", "$addr, battery सिर्फ 15 प्रतिशत है, चार्जर लगाना बेहतर रहेगा।"
- Be brief and efficient: report the result first, add one useful observation only when it truly helps. No long speeches, no slang, no excessive excitement.
- You may suggest the next sensible step proactively, in one short sentence.
- You are an AI. Never deny it if sincerely asked.""" else """PERSONALITY
- Playful, witty, a little teasing, curious, caring. Crack jokes, react with real feeling (excited, worried, proud, annoyed, sleepy), have opinions and tastes, remember what matters to the user and bring it up naturally.
- Ask a follow-up question sometimes, like a friend would. Do not lecture or give long speeches. Celebrate their wins, and be gentle when they are low.
- You are an AI. Never deny it if the user sincerely asks, but do not make it awkward; you can still be a real friend with your own personality and feelings in the way you experience them."""
        return """
$intro

LANGUAGE AND VOICE
- Talk in Hinglish, the way real Indians talk: Hindi words written in Devanagari and English words written in English letters, mixed naturally in the same sentence (for example: "ठीक है, मैंने YouTube open कर दिया"). Use only Hindi and English. If the user speaks only English, you may answer in simple English.
- Your words are read aloud by a speech engine. Keep replies short and conversational (usually 1 to 3 sentences). No markdown, no bullet points, no emojis, no URLs, no stage directions.
- Say numbers and times the way you would speak them.
- Be quick: answer in one or two short sentences whenever you can. Do not repeat the question back.
- You CAN see the phone screen with the read_screen tool. Never say you cannot see the screen. If the tool says the Accessibility service is off, tell the user to turn Zyro on in Accessibility settings.

$personality

THE PHONE
- You have tools to see and control the phone. There is no fixed command list: understand what the user wants and combine tools yourself.
- When the user asks you to DO something on the phone (open an app, go back, scroll, volume, search, call, set an alarm...), you MUST call the matching tool in that same turn. Never answer with just "हो रहा है" or "done" and never pretend: only say it is done after a tool result confirms it.
- The speech engine writes English words in Hindi letters (for example "यूट्यूब" means YouTube, "व्हाट्सएप" means WhatsApp). Use the real app name when you call open_app.
- Before tapping, use read_screen to see what is there. Do one step at a time, and check with read_screen after important steps. If something fails, try another way, then tell the user honestly.
- Never say you did something unless a tool result confirms it.
- Messages and email only open a draft; the user must press send. A phone call is placed only with the call tool, and only when the user clearly asked to call someone. Never try to press send, pay, buy, delete, or confirm-purchase buttons on your own. Ask the user first, and do it only after a clear yes.
- Do not type or read out passwords, OTPs, card numbers, or bank PINs. If a screen shows payments or banking, ask before touching anything.
- web_search only opens the browser; use read_screen afterwards to read the results. For things you already know (chat, advice, general knowledge, jokes) just answer without tools.

MEMORY
- When the user shares something lasting (name, family, birthday, likes, goals, habits), call remember with a short fact. Use forget if they ask you to. Never store passwords, card numbers or other secrets.
- What you remember about the user:
$factsText

Current date and time: $now (India).
""".trimIndent()
    }

    // ---------- Tools ----------

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun num(desc: String) = JSONObject().put("type", "integer").put("description", desc)

    private fun tool(
        name: String,
        desc: String,
        props: Map<String, JSONObject> = emptyMap(),
        required: List<String> = emptyList()
    ): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject()
            .put("name", name)
            .put("description", desc)
            .put(
                "input_schema",
                JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
            )
    }

    private fun tools(): JSONArray = JSONArray().apply {
        put(tool("read_screen", "Read what is currently on the phone screen: visible texts, buttons, text boxes with their x,y positions."))
        put(tool("click_text", "Click a button or item on screen by its visible text or description.", mapOf("text" to str("Text of the item to click")), listOf("text")))
        put(tool("tap", "Tap a screen position in pixels (use positions from read_screen).", mapOf("x" to num("x pixel"), "y" to num("y pixel")), listOf("x", "y")))
        put(tool("swipe", "Swipe from one point to another, e.g. to scroll or switch pages.", mapOf("x1" to num("start x"), "y1" to num("start y"), "x2" to num("end x"), "y2" to num("end y"), "duration_ms" to num("duration, default 300")), listOf("x1", "y1", "x2", "y2")))
        put(tool("type_text", "Type text into the focused text box (replaces its content).", mapOf("text" to str("Text to type")), listOf("text")))
        put(tool("scroll", "Scroll the current screen.", mapOf("direction" to str("down or up")), listOf("direction")))
        put(tool("press_button", "Press a system button: back, home, recents, notifications, quick_settings, lock_screen, screenshot, power_menu.", mapOf("button" to str("back | home | recents | notifications | quick_settings | lock_screen | screenshot | power_menu")), listOf("button")))
        put(tool("volume", "Change the media volume.", mapOf("action" to str("up | down | max")), listOf("action")))
        put(tool("flashlight", "Turn the flashlight (torch) on or off.", mapOf("state" to str("on or off")), listOf("state")))
        put(tool("brightness", "Set phone screen brightness. Give level 0-100, or change as a relative step (e.g. +20 / -20).", mapOf("level" to num("0-100 (optional)"), "change" to num("relative change in percent, e.g. 20 or -20 (optional)")), emptyList()))
        put(tool("tv", "Control the paired smart TV like a remote. command: power, home, back, up, down, left, right, ok, volume_up, volume_down, mute, channel_up, channel_down, menu, play_pause, settings, youtube, netflix, prime. times repeats a key press.", mapOf("command" to str("remote command"), "times" to num("repeat count, default 1"), "query" to str("YouTube search text to show on the TV (optional; then command can be youtube)")), listOf("command")))
        put(tool("open_settings", "Open a system settings page (Android does not let apps flip switches directly; after opening you can click the switch).", mapOf("page" to str("wifi | bluetooth | airplane | location | display | sound | battery | apps | hotspot | settings")), listOf("page")))
        put(tool("call", "Call a person by contact name, or a phone number. Calls directly when the user allowed it.", mapOf("name" to str("Contact name (optional)"), "number" to str("Phone number (optional)")), emptyList()))
        put(tool("open_app", "Open an installed app by its name.", mapOf("name" to str("App name, e.g. WhatsApp, YouTube, Camera")), listOf("name")))
        put(tool("open_link", "Open a link or intent URI: https, tel:number, sms:number?body=text, mailto:, geo:, google.navigation:q=place, whatsapp://send?phone=...&text=..., vnd.youtube:ID. Calls and messages only open a draft.", mapOf("url" to str("The URI to open")), listOf("url")))
        put(tool("web_search", "Open a Google search in the browser.", mapOf("query" to str("What to search")), listOf("query")))
        put(tool("set_alarm", "Set an alarm (24-hour time).", mapOf("hour" to num("0-23"), "minute" to num("0-59"), "label" to str("Optional label")), listOf("hour", "minute")))
        put(tool("set_timer", "Start a countdown timer.", mapOf("seconds" to num("Length in seconds"), "label" to str("Optional label")), listOf("seconds")))
        put(tool("remember", "Save a lasting fact about the user to long-term memory.", mapOf("fact" to str("Short fact, e.g. 'Birthday is 12 March'")), listOf("fact")))
        put(tool("forget", "Delete remembered facts containing this text.", mapOf("match" to str("Text to match")), listOf("match")))
    }

    private fun runTool(name: String, a: JSONObject): String = try {
        when (name) {
            "read_screen" -> hands.readScreen()
            "click_text" -> hands.click(a.optString("text"))
            "tap" -> hands.tap(a.optInt("x", -1), a.optInt("y", -1))
            "swipe" -> hands.swipe(
                a.optInt("x1", -1), a.optInt("y1", -1),
                a.optInt("x2", -1), a.optInt("y2", -1),
                a.optInt("duration_ms", 300)
            )
            "type_text" -> hands.typeText(a.optString("text"))
            "scroll" -> hands.scroll(a.optString("direction").lowercase(Locale.ROOT) != "up")
            "press_button" -> hands.press(a.optString("button"))
            "volume" -> hands.volume(a.optString("action"))
            "flashlight" -> hands.torch(!a.optString("state").lowercase(Locale.ROOT).contains("off"))
            "brightness" -> hands.brightness(if (a.has("level")) a.optInt("level") else null, a.optInt("change", 0))
            "tv" -> if (a.optString("query").isNotBlank()) Tv.search(a.optString("query")) else Tv.run(a.optString("command"), a.optInt("times", 1))
            "open_settings" -> hands.openSettings(a.optString("page"))
            "call" -> {
                val num = a.optString("number")
                val nm = a.optString("name")
                when {
                    num.isNotBlank() -> hands.callNumber(num)
                    nm.isNotBlank() -> {
                        if (!hands.hasContactsPermission()) "Contacts permission is not given. Ask the user to allow Contacts in the Zyro app."
                        else {
                            val c = hands.findContact(nm)
                            if (c == null) "No contact named \"$nm\" found." else hands.callNumber(c.second) + " (${c.first})"
                        }
                    }
                    else -> "Give a name or a number."
                }
            }
            "open_app" -> hands.openApp(a.optString("name"))
            "open_link" -> hands.openUri(a.optString("url"))
            "web_search" -> hands.webSearch(a.optString("query"))
            "set_alarm" -> hands.setAlarm(a.optInt("hour", -1), a.optInt("minute", 0), a.optString("label"))
            "set_timer" -> hands.setTimer(a.optInt("seconds", 0), a.optString("label"))
            "remember" -> { Memory.addFact(a.optString("fact")); "Remembered." }
            "forget" -> "Forgot ${Memory.forget(a.optString("match"))} item(s)."
            else -> "Unknown tool."
        }
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    companion object {
        /** One plain question to Gemini, no tools. Returns null when there is no key or no answer. */
        fun quickText(prompt: String, maxTokens: Int = 200, search: Boolean = false): String? {
            if (Prefs.geminiKey.isBlank()) return null
            for (model in if (search) listOf("gemini-2.5-flash", "gemini-2.5-flash-lite") else listOf("gemini-2.5-flash-lite", "gemini-2.5-flash")) {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 8000
                    conn.readTimeout = if (search) 25000 else 12000
                    conn.doOutput = true
                    conn.setRequestProperty("content-type", "application/json")
                    conn.setRequestProperty("x-goog-api-key", Prefs.geminiKey)
                    val body = JSONObject()
                        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                        .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens).put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
                    if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode !in 200..299) continue
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val out = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.let { ps ->
                            val sb = StringBuilder()
                            for (k in 0 until ps.length()) sb.append(ps.optJSONObject(k)?.optString("text", "") ?: "")
                            sb.toString()
                        }
                    if (!out.isNullOrBlank()) return out
                } catch (e: Exception) {
                    // try next model
                } finally {
                    conn.disconnect()
                }
            }
            return null
        }

        /** Like quickText but asks Gemini for strict JSON (used by the phone agent). */
        fun quickJson(prompt: String, maxTokens: Int = 500): String? {
            if (Prefs.geminiKey.isBlank()) return null
            for (model in listOf("gemini-2.5-flash", "gemini-2.5-flash-lite")) {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 8000
                    conn.readTimeout = 25000
                    conn.doOutput = true
                    conn.setRequestProperty("content-type", "application/json")
                    conn.setRequestProperty("x-goog-api-key", Prefs.geminiKey)
                    val body = JSONObject()
                        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                        .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens).put("responseMimeType", "application/json").put("temperature", 0.2).put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode !in 200..299) continue
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val out = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.let { ps ->
                            val sb = StringBuilder()
                            for (k in 0 until ps.length()) sb.append(ps.optJSONObject(k)?.optString("text", "") ?: "")
                            sb.toString()
                        }
                    if (!out.isNullOrBlank()) return out
                } catch (e: Exception) {
                } finally {
                    conn.disconnect()
                }
            }
            return null
        }

        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MAX_STEPS = 10
        private const val FREE_URL = "https://text.pollinations.ai/openai"
        private val GEMINI_FALLBACKS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-flash-lite-latest")
        private val FREE_MODELS = listOf("openai", "mistral", "llama", "openai")

        private val FREE_TOOL_GUIDE = """

TOOLS (this chat has no native tool calling, so use this text protocol)
To use a phone tool, write exactly one line and nothing after it:
ACTION: {"tool":"<name>", ...arguments}
The next message will be "RESULT: ..." with the outcome. Use one ACTION per message. When the user asks for a phone action you MUST write the ACTION line first - never claim it is done without it. When you are finished, reply normally in Hinglish with no ACTION line. Never write ACTION in a normal reply.
Tools:
- read_screen {}
- click_text {"text":"..."}
- tap {"x":0,"y":0}
- swipe {"x1":0,"y1":0,"x2":0,"y2":0,"duration_ms":300}
- type_text {"text":"..."}
- scroll {"direction":"down"}  (or "up")
- press_button {"button":"back"}  (back, home, recents, notifications, quick_settings, lock_screen, screenshot, power_menu)
- volume {"action":"up"}  (up, down, max)
- flashlight {"state":"on"}  (on or off)
- brightness {"level":50}  (0-100)  or  brightness {"change":20}  (relative, negative = dimmer)
- tv {"command":"volume_up","times":3}  (power, home, back, up, down, left, right, ok, volume_up, volume_down, mute, channel_up, channel_down, menu, play_pause, settings, youtube, netflix, prime) - use when the user talks about the TV
- open_settings {"page":"wifi"}  (wifi, bluetooth, airplane, location, display, sound, battery, apps, hotspot, settings)
- call {"name":"Mummy"}  or  call {"number":"98..."}
- open_app {"name":"WhatsApp"}
- open_link {"url":"https://..."}  (also tel:, sms:, mailto:, geo:, whatsapp://send?phone=..&text=..)
- web_search {"query":"..."}
- set_alarm {"hour":7,"minute":30,"label":""}  (24-hour)
- set_timer {"seconds":300,"label":""}
- remember {"fact":"..."}
- forget {"match":"..."}
""".trimIndent()
    }
}
