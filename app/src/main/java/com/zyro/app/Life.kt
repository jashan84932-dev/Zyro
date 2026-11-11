package com.zyro.app

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** Everyday life helpers: habit streaks, daily rating, stopwatch, decisions, travel and history questions. */
object Life {
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun s(x: String) = Local.strip(x)

    private fun dayString(offset: Int = 0): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_MONTH, offset)
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    // ------------------------------------------------------------------ habit streaks

    private fun streaks(): JSONObject = try { JSONObject(Prefs.streaks) } catch (e: Exception) { JSONObject() }

    private val H_NOISE = setOf(
        "habit", "आदत", "jodo", "जोड़ो", "add", "banao", "बनाओ", "shuru", "शुरू", "karo", "करो", "ek", "एक", "nayi", "नई", "naya", "नया", "mein", "में", "ki", "की", "ka", "का",
        "hey", "micro", "माइक्रो", "mujhe", "मुझे", "hatao", "हटाओ", "delete", "ko", "को", "track", "ट्रैक"
    ).map { s(it) }.toSet()

    private fun habits(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        val d = streaks()
        val today = dayString()
        if (any("habit", "आदत") && any("jodo", "जोड़ो", "add", "banao", "बनाओ", "shuru", "शुरू", "track", "ट्रैक")) {
            val name = words.filter { it !in H_NOISE }.joinToString(" ").trim()
            if (name.isBlank()) return "किस आदत को ट्रैक करना है? जैसे: आदत जोड़ो जिम।"
            if (!d.has(name)) d.put(name, JSONObject().put("d", "").put("c", 0).put("b", 0))
            Prefs.streaks = d.toString()
            return "आदत '$name' जोड़ ली। जब पूरी करो तो बोलना, $name कर लिया। रोज़ रात 9 बजे याद दिलाऊँगा अगर बाकी रहा।"
        }
        if (any("habit", "आदत") && any("hatao", "हटाओ", "delete", "मिटाओ")) {
            val name = words.filter { it !in H_NOISE }.joinToString(" ").trim()
            val key = d.keys().asSequence().firstOrNull { it == name || (name.isNotBlank() && it.contains(name)) }
            if (key != null) {
                d.remove(key)
                Prefs.streaks = d.toString()
                return "आदत '$key' हटा दी।"
            }
            return "ऐसी कोई आदत नहीं मिली।"
        }
        if (any("streak", "स्ट्रीक") || (any("habit", "आदत") && any("batao", "बताओ", "list", "लिस्ट", "kaisi", "कैसी"))) {
            if (d.length() == 0) return "अभी कोई आदत ट्रैक नहीं हो रही। बोलो, आदत जोड़ो जिम।"
            val yest = dayString(-1)
            val parts = ArrayList<String>()
            val it = d.keys()
            while (it.hasNext()) {
                val k = it.next()
                val o = d.getJSONObject(k)
                val last = o.optString("d")
                val live = if (last == today || last == yest) o.optInt("c") else 0
                parts.add("$k: $live दिन की लकीर, सबसे लंबी ${o.optInt("b")}" + if (last == today) ", आज हो गया" else "")
            }
            return parts.joinToString("। ")
        }
        if (d.length() > 0 && any("kar liya", "कर लिया", "ho gaya", "हो गया", "kiya", "किया", "complete", "पूरा", "poora", "done", "ho gayi", "हो गई")) {
            val key = d.keys().asSequence().firstOrNull { t.contains(it) } ?: return null
            val o = d.getJSONObject(key)
            if (o.optString("d") == today) return "$key आज पहले ही दर्ज है। शाबाश!"
            val c = if (o.optString("d") == dayString(-1)) o.optInt("c") + 1 else 1
            o.put("d", today).put("c", c).put("b", maxOf(o.optInt("b"), c))
            Prefs.streaks = d.toString()
            return when {
                c == 1 -> "$key दर्ज किया। नई शुरुआत, कल फिर करना!"
                c % 7 == 0 -> "वाह! $key की लगातार $c दिन की लकीर! पूरे ${c / 7} हफ़्ते हो गए।"
                else -> "$key पूरा। लगातार $c दिन हो गए, लकीर मत तोड़ना!"
            }
        }
        return null
    }

    // ------------------------------------------------------------------ rate my day

    private fun rating(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        if (any("mera hafta kaisa", "मेरा हफ्ता कैसा", "hafta kaisa raha", "हफ्ता कैसा रहा", "mere din kaise", "मेरे दिन कैसे")) {
            val d = try { JSONObject(Prefs.ratings) } catch (e: Exception) { JSONObject() }
            val vals = (0 until 7).mapNotNull { i -> d.optInt(dayString(-i), 0).takeIf { it > 0 } }
            if (vals.isEmpty()) return "अभी इस हफ़्ते की कोई रेटिंग नहीं है। रोज़ बोलो, आज का दिन 8 में से रेट करो।"
            val avg = vals.sum() / vals.size.toFloat()
            return "इस हफ़्ते ${vals.size} दिन की रेटिंग है, औसत ${"%.1f".format(Locale.US, avg)} में से 10। सबसे अच्छा दिन ${vals.maxOrNull()}, सबसे कमज़ोर ${vals.minOrNull()}।" +
                if (avg < 5) " थोड़ा भारी हफ़्ता रहा, खुद का ख़याल रखना।" else " अच्छा चल रहा है!"
        }
        if (any("aaj ka din", "आज का दिन") && any("rate", "रेट", "rating", "रेटिंग", "out of", "में से", "mein se", "number", "नंबर")) {
            val n = words.firstNotNullOfOrNull { w -> (w.toIntOrNull() ?: Local.numberOf(w))?.takeIf { it in 1..10 } } ?: return "1 से 10 में कितना? जैसे: आज का दिन 8 में से रेट करो।"
            val d = try { JSONObject(Prefs.ratings) } catch (e: Exception) { JSONObject() }
            d.put(dayString(), n)
            val keys = ArrayList<String>()
            val it = d.keys()
            while (it.hasNext()) keys.add(it.next())
            keys.sort()
            while (keys.size > 60) d.remove(keys.removeAt(0))
            Prefs.ratings = d.toString()
            return when {
                n >= 8 -> "वाह, $n में से $n! बढ़िया दिन रहा।"
                n >= 5 -> "$n दर्ज किया। ठीक-ठाक दिन, कल और बेहतर होगा।"
                else -> "$n दर्ज किया। मुश्किल दिन था ना? कल नया दिन है।"
            }
        }
        return null
    }

    // ------------------------------------------------------------------ stopwatch

    @Volatile private var swStart = 0L

    private fun stopwatch(t: String): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        if (!any("stopwatch", "स्टॉपवॉच", "स्टॉप वॉच", "stop watch")) return null
        fun fmt(ms: Long): String {
            val sec = ms / 1000
            return if (sec >= 3600) "${sec / 3600} घंटा ${sec % 3600 / 60} मिनट ${sec % 60} सेकंड" else "${sec / 60} मिनट ${sec % 60} सेकंड"
        }
        return when {
            any("shuru", "शुरू", "start", "chalu", "चालू", "reset", "रीसेट") -> {
                swStart = System.currentTimeMillis()
                "स्टॉपवॉच शुरू।"
            }
            swStart == 0L -> "स्टॉपवॉच अभी चल नहीं रही। बोलो, स्टॉपवॉच शुरू करो।"
            any("ruko", "रुको", "stop", "band", "बंद", "khatam", "खत्म") -> {
                val e = System.currentTimeMillis() - swStart
                swStart = 0L
                "रुक गई। कुल समय ${fmt(e)}।"
            }
            else -> "अभी तक ${fmt(System.currentTimeMillis() - swStart)} हुए हैं।"
        }
    }

    // ------------------------------------------------------------------ decisions

    private fun decide(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        if (!any("decide", "डिसाइड", "chuno", "चुनो", "faisla", "फैसला", "konsa", "कौनसा", "कौन सा", "select", "pick")) return null
        val ya = words.indexOfFirst { it == "ya" || it == "या" }
        if (ya <= 0 || ya >= words.size - 1) return null
        val noise = setOf("decide", "डिसाइड", "karo", "करो", "chuno", "चुनो", "faisla", "फैसला", "konsa", "कौनसा", "कौन", "सा", "select", "pick", "mere", "मेरे", "liye", "लिए", "mein", "में", "se", "से", "hey", "micro", "माइक्रो", "bolo", "बोलो", "kya", "क्या", "lena", "लेना", "chahiye", "चाहिए", "hai", "है", "mujhe", "मुझे").map { s(it) }.toSet()
        val opts = ArrayList<String>()
        var cur = ArrayList<String>()
        for (w in words) {
            if (w == "ya" || w == "या") {
                if (cur.isNotEmpty()) opts.add(cur.joinToString(" "))
                cur = ArrayList()
            } else if (w !in noise) {
                cur.add(w)
            }
        }
        if (cur.isNotEmpty()) opts.add(cur.joinToString(" "))
        if (opts.size < 2) return null
        return "मैंने सिक्का उछाला, फ़ैसला: ${opts.random()}। अगर दिल कुछ और कह रहा है तो वही चुनो!"
    }

    // ------------------------------------------------------------------ nag + tick

    fun tick() {
        val cal = Calendar.getInstance()
        val mod = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (mod !in 1260..1275) return
        val today = dayString()
        if (Prefs.streakNag == today) return
        val d = streaks()
        if (d.length() == 0) return
        val pending = ArrayList<String>()
        val it = d.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (d.getJSONObject(k).optString("d") != today) pending.add(k)
        }
        Prefs.streakNag = today
        val c = appCtx ?: return
        if (pending.isNotEmpty()) Assistant.get(c).announce("आज ${pending.joinToString(" और ")} अभी बाकी है। लकीर मत तोड़ना!")
    }

    // ------------------------------------------------------------------ voice commands

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        stopwatch(t)?.let { return it }
        decide(t, words)?.let { return it }
        rating(t, words)?.let { return it }
        habits(t, words)?.let { return it }
        if (any("kitni door", "कितनी दूर", "kitna time lagega", "कितना समय लगेगा", "kitne km", "कितने किलोमीटर", "kitne kilometer", "kitni der", "कितनी देर में पहुँच")) {
            return Smart.live(raw)
        }
        if (any("aaj ke din", "आज के दिन") && any("itihas", "इतिहास", "history", "hua tha", "हुआ था", "kya hua")) return Smart.live(raw)
        return null
    }
}
