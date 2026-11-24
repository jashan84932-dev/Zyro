package com.zyro.app

import java.util.Locale

/**
 * A tidy, structured view of what Zyro remembers. The saved facts are sorted into routines, likes, work,
 * places and people, so the AI gets a clear profile instead of one long list, and the user can ask
 * "Rahul ke baare mein kya pata hai" or "meri memory ka hisab".
 */
object Mind {

    private val TITLES = arrayOf("Routines", "Likes and dislikes", "Work and projects", "Places", "People in the user's life", "Other")
    private val SHORT = arrayOf("आदतें", "पसंद-नापसंद", "काम और प्रोजेक्ट", "जगहें", "लोग", "बाकी")

    private val ROUTINE = setOf("roz", "daily", "subah", "raat", "har", "hamesha", "usually", "every", "morning", "night", "weekend", "evening", "shaam", "routine", "aadat", "habit")
    private val ROUTINE_DEV = listOf("रोज", "सुबह", "रात", "हमेशा", "आदत", "शाम")
    private val LIKES = setOf("pasand", "favorite", "favourite", "love", "loves", "like", "likes", "enjoy", "enjoys", "hate", "hates", "dislike", "nafrat", "shauk", "pasandida")
    private val LIKES_DEV = listOf("पसंद", "नापसंद", "शौक", "नफरत")
    private val WORK = setOf("project", "app", "kaam", "job", "work", "exam", "interview", "study", "padhai", "college", "school", "business", "code", "coding", "github", "zyro", "office", "company", "salary", "class")
    private val WORK_DEV = listOf("काम", "प्रोजेक्ट", "नौकरी", "पढ़ाई", "परीक्षा", "कॉलेज", "स्कूल", "ऑफिस")
    private val PLACES = setOf("ghar", "home", "city", "shehar", "lives", "live", "rehta", "rehti", "from", "village", "gaon", "address", "india", "town", "mohalla")
    private val PLACES_DEV = listOf("घर", "शहर", "गांव", "गाँव", "रहता", "रहती")
    private val PEOPLE = setOf("dost", "friend", "bhai", "behen", "bahan", "mummy", "mom", "papa", "dad", "wife", "husband", "biwi", "girlfriend", "boyfriend", "gf", "bf", "teacher", "boss", "uncle", "aunty", "chacha", "mama", "dada", "dadi", "nana", "nani", "sister", "brother", "cousin", "colleague", "son", "daughter", "beta", "beti")
    private val PEOPLE_DEV = listOf("दोस्त", "भाई", "बहन", "मम्मी", "पापा", "पत्नी", "पति", "टीचर", "बॉस", "चाचा", "मामा", "बेटा", "बेटी")

    private fun words(f: String): Set<String> =
        Regex("[a-z0-9]+").findAll(f.lowercase(Locale.ROOT)).map { it.value }.toSet()

    private fun hit(f: String, ascii: Set<String>, dev: List<String>): Boolean {
        if (words(f).any { it in ascii }) return true
        return dev.any { f.contains(it) }
    }

    /** 0 routines, 1 likes, 2 work, 3 places, 4 people, 5 other. */
    fun category(f: String): Int = when {
        hit(f, ROUTINE, ROUTINE_DEV) -> 0
        hit(f, LIKES, LIKES_DEV) -> 1
        hit(f, WORK, WORK_DEV) -> 2
        hit(f, PLACES, PLACES_DEV) -> 3
        hit(f, PEOPLE, PEOPLE_DEV) -> 4
        else -> 5
    }

    /** The facts, grouped under short headings, ready to put into an AI prompt. */
    fun grouped(facts: List<String>): String {
        if (facts.isEmpty()) return ""
        val buckets = Array(TITLES.size) { ArrayList<String>() }
        for (f in facts) buckets[category(f)].add(f)
        val sb = StringBuilder()
        for (i in buckets.indices) {
            if (buckets[i].isEmpty()) continue
            sb.append(TITLES[i]).append(":\n")
            for (f in buckets[i]) sb.append("- ").append(f).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun summary(): String {
        val facts = Memory.facts()
        if (facts.isEmpty()) return "अभी मुझे तुम्हारे बारे में कुछ याद नहीं है। बोलो, याद रखो, और फिर बात बताओ।"
        val counts = IntArray(TITLES.size)
        for (f in facts) counts[category(f)]++
        val parts = ArrayList<String>()
        for (i in counts.indices) if (counts[i] > 0) parts.add("${SHORT[i]} की ${counts[i]}")
        return "तुम्हारे बारे में ${facts.size} बातें याद हैं: " + parts.joinToString(", ") + "। किसी के बारे में पूछना हो तो बोलो, राहुल के बारे में क्या पता है।"
    }

    private val SELF = setOf("mere", "मेरे", "mera", "मेरा", "meri", "मेरी", "my", "me", "मुझे", "mujhe")
    private val FILLER = setOf(
        "tum", "तुम", "tumhe", "तुम्हे", "तुम्हें", "aap", "आप", "apko", "आपको", "mujhe", "मुझे", "batao", "बताओ", "bata", "बता", "please", "plz", "zyro", "ziro", "hey", "ok", "okay", "ab", "अब",
        "ye", "ये", "wo", "वो", "kya", "क्या", "bhai", "यार", "yaar", "toh", "तो", "to", "ki", "की", "ka", "का", "ke", "के", "ko", "को", "jo", "aur", "और", "ek", "एक", "zara", "ज़रा", "जरा"
    ).map { Local.strip(it) }.toSet()

    /** Voice questions about memory. [t] is the normalised sentence. */
    fun handle(t: String): String? {
        fun has(vararg w: String) = w.any { t.contains(Local.strip(it)) }

        val aboutAt = listOf("ke baare", "ke bare", "के बारे").map { Local.strip(it) }.map { t.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        val asks = has("kya pata", "kya yaad", "kya jaan", "क्या पता", "क्या याद", "क्या जान", "yaad hai", "याद है", "batao", "बताओ", "kya malum", "क्या मालूम")
        if (aboutAt >= 0 && asks) {
            val tokens = t.substring(0, aboutAt).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val isSelf = tokens.any { it in SELF.map { x -> Local.strip(x) } }
            val rest = tokens.filter { it !in FILLER && it !in SELF.map { x -> Local.strip(x) } }
            if (rest.isEmpty()) return if (isSelf) summary() else null
            val name = rest.takeLast(2).joinToString(" ")
            val hits = Memory.facts().filter { it.lowercase(Locale.ROOT).contains(name) || Local.strip(it).contains(name) }
            if (hits.isEmpty()) return "$name के बारे में मुझे अभी कुछ याद नहीं है। बोलो, याद रखो, और बात बताओ, मैं रख लूँगा।"
            return "$name के बारे में ${hits.size} बातें याद हैं। " + hits.takeLast(5).joinToString("। ") + "।"
        }

        val mem = has("memory", "yaaddasht", "yaddasht", "याददाश्त", "yaadein", "यादें")
        val show = has("kitni", "summary", "dikhao", "category", "hisab", "हिसाब", "kya kya", "क्या क्या", "कितनी", "सारांश")
        val device = has("phone", "फोन", "फ़ोन", "storage", "स्टोरेज", "ram", "gb", "card", "कार्ड")
        if (mem && show && !device) return summary()
        return null
    }
}
