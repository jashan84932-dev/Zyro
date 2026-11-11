package com.zyro.app

import android.app.Notification
import android.app.RemoteInput
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * "Genius" pack: Zyro understands what is on your screen or in your gallery, summarises your
 * notifications, researches topics with live web search, suggests replies, and can auto-reply
 * (always marked as an automatic reply) while you are busy.
 */
object Genius {

    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun s(x: String) = Local.strip(x)

    private fun clean(x: String): String =
        x.replace(Regex("[*#`_]+"), "").replace(Regex("\\n{2,}"), "\n").trim()

    private fun spoken(x: String, max: Int = 900): String = clean(x).replace(Regex("\\s+"), " ").take(max)

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(s(it)) }

        // ---------------- auto reply on / off ----------------
        if (any("auto reply", "ऑटो रिप्लाई", "auto jawab", "ऑटो जवाब")) {
            if (any("band", "बंद", "off", "stop", "khatam", "खत्म")) {
                Prefs.autoReply = false
                return "ऑटो रिप्लाई बंद।"
            }
            val note = Regex("(?:chalu karo|chalu|शुरू करो|चालू करो|चालू|on)[,\\s]*(.*)$", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.trim().orEmpty()
            Prefs.autoReplyNote = note.ifBlank { "मैं अभी busy हूँ, थोड़ी देर में जवाब दूँगा" }
            Prefs.autoReply = true
            return "ऑटो रिप्लाई चालू। मैसेज आने पर मैं एक छोटा जवाब भेजूँगा, और उसमें लिखा होगा कि यह ऑटोमैटिक है। बंद करने के लिए बोलो, ऑटो रिप्लाई बंद।"
        }

        // ---------------- reply suggestions ----------------
        if (any("kya jawab dun", "क्या जवाब दूँ", "क्या जवाब दूं", "reply suggest", "रिप्लाई सुझाओ", "jawab batao", "जवाब बताओ", "kya reply karun", "क्या रिप्लाई करूँ")) {
            val m = NotifService.last ?: return "अभी कोई नया मैसेज नहीं है जिसका जवाब सुझाऊँ।"
            val out = Brain.quickText(
                "Message from ${m.title} on ${m.app}: \"${m.text}\"\nGive 3 short possible replies (one polite, one casual, one funny), in the same language as the message, numbered 1 to 3. Nothing else.",
                220
            ) ?: return "जवाब नहीं सूझ पाया, इंटरनेट देखो।"
            return "${m.title} ने लिखा: ${m.text.take(100)}। सुझाव: " + spoken(out, 500)
        }

        // ---------------- notification digest ----------------
        if (any("messages ka summary", "मैसेज का सारांश", "notifications ka summary", "नोटिफिकेशन का सारांश", "kya miss kiya", "क्या मिस किया",
                "sab notifications", "सारी नोटिफिकेशन", "kya aaya", "क्या आया", "mere messages")) {
            val list = synchronized(NotifService.recent) { NotifService.recent.toList() }
            if (list.isEmpty()) return "अभी कोई नई नोटिफिकेशन नहीं है।"
            val out = Brain.quickText(
                "These are my recent phone notifications:\n" + list.joinToString("\n") +
                    "\nSummarise in Hinglish in 3 to 5 short spoken sentences. Mention what is important or urgent first. Ignore ads.",
                300
            ) ?: return list.takeLast(3).joinToString(". ")
            return spoken(out)
        }

        // ---------------- screen understanding ----------------
        val screenWord = any("screen", "स्क्रीन")
        val screenAsk = any("kya hai", "क्या है", "padho", "पढ़ो", "samjhao", "समझाओ", "summarize", "सारांश", "translate", "ट्रांसलेट", "solve", "हल करो", "batao", "बताओ", "explain", "एक्सप्लेन")
        if (screenWord && screenAsk && !any("hand", "हैंड", "gesture", "जेस्चर", "brightness", "ब्राइटनेस", "lock", "लॉक", "record", "रिकॉर्ड", "shot", "शॉट")) {
            if (hands.service == null) return "स्क्रीन पढ़ने के लिए Accessibility में Zyro चालू करो।"
            val text = hands.readScreen()
            val task = when {
                any("translate", "ट्रांसलेट", "अनुवाद") -> "Translate the main text on this screen into simple Hindi."
                any("solve", "हल करो", "answer", "जवाब") -> "If there is a question, problem or quiz on this screen, solve it and give the answer with a one-line reason."
                any("summarize", "सारांश", "short") -> "Summarise this screen in 2 to 3 short spoken sentences."
                else -> "Explain what is on this screen in 2 to 4 short spoken sentences, like telling a friend."
            }
            val out = Brain.quickText("Screen content (UI elements of an Android phone):\n${text.take(5000)}\n\n$task Reply in Hinglish. Do not read out passwords or OTPs.", 350)
                ?: return "स्क्रीन समझ नहीं पाया, इंटरनेट देखो।"
            return spoken(out)
        }

        // ---------------- gallery vision ----------------
        val photoWord = any("photo", "फोटो", "screenshot", "स्क्रीनशॉट", "तस्वीर", "tasveer")
        val lastWord = any("last", "latest", "आखिरी", "आख़िरी", "अभी वाली", "nayi", "नई", "recent", "gallery", "गैलरी")
        val askWord = any("kya hai", "क्या है", "dekho", "देखो", "samjhao", "समझाओ", "solve", "हल", "translate", "ट्रांसलेट", "batao", "बताओ", "padho", "पढ़ो")
        if (photoWord && lastWord && askWord) {
            val shot = any("screenshot", "स्क्रीनशॉट")
            val b64 = latestImage(ctx, shot) ?: return "गैलरी की फोटो नहीं पढ़ पाया। ऐप सेटिंग में फोटो की अनुमति दो, या गैलरी खाली है।"
            val prompt = when {
                any("solve", "हल") -> "इस फोटो में जो सवाल या समस्या है उसे हल करो। हिंदी में छोटा जवाब दो।"
                any("translate", "ट्रांसलेट") -> "इस फोटो का लिखा हुआ पाठ सरल हिंदी में अनुवाद करो।"
                any("padho", "पढ़ो") -> "इस फोटो में जो लिखा है वह पढ़कर सुनाओ।"
                else -> "इस फोटो में क्या है? हिंदी (थोड़ी English मिलाकर) में 2-3 छोटे वाक्यों में बताओ।"
            }
            return spoken(Brain(hands).describeImage(b64, prompt))
        }

        // ---------------- research / news / prices (live web search) ----------------
        if (any("research karo", "रिसर्च करो", "deep research", "डीप रिसर्च", "gehri jaankari", "गहरी जानकारी", "poori jaankari", "पूरी जानकारी")) {
            val topic = raw.replace(Regex("(?i)(research karo|रिसर्च करो|deep research|डीप रिसर्च|zyro|jarvis|micro|पर|par|ke baare mein|के बारे में|gehri jaankari|गहरी जानकारी|poori jaankari|पूरी जानकारी)"), " ").replace(Regex("\\s+"), " ").trim()
            if (topic.length < 3) return "किस विषय पर रिसर्च करूँ?"
            val out = Brain.quickText(
                "Research this topic using current web information: \"$topic\". Give 5 key facts in Hinglish as short spoken sentences. Add one line on what is uncertain or disputed. No markdown.",
                700, search = true
            ) ?: return "रिसर्च नहीं हो पाई, इंटरनेट या Gemini key देखो।"
            return spoken(out, 1100)
        }
        if (any("khabrein", "खबरें", "ख़बरें", "news batao", "न्यूज़ बताओ", "न्यूज बताओ", "aaj ki khabar", "आज की खबर", "headlines", "हेडलाइन")) {
            val out = Brain.quickText(
                "Today is ${java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date())}. Give the 5 most important news headlines for India today from current web results, each as one short Hinglish spoken sentence. No markdown.",
                500, search = true
            ) ?: return "खबरें नहीं मिल पाईं, इंटरनेट देखो।"
            return spoken(out, 900)
        }
        if (any("share price", "शेयर का भाव", "शेयर प्राइस", "bitcoin", "बिटकॉइन", "sone ka bhav", "सोने का भाव", "gold rate", "गोल्ड रेट", "petrol price", "पेट्रोल का भाव", "dollar rate", "डॉलर का रेट", "sensex", "सेंसेक्स", "nifty", "निफ्टी")) {
            val out = Brain.quickText("Current value for this request (use live web data, mention the source and that prices change): \"$raw\". Answer in one or two short Hinglish spoken sentences.", 250, search = true)
                ?: return "भाव नहीं मिल पाया, इंटरनेट देखो।"
            return spoken(out, 400)
        }

        // ---------------- focus mode ----------------
        if (any("focus mode", "फोकस मोड", "pomodoro", "पोमोडोरो", "padhai mode", "पढ़ाई मोड")) {
            val mins = words.firstNotNullOfOrNull { Local.numberOf(it) }?.coerceIn(5, 180) ?: 25
            val r = hands.setTimer(mins * 60, "Zyro focus")
            Prefs.readAloud = false
            return "फोकस मोड $mins मिनट के लिए चालू। इस दौरान मैसेज मैं नहीं पढ़ूँगा। टाइमर बजेगा तब ब्रेक लेना। $r"
        }
        if (any("focus mode band", "फोकस मोड बंद", "focus khatam", "फोकस खत्म")) {
            return "फोकस मोड बंद। मैसेज पढ़ने की सेटिंग आप ऐप में फिर चालू कर सकते हो।"
        }
        return null
    }

    // ------------------------------------------------------------------ gallery helper

    private fun latestImage(ctx: Context, screenshotOnly: Boolean): String? {
        try {
            val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 80) {
                    n++
                    val path = c.getString(1) ?: ""
                    if (screenshotOnly && !path.lowercase().contains("screenshot")) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: continue
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                    return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    // ------------------------------------------------------------------ auto reply

    private val lastReplyAt = HashMap<String, Long>()

    /** Called by the notification listener for every message notification. */
    fun onMessage(ctx: Context, pkg: String, title: String, text: String, act: Notification.Action?) {
        if (!Prefs.autoReply || act == null) return
        if (title.isBlank() || text.isBlank()) return
        val tl = title.lowercase()
        // skip groups and system-ish senders
        if (tl.contains("messages") || tl.contains("message") || title.contains(":") || title.contains("(")) return
        val now = System.currentTimeMillis()
        synchronized(lastReplyAt) {
            val prev = lastReplyAt[pkg + title] ?: 0L
            if (now - prev < 30 * 60_000L) return
            lastReplyAt[pkg + title] = now
        }
        Thread {
            try {
                val note = Prefs.autoReplyNote
                val reply = Brain.quickText(
                    "I (${Prefs.userName.ifBlank { "the user" }}) am busy right now: \"$note\". Someone wrote me: \"${text.take(300)}\".\n" +
                        "Write one short, friendly reply (max 2 sentences) in the same language as their message. Say I will reply later. " +
                        "Do not promise anything, do not share personal details, do not agree to money or meetings. Output only the reply.",
                    120
                ) ?: return@Thread
                val msg = spoken(reply, 300) + " (Zyro का ऑटो-रिप्लाई)"
                val inputs = act.remoteInputs ?: return@Thread
                val intent = Intent()
                val b = Bundle()
                for (ri in inputs) b.putCharSequence(ri.resultKey, msg)
                RemoteInput.addResultsToIntent(inputs, intent, b)
                act.actionIntent.send(ctx, 0, intent)
                Assistant.get(ctx).note("${Prefs.friendName}: ऑटो-रिप्लाई भेजा → $title")
            } catch (e: Exception) {
                // ignore
            }
        }.start()
    }
}
