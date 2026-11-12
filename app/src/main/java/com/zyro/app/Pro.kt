package com.zyro.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Live translator, meeting recorder, voice diary, smart replies, expense report,
 * automation rules and document reading. Everything is stored only on the phone.
 */
object Pro {
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    // ------------------------------------------------------------------ shared helpers

    private fun s(x: String) = Local.strip(x)

    private val YES = setOf("haan", "han", "ha", "हाँ", "हां", "हा", "yes", "ok", "okay", "ओके", "ठीक", "thik", "laga", "लगा", "bhejo", "भेजो", "bhej", "भेज", "kar", "कर").map { s(it) }.toSet()
    private val NO = setOf("nahi", "नहीं", "nahin", "cancel", "no", "mat", "रहने", "rehne", "कैंसिल", "रहनेदो").map { s(it) }.toSet()

    private fun today(): String {
        val c = Calendar.getInstance()
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    // ------------------------------------------------------------------ 1. live translator

    private val LANGS = listOf(
        Triple(listOf("english", "अंग्रेजी", "अंग्रेज़ी", "angrezi", "इंग्लिश", "अंग्रेज़ी"), "English", "en-US"),
        Triple(listOf("punjabi", "पंजाबी"), "Punjabi", "pa-IN"),
        Triple(listOf("japanese", "जापानी", "जैपनीज़", "जैपनीज"), "Japanese", "ja-JP"),
        Triple(listOf("french", "फ्रेंच", "फ़्रेंच"), "French", "fr-FR"),
        Triple(listOf("german", "जर्मन"), "German", "de-DE"),
        Triple(listOf("spanish", "स्पैनिश", "स्पेनिश"), "Spanish", "es-ES"),
        Triple(listOf("arabic", "अरबी"), "Arabic", "ar"),
        Triple(listOf("korean", "कोरियन", "कोरियाई"), "Korean", "ko-KR"),
        Triple(listOf("chinese", "चीनी", "चाइनीज़", "चाइनीज"), "Chinese", "zh-CN"),
        Triple(listOf("tamil", "तमिल"), "Tamil", "ta-IN"),
        Triple(listOf("telugu", "तेलुगु", "तेलगु"), "Telugu", "te-IN"),
        Triple(listOf("bengali", "bangla", "बंगाली", "बांग्ला"), "Bengali", "bn-IN"),
        Triple(listOf("marathi", "मराठी"), "Marathi", "mr-IN"),
        Triple(listOf("gujarati", "गुजराती"), "Gujarati", "gu-IN"),
        Triple(listOf("urdu", "उर्दू"), "Urdu", "ur-PK")
    )

    @Volatile var translateLang: String? = null

    private fun langOf(t: String) = LANGS.firstOrNull { l -> l.first.any { t.contains(s(it)) } }

    /** Returns (language tag to speak in, translated text), or null when it failed. */
    fun translate(text: String): Pair<String, String>? {
        val lang = translateLang ?: return null
        val tag = LANGS.firstOrNull { it.second == lang }?.third ?: "en-US"
        val prompt = "You are a live interpreter between Hindi and $lang. " +
            "If the text below is Hindi or Hinglish, translate it into $lang. Otherwise translate it into Hindi (Devanagari). " +
            "The text comes from speech recognition, so fix obvious recognition mistakes silently. " +
            "Reply in exactly this format and nothing else: LANGUAGETAG|translation, where LANGUAGETAG is the BCP-47 tag of your output language " +
            "(use $tag for $lang and hi-IN for Hindi).\n\nText: $text"
        val out = Brain.quickText(prompt, 500)?.trim() ?: return null
        val m = Regex("^([A-Za-z]{2,3}(?:-[A-Za-z]{2,4})?)\\|(.+)$", RegexOption.DOT_MATCHES_ALL).find(out)
        return if (m != null) Pair(m.groupValues[1], m.groupValues[2].trim()) else Pair("hi-IN", out)
    }

    // ------------------------------------------------------------------ 2 + 3. meeting recorder and voice diary

    @Volatile var capKind: String? = null
    private val cap = ArrayList<String>()

    fun capturing() = capKind != null
    fun record(text: String) {
        synchronized(cap) { cap.add(text) }
    }

    /** True when a short sentence asks to stop translating / recording. */
    fun stopRequested(text: String): Boolean {
        val t = s(text)
        if (t.length > 45) return false
        val stop = listOf("band", "बंद", "बन्द", "stop", "khatam", "खत्म", "समाप्त", "off", "ख़त्म").any { t.contains(s(it)) }
        val mode = listOf("meeting", "मीटिंग", "diary", "डायरी", "lecture", "लेक्चर", "class", "क्लास", "recording", "रिकॉर्डिंग", "notes", "नोट्स",
            "translat", "ट्रांसलेट", "अनुवाद", "translator", "ट्रांसलेटर").any { t.contains(s(it)) }
        return (stop && mode) || t == "stop"
    }

    private const val MEETING_PROMPT = "यह एक मीटिंग या क्लास की बातचीत का टेक्स्ट है (speech recognition से, इसलिए गलतियाँ हो सकती हैं)। " +
        "इसका हिंदी में छोटा सारांश बनाओ: पहले मुख्य बातें (3 से 5 बिंदु), फिर लिए गए फैसले, फिर किसे क्या करना है। " +
        "सादा टेक्स्ट लिखो, कोई markdown या सितारे नहीं।\n\n"

    fun finishCapture(): String {
        val kind = capKind ?: return "कुछ रिकॉर्ड नहीं हो रहा था।"
        capKind = null
        val lines: List<String>
        synchronized(cap) {
            lines = ArrayList(cap)
            cap.clear()
        }
        if (lines.isEmpty()) return "कुछ सुनाई नहीं दिया, इसलिए कुछ सेव नहीं किया।"
        if (kind == "diary") {
            lines.forEach { diaryAdd(it) }
            return "डायरी बंद। " + diarySummary()
        }
        val text = lines.joinToString("\n")
        val sum = Brain.quickText(MEETING_PROMPT + text.takeLast(12000), 1500)?.trim()
        val saved = sum ?: ("(Gemini key नहीं थी, सिर्फ़ टेक्स्ट सेव हुआ)\n" + text.take(1500))
        saveMeeting(saved)
        if (sum == null) return "मीटिंग का टेक्स्ट सेव कर लिया। सारांश के लिए Gemini key चाहिए।"
        return "मीटिंग का सारांश: " + shorten(sum, 520) + " पूरा सारांश सुनने के लिए बोलो, मीटिंग सारांश पढ़ो।"
    }

    private fun shorten(x: String, max: Int): String {
        val flat = x.replace(Regex("[\\n\\r*#]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (flat.length <= max) return flat
        val cut = flat.take(max)
        val dot = cut.lastIndexOfAny(charArrayOf('।', '.', '!'))
        return if (dot > 150) cut.take(dot + 1) else cut
    }

    private fun saveMeeting(summary: String) {
        val a = try { JSONArray(Prefs.meetings) } catch (e: Exception) { JSONArray() }
        a.put(JSONObject().put("ts", System.currentTimeMillis()).put("s", summary))
        val keep = JSONArray()
        for (i in maxOf(0, a.length() - 20) until a.length()) keep.put(a.get(i))
        Prefs.meetings = keep.toString()
    }

    internal fun lastMeeting(): String? {
        val a = try { JSONArray(Prefs.meetings) } catch (e: Exception) { return null }
        if (a.length() == 0) return null
        return a.getJSONObject(a.length() - 1).optString("s")
    }

    private fun diaryAdd(entry: String) {
        val d = try { JSONObject(Prefs.diary) } catch (e: Exception) { JSONObject() }
        val arr = d.optJSONArray(today()) ?: JSONArray()
        arr.put(entry)
        d.put(today(), arr)
        val keys = ArrayList<String>()
        val it = d.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.sort()
        while (keys.size > 14) d.remove(keys.removeAt(0))
        Prefs.diary = d.toString()
    }

    private fun diarySummary(): String {
        val d = try { JSONObject(Prefs.diary) } catch (e: Exception) { JSONObject() }
        val arr = d.optJSONArray(today()) ?: return "आज की डायरी खाली है।"
        if (arr.length() == 0) return "आज की डायरी खाली है।"
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append("- ").append(arr.optString(i)).append('\n')
        val ai = Brain.quickText(
            "आज दिन भर मैंने ये बातें बोलीं या लिखवाईं (speech recognition से):\n$sb\n" +
                "इनसे मेरी आज की डायरी का सारांश हिंदी में 4 से 5 छोटे वाक्यों में लिखो: क्या हुआ, कौन सा वादा या काम बाकी है, कल क्या करना है। सादा टेक्स्ट।", 1200
        )?.trim()
        return if (ai != null) shorten(ai, 600) else "आज की डायरी में ${arr.length()} बातें हैं: " + shorten(sb.toString(), 400)
    }

    // ------------------------------------------------------------------ 5. smart reply

    private var pendChoices: List<String>? = null
    private var pendMsg: NotifService.LastMsg? = null
    private var pendAt = 0L
    private var pendSend: Pair<NotifService.LastMsg, String>? = null
    private var pendSendAt = 0L

    private fun smartReply(): String {
        val m = NotifService.last
        if (m == null || System.currentTimeMillis() - m.at > 2 * 3_600_000L) {
            return "जवाब देने के लिए कोई ताज़ा मैसेज नहीं मिला। Notification access चालू होना चाहिए।"
        }
        if (m.action == null) return "${m.app} के इस मैसेज का सीधा जवाब नहीं दे सकता।"
        val prompt = "मेरे दोस्त ${m.title} ने ${m.app} पर लिखा: \"${m.text}\"\n" +
            "मेरी तरफ़ से 3 अलग-अलग छोटे जवाब लिखो, उसी अंदाज़ में जैसे मैं दोस्तों से हिंदी/Hinglish में बात करता हूँ: " +
            "पहला दोस्ताना, दूसरा बहुत छोटा, तीसरा हल्का मज़ाकिया। हर जवाब नई लाइन में, बिना नंबर और बिना quotes के, कोई और बात नहीं।"
        val out = Brain.quickText(prompt, 400) ?: return "जवाब सुझाने के लिए Gemini key और इंटरनेट चाहिए।"
        val lines = out.lines().map { it.trim().trim('"').replace(Regex("^[0-9]+[.)]\\s*"), "").trim() }.filter { it.isNotBlank() }.take(3)
        if (lines.isEmpty()) return "कोई जवाब नहीं सूझा, फिर से बोलो।"
        pendChoices = lines
        pendMsg = m
        pendAt = System.currentTimeMillis()
        val names = listOf("पहला", "दूसरा", "तीसरा")
        val spoken = lines.mapIndexed { i, l -> "${names[i]}: $l" }.joinToString("। ")
        return "${m.title.ifBlank { m.app }} ने लिखा: ${m.text.take(120)}। मेरे सुझाव। $spoken। कौन सा भेजूँ? पहला, दूसरा या तीसरा बोलो।"
    }

    private fun customReply(raw: String): String {
        val m = NotifService.last
        if (m == null || m.action == null || System.currentTimeMillis() - m.at > 2 * 3_600_000L) {
            return "जवाब देने के लिए कोई ताज़ा मैसेज नहीं मिला।"
        }
        val mm = Regex("(?:^|\\s)(?:ki|कि|that)\\s+(.+)").find(raw) ?: return "ऐसे बोलो: व्हाट्सएप पर जवाब दो कि मैं बाद में बात करता हूँ।"
        val body = mm.groupValues[1].trim()
        pendSend = Pair(m, body)
        pendSendAt = System.currentTimeMillis()
        return "${m.title.ifBlank { m.app }} को जवाब: $body। भेज दूँ?"
    }

    private fun sendReply(m: NotifService.LastMsg, body: String): String {
        val ctx = appCtx ?: return "अभी नहीं भेज पाया।"
        return try {
            val a = m.action ?: return "यह मैसेज अब जवाब के लिए खुला नहीं है।"
            val ri = a.remoteInputs ?: return "यह मैसेज अब जवाब के लिए खुला नहीं है।"
            val i = Intent()
            val b = Bundle()
            for (r in ri) b.putCharSequence(r.resultKey, body)
            android.app.RemoteInput.addResultsToIntent(ri, i, b)
            a.actionIntent.send(ctx, 0, i)
            "${m.title.ifBlank { m.app }} को जवाब भेज दिया।"
        } catch (e: Exception) {
            "जवाब नहीं जा पाया। शायद वह नोटिफ़िकेशन हट चुका है।"
        }
    }

    fun handlePending(hands: Hands, t: String, words: List<String>): String? {
        val now = System.currentTimeMillis()
        if (words.size > 4) return null
        fun word(vararg w: String) = w.any { x -> words.contains(s(x)) }
        val yes = words.any { it in YES }
        val no = words.any { it in NO }

        val d = pendDoc
        if (d != null) {
            if (now - d.third > 120_000) {
                pendDoc = null
            } else if (yes || no) {
                pendDoc = null
                return if (yes && !no) addDateReminder(d.first, d.second) else "ठीक है, रिमाइंडर नहीं लगाया।"
            }
        }
        val ps = pendSend
        if (ps != null) {
            if (now - pendSendAt > 90_000) {
                pendSend = null
            } else if (yes || no) {
                pendSend = null
                return if (yes && !no) sendReply(ps.first, ps.second) else "ठीक है, जवाब नहीं भेजा।"
            }
        }
        val ch = pendChoices
        if (ch != null) {
            if (now - pendAt > 90_000) {
                pendChoices = null
            } else {
                val idx = when {
                    word("pehla", "पहला", "पहले", "1", "first", "पहली") -> 0
                    word("doosra", "dusra", "दूसरा", "दूसरे", "2", "second") || (words.size <= 2 && word("do", "दो")) -> 1
                    word("teesra", "tisra", "तीसरा", "तीसरे", "3", "third", "teen", "तीन") -> 2
                    else -> -1
                }
                if (no && idx < 0) {
                    pendChoices = null
                    return "ठीक है, कोई जवाब नहीं भेजा।"
                }
                if (idx in ch.indices) {
                    val m = pendMsg
                    pendChoices = null
                    if (m != null) return sendReply(m, ch[idx])
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ 6. expense report

    private fun expenseReport(hands: Hands): String {
        val ctx = appCtx ?: return "अभी रिपोर्ट नहीं बन पाई।"
        val a = try { JSONArray(Prefs.expenses) } catch (e: Exception) { JSONArray() }
        if (a.length() == 0) return "अभी कोई खर्च दर्ज नहीं है। बोलो, आज चाय पर 20 रुपये खर्च हुए।"
        val cal = Calendar.getInstance()
        fun monthStart(c: Calendar) = (c.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val thisStart = monthStart(cal)
        val prev = (cal.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val prevStart = monthStart(prev)
        var cur = 0
        var old = 0
        val byCat = HashMap<String, Int>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val ts = o.optLong("ts")
            val amt = o.optInt("a")
            if (ts >= thisStart) {
                cur += amt
                val c = o.optString("c").ifBlank { "अन्य" }
                byCat[c] = (byCat[c] ?: 0) + amt
            } else if (ts >= prevStart) {
                old += amt
            }
        }
        if (cur == 0 && old == 0) return "इस महीने और पिछले महीने का कोई खर्च दर्ज नहीं है।"
        val top = byCat.entries.sortedByDescending { it.value }.take(6)
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val sb = StringBuilder("इस महीने अब तक $cur रुपये खर्च हुए")
        if (old > 0) {
            val diff = ((cur - old) * 100f / old).toInt()
            sb.append(if (diff >= 0) ", पिछले महीने $old थे, यानी $diff प्रतिशत ज़्यादा" else ", पिछले महीने $old थे, यानी ${-diff} प्रतिशत कम")
        }
        sb.append("।")
        if (top.isNotEmpty()) sb.append(" सबसे ज़्यादा ${top[0].key} पर ${top[0].value}।")
        if (day >= 5 && cur > 0) sb.append(" इसी रफ़्तार से महीने के अंत तक लगभग ${cur * days / day} रुपये हो जाएँगे।")
        try {
            val i = Intent(ctx, ReportActivity::class.java)
                .putExtra("title", "इस महीने ₹$cur" + if (old > 0) "  (पिछला ₹$old)" else "")
                .putStringArrayListExtra("labels", ArrayList(top.map { it.key }))
                .putIntegerArrayListExtra("values", ArrayList(top.map { it.value }))
            hands.openActivity(i)
            sb.append(" चार्ट खोल रहा हूँ।")
        } catch (e: Exception) {
            // voice report is enough
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ 9. document reading

    @Volatile var docMode = false
    private var pendDoc: Triple<String, String, Long>? = null

    const val DOC_PROMPT = "यह किसी बिल, चिट्ठी, फ़ॉर्म या कागज़ की फोटो है। हिंदी (थोड़ी English मिलाकर) में 3 से 4 छोटे वाक्यों में बताओ: " +
        "यह क्या है, कितनी रकम है (अगर है), आख़िरी तारीख़ क्या है (अगर है), और मुझे क्या करना है। " +
        "सबसे आख़िरी अलग लाइन में सिर्फ़ यह लिखो: REMIND|YYYY-MM-DD|छोटा शीर्षक (अगर कोई आख़िरी तारीख़ है), वरना REMIND|none"

    fun docReply(raw: String): String {
        val line = raw.lines().lastOrNull { it.trim().startsWith("REMIND|") }
        val body = raw.lines().filter { !it.trim().startsWith("REMIND|") }.joinToString(" ").replace(Regex("[*#]+"), "").trim()
        if (line == null) return body
        val p = line.trim().split("|")
        if (p.size < 3 || p[1].trim() == "none") return body
        val date = p[1].trim()
        val ok = try {
            val dt = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)
            dt != null && date >= today()
        } catch (e: Exception) {
            false
        }
        if (!ok) return body
        val title = p[2].trim().ifBlank { "ज़रूरी काम" }
        pendDoc = Triple(title, date, System.currentTimeMillis())
        return "$body आख़िरी तारीख़ $date है। इसका रिमाइंडर लगा दूँ?"
    }

    private fun addDateReminder(title: String, date: String): String {
        val a = rules()
        val td = today()
        val dayBefore = try {
            val c = Calendar.getInstance()
            c.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!
            c.add(Calendar.DAY_OF_MONTH, -1)
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
        } catch (e: Exception) {
            date
        }
        if (dayBefore > td) {
            a.put(JSONObject().put("id", System.nanoTime()).put("k", "date").put("date", dayBefore).put("at", "09:00").put("cmd", "बोलो कल $title की आख़िरी तारीख़ है").put("last", ""))
        }
        a.put(JSONObject().put("id", System.nanoTime() + 1).put("k", "date").put("date", date).put("at", "09:00").put("cmd", "बोलो आज $title की आख़िरी तारीख़ है").put("last", ""))
        Prefs.rules = a.toString()
        return "ठीक है, $date से एक दिन पहले और उसी दिन सुबह 9 बजे याद दिलाऊँगा। Zyro चालू रखना।"
    }

    // ------------------------------------------------------------------ 8. automation rules

    private var wasHome: Boolean? = null
    private var receiver: BroadcastReceiver? = null
    private var ctxForRun: Context? = null
    private var handsForRun: Hands? = null

    private fun rules(): JSONArray = try { JSONArray(Prefs.rules) } catch (e: Exception) { JSONArray() }

    private fun describe(o: JSONObject): String {
        val c = o.optString("cmd")
        return when (o.optString("k")) {
            "time" -> "रोज़ ${o.optString("at")} बजे: $c"
            "plug" -> "चार्जर लगाने पर: $c"
            "unplug" -> "चार्जर निकालने पर: $c"
            "homeIn" -> "घर के वाईफाई से जुड़ने पर: $c"
            "homeOut" -> "घर का वाईफाई छोड़ने पर: $c"
            "date" -> "${o.optString("date")} को: $c"
            else -> c
        }
    }

    private fun currentSsid(ctx: Context): String {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wm.connectionInfo?.ssid?.trim('"') ?: ""
            if (ssid.isBlank() || ssid.contains("unknown ssid", true)) "" else ssid
        } catch (e: Exception) {
            ""
        }
    }

    fun start(ctx: Context, hands: Hands) {
        ctxForRun = ctx.applicationContext
        handsForRun = hands
        if (receiver != null) return
        val home = Prefs.homeSsid
        wasHome = if (home.isBlank()) null else (currentSsid(ctx) == home)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_POWER_CONNECTED -> fire("plug")
                    Intent.ACTION_POWER_DISCONNECTED -> fire("unplug")
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        val h = Prefs.homeSsid
                        if (h.isBlank()) return
                        val nowHome = currentSsid(c) == h
                        val before = wasHome
                        wasHome = nowHome
                        if (before != null && before != nowHome) fire(if (nowHome) "homeIn" else "homeOut")
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        try {
            ctx.applicationContext.registerReceiver(r, f)
            receiver = r
        } catch (e: Exception) {
            // automation by time still works
        }
    }

    fun stop(ctx: Context) {
        try { receiver?.let { ctx.applicationContext.unregisterReceiver(it) } } catch (e: Exception) { /* ignore */ }
        receiver = null
        wasHome = null
    }

    private fun fire(kind: String) {
        val a = rules()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("k") == kind) runCmd(o.optString("cmd"))
        }
    }

    private val BLOCKED = listOf("sms", "एसएमएस", "call", "कॉल", "फोन करो", "message", "मैसेज", "whatsapp", "व्हाट्सएप", "व्हाट्सऐप")

    private fun runCmd(cmd: String) {
        val ctx = ctxForRun ?: return
        val hands = handsForRun ?: return
        val c = cmd.trim()
        if (c.isBlank()) return
        Thread {
            val say = Regex("^(bolo|bol do|say|बोलो|बोल दो|कहो)\\s+(.+)$", RegexOption.IGNORE_CASE).find(c)
            val reply = if (say != null) {
                say.groupValues[2]
            } else if (BLOCKED.any { c.contains(it, true) }) {
                "ऑटोमेशन में कॉल या मैसेज नहीं चलते, ये सुरक्षा के लिए रोका है।"
            } else {
                try { Local.handle(hands, c) } catch (e: Exception) { null }
            }
            if (!reply.isNullOrBlank()) Assistant.get(ctx).announce(reply)
        }.start()
    }

    /** Called once a minute. */
    fun tick() {
        val cal = Calendar.getInstance()
        val td = today()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val a = rules()
        val keep = JSONArray()
        var changed = false
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val k = o.optString("k")
            if (k == "time" || k == "date") {
                val parts = o.optString("at", "09:00").split(":")
                val at = try { parts[0].toInt() * 60 + parts[1].toInt() } catch (e: Exception) { 540 }
                val dayOk = k == "time" || o.optString("date") == td
                val due = dayOk && nowMin >= at && nowMin - at <= 15 && o.optString("last") != td
                if (due) {
                    runCmd(o.optString("cmd"))
                    o.put("last", td)
                    changed = true
                    if (k == "date") continue
                }
                if (k == "date" && o.optString("date") < td) {
                    changed = true
                    continue
                }
            }
            keep.put(o)
        }
        if (changed) Prefs.rules = keep.toString()
    }

    private fun createRule(t: String): String? {
        val m = Regex("(?:^|\\s)(jab|जब)\\s+(.+?)\\s+(?:tab|तब|to|तो|toh)\\s+(.+)$").find(t) ?: return null
        val left = m.groupValues[2]
        val cmd = m.groupValues[3].trim()
        if (cmd.isBlank()) return null
        val toks = left.split(Regex("\\s+"))
        val o = JSONObject().put("id", System.nanoTime()).put("cmd", cmd).put("last", "")
        fun has(vararg w: String) = w.any { left.contains(s(it)) }
        val bajeIdx = toks.indexOfFirst { it == s("baje") || it == s("बजे") || it == "baje" }
        when {
            bajeIdx > 0 -> {
                var h = toks[bajeIdx - 1].toIntOrNull() ?: Local.numberOf(toks[bajeIdx - 1]) ?: return null
                val min = if (bajeIdx > 1) (toks[bajeIdx - 2].takeIf { it.startsWith(":") }?.drop(1)?.toIntOrNull() ?: 0) else 0
                if (has("shaam", "शाम", "raat", "रात", "dopahar", "दोपहर") && h < 12) h += 12
                if (has("raat", "रात") && h == 24) h = 0
                if (h !in 0..23) return null
                o.put("k", "time").put("at", String.format(Locale.US, "%02d:%02d", h, min))
            }
            has("charger", "चार्जर", "charge") -> {
                val out = has("nikal", "निकाल", "hata", "हटा", "unplug")
                o.put("k", if (out) "unplug" else "plug")
            }
            has("ghar", "घर") && has("wifi", "वाईफाई", "वाई-फाई", "वाईफ़ाई", "वाई फाई", "वाइफाई") -> {
                if (Prefs.homeSsid.isBlank()) return "पहले घर के वाईफाई से जुड़कर बोलो: ये घर का वाईफाई है।"
                val out = has("chhod", "छोड़", "nikal", "निकल", "bahar", "बाहर", "disconnect", "कट")
                o.put("k", if (out) "homeOut" else "homeIn")
            }
            else -> return null
        }
        val a = rules()
        a.put(o)
        Prefs.rules = a.toString()
        return "ऑटोमेशन बन गया। " + describe(o) + "।"
    }

    private fun automation(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        if (any("ghar ka wifi", "घर का वाईफाई", "घर का वाई फाई", "ghar ka wi fi") && any("yaad", "याद", "hai", "है", "save", "सेव")) {
            val ctx = appCtx ?: return null
            val ssid = currentSsid(ctx)
            if (ssid.isBlank()) return "वाईफाई का नाम नहीं मिल रहा। घर के वाईफाई से जुड़ो, Location चालू करो और Zyro को लोकेशन की अनुमति दो।"
            Prefs.homeSsid = ssid
            wasHome = true
            return "ठीक है, $ssid को घर का वाईफाई याद कर लिया।"
        }
        val auto = any("automation", "ऑटोमेशन", "niyam", "नियम", "rule", "रूल")
        if (auto && any("list", "लिस्ट", "batao", "बताओ", "kya kya", "क्या क्या", "sunao", "सुनाओ")) {
            val a = rules()
            if (a.length() == 0) return "अभी कोई ऑटोमेशन नहीं है। बोलो, जब चार्जर लगाऊँ तो बैटरी बताओ।"
            val sb = StringBuilder("${a.length()} ऑटोमेशन हैं। ")
            for (i in 0 until a.length()) sb.append("${i + 1}. ").append(describe(a.getJSONObject(i))).append("। ")
            return sb.toString()
        }
        if (auto && any("hatao", "हटाओ", "delete", "मिटाओ", "mitao", "band", "बंद", "clear")) {
            val n = words.firstNotNullOfOrNull { it.toIntOrNull() ?: Local.numberOf(it) }
            val a = rules()
            if (n != null && n in 1..a.length() && !any("saare", "सारे", "sab", "सब")) {
                val keep = JSONArray()
                for (i in 0 until a.length()) if (i != n - 1) keep.put(a.get(i))
                Prefs.rules = keep.toString()
                return "ऑटोमेशन $n हटा दिया।"
            }
            Prefs.rules = "[]"
            return "सारे ऑटोमेशन हटा दिए।"
        }
        if (t.startsWith("jab ") || t.startsWith(s("जब") + " ") || auto) {
            return createRule(t)
        }
        return null
    }

    // ------------------------------------------------------------------ voice commands

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(s(it)) }

        // translator
        val transWord = any("translator", "ट्रांसलेटर", "translate", "ट्रांसलेट", "अनुवाद", "anuvad", "interpreter", "दुभाषिया")
        if (transWord) {
            if (any("band", "बंद", "stop", "off", "khatam", "खत्म")) {
                translateLang = null
                return "अनुवादक बंद।"
            }
            val l = langOf(t)
            if (l != null) {
                translateLang = l.second
                capKind = null
                return "अनुवादक चालू। अब जो बोलोगे, मैं ${l.second} में बोलूँगा, और ${l.second} में कोई बोले तो हिंदी में। बंद करने के लिए बोलो, अनुवादक बंद।"
            }
        }

        // meeting recorder
        val meetWord = any("meeting", "मीटिंग", "lecture", "लेक्चर", "class notes", "क्लास नोट्स", "क्लास नोट")
        if (meetWord) {
            if (any("summary", "सारांश", "saransh", "padho", "पढ़ो", "sunao", "सुनाओ", "last", "पिछली")) {
                return lastMeeting()?.let { shorten(it, 1200) } ?: "अभी कोई मीटिंग सेव नहीं है।"
            }
            if (any("shuru", "शुरू", "start", "record", "रिकॉर्ड")) {
                capKind = "meeting"
                translateLang = null
                synchronized(cap) { cap.clear() }
                return "मीटिंग रिकॉर्डिंग शुरू। मैं चुपचाप सुनता रहूँगा। खत्म करने के लिए बोलो, मीटिंग खत्म।"
            }
        }

        // voice diary
        if (any("diary", "डायरी")) {
            if (any("shuru", "शुरू", "start", "mode", "मोड", "chalu", "चालू")) {
                capKind = "diary"
                translateLang = null
                synchronized(cap) { cap.clear() }
                return "डायरी चालू। जो बोलोगे, चुपचाप लिखता रहूँगा। बंद करने के लिए बोलो, डायरी बंद।"
            }
            val w = Regex("(?:likho|लिखो|likh do|लिख दो|jodo|जोड़ो|add karo|add kar do)\\s+(.+)$").find(t)
            if (w != null) {
                diaryAdd(w.groupValues[1])
                return "डायरी में लिख लिया।"
            }
            if (any("padho", "पढ़ो", "sunao", "सुनाओ", "aaj", "आज", "summary", "सारांश", "kya hua", "क्या हुआ")) return diarySummary()
        }

        // smart reply
        if (any("smart reply", "स्मार्ट रिप्लाई", "sujhao", "सुझाओ", "kya jawab", "क्या जवाब", "kya reply", "क्या रिप्लाई", "jawab sujha", "जवाब सुझा", "reply sujha")) {
            return smartReply()
        }
        if (any("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "व्हाट्सअप", "whatsap") && any("jawab", "जवाब", "reply", "रिप्लाई")) {
            return customReply(raw)
        }

        // expense report
        if (any("report", "रिपोर्ट", "chart", "चार्ट", "graph", "ग्राफ") && any("kharch", "खर्च", "hisab", "हिसाब", "expense")) {
            return expenseReport(hands)
        }

        // documents
        val docWord = listOf("bill", "बिल", "letter", "लेटर", "chitthi", "चिट्ठी", "document", "डॉक्यूमेंट", "kagaz", "कागज", "कागज़", "receipt", "रसीद", "notice", "नोटिस", "form", "फॉर्म").any { words.contains(s(it)) }
        val readWord = any("padho", "पढ़ो", "padh", "पढ़", "scan", "स्कैन", "samjhao", "समझाओ")
        if (docWord && readWord && !any("screen", "स्क्रीन")) {
            if (!Prefs.hasGemini) return "कागज़ पढ़ने के लिए Gemini key चाहिए।"
            return try {
                docMode = true
                hands.openActivity(Intent(ctx, CameraActivity::class.java))
                "कैमरा खुल रहा है। कागज़ की साफ़ फोटो खींचकर OK दबा दो।"
            } catch (e: Exception) {
                docMode = false
                "कैमरा नहीं खुल पाया।"
            }
        }

        return automation(t, words)
    }
}
