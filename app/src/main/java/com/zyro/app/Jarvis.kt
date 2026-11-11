package com.zyro.app

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "Jarvis" extras: system report, welcome greeting, guard (anti-theft) mode,
 * reading new messages aloud (driving mode) and remembering where you parked.
 */
object Jarvis {

    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun strip(s: String) = Local.strip(s)
    private fun sir() = if (Prefs.jarvisMode) "सर" else "दोस्त"

    private fun has(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ small readers

    private fun batteryInfo(ctx: Context): Triple<Int, Boolean, Double> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return Triple(-1, false, 0.0)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return Triple(pct, charging, temp)
    }

    private fun timeText(): String = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())

    // ------------------------------------------------------------------ greeting and day summary

    fun greeting(ctx: Context): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = when {
            h < 12 -> "सुप्रभात"
            h < 17 -> "शुभ दोपहर"
            else -> "शुभ संध्या"
        }
        val (pct, charging, _) = batteryInfo(ctx)
        val sb = StringBuilder("$hello ${sir()}। वापस स्वागत है। अभी ${timeText()} हो रहे हैं।")
        if (pct >= 0) {
            sb.append(" बैटरी $pct प्रतिशत है")
            sb.append(if (charging) " और चार्ज हो रही है।" else ".")
            if (pct in 0..20 && !charging) sb.append(" चार्जर लगाना बेहतर रहेगा।")
        }
        return sb.toString()
    }

    /** Extra lines for the morning briefing: next alarm, shopping list, notes, today's spending. */
    fun dayExtras(ctx: Context): String {
        val sb = StringBuilder()
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.nextAlarmClock?.let {
                sb.append("अगला अलार्म ").append(SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(it.triggerTime))).append(" पर है। ")
            }
        } catch (e: Exception) {
            // ignore
        }
        try {
            val shop = JSONObject(Prefs.lists).optJSONArray("शॉपिंग")
            if (shop != null && shop.length() > 0) sb.append("शॉपिंग लिस्ट में ${shop.length()} चीज़ें हैं। ")
            val notes = JSONArray(Prefs.notes)
            if (notes.length() > 0) sb.append("आपके ${notes.length()} नोट्स सेव हैं। ")
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val ex = JSONArray(Prefs.expenses)
            var total = 0
            for (i in 0 until ex.length()) {
                val o = ex.optJSONObject(i) ?: continue
                if (o.optLong("ts") >= cal.timeInMillis) total += o.optInt("a")
            }
            if (total > 0) sb.append("आज अब तक $total रुपये खर्च हुए हैं। ")
        } catch (e: Exception) {
            // ignore
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ system report

    private fun systemReport(ctx: Context): String {
        val (pct, charging, temp) = batteryInfo(ctx)
        val warnings = ArrayList<String>()
        val parts = ArrayList<String>()
        if (pct >= 0) {
            parts.add("बैटरी $pct प्रतिशत${if (charging) " (चार्ज हो रही है)" else ""}")
            if (pct <= 20 && !charging) warnings.add("बैटरी कम है")
        }
        if (temp > 0) {
            parts.add("तापमान ${temp.toInt()} डिग्री")
            if (temp >= 42) warnings.add("फ़ोन काफ़ी गर्म है, उसे ठंडा होने दीजिए")
        }
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val free = (mi.availMem * 100 / mi.totalMem).toInt()
            parts.add("रैम $free प्रतिशत खाली")
            if (free < 12) warnings.add("रैम बहुत कम बची है, कुछ ऐप बंद कर दीजिए")
        } catch (e: Exception) {
            // ignore
        }
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            val freeGb = st.availableBytes / 1_000_000_000.0
            parts.add(String.format(Locale.ENGLISH, "स्टोरेज %.1f जीबी खाली", freeGb))
            if (freeGb < 1.5) warnings.add("स्टोरेज लगभग भर गया है")
        } catch (e: Exception) {
            // ignore
        }
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val n = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            val net = if (n != null && n.isConnected) {
                if (n.type == ConnectivityManager.TYPE_WIFI) "वाई-फ़ाई" else "मोबाइल डेटा"
            } else null
            parts.add("इंटरनेट: ${net ?: "नहीं है"}")
            if (net == null) warnings.add("इंटरनेट नहीं है")
        } catch (e: Exception) {
            // ignore
        }
        val hours = SystemClock.elapsedRealtime() / 3_600_000
        if (hours >= 1) parts.add("फ़ोन $hours घंटे से चालू है")
        val acc = accessibilityOn(ctx)
        if (!acc) warnings.add("Accessibility बंद है, इसलिए मैं स्क्रीन पर कुछ नहीं कर पाऊँगा")

        val head = "${sir()}, सिस्टम रिपोर्ट: " + parts.joinToString(", ") + "।"
        return if (warnings.isEmpty()) "$head सब कुछ सामान्य है।" else "$head ध्यान दीजिए: " + warnings.joinToString("; ") + "।"
    }

    private fun accessibilityOn(ctx: Context): Boolean {
        val s = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: return false
        return s.contains(ctx.packageName)
    }

    // ------------------------------------------------------------------ guard (anti-theft) mode

    private var sm: SensorManager? = null
    private var sensorListener: SensorEventListener? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var player: MediaPlayer? = null
    @Volatile private var armed = false
    @Volatile private var ringing = false
    private val stopRunnable = Runnable { stopAlarm() }

    private fun arm(ctx: Context): String {
        if (armed) return "गार्ड मोड पहले से चालू है।"
        val manager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return "इस फ़ोन में मोशन सेंसर नहीं मिला।"
        sm = manager
        val armAt = System.currentTimeMillis() + 10_000
        val l = object : SensorEventListener {
            private var lx = 0f
            private var ly = 0f
            private var lz = 0f
            private var init = false
            override fun onSensorChanged(e: SensorEvent) {
                val x = e.values[0]
                val y = e.values[1]
                val z = e.values[2]
                if (init && System.currentTimeMillis() > armAt) {
                    val d = Math.abs(x - lx) + Math.abs(y - ly) + Math.abs(z - lz)
                    if (d > 3.5f && !ringing) triggerAlarm(ctx)
                }
                lx = x; ly = y; lz = z
                init = true
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorListener = l
        manager.registerListener(l, acc, SensorManager.SENSOR_DELAY_NORMAL)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                disarm(ctx)
            }
        }
        unlockReceiver = r
        try { ctx.registerReceiver(r, IntentFilter(Intent.ACTION_USER_PRESENT)) } catch (e: Exception) { /* ignore */ }
        armed = true
        return "गार्ड मोड दस सेकंड में चालू होगा। इसके बाद फ़ोन को हिलाया या उठाया गया तो तेज़ अलार्म बजेगा। फ़ोन अनलॉक करने पर या मेरे कहने पर बंद होगा।"
    }

    private fun disarm(ctx: Context): String {
        val was = armed || ringing
        stopAlarm()
        try { sensorListener?.let { sm?.unregisterListener(it) } } catch (e: Exception) { /* ignore */ }
        sensorListener = null
        try { unlockReceiver?.let { ctx.unregisterReceiver(it) } } catch (e: Exception) { /* ignore */ }
        unlockReceiver = null
        armed = false
        return if (was) "गार्ड मोड बंद कर दिया।" else "गार्ड मोड चालू नहीं था।"
    }

    private fun triggerAlarm(ctx: Context) {
        ringing = true
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val mp = MediaPlayer()
            mp.setDataSource(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(AudioManager.STREAM_ALARM)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            // no sound possible
        }
        main.postDelayed(stopRunnable, 120_000)
    }

    private fun stopAlarm() {
        main.removeCallbacks(stopRunnable)
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) {
            // ignore
        }
        player = null
        ringing = false
    }

    // ------------------------------------------------------------------ remember where I parked

    private fun bestLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } catch (e: Exception) { null }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        return best
    }

    private fun parkSave(ctx: Context): String {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) {
            return "लोकेशन की अनुमति चाहिए। Zyro में पहला बटन दबाकर permission दे दो।"
        }
        val l = bestLocation(ctx) ?: return "लोकेशन नहीं मिली। लोकेशन चालू करके एक बार Google Maps खोलो, फिर दोबारा बोलो।"
        Prefs.park = "${l.latitude},${l.longitude},${System.currentTimeMillis()}"
        val ageMin = (System.currentTimeMillis() - l.time) / 60_000
        return "गाड़ी की जगह याद कर ली।" + if (ageMin > 15) " ध्यान दें, यह लोकेशन $ageMin मिनट पुरानी है।" else ""
    }

    private fun parkFind(hands: Hands): String {
        val p = Prefs.park.split(",")
        if (p.size < 3) return "मुझे अभी कोई जगह याद नहीं है। पहले बोलो: गाड़ी यहाँ पार्क की है।"
        return try {
            hands.openActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p[0]},${p[1]}&mode=w"))
                    .setPackage("com.google.android.apps.maps")
            )
            "गाड़ी तक पैदल रास्ता खोल दिया।"
        } catch (e: Exception) {
            hands.openUri("https://www.google.com/maps/dir/?api=1&travelmode=walking&destination=${p[0]},${p[1]}")
            "गाड़ी तक का रास्ता खोल दिया।"
        }
    }

    // ------------------------------------------------------------------ voice entry

    fun handle(hands: Hands, t: String, words: List<String>): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        fun word(vararg w: String) = w.any { x -> words.contains(strip(x)) }

        // system report
        if (words.size <= 6 && any("system check", "सिस्टम चेक", "status report", "स्टेटस रिपोर्ट", "diagnostic", "डायग्नोस्टिक", "sab theek hai", "सब ठीक है", "phone ki halat", "फोन की हालत", "फ़ोन की हालत", "health check", "सिस्टम रिपोर्ट", "system report")) {
            return systemReport(ctx)
        }

        // guard mode
        if (any("guard mode", "गार्ड मोड", "suraksha mode", "सुरक्षा मोड", "anti theft", "एंटी थेफ्ट", "chor alarm", "चोर अलार्म", "theft alarm")) {
            val off = word("off", "band", "disarm", "stop") || any("बंद", "हटाओ", "बन्द")
            return if (off) disarm(ctx) else arm(ctx)
        }
        if (ringing && (any("alarm band", "अलार्म बंद", "alarm off", "अलार्म बन्द", "बंद करो", "band karo") || word("stop"))) {
            return disarm(ctx)
        }

        // read new messages aloud
        if (any("driving mode", "ड्राइविंग मोड", "message khud padho", "मैसेज खुद पढ़ो", "मैसेज खुद पढो", "नोटिफिकेशन खुद पढ़ो", "नोटिफिकेशन खुद पढो", "notification khud padho", "read messages aloud", "msg khud padho")) {
            val off = word("off", "band", "stop", "disable") || any("बंद", "बन्द", "हटाओ")
            Prefs.readAloud = !off
            return if (off) "ड्राइविंग मोड बंद। अब नए मैसेज खुद नहीं पढ़ूँगा."
            else "ड्राइविंग मोड चालू। अब नए WhatsApp और SMS मैसेज आते ही मैं बोलकर सुना दूँगा। Notification access ON होना ज़रूरी है।"
        }

        // parking
        val vehicle = any("gaadi", "गाड़ी", "gadi", "गाडी", "car", "bike", "बाइक", "scooter", "स्कूटर", "vehicle")
        if (vehicle && any("kahan", "कहाँ", "कहां", "kidhar", "किधर", "kaha", "कहा", "where")) return parkFind(hands)
        if (vehicle && any("park", "पार्क", "khadi", "खड़ी", "yahan", "यहाँ", "यहां", "rakhi", "रखी", "yaad", "याद")) return parkSave(ctx)
        return null
    }
}
