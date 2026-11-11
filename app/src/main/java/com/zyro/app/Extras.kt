package com.zyro.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.KeyEvent
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Extra instant commands: battery, storage, music keys, calculator, unit conversion,
 * reminders, morning briefing, routines, WhatsApp drafts, reading notifications / screen,
 * and "what is this?" (camera).
 * Called from Local.handle on a background thread.
 */
object Extras {

    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun strip(s: String) = Local.strip(s)

    // ------------------------------------------------------------------ pending WhatsApp draft

    private var pendingWhatsApp = 0L // time the draft was opened, 0 = none

    /** Called first for every sentence: answers "haan bhejo" / "nahi" after a WhatsApp draft. */
    fun handlePending(hands: Hands, t: String, words: List<String>): String? {
        Agent.handlePending(t, words)?.let { return it }
        Safety.handlePending(hands, t, words)?.let { return it }
        Pro.handlePending(hands, t, words)?.let { return it }
        Comms.handlePending(hands, t, words)?.let { return it }
        if (pendingWhatsApp == 0L) return null
        if (System.currentTimeMillis() - pendingWhatsApp > 90_000) {
            pendingWhatsApp = 0L
            return null
        }
        fun word(vararg w: String) = w.any { x -> words.contains(strip(x)) }
        return when {
            word("नहीं", "nahi", "nahin", "cancel", "रहने", "mat", "no", "कैंसिल") -> {
                pendingWhatsApp = 0L
                "ठीक है, मैसेज नहीं भेजा।"
            }
            word("हाँ", "हां", "haan", "han", "yes", "bhejo", "भेजो", "send", "भेज", "ok", "okay", "ओके", "ठीक") -> {
                pendingWhatsApp = 0L
                var r = hands.click("Send")
                if (!r.startsWith("Clicked")) r = hands.click("भेजें")
                if (!r.startsWith("Clicked")) r = hands.click("भेजो")
                if (r.startsWith("Clicked")) "मैसेज भेज दिया।"
                else "Send बटन नहीं मिला। WhatsApp में खुद भेज दो, मैसेज तैयार है।"
            }
            else -> {
                pendingWhatsApp = 0L
                null
            }
        }
    }

    // ------------------------------------------------------------------ main entry

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        fun word(vararg w: String) = w.any { x -> words.contains(strip(x)) }

        // ----- calls, SMS, OTP, navigation, habits -----
        Agent.handle(hands, t, words, raw)?.let { return it }
        Live.handle(hands, t, words, raw)?.let { return it }
        Genius.handle(hands, t, words, raw)?.let { return it }
        Stark.handle(hands, t, words, raw)?.let { return it }
        Life.handle(hands, t, words, raw)?.let { return it }
        Safety.handle(hands, t, words, raw)?.let { return it }
        Plus.handle(hands, t, words, raw)?.let { return it }
        Smart.handle(hands, t, words, raw)?.let { return it }
        Pro.handle(hands, t, words, raw)?.let { return it }
        Comms.handle(hands, t, words, raw)?.let { return it }

        // ----- devices on the hotspot -----
        Lan.handle(hands, t, words)?.let { return it }

        // ----- open phone, weather, expenses, lists, notes -----
        Daily.handle(hands, t, words, raw)?.let { return it }

        // ----- system report, guard mode, driving mode, parking -----
        Jarvis.handle(hands, t, words)?.let { return it }

        // ----- your own routines (made by voice) -----
        customRoutine(hands, t, words)?.let { return it }

        // ----- phone sound mode -----
        if (any("mode", "मोड") && any("silent", "साइलेंट", "vibrate", "वाइब्रेट", "normal", "नॉर्मल", "ring", "रिंग")) {
            return ringerMode(ctx, t)
        }

        // ----- coin / dice / random -----
        if (any("coin", "सिक्का", "toss", "टॉस", "सिक्के")) {
            return if (Math.random() < 0.5) "सिक्का उछाला: चित आया।" else "सिक्का उछाला: पट आया।"
        }
        if (any("dice", "पासा", "डाइस", "पासे")) {
            return "पासे पर ${(1..6).random()} आया।"
        }
        if (any("random number", "रैंडम नंबर", "रेंडम नंबर")) {
            return "मेरा चुना हुआ नंबर ${(1..100).random()} है।"
        }

        // ----- WhatsApp message -----
        if (any("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "व्हाट्सअप", "whatsap", "वाट्सऐप")) {
            whatsapp(hands, raw)?.let { return it }
        }

        // ----- notifications -----
        if (any("notification", "नोटिफिकेशन", "नोटिफ़िकेशन", "सूचना") &&
            any("पढ़", "पढ", "padh", "read", "सुना", "suna", "बता", "bata", "कौन", "kaun", "kya", "क्या", "आई", "aayi", "aai", "आया")
        ) {
            return readNotifications(ctx)
        }
        if (any("koi message", "कोई मैसेज", "कोई मेसेज", "message aaya", "मैसेज आया", "मैसेज आए", "कोई नोटिफिकेशन")) {
            return readNotifications(ctx)
        }

        // ----- read the screen aloud (a summary goes to the AI) -----
        if (any("screen", "स्क्रीन") && !any("screenshot", "स्क्रीनशॉट", "स्क्रीन शॉट") &&
            any("पढ़", "पढ", "पड़", "padh", "pad ", "pado", "read", "सुनाओ", "sunao", "kya hai", "क्या है", "क्या लिखा", "kya likha", "kya dikh", "क्या दिख", "बताओ", "batao", "दिखा") &&
            !any("summary", "सारांश", "समरी", "summarize", "समझाओ", "samjhao", "khulasa", "संक्षेप")
        ) {
            return readScreenAloud(hands)
        }

        // ----- camera: what is this? -----
        if ((any("camera", "कैमरा", "कैमरे") && any("dekh", "देख", "batao", "बताओ", "kya hai", "क्या है", "pehchan", "पहचान", "scan", "स्कैन")) ||
            any("ye kya hai", "yeh kya hai", "ये क्या है", "यह क्या है", "isko dekho", "इसे देखो", "इसको देखो",
                "camera se", "कैमरे से", "कैमरा से", "dekh ke batao", "देखकर बताओ", "देख के बताओ", "what is this", "what's this", "photo kheencho", "फोटो खींचो")
        ) {
            return startCamera(ctx, hands)
        }

        // ----- battery -----
        if (any("battery", "बैटरी", "bettery", "charge", "चार्ज") &&
            any("kitni", "कितनी", "kitna", "कितना", "percent", "प्रतिशत", "बता", "bata", "status", "कितने", "how much", "level", "क्या")
        ) {
            return batteryReply(ctx)
        }

        // ----- storage -----
        if (any("storage", "स्टोरेज", "खाली जगह", "free space") &&
            any("kitni", "कितनी", "kitna", "कितना", "बची", "bachi", "बाकी", "free", "बता", "bata", "कितने")
        ) {
            return storageReply()
        }

        // ----- music keys -----
        mediaCommand(ctx, t, words)?.let { return it }

        // ----- reminders -----
        if (any("yaad", "याद", "remind", "रिमाइंड", "reminder") &&
            any("dilana", "दिलाना", "dilao", "दिला", "dila", "remind", "रिमाइंड", "reminder")
        ) {
            return reminder(hands, t, words, raw)
        }

        // ----- morning briefing -----
        if (any("good morning", "गुड मॉर्निंग", "गुड मोर्निंग", "सुप्रभात", "briefing", "ब्रीफिंग", "आज का दिन कैसा", "aaj ka din")) {
            return briefing(ctx, hands, true)
        }

        // ----- routines -----
        routine(ctx, hands, t, words)?.let { return it }

        // ----- calculator and unit conversion -----
        if (!any("call", "कॉल", "फोन", "alarm", "अलार्म")) {
            convertUnits(t, words)?.let { return it }
            calculate(t)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ battery / storage

    fun batteryReply(ctx: Context): String {
        val i = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "बैटरी की जानकारी नहीं मिली।"
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else return "बैटरी की जानकारी नहीं मिली।"
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "बैटरी $pct प्रतिशत है" + (if (charging) " और चार्ज हो रही है।" else ".")
    }

    private fun storageReply(): String {
        return try {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = st.totalBytes / 1_000_000_000.0
            val free = st.availableBytes / 1_000_000_000.0
            String.format(Locale.ENGLISH, "स्टोरेज में %.1f जीबी खाली है, कुल %.0f जीबी में से।", free, total)
        } catch (e: Exception) {
            "स्टोरेज की जानकारी नहीं मिली।"
        }
    }

    // ------------------------------------------------------------------ music

    private fun sendKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun mediaCommand(ctx: Context, t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        fun word(vararg w: String) = w.any { x -> words.contains(strip(x)) }
        val music = any("song", "gaana", "gana", "गाना", "गाने", "music", "म्यूजिक", "म्यूज़िक", "track", "ट्रैक", "gaane")
        if (music && any("next", "अगला", "agla", "अगले", "skip", "बदल", "badlo")) {
            sendKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
            return "अगला गाना।"
        }
        if (music && any("previous", "पिछला", "pichla", "पिछले", "pehla wala", "वापस", "back")) {
            sendKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            return "पिछला गाना।"
        }
        if ((music && any("pause", "रोक", "rok", "बंद", "band", "stop")) || (words.size <= 2 && word("pause", "पॉज़", "पॉज"))) {
            sendKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
            return "गाना रोक दिया।"
        }
        if ((music && any("resume", "फिर से चला", "dobara", "दोबारा", "chalu", "चालू", "play")) || (words.size <= 2 && word("resume", "रिज्यूम"))) {
            sendKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
            return "गाना चालू कर दिया।"
        }
        return null
    }

    // ------------------------------------------------------------------ reminders

    private fun reminder(hands: Hands, t: String, words: List<String>, raw: String): String {
        val label = raw.trim().take(60)
        // "10 minute baad" / "2 ghante baad"
        for (i in 1 until words.size) {
            val unit = words[i]
            val mult = when {
                unit.startsWith(strip("minute")) || unit.startsWith(strip("मिनट")) || unit == "min" -> 60
                unit.startsWith(strip("ghante")) || unit.startsWith(strip("घंटे")) || unit.startsWith(strip("घंटा")) ||
                    unit.startsWith(strip("hour")) || unit.startsWith(strip("ghanta")) -> 3600
                unit.startsWith(strip("second")) || unit.startsWith(strip("सेकंड")) || unit.startsWith(strip("सेकण्ड")) -> 1
                else -> 0
            }
            if (mult > 0) {
                val n = Local.numberOf(words[i - 1]) ?: continue
                val r = hands.setTimer(n * mult, label)
                return if (r.startsWith("Timer")) "ठीक है, $n ${if (mult == 3600) "घंटे" else if (mult == 60) "मिनट" else "सेकंड"} बाद याद दिला दूँगा।"
                else "रिमाइंडर नहीं लग पाया, clock app नहीं मिला।"
            }
        }
        val al = Local.parseAlarm(t, words)
        if (al != null) {
            val r = hands.setAlarm(al.first, al.second, label)
            return if (r.startsWith("Alarm")) "ठीक है, उस समय अलार्म की तरह याद दिला दूँगा।"
            else "रिमाइंडर नहीं लग पाया, clock app नहीं मिला।"
        }
        return "कब याद दिलाऊँ? ऐसे बोलो: शाम 5 बजे दवाई की याद दिलाना, या 10 मिनट बाद चाय की याद दिलाना।"
    }

    // ------------------------------------------------------------------ briefing and routines

    private fun weather(): String? {
        return try {
            val c = URL("https://wttr.in/?format=%25C,+%25t&lang=hi").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 6000
            c.setRequestProperty("User-Agent", "curl/8")
            val s = c.inputStream.bufferedReader().use { it.readText() }.trim()
            c.disconnect()
            if (s.isBlank() || s.length > 80 || s.contains("Unknown")) null else s
        } catch (e: Exception) {
            null
        }
    }

    private fun briefing(ctx: Context, hands: Hands, greet: Boolean): String {
        val sb = StringBuilder()
        if (greet) sb.append("गुड मॉर्निंग! ")
        val f = SimpleDateFormat("d MMMM, EEEE", Locale.forLanguageTag("hi-IN"))
        sb.append("आज ").append(f.format(Date())).append(" है। ")
        sb.append(Local.timeReply()).append(" ")
        weather()?.let { sb.append("मौसम: ").append(it).append("। ") }
        sb.append(Jarvis.dayExtras(ctx))
        sb.append(batteryReply(ctx))
        return sb.toString()
    }

    private fun routine(ctx: Context, hands: Hands, t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        if (any("sone ka time", "सोने का टाइम", "सोने का समय", "sleep mode", "bedtime", "night routine", "रात का रूटीन", "सोने जा रहा", "sone ja raha", "सोने जा रही")) {
            hands.brightness(5)
            hands.torch(false)
            sendKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
            return "ठीक है, ब्राइटनेस बहुत कम कर दी और गाना रोक दिया। शुभ रात्रि!"
        }
        if (any("movie mode", "मूवी मोड", "फिल्म मोड", "film mode", "cinema mode")) {
            hands.brightness(15)
            hands.volume("up")
            return "मूवी मोड चालू: ब्राइटनेस कम और आवाज़ बढ़ा दी। मज़े करो!"
        }
        if (any("work mode", "वर्क मोड", "काम का मोड", "padhai mode", "पढ़ाई मोड", "study mode")) {
            hands.brightness(60)
            hands.torch(false)
            return "वर्क मोड चालू: ब्राइटनेस 60 प्रतिशत कर दी।"
        }
        if (any("routine", "रूटीन") && any("कौन", "kaun", "list", "कितने", "बताओ", "batao")) {
            return "मेरे रूटीन ये हैं: सोने का टाइम, मूवी मोड, वर्क मोड और गुड मॉर्निंग। इनमें से कोई बोलो।"
        }
        return null
    }

    // ------------------------------------------------------------------ calculator / units

    private val OPS = listOf(
        "plus" to "+", "जमा" to "+", "जोड़" to "+", "add" to "+",
        "minus" to "-", "घटा" to "-", "ghata" to "-", "माइनस" to "-", "subtract" to "-",
        "गुणा" to "*", "guna" to "*", "multiplied by" to "*", "multiply" to "*", "times" to "*", "into" to "*", "×" to "*",
        "भाग" to "/", "bhag" to "/", "divided by" to "/", "divide" to "/", "÷" to "/", "by" to "/"
    )

    private fun fmt(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "?"
        val r = Math.round(d * 10000.0) / 10000.0
        return if (r == Math.floor(r) && Math.abs(r) < 1e15) r.toLong().toString() else r.toString()
    }

    private fun calculate(t: String): String? {
        // percentages: "18% of 250", "250 ka 18%"
        val pct = "(?:%|percent|परसेंट|प्रतिशत|फीसदी|फीसद|persent)"
        val a = Regex("(\\d+(?:\\.\\d+)?)\\s*$pct\\s*(?:of|ka|का|की|के)?\\s*(\\d+(?:\\.\\d+)?)").find(t)
        if (a != null) {
            val p = a.groupValues[1].toDouble()
            val n = a.groupValues[2].toDouble()
            return "${fmt(n)} का ${fmt(p)} प्रतिशत ${fmt(n * p / 100)} होता है।"
        }
        val b = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:of|ka|का|की|के)\\s*(\\d+(?:\\.\\d+)?)\\s*$pct").find(t)
        if (b != null) {
            val n = b.groupValues[1].toDouble()
            val p = b.groupValues[2].toDouble()
            return "${fmt(n)} का ${fmt(p)} प्रतिशत ${fmt(n * p / 100)} होता है।"
        }
        // plain arithmetic
        var s = t
        for ((w, sym) in OPS) s = s.replace(strip(w), " $sym ")
        s = s.replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), " * ")
        val m = Regex("\\d+(?:\\.\\d+)?(?:\\s*[+\\-*/]\\s*\\d+(?:\\.\\d+)?)+").find(s) ?: return null
        val expr = m.value.replace(" ", "")
        return try {
            val v = Parser(expr).parse()
            "जवाब ${fmt(v)} है।"
        } catch (e: Exception) {
            null
        }
    }

    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw IllegalStateException("bad")
            return v
        }
        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        private fun term(): Double {
            var v = num()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = num()
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        private fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalStateException("num")
            return s.substring(st, i).toDouble()
        }
    }

    private class U(val dim: String, val factor: Double, val label: String)

    private val UNITS: Map<String, U> = HashMap<String, U>().apply {
        fun add(u: U, vararg names: String) { for (n in names) put(strip(n), u) }
        add(U("len", 1000.0, "किलोमीटर"), "km", "kilometer", "kilometre", "kilometers", "किलोमीटर", "किमी")
        add(U("len", 1609.344, "मील"), "mile", "miles", "मील")
        add(U("len", 1.0, "मीटर"), "meter", "metre", "meters", "मीटर")
        add(U("len", 0.3048, "फीट"), "feet", "foot", "फीट", "फुट")
        add(U("len", 0.01, "सेंटीमीटर"), "cm", "centimeter", "centimetre", "सेंटीमीटर")
        add(U("len", 0.0254, "इंच"), "inch", "inches", "इंच")
        add(U("mass", 1000.0, "किलोग्राम"), "kg", "kilo", "kilogram", "किलो", "किलोग्राम")
        add(U("mass", 453.592, "पाउंड"), "pound", "pounds", "lb", "lbs", "पाउंड")
        add(U("mass", 1.0, "ग्राम"), "gram", "grams", "ग्राम")
        add(U("vol", 1000.0, "लीटर"), "litre", "liter", "litres", "liters", "लीटर")
        add(U("vol", 1.0, "मिलीलीटर"), "ml", "milliliter", "मिलीलीटर")
        add(U("temp", 1.0, "सेल्सियस"), "celsius", "सेल्सियस", "centigrade")
        add(U("temp", 2.0, "फ़ारेनहाइट"), "fahrenheit", "फारेनहाइट", "फॉरेनहाइट", "फ़ारेनहाइट")
    }

    private fun convertUnits(t: String, words: List<String>): String? {
        val num = Regex("(\\d+(?:\\.\\d+)?)").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val found = ArrayList<U>()
        for (w in words) {
            val u = UNITS[w] ?: UNITS[w.replace(Regex("[0-9.]"), "")] ?: continue
            if (found.isEmpty() || found.last() !== u) found.add(u)
        }
        if (found.size < 2) return null
        val from = found[0]
        val to = found[1]
        if (from.dim != to.dim) return null
        val result = if (from.dim == "temp") {
            if (from.factor == to.factor) num else if (from.factor == 1.0) num * 9 / 5 + 32 else (num - 32) * 5 / 9
        } else {
            num * from.factor / to.factor
        }
        return "${fmt(num)} ${from.label} बराबर ${fmt(result)} ${to.label} होता है।"
    }

    // ------------------------------------------------------------------ WhatsApp

    private val WA_LEAD = setOf(
        "whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "व्हाट्सअप", "whatsap", "वाट्सऐप",
        "pe", "पे", "पर", "par", "message", "मैसेज", "मेसेज", "संदेश", "bhejo", "भेजो", "भेज", "karo", "करो", "कर",
        "कि", "ki", "ke", "bolo", "बोलो", "बोल", "likho", "लिखो", "लिख", "ye", "ये", "that", "send", "में", "mein", "me",
        "से", "se", "a", "ek", "एक", "और", "aur", "दो", "do", "दे", "de", "hey", "micro", "माइक्रो"
    ).map { strip(it) }.toSet()

    private fun whatsapp(hands: Hands, raw: String): String? {
        val tokens = strip(raw).replace(Regex("[।,?!;:\"'()]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        val koAt = tokens.indexOfFirst { it == "ko" || it == "को" }
        if (koAt < 0) return null
        val name = tokens.subList(0, koAt).filter { it !in WA_LEAD }.joinToString(" ").trim()
        var rest = tokens.subList(koAt + 1, tokens.size)
        var skip = 0
        while (skip < rest.size && rest[skip] in WA_LEAD) skip++
        rest = rest.subList(skip, rest.size)
        val body = rest.joinToString(" ").trim()
        if (name.isBlank()) return null
        if (body.isBlank()) {
            return "क्या मैसेज भेजना है? ऐसे बोलो: मम्मी को WhatsApp पर मैसेज भेजो कि मैं देर से आऊँगा।"
        }
        if (!hands.hasContactsPermission()) return "Contacts की permission दे दो, फिर मैं नाम से WhatsApp खोल दूँगा।"
        val c = hands.findContact(name) ?: return "$name नाम का contact मुझे नहीं मिला।"
        var digits = c.second.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits"
        if (digits.length < 8) return "${c.first} का नंबर सही नहीं लग रहा।"
        val r = hands.openUri("whatsapp://send?phone=$digits&text=" + Uri.encode(body))
        if (!r.startsWith("Opened") && r.contains("Couldn't")) return "WhatsApp नहीं खुल पाया।"
        pendingWhatsApp = System.currentTimeMillis()
        return "${c.first} के लिए मैसेज तैयार है: $body। भेज दूँ? हाँ बोलो या नहीं।"
    }

    // ------------------------------------------------------------------ notifications / screen

    private fun listenerEnabled(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(ctx.packageName)
    }

    private fun readNotifications(ctx: Context): String {
        if (!listenerEnabled(ctx)) {
            try {
                ctx.startActivity(
                    Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // ignore
            }
            return "Notifications पढ़ने के लिए सेटिंग्स में Zyro को ON कर दो, फिर दोबारा पूछना।"
        }
        val items = ArrayList<String>()
        synchronized(NotifService.recent) {
            items.addAll(NotifService.recent.takeLast(5))
            NotifService.recent.clear()
        }
        if (items.isEmpty()) return "कोई नई notification नहीं है।"
        return "${items.size} नई notification: " + items.joinToString("। ").take(900)
    }

    private fun readScreenAloud(hands: Hands): String {
        val raw = hands.readScreen()
        if (raw.contains("Accessibility")) {
            return "पहले Accessibility में Zyro को ON कर दो, फिर मैं स्क्रीन पढ़ पाऊँगा।"
        }
        val labels = ArrayList<String>()
        for (line in raw.lines()) {
            val a = line.indexOf('"')
            val b = line.lastIndexOf('"')
            if (a >= 0 && b > a) {
                val s = line.substring(a + 1, b).replace(" | ", ", ").trim()
                if (s.isNotBlank() && s != "(password field)" && s !in labels) labels.add(s)
            }
        }
        if (labels.isEmpty()) return "स्क्रीन पर मुझे पढ़ने लायक कुछ नहीं मिला।"
        return "स्क्रीन पर लिखा है: " + labels.joinToString("। ").take(700)
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera(ctx: Context, hands: Hands): String {
        if (Prefs.geminiKey.isBlank()) return "फोटो देखने के लिए Gemini key चाहिए। Zyro की settings में डाल दो।"
        return try {
            hands.openActivity(Intent(ctx, CameraActivity::class.java))
            "कैमरा खुल रहा है। फोटो खींचकर OK दबा दो, फिर मैं बताऊँगा।"
        } catch (e: Exception) {
            "कैमरा नहीं खुल पाया।"
        }
    }

    // ------------------------------------------------------------------ sound mode

    private fun ringerMode(ctx: Context, t: String): String {
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            when {
                any("vibrate", "वाइब्रेट") -> {
                    am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    "फ़ोन वाइब्रेट मोड पर है।"
                }
                any("silent", "साइलेंट") -> {
                    am.ringerMode = AudioManager.RINGER_MODE_SILENT
                    "फ़ोन साइलेंट कर दिया।"
                }
                else -> {
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    "फ़ोन नॉर्मल मोड पर है।"
                }
            }
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (x: Exception) {
                // ignore
            }
            "इसके लिए फ़ोन की अनुमति चाहिए। मैंने साउंड सेटिंग्स खोल दी हैं।"
        }
    }

    // ------------------------------------------------------------------ your own routines

    private val SEPARATORS = Regex("\\s*(?:,|\\s+(?:और|aur|phir|फिर|then|and|तथा|फिर से)\\s+)\\s*", RegexOption.IGNORE_CASE)

    private fun loadRoutines(): JSONObject = try { JSONObject(Prefs.routines) } catch (e: Exception) { JSONObject() }

    private fun customRoutine(hands: Hands, t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        val hasRoutine = any("routine", "रूटीन", "रुटीन")
        if (!hasRoutine) return null
        val saved = loadRoutines()

        // make: "routine banao padhai brightness 60 aur torch band"
        if (any("banao", "बनाओ", "bana", "बना", "save", "सेव", "create", "जोड़ो", "add")) {
            val after = Regex("(?:routine|रूटीन|रुटीन)\\s+(?:banao|बनाओ|bana|बना|save|सेव|create|add)\\s+(.+)").find(t)
                ?: Regex("(?:banao|बनाओ|bana|बना|save|सेव|create|add)\\s+(?:routine|रूटीन|रुटीन)\\s+(.+)").find(t)
                ?: return "ऐसे बोलो: रूटीन बनाओ पढ़ाई ब्राइटनेस 60 और टॉर्च बंद। पहला शब्द रूटीन का नाम होगा।"
            val rest = after.groupValues[1].trim()
            val name = rest.substringBefore(' ').trim()
            val cmds = rest.substringAfter(' ', "").trim()
                .replace(Regex("^(?:jisme|जिसमें|jismein|me|में|ki|की|mein)\\s+"), "")
                .split(SEPARATORS).map { it.trim() }.filter { it.isNotBlank() }
            if (name.isBlank() || cmds.isEmpty()) {
                return "रूटीन में क्या-क्या करना है? जैसे: रूटीन बनाओ पढ़ाई ब्राइटनेस 60 और टॉर्च बंद।"
            }
            val arr = JSONArray()
            cmds.forEach { arr.put(it) }
            saved.put(name, arr)
            Prefs.routines = saved.toString()
            return "रूटीन $name सेव हो गया, इसमें ${cmds.size} काम हैं। चलाने के लिए बोलो: $name रूटीन चलाओ।"
        }

        // delete
        if (any("hatao", "हटाओ", "delete", "डिलीट", "मिटाओ", "mitao", "remove")) {
            val key = saved.keys().asSequence().firstOrNull { words.contains(strip(it)) || t.contains(strip(it)) }
                ?: return "कौन-सा रूटीन हटाऊँ? नाम के साथ बोलो।"
            saved.remove(key)
            Prefs.routines = saved.toString()
            return "रूटीन $key हटा दिया।"
        }

        // list
        if (any("kaun", "कौन", "list", "लिस्ट", "kitne", "कितने", "batao", "बताओ", "naam", "नाम")) {
            val mine = saved.keys().asSequence().toList()
            val base = "सोने का टाइम, मूवी मोड, वर्क मोड और गुड मॉर्निंग"
            return if (mine.isEmpty()) "मेरे रूटीन ये हैं: $base। अपना बनाने के लिए बोलो: रूटीन बनाओ।"
            else "आपके रूटीन: ${mine.joinToString(", ")}। साथ में ये भी हैं: $base।"
        }

        // run
        val key = saved.keys().asSequence().firstOrNull { words.contains(strip(it)) || t.contains(strip(it)) } ?: return null
        val arr = saved.optJSONArray(key) ?: return null
        var failed = 0
        for (i in 0 until arr.length()) {
            val r = try { Local.handle(hands, arr.optString(i)) } catch (e: Exception) { null }
            if (r == null) failed++
            try { Thread.sleep(500) } catch (e: InterruptedException) { /* ignore */ }
        }
        return if (failed == 0) "रूटीन $key चला दिया।" else "रूटीन $key चला दिया, पर $failed काम मैं सीधे नहीं कर पाया।"
    }

    // ------------------------------------------------------------------ several commands in one sentence

    private val NO_CHAIN = listOf(
        "whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "message", "मैसेज", "search", "सर्च", "खोज", "ढूंढ", "yaad", "याद",
        "call", "कॉल", " ko ", " को ", "routine", "रूटीन", "रुटीन", "plus", "minus"
    )

    /**
     * "volume badhao aur torch chalu karo" -> runs each part. Parts that I cannot do locally go to the AI.
     * Returns null if the sentence is not a simple list of short commands.
     */
    fun chain(hands: Hands, raw: String, ai: (String) -> String): String? {
        val low = " " + raw.lowercase(Locale.getDefault()) + " "
        if (NO_CHAIN.any { low.contains(it) }) return null
        val parts = raw.split(SEPARATORS).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 2 || parts.size > 4) return null
        if (parts.any { it.split(Regex("\\s+")).size > 7 }) return null
        val replies = ArrayList<String>()
        for ((idx, p) in parts.withIndex()) {
            val local = try { Local.handle(hands, p) } catch (e: Exception) { null }
            if (idx == 0 && local == null) return null // not a command list: handle the whole sentence normally
            val r = local ?: try { ai(p) } catch (e: Exception) { "कुछ गड़बड़ हो गई।" }
            replies.add(r)
            try { Thread.sleep(400) } catch (e: InterruptedException) { /* ignore */ }
        }
        return replies.joinToString(" ")
    }
}
