package com.zyro.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Extra helpers: focus timer, repeating reminders, birthdays and exam countdowns, money calculators,
 * camera helpers (food, homework, signs, plants, medicine strips), SOS torch, monthly budget and a music sleep timer.
 */
object Plus {
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun s(x: String) = Local.strip(x)

    private fun say(text: String) {
        val c = appCtx ?: return
        Assistant.get(c).announce(text)
    }

    // ------------------------------------------------------------------ number helpers

    private class Num(val v: Double, val unit: String)

    private val LAKH = setOf("lakh", "लाख", "lac", "lakhs").map { s(it) }.toSet()
    private val CRORE = setOf("crore", "करोड़", "करोड", "cr").map { s(it) }.toSet()
    private val HAZAR = setOf("hazar", "hazaar", "हज़ार", "हजार", "thousand", "k").map { s(it) }.toSet()
    private val PCT = setOf("percent", "प्रतिशत", "pratishat", "fisadi", "फ़ीसदी", "फीसदी", "percentage", "%", "परसेंट", "पर्सेंट").map { s(it) }.toSet()
    private val YEAR = setOf("saal", "साल", "year", "years", "varsh", "वर्ष").map { s(it) }.toSet()
    private val MONTH = setOf("mahine", "महीने", "month", "months", "mahina", "महीना").map { s(it) }.toSet()

    private fun nums(words: List<String>): List<Num> {
        val out = ArrayList<Num>()
        var i = 0
        while (i < words.size) {
            var w = words[i].replace(",", "")
            var unit = words.getOrElse(i + 1) { "" }
            var pct = false
            if (w.endsWith("%")) {
                w = w.dropLast(1)
                pct = true
            }
            val v = w.toDoubleOrNull() ?: Local.numberOf(w)?.toDouble()
            if (v == null) {
                i++
                continue
            }
            var value = v
            var step = 1
            val mult = when (unit) {
                in LAKH -> 1e5
                in CRORE -> 1e7
                in HAZAR -> 1e3
                else -> 1.0
            }
            if (mult != 1.0) {
                value *= mult
                unit = words.getOrElse(i + 2) { "" }
                step = 2
            }
            out.add(Num(value, if (pct) "percent" else unit))
            i += step
        }
        return out
    }

    private fun money(x: Double): String {
        val r = Math.round(x)
        return if (r >= 10_000_000) String.format(Locale.US, "%.2f करोड़", r / 1e7)
        else if (r >= 100_000) String.format(Locale.US, "%.2f लाख", r / 1e5)
        else r.toString()
    }

    private fun finance(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        val ns = nums(words)
        if (words.contains("emi") || words.contains("ईएमआई") || any("loan ki kist", "लोन की किस्त")) {
            val rate = ns.firstOrNull { it.unit in PCT }?.v ?: return "EMI के लिए बोलो: EMI निकालो, 5 लाख, 10 प्रतिशत, 5 साल।"
            val yrs = ns.firstOrNull { it.unit in YEAR }?.v
            val mon = ns.firstOrNull { it.unit in MONTH }?.v
            val n = (if (yrs != null) yrs * 12 else mon) ?: return "कितने साल या महीने के लिए? ऐसे बोलो: EMI निकालो, 5 लाख, 10 प्रतिशत, 5 साल।"
            val p = ns.filter { it.unit !in PCT && it.unit !in YEAR && it.unit !in MONTH }.maxByOrNull { it.v }?.v ?: return "लोन की रकम बताओ।"
            val r = rate / 12 / 100
            val emi = if (r == 0.0) p / n else p * r * Math.pow(1 + r, n) / (Math.pow(1 + r, n) - 1)
            val total = emi * n
            return "${money(p)} रुपये के लोन पर हर महीने की EMI लगभग ${money(emi)} रुपये होगी। कुल ब्याज ${money(total - p)} और कुल देना ${money(total)} रुपये।"
        }
        if (any("baant", "बांट", "बाँट", "split", "hissa", "हिस्सा") && ns.any { it.unit in setOf("log", "लोग", "people", "logon", "लोगों", "dost", "दोस्त", "persons").map { x -> s(x) } }) {
            val people = ns.first { it.unit in setOf("log", "लोग", "people", "logon", "लोगों", "dost", "दोस्त", "persons").map { x -> s(x) } }.v.toInt().coerceAtLeast(1)
            val tip = ns.firstOrNull { it.unit in PCT }?.v ?: 0.0
            val bill = ns.filter { it.unit !in PCT && it.v.toInt() != people }.maxByOrNull { it.v }?.v ?: return "बिल की रकम बताओ।"
            val total = bill * (1 + tip / 100)
            return "कुल ${money(total)} रुपये, $people लोगों में हर एक के ${money(total / people)} रुपये।"
        }
        if (any("discount", "डिस्काउंट", "chhoot", "छूट", "chhut", "sale", "off") && ns.any { it.unit in PCT }) {
            val pct = ns.first { it.unit in PCT }.v
            val price = ns.filter { it.unit !in PCT }.maxByOrNull { it.v }?.v ?: return "असली कीमत बताओ।"
            val cut = price * pct / 100
            return "${money(price)} पर ${pct.toInt()} प्रतिशत छूट के बाद कीमत ${money(price - cut)} रुपये, यानी ${money(cut)} रुपये की बचत।"
        }
        if (words.contains("gst") || words.contains("जीएसटी")) {
            val pct = ns.firstOrNull { it.unit in PCT }?.v ?: 18.0
            val price = ns.filter { it.unit !in PCT }.maxByOrNull { it.v }?.v ?: return "रकम बताओ, जैसे: 1000 पर GST 18 प्रतिशत।"
            return if (any("include", "शामिल", "andar", "अंदर", "pehle", "पहले")) {
                val base = price / (1 + pct / 100)
                "${money(price)} में GST शामिल है तो असली कीमत ${money(base)} और GST ${money(price - base)} रुपये है।"
            } else {
                "${money(price)} पर ${pct.toInt()} प्रतिशत GST ${money(price * pct / 100)} रुपये है, कुल ${money(price * (1 + pct / 100))} रुपये।"
            }
        }
        return null
    }

    // ------------------------------------------------------------------ focus timer (pomodoro)

    private var focusToken = 0
    @Volatile private var focusOn = false

    private fun focusStep(token: Int, round: Int, rounds: Int, work: Int, rest: Int, working: Boolean) {
        main.postDelayed({
            if (token != focusToken || !focusOn) return@postDelayed
            if (working) {
                if (round >= rounds) {
                    focusOn = false
                    say("शाबाश! $rounds राउंड का फ़ोकस पूरा हुआ। अब लंबा आराम करो।")
                } else {
                    say("फ़ोकस का समय पूरा। $rest मिनट का ब्रेक लो, पानी पी लो।")
                    focusStep(token, round, rounds, work, rest, false)
                }
            } else {
                say("ब्रेक खत्म। राउंड ${round + 1} शुरू, $work मिनट फ़ोकस करो।")
                focusStep(token, round + 1, rounds, work, rest, true)
            }
        }, (if (working) work else rest) * 60_000L)
    }

    private fun focus(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        if (!any("focus", "फोकस", "फ़ोकस", "pomodoro", "पोमोडोरो")) return null
        if (any("band", "बंद", "stop", "cancel", "khatam", "खत्म")) {
            focusOn = false
            focusToken++
            return "फ़ोकस मोड बंद।"
        }
        if (!any("shuru", "शुरू", "start", "mode", "मोड", "chalu", "चालू", "pomodoro", "पोमोडोरो", "minute", "मिनट")) return null
        var work = 25
        for (i in 1 until words.size) {
            if (words[i].startsWith(s("minute")) || words[i].startsWith(s("मिनट"))) {
                val n = words[i - 1].toIntOrNull() ?: Local.numberOf(words[i - 1])
                if (n != null && n in 5..120) work = n
                break
            }
        }
        val rest = if (work >= 45) 10 else 5
        focusToken++
        focusOn = true
        focusStep(focusToken, 1, 4, work, rest, true)
        return "फ़ोकस मोड शुरू। $work मिनट बिना रुके काम करो, फिर $rest मिनट का ब्रेक। कुल 4 राउंड। मैं याद दिलाऊँगा।"
    }

    // ------------------------------------------------------------------ repeating reminders

    private fun every(): JSONArray = try { JSONArray(Prefs.every) } catch (e: Exception) { JSONArray() }

    private val EVERY_NOISE = setOf(
        "har", "हर", "mujhe", "मुझे", "ko", "को", "ki", "की", "ka", "का", "ke", "के", "mein", "में", "me", "minute", "मिनट", "ghante", "घंटे", "ghanta", "घंटा", "hour",
        "baad", "बाद", "hey", "micro", "माइक्रो", "pe", "पे", "par", "पर", "roz", "रोज़", "aadha", "आधा"
    ).map { s(it) }.toSet()

    private fun repeating(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        if (any("yaad dilana band", "याद दिलाना बंद", "saare reminder band", "सारे रिमाइंडर बंद", "repeat reminder band")) {
            Prefs.every = "[]"
            return "बार-बार याद दिलाने वाले सारे रिमाइंडर बंद कर दिए।"
        }
        if (!(any("yaad dila", "याद दिला") && (words.contains("har") || words.contains("हर")))) return null
        var minutes = 0
        for (i in 1 until words.size) {
            val u = words[i]
            val n = words[i - 1].toIntOrNull() ?: Local.numberOf(words[i - 1]) ?: continue
            if (u.startsWith(s("minute")) || u.startsWith(s("मिनट"))) { minutes = n; break }
            if (u.startsWith(s("ghant")) || u.startsWith(s("घंट")) || u.startsWith(s("hour"))) { minutes = n * 60; break }
        }
        if (minutes == 0 && (any("ghante", "घंटे", "ghanta", "घंटा", "hour"))) minutes = 60
        if (minutes < 5) return "कितने मिनट बाद? ऐसे बोलो: हर 45 मिनट में पानी पीने की याद दिलाओ।"
        val yi = words.indexOfFirst { it.startsWith(s("yaad")) || it.startsWith(s("याद")) }
        val thing = words.subList(0, yi.coerceAtLeast(0)).filter { it !in EVERY_NOISE && it.toIntOrNull() == null && Local.numberOf(it) == null }.joinToString(" ").ifBlank { "ब्रेक" }
        val a = every()
        a.put(JSONObject().put("id", System.nanoTime()).put("min", minutes).put("thing", thing).put("next", System.currentTimeMillis() + minutes * 60_000L))
        Prefs.every = a.toString()
        return "ठीक है, हर $minutes मिनट पर $thing की याद दिलाऊँगा, सुबह 8 से रात 10 बजे के बीच। Zyro चालू रखना।"
    }

    // ------------------------------------------------------------------ birthdays and exam countdowns

    private val MONTHS: Map<String, Int> = mapOf(
        "january" to 1, "jan" to 1, "जनवरी" to 1, "february" to 2, "feb" to 2, "फरवरी" to 2, "march" to 3, "मार्च" to 3, "april" to 4, "अप्रैल" to 4,
        "may" to 5, "मई" to 5, "june" to 6, "जून" to 6, "july" to 7, "जुलाई" to 7, "august" to 8, "अगस्त" to 8, "september" to 9, "sep" to 9, "सितंबर" to 9, "सितम्बर" to 9,
        "october" to 10, "oct" to 10, "अक्टूबर" to 10, "november" to 11, "nov" to 11, "नवंबर" to 11, "नवम्बर" to 11, "december" to 12, "dec" to 12, "दिसंबर" to 12, "दिसम्बर" to 12
    ).mapKeys { s(it.key) }

    private fun events(): JSONArray = try { JSONArray(Prefs.events) } catch (e: Exception) { JSONArray() }

    private fun kindOf(t: String): String? = when {
        listOf("birthday", "बर्थडे", "जन्मदिन", "janamdin", "janmdin", "जन्मदिवस").any { t.contains(s(it)) } -> "birthday"
        listOf("anniversary", "एनिवर्सरी", "सालगिरह", "salgirah").any { t.contains(s(it)) } -> "anniversary"
        listOf("exam", "एग्जाम", "एक्ज़ाम", "परीक्षा", "pariksha", "paper", "पेपर").any { t.contains(s(it)) } -> "exam"
        listOf("event", "इवेंट", "function", "फंक्शन", "shaadi", "शादी", "interview", "इंटरव्यू", "trip", "ट्रिप").any { t.contains(s(it)) } -> "event"
        else -> null
    }

    private fun label(o: JSONObject): String {
        val n = o.optString("n")
        return when (o.optString("k")) {
            "birthday" -> if (n.isBlank()) "आपका जन्मदिन" else "$n का जन्मदिन"
            "anniversary" -> if (n.isBlank()) "सालगिरह" else "$n की सालगिरह"
            "exam" -> if (n.isBlank()) "परीक्षा" else "$n की परीक्षा"
            else -> if (n.isBlank()) "इवेंट" else n
        }
    }

    private fun daysUntil(m: Int, d: Int, y: Int): Long {
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val c = (today.clone() as Calendar).apply { set(Calendar.MONTH, m - 1); set(Calendar.DAY_OF_MONTH, d) }
        if (y > 0) c.set(Calendar.YEAR, y) else if (c.before(today)) c.add(Calendar.YEAR, 1)
        return Math.round((c.timeInMillis - today.timeInMillis) / 86_400_000.0)
    }

    private val EV_NOISE = setOf(
        "ka", "का", "ki", "की", "ke", "के", "ko", "को", "meri", "मेरी", "mera", "मेरा", "mere", "मेरे", "hai", "है", "hain", "हैं", "yaad", "याद", "rakho", "रखो", "likh", "लिख", "lo", "लो", "save",
        "hey", "micro", "माइक्रो", "tarikh", "तारीख", "तारीख़", "tareekh", "ek", "एक", "ko", "pe", "पे", "par", "पर", "me", "mein", "में", "agle", "next", "kab", "कब", "kitne", "कितने", "din", "दिन", "bache", "बचे", "baki", "बाकी"
    ).map { s(it) }.toSet()

    private fun eventsHandler(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        val kind = kindOf(t)
        val a = events()
        if (any("sab events hatao", "सारे इवेंट हटाओ", "saari tarikhen hatao", "सारी तारीखें हटाओ")) {
            Prefs.events = "[]"
            return "सारी तारीखें मिटा दीं।"
        }
        if ((any("aane wale", "आने वाले", "upcoming", "agle") && (kind != null || any("event", "इवेंट", "tarikh", "तारीख"))) ) {
            if (a.length() == 0) return "अभी कोई तारीख़ सेव नहीं है। बोलो, राहुल का जन्मदिन 5 मार्च को है।"
            val list = (0 until a.length()).map { a.getJSONObject(it) }
                .map { Pair(it, daysUntil(it.optInt("m"), it.optInt("d"), it.optInt("y"))) }
                .filter { it.second >= 0 }.sortedBy { it.second }.take(5)
            if (list.isEmpty()) return "आगे कोई तारीख़ बाकी नहीं है।"
            return list.joinToString("। ") { (o, d) -> label(o) + if (d == 0L) " आज है" else " $d दिन बाद" }
        }
        if (kind == null) return null
        val askWords = any("kitne din", "कितने दिन", "kab hai", "कब है", "bache", "बचे", "baki", "बाकी", "kab", "कब")
        val mon = words.firstNotNullOfOrNull { MONTHS[it] }
        val name = words.filter { it !in EV_NOISE && MONTHS[it] == null && it.toIntOrNull() == null && Local.numberOf(it) == null && kindOf(it) == null }.joinToString(" ").trim()
        if (askWords && mon == null) {
            val hits = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("k") == kind && (name.isBlank() || s(it.optString("n")).contains(name) || name.contains(s(it.optString("n")))) }
            if (hits.isEmpty()) return "ऐसी कोई तारीख़ सेव नहीं है।"
            return hits.joinToString("। ") { o ->
                val d = daysUntil(o.optInt("m"), o.optInt("d"), o.optInt("y"))
                label(o) + when {
                    d == 0L -> " आज है!"
                    d == 1L -> " कल है।"
                    d > 1 -> " में $d दिन बाकी हैं।"
                    else -> " हो चुकी है।"
                }
            }
        }
        if (mon == null) return null
        val dayTok = words.firstNotNullOfOrNull { w -> (w.toIntOrNull() ?: Local.numberOf(w))?.takeIf { it in 1..31 } } ?: return null
        val year = words.firstNotNullOfOrNull { it.toIntOrNull()?.takeIf { y -> y in 2000..2100 } } ?: 0
        val o = JSONObject().put("n", name).put("k", kind).put("m", mon).put("d", dayTok).put("y", year).put("last", "")
        val keep = JSONArray()
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            if (!(x.optString("k") == kind && x.optString("n") == name && x.optInt("m") == mon && x.optInt("d") == dayTok)) keep.put(x)
        }
        keep.put(o)
        Prefs.events = keep.toString()
        val d = daysUntil(mon, dayTok, year)
        return "सेव कर लिया: ${label(o)}, $dayTok/$mon। " + when {
            d == 0L -> "वो आज ही है!"
            d > 0 -> "अभी $d दिन बाकी हैं। उस दिन और एक दिन पहले सुबह 9 बजे याद दिलाऊँगा।"
            else -> "यह तारीख़ निकल चुकी है।"
        }
    }

    // ------------------------------------------------------------------ camera helpers

    @Volatile var camPrompt: String? = null

    private fun camera(hands: Hands, t: String, words: List<String>): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        val camWord = any("photo", "फोटो", "camera", "कैमरा", "scan", "स्कैन", "dekho", "देखो", "dikha", "दिखा")
        val prompt: String? = when {
            any("calorie", "calories", "कैलोरी") && (camWord || any("khana", "खाना", "thali", "थाली")) ->
                "इस खाने की फोटो देखो। हिंदी (थोड़ी English मिलाकर) में 3 छोटे वाक्यों में बताओ: यह क्या-क्या है, अंदाज़े से कुल कैलोरी, और प्रोटीन/कार्ब/फैट का मोटा अंदाज़ा। साफ़ कहो कि यह सिर्फ़ अंदाज़ा है।"
            any("solve", "हल", "hal karo", "हल करो", "samjhao", "समझाओ", "sawal", "सवाल", "homework", "होमवर्क", "math", "गणित") && camWord && !any("screen", "स्क्रीन") ->
                "फोटो में जो सवाल या समस्या है उसे हल करो। हिंदी में छोटे-छोटे स्टेप में समझाओ और आख़िर में जवाब साफ़ बताओ। बोलकर सुनाने लायक सादा टेक्स्ट लिखो, कोई markdown नहीं।"
            any("translate", "ट्रांसलेट", "anuvad", "अनुवाद") && any("photo", "फोटो", "camera", "कैमरा", "board", "बोर्ड", "menu", "मेन्यू", "sign", "साइन") ->
                "फोटो में जो भी लिखा है उसे पढ़ो और हिंदी में उसका मतलब बताओ (अगर पहले से हिंदी है तो English में)। मेन्यू या बोर्ड हो तो मुख्य बातें बताओ। छोटा रखो।"
            any("paudha", "पौधा", "plant", "bird", "चिड़िया", "janwar", "जानवर", "kida", "कीड़ा", "insect", "phool", "फूल", "flower") && (camWord || any("pehchan", "पहचान", "identify", "kya hai", "क्या है")) ->
                "फोटो में जो पौधा, फूल, पक्षी, जानवर या कीड़ा है उसे पहचानो। हिंदी में 3 छोटे वाक्यों में बताओ: नाम (हिंदी और English), कुछ ख़ास बातें, और कोई सावधानी (जैसे ज़हरीला या नहीं)। पक्का न हो तो साफ़ कहो।"
            any("dawai", "दवाई", "दवा", "medicine", "tablet", "गोली", "strip") && (camWord || any("pehchan", "पहचान", "kya hai", "क्या है", "padho", "पढ़ो")) ->
                "यह किसी दवा के पत्ते या डिब्बे की फोटो है। हिंदी में बताओ: दवा का नाम, उसमें क्या है, आम तौर पर किस काम आती है, और एक्सपायरी (अगर दिखे)। खुराक कभी मत बताओ। आख़िर में कहो कि यह सिर्फ़ जानकारी है, डॉक्टर या फार्मासिस्ट से पुष्टि कर लो।"
            else -> null
        }
        if (prompt == null) return null
        if (Prefs.geminiKey.isBlank()) return "इसके लिए Gemini key चाहिए।"
        return try {
            camPrompt = prompt
            hands.openActivity(Intent(ctx, CameraActivity::class.java))
            "कैमरा खुल रहा है। फोटो खींचकर OK दबा दो।"
        } catch (e: Exception) {
            camPrompt = null
            "कैमरा नहीं खुल पाया।"
        }
    }

    // ------------------------------------------------------------------ SOS torch

    @Volatile private var sosOn = false

    private fun sos(hands: Hands, t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        val isSos = words.contains("sos") || words.contains("एसओएस") || any("torch blink", "टॉर्च ब्लिंक", "strobe", "स्ट्रोब")
        if (!isSos) return null
        if (any("band", "बंद", "stop", "off")) {
            sosOn = false
            return "SOS बंद।"
        }
        if (sosOn) return "SOS पहले से चल रहा है।"
        sosOn = true
        Thread {
            try {
                val pattern = intArrayOf(200, 200, 200, 200, 200, 600, 600, 200, 600, 200, 600, 600, 200, 200, 200, 200, 200, 1400)
                var rounds = 0
                while (sosOn && rounds < 4) {
                    var on = true
                    for (ms in pattern) {
                        if (!sosOn) break
                        hands.torch(on)
                        Thread.sleep(ms.toLong())
                        on = !on
                    }
                    hands.torch(false)
                    rounds++
                }
            } catch (e: Exception) {
                // stop quietly
            } finally {
                try { hands.torch(false) } catch (e: Exception) { /* ignore */ }
                sosOn = false
            }
        }.start()
        return "SOS फ़्लैश चालू। रोकने के लिए बोलो, SOS बंद।"
    }

    // ------------------------------------------------------------------ monthly budget

    private fun monthTotal(): Int {
        val a = try { JSONArray(Prefs.expenses) } catch (e: Exception) { return 0 }
        val start = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var sum = 0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optLong("ts") >= start) sum += o.optInt("a")
        }
        return sum
    }

    private fun budget(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        if (!any("budget", "बजट")) return null
        val n = nums(words).firstOrNull { it.unit !in PCT }?.v?.toInt()
        if (n != null && n > 0 && !any("kitna", "कितना", "bacha", "बचा")) {
            Prefs.budget = n
            Prefs.budgetWarn = ""
            return "इस महीने का बजट $n रुपये तय। 80 प्रतिशत और 100 प्रतिशत खर्च होने पर मैं बता दूँगा।"
        }
        val b = Prefs.budget
        if (b <= 0) return "अभी बजट तय नहीं है। बोलो, महीने का बजट 10000 रुपये रखो।"
        val used = monthTotal()
        val left = b - used
        val cal = Calendar.getInstance()
        val daysLeft = cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.get(Calendar.DAY_OF_MONTH) + 1
        return if (left >= 0) "बजट $b में से $used खर्च हो चुके हैं, $left बचे हैं। महीने के बचे $daysLeft दिनों के लिए रोज़ लगभग ${left / daysLeft} रुपये।"
        else "बजट $b था और $used खर्च हो चुके हैं, यानी ${-left} रुपये ज़्यादा।"
    }

    // ------------------------------------------------------------------ music sleep timer

    private var sleepToken = 0

    private fun sleepTimer(t: String, words: List<String>): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        val timerWord = any("sleep timer", "स्लीप टाइमर")
        val mediaWord = any("music", "म्यूज़िक", "म्यूजिक", "gaana", "गाना", "gane", "गाने", "video", "वीडियो", "youtube", "यूट्यूब", "song", "सॉन्ग")
        if (timerWord && any("cancel", "कैंसिल", "band", "बंद", "hatao", "हटाओ")) {
            sleepToken++
            return "स्लीप टाइमर रद्द।"
        }
        if (!(timerWord || (mediaWord && any("band", "बंद", "pause", "रोक")))) return null
        var minutes = 0
        for (i in 1 until words.size) {
            if (words[i].startsWith(s("minute")) || words[i].startsWith(s("मिनट"))) {
                minutes = words[i - 1].toIntOrNull() ?: Local.numberOf(words[i - 1]) ?: 0
                break
            }
            if (words[i].startsWith(s("ghant")) || words[i].startsWith(s("घंट"))) {
                minutes = (words[i - 1].toIntOrNull() ?: Local.numberOf(words[i - 1]) ?: 0) * 60
                break
            }
        }
        if (minutes <= 0) return null
        val token = ++sleepToken
        main.postDelayed({
            if (token != sleepToken) return@postDelayed
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            } catch (e: Exception) {
                // nothing to do
            }
        }, minutes * 60_000L)
        return "ठीक है, $minutes मिनट बाद म्यूज़िक या वीडियो रोक दूँगा। शुभ रात्रि!"
    }

    // ------------------------------------------------------------------ tick (once a minute)

    fun tick() {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minuteOfDay = hour * 60 + cal.get(Calendar.MINUTE)

        // repeating reminders
        val ev = every()
        var changed = false
        for (i in 0 until ev.length()) {
            val o = ev.optJSONObject(i) ?: continue
            if (now >= o.optLong("next")) {
                if (hour in 8..21) {
                    say(o.optString("thing") + " का समय हो गया।")
                    o.put("next", now + o.optInt("min") * 60_000L)
                } else {
                    o.put("next", now + 15 * 60_000L)
                }
                changed = true
            }
        }
        if (changed) Prefs.every = ev.toString()

        // birthdays and exams at 9 am
        if (minuteOfDay in 540..555) {
            val td = String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            val a = events()
            var c2 = false
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("last") == td) continue
                val d = daysUntil(o.optInt("m"), o.optInt("d"), o.optInt("y"))
                val text = when {
                    d == 0L && o.optString("k") == "birthday" -> "आज ${label(o)} है! बधाई देना मत भूलना।"
                    d == 0L -> "आज ${label(o)} है।"
                    d == 1L -> "कल ${label(o)} है।"
                    o.optString("k") == "exam" && d in 2..7 -> "${label(o)} में सिर्फ़ $d दिन बचे हैं।"
                    else -> null
                }
                if (text != null) {
                    say(text)
                    o.put("last", td)
                    c2 = true
                }
            }
            if (c2) Prefs.events = a.toString()
        }

        // budget warnings
        val b = Prefs.budget
        if (b > 0) {
            val ym = String.format(Locale.US, "%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
            val used = monthTotal()
            val pct = used * 100 / b
            val w = Prefs.budgetWarn
            if (pct >= 100 && w != "$ym:100") {
                Prefs.budgetWarn = "$ym:100"
                say("सावधान! इस महीने का बजट पूरा हो गया। $used रुपये खर्च हो चुके हैं।")
            } else if (pct >= 80 && w != "$ym:80" && w != "$ym:100") {
                Prefs.budgetWarn = "$ym:80"
                say("बजट का 80 प्रतिशत खर्च हो चुका है। $used में से $b। थोड़ा संभलकर।")
            }
        }
    }

    fun stopAll() {
        focusOn = false
        focusToken++
        sosOn = false
        sleepToken++
    }

    // ------------------------------------------------------------------ voice commands

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        sos(hands, t, words)?.let { return it }
        focus(t, words)?.let { return it }
        sleepTimer(t, words)?.let { return it }
        budget(t, words)?.let { return it }
        finance(t, words)?.let { return it }
        repeating(t, words)?.let { return it }
        camera(hands, t, words)?.let { return it }
        eventsHandler(t, words)?.let { return it }

        // live currency rates
        val cur = words.any { it in setOf("dollar", "डॉलर", "euro", "यूरो", "pound", "पाउंड", "dirham", "दिरहम", "yen", "येन", "riyal", "रियाल").map { x -> s(x) } }
        if (cur && any("rupaye", "रुपये", "rupees", "inr", "kitne", "कितने", "rate", "रेट", "kimat", "कीमत")) {
            return Smart.live(raw)
        }
        return null
    }
}
