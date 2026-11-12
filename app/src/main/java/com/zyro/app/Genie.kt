package com.zyro.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * - World briefing: what is happening in the world right now, spoken, or as a longer report you can share.
 * - Make a picture / design from a description and share it.
 * - Fever and temperature log: you type in what a REAL thermometer shows; Zyro keeps the record and tells you
 *   when it is time to see a doctor. (A phone cannot measure body temperature - see the reply.)
 */
object Genie {

    private fun has(t: String, vararg w: String) = w.any { t.contains(Local.strip(it)) }

    fun handle(ctx: Context, hands: Hands, t: String, raw: String): String? {
        fever(ctx, t, raw)?.let { return it }

        // ----- hologram mode -----
        if (has(t, "hologram", "होलोग्राम", "holo", "होलो") && !has(t, "kaise", "कैसे")) {
            hands.openActivity(Intent(ctx, HoloActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "फोन को सीधा लिटाओ और बीच में पिरामिड रखो। बंद करने के लिए स्क्रीन छू लो।"
        }

        // ----- phone as a thermometer: be honest -----
        if (has(t, "phone se temperature", "mobile se temperature", "phone se bukhar", "mobile se bukhar", "phone se fever", "फोन से बुखार", "मोबाइल से बुखार", "फोन से तापमान") ||
            (has(t, "bukhar", "बुखार", "fever") && has(t, "phone laga", "mobile laga", "फोन लगा", "मोबाइल लगा", "shareer par phone", "body par phone"))) {
            return "फोन से बुखार नहीं नापा जा सकता। उसका सेंसर फोन की अपनी बैटरी की गर्मी बताता है, शरीर की नहीं। गलत रीडिंग खतरनाक हो सकती है। थर्मामीटर से नापकर बोलो, जैसे: मेरा बुखार एक सौ एक है। मैं रिकॉर्ड रखूँगा।"
        }

        // ----- world briefing -----
        if (has(t, "duniya mein kya", "दुनिया में क्या", "duniya me kya", "world news", "वर्ल्ड न्यूज़", "वर्ल्ड न्यूज", "duniya ki khabar", "दुनिया की खबर", "duniya ka haal", "दुनिया का हाल", "kya chal raha hai duniya", "क्या चल रहा है दुनिया")) {
            val report = has(t, "report", "रिपोर्ट", "bhej", "भेज", "share", "शेयर", "file")
            if (!Prefs.hasGemini) return "इसके लिए Gemini key चाहिए।"
            Thread {
                val today = SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(Date())
                val a = Assistant.get(ctx)
                if (report) {
                    val r = Brain.quickText(
                        "Today is $today. Using current web results, write a clear world briefing report: top 8 developments across world politics, economy, technology and AI, science, and India. " +
                            "For each: a short heading and 2 sentences on what happened and why it matters. Write in simple Hinglish (Hindi in Devanagari, English words in English). Plain text, no markdown symbols.",
                        1800, search = true
                    )
                    if (r.isNullOrBlank()) { a.announce("रिपोर्ट नहीं बन पाई, इंटरनेट देखो।"); return@Thread }
                    shareText(ctx, hands, "Zyro world report - $today", r)
                    a.announce("रिपोर्ट तैयार है। शेयर का मेन्यू खोल दिया।")
                } else {
                    val r = Brain.quickText(
                        "Today is $today. From current web results, give the 6 most important things happening in the world right now (politics, economy, tech and AI, science, India). " +
                            "Each as one short spoken Hinglish sentence. No markdown, no numbering symbols.",
                        420, search = true
                    )
                    a.announce(r ?: "खबरें नहीं मिल पाईं, इंटरनेट देखो।")
                }
            }.start()
            return ""
        }

        // ----- make a picture and send it -----
        if (has(t, "tasveer", "तस्वीर", "image banao", "इमेज बनाओ", "picture banao", "पिक्चर बनाओ", "design banao", "डिज़ाइन बनाओ", "डिजाइन बनाओ", "logo banao", "लोगो बनाओ", "poster banao", "पोस्टर बनाओ", "wallpaper banao", "वॉलपेपर बनाओ") &&
            has(t, "banao", "बनाओ", "bana do", "बना दो", "generate")) {
            val prompt = raw.replace(Regex("(?i)(zyro|ज़ायरो|जायरो|banao|बनाओ|bana do|बना दो|ki tasveer|की तस्वीर|image|इमेज|picture|पिक्चर|ek|एक|mujhe|मुझे|aur bhejo|और भेजो|bhej do|भेज दो)"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (prompt.length < 3) return "क्या बनाना है? जैसे: एक नीले रंग के रोबोट का पोस्टर बनाओ।"
            Thread {
                val a = Assistant.get(ctx)
                try {
                    // let the AI turn the Hindi request into a rich English picture prompt
                    val en = Brain.quickText("Turn this request into one vivid English image-generation prompt (max 40 words). Output only the prompt: $prompt", 90) ?: prompt
                    val url = "https://image.pollinations.ai/prompt/" + Uri.encode(en) + "?width=1024&height=1024&nologo=true&seed=" + (System.currentTimeMillis() % 100000)
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 90000
                    if (c.responseCode !in 200..299) { a.announce("तस्वीर नहीं बन पाई।"); return@Thread }
                    val name = "zyro_" + (System.currentTimeMillis() / 1000) + ".jpg"
                    File(ctx.cacheDir, name).outputStream().use { o -> c.inputStream.use { it.copyTo(o) } }
                    val uri = Uri.parse("content://com.zyro.app.photo/$name")
                    val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    hands.openActivity(Intent.createChooser(send, "Zyro की बनाई तस्वीर").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    a.announce("तस्वीर तैयार है। भेजने का मेन्यू खोल दिया।")
                } catch (e: Exception) {
                    a.announce("तस्वीर नहीं बन पाई।")
                }
            }.start()
            return ""
        }
        return null
    }

    private fun shareText(ctx: Context, hands: Hands, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        hands.openActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------------------------------------------------------- fever log

    private fun entries(): JSONArray = try { JSONArray(Prefs.feverLog) } catch (e: Exception) { JSONArray() }

    private fun fever(ctx: Context, t: String, raw: String): String? {
        if (!has(t, "bukhar", "बुखार", "fever", "temperature", "तापमान", "टेम्परेचर")) return null
        // reading: "101", "101.5", or Celsius "38.5"
        val num = Regex("(\\d{2,3}(?:\\.\\d)?)").find(raw.replace(",", "."))?.groupValues?.get(1)?.toDoubleOrNull()
        val wantsLog = has(t, "record", "रिकॉर्ड", "history", "हिस्ट्री", "pichhle", "पिछले", "kitna raha", "कितना रहा")
        if (num != null && !wantsLog) {
            val f = if (num < 50) num * 9 / 5 + 32 else num
            if (f < 90 || f > 109) return "यह रीडिंग सही नहीं लग रही। थर्मामीटर दोबारा लगाकर बताओ।"
            val a = entries()
            a.put(JSONObject().put("t", System.currentTimeMillis()).put("f", Math.round(f * 10) / 10.0))
            while (a.length() > 60) a.remove(0)
            Prefs.feverLog = a.toString()
            val c = Math.round((f - 32) * 5 / 9 * 10) / 10.0
            val advice = when {
                f >= 103 -> "यह तेज़ बुखार है। जल्दी डॉक्टर को दिखाओ, खासकर अगर कमज़ोरी, साँस की दिक्कत, बहुत सुस्ती या गर्दन में अकड़न हो।"
                f >= 100.4 -> "यह बुखार है। आराम करो, पानी खूब पियो। तीन दिन से ज़्यादा रहे, या बच्चा, बुज़ुर्ग या गर्भवती हो तो डॉक्टर को दिखाओ। दवा डॉक्टर या फार्मासिस्ट से पूछकर लेना।"
                f >= 99.5 -> "हल्का बढ़ा हुआ है। कुछ घंटे बाद फिर नापकर बताना।"
                else -> "यह सामान्य है।"
            }
            return "नोट कर लिया, ${Math.round(f * 10) / 10.0} फ़ारेनहाइट, यानी $c सेल्सियस। $advice"
        }
        if (wantsLog || has(t, "bukhar record", "बुखार रिकॉर्ड")) {
            val a = entries()
            if (a.length() == 0) return "अभी कोई रीडिंग नहीं है। थर्मामीटर से नापकर बताओ।"
            val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
            val last = minOf(5, a.length())
            val sb = StringBuilder("पिछली $last रीडिंग: ")
            for (i in a.length() - last until a.length()) {
                val o = a.getJSONObject(i)
                sb.append(fmt.format(Date(o.getLong("t")))).append(" पर ").append(o.getDouble("f")).append("। ")
            }
            val first = a.getJSONObject(a.length() - last).getDouble("f")
            val lastV = a.getJSONObject(a.length() - 1).getDouble("f")
            sb.append(if (lastV > first + 0.5) "बुखार बढ़ रहा है।" else if (lastV < first - 0.5) "बुखार उतर रहा है।" else "लगभग वैसा ही है।")
            return sb.toString()
        }
        return null
    }
}
