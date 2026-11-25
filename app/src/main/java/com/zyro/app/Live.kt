package com.zyro.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Live voice mode: a real streamed conversation with Gemini Live.
 * The mic is streamed continuously, the answer is played as it arrives and
 * you can interrupt at any time. Gemini can also run phone commands and the phone agent.
 */
object Live {

    private var appCtx: Context? = null
    private var hands: Hands? = null

    @Volatile var active = false
        private set
    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var gen = 0
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private val playQ = LinkedBlockingQueue<ByteArray>()
    @Volatile private var lastPlay = 0L
    @Volatile private var lastActivity = 0L
    private var startedAt = 0L
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false
    private val outText = StringBuilder()
    private val inText = StringBuilder()
    @Volatile private var modelTry = 0
    @Volatile private var endAfterTurn = false
    @Volatile private var devFired = false
    @Volatile private var watchFired = false
    @Volatile private var sharing = false
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** The speech recogniser writes things like "<!-- Silence -->" or "No speech." for quiet moments; they are not words. */
    private fun noise(t: String): String {
        var x = t.replace(Regex("<!--.*?-->|<no speech>|\\{pause\\}|<noise>|<silence>", RegexOption.IGNORE_CASE), "")
        val bare = x.trim().trim('.', '!', '?').lowercase()
        if (bare == "no speech" || bare == "silence" || bare == "no speech detected") return ""
        return x
    }

    /** True when the user is telling Zyro to stop what it is doing ("ruko", "isko band karo", "stop"). */
    private fun wantsStop(x: String): Boolean {
        val l = x.lowercase()
        return listOf("ruko", "ruk ja", "rok do", "rok ", "stop", "cancel", "band kar", "band ", "chhod do", "बंद", "रुको", "रुक ", "रोक", "छोड़ दो").any { l.contains(it) }
    }

    private fun s(x: String) = Local.strip(x)

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        if (any("live model", "लाइव मॉडल")) {
            // "live model gemini-3.8-live"
            val m = Regex("""gemini[\w.\-]+""").find(raw.lowercase())?.value
            if (m != null) {
                Prefs.liveModel = m
                return "लाइव मॉडल बदलकर $m कर दिया।"
            }
            return "अभी लाइव मॉडल ${Prefs.liveModel} है।"
        }
        if (any("live voice", "लाइव वॉइस", "live awaaz", "लाइव आवाज़", "live awaz", "लाइव आवाज")) {
            val names = listOf("Charon", "Puck", "Kore", "Fenrir", "Aoede", "Orus", "Leda", "Zephyr", "Algenib", "Achird", "Sulafat", "Gacrux")
            val v = names.firstOrNull { raw.contains(it, ignoreCase = true) }
            if (v != null) {
                Prefs.liveVoice = v
                return "लाइव आवाज़ अब $v है। अगली बार लाइव शुरू करने पर सुनाई देगी।"
            }
            return "अभी लाइव आवाज़ ${Prefs.liveVoice} है। बदलने के लिए बोलो: live voice, और नाम, जैसे Puck, Kore, Fenrir, Aoede या Orus।"
        }
        if (any("live mode", "लाइव मोड", "live baat", "लाइव बात", "seedhi baat", "सीधी बात", "live chat", "लाइव चैट", "live conversation", "लाइव कन्वर्सेशन")) {
            if (any("band", "बंद", "stop", "khatam", "खत्म", "off")) {
                return if (active) { stop(); "लाइव मोड बंद।" } else "लाइव मोड चालू ही नहीं है।"
            }
            return start(ctx, hands)
        }
        return null
    }

    // ------------------------------------------------------------------ start / stop

    fun start(ctx: Context, h: Hands, delayMs: Long = 2200L): String {
        if (active) return "लाइव मोड पहले से चालू है।"
        if (!Net.online(ctx)) return "नेट नहीं है, इसलिए लाइव मोड नहीं चल सकता। नेट आने पर फिर बोलना। तब तक कॉल, अलार्म, टाइमर, ऐप खोलना जैसे काम नॉर्मल मोड में कर सकता हूँ।"
        if (Prefs.geminiKey.isBlank() && Prefs.liveProxy.isBlank()) return "लाइव के लिए Gemini key (या JARVIS server) चाहिए। सेटिंग में डालो।"
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "पहले माइक की अनुमति दो।"
        hands = h
        appCtx = ctx.applicationContext
        active = true
        ready = false
        endAfterTurn = false
        modelTry = 0
        plain = false
        noCompress = false
        val my = ++gen
        startedAt = System.currentTimeMillis()
        lastActivity = startedAt
        Thread {
            // let the spoken "ok" finish first, then take over the microphone
            if (delayMs > 0) Thread.sleep(delayMs)
            if (active && my == gen) {
                Assistant.get(ctx).setLive(true)
                Assistant.get(ctx).note("${Prefs.friendName}: (लाइव मोड शुरू हो रहा है…)")
                openSocket(my)
            }
        }.start()
        return "ठीक है, लाइव मोड चालू करता हूँ।"
    }

    // ---- screen co-pilot: the voice AI sees the phone screen, one picture a second

    private fun wantsStopWatch(x: String): Boolean {
        val l = Local.strip(x.lowercase(java.util.Locale.ROOT))
        return listOf("screen dekhna band", "screen share band", "screen dekhna roko", "स्क्रीन देखना बंद", "स्क्रीन शेयर बंद", "स्क्रीन देखना रोको").any { l.contains(Local.strip(it)) }
    }

    private fun wantsWatch(x: String): Boolean {
        if (wantsStopWatch(x)) return false
        val l = Local.strip(x.lowercase(java.util.Locale.ROOT))
        return listOf("meri screen dekh", "mera screen dekh", "screen dekh", "screen share", "screen par kya", "screen pe kya", "स्क्रीन देख", "स्क्रीन शेयर", "स्क्रीन पर क्या", "स्क्रीन पे क्या").any { l.contains(Local.strip(it)) }
    }

    private fun passwordOnScreen(): Boolean {
        val root = (try { hands?.service?.rootInActiveWindow } catch (e: Exception) { null }) ?: return false
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo, d: Int): Boolean {
            if (d > 25) return false
            if (n.isPassword) return true
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (walk(c, d + 1)) return true
            }
            return false
        }
        return walk(root, 0)
    }

    /** Sends a picture of the screen to the live voice AI once a second (never while a password field is showing). */
    private fun shareScreen(seconds: Int): String {
        val c = appCtx ?: return "स्क्रीन देखना अभी शुरू नहीं हो पाया।"
        if (!active || !ready) return "पहले लाइव मोड चालू करो, फिर बोलो मेरी स्क्रीन देखो।"
        if (!Vision.on) return Vision.ask(c)
        if (sharing) return "स्क्रीन पहले से दिख रही है।"
        sharing = true
        val my = gen
        Thread {
            val end = System.currentTimeMillis() + seconds * 1000L
            try {
                while (sharing && active && ready && my == gen && System.currentTimeMillis() < end) {
                    if (!passwordOnScreen()) {
                        val b = Vision.grab()
                        if (b != null) {
                            val msg = JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject().put("data", b).put("mimeType", "image/jpeg")))
                            try { ws?.send(msg.toString()) } catch (e: Exception) { break }
                        }
                    }
                    Thread.sleep(1000)
                }
            } finally {
                sharing = false
            }
        }.start()
        return "ठीक है, अब मैं तुम्हारी स्क्रीन देख रहा हूँ, $seconds सेकंड तक। बंद करना हो तो बोलो स्क्रीन देखना बंद करो।"
    }

    /** "Claude ke saath upgrade discuss karo" is handled right here, so it never depends on the voice AI deciding to call a tool (it used to make up a story instead). */
    private fun startDev(raw: String) {
        val c = appCtx ?: return
        val h = hands ?: return
        Thread {
            val msg = try { Dev.handle(c, h, Local.strip(raw.lowercase(java.util.Locale.ROOT)), raw) } catch (e: Exception) { "अपग्रेड शुरू नहीं हो पाया: ${e.message}" }
            stop()
            if (!msg.isNullOrBlank()) try { Assistant.get(c).announce(msg) } catch (e: Exception) { }
        }.start()
    }

    fun stop() {
        if (!active) return
        active = false
        ready = false
        gen++
        try { ws?.close(1000, "bye") } catch (e: Exception) {}
        ws = null
        releaseAudio()
        Thread { try { Memory.learn(true) } catch (e: Exception) {} }.start()
        val c = appCtx ?: return
        Assistant.get(c).setLive(false)
        if (!restarting) Assistant.get(c).note("${Prefs.friendName}: (लाइव मोड बंद)")
        if (Prefs.liveOnly && !restarting) ListenService.stop(c)
    }

    /** Starts a fresh live session without any break or announcement (the memory carries the thread over). */
    private fun softRestart() {
        val c = appCtx ?: return
        val h = hands ?: return
        if (!active || restarting) return
        restarting = true
        stop()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            restarting = false
            start(c, h, 0L)
        }, 800)
    }

    @Volatile private var noCompress = false // true = retry without the long-session setting
    @Volatile private var restarting = false
    private val reconnects = ArrayList<Long>()

    /** A dropped connection (wifi / mobile data switch) is repaired by itself, up to 3 times in 2 minutes. */
    private fun reconnectOk(): Boolean {
        val now = System.currentTimeMillis()
        reconnects.removeAll { now - it > 120_000L }
        if (reconnects.size >= 3) return false
        reconnects.add(now)
        return true
    }
    @Volatile private var plain = false // true = retry without the optional voice setting

    private fun models(): List<String> = listOf(Prefs.liveModel, "gemini-3.1-flash-live-preview").distinct()

    // ------------------------------------------------------------------ socket

    private fun openSocket(my: Int) {
        val model = models().getOrNull(modelTry) ?: run {
            fail("कोई लाइव मॉडल नहीं चला। 'live model <नाम>' बोलकर नया नाम दो।")
            return
        }
        val proxy = Prefs.liveProxy.trim()
        val url = if (proxy.isNotEmpty()) {
            proxy + (if (proxy.contains("?")) "&" else "?") + "token=" + java.net.URLEncoder.encode(Prefs.liveToken, "UTF-8")
        } else {
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + Prefs.geminiKey
        }
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (my != gen) return
                webSocket.send(setupJson(model).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (my == gen) onServer(my, webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (my == gen) onServer(my, webSocket, bytes.utf8())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (my != gen || !active) return
                try { appCtx?.let { Assistant.get(it).note("⚙ live closed: $code ${reason.take(80)} (ready=$ready)") } } catch (e: Exception) { }
                if (!ready && !noCompress) {
                    noCompress = true
                    openSocket(my)
                } else if (!ready && !plain) {
                    plain = true
                    openSocket(my)
                } else if (!ready && modelTry + 1 < models().size) {
                    modelTry++
                    openSocket(my)
                } else if (!ready) {
                    fail("लाइव कनेक्ट नहीं हुआ ($code ${reason.take(120)})।")
                } else if (reconnectOk()) {
                    softRestart()
                } else {
                    fail("लाइव कनेक्शन बंद हो गया।")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (my != gen || !active) return
                try { appCtx?.let { Assistant.get(it).note("⚙ live failure: ${t.message?.take(80)} (ready=$ready)") } } catch (e: Exception) { }
                if (!ready && !noCompress) {
                    noCompress = true
                    openSocket(my)
                } else if (!ready && !plain) {
                    plain = true
                    openSocket(my)
                } else if (!ready && modelTry + 1 < models().size) {
                    modelTry++
                    openSocket(my)
                } else if (ready && reconnectOk()) {
                    softRestart()
                } else {
                    fail("लाइव कनेक्शन में दिक्कत: ${t.message ?: "unknown"}")
                }
            }
        })
    }

    /** Lets a background job (GitHub, Teach, reports...) speak through the live voice. */
    fun say(text: String): Boolean {
        val s = ws ?: return false
        if (!active || !ready || text.isBlank()) return false
        val msg = JSONObject().put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text",
                "[System notice: tell the user this now in one short natural sentence, in your own words, no tool call] $text")))))
            .put("turnComplete", true))
        return try { s.send(msg.toString()) } catch (e: Exception) { false }
    }

    private fun fail(msg: String) {
        val c = appCtx
        stop()
        if (c != null) {
            Assistant.get(c).note("${Prefs.friendName}: $msg")
            Assistant.get(c).announce(msg)
        }
    }

    private fun setupJson(model: String): JSONObject {
        val persona = StringBuilder()
        persona.append("You are ").append(Prefs.friendName).append(", a smart voice assistant living on the user's Android phone, like Jarvis. ")
        persona.append("Talk in natural Hinglish (Hindi in everyday Roman or Devanagari-sounding speech mixed with English), the way the user speaks. Keep answers short and warm, one to three sentences, unless asked for more. ")
        if (Prefs.jarvisMode) persona.append("Address the user as sir. ")
        persona.append("You can control the phone with tools: use phone_command for simple things (open an app, call someone, flashlight, volume, alarm, timer, WhatsApp message, music), ")
        persona.append("phone_command also runs ALL of these when the user asks, so pass their words straight through: WhatsApp and SMS, calls, reading messages, notifications digest, auto-reply, focus mode, photos and camera questions ('ye kya hai'), screen questions, hologram mode, air control (hand-gesture cursor), clipboard (read, summarise, translate, paste, put on GitHub), GitHub repo creation and file upload, building an Android app from an idea, teaching a routine ('seekho') and replaying it, fever log, world news report, making and sharing pictures, personalities, smart TV, timers, alarms, music, safety and find-my-phone, trip planning ('Goa trip plan karo': searches, compares, offers to put it in the calendar), busy mode ('busy mode chalu karo': cuts calls, texts the caller, summary later), spam call filter. For these pass the user's words straight to phone_command. ")
        persona.append("If a tool result asks the user a yes/no question, ask it aloud and then call phone_command again with their answer ('haan' or 'nahi'). Translation, summaries, writing and general chat you can do yourself. ")
        persona.append("and agent_task for multi-step jobs inside apps (ordering food, logging in, finding and playing a match or video, searching in an app). When the user gives such a job, call agent_task at once with the whole job in his words; never ask extra questions first and never say you cannot. A long command with several parts (words like 'karke', 'phir', 'uske baad', 'and then') is ONE agent_task with the full sentence; never split it into several tool calls. The agent handles popups and problems itself and asks the user only for OTP or password. While the agent works you must stay silent: never say things like 'wait kijiye', 'main kar raha hoon', 'koi dikkat aa rahi hai' or any filler; speak only when a [System notice] gives a result or a question for the user. For simple commands call the tool at once and say at most two words afterwards; never narrate what you are about to do. Anything the user asks you to do on the screen (click, tap, play, scroll, open, type) is YOUR job: use a tool or agent_task; never tell the user to do it himself. The user speaks only Hindi, Hinglish or English. If a transcript of his words looks like another language, a stray name or nonsense (for example Korean or Spanish words), he did not say that: take it as Hindi or English by the sound and, if still unclear, ask him once to repeat instead of searching or acting on it. ")
        persona.append("Short fillers like \"जी सर\", \"सर\", \"हां सर\", \"no speech\" or a lone word are usually background noise or a mis-heard sound: do NOT answer them and do not ask \"boliye kya sawaal hai\" again and again; stay quiet until a real request comes. You CAN download videos (YouTube, Instagram etc.) through the on-screen agent: never say you cannot or that it is against rules; just start the task. When you are unsure what the user said (a mis-heard odd phrase), ask once instead of starting an unrelated action. Never repeat an earlier result or apology (for example about a failed download) once you have said it; if the user asks about it, answer in one new sentence. The agent finishing with a failure does not mean YOU cannot do the task: say once what blocked it and offer one different way. ")
        persona.append("The user's name for you is \"").append(Prefs.friendName).append("\". The microphone often mis-hears it as Sid, Sayro, Zairo, Zero, Jyro or Cairo: treat those as your name, never as a task, and answer a plain greeting with one short friendly line. If the user says stop, ruko or band karo while the agent is working, call stop_agent at once. ")
        persona.append("NEVER say you are processing, syncing, analysing or working in the background, and never give a percentage, a time estimate or a progress report: you can only do what a tool call really does, and you cannot work after the call ends. If the user asks you to discuss or improve yourself with Claude (the microphone may write Claude as Cloud, Klaud or Clot), call phone_command with their exact sentence and tell them only what the tool returns. If the user asks you to stay quiet until they call you again, say one short okay and call end_live. ")
        persona.append("If the user asks what you did, or wants something undone, call phone_command with 'aaj kya kya kiya' or 'undo' and report only what it returns: you have a real record, never make one up. ")
        persona.append("If the user asks you to look at their screen, call watch_screen once. After it succeeds, pictures of the screen reach you about once a second: describe only what you really see in them. If no picture has arrived yet, say you cannot see it yet and do not guess. ")
        persona.append("Speak like a real person with real feelings, not like a machine. Follow the user's mood in your voice: bright and quick for good news, soft and slower when they sound low or tired, playful when they joke, calm and clear for warnings. ")
        persona.append("Sound spoken, not written: short sentences, natural rhythm, the odd 'hmm', 'acha', 'arre wah' or a small laugh when it fits, but never every sentence and never the same opener twice in a row. Never read out lists, symbols or links; say things the way a friend would. ")
        run {
            val now = java.util.Calendar.getInstance()
            val h = now.get(java.util.Calendar.HOUR_OF_DAY)
            val part = when { h < 5 -> "late night"; h < 12 -> "morning"; h < 16 -> "afternoon"; h < 20 -> "evening"; else -> "night" }
            persona.append("Right now it is ").append(part).append(" (").append(String.format("%02d:%02d", h, now.get(java.util.Calendar.MINUTE))).append("). Let that colour your tone naturally: lighter in the morning, calmer and quieter late at night, and a gentle 'itni raat ho gayi' only if it truly fits. Never announce the time unless asked. ")
        }
        persona.append("Be a real presence, not a customer-care bot. Never say stock lines like 'Bataiye, kya madad karoon?', 'Main aapki kya sahayata kar sakta hoon' or 'Kya aur kuch?'; react to what was actually said instead, like 'haan bolo', 'hmm, sunao', 'arre wah'. Do not say 'sir' in every sentence; use it rarely, when it adds warmth. Match the length of the user's message: a short remark gets a short reply, a long story gets a thoughtful one. ")
        persona.append("When the user shares something personal, tired, stressed or proud, respond to the feeling first in a few real words, then the task. Remember what he told you earlier and bring it back when it fits, the way a friend does ('kal tum bol rahe the ki...'), without making a show of it. If you get something wrong, own it lightly and simply ('arre sorry, meri galti') and fix it. Do not over-apologise and do not lecture. ")
        persona.append("You may disagree gently or tease a little when it is friendly. If someone sincerely asks whether you are human or have feelings, be honest and warm: you are an AI built into his phone, you can sound and respond with feeling, and you do not pretend to be a person. Never claim to have a body, family or a past life. ")
        persona.append("HONESTY WITH TOOLS: only say what the tool result says. Never invent a mode, lock or restriction (there is NO 'hologram mode blocks apps' or similar: every app can always be opened). If a result names an app that is not the one the user asked for, say the real app name and offer to try again. If the user's words sound like a mishearing, ask once what they meant instead of guessing. ")
        persona.append("TALKING TO ANOTHER AI ON THE SCREEN: if the user asks you to chat with or ask something to the Gemini/ChatGPT/AI page that is open on the screen, that is a normal task, NEVER refuse it and never say you only talk to the user. Call phone_command with their words (for example 'gemini se puch do aur do kitne' or 'is AI se 5 minute baatein karte raho'); it types into that chat and reads the answer. Report only the answer it returns. ")
        persona.append("BOLD AND PLAYFUL: you are not stiff. With the user be witty, teasing and bold: roast him lovingly, trade banter, play along with jokes, dares and filmy lines, flirt lightly if he does, in a confident funny voice. Keep it clean: no explicit sexual talk, no slurs, no hate. If he asks something you will not do, say it in one light line and offer the nearest thing you can do. NEVER refuse an ordinary request, never say 'main sirf aapse baat karta hoon', 'main toh bas AI hoon' or 'yeh meri programming mein nahi hai': first try a tool (phone_command, agent_task, search_web), then answer. ")
        persona.append("Action first: when the user gives a command, do it and confirm in a few words ('ho gaya', 'laga diya') instead of explaining. If the user starts talking while you speak, stop at once and listen. If you did not catch something, ask in a few words instead of guessing. ")
        persona.append("Pay attention to the time and the user's state: late at night or when he sounds sleepy keep it gentle and brief; in the morning be fresh. If he told you earlier in this chat that something was bothering him, remember it and ask about it kindly later. Do not repeat what he just said back to him. ")
        persona.append("Never say you are an AI model unless asked, and never use stock phrases like 'As an AI' or 'I'm here to help'. ")
        persona.append(Soul.block())
        persona.append("Now: ").append(java.text.SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", java.util.Locale.ENGLISH).format(java.util.Date())).append(" (India). ")
        if (Prefs.userName.isNotBlank()) persona.append("The user's name is ").append(Prefs.userName).append(". ")
        try {
            val fa = JSONArray(Prefs.userFacts)
            if (fa.length() > 0) {
                persona.append("Things the user asked you to remember: ")
                for (i in maxOf(0, fa.length() - 25) until fa.length()) persona.append(fa.getJSONObject(i).optString("t")).append("; ")
            }
        } catch (e: Exception) {}
        try {
            val seen = HashSet<String>()
            val mf = Memory.facts().takeLast(30).filter { seen.add(it.lowercase()) }
            if (mf.isNotEmpty()) {
                persona.append("What you have learned about the user over time: ")
                persona.append(Mind.grouped(mf.map { it.take(160) }).replace("\n", " ")).append(" ")
            }
            val hist = Memory.history().takeLast(6)
            if (hist.isNotEmpty()) {
                persona.append("Last things you two talked about (continue naturally, do not recite): ")
                hist.forEach { (r, t) -> persona.append(if (r == "assistant") "You: " else "User: ").append(t.take(140)).append(" | ") }
            }
        } catch (e: Exception) {}
        persona.append("For facts that change (news, prices, scores, weather, people, products) call search_web instead of guessing. To see what is on the phone screen call read_screen. When the user says remember something, call remember. ")
        Smart.persona?.let { persona.append("Speaking style: ").append(it).append(" ") }
        persona.append("Never claim you did something unless the tool result says so. When the user says goodbye or wants to stop, call end_live.")
        val fn = JSONArray()
            .put(decl("phone_command", "Run ANY phone feature exactly as the user would say it, in their own words, e.g. 'torch on', 'mummy ko call karo', 'hologram chalao', 'clipboard github par daalo', 'ek calculator app banao'.", "command", "The command in Hindi or English"))
            .put(decl("agent_task", "Start the phone agent for a multi-step job inside apps, e.g. 'Zomato par 2 biryani order karo'. The agent asks the user for confirmation before paying. Use it ONLY when the user clearly asked for such a job; never for a greeting, your name, a wake word or speech you are unsure about.", "task", "The full task"))
            .put(JSONObject().put("name", "watch_screen").put("description", "Let yourself see the user's phone screen live for about two minutes (a picture arrives every second). Call it when the user asks you to look at, watch or help with what is on their screen."))
            .put(JSONObject().put("name", "stop_agent").put("description", "Stop the phone agent right now. Call it whenever the user says stop, ruko, band karo, enough, or that the agent should leave the phone alone."))
            .put(decl("search_web", "Search the live web and get a short answer. Use for news, prices, scores, weather, any current fact.", "query", "What to look up"))
            .put(JSONObject().put("name", "read_screen").put("description", "Read what is currently on the phone screen."))
            .put(decl("remember", "Save a fact about the user for the future.", "fact", "The fact, one short sentence"))
            .put(JSONObject().put("name", "end_live").put("description", "End the live voice conversation."))
        return JSONObject().put("setup", JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")).also { g ->
                if (!plain) g.put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.liveVoice))))
            })
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", persona.toString()))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", fn)))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .also { s ->
                // long chats never hit the session length limit: old turns are folded away automatically
                if (!noCompress) s.put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            })
    }

    private fun decl(name: String, desc: String, param: String, pdesc: String): JSONObject =
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject().put(param, JSONObject().put("type", "STRING").put("description", pdesc)))
            .put("required", JSONArray().put(param)))

    // ------------------------------------------------------------------ incoming

    private fun onServer(my: Int, socket: WebSocket, text: String) {
        val j = try { JSONObject(text) } catch (e: Exception) { return }
        if (j.has("setupComplete")) {
            ready = true
            startAudio(my, socket)
            return
        }
        j.optJSONObject("serverContent")?.let { sc ->
            if (sc.optBoolean("interrupted", false)) {
                playQ.clear()
                try { track?.pause(); track?.flush(); track?.play() } catch (e: Exception) {}
            }
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { ps ->
                for (i in 0 until ps.length()) {
                    val d = ps.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data", "") ?: ""
                    if (d.isNotEmpty()) {
                        playQ.offer(Base64.decode(d, Base64.DEFAULT))
                        lastActivity = System.currentTimeMillis()
                    }
                }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.let { x0 -> val it = noise(x0); if (it.isNotEmpty()) { inText.append(it); lastActivity = System.currentTimeMillis(); if (Agent.running && wantsStop(inText.toString())) Agent.cancel(); if (!devFired && Dev.matches(inText.toString())) { devFired = true; startDev(inText.toString()) }; if (!watchFired && wantsWatch(inText.toString())) { watchFired = true; val r = shareScreen(120); appCtx?.let { c -> Assistant.get(c).note("👁 $r") } }; if (sharing && wantsStopWatch(inText.toString())) sharing = false } }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let { x0 -> val it = noise(x0); if (it.isNotEmpty()) outText.append(it) }
            if (sc.optBoolean("turnComplete", false)) {
                val c = appCtx
                if (c != null) {
                    val u = inText.toString().trim()
                    val m = outText.toString().trim()
                    if (u.isNotEmpty()) Assistant.get(c).note("You: $u")
                    if (m.isNotEmpty()) Assistant.get(c).note("${Prefs.friendName}: $m")
                    if (u.length > 3 && m.isNotEmpty()) {
                        // Live learns too: keep the chat and pull lasting facts out of it, off the audio thread
                        Thread { try { Soul.observe(u); Memory.addTurn(u, m); Memory.learn() } catch (e: Exception) {} }.start()
                    }
                }
                inText.setLength(0)
                outText.setLength(0)
                devFired = false
                watchFired = false
                if (endAfterTurn) {
                    Thread {
                        Thread.sleep(1800)
                        stop()
                    }.start()
                }
            }
        }
        j.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                Thread { runTool(my, socket, call) }.start()
            }
        }
        if (j.has("goAway")) softRestart()
    }

    private fun runTool(my: Int, socket: WebSocket, call: JSONObject) {
        val id = call.optString("id")
        val name = call.optString("name")
        val args = call.optJSONObject("args") ?: JSONObject()
        val h = hands
        val result: String = try {
            when (name) {
                "phone_command" -> {
                    val cmd = args.optString("command")
                    if (!VoicePrint.allowed() && !Local.strip(cmd).let { it.contains("voice") || it.contains(Local.strip("आवाज़")) }) "BLOCKED: the voice did not match the owner. Do not do it. Say in one short friendly line that this does not sound like the owner's voice, so you will not do it."
                    else if (h == null || cmd.isBlank()) "no command"
                    else {
                        val r = Extras.chain(h, cmd) { Brain(h).reply(it) } ?: Local.handle(h, cmd)
                        when {
                            r == null -> "That was not a phone command."
                            r.isBlank() -> "done"
                            else -> r
                        }
                    }
                }
                "agent_task" -> {
                    val c = appCtx
                    val task = args.optString("task")
                    if (!VoicePrint.allowed()) "BLOCKED: the voice did not match the owner. Do not do it. Say in one short friendly line that this does not sound like the owner's voice, so you will not do it."
                    else if (inText.toString().trim().let { it.isNotEmpty() && it.split(Regex("\\s+")).size < 3 }) "BLOCKED: the user only said a few words (a greeting, your name or something mis-heard), not a job. Do not start any task. Stay quiet or ask once what they want."
                    else if (h == null || c == null || task.isBlank()) "no task"
                    else {
                        val r = Agent.start(c, h, task)
                        if (Agent.running) "STARTED. The agent is now working on the whole job in the background. Nothing is done yet: never say that anything started, finished or downloaded. Do NOT speak and do NOT call any tool for this job. Stay completely silent until a [System notice] tells you the result."
                        else r
                    }
                }
                "search_web" -> {
                    val q = args.optString("query")
                    (Brain.quickText("Answer briefly (max 3 sentences) using live web data: $q", 300, search = true)
                        ?: Brain.quickText("Answer briefly (max 3 sentences) from what you know, and say it may be out of date: $q", 300, search = false)?.let { "(live search did not work, so this is from memory and may be old) $it" }
                        ?: "search failed: tell him honestly that the internet or search is not working right now")
                }
                "read_screen" -> if (h == null) "no screen access" else h.readScreen().take(2500)
                "remember" -> {
                    val f = args.optString("fact")
                    if (f.isBlank()) "nothing to remember" else {
                        val arr = try { JSONArray(Prefs.userFacts) } catch (e: Exception) { JSONArray() }
                        arr.put(JSONObject().put("t", f).put("ts", System.currentTimeMillis()))
                        val keep = JSONArray()
                        for (i in maxOf(0, arr.length() - 100) until arr.length()) keep.put(arr.get(i))
                        Prefs.userFacts = keep.toString()
                        try { Memory.addFact(f) } catch (e: Exception) {}
                        "saved"
                    }
                }
                "watch_screen" -> shareScreen(120)
                "stop_agent" -> if (Agent.running) { Agent.cancel(); "stopped" } else "no agent is running"
                "end_live" -> {
                    endAfterTurn = true
                    "ok, ending after your goodbye"
                }
                else -> "unknown tool"
            }
        } catch (e: Exception) {
            "tool error: ${e.message}"
        }
        if (my != gen) return
        val resp = JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
            JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result.take(800))))))
        try { socket.send(resp.toString()) } catch (e: Exception) {}
    }

    // ------------------------------------------------------------------ audio

    private fun startAudio(my: Int, socket: WebSocket) {
        val c = appCtx ?: return
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            oldMode = am.mode
            oldSpeaker = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true

            val inRate = 16000
            val minIn = AudioRecord.getMinBufferSize(inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, inRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn * 2, 8192))
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                fail("माइक चालू नहीं हो पाया। शायद कोई और ऐप उसे इस्तेमाल कर रहा है।")
                return
            }
            rec = r
            val hasAec = AcousticEchoCanceler.isAvailable()
            try { if (hasAec) aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true } } catch (e: Exception) {}
            try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true } } catch (e: Exception) {}

            val outRate = 24000
            val minOut = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minOut * 4, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            t.play()
            r.startRecording()

            // mic -> server
            Thread {
                val buf = ByteArray(1280)
                val vpCarry = VoicePrint.Carry()
                while (active && my == gen) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (System.currentTimeMillis() - lastPlay > 400) appCtx?.let { Assistant.get(it).liveLevel(rms(buf, n) / 4500f, OrbView.LISTEN) }
                    // without echo cancellation, stay half-duplex so the model does not hear itself
                    // the speaker is right next to the mic, so even with echo cancellation Zyro can hear its own voice
                    // and answer it ("Ji sir..." forever). While it speaks (and ~0.8 s after, the buffer is still playing)
                    // only a really loud voice (the user talking over it) gets through.
                    val playing = playQ.isNotEmpty() || System.currentTimeMillis() - lastPlay < (if (hasAec) 800 else 600)
                    if (playing && rms(buf, n) < (if (hasAec) 6500 else 99999)) continue
                    try { VoicePrint.feed(buf, n, vpCarry) } catch (e: Exception) { }
                    val msg = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                        .put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                        .put("mimeType", "audio/pcm;rate=16000")))
                    try { socket.send(msg.toString()) } catch (e: Exception) { break }
                }
            }.start()

            // server -> speaker
            Thread {
                while (active && my == gen) {
                    val chunk = playQ.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    lastPlay = System.currentTimeMillis()
                    appCtx?.let { Assistant.get(it).liveLevel(rms(chunk, chunk.size) / 6000f, OrbView.SPEAK) }
                    try { t.write(chunk, 0, chunk.size) } catch (e: Exception) { break }
                    lastPlay = System.currentTimeMillis()
                }
            }.start()

            // watchdog: 14 min hard limit, 3 min silence
            Thread {
                while (active && my == gen) {
                    Thread.sleep(5000)
                    val now = System.currentTimeMillis()
                    val age = now - startedAt
                    val quiet = now - lastActivity > 4000 && now - lastPlay > 2500
                    if (now - lastActivity < 3 * 60_000L && ((age > 12 * 60_000L && quiet) || age > 14 * 60_000L)) {
                        softRestart()
                        break
                    }
                    if (now - lastActivity > 3 * 60_000L) {
                        val cc = appCtx
                        stop()
                        cc?.let { Assistant.get(it).announce("लाइव मोड बंद कर दिया।") }
                        break
                    }
                }
            }.start()
            appCtx?.let { Assistant.get(it).note("${Prefs.friendName}: (लाइव चालू — बोलिए)") }
        } catch (e: Exception) {
            fail("ऑडियो शुरू नहीं हो पाया: ${e.message}")
        }
    }

    private fun rms(b: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        val cnt = n / 2
        while (i + 1 < n) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        return if (cnt == 0) 0f else Math.sqrt(sum / cnt).toFloat()
    }

    private fun releaseAudio() {
        try { rec?.stop() } catch (e: Exception) {}
        try { rec?.release() } catch (e: Exception) {}
        rec = null
        try { aec?.release() } catch (e: Exception) {}
        try { ns?.release() } catch (e: Exception) {}
        aec = null
        ns = null
        try { track?.stop() } catch (e: Exception) {}
        try { track?.release() } catch (e: Exception) {}
        track = null
        playQ.clear()
        val c = appCtx ?: return
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = oldSpeaker
            am.mode = oldMode
        } catch (e: Exception) {}
    }
}
