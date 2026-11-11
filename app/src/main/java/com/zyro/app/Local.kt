package com.zyro.app

import android.net.Uri
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Instant command layer. Common phone commands (open an app, volume, torch, back/home,
 * search, call, alarm, timer, time ...) are understood right here, on the phone, in Hindi,
 * English or a mix - no internet and no AI needed, so they are fast and always work.
 * Anything it does not understand returns null and goes to the AI brain instead.
 *
 * Call from a background thread (Hands may wait for gestures).
 */
object Local {

    /** Lower-case, and remove the Devanagari "nukta" dot so spelling variants compare equal. */
    internal fun strip(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace("़", "")

    private fun norm(s: String): String =
        strip(s).replace(Regex("[।,?!;\"'()]"), " ").replace(Regex("\\s+"), " ").trim()

    private val OPEN_WORDS = arrayOf(
        "open", "ओपन", "खोल", "launch", "लॉन्च", "लांच", "start", "स्टार्ट",
        "चालू", "चला", "kholo", "khol", "chalu", "लगाओ"
    )
    private val SEARCH_WORDS = arrayOf("search", "सर्च", "ढूंढ", "ढूँढ", "खोजो", "खोज", "dhundo", "play", "प्ले", "बजाओ", "देखना", "देखो", "दिखाओ")
    private val YT_WORDS = arrayOf("youtube", "यूट्यूब", "यू ट्यूब", "यूटयूब", "यूटूब")
    private val STOP_TOKENS = setOf(
        "please", "plz", "प्लीज", "जरा", "भाई", "यार", "अरे", "hey", "ok", "okay", "ओके", "micro", "माइक्रो",
        "मेरे", "लिए", "मेरा", "मेरी", "the", "my", "app", "ऐप", "एप", "एप्प", "application", "को", "तो", "अब", "भई",
        "करो", "कर", "करना", "दो", "दे", "दीजिए", "कीजिए", "जाओ", "चलो", "ज़रा", "जी", "ना", "न",
        "पर", "पे", "में", "मे", "का", "की", "के", "से", "और", "on", "in", "for", "to", "a", "an", "of", "and", "now",
        "go", "can", "you", "could", "will", "kindly", "me", "par", "pe", "ko", "ka", "ki", "ke", "me", "mein", "se",
        "karo", "kar", "do", "de"
    ).map { strip(it) }.toSet()

    private val CALL_TOKENS = listOf("कॉल", "call", "फोन", "dial", "डायल", "लगा", "लगाओ").map { strip(it) }
    private val SEARCH_TOKENS = (SEARCH_WORDS.toList() + listOf("google", "गूगल")).map { strip(it) }
    private val OPEN_TOKENS = OPEN_WORDS.map { strip(it) }

    private val NUM_WORDS: Map<String, Int> = mapOf(
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6, "छे" to 6,
        "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10, "ग्यारह" to 11, "बारह" to 12, "पंद्रह" to 15, "बीस" to 20,
        "पच्चीस" to 25, "तीस" to 30, "पैंतालीस" to 45, "साठ" to 60,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30
    ).mapKeys { strip(it.key) }

    internal fun numberOf(token: String): Int? = token.toIntOrNull() ?: NUM_WORDS[strip(token)]

    /** Returns the sentence to speak, or null if this is not a phone command. */
    fun handle(hands: Hands, raw: String): String? {
        val t = norm(raw)
        if (t.isEmpty()) return null
        val words = t.split(" ")

        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        fun word(vararg w: String) = w.any { x -> words.contains(strip(x)) }

        // ----- answer to a pending WhatsApp draft -----
        Extras.handlePending(hands, t, words)?.let { return it }

        // ----- smart TV remote -----
        if (any("tv", "टीवी", "टी वी", "television", "टेलीविजन")) {
            tvCommand(t, words)?.let { return it }
        }

        // ----- brightness -----
        if (any("brightness", "ब्राइटनेस", "ब्राइटनस", "brightnes", "रोशनी")) {
            val num = words.firstNotNullOfOrNull { numberOf(it) }
            return when {
                any("full", "max", "फुल", "मैक्स", "सबसे ज्यादा") -> hands.brightness(100)
                any("min", "lowest", "मिनिमम", "सबसे कम") -> hands.brightness(5)
                num != null && !any("badha", "बढ़ा", "बढा", "kam", "कम", "घटा", "increase", "decrease", "up", "down") -> hands.brightness(num.coerceIn(0, 100))
                any("बढ़ा", "बढा", "badha", "increase", "up", "ज्यादा", "zyada", "high", "तेज") -> hands.brightness(null, num ?: 20)
                any("कम", "kam", "घटा", "ghata", "decrease", "down", "low", "dim", "धीमा") -> hands.brightness(null, -(num ?: 20))
                else -> hands.openSettings("display")
            }
        }

        // ----- time and date -----
        if (any("कितने बजे", "टाइम क्या", "time kya", "what time", "समय क्या", "समय बताओ", "टाइम बताओ", "current time", "time batao")) {
            return timeReply()
        }
        if (any("तारीख", "today's date", "what date", "what day", "कौन सा दिन", "date batao", "आज की date", "आज की डेट")) {
            val f = SimpleDateFormat("d MMMM, EEEE", Locale.forLanguageTag("hi-IN"))
            return "आज " + f.format(Date()) + " है।"
        }

        // ----- flashlight -----
        if (any("torch", "टॉर्च", "टार्च", "flashlight", "फ्लैशलाइट", "फ्लैश लाइट")) {
            val off = any("off", "बंद", "बन्द", "बुझा", "ऑफ", "band")
            return doIt(hands.torch(!off), if (off) "टॉर्च बंद कर दी।" else "टॉर्च चालू कर दी।")
        }

        // ----- volume -----
        if (any("volume", "वॉल्यूम", "वोल्यूम", "आवाज", "साउंड", "awaaz")) {
            val action = when {
                any("full", "max", "फुल", "मैक्स", "पूरा", "सबसे ज्यादा") -> "max"
                any("कम", "घटा", "down", "decrease", "धीमा", "धीरे", "low", "हल्का", "lower", "reduce") -> "down"
                any("बढ", "ज्यादा", "तेज", "up", "increase", "high", "raise", "louder", "badha") -> "up"
                else -> ""
            }
            if (action.isNotEmpty()) {
                val reply = when (action) {
                    "max" -> "आवाज़ फुल कर दी।"
                    "down" -> "आवाज़ कम कर दी।"
                    else -> "आवाज़ बढ़ा दी।"
                }
                return doIt(hands.volume(action), reply)
            }
        }

        // ----- extras: battery, music, calculator, reminders, WhatsApp, notifications, camera ... -----
        Extras.handle(hands, t, words, raw)?.let { return it }

        // ----- system buttons (short phrases only) -----
        if (words.size <= 4) {
            when {
                word("back", "बैक", "पीछे", "वापस") -> return doIt(hands.press("back"), "ठीक है, पीछे आ गया।")
                word("home", "होम") -> return doIt(hands.press("home"), "होम स्क्रीन पर आ गया।")
                word("recents", "recent", "रिसेंट", "रीसेंट") -> return doIt(hands.press("recents"), "ये लो, recent apps।")
                word("notification", "notifications", "नोटिफिकेशन", "नोटिफिकेशंस") -> return doIt(hands.press("notifications"), "notifications खोल दीं।")
                word("screenshot", "स्क्रीनशॉट") -> return doIt(hands.press("screenshot"), "screenshot ले लिया।")
                word("lock", "लॉक") -> return doIt(hands.press("lock_screen"), "फोन लॉक कर दिया।")
            }
        }

        // ----- scroll -----
        if (any("scroll", "स्क्रॉल", "स्क्रोल")) {
            val down = !any("up", "ऊपर", "upar")
            return doIt(hands.scroll(down), "हो गया।")
        }

        // ----- settings pages (Android does not let apps flip Wi-Fi etc. directly) -----
        val page = when {
            any("wifi", "wi-fi", "वाईफाई", "वाई फाई", "वाइफाई", "वाई-फाई") -> "wifi"
            any("bluetooth", "ब्लूटूथ", "ब्लूटुथ") -> "bluetooth"
            any("airplane", "फ्लाइट मोड", "एयरप्लेन", "हवाई जहाज") -> "airplane"
            any("hotspot", "हॉटस्पॉट") -> "hotspot"
            any("location", "लोकेशन") -> "location"
            any("brightness", "ब्राइटनेस") -> "display"
            else -> ""
        }
        if (page.isNotEmpty()) {
            return doIt(hands.openSettings(page), "सेटिंग्स खोल दी हैं, यहाँ से ON या OFF कर लो।")
        }

        // ----- timer -----
        if (any("timer", "टाइमर")) {
            for (i in 1 until words.size) {
                val mult = when (words[i]) {
                    "minute", "minutes", "min", "मिनट", "मिनिट" -> 60
                    "second", "seconds", "sec", "सेकंड", "सेकेंड", "सेकण्ड" -> 1
                    "hour", "hours", "घंटा", "घंटे", "घण्टा", "घण्टे" -> 3600
                    else -> 0
                }
                val n = numberOf(words[i - 1])
                if (mult > 0 && n != null) {
                    return doIt(hands.setTimer(n * mult, ""), "ठीक है, $n ${unitName(mult)} का timer लगा दिया।")
                }
            }
        }

        // ----- alarm -----
        if (any("alarm", "अलार्म")) {
            val al = parseAlarm(t, words)
            if (al != null) {
                val (h, m) = al
                val h12 = if (h % 12 == 0) 12 else h % 12
                val part = when (h) { in 4..11 -> "सुबह"; in 12..15 -> "दोपहर"; in 16..19 -> "शाम"; else -> "रात" }
                val spoken = if (m == 0) "$part के $h12 बजे" else "$part के $h12 बजकर $m मिनट"
                return doIt(hands.setAlarm(h, m, ""), "ठीक है, $spoken का alarm लगा दिया।")
            }
        }

        // ----- YouTube -----
        if (any(*YT_WORDS)) {
            val q = extractQuery(t, YT_WORDS, true, false)
            return if (q.isNotBlank() && !any("बंद", "बन्द", "band", "close")) {
                val r = hands.openUri("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                doIt(r, "YouTube पर $q ढूँढ दिया।")
            } else {
                openAppReply(hands, "youtube") ?: return null
            }
        }

        // ----- call -----
        if (any("call", "कॉल", "फोन करो", "फोन लगा", "dial", "डायल")) {
            val digits = raw.filter { it.isDigit() }
            if (digits.length >= 6) return doIt(hands.callNumber(digits), "ठीक है, कॉल लगा रहा हूँ।")
            val name = extractQuery(t, emptyArray<String>(), false, true)
            if (name.isNotBlank() && words.size <= 6) {
                if (!hands.hasContactsPermission()) return "Contacts की permission दे दो, फिर मैं नाम से कॉल लगा दूँगा।"
                val c = hands.findContact(name) ?: return "$name नाम का contact मुझे नहीं मिला।"
                return doIt(hands.callNumber(c.second), "ठीक है, ${c.first} को कॉल लगा रहा हूँ।")
            }
        }

        // ----- web search -----
        if (any("search", "सर्च", "ढूंढ", "ढूँढ", "खोजो", "dhundo", "google par", "गूगल पर")) {
            val q = extractQuery(t, emptyArray<String>(), true, false)
            if (q.isNotBlank()) return doIt(hands.webSearch(q), "$q search कर दिया।")
        }

        // ----- open an app -----
        if (any(*OPEN_WORDS)) {
            val app = extractQuery(t, emptyArray<String>(), false, false)
            if (app.isNotBlank() && app.split(" ").size <= 3) {
                return openAppReply(hands, app)
            }
        }

        return null
    }

    // ---------- helpers ----------

    private fun openAppReply(hands: Hands, app: String): String? {
        val r = hands.openApp(app)
        return when {
            r.startsWith("Opened ") -> "ठीक है, " + r.removePrefix("Opened ").trimEnd('.') + " खोल रहा हूँ।"
            r.startsWith("No installed app") -> null // maybe it was not an app request at all - let the AI decide
            else -> fail(r)
        }
    }

    /** What is left of the sentence after removing command words - e.g. the app name or search text. */
    private fun extractQuery(
        t: String,
        extraPhrases: Array<String>,
        dropSearch: Boolean,
        dropCall: Boolean
    ): String {
        var s = " $t "
        for (p in extraPhrases) s = s.replace(strip(p), " ")
        val cmd = ArrayList<String>(OPEN_TOKENS)
        if (dropSearch) cmd.addAll(SEARCH_TOKENS)
        if (dropCall) cmd.addAll(CALL_TOKENS)
        return s.split(" ")
            .filter { tok -> tok.isNotBlank() && tok !in STOP_TOKENS && cmd.none { tok.startsWith(it) } }
            .joinToString(" ")
            .trim()
    }

    internal fun parseAlarm(t: String, words: List<String>): Pair<Int, Int>? {
        var h: Int
        var m = 0
        val hhmm = Regex("(\\d{1,2})[:.](\\d{2})").find(t)
        if (hhmm != null) {
            h = hhmm.groupValues[1].toInt()
            m = hhmm.groupValues[2].toInt()
        } else {
            h = -1
            for (i in 1 until words.size) {
                if (words[i] in setOf("बजे", "baje", "am", "pm", "बजकर", "a.m", "p.m")) {
                    val n = numberOf(words[i - 1])
                    if (n != null) { h = n; break }
                }
            }
            if (h < 0) {
                // forms like "7am" / "7pm"
                val glued = Regex("(\\d{1,2})\\s?(am|pm)").find(t)
                if (glued != null) h = glued.groupValues[1].toInt()
            }
        }
        if (h < 0 || h > 24 || m > 59) return null
        val am = words.any { it in setOf("am", "a.m", "सुबह", "morning") } || Regex("\\dam").containsMatchIn(t)
        val pm = words.any { it in setOf("pm", "p.m", "शाम", "रात", "दोपहर", "evening", "night", "afternoon") } || Regex("\\dpm").containsMatchIn(t)
        var hour = h % 24
        when {
            pm && hour < 12 -> hour += 12
            am && hour == 12 -> hour = 0
            !am && !pm && hour in 1..12 -> {
                // no hint: pick whichever of AM / PM comes next
                val now = Calendar.getInstance()
                val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                val c1 = (hour % 12) * 60 + m
                val c2 = ((hour % 12) + 12) * 60 + m
                val d1 = (c1 - nowMin + 1440) % 1440
                val d2 = (c2 - nowMin + 1440) % 1440
                hour = if (d1 <= d2) hour % 12 else (hour % 12) + 12
            }
        }
        return hour to m
    }

    private fun unitName(mult: Int) = when (mult) { 3600 -> "घंटे"; 60 -> "मिनट"; else -> "सेकंड" }

    internal fun timeReply(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        val h12 = if (h % 12 == 0) 12 else h % 12
        val part = when (h) { in 4..11 -> "सुबह"; in 12..15 -> "दोपहर"; in 16..19 -> "शाम"; else -> "रात" }
        return if (m == 0) "अभी $part के $h12 बजे हैं।" else "अभी $part के $h12 बजकर $m मिनट हुए हैं।"
    }

    private fun isOk(result: String): Boolean {
        val bad = listOf("Couldn't", "No ", "The ", "Invalid", "That ", "Nothing", "Error", "Unknown", "Ask ")
        return bad.none { result.startsWith(it) }
    }

    private fun fail(result: String): String = when {
        result.contains("Accessibility") ->
            "पहले Accessibility settings में Zyro को ON कर दो, फिर मैं फोन चला पाऊँगा।"
        result.startsWith("No installed app") -> "ये app मुझे फोन में नहीं मिला।"
        result.startsWith("No flashlight") -> "इस फोन में टॉर्च नहीं मिली।"
        else -> "ये काम नहीं हो पाया यार।"
    }

    private fun doIt(result: String, okText: String): String = if (isOk(result)) okText else fail(result)

    private fun tvCommand(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        fun word(vararg w: String) = w.any { x -> words.contains(strip(x)) }
        if (any(*SEARCH_WORDS) && !any("volume", "वॉल्यूम", "channel", "चैनल", "pause", "पॉज")) {
            val extra = arrayOf(*YT_WORDS, "netflix", "नेटफ्लिक्स", "tv", "टीवी", "टी वी", "television", "टेलीविजन")
            val q = extractQuery(t, extra, true, false)
            if (q.isNotBlank()) {
                val r = Tv.search(q)
                return if (r.startsWith("Sent") || r.startsWith("Opened")) "ठीक है, टीवी पर $q ढूँढ रहा हूँ।" else r
            }
        }
        val times = words.firstNotNullOfOrNull { numberOf(it) }?.coerceIn(1, 20) ?: 1
        val up = any("बढ़ा", "बढा", "badha", "increase", "ज्यादा", "zyada", "up", "ऊपर", "plus", "next", "अगला", "agla")
        val down = any("घटा", "ghata", "कम", "kam", "decrease", "down", "नीचे", "minus", "previous", "पिछला", "pichla", "low")
        val cmd: String? = when {
            any("volume", "वॉल्यूम", "आवाज", "awaz", "awaaz") -> if (down && !up) "volume_down" else "volume_up"
            any("mute", "म्यूट", "unmute", "चुप") -> "mute"
            any("channel", "चैनल") -> if (down && !up) "channel_down" else "channel_up"
            any("youtube", "यूट्यूब", "यू ट्यूब") -> "youtube"
            any("netflix", "नेटफ्लिक्स") -> "netflix"
            any("prime", "प्राइम") -> "prime"
            (any("बंद", "band", "ऑफ", "बन्द", "चालू", "ऑन", "power", "पावर", "शुरू") || word("on", "off", "start")) -> "power"
            any("home", "होम") -> "home"
            any("back", "बैक", "पीछे", "वापस") -> "back"
            any("settings", "सेटिंग") -> "settings"
            any("menu", "मेन्यू", "मेनू") -> "menu"
            any("pause", "play", "पॉज", "प्ले", "रोको", "चलाओ") -> "play_pause"
            (word("ok", "okay", "select", "enter") || any("ओके", "सेलेक्ट", "एंटर", "ठीक")) -> "ok"
            any("left", "लेफ्ट", "बाएं", "बायें", "बाया") -> "left"
            any("right", "राइट", "दाएं", "दायें", "दाया") -> "right"
            word("up", "ऊपर", "अप") -> "up"
            word("down", "नीचे", "डाउन") -> "down"
            else -> null
        }
        if (cmd == null) return null
        val r = Tv.run(cmd, times)
        return if (r.startsWith("Sent") || r.startsWith("Opened")) "ठीक है।" else r
    }
}
