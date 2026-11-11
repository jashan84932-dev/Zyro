package com.zyro.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Safety and health helpers: emergency alert with location, sharing location, "where am I",
 * water tracker, step counter and sharing the last answer.
 * Zyro is a helper, it does not replace calling 112 in a real emergency.
 */
object Safety {
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile var lastSpoken: String = ""

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun s(x: String) = Local.strip(x)

    private fun say(text: String) {
        val c = appCtx ?: return
        Assistant.get(c).announce(text)
    }

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ location

    internal fun fresh(ctx: Context): Location? {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        fun consider(p: String) {
            try {
                val l = lm.getLastKnownLocation(p) ?: return
                if (best == null || l.time > best!!.time) best = l
            } catch (e: Exception) {
                // provider not available
            }
        }
        consider(LocationManager.GPS_PROVIDER)
        consider(LocationManager.NETWORK_PROVIDER)
        consider(LocationManager.PASSIVE_PROVIDER)
        val recent = best != null && System.currentTimeMillis() - best!!.time < 3 * 60_000L
        if (recent) return best
        // ask for one new fix, wait a few seconds (this runs on a worker thread)
        val latch = CountDownLatch(1)
        var got: Location? = null
        val ls = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                got = l
                latch.countDown()
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
                main.post {
                    try { lm.requestSingleUpdate(provider, ls, Looper.getMainLooper()) } catch (e: Exception) { latch.countDown() }
                }
                latch.await(9, TimeUnit.SECONDS)
                main.post { try { lm.removeUpdates(ls) } catch (e: Exception) { /* ignore */ } }
            }
        } catch (e: Exception) {
            // use the old fix
        }
        return got ?: best
    }

    internal fun link(l: Location) = "https://maps.google.com/?q=${l.latitude},${l.longitude}"

    private fun whereAmI(ctx: Context): String {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) return "लोकेशन की अनुमति चाहिए। Zyro में पहला बटन दबाकर दे दो।"
        val l = fresh(ctx) ?: return "लोकेशन नहीं मिल रही। फ़ोन की Location चालू करो और खुले में आओ।"
        val addr = try {
            Geocoder(ctx, Locale("hi", "IN")).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.getAddressLine(0)
        } catch (e: Exception) {
            null
        }
        return if (addr != null) "आप यहाँ हैं: $addr।" else "आपकी लोकेशन है अक्षांश ${"%.4f".format(Locale.US, l.latitude)}, देशांतर ${"%.4f".format(Locale.US, l.longitude)}। पता नहीं मिल पाया।"
    }

    private fun shareLocation(hands: Hands, ctx: Context, t: String): String {
        val m = Regex("(\\S+(?:\\s\\S+)?)\\s+(?:ko|को)\\s+(?:meri|मेरी|apni|अपनी)?\\s*(?:location|लोकेशन)").find(t)
            ?: Regex("(?:location|लोकेशन)\\s+(\\S+(?:\\s\\S+)?)\\s+(?:ko|को)").find(t)
        val raw = m?.groupValues?.get(1)?.trim() ?: return "ऐसे बोलो: मेरी लोकेशन मम्मी को भेजो।"
        val name = raw.split(" ").filter { it !in setOf("meri", "मेरी", "apni", "अपनी", "hey", "micro", "माइक्रो").map { x -> s(x) } }.joinToString(" ").trim()
        if (name.isBlank()) return "किसे लोकेशन भेजनी है?"
        if (!hands.hasContactsPermission()) return "Contacts की अनुमति दे दो।"
        val c = hands.findContact(name) ?: return "$name नाम का contact नहीं मिला।"
        val l = fresh(ctx) ?: return "लोकेशन नहीं मिल रही। फ़ोन की Location चालू करो।"
        Comms.stageSms(c.first, c.second, "मेरी लोकेशन: ${link(l)}")
        return "${c.first} को अपनी लोकेशन का लिंक भेजूँ? हाँ या नहीं बोलो।"
    }

    // ------------------------------------------------------------------ emergency alert

    private var emToken = 0
    @Volatile private var emWaiting = false

    private fun contacts(): JSONArray = try { JSONArray(Prefs.emergency) } catch (e: Exception) { JSONArray() }

    private fun emergencyStart(): String {
        val a = contacts()
        if (a.length() == 0) return "पहले इमरजेंसी कॉन्टैक्ट तय करो। बोलो, इमरजेंसी कॉन्टैक्ट सेट करो मम्मी।"
        val names = (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("n") }
        val token = ++emToken
        emWaiting = true
        main.postDelayed({
            if (token == emToken && emWaiting) {
                emWaiting = false
                Thread { emergencySend() }.start()
            }
        }, 10_000)
        return "इमरजेंसी अलर्ट 10 सेकंड में $names को लोकेशन के साथ जाएगा और पहले नंबर पर कॉल होगी। गलती से बोला हो तो अभी बोलो, रुको या कैंसिल। असली खतरे में 112 पर भी कॉल करो।"
    }

    private fun emergencySend() {
        val ctx = appCtx ?: return
        val a = contacts()
        if (a.length() == 0) return
        val l = fresh(ctx)
        val body = "आपातकालीन अलर्ट: मुझे मदद की ज़रूरत है। " + if (l != null) "मेरी लोकेशन: ${link(l)}" else "मेरी लोकेशन नहीं मिल पाई, कृपया मुझे कॉल करें।"
        var sent = 0
        if (has(ctx, Manifest.permission.SEND_SMS)) {
            for (i in 0 until a.length()) {
                try {
                    val sm = SmsManager.getDefault()
                    sm.sendMultipartTextMessage(a.getJSONObject(i).optString("num"), null, sm.divideMessage(body), null, null)
                    sent++
                } catch (e: Exception) {
                    // try the next contact
                }
            }
        }
        say(if (sent > 0) "इमरजेंसी अलर्ट $sent लोगों को भेज दिया।" else "SMS नहीं जा पाया, SMS की अनुमति देखो।")
        try {
            val first = a.getJSONObject(0)
            Assistant.get(ctx).hands.callNumber(first.optString("num"))
        } catch (e: Exception) {
            // call is optional
        }
    }

    private fun setEmergencyContact(hands: Hands, t: String): String {
        val name = t.split(Regex("\\s+")).filter { it !in setOf("emergency", "इमरजेंसी", "contact", "कॉन्टैक्ट", "कांटेक्ट", "set", "सेट", "karo", "करो", "add", "जोड़ो", "hey", "micro", "माइक्रो", "ka", "का", "ko", "को", "banao", "बनाओ").map { x -> s(x) } }.joinToString(" ").trim()
        if (name.isBlank()) return "किसे इमरजेंसी कॉन्टैक्ट बनाना है? बोलो, इमरजेंसी कॉन्टैक्ट सेट करो मम्मी।"
        if (!hands.hasContactsPermission()) return "Contacts की अनुमति दे दो।"
        val c = hands.findContact(name) ?: return "$name नाम का contact नहीं मिला।"
        val a = contacts()
        val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("num") != c.second) keep.put(a.get(i))
        keep.put(JSONObject().put("n", c.first).put("num", c.second))
        val out = JSONArray()
        for (i in maxOf(0, keep.length() - 3) until keep.length()) out.put(keep.get(i))
        Prefs.emergency = out.toString()
        return "${c.first} को इमरजेंसी कॉन्टैक्ट बना दिया। कुल ${out.length()} कॉन्टैक्ट हैं।"
    }

    fun handlePending(hands: Hands, t: String, words: List<String>): String? {
        if (!emWaiting || words.size > 5) return null
        val stop = listOf("ruko", "रुको", "rukiye", "रुकिए", "cancel", "कैंसिल", "nahi", "नहीं", "stop", "galti", "गलती", "mat", "ग़लती").map { s(it) }
        if (words.any { it in stop }) {
            emWaiting = false
            emToken++
            return "ठीक है, इमरजेंसी अलर्ट रद्द कर दिया। आप ठीक हैं ना?"
        }
        return null
    }

    // ------------------------------------------------------------------ water and steps

    private fun today(): String {
        val c = Calendar.getInstance()
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun water(t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        if (!any("paani", "पानी", "pani")) return null
        val d = try { JSONObject(Prefs.water) } catch (e: Exception) { JSONObject() }
        val td = today()
        val asking = any("kitna", "कितना", "kitne", "कितने", "total", "hisab", "हिसाब")
        if (any("piya", "पिया", "pi liya", "पी लिया", "piye", "पिये", "peeya", "पीया") && !asking) {
            val n = words.firstNotNullOfOrNull { w -> (w.toIntOrNull() ?: Local.numberOf(w))?.takeIf { it in 1..12 } } ?: 1
            val total = d.optInt(td) + n
            d.put(td, total)
            val keys = ArrayList<String>()
            val it = d.keys()
            while (it.hasNext()) keys.add(it.next())
            keys.sort()
            while (keys.size > 10) d.remove(keys.removeAt(0))
            Prefs.water = d.toString()
            return if (total >= 8) "शाबाश! आज $total गिलास हो गए, लक्ष्य पूरा।" else "लिख लिया। आज $total गिलास हुए, ${8 - total} और पी लो।"
        }
        if (asking) {
            val n = d.optInt(td)
            return if (n == 0) "आज अभी तक कोई गिलास दर्ज नहीं है। पानी पीकर बोलो, एक गिलास पानी पिया।" else "आज तुमने $n गिलास पानी पिया, लक्ष्य 8 का है।"
        }
        return null
    }

    private fun readSteps(ctx: Context): Float? {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val latch = CountDownLatch(1)
        var value: Float? = null
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                value = e.values[0]
                latch.countDown()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        latch.await(3, TimeUnit.SECONDS)
        sm.unregisterListener(l)
        return value
    }

    private fun steps(ctx: Context): String {
        if (ctx.getSystemService(Context.SENSOR_SERVICE).let { (it as SensorManager).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } == null) return "इस फ़ोन में स्टेप सेंसर नहीं है।"
        if (android.os.Build.VERSION.SDK_INT >= 29 && !has(ctx, "android.permission.ACTIVITY_RECOGNITION")) return "कदम गिनने के लिए Physical activity की अनुमति चाहिए। Zyro में पहला बटन दबाकर दे दो।"
        val v = readSteps(ctx) ?: return "अभी कदम नहीं पढ़ पाया, थोड़ी देर बाद कोशिश करो।"
        val parts = Prefs.stepBase.split(":")
        var base = if (parts.size == 2 && parts[0] == today()) parts[1].toFloatOrNull() ?: v else v
        if (v < base) base = 0f
        if (parts.size != 2 || parts[0] != today()) Prefs.stepBase = "${today()}:$v"
        val n = (v - base).toInt()
        return "आज अब तक लगभग $n कदम चले, यानी करीब ${"%.1f".format(Locale.US, n * 0.0007)} किलोमीटर। (गिनती तब से है जब से Zyro ने आज का पहला रीडिंग लिया)"
    }

    /** Once a minute: remember the step counter at the start of each day. */
    fun tick() {
        val ctx = appCtx ?: return
        val parts = Prefs.stepBase.split(":")
        if (parts.size == 2 && parts[0] == today()) return
        if (android.os.Build.VERSION.SDK_INT >= 29 && !has(ctx, "android.permission.ACTIVITY_RECOGNITION")) return
        Prefs.stepBase = "${today()}:pending"
        Thread {
            val v = try { readSteps(ctx) } catch (e: Exception) { null }
            Prefs.stepBase = if (v != null) "${today()}:$v" else ""
        }.start()
    }

    // ------------------------------------------------------------------ sharing

    private fun share(hands: Hands): String {
        val text = lastSpoken
        if (text.isBlank()) return "शेयर करने के लिए अभी कुछ नहीं है।"
        return try {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            hands.openActivity(Intent.createChooser(send, "Zyro से शेयर").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "शेयर का मेन्यू खोल दिया। ऐप चुन लो।"
        } catch (e: Exception) {
            "शेयर नहीं खुल पाया।"
        }
    }

    // ------------------------------------------------------------------ teach Zyro your own answers

    private var inMacro = false

    private fun macros(): JSONArray = try { JSONArray(Prefs.macros) } catch (e: Exception) { JSONArray() }

    private fun teach(raw: String, t: String): String? {
        val m = Regex("(?:jab|जब)\\s+(?:main|मैं|mai|मैंने)?\\s*(.+?)\\s+(?:bolu|bolun|बोलूँ|बोलूं|boloon|kahu|kahun|कहूँ|कहूं|bol dun|बोल दूँ)\\s+(?:to|तो|toh)\\s+(.+)$").find(t)
            ?: return "ऐसे सिखाओ: सीखो, जब मैं गुड मॉर्निंग बोलूँ तो बोलो सुप्रभात सर।"
        val q = m.groupValues[1].trim().trim('"', '\'')
        var a = m.groupValues[2].trim().trim('"', '\'')
        a = a.replace(Regex("\\s+(?:bolna|बोलना|kehna|कहना|bolo|बोलो|kahna)$"), "").trim()
        a = a.replace(Regex("^(?:bolo|बोलो|kaho|कहो)\\s+"), "").trim()
        if (q.length < 2 || a.isBlank()) return "ऐसे सिखाओ: सीखो, जब मैं गुड मॉर्निंग बोलूँ तो बोलो सुप्रभात सर।"
        val arr = macros()
        val keep = JSONArray()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("q") != q) keep.put(arr.get(i))
        keep.put(JSONObject().put("q", q).put("a", a))
        val out = JSONArray()
        for (i in maxOf(0, keep.length() - 40) until keep.length()) out.put(keep.get(i))
        Prefs.macros = out.toString()
        return "सीख लिया। जब तुम '$q' बोलोगे, मैं जवाब दूँगा: $a"
    }

    private fun macroAnswer(hands: Hands, t: String): String? {
        if (inMacro) return null
        val arr = macros()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val q = s(o.optString("q"))
            if (q.isBlank()) continue
            if (t == q || (q.length >= 6 && t.contains(q))) {
                val a = o.optString("a")
                inMacro = true
                try {
                    // the answer can also be a phone command, for example "torch jalao"
                    return Local.handle(hands, a) ?: a
                } finally {
                    inMacro = false
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ voice commands

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(s(it)) }
        fun word(vararg w: String) = w.any { x -> words.contains(s(x)) }

        // your own taught answers
        if (word("sikho", "सीखो", "seekho", "सीख", "sikh", "सिखो")) {
            if (any("jab", "जब")) return teach(raw, t)
        }
        if (any("sikhaya hua", "सिखाया हुआ", "macros") && any("bhool", "भूल", "hatao", "हटाओ", "delete")) {
            Prefs.macros = "[]"
            return "सिखाई हुई सारी बातें भूल गया।"
        }
        if (any("kya kya sikhaya", "क्या क्या सिखाया", "macros batao", "सिखाई हुई बातें")) {
            val a = macros()
            if (a.length() == 0) return "अभी तुमने मुझे कुछ नहीं सिखाया।"
            return "तुमने सिखाया है: " + (0 until a.length()).joinToString("। ") { "${a.getJSONObject(it).optString("q")} पर ${a.getJSONObject(it).optString("a")}" }
        }
        macroAnswer(hands, t)?.let { return it }

        // find my phone by SMS: set the secret code
        if (any("find my phone", "फाइंड माय फोन", "phone khoya", "फोन खोया", "sms se phone", "एसएमएस से फोन") && any("code", "कोड")) {
            val code = words.firstOrNull { it.length >= 4 && it.all { c -> c.isLetterOrDigit() } && it.any { c -> c.isDigit() } && !it.startsWith("sms") }
            return if (code == null) {
                "कम से कम 4 अंकों का गुप्त कोड बोलो, जैसे: फाइंड माय फोन कोड 4829।"
            } else {
                Prefs.findCode = code
                "ठीक है। अब किसी भी फ़ोन से मेरे नंबर पर 'micro find $code' लिखकर SMS भेजो, मेरा फ़ोन ज़ोर से बजेगा और लोकेशन भेजेगा। रोकने के लिए 'micro stop $code'।"
            }
        }

        // emergency
        if (any("emergency contact", "इमरजेंसी कॉन्टैक्ट", "इमरजेंसी कांटेक्ट", "इमरजेंसी contact", "emergency number", "इमरजेंसी नंबर")) {
            if (any("batao", "बताओ", "list", "लिस्ट", "kaun", "कौन")) {
                val a = contacts()
                return if (a.length() == 0) "अभी कोई इमरजेंसी कॉन्टैक्ट नहीं है।" else "इमरजेंसी कॉन्टैक्ट: " + (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("n") }
            }
            if (any("hatao", "हटाओ", "delete", "मिटाओ", "clear")) {
                Prefs.emergency = "[]"
                return "सारे इमरजेंसी कॉन्टैक्ट हटा दिए।"
            }
            return setEmergencyContact(hands, t)
        }
        if (word("bachao", "बचाओ") || ((word("emergency", "इमरजेंसी", "sos", "एसओएस")) && any("alert", "अलर्ट", "bhejo", "भेजो", "help", "madad", "मदद", "chahiye", "चाहिए")) && !word("flash", "फ़्लैश", "फ्लैश", "torch", "टॉर्च")) {
            return emergencyStart()
        }

        // location
        if (any("location", "लोकेशन") && any("bhejo", "भेजो", "share", "शेयर", "send", "भेज")) return shareLocation(hands, ctx, t)
        if (any("main kahan hoon", "मैं कहाँ हूँ", "main kahan hun", "mai kahan hu", "मैं कहां हूं", "ye kaun si jagah", "ये कौन सी जगह", "meri location kya", "मेरी लोकेशन क्या", "mera pata", "मेरा पता")) return whereAmI(ctx)

        // water and steps
        water(t, words)?.let { return it }
        if (any("kitne kadam", "कितने कदम", "kitne step", "कितने स्टेप", "steps", "स्टेप्स", "kadam chale", "कदम चले", "कितने क़दम") || (word("step", "स्टेप") && any("kitne", "कितने", "aaj", "आज"))) return steps(ctx)

        // share the last answer
        if (word("share", "शेयर") && !any("location", "लोकेशन")) return share(hands)
        return null
    }
}
