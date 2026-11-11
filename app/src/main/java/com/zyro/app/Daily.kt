package com.zyro.app

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Screen wake / unlock, notes, lists, expenses and weather. Everything is stored only on the phone. */
object Daily {

    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun strip(s: String) = Local.strip(s)
    private fun sir() = if (Prefs.jarvisMode) "सर, " else ""

    // ------------------------------------------------------------------ screen

    fun screenIsOn(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    /** Turns the screen on if it is off. */
    fun wakeScreen(ctx: Context) {
        try {
            if (screenIsOn(ctx)) return
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "micro:wakeScreen"
            )
            wl.acquire(6000)
        } catch (e: Exception) {
            // not possible on this phone
        }
    }

    private fun openPhone(hands: Hands, ctx: Context): String {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val wasOff = !screenIsOn(ctx)
        wakeScreen(ctx)
        if (!km.isKeyguardLocked) return if (wasOff) "स्क्रीन चालू कर दी।" else "फ़ोन पहले से खुला है।"
        if (km.isKeyguardSecure) {
            return "स्क्रीन चालू कर दी। फ़ोन पिन, पैटर्न या फ़िंगरप्रिंट से लॉक है, सुरक्षा के लिए उसे आपको खुद खोलना होगा।"
        }
        // only a "swipe up" lock screen: swipe it away
        try { Thread.sleep(900) } catch (e: InterruptedException) { /* ignore */ }
        val dm = ctx.resources.displayMetrics
        val r = hands.swipe(dm.widthPixels / 2, (dm.heightPixels * 0.85).toInt(), dm.widthPixels / 2, (dm.heightPixels * 0.2).toInt(), 350)
        return if (r.contains("Accessibility", ignoreCase = true)) "स्क्रीन चालू है, पर खोलने के लिए Accessibility में Zyro ON होना चाहिए।"
        else "${sir()}फ़ोन खोल दिया।"
    }

    // ------------------------------------------------------------------ storage

    private fun arr(s: String): JSONArray = try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    private fun obj(s: String): JSONObject = try { JSONObject(s) } catch (e: Exception) { JSONObject() }

    // ------------------------------------------------------------------ main entry

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }

        // ----- open / unlock the phone -----
        if (any(
                "mobile kholo", "मोबाइल खोलो", "mobile khol", "मोबाइल खोल", "phone kholo", "फोन खोलो", "फ़ोन खोलो", "phone khol", "फोन खोल",
                "unlock", "अनलॉक", "screen on", "स्क्रीन ऑन", "स्क्रीन चालू", "screen chalu", "screen jalao", "स्क्रीन जलाओ",
                "phone on karo", "फोन ऑन करो", "mobile on karo", "मोबाइल ऑन करो", "phone jagao", "फोन जगाओ", "wake up phone", "open my mobile", "open my phone"
            ) && words.size <= 6
        ) {
            return openPhone(hands, ctx)
        }

        // ----- weather -----
        if (any("mausam", "मौसम", "weather")) return weather(t, words)

        // ----- expenses -----
        val money = any("kharch", "खर्च", "kharcha", "खर्चा", "spent", "spend")
        if (money) {
            expenses(t, words)?.let { return it }
        }

        // ----- lists -----
        if (words.any { it == "list" || it == "lists" || it == "लिस्ट" || it == "shoppinglist" }) {
            lists(t, words)?.let { return it }
        }

        // ----- notes -----
        notes(t, words)?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ weather

    private val WEATHER_NOISE = setOf(
        "aaj", "आज", "kal", "कल", "ka", "का", "ki", "की", "ke", "के", "kaisa", "कैसा", "kaisi", "कैसी", "hai", "है", "batao", "बताओ", "bata", "बता", "kya", "क्या",
        "me", "में", "mein", "mausam", "मौसम", "weather", "today", "the", "in", "of", "hey", "micro", "माइक्रो", "abhi", "अभी", "kaise", "कैसे", "ho", "हो", "raha", "रहा", "rahi", "रही",
        "mujhe", "मुझे", "please", "plz", "ji", "जी", "is", "इस", "samay", "समय", "shahar", "शहर", "city"
    ).map { strip(it) }.toSet()

    private fun weather(t: String, words: List<String>): String {
        val city = words.filter { it !in WEATHER_NOISE }.joinToString(" ").trim()
        return try {
            val url = "https://wttr.in/" + Uri.encode(city) + "?format=" + Uri.encode("%l: %C, %t, नमी %h") + "&lang=hi"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 7000
            c.setRequestProperty("User-Agent", "curl/8")
            val s = c.inputStream.bufferedReader().use { it.readText() }.trim()
            c.disconnect()
            if (s.isBlank() || s.length > 140 || s.contains("Unknown", ignoreCase = true)) "मौसम की जानकारी नहीं मिली। शहर का नाम साफ़ बोलो।"
            else "आज का मौसम: $s।"
        } catch (e: Exception) {
            "मौसम की जानकारी नहीं ला पाया। इंटरनेट देख लो।"
        }
    }

    // ------------------------------------------------------------------ notes

    private fun notes(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        val add = Regex("(?:^|\\s)(?:note|नोट)\\s*(?:karo|करो|kar|कर|likho|लिखो|kar lo|कर लो)\\s*(?:ki|कि|that)?\\s*(.*)").find(t)
            ?: Regex("(?:^|\\s)(?:yaad|याद)\\s*(?:rakhna|रखना|rakho|रखो)\\s*(?:ki|कि|that)?\\s*(.*)").find(t)
        if (add != null) {
            val body = add.groupValues[1].trim()
            if (body.isBlank()) return "क्या नोट करूँ? जैसे: नोट करो कि कल बैंक जाना है।"
            val a = arr(Prefs.notes)
            a.put(JSONObject().put("t", body).put("ts", System.currentTimeMillis()))
            Prefs.notes = a.toString()
            return "नोट कर लिया: $body।"
        }
        if (any("notes", "नोट्स", "नोट्स", "mere note", "मेरे नोट") ) {
            if (any("hatao", "हटाओ", "delete", "clear", "saaf", "साफ", "मिटाओ", "mitao")) {
                Prefs.notes = "[]"
                return "सारे नोट्स हटा दिए।"
            }
            if (any("padho", "पढ़ो", "पढो", "padh", "पढ़", "sunao", "सुनाओ", "batao", "बताओ", "dikhao", "दिखाओ", "kya", "क्या", "read")) {
                val a = arr(Prefs.notes)
                if (a.length() == 0) return "अभी कोई नोट नहीं है।"
                val from = maxOf(0, a.length() - 5)
                val parts = ArrayList<String>()
                for (i in from until a.length()) parts.add("${i - from + 1}: ${a.getJSONObject(i).optString("t")}")
                return "आख़िरी ${parts.size} नोट: " + parts.joinToString("। ") + "।"
            }
        }
        return null
    }

    // ------------------------------------------------------------------ lists

    private val LIST_NOISE = setOf(
        "meri", "मेरी", "mera", "मेरा", "ki", "की", "ka", "का", "ke", "के", "koi", "कोई", "wo", "वो", "my", "the", "aur", "और", "ye", "ये", "is", "इस", "us", "उस",
        "hey", "micro", "माइक्रो", "padho", "पढ़ो", "सुनाओ", "sunao", "batao", "बताओ", "dikhao", "दिखाओ", "kya", "क्या", "hai", "है", "mein", "में", "me", "se", "से", "ko", "को", "me", "ek", "एक"
    ).map { strip(it) }.toSet()

    private fun listName(words: List<String>): String {
        val i = words.indexOfFirst { it == "list" || it == strip("लिस्ट") }
        if (i > 0) {
            var j = i - 1
            while (j >= 0 && words[j] in LIST_NOISE) j--
            if (j >= 0) return words[j]
        }
        return "शॉपिंग"
    }

    private fun lists(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        val name = listName(words).let { if (it == "shopping" || it == strip("शॉपिंग") || it == strip("सामान")) "शॉपिंग" else it }
        val all = obj(Prefs.lists)
        val items = all.optJSONArray(name) ?: JSONArray()

        val ADD = strip("(?:jodo|जोड़ो|जोड़|jod do|जोड़ दो|add|daalo|डालो|dalo|डाल दो|likho|लिखो|rakho|रखो)")
        val addRe = Regex("(?:me|mein|में|मे)\\s+(.+?)\\s+$ADD").find(t)
            ?: Regex("(.+?)\\s+(?:ko\\s+)?(?:\\S+\\s+)?(?:list|लिस्ट)\\s*(?:me|mein|में|मे)\\s*$ADD").find(t)
        if (addRe != null) {
            val item = addRe.groupValues[1].replace(Regex("^(?:aur|और)\\s+"), "").trim()
            if (item.isBlank()) return null
            val parts = item.split(Regex("\\s+(?:aur|और)\\s+")).map { it.trim() }.filter { it.isNotBlank() }
            for (p in parts) items.put(p)
            all.put(name, items)
            Prefs.lists = all.toString()
            return "$name लिस्ट में जोड़ दिया: ${parts.joinToString(", ")}।"
        }
        val rem = Regex("(?:list|लिस्ट)\\s*(?:se|से)\\s+(.+?)\\s+(?:hatao|हटाओ|delete|nikalo|निकालो|kato|काटो|hata do|हटा दो)").find(t)
        if (rem != null) {
            val item = rem.groupValues[1].trim()
            var removed = false
            val keep = JSONArray()
            for (i in 0 until items.length()) {
                val s = items.optString(i)
                if (!removed && strip(s).contains(item)) removed = true else keep.put(s)
            }
            all.put(name, keep)
            Prefs.lists = all.toString()
            return if (removed) "$item $name लिस्ट से हटा दिया।" else "$item लिस्ट में नहीं मिला।"
        }
        if (any("saaf", "साफ", "khali", "खाली", "clear", "delete", "मिटाओ", "mitao", "हटाओ list", "सारी हटाओ")) {
            all.put(name, JSONArray())
            Prefs.lists = all.toString()
            return "$name लिस्ट खाली कर दी।"
        }
        if (any("padho", "पढ़ो", "पढो", "padh", "पढ़", "sunao", "सुनाओ", "batao", "बताओ", "dikhao", "दिखाओ", "kya kya", "क्या क्या", "read", "kitne", "कितने")) {
            if (items.length() == 0) return "$name लिस्ट अभी खाली है।"
            val parts = ArrayList<String>()
            for (i in 0 until items.length()) parts.add(items.optString(i))
            return "$name लिस्ट में ${items.length()} चीज़ें हैं: ${parts.joinToString(", ")}।"
        }
        return null
    }

    // ------------------------------------------------------------------ expenses

    private val EXP_NOISE = setOf(
        "aaj", "आज", "kal", "कल", "kharch", "खर्च", "kharcha", "खर्चा", "kharche", "hue", "हुए", "hua", "हुआ", "kiya", "किया", "rupaye", "रुपये", "रुपए", "rupees", "rupiye", "rs", "₹",
        "pe", "पे", "par", "पर", "ka", "का", "ke", "के", "ki", "की", "liye", "लिए", "mein", "में", "me", "mujhe", "मुझे", "maine", "मैंने", "spent", "spend", "on", "for", "the", "ek", "एक",
        "hey", "micro", "माइक्रो", "likh", "लिख", "lo", "लो", "do", "दो", "add", "karo", "करो", "kar", "कर", "total", "kitna", "कितना", "gaya", "गया", "hai", "है", "tha", "था", "diye", "दिए"
    ).map { strip(it) }.toSet()

    private fun amountOf(words: List<String>): Int? =
        words.firstNotNullOfOrNull { it.toIntOrNull() } ?: words.firstNotNullOfOrNull { Local.numberOf(it) }

    private fun expenses(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        val asking = any("kitna", "कितना", "total", "hisab", "हिसाब", "kitne", "कितने", "batao", "बताओ", "bata", "बता")
        val a = arr(Prefs.expenses)

        if (any("saaf", "साफ", "delete", "मिटाओ", "mitao", "hatao", "हटाओ", "clear") && any("hisab", "हिसाब", "kharch", "खर्च")) {
            Prefs.expenses = "[]"
            return "खर्चे का सारा हिसाब मिटा दिया।"
        }
        if (!asking) {
            val amount = amountOf(words) ?: return null
            if (amount <= 0) return null
            val numTokens = words.filter { it.toIntOrNull() != null }.toSet()
            val cat = words.filter { it !in EXP_NOISE && it !in numTokens && Local.numberOf(it) == null }.take(2).joinToString(" ").ifBlank { "अन्य" }
            a.put(JSONObject().put("a", amount).put("c", cat).put("ts", System.currentTimeMillis()))
            Prefs.expenses = a.toString()
            return "$cat पर $amount रुपये का खर्च लिख लिया।"
        }
        // report
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        val dayStart = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val monthStart = (cal.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val (from, to, label) = when {
            any("kal", "कल") -> Triple(dayStart - 86_400_000L, dayStart, "कल")
            any("hafte", "हफ्ते", "week", "सप्ताह") -> Triple(now - 7 * 86_400_000L, now + 1, "इस हफ्ते")
            any("mahine", "महीने", "month") -> Triple(monthStart, now + 1, "इस महीने")
            any("aaj", "आज") -> Triple(dayStart, now + 1, "आज")
            else -> Triple(monthStart, now + 1, "इस महीने")
        }
        val catFilter = words.firstOrNull { it !in EXP_NOISE && it !in WEATHER_NOISE && it.toIntOrNull() == null && !listOf("mahine", "महीने", "hafte", "हफ्ते", "week", "month", "hisab", "हिसाब", "batao", "बताओ", "bata", "बता", "kitne", "कितने", "is", "इस").map { strip(it) }.contains(it) }
        var total = 0
        val byCat = HashMap<String, Int>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val ts = o.optLong("ts")
            if (ts < from || ts >= to) continue
            val c = o.optString("c")
            if (catFilter != null && !strip(c).contains(catFilter)) continue
            val amt = o.optInt("a")
            total += amt
            byCat[c] = (byCat[c] ?: 0) + amt
        }
        if (total == 0) return "$label ${if (catFilter != null) "$catFilter पर " else ""}कोई खर्च दर्ज नहीं है।"
        val top = byCat.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "${it.key} ${it.value}" }
        return "$label ${if (catFilter != null) "$catFilter पर " else ""}कुल $total रुपये खर्च हुए। सबसे ज़्यादा: $top।"
    }
}
