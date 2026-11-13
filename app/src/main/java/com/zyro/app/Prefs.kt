package com.zyro.app

import android.content.Context
import android.content.SharedPreferences

/** All user settings and small persisted state. Stored privately on this phone only. */
object Prefs {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) {
            sp = ctx.applicationContext.getSharedPreferences("micro", Context.MODE_PRIVATE)
        }
    }

    private fun read(key: String, def: String): String = sp?.getString(key, def) ?: def
    private fun write(key: String, value: String) {
        sp?.edit()?.putString(key, value)?.apply()
    }

    /** Anthropic API key (the "brain"). */
    var apiKey: String
        get() = read("apiKey", "")
        set(v) = write("apiKey", v.trim())

    /** Free Google Gemini key (aistudio.google.com) – a reliable free brain. */
    var geminiKey: String
        get() = read("geminiKey", "")
        set(v) = write("geminiKey", v.trim())

    var geminiModel: String
        get() = read("geminiModel", DEFAULT_GEMINI).ifBlank { DEFAULT_GEMINI }
        set(v) = write("geminiModel", v.trim())

    /** Show the conversation text on screen (off = voice only). */
    var showText: Boolean
        get() = sp?.getBoolean("showText", false) ?: false
        set(v) {
            sp?.edit()?.putBoolean("showText", v)?.apply()
        }

    var model: String
        get() = read("model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        set(v) = write("model", v.trim())

    /** What the friend is called. */
    var friendName: String
        get() = read("friendName", "Zyro").ifBlank { "Zyro" }
        set(v) = write("friendName", v.trim())

    /** What the friend calls you. */
    var userName: String
        get() = read("userName", "")
        set(v) = write("userName", v.trim())

    /** Use a custom cloud voice instead of the phone's built-in Hindi voice. */
    var customVoice: Boolean
        get() = sp?.getBoolean("customVoice", false) ?: false
        set(v) {
            sp?.edit()?.putBoolean("customVoice", v)?.apply()
        }

    var voiceKey: String
        get() = read("voiceKey", "")
        set(v) = write("voiceKey", v.trim())

    var voiceId: String
        get() = read("voiceId", "")
        set(v) = write("voiceId", v.trim())

    /** Only react after hearing "Hey Zyro". */
    var wakeWord: Boolean
        get() = sp?.getBoolean("wakeWord", true) ?: true
        set(v) {
            sp?.edit()?.putBoolean("wakeWord", v)?.apply()
        }

    /** Say "ruko" / "stop" while Zyro is speaking to cut it off. */
    var bargeIn: Boolean
        get() = sp?.getBoolean("bargeIn", true) ?: true
        set(v) {
            sp?.edit()?.putBoolean("bargeIn", v)?.apply()
        }

    /** Jarvis-style personality and voice (calm, formal, "Sir"). Off = friendly buddy. */
    var jarvisMode: Boolean
        get() = sp?.getBoolean("jarvisMode", true) ?: true
        set(v) {
            sp?.edit()?.putBoolean("jarvisMode", v)?.apply()
        }

    /** Your own routines, saved as JSON: {"name": ["command", "command"]}. */
    var routines: String
        get() = read("routines", "{}")
        set(v) = write("routines", v)

    /** Saved places: {"home": "...", "office": "..."}. */
    var places: String
        get() = read("places", "{}")
        set(v) = write("places", v)

    /** What you usually say at which hour (stays on the phone). */
    var habits: String
        get() = read("habits", "{}")
        set(v) = write("habits", v)

    /** Wake the screen when I say "Hey Zyro" and the phone is asleep. */
    var autoWake: Boolean
        get() = sp?.getBoolean("autoWake", true) ?: true
        set(v) {
            sp?.edit()?.putBoolean("autoWake", v)?.apply()
        }

    var notes: String
        get() = read("notes", "[]")
        set(v) = write("notes", v)

    var lists: String
        get() = read("lists", "{}")
        set(v) = write("lists", v)

    var expenses: String
        get() = read("expenses", "[]")
        set(v) = write("expenses", v)

    /** Where the car / bike was parked: "lat,lon,time". */
    var park: String
        get() = read("park", "")
        set(v) = write("park", v)

    /** Driving mode: read new messages aloud as they arrive. */
    var readAloud: Boolean
        get() = sp?.getBoolean("readAloud", false) ?: false
        set(v) {
            sp?.edit()?.putBoolean("readAloud", v)?.apply()
        }

    private fun flag(k: String, d: Boolean) = sp?.getBoolean(k, d) ?: d
    private fun setFlag(k: String, v: Boolean) { sp?.edit()?.putBoolean(k, v)?.apply() }
    var pocketMode: Boolean
        get() = flag("pocketMode", false)
        set(v) = setFlag("pocketMode", v)
    var clapFind: Boolean
        get() = flag("clapFind", false)
        set(v) = setFlag("clapFind", v)
    var moodAware: Boolean
        get() = flag("moodAware", false)
        set(v) = setFlag("moodAware", v)
    var shakeTorch: Boolean
        get() = flag("shakeTorch", true)
        set(v) = setFlag("shakeTorch", v)
    var faceDownQuiet: Boolean
        get() = flag("faceDownQuiet", true)
        set(v) = setFlag("faceDownQuiet", v)
    /** Jobs you showed Zyro with "seekho": {"name": [steps]} */
    var taught: String
        get() = read("taught", "{}")
        set(v) = write("taught", v)
    var macros: String
        get() = read("macros", "[]")
        set(v) = write("macros", v)
    var findCode: String
        get() = read("findCode", "")
        set(v) = write("findCode", v)
    var personas: String
        get() = read("personas", "{}")
        set(v) = write("personas", v)
    var liveProxy: String
        get() = read("liveProxy", "")
        set(v) = write("liveProxy", v.trim())
    /** True when Gemini is reachable: own key, or the Zyro server (proxy) is set. */
    val hasGemini: Boolean get() = geminiKey.isNotBlank() || (liveProxy.isNotBlank() && liveToken.isNotBlank())

    /** REST url for a Gemini model; goes through the Zyro server when one is set (key stays hidden). */
    fun geminiUrl(model: String): String {
        val p = liveProxy.trim()
        if (p.isNotBlank() && liveToken.isNotBlank()) {
            val base = p.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").trimEnd('/')
            return "$base/rest/$model:generateContent"
        }
        return "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
    }


    /** Last REST route used / failure reason, for clear spoken errors. */
    @Volatile var lastRoute = ""
    val viaProxy: Boolean get() = liveProxy.isNotBlank() && liveToken.isNotBlank()

    private fun urlFor(model: String, proxy: Boolean): String {
        if (proxy) {
            val base = liveProxy.trim().replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").trimEnd('/')
            return "$base/rest/$model:generateContent"
        }
        return "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
    }

    /**
     * One Gemini REST call. Tries the Zyro server first (when set); if that fails for any reason
     * and an own key exists, silently retries direct. One quick retry on 5xx. Returns (code, body); code 0 = no network.
     */
    fun gpost(model: String, body: String, connMs: Int, readMs: Int): Pair<Int, String> {
        var last: Pair<Int, String> = Pair(0, "")
        val routes = ArrayList<Boolean>()
        if (viaProxy) routes.add(true)
        if (geminiKey.isNotBlank() || !viaProxy) routes.add(false)
        for (proxy in routes) {
            for (attempt in 0..1) {
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = java.net.URL(urlFor(model, proxy)).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = connMs
                    conn.readTimeout = readMs
                    conn.doOutput = true
                    conn.setRequestProperty("content-type", "application/json")
                    if (proxy) conn.setRequestProperty("x-app-token", liveToken) else conn.setRequestProperty("x-goog-api-key", geminiKey)
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    if (code in 200..299 && text.trimStart().startsWith("{")) {
                        lastRoute = if (proxy) "server" else "direct"
                        return Pair(code, text)
                    }
                    last = if (code in 200..299) Pair(502, text) else Pair(code, text)
                    lastRoute = if (proxy) "server" else "direct"
                    if (code >= 500 && attempt == 0) { Thread.sleep(350); continue }
                    break
                } catch (e: Exception) {
                    last = Pair(0, e.message ?: "")
                    lastRoute = if (proxy) "server" else "direct"
                    break
                } finally {
                    // no disconnect: keeps the TLS connection warm for the next call (faster)
                }
            }
        }
        return last
    }

    /** Plain Hindi reason for a failed AI call. */
    fun explainFail(code: Int, body: String): String {
        val srv = lastRoute == "server"
        return when {
            code == 0 -> "नेट या सर्वर तक पहुँच नहीं पा रहा। इंटरनेट चेक करो।"
            srv && (code == 401 || code == 403) -> "Zyro server का password गलत है। Settings में ZYRO SERVER का password चेक करो।"
            srv && code == 404 -> "Server में नया worker.js deploy नहीं है। Cloudflare में नया कोड डालकर Deploy करो, या Settings में server खाली छोड़कर अपनी key डालो।"
            srv && code == 429 -> "Server की आज की limit पूरी हो गई। कल या अपनी key डालकर चलाओ।"
            code == 401 || code == 403 || (code == 400 && (body.contains("API key") || body.contains("API_KEY"))) -> "Gemini की key सही नहीं है। Settings में चेक करो।"
            code == 429 -> "Gemini की free limit कुछ देर के लिए भर गई। एक मिनट बाद बोलो।"
            code >= 500 -> "Gemini अभी busy है। एक बार फिर बोलो।"
            else -> "जवाब नहीं आ पाया, error $code।"
        }
    }

    fun authGemini(c: java.net.HttpURLConnection) {
        if (liveProxy.isNotBlank() && liveToken.isNotBlank()) c.setRequestProperty("x-app-token", liveToken)
        else c.setRequestProperty("x-goog-api-key", geminiKey)
    }

    var watchList: String
        get() = read("watchList", "[]")
        set(v) = write("watchList", v)
    var feverLog: String
        get() = read("feverLog", "[]")
        set(v) = write("feverLog", v)
    /** Live conversation is the main way to talk; the old listen-and-reply loop stays off. */
    var liveOnly: Boolean
        get() = flag("liveOnly", true)
        set(v) = setFlag("liveOnly", v)
    var githubToken: String
        get() = read("githubToken", "")
        set(v) = write("githubToken", v.trim())
    var liveToken: String
        get() = read("liveToken", "")
        set(v) = write("liveToken", v.trim())
    var autoReply: Boolean
        get() = sp?.getBoolean("autoReply", false) ?: false
        set(v) {
            sp?.edit()?.putBoolean("autoReply", v)?.apply()
        }
    var autoReplyNote: String
        get() = read("autoReplyNote", "")
        set(v) = write("autoReplyNote", v)
    /** Name of the Gemini Live voice (Charon, Puck, Kore, Fenrir, Aoede, Orus, Leda, Zephyr...). */
    var liveVoice: String
        get() = read("liveVoice", "Charon").ifBlank { "Charon" }
        set(v) = write("liveVoice", v.trim())
    var liveModel: String
        get() = read("liveModel", "gemini-3.8-live").ifBlank { "gemini-3.8-live" }
        set(v) = write("liveModel", v.trim())
    var airDwell: Boolean
        get() = sp?.getBoolean("airDwell", true) ?: true
        set(v) {
            sp?.edit()?.putBoolean("airDwell", v)?.apply()
        }
    var emergency: String
        get() = read("emergency", "[]")
        set(v) = write("emergency", v)
    var water: String
        get() = read("water", "{}")
        set(v) = write("water", v)
    var stepBase: String
        get() = read("stepBase", "")
        set(v) = write("stepBase", v)
    var autoStart: Boolean
        get() = sp?.getBoolean("autoStart", false) ?: false
        set(v) {
            sp?.edit()?.putBoolean("autoStart", v)?.apply()
        }
    var events: String
        get() = read("events", "[]")
        set(v) = write("events", v)
    var every: String
        get() = read("every", "[]")
        set(v) = write("every", v)
    var budget: Int
        get() = sp?.getInt("budget", 0) ?: 0
        set(v) {
            sp?.edit()?.putInt("budget", v)?.apply()
        }
    var budgetWarn: String
        get() = read("budgetWarn", "")
        set(v) = write("budgetWarn", v)
    var weatherNag: String
        get() = read("weatherNag", "")
        set(v) = write("weatherNag", v)
    var streaks: String
        get() = read("streaks", "{}")
        set(v) = write("streaks", v)
    var ratings: String
        get() = read("ratings", "{}")
        set(v) = write("ratings", v)
    var streakNag: String
        get() = read("streakNag", "")
        set(v) = write("streakNag", v)
    var userFacts: String
        get() = read("userFacts", "[]")
        set(v) = write("userFacts", v)
    var diary: String
        get() = read("diary", "{}")
        set(v) = write("diary", v)
    var meetings: String
        get() = read("meetings", "[]")
        set(v) = write("meetings", v)
    var rules: String
        get() = read("rules", "[]")
        set(v) = write("rules", v)
    var homeSsid: String
        get() = read("homeSsid", "")
        set(v) = write("homeSsid", v)
    var fakeCaller: String
        get() = read("fakeCaller", "Mummy")
        set(v) = write("fakeCaller", v)

    /** IP address of the paired smart TV on the hotspot. */
    var tvIp: String
        get() = read("tvIp", "")
        set(v) = write("tvIp", v.trim())

    var facts: String
        get() = read("facts", "[]")
        set(v) = write("facts", v)

    var history: String
        get() = read("history", "[]")
        set(v) = write("history", v)

    const val DEFAULT_MODEL = "claude-sonnet-5-5"
    const val DEFAULT_GEMINI = "gemini-2.5-flash"
}
