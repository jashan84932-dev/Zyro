package com.zyro.app

import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Smarter helpers: live internet answers, practice modes (quiz, English tutor, interview, stories),
 * a memory for "remember this", daily reminders in plain words, clipboard help and day planning.
 */
object Smart {
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun s(x: String) = Local.strip(x)

    // ------------------------------------------------------------------ practice modes

    @Volatile var persona: String? = null
    @Volatile var personaName: String = ""

    /** Language for the built-in voice while a persona is on (English tutor speaks English). */
    fun speakLang(): String? = if (personaName == "english") "en-IN" else null

    fun wrap(text: String): String = "[निर्देश, इसे मानते रहो: $persona]\n$text"

    fun clear() {
        persona = null
        personaName = ""
    }

    fun modeCommand(text: String): Boolean {
        val t = s(text)
        if (t.length > 40) return false
        val m = t.contains(s("mode")) || t.contains(s("मोड")) || t.contains(s("practice")) || t.contains(s("प्रैक्टिस")) || t.contains(s("quiz")) || t.contains(s("क्विज़"))
        val stop = listOf("band", "बंद", "normal", "नॉर्मल", "khatam", "खत्म", "stop").any { t.contains(s(it)) }
        return m && stop
    }

    private fun startCustom(who: String): String {
        val saved = try { JSONObject(Prefs.personas) } catch (e: Exception) { JSONObject() }
        val key = who.lowercase()
        var p = saved.optString(key, "")
        if (p.isBlank()) {
            p = Brain.quickText(
                "Write instructions (6 to 8 sentences, in English) telling a voice assistant how to talk in the style of \"$who\": " +
                    "their attitude, humour, vocabulary, typical way of addressing the user, and how short the replies should be. " +
                    "Spoken replies must be 1 to 3 short sentences, mix Hindi and English (Hinglish) the way an Indian friend would, and never be hurtful. " +
                    "Capture the style and attitude only; do not quote copyrighted lines or dialogue. Output only the instructions.",
                450
            ) ?: return "पर्सनैलिटी बनाने के लिए इंटरनेट और Gemini key चाहिए।"
            saved.put(key, p.trim())
            // keep at most 15
            while (saved.length() > 15) saved.remove(saved.keys().next())
            Prefs.personas = saved.toString()
        }
        return startMode("custom:$who", "इस शैली में बात करो (यह सिर्फ़ बोलने का अंदाज़ है, काम और सच्चाई में कोई बदलाव नहीं): $p", "ठीक है, अब मैं $who के अंदाज़ में बात करूँगा।")
    }

    private fun startMode(name: String, p: String, intro: String): String {
        persona = p
        personaName = name
        return intro + " बंद करने के लिए बोलो, मोड बंद।"
    }

    private fun topicAfter(t: String, vararg markers: String): String {
        val words = t.split(Regex("\\s+"))
        val noise = setOf("quiz", "क्विज़", "क्विज", "lo", "लो", "shuru", "शुरू", "karo", "करो", "kar", "कर", "do", "दो", "mujhse", "मुझसे", "hey", "micro", "माइक्रो",
            "interview", "इंटरव्यू", "practice", "प्रैक्टिस", "ka", "का", "ke", "के", "liye", "लिए", "par", "पर", "pe", "पे", "ki", "की", "mein", "में", "kahani", "कहानी", "sunao", "सुनाओ", "ek", "एक", "mode", "मोड", "chalu", "चालू")
            .map { s(it) }.toSet()
        return words.filter { it !in noise && it.isNotBlank() }.joinToString(" ").trim()
    }

    // ------------------------------------------------------------------ memory ("remember this")

    private fun facts(): JSONArray = try { JSONArray(Prefs.userFacts) } catch (e: Exception) { JSONArray() }

    private fun remember(fact: String): String {
        val a = facts()
        a.put(JSONObject().put("t", fact).put("ts", System.currentTimeMillis()))
        val keep = JSONArray()
        for (i in maxOf(0, a.length() - 100) until a.length()) keep.put(a.get(i))
        Prefs.userFacts = keep.toString()
        return "ठीक है, याद रख लिया: $fact"
    }

    private fun recall(question: String): String {
        val a = facts()
        if (a.length() == 0) return "अभी तक तुमने मुझे कुछ याद रखने को नहीं कहा। बोलो, याद रखो कि चाबी अलमारी में है।"
        val sb = StringBuilder()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            sb.append("- (").append(SimpleDateFormat("d MMM", Locale("hi", "IN")).format(Date(o.optLong("ts")))).append(") ").append(o.optString("t")).append('\n')
        }
        val ai = Brain.quickText(
            "ये वो बातें हैं जो मैंने तुम्हें याद रखने को कहा था:\n$sb\nमेरा सवाल: $question\n" +
                "सिर्फ़ इन बातों के आधार पर हिंदी में एक छोटे वाक्य में जवाब दो। अगर जवाब इनमें नहीं है तो साफ़ कहो कि मुझे नहीं पता।", 300
        )?.trim()
        if (ai != null) return ai
        val words = s(question).split(Regex("\\s+")).filter { it.length > 2 }
        val hit = (0 until a.length()).map { a.getJSONObject(it).optString("t") }.firstOrNull { f -> words.any { s(f).contains(it) } }
        return hit ?: "मुझे इसका जवाब याद नहीं आ रहा।"
    }

    // ------------------------------------------------------------------ live internet answer

    internal fun live(raw: String): String? {
        val today = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("en", "IN")).format(Date())
        val ans = Brain.quickText(
            "आज की तारीख़: $today. सवाल: $raw\nइंटरनेट पर ताज़ा जानकारी देखकर हिंदी में 2 से 3 छोटे वाक्यों में जवाब दो। " +
                "सादा टेक्स्ट, कोई markdown, लिंक या सितारे नहीं। अगर पक्का न पता चले तो साफ़ कहो।", 600, true
        )?.trim() ?: return null
        return ans.replace(Regex("[*#]+"), "").replace(Regex("\\s+"), " ").take(700)
    }

    // ------------------------------------------------------------------ clipboard and planning

    private fun clipboard(ctx: Context, t: String): String {
        val text = try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) return "क्लिपबोर्ड खाली है। पहले कुछ कॉपी करो।"
        val low = t
        val task = when {
            listOf("translate", "ट्रांसलेट", "अनुवाद", "english", "अंग्रेजी", "angrezi").any { low.contains(s(it)) } ->
                "इस टेक्स्ट का अनुवाद करो: अगर यह हिंदी है तो English में, वरना हिंदी में। सिर्फ़ अनुवाद दो।"
            listOf("summary", "सारांश", "short", "chhota", "छोटा", "saransh").any { low.contains(s(it)) } ->
                "इस टेक्स्ट का हिंदी में 2 से 3 वाक्यों में सारांश दो।"
            listOf("padho", "पढ़ो", "sunao", "सुनाओ").any { low.contains(s(it)) } && !low.contains(s("samjhao")) && !low.contains(s("समझाओ")) ->
                return "क्लिपबोर्ड में है: " + text.take(600)
            else -> "इस टेक्स्ट का मतलब हिंदी में आसान शब्दों में 2 से 3 वाक्यों में समझाओ।"
        }
        return Brain.quickText("$task\n\nटेक्स्ट:\n${text.take(3000)}", 700)?.trim()?.replace(Regex("[*#]+"), "")
            ?: "समझाने के लिए Gemini key और इंटरनेट चाहिए।"
    }

    private fun plan(t: String): String {
        val tomorrow = t.contains(s("kal")) || t.contains(s("कल"))
        val day = if (tomorrow) "कल" else "आज"
        val ctx = StringBuilder()
        ctx.append("नोट्स: ").append(Prefs.notes.take(1200)).append('\n')
        ctx.append("लिस्ट: ").append(Prefs.lists.take(1200)).append('\n')
        ctx.append("मेरे ऑटोमेशन/रिमाइंडर: ").append(Prefs.rules.take(800)).append('\n')
        val now = SimpleDateFormat("HH:mm, EEEE", Locale("en", "IN")).format(Date())
        return Brain.quickText(
            "अभी का समय: $now. नीचे मेरी नोट्स और लिस्ट हैं (JSON जैसे दिख सकते हैं, उनसे असली काम निकालो):\n$ctx\n" +
                "इनके आधार पर $day का एक सीधा, छोटा प्लान हिंदी में बनाओ: 4 से 6 बातें, हर एक समय के साथ, बोलकर सुनाने लायक। सादा टेक्स्ट। " +
                "अगर कुछ भी काम नहीं मिला तो कहो कि पहले नोट या लिस्ट में काम जोड़ो।", 700
        )?.trim()?.replace(Regex("[*#]+"), "") ?: "प्लान बनाने के लिए Gemini key और इंटरनेट चाहिए।"
    }

    // ------------------------------------------------------------------ plain-words daily reminder

    private val REM_NOISE = setOf(
        "roz", "रोज़", "रोज", "daily", "har", "हर", "din", "दिन", "subah", "सुबह", "shaam", "शाम", "raat", "रात", "dopahar", "दोपहर", "baje", "बजे", "ki", "की", "ka", "का", "ke", "के",
        "mujhe", "मुझे", "ko", "को", "hey", "micro", "माइक्रो", "me", "मे", "mein", "में", "sab", "pe", "पे", "par", "पर"
    ).map { s(it) }.toSet()

    private fun dailyReminder(t: String, words: List<String>): String? {
        val yi = words.indexOfFirst { it.startsWith(s("yaad")) || it.startsWith(s("याद")) }
        if (yi < 0) return null
        val bi = words.indexOfFirst { it == s("baje") || it == s("बजे") }
        if (bi <= 0) return null
        var h = words[bi - 1].toIntOrNull() ?: Local.numberOf(words[bi - 1]) ?: return null
        fun has(vararg w: String) = w.any { t.contains(s(it)) }
        if (has("shaam", "शाम", "raat", "रात", "dopahar", "दोपहर") && h < 12) h += 12
        if (h !in 0..23) return null
        val thing = words.subList(0, yi).filter { it !in REM_NOISE && it.toIntOrNull() == null && Local.numberOf(it) == null }.joinToString(" ").trim().ifBlank { "ज़रूरी काम" }
        val a = try { JSONArray(Prefs.rules) } catch (e: Exception) { JSONArray() }
        a.put(JSONObject().put("id", System.nanoTime()).put("k", "time").put("at", String.format(Locale.US, "%02d:00", h)).put("cmd", "बोलो $thing का समय हो गया").put("last", ""))
        Prefs.rules = a.toString()
        return "ठीक है, रोज़ ${String.format(Locale.US, "%02d:00", h)} बजे $thing याद दिलाऊँगा। Zyro चालू रखना।"
    }

    // ------------------------------------------------------------------ voice commands

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        fun word(vararg w: String) = w.any { x -> words.contains(s(x)) }

        // leave a practice mode
        if (persona != null && modeCommand(raw)) {
            clear()
            return "ठीक है, मोड बंद। अब नॉर्मल बात करते हैं।"
        }

        // custom personality: "tum Tony Stark ban jao", "Sherlock jaisa baat karo", "personality Yoda"
        if (persona != null && personaName.startsWith("custom:") &&
            any("normal ho jao", "नॉर्मल हो जाओ", "apni personality", "अपनी पर्सनैलिटी", "personality band", "पर्सनैलिटी बंद", "asli roop", "असली रूप")) {
            clear()
            return "ठीक है, अब मैं अपने असली रूप में हूँ।"
        }
        if (any("personality list", "पर्सनैलिटी लिस्ट", "kaun kaun si personality", "कौन कौन सी पर्सनैलिटी")) {
            val keys = try { JSONObject(Prefs.personas).keys().asSequence().toList() } catch (e: Exception) { emptyList<String>() }
            return if (keys.isEmpty()) "अभी कोई सेव्ड पर्सनैलिटी नहीं है। बोलो, तुम टोनी स्टार्क बन जाओ।" else "सेव्ड पर्सनैलिटी: " + keys.joinToString(", ")
        }
        val pm = Regex("(.+?)\\s+(?:ban jao|ban jaao|बन जाओ|बन जाइए|jaisa baat karo|jaise baat karo|जैसा बात करो|जैसे बात करो|jaisa bolo|jaise bolo|जैसे बोलो|जैसा बोलो|ki tarah baat karo|की तरह बात करो)")
            .find(raw.trim()) ?: Regex("(?:personality|पर्सनैलिटी|पर्सनालिटी)\\s+(.+)", RegexOption.IGNORE_CASE).find(raw.trim())
        if (pm != null) {
            var who = pm.groupValues[1]
            val junk = setOf("micro", "माइक्रो", "tum", "तुम", "aap", "आप", "ab", "अब", "ek", "एक", "mere", "मेरे", "liye", "लिए", "hey", "ok", "okay", "please", "ab", "se", "से", "ki", "की", "ka", "का", "jaisa", "jaise", "जैसा", "जैसे", "set", "karo", "करो", "badlo", "बदलो", "meri", "मेरी")
            who = who.split(Regex("\\s+")).filter { it.lowercase() !in junk }.joinToString(" ").trim(' ', ',', '.')
            if (who.length in 2..40) return startCustom(who)
        }

        // practice modes
        if (word("quiz", "क्विज़", "क्विज") && any("lo", "लो", "shuru", "शुरू", "karo", "करो", "khelo", "खेलो", "start", "mujhse", "मुझसे")) {
            val topic = topicAfter(t).ifBlank { "सामान्य ज्ञान" }
            return startMode(
                "quiz",
                "तुम क्विज़ मास्टर हो। विषय: $topic. एक बार में सिर्फ़ एक छोटा सवाल पूछो, हिंदी में, 4 विकल्प बोलकर। मेरे जवाब पर साफ़ बताओ सही या गलत, एक वाक्य में कारण दो, स्कोर गिनते रहो। " +
                    "5 सवाल के बाद कुल स्कोर बताओ और पूछो कि और खेलें? हर जवाब छोटा रखो।",
                "क्विज़ शुरू। विषय है $topic। तैयार हो? पहला सवाल सुनाने के लिए बोलो, हाँ।"
            )
        }
        if (any("english practice", "इंग्लिश प्रैक्टिस", "english bolna", "इंग्लिश बोलना", "english speaking", "इंग्लिश स्पीकिंग", "english sikhao", "इंग्लिश सिखाओ")) {
            return startMode(
                "english",
                "You are my friendly English speaking tutor. Reply ONLY in simple English, in 1 to 3 short sentences. " +
                    "If my English has a mistake, first give the corrected sentence politely, then ask me one easy follow-up question to keep the chat going.",
                "English practice mode. Hello! Let's practise. Tell me, how was your day?"
            )
        }
        if (word("interview", "इंटरव्यू") && any("practice", "प्रैक्टिस", "lo", "लो", "karao", "कराओ", "shuru", "शुरू", "taiyari", "तैयारी", "mock")) {
            val job = topicAfter(t).ifBlank { "सामान्य नौकरी" }
            return startMode(
                "interview",
                "तुम एक सख़्त पर दोस्ताना HR इंटरव्यूअर हो। पद: $job. एक बार में एक सवाल पूछो। मेरे जवाब के बाद एक वाक्य का सुधार या तारीफ़ दो, फिर अगला सवाल पूछो। 6 सवाल के बाद कुल फ़ीडबैक दो। हिंदी-अंग्रेज़ी मिलाकर, छोटे जवाब।",
                "इंटरव्यू प्रैक्टिस शुरू, पद: $job। जब तैयार हो, बोलो, शुरू करो।"
            )
        }
        if (any("kahani", "कहानी") && any("sunao", "सुनाओ", "suna do", "सुना दो", "batao", "बताओ")) {
            val topic = topicAfter(t)
            return startMode(
                "story",
                "तुम एक कहानीकार हो। हिंदी में एक रोचक कहानी सुनाओ${if (topic.isNotBlank()) ", विषय: $topic" else ""}। एक बार में लगभग 60 सेकंड का हिस्सा बोलो, फिर रुककर पूछो कि आगे सुनाऊँ? जब मैं कहूँ आगे, तो कहानी आगे बढ़ाओ।",
                "ठीक है, कहानी शुरू करता हूँ। तैयार हो? बोलो, हाँ।"
            )
        }

        // memory
        if (any("yaad rakho", "याद रखो", "yaad rakhna", "याद रखना", "remember that", "yaad rakh")) {
            val m = Regex("(?:yaad rakho|याद रखो|yaad rakhna|याद रखना|remember that|yaad rakh)\\s+(?:ki|कि|that)?\\s*(.+)$").find(t)
            val fact = m?.groupValues?.get(1)?.trim()
            if (!fact.isNullOrBlank()) return remember(fact)
        }
        if (any("sab bhool jao", "सब भूल जाओ", "yaad kiya hua mita", "याद किया हुआ मिटा")) {
            Prefs.userFacts = "[]"
            return "याद रखी हुई सारी बातें मिटा दीं।"
        }
        if (any("kahan rakha", "कहाँ रखा", "kahan rakhi", "कहाँ रखी", "kahan hai", "कहाँ है", "kya yaad", "क्या याद", "maine kya bola", "मैंने क्या बोला", "yaad hai", "याद है") &&
            facts().length() > 0 && !any("reminder", "रिमाइंडर")
        ) {
            return recall(raw)
        }

        // plain-words daily reminder
        if (any("yaad dila", "याद दिला") && any("roz", "रोज़", "रोज", "daily", "har din", "हर दिन")) {
            dailyReminder(t, words)?.let { return it }
        }

        // clipboard
        if (any("clipboard", "क्लिपबोर्ड", "copy kiya", "कॉपी किया", "copy kiya hua", "कॉपी किया हुआ")) return clipboard(ctx, t)

        // day plan
        if (any("plan banao", "प्लान बनाओ", "din ka plan", "दिन का प्लान", "mera plan", "मेरा प्लान")) return plan(t)

        // live internet answers
        val explicit = any("internet", "इंटरनेट", "google", "गूगल") &&
            any("pe", "पे", "par", "पर", "dekho", "देखो", "batao", "बताओ", "search", "सर्च", "se", "से") &&
            !any("maps", "मैप", "khol", "खोल", "open", "ओपन", "chalu", "चालू", "navigate", "नेविगेट")
        val topic = word("khabar", "खबर", "ख़बर", "news", "न्यूज़", "न्यूज", "score", "स्कोर", "rate", "रेट", "bhav", "भाव", "price", "कीमत", "kimat", "कीमत", "match", "मैच")
        val fresh = word("aaj", "आज", "latest", "abhi", "अभी", "live", "taaza", "ताज़ा", "ताजा", "kya", "क्या", "kitna", "कितना", "kitne", "कितने")
        if (explicit || (topic && fresh)) {
            return live(raw)
        }
        return null
    }
}
