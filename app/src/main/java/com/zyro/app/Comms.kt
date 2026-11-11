package com.zyro.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Calls, SMS / OTP, navigation (home / office) and learning your daily habits.
 * Everything stays on the phone.
 */
object Comms {

    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun strip(s: String) = Local.strip(s)
    private fun sir() = if (Prefs.jarvisMode) "सर, " else ""

    private fun has(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ pending questions

    private var pendingSms: Triple<String, String, String>? = null // name, number, text
    private var pendingSmsAt = 0L

    internal fun stageSms(name: String, number: String, body: String) {
        pendingSms = Triple(name, number, body)
        pendingSmsAt = System.currentTimeMillis()
    }
    private var pendingCallAt = 0L
    private var pendingHabit: String? = null
    private var pendingHabitAt = 0L

    private val YES = listOf("हाँ", "हां", "haan", "han", "yes", "ok", "okay", "ओके", "ठीक", "bhejo", "भेजो", "send", "uthao", "उठाओ", "utha", "उठा", "receive", "accept", "कर", "kar", "karo", "करो", "दो", "do")
    private val NO = listOf("नहीं", "nahi", "nahin", "no", "cancel", "mat", "मत", "रहने", "kaat", "काट", "cut", "reject", "कैंसिल")

    fun handlePending(hands: Hands, t: String, words: List<String>): String? {
        val ctx = appCtx ?: return null
        if (words.size > 3) return null // a yes/no answer is short; longer sentences are new commands
        val now = System.currentTimeMillis()
        fun word(list: List<String>) = list.any { x -> words.contains(strip(x)) }
        val yes = word(YES)
        val no = word(NO)

        if (pendingCallAt != 0L) {
            if (now - pendingCallAt > 25_000) pendingCallAt = 0L
            else if (no) {
                pendingCallAt = 0L
                return rejectCall(ctx)
            } else if (yes) {
                pendingCallAt = 0L
                return answerCall(ctx)
            }
        }
        val ps = pendingSms
        if (ps != null) {
            if (now - pendingSmsAt > 90_000) pendingSms = null
            else if (no) {
                pendingSms = null
                return "ठीक है, SMS नहीं भेजा।"
            } else if (yes) {
                pendingSms = null
                return sendSms(ctx, ps.first, ps.second, ps.third)
            }
        }
        val ph = pendingHabit
        if (ph != null) {
            if (now - pendingHabitAt > 120_000) pendingHabit = null
            else if (no) {
                pendingHabit = null
                Habit.decline(ph)
                return "ठीक है, अगली बार नहीं पूछूँगा... या कम पूछूँगा।"
            } else if (yes) {
                pendingHabit = null
                val r = try { Local.handle(hands, ph) } catch (e: Exception) { null }
                return r ?: "ठीक है, कर दिया।"
            }
        }
        return null
    }

    // ------------------------------------------------------------------ main entry

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        val whatsapp = any("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "व्हाट्सअप")

        // ----- habits -----
        if (any("aadat", "आदत", "aadaten", "आदतें", "आदतों")) {
            if (any("band", "बंद", "hatao", "हटाओ", "bhool", "भूल", "delete", "clear", "मिटाओ", "mitao")) {
                Prefs.habits = "{}"
                return "मैंने आपकी आदतों का सारा रिकॉर्ड मिटा दिया।"
            }
            return Habit.list()
        }

        // ----- calls -----
        if (any("missed", "मिस्ड", "मिस कॉल", "मिस काल", "miss call", "छूटी", "chhooti") && any("call", "कॉल", "काल", "फोन", "फ़ोन", "phone")) {
            return missedCalls(ctx)
        }
        if (any("kiska phone", "किसका फोन", "किसका फ़ोन", "kisne phone", "किसने फोन", "kisne call", "किसने कॉल", "last call", "आखिरी कॉल", "आख़िरी कॉल", "पिछली कॉल", "kiska call", "किसका कॉल")) {
            return lastCall(ctx)
        }
        if (any("wapas call", "वापस कॉल", "call back", "कॉल बैक", "वापस फोन", "wapas phone", "palat ke call", "पलट के कॉल") ||
            (any("jisne", "जिसने", "usko", "उसको", "उसे", "use ") && any("call", "कॉल") && any("kiya", "किया", "back", "बैक", "वापस", "wapas"))
        ) {
            return callBack(hands, ctx)
        }

        // ----- SMS -----
        val smsWord = any("sms", "एसएमएस", "text message", "टेक्स्ट मैसेज", "टेक्स्ट")
        val msgWord = smsWord || any("message", "मैसेज", "मेसेज", "संदेश")
        if (!whatsapp) {
            if (any("otp", "ओटीपी", "ओ टी पी", "verification code", "वेरिफिकेशन", "कोड") &&
                any("kya", "क्या", "bata", "बता", "padh", "पढ़", "पढ", "aaya", "आया", "aayi", "आई", "sunao", "सुना")
            ) {
                return otp(ctx)
            }
            if (any("jawab", "जवाब", "reply", "रिप्लाई") && msgWord) return replySms(ctx, t)
            if (smsWord && any("bhejo", "भेजो", "भेज", "send", "likho", "लिखो", "लिख", "karo", "करो")) {
                return composeSms(hands, ctx, raw)
            }
            if (msgWord && any("padho", "पढ़ो", "पढो", "padh", "पढ़", "read", "sunao", "सुनाओ", "suna", "सुना", "naya", "नया", "latest", "last", "आखिरी") &&
                !any("notification", "नोटिफिकेशन")
            ) {
                return readSms(ctx)
            }
        }

        // ----- places and navigation -----
        val placeWords = listOf("ghar", "घर", "home", "office", "ऑफिस", "ऑफ़िस", "दफ्तर", "दफ़्तर", "daftar", "work")
        if (any("pata", "पता", "address", "एड्रेस") && any("save", "सेव", "yaad", "याद", "set", "सेट", "rakho", "रखो", "ye hai", "यह है", "ये है", "likh", "लिख") &&
            placeWords.any { t.contains(strip(it)) }
        ) {
            return savePlace(t, words)
        }
        if (any("rasta", "रास्ता", "रस्ता", "navigate", "नेविगेट", "directions", "डायरेक्शन", "le chalo", "ले चलो", "route", "रूट", "navigation", "नेविगेशन") ||
            (any("kaise", "कैसे") && any("pahunch", "पहुँच", "पहुंच", "पहुच")) ||
            (any("kitna time", "कितना समय", "कितना टाइम", "kitni der", "कितनी देर") && any("pahunch", "पहुँच", "पहुंच", "lagega", "लगेगा"))
        ) {
            return navigateTo(hands, ctx, t, words)
        }
        return null
    }

    // ------------------------------------------------------------------ calls

    private class CallRow(val number: String, val name: String?, val type: Int, val date: Long)

    private fun callRows(ctx: Context, limit: Int): List<CallRow> {
        val out = ArrayList<CallRow>()
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out.add(CallRow(c.getString(0) ?: "", c.getString(1), c.getInt(2), c.getLong(3)))
                }
            }
        } catch (e: Exception) {
            // no permission or no log
        }
        return out
    }

    private fun spell(number: String): String = number.filter { it.isDigit() }.takeLast(10).toCharArray().joinToString(" ")

    private fun who(ctx: Context, r: CallRow): String =
        (r.name?.takeIf { it.isNotBlank() } ?: nameFor(ctx, r.number)) ?: if (r.number.isBlank()) "अनजान नंबर" else "नंबर ${spell(r.number)}"

    private fun missedCalls(ctx: Context): String {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return "कॉल लॉग देखने की अनुमति चाहिए। Zyro में पहला बटन दबाकर permission दे दो।"
        val missed = callRows(ctx, 40).filter { it.type == CallLog.Calls.MISSED_TYPE }.take(5)
        if (missed.isEmpty()) return "कोई मिस्ड कॉल नहीं है।"
        val names = missed.map { who(ctx, it) }
        return "${sir()}${missed.size} मिस्ड कॉल हैं: ${names.joinToString(", ")}।"
    }

    private fun lastCall(ctx: Context): String {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return "कॉल लॉग देखने की अनुमति चाहिए। Zyro में पहला बटन दबाकर permission दे दो।"
        val r = callRows(ctx, 15).firstOrNull { it.type == CallLog.Calls.INCOMING_TYPE || it.type == CallLog.Calls.MISSED_TYPE }
            ?: return "कोई आई हुई कॉल नहीं मिली।"
        val f = SimpleDateFormat("h:mm a", Locale.ENGLISH)
        val answered = if (r.type == CallLog.Calls.MISSED_TYPE) "आपने नहीं उठाया" else "आपने उठाया"
        return "आख़िरी कॉल ${who(ctx, r)} की थी, ${f.format(Date(r.date))} बजे। $answered।"
    }

    private fun callBack(hands: Hands, ctx: Context): String {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return "कॉल लॉग देखने की अनुमति चाहिए। Zyro में पहला बटन दबाकर permission दे दो।"
        val rows = callRows(ctx, 30)
        val r = rows.firstOrNull { it.type == CallLog.Calls.MISSED_TYPE }
            ?: rows.firstOrNull { it.type == CallLog.Calls.INCOMING_TYPE }
            ?: return "वापस करने लायक कोई कॉल नहीं मिली।"
        if (r.number.isBlank()) return "उस कॉल का नंबर छिपा हुआ है।"
        hands.callNumber(r.number)
        return "${who(ctx, r)} को कॉल कर रहा हूँ।"
    }

    private fun nameFor(ctx: Context, number: String): String? {
        if (number.isBlank() || !has(ctx, Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Called when the phone starts ringing. Returns the sentence Zyro should say, or null. */
    fun incomingCallText(number: String): String? {
        val ctx = appCtx ?: return null
        val name = nameFor(ctx, number)
        val who = name ?: if (number.isBlank()) null else "नंबर ${spell(number)}"
        pendingCallAt = System.currentTimeMillis()
        return if (who == null) "${sir()}कोई फ़ोन आ रहा है। उठाऊँ?" else "${sir()}$who का फ़ोन आ रहा है। उठाऊँ?"
    }

    private fun answerCall(ctx: Context): String {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return "कॉल उठाने की अनुमति चाहिए। Zyro में पहला बटन दबाकर permission दे दो।"
        return try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
            "कॉल उठा ली।"
        } catch (e: Exception) {
            "कॉल नहीं उठा पाया।"
        }
    }

    private fun rejectCall(ctx: Context): String {
        if (android.os.Build.VERSION.SDK_INT < 28) return "इस फ़ोन पर मैं कॉल काट नहीं सकता, आप खुद काट दीजिए।"
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return "कॉल काटने की अनुमति चाहिए।"
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.endCall()
            "कॉल काट दी।"
        } catch (e: Exception) {
            "कॉल नहीं काट पाया।"
        }
    }

    // ------------------------------------------------------------------ SMS

    private class SmsRow(val address: String, val body: String, val date: Long)

    private fun inbox(ctx: Context, limit: Int): List<SmsRow> {
        val out = ArrayList<SmsRow>()
        try {
            ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out.add(SmsRow(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2)))
                }
            }
        } catch (e: Exception) {
            // no permission
        }
        return out
    }

    private fun readSms(ctx: Context): String {
        if (!has(ctx, Manifest.permission.READ_SMS)) return "SMS पढ़ने की अनुमति चाहिए। Zyro में पहला बटन दबाकर permission दे दो।"
        val rows = inbox(ctx, 3)
        if (rows.isEmpty()) return "कोई SMS नहीं मिला।"
        return rows.joinToString(" ") { r ->
            val from = nameFor(ctx, r.address) ?: r.address
            "$from से: ${r.body.replace(Regex("\\s+"), " ").take(160)}।"
        }
    }

    private fun otp(ctx: Context): String {
        if (!has(ctx, Manifest.permission.READ_SMS)) return "SMS पढ़ने की अनुमति चाहिए। Zyro में पहला बटन दबाकर permission दे दो।"
        val limit = System.currentTimeMillis() - 20 * 60_000
        for (r in inbox(ctx, 12)) {
            if (r.date < limit) break
            val m = Regex("(?<!\\d)\\d{4,8}(?!\\d)").find(r.body) ?: continue
            val from = nameFor(ctx, r.address) ?: r.address
            return "${sir()}ओटीपी है ${m.value.toCharArray().joinToString(" ")}। भेजने वाला: $from।"
        }
        return "पिछले बीस मिनट में कोई ओटीपी नहीं आया।"
    }

    private val SMS_LEAD = setOf(
        "sms", "एसएमएस", "text", "टेक्स्ट", "message", "मैसेज", "मेसेज", "संदेश", "bhejo", "भेजो", "भेज", "send", "karo", "करो", "कर",
        "likho", "लिखो", "लिख", "कि", "ki", "ke", "ye", "ये", "that", "pe", "पे", "पर", "par", "hey", "micro", "माइक्रो", "aur", "और", "ek", "एक", "do", "दो", "से", "se"
    ).map { strip(it) }.toSet()

    private fun composeSms(hands: Hands, ctx: Context, raw: String): String {
        val tokens = strip(raw).replace(Regex("[।,?!;:\"'()]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        val ko = tokens.indexOfFirst { it == "ko" || it == "को" }
        if (ko < 0) return "ऐसे बोलो: मम्मी को SMS भेजो कि मैं देर से आऊँगा।"
        val name = tokens.subList(0, ko).filter { it !in SMS_LEAD }.joinToString(" ").trim()
        var rest = tokens.subList(ko + 1, tokens.size)
        var skip = 0
        while (skip < rest.size && (rest[skip] in SMS_LEAD || rest[skip] in setOf("wala", "वाला", "wali", "वाली", "ek", "एक"))) skip++
        rest = rest.subList(skip, rest.size)
        val body = rest.joinToString(" ").trim()
        if (name.isBlank()) return "किसे SMS भेजना है?"
        if (body.isBlank()) return "SMS में क्या लिखूँ? ऐसे बोलो: मम्मी को SMS भेजो कि मैं देर से आऊँगा।"
        if (!hands.hasContactsPermission()) return "Contacts की अनुमति दे दो, फिर मैं नाम से SMS भेज पाऊँगा।"
        val c = hands.findContact(name) ?: return "$name नाम का contact नहीं मिला।"
        pendingSms = Triple(c.first, c.second, body)
        pendingSmsAt = System.currentTimeMillis()
        return "${c.first} को SMS: $body। भेज दूँ?"
    }

    private fun replySms(ctx: Context, t: String): String {
        if (!has(ctx, Manifest.permission.READ_SMS)) return "SMS पढ़ने की अनुमति चाहिए।"
        val last = inbox(ctx, 1).firstOrNull() ?: return "जवाब देने के लिए कोई SMS नहीं मिला।"
        val m = Regex("(?:^|\\s)(?:ki|कि|that)\\s+(.+)").find(t)
            ?: return "ऐसे बोलो: जवाब दो कि मैं बाद में बात करता हूँ।"
        val body = m.groupValues[1].trim()
        val name = nameFor(ctx, last.address) ?: last.address
        pendingSms = Triple(name, last.address, body)
        pendingSmsAt = System.currentTimeMillis()
        return "$name को जवाब: $body। भेज दूँ?"
    }

    private fun sendSms(ctx: Context, name: String, number: String, body: String): String {
        if (!has(ctx, Manifest.permission.SEND_SMS)) return "SMS भेजने की अनुमति चाहिए। Zyro में पहला बटन दबाकर permission दे दो।"
        return try {
            val sm = SmsManager.getDefault()
            sm.sendMultipartTextMessage(number, null, sm.divideMessage(body), null, null)
            "$name को SMS भेज दिया।"
        } catch (e: Exception) {
            "SMS नहीं जा पाया।"
        }
    }

    // ------------------------------------------------------------------ places and navigation

    private fun places(): JSONObject = try { JSONObject(Prefs.places) } catch (e: Exception) { JSONObject() }

    private fun isHome(t: String) = listOf("ghar", "घर", "home").any { t.contains(strip(it)) }
    private fun isOffice(t: String) = listOf("office", "ऑफिस", "ऑफ़िस", "दफ्तर", "दफ़्तर", "daftar", "work").any { t.contains(strip(it)) }

    private val SAVE_WORDS = setOf("save", "सेव", "karo", "करो", "kar", "कर", "hai", "है", "yaad", "याद", "rakho", "रखो", "set", "सेट", "likho", "लिखो", "ye", "ये", "यह", "yeh", "do", "दो", "isko", "इसको").map { strip(it) }.toSet()

    private fun savePlace(t: String, words: List<String>): String {
        val key = if (isHome(t)) "home" else "office"
        val idxAddr = words.indexOfFirst { it == strip("pata") || it == strip("पता") || it == "address" || it == strip("एड्रेस") }
        var i = idxAddr + 1
        // skip small words after the word "pata"/"address" such as "ka", "save karo", "hai"
        while (i < words.size && (words[i] in SAVE_WORDS || words[i] in setOf("ka", "का", "ke", "के", "ki", "की", "mera", "मेरा", "meri", "मेरी", "ghar", "घर", "home", "office", "ऑफिस", "दफ्तर", "work").map { strip(it) })) i++
        val addr = words.drop(i).joinToString(" ").trim()
        if (addr.length < 3) return "पता भी बोलो। जैसे: घर का पता सेव करो सेक्टर सत्रह चंडीगढ़।"
        val o = places()
        o.put(key, addr)
        Prefs.places = o.toString()
        return "${if (key == "home") "घर" else "ऑफ़िस"} का पता सेव हो गया: $addr।"
    }

    private val NAV_WORDS = setOf(
        "rasta", "रास्ता", "रस्ता", "dikhao", "दिखाओ", "batao", "बताओ", "navigate", "नेविगेट", "navigation", "नेविगेशन", "to", "jana", "जाना", "hai", "है",
        "mujhe", "मुझे", "ko", "को", "ka", "का", "ke", "के", "ki", "की", "le", "ले", "chalo", "चलो", "directions", "डायरेक्शन", "route", "रूट",
        "kaise", "कैसे", "pahunchna", "पहुँचना", "पहुंचना", "pahuchna", "pahunch", "पहुँच", "पहुंच", "kitna", "कितना", "time", "टाइम", "lagega", "लगेगा",
        "samay", "समय", "me", "में", "se", "से", "tak", "तक", "map", "maps", "मैप", "मैप्स", "google", "गूगल", "hey", "micro", "माइक्रो", "please", "plz",
        "kitni", "कितनी", "der", "देर", "mera", "मेरा", "meri", "मेरी", "karo", "करो", "kholo", "खोलो", "chalu", "चालू", "start", "शुरू", "a", "the", "navigate"
    ).map { strip(it) }.toSet()

    private fun navigateTo(hands: Hands, ctx: Context, t: String, words: List<String>): String {
        val saved = places()
        var dest = ""
        var label = ""
        if (isHome(t)) {
            dest = saved.optString("home")
            label = "घर"
            if (dest.isBlank()) return "घर का पता अभी सेव नहीं है। बोलो: घर का पता सेव करो और पता बताओ।"
        } else if (isOffice(t)) {
            dest = saved.optString("office")
            label = "ऑफ़िस"
            if (dest.isBlank()) return "ऑफ़िस का पता अभी सेव नहीं है। बोलो: ऑफ़िस का पता सेव करो और पता बताओ।"
        } else {
            dest = words.filter { it !in NAV_WORDS }.joinToString(" ").trim()
            label = dest
            if (dest.isBlank()) return "कहाँ जाना है? जैसे: कनॉट प्लेस का रास्ता दिखाओ।"
        }
        return try {
            hands.openActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))
                    .setPackage("com.google.android.apps.maps")
            )
            "$label के लिए नेविगेशन चालू कर दिया। पहुँचने का समय Maps में दिख जाएगा।"
        } catch (e: Exception) {
            hands.openUri("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest))
            "$label का रास्ता खोल दिया।"
        }
    }

    // ------------------------------------------------------------------ habits

    /** Asked every minute by the assistant. Returns a question to speak when one of your habits is due. */
    fun habitOffer(): String? {
        val cmd = Habit.due() ?: return null
        pendingHabit = cmd
        pendingHabitAt = System.currentTimeMillis()
        return "${sir()}आप अक्सर इस समय \"$cmd\" कहते हैं। कर दूँ?"
    }
}

/** Remembers which command you say at which hour, on how many days, and offers it again later. */
object Habit {
    private val SKIP = listOf(
        "whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "sms", "एसएमएस", "message", "मैसेज", "call", "कॉल", "otp", "ओटीपी", "routine", "रूटीन",
        "pata", "पता", "address", "password", "पासवर्ड", " ko ", " को "
    )
    private val offered = HashSet<String>()

    private fun load(): JSONObject = try { JSONObject(Prefs.habits) } catch (e: Exception) { JSONObject() }
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun hour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    fun record(text: String) {
        val cmd = text.trim().lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ")
        if (cmd.length < 3 || cmd.split(" ").size > 6) return
        val padded = " $cmd "
        if (SKIP.any { padded.contains(it) }) return
        val key = "${hour()}|$cmd"
        val o = load()
        if (!o.has(key) && o.length() >= 200) return
        val e = o.optJSONObject(key) ?: JSONObject().put("days", JSONArray()).put("no", 0)
        val days = e.getJSONArray("days")
        val d = today()
        for (i in 0 until days.length()) if (days.optString(i) == d) return
        days.put(d)
        if (days.length() > 14) {
            val trimmed = JSONArray()
            for (i in days.length() - 14 until days.length()) trimmed.put(days.optString(i))
            e.put("days", trimmed)
        }
        o.put(key, e)
        Prefs.habits = o.toString()
    }

    fun due(): String? {
        val o = load()
        val h = hour()
        val d = today()
        for (key in o.keys().asSequence().toList()) {
            if (!key.startsWith("$h|")) continue
            val e = o.optJSONObject(key) ?: continue
            if (e.optInt("no") >= 2) continue
            val days = e.optJSONArray("days") ?: continue
            if (days.length() < 3) continue
            var doneToday = false
            for (i in 0 until days.length()) if (days.optString(i) == d) doneToday = true
            if (doneToday || key + d in offered) continue
            offered.add(key + d)
            return key.substringAfter('|')
        }
        return null
    }

    fun decline(cmd: String) {
        val o = load()
        val key = "${hour()}|$cmd"
        val e = o.optJSONObject(key) ?: return
        e.put("no", e.optInt("no") + 1)
        o.put(key, e)
        Prefs.habits = o.toString()
    }

    fun list(): String {
        val o = load()
        val rows = ArrayList<Pair<Int, String>>()
        for (key in o.keys().asSequence().toList()) {
            val e = o.optJSONObject(key) ?: continue
            val n = e.optJSONArray("days")?.length() ?: 0
            if (n >= 2) rows.add(n to "${key.substringBefore('|')} बजे ${key.substringAfter('|')} ($n दिन)")
        }
        if (rows.isEmpty()) return "अभी मैंने आपकी कोई पक्की आदत नहीं पहचानी। कुछ दिन और साथ चलाइए।"
        rows.sortByDescending { it.first }
        return "आपकी आदतें: " + rows.take(4).joinToString("; ") { it.second } + "। यह सब सिर्फ़ आपके फ़ोन में है।"
    }
}
