package com.zyro.app

import android.content.Context

/**
 * "Gemini se baat karo / isse puch do aur do kitne": Zyro types into the AI chat that is open on the screen
 * (Gemini, ChatGPT, Meta AI...), reads the answer, and for a longer chat asks the next question itself.
 * One question = one round. "5 minute baat karte raho" = up to 6 rounds. "ruko" stops.
 */
object Duet {

    @Volatile var active = false
        private set
    @Volatile private var stop = false

    private fun has(t: String, vararg w: String) = w.any { t.contains(Local.strip(it)) }

    fun handle(ctx: Context, hands: Hands, t: String, raw: String): String? {
        if (active && has(t, "ruko", "रुको", "stop", "band", "बंद", "bas karo", "बस करो")) { stop = true; Agent.cancel(); return "ठीक है, बातचीत रोक रहा हूँ।" }
        val who = has(t, "gemini", "जेमिनी", "chatgpt", "चैटजीपीटी", "meta ai", "copilot", "इससे", "isse", "usse", "उससे", "iske saath", "इसके साथ", "is ai", "ai se", "एआई से")
        val talk = has(t, "baat", "बात", "chat", "चैट", "puch", "पूछ", "poochh", "pooch", "sawal", "सवाल", "duet")
        if (!who || !talk) return null
        // not for phone-contact chats or sending real messages
        if (has(t, "whatsapp", "व्हाट्सएप", "sms", "एसएमएस", "bhej", "भेज", "call", "कॉल", "mummy", "papa", "मम्मी", "पापा")) return null
        if (!Prefs.hasGemini) return "इसके लिए Gemini key चाहिए।"
        if (hands.service == null) return "इसके लिए Accessibility में Zyro चालू करो।"
        if (active) return "एक बातचीत चल रही है। रोकना हो तो बोलो रुको।"
        if (Agent.running) return "अभी मैं दूसरा काम कर रहा हूँ। पूरा होने दो या रुको बोलो।"

        val long = has(t, "minute", "मिनट", "karte raho", "करते रहो", "baatein", "बातें", "gossip", "flirt", "lagatar", "लगातार")
        val rounds = if (long) 5 else 1
        // the question itself, when the user said it ("isse puch do aur do kitne")
        var topic = raw.trim()
        val cut = listOf("puch ke", "puch kar", "pooch", "puch", "पूछ के", "पूछकर", "पूछ", "पूछो", "poocho").map { topic.lowercase().indexOf(it) to it }.filter { it.first >= 0 }.minByOrNull { it.first }
        if (cut != null) topic = topic.substring(cut.first + cut.second.length).trim(' ', ',', '.', ':', '?')
        run(ctx, hands, topic, rounds, long)
        return if (long) "ठीक है, स्क्रीन वाले AI से बात शुरू करता हूँ। रोकना हो तो बोलो रुको।" else "ठीक है, उससे पूछ लेता हूँ।"
    }

    private fun run(ctx: Context, hands: Hands, topic: String, rounds: Int, long: Boolean) {
        active = true; stop = false
        Thread {
            val said = ArrayList<String>()
            try {
                var q = if (topic.length >= 4 && !long) topic else firstQuestion(topic)
                var lastAns = ""
                for (r in 0 until rounds) {
                    if (stop) break
                    Agent.lastSay = ""
                    val job = "इस स्क्रीन पर जो AI चैट खुली है (Gemini, ChatGPT या कोई भी) उसके मैसेज बॉक्स में यह टेक्स्ट टाइप करो: \"$q\" और भेजो (Send/तीर बटन)। " +
                        "फिर रुककर जवाब पूरा आने दो और जवाब का मुख्य हिस्सा 2 छोटे वाक्य में बताओ। अगर स्क्रीन पर कोई AI चैट नहीं दिखे तो बताओ कि चैट नहीं मिली, कुछ और मत करो।"
                    val s = Agent.start(ctx, hands, job)
                    if (!Agent.running) { said.add(s); break }
                    var w = 0
                    while (Agent.running && !stop && w < 150_000) { Thread.sleep(500); w += 500 }
                    if (stop || Agent.wasCancelled) break
                    lastAns = Agent.lastSay.ifBlank { "" }
                    said.add("सवाल: $q → जवाब: ${lastAns.take(220)}")
                    if (r + 1 < rounds) {
                        q = Brain.quickJson(
                            "तुम एक यूज़र की तरफ़ से एक AI से दोस्ताना बातचीत कर रहे हो। विषय: \"${topic.ifBlank { "कुछ भी मज़ेदार" }}\"।\nAI का पिछला जवाब: \"${lastAns.take(300)}\"\n" +
                                "अगला एक छोटा, सभ्य, दिलचस्प सवाल या टिप्पणी हिंदी या Hinglish में लिखो (एक ही वाक्य)। निजी जानकारी, पैसे या पासवर्ड कभी मत माँगो या दो। सिर्फ़ वो वाक्य लिखो।",
                            120, 15000, true
                        )?.trim()?.trim('"') ?: break
                        if (q.isBlank()) break
                    }
                }
            } catch (e: Exception) { said.add("दिक्कत: ${e.message}") }
            active = false
            val out = if (said.isEmpty()) "बातचीत शुरू नहीं हो पाई।" else if (said.size == 1 && !said[0].startsWith("सवाल")) said[0] else {
                if (said.size == 1) said[0].substringAfter("जवाब: ").ifBlank { "जवाब नहीं पढ़ पाया।" }
                else "${said.size} बार बात हुई। आख़िरी: " + said.last().substringAfter("जवाब: ").ifBlank { "जवाब नहीं पढ़ पाया।" }
            }
            try { Assistant.get(ctx).note("🗣 AI से बातचीत:\n" + said.joinToString("\n")) } catch (e: Exception) { }
            try { Assistant.get(ctx).announce(out) } catch (e: Exception) { }
        }.start()
    }

    private fun firstQuestion(topic: String): String {
        val seed = topic.ifBlank { "कोई मज़ेदार सवाल" }
        return Brain.quickJson("यूज़र चाहता है कि तुम स्क्रीन पर खुले AI से बातचीत शुरू करो। विषय: \"$seed\"। एक छोटा दोस्ताना शुरुआती सवाल हिंदी/Hinglish में लिखो, एक वाक्य। सिर्फ़ वो वाक्य।", 100, 15000, true)?.trim()?.trim('"')
            ?.takeIf { it.length > 3 } ?: "नमस्ते! आज का दिन कैसा रहा?"
    }
}
