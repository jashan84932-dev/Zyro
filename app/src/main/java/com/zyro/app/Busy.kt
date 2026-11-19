package com.zyro.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Busy mode: when you cannot take calls, Zyro cuts the call, sends the caller a short SMS ("busy, please message"),
 * remembers who called and gives you a summary later. It can also hang up on telemarketing numbers on its own.
 *
 * Honest limits: Android does not let an app talk to the caller inside a normal phone call, so the "message" goes by SMS.
 * Someone who calls twice within 3 minutes is let through (it may be urgent).
 */
object Busy {
    private fun strip(s: String) = Local.strip(s)
    private val lastCall = HashMap<String, Long>()
    private val lastSms = HashMap<String, Long>()

    // ---------------------------------------------------------------- number checks

    private fun digits(n: String) = n.filter { it.isDigit() }

    /** Indian telemarketing ranges (140xxxx, 160xxxx) and toll-free promo lines. Deliberately narrow, so real people are never cut. */
    fun isSpam(number: String): Boolean {
        var d = digits(number)
        if (d.isEmpty()) return false
        if (d.startsWith("91") && d.length > 10) d = d.substring(2)
        if (d.startsWith("0") && d.length > 10) d = d.substring(1)
        return d.startsWith("140") || d.startsWith("160") || d.startsWith("1800") || d.startsWith("1860")
    }

    private fun looksLikeMobile(number: String): Boolean {
        var d = digits(number)
        if (d.startsWith("91") && d.length == 12) d = d.substring(2)
        return d.length == 10 && d[0] in '6'..'9'
    }

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Cuts the ringing call. Returns true when the phone really hung up. */
    private fun hangUp(ctx: Context): Boolean {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28 && has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) {
                return (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
            }
        } catch (e: Exception) { }
        // older phones (Android 8 and below): the hidden telephony interface still works
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
            val m = tm.javaClass.getDeclaredMethod("getITelephony")
            m.isAccessible = true
            val svc = m.invoke(tm)
            svc.javaClass.getMethod("endCall").invoke(svc)
            true
        } catch (e: Throwable) { false }
    }

    // ---------------------------------------------------------------- log

    private fun log(number: String, name: String?, kind: String) {
        try {
            val c = Extras.appContext()
            val who = name ?: ("नंबर " + digits(number).takeLast(10))
            val txt = when (kind) { "spam" -> "📵 स्पैम कॉल काटी: $who"; "busy" -> "📵 बिज़ी मोड: $who की कॉल काटी, SMS भेजा"; "repeat" -> "⚠️ $who ने दोबारा कॉल की, शायद ज़रूरी है"; else -> null }
            if (c != null && txt != null) Assistant.get(c).note(txt)
        } catch (e: Exception) { }
        val arr = try { JSONArray(Prefs.busyLog) } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("n", number).put("name", name ?: "").put("t", System.currentTimeMillis()).put("k", kind))
        // keep the last 60
        val out = JSONArray()
        for (i in maxOf(0, arr.length() - 60) until arr.length()) out.put(arr.get(i))
        Prefs.busyLog = out.toString()
    }

    // ---------------------------------------------------------------- the ring

    /**
     * Called when the phone starts ringing. Returns true when Zyro dealt with the call by itself
     * (then it should NOT announce it).
     */
    fun onRing(ctx: Context, number: String): Boolean {
        if (!Prefs.busyMode && !Prefs.spamBlock) return false
        if (number.isBlank()) return false
        val now = System.currentTimeMillis()
        val name = try { Comms.nameFor(ctx, number) } catch (e: Exception) { null }

        if (name == null && isSpam(number)) {
            val ok = hangUp(ctx)
            log(number, null, if (ok) "spam" else "spam_ring")
            return ok
        }
        if (!Prefs.busyMode) return false

        // the same person ringing again within 3 minutes: let it through
        val prev = synchronized(lastCall) { val p = lastCall[number] ?: 0L; lastCall[number] = now; p }
        if (now - prev < 180_000L) {
            log(number, name, "repeat")
            return false
        }
        val ok = hangUp(ctx)
        if (!ok) { log(number, name, "ring"); return false }
        log(number, name, "busy")
        // one short SMS per person per half hour; never to short codes
        val last = synchronized(lastSms) { lastSms[number] ?: 0L }
        if (now - last > 30 * 60_000L && (name != null || looksLikeMobile(number)) && has(ctx, Manifest.permission.SEND_SMS)) {
            synchronized(lastSms) { lastSms[number] = now }
            try {
                val me = Prefs.userName.ifBlank { "मैं" }
                val body = if (Prefs.userName.isBlank()) "मैं अभी busy हूँ, कॉल नहीं ले पा रहा। कृपया अपना मैसेज SMS या WhatsApp पर भेज दीजिए, मैं बाद में देखकर जवाब दूँगा। (Zyro ऑटो-मैसेज)"
                else "$me अभी busy हैं, कॉल नहीं ले पा रहे। कृपया अपना मैसेज SMS या WhatsApp पर भेज दीजिए, बाद में देखकर जवाब देंगे। (Zyro ऑटो-मैसेज)"
                @Suppress("DEPRECATION")
                val sm = SmsManager.getDefault()
                sm.sendMultipartTextMessage(number, null, sm.divideMessage(body), null, null)
            } catch (e: Exception) { }
        }
        return true
    }

    // ---------------------------------------------------------------- summary

    private fun summary(): String {
        val arr = try { JSONArray(Prefs.busyLog) } catch (e: Exception) { JSONArray() }
        if (arr.length() == 0) return "इस दौरान कोई कॉल नहीं आई।"
        val tf = SimpleDateFormat("h:mm a", Locale.US)
        val people = LinkedHashMap<String, Pair<Int, Long>>() // who -> (count, last time)
        var spam = 0
        var urgent = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val k = o.optString("k")
            if (k == "spam" || k == "spam_ring") { spam++; continue }
            if (k == "repeat") urgent++
            val who = o.optString("name").ifBlank { "नंबर " + digits(o.optString("n")).takeLast(10).toCharArray().joinToString(" ") }
            val p = people[who]
            people[who] = Pair((p?.first ?: 0) + 1, o.optLong("t"))
        }
        val sb = StringBuilder()
        if (people.isEmpty()) sb.append("किसी इंसान की कॉल नहीं आई। ")
        else {
            sb.append("${people.size} लोगों ने कॉल की। ")
            for ((who, v) in people.entries.take(6)) sb.append("$who ने ${if (v.first > 1) "${v.first} बार, आख़िरी " else ""}${tf.format(Date(v.second))} पर। ")
        }
        if (urgent > 0) sb.append("$urgent बार किसी ने जल्दी-जल्दी दोबारा कॉल की, शायद ज़रूरी हो। ")
        if (spam > 0) sb.append("$spam स्पैम कॉल मैंने खुद काट दीं।")
        Prefs.busyLog = "[]"
        return sb.toString().trim()
    }

    // ---------------------------------------------------------------- commands

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        val off = any("band", "बंद", "off", "stop", "khatam", "खत्म", "hata", "हटा")
        val on = !off && any("chalu", "चालू", "shuru", "शुरू", "on", "laga", "लगा", "karo", "करो")

        if (any("busy mode", "बिजी मोड", "busy mod", "disturb mat", "डिस्टर्ब मत")) {
            if (off) {
                Prefs.busyMode = false
                return "बिज़ी मोड बंद। ${summary()}"
            }
            Prefs.busyMode = true
            return "बिज़ी मोड चालू। अब कोई कॉल आएगी तो मैं काट दूँगा और उसे SMS भेज दूँगा कि आप busy हैं। जो दो बार जल्दी-जल्दी कॉल करेगा उसे आने दूँगा। बाद में बोलो, बिज़ी का हिसाब।" +
                (if (android.os.Build.VERSION.SDK_INT < 28) " ध्यान रहे, इस पुराने फ़ोन पर कॉल काटना हर बार काम नहीं भी कर सकता।" else "")
        }
        if (any("spam filter", "स्पैम फिल्टर", "spam block", "स्पैम ब्लॉक", "spam call band", "स्पैम कॉल")) {
            if (any("hisab", "हिसाब", "kitne", "कितने")) return summary()
            Prefs.spamBlock = !off
            return if (off) "स्पैम फ़िल्टर बंद।" else "स्पैम फ़िल्टर चालू। 140 और 160 वाली मार्केटिंग कॉल मैं खुद काट दूँगा, किसी जान-पहचान वाले को नहीं।"
        }
        if ((any("busy", "बिजी", "बिज़ी") && any("hisab", "हिसाब", "summary", "सारांश", "batao", "बताओ")) ||
            any("kisne call kiya", "किसने कॉल किया", "kaun kaun ke call", "कौन कौन के कॉल", "kiska call aaya", "किसका कॉल आया", "missed ka hisab", "पीछे कौन कौन")) {
            return summary()
        }
        return null
    }
}
