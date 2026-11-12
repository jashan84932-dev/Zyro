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
