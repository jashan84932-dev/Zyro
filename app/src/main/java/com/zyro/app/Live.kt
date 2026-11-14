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
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
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
        if (Prefs.geminiKey.isBlank() && Prefs.liveProxy.isBlank()) return "लाइव के लिए Gemini key (या JARVIS server) चाहिए। सेटिंग में डालो।"
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "पहले माइक की अनुमति दो।"
        hands = h
        appCtx = ctx.applicationContext
        active = true
        ready = false
        endAfterTurn = false
        modelTry = 0
        plain = false
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
        Assistant.get(c).note("${Prefs.friendName}: (लाइव मोड बंद)")
        if (Prefs.liveOnly) ListenService.stop(c)
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
                if (!ready && !plain) {
                    plain = true
                    openSocket(my)
                } else if (!ready && modelTry + 1 < models().size) {
                    modelTry++
                    openSocket(my)
                } else if (!ready) {
                    fail("लाइव कनेक्ट नहीं हुआ ($code ${reason.take(120)})।")
                } else {
                    fail("लाइव कनेक्शन बंद हो गया।")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (my != gen || !active) return
                if (!ready && !plain) {
                    plain = true
                    openSocket(my)
                } else if (!ready && modelTry + 1 < models().size) {
                    modelTry++
                    openSocket(my)
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
        persona.append("phone_command also runs ALL of these when the user asks, so pass their words straight through: WhatsApp and SMS, calls, reading messages, notifications digest, auto-reply, focus mode, photos and camera questions ('ye kya hai'), screen questions, hologram mode, air control (hand-gesture cursor), clipboard (read, summarise, translate, paste, put on GitHub), GitHub repo creation and file upload, building an Android app from an idea, teaching a routine ('seekho') and replaying it, fever log, world news report, making and sharing pictures, personalities, smart TV, timers, alarms, music, safety and find-my-phone. ")
        persona.append("If a tool result asks the user a yes/no question, ask it aloud and then call phone_command again with their answer ('haan' or 'nahi'). Translation, summaries, writing and general chat you can do yourself. ")
        persona.append("and agent_task for multi-step jobs inside apps (ordering food, logging in, finding and playing a match or video, searching in an app). When the user gives such a job, call agent_task at once with the whole job in his words; never ask extra questions first and never say you cannot. A long command with several parts (words like 'karke', 'phir', 'uske baad', 'and then') is ONE agent_task with the full sentence; never split it into several tool calls. The agent handles popups and problems itself and asks the user only for OTP or password. While the agent works you must stay silent: never say things like 'wait kijiye', 'main kar raha hoon', 'koi dikkat aa rahi hai' or any filler; speak only when a [System notice] gives a result or a question for the user. For simple commands call the tool at once and say at most two words afterwards; never narrate what you are about to do. ")
        persona.append("Speak like a real person with real feelings: react naturally and let your tone follow the user's mood (glad for good news, gentle and slower when they are low, playful when they joke, serious for warnings). Short natural reactions are fine. Never sound like a robot and never say you are an AI model unless asked. ")
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
                mf.forEach { persona.append(it.take(160)).append("; ") }
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
            .put(decl("agent_task", "Start the phone agent for a multi-step job inside apps, e.g. 'Zomato par 2 biryani order karo'. The agent asks the user for confirmation before paying.", "task", "The full task"))
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
            .put("outputAudioTranscription", JSONObject()))
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
            sc.optJSONObject("inputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) { inText.append(it); lastActivity = System.currentTimeMillis() } }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) outText.append(it) }
            if (sc.optBoolean("turnComplete", false)) {
                val c = appCtx
                if (c != null) {
                    val u = inText.toString().trim()
                    val m = outText.toString().trim()
                    if (u.isNotEmpty()) Assistant.get(c).note("You: $u")
                    if (m.isNotEmpty()) Assistant.get(c).note("${Prefs.friendName}: $m")
                    if (u.length > 3 && m.isNotEmpty()) {
                        // Live learns too: keep the chat and pull lasting facts out of it, off the audio thread
                        Thread { try { Memory.addTurn(u, m); Memory.learn() } catch (e: Exception) {} }.start()
                    }
                }
                inText.setLength(0)
                outText.setLength(0)
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
        if (j.has("goAway")) {
            val c = appCtx
            stop()
            if (c != null) Assistant.get(c).announce("लाइव सेशन का समय पूरा हो गया। फिर से बोलो लाइव मोड।")
        }
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
                    if (h == null || cmd.isBlank()) "no command"
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
                    if (h == null || c == null || task.isBlank()) "no task"
                    else {
                        val r = Agent.start(c, h, task)
                        if (Agent.running) "STARTED. The agent is now working on the whole job in the background. Do NOT speak and do NOT call any tool for this job. Stay completely silent until a [System notice] tells you the result."
                        else r
                    }
                }
                "search_web" -> {
                    val q = args.optString("query")
                    Brain.quickText("Answer briefly (max 3 sentences) using live web data: $q", 300, search = true) ?: "search failed"
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
                while (active && my == gen) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (System.currentTimeMillis() - lastPlay > 400) appCtx?.let { Assistant.get(it).liveLevel(rms(buf, n) / 4500f, OrbView.LISTEN) }
                    // without echo cancellation, stay half-duplex so the model does not hear itself
                    if (!hasAec && (playQ.isNotEmpty() || System.currentTimeMillis() - lastPlay < 350)) continue
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
                    if (now - startedAt > 14 * 60_000L || now - lastActivity > 3 * 60_000L) {
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
