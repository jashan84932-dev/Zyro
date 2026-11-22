package com.zyro.app

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** "WhatsApp pe kya aaya?", "Rahul ka message kya tha?", "kitne naye message hain?": answers from the notification log, nothing leaves the phone. */
object Inbox {

    private val APP_WORDS = listOf(
        "whatsapp" to "whatsapp", "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वॉट्सऐप" to "whatsapp",
        "instagram" to "instagram", "insta" to "instagram", "इंस्टाग्राम" to "instagram",
        "telegram" to "telegram", "टेलीग्राम" to "telegram",
        "gmail" to "gm", "जीमेल" to "gm", "mail" to "gm", "मेल" to "gm",
        "sms" to "messag", "एसएमएस" to "messag", "youtube" to "youtube", "यूट्यूब" to "youtube", "facebook" to "facebook", "फेसबुक" to "facebook"
    )

    private fun listenerOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(ctx.packageName)
    }

    fun handle(ctx: Context, t: String): String? {
        fun any(vararg w: String) = w.any { t.contains(Local.strip(it)) }
        if (any("bhej", "भेज", "send", "likh", "लिख", "reply", "जवाब", "jawab", "yaad", "याद", "delete", "हटा")) return null
        val subject = any("message", "मैसेज", "मेसेज", "notification", "नोटिफिकेशन", "नोटिफ़िकेशन", "whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "instagram", "इंस्टाग्राम", "telegram", "टेलीग्राम", "gmail", "जीमेल", "kisi ne", "किसी ने", "unread")
        val ask = any("kya aaya", "क्या आया", "kya aya", "aaya", "आया", "aayi", "आई", "aaye", "आए", "kaun", "कौन", "kitne", "कितने", "unread", "naye", "नए", "new", "padh", "पढ़", "पढ", "sunao", "सुनाओ", "kya tha", "क्या था", "kya likha", "क्या लिखा", "bata", "बता", "dikha", "दिखा", "koi")
        if (!subject || !ask) return null
        // "WhatsApp kholo" and similar are not questions about incoming messages
        if (any("khol", "खोल", "open") && !any("kya", "क्या", "kaun", "कौन")) return null
        if (!listenerOn(ctx)) {
            try { ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            return "मैसेज पढ़ने के लिए सेटिंग्स में Zyro को Notification access ON कर दो, फिर दोबारा पूछना।"
        }
        val all = synchronized(NotifService.log) { ArrayList(NotifService.log) }
        val appKey = APP_WORDS.firstOrNull { t.contains(Local.strip(it.first)) }?.second
        // a sender name: any word of the request that matches the start of a sender in the log
        val words = t.split(Regex("[\\s,।.!?]+")).filter { it.length >= 3 }
        val sender = all.map { it.who }.distinct().firstOrNull { who ->
            val w = Local.strip(who.lowercase())
            w.length >= 3 && words.any { x -> w.startsWith(x) || x.startsWith(w.split(" ")[0]) && w.split(" ")[0].length >= 3 }
        }
        var list = all.filter { it.chat || appKey != null }
        if (appKey != null) list = list.filter { low(it.pkg + " " + it.app).contains(appKey) }
        if (sender != null) list = all.filter { it.who == sender }
        if (list.isEmpty()) {
            return if (all.isEmpty()) "अभी तक कोई नई notification नहीं आई। (मैं सिर्फ़ वो देख पाता हूँ जो Zyro चालू होने के बाद आईं।)"
            else if (sender != null) "$sender का कोई मैसेज नहीं मिला।" else "इस ऐप से कोई नया मैसेज नहीं है।"
        }
        if (sender != null) {
            val msgs = list.takeLast(3).joinToString("। ") { it.text }
            return "$sender के ${list.size} मैसेज: ".plus(msgs).take(500)
        }
        // group by sender, newest first
        val groups = list.groupBy { it.app + "|" + it.who }.values.sortedByDescending { it.last().at }
        val sb = StringBuilder("${list.size} मैसेज, ${groups.size} लोगों से। ")
        for (g in groups.take(4)) {
            val last = g.last()
            val who = last.who.ifBlank { last.app }
            sb.append(who).append(if (g.size > 1) " के ${g.size} मैसेज, आख़िरी: " else ": ").append(last.text.take(70)).append("। ")
        }
        if (groups.size > 4) sb.append("और ${groups.size - 4} लोग भी हैं।")
        return sb.toString().take(700)
    }

    private fun low(s: String) = s.lowercase()
}
