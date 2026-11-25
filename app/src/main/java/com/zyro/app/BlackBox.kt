package com.zyro.app

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Zyro's black box: an honest record of everything it did, and a real "undo".
 *   "aaj kya kya kiya"          -> reads the last commands back (from the record, never invented)
 *   "undo" / "pichla kaam wapas" -> puts the phone settings back as they were before the last command
 * Before every command it takes a snapshot of volume, ringer, brightness, rotation, Do Not Disturb, Wi-Fi,
 * Bluetooth and torch; a second later it takes another one. What changed is what an undo can restore.
 * Opening an app or sending a message cannot be undone, and Zyro says so.
 */
object BlackBox {

    private class Change(val key: String, val before: Int, val after: Int)
    private class Entry(val at: Long, val said: String, val reply: String, val changes: List<Change>, var undone: Boolean)

    private const val MAX = 100
    private val entries = ArrayList<Entry>()
    private val torch = ConcurrentHashMap<String, Boolean>()
    @Volatile private var inited = false
    private val running = ThreadLocal<Boolean>()

    private val LABEL = mapOf(
        "vol_music" to "म्यूज़िक वॉल्यूम", "vol_ring" to "रिंग वॉल्यूम", "vol_alarm" to "अलार्म वॉल्यूम", "vol_notif" to "नोटिफिकेशन वॉल्यूम",
        "ringer" to "रिंगर मोड", "bright" to "ब्राइटनेस", "brightmode" to "ऑटो ब्राइटनेस", "rotate" to "ऑटो रोटेट",
        "dnd" to "डू नॉट डिस्टर्ब", "wifi" to "वाईफाई", "bt" to "ब्लूटूथ", "torch" to "टॉर्च"
    )

    private fun show(key: String, v: Int): String = when (key) {
        "wifi", "bt", "torch", "rotate", "brightmode" -> if (v == 1) "चालू" else "बंद"
        "ringer" -> when (v) { 0 -> "साइलेंट"; 1 -> "वाइब्रेट"; else -> "नॉर्मल" }
        "dnd" -> if (v <= 1) "बंद" else "चालू"
        "bright" -> "${v * 100 / 255}%"
        else -> v.toString()
    }

    // ------------------------------------------------------------------ entry point

    /** Wraps one command: answers the black-box questions itself, otherwise runs [core] and records what it changed. */
    fun track(hands: Hands, raw: String, core: () -> String?): String? {
        if (running.get() == true) return core()
        val c = (try { hands.service?.applicationContext } catch (e: Exception) { null }) ?: return core()
        init(c)
        query(c, raw)?.let { return it }
        val before = snapshot(c)
        running.set(true)
        val r = try { core() } finally { running.set(false) }
        if (r != null && raw.isNotBlank()) {
            Thread {
                try {
                    Thread.sleep(1100)
                    record(c, raw, r, before, snapshot(c))
                } catch (e: Exception) { /* the record is optional */ }
            }.start()
        }
        return r
    }

    private fun init(c: Context) {
        if (inited) return
        inited = true
        load(c)
        try {
            val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torch[cameraId] = enabled }
            }, null)
        } catch (e: Exception) { /* no torch */ }
    }

    // ------------------------------------------------------------------ snapshot, restore

    private fun snapshot(c: Context): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            m["vol_music"] = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            m["vol_ring"] = am.getStreamVolume(AudioManager.STREAM_RING)
            m["vol_alarm"] = am.getStreamVolume(AudioManager.STREAM_ALARM)
            m["vol_notif"] = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            m["ringer"] = am.ringerMode
        } catch (e: Exception) { /* skip */ }
        try { m["bright"] = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (e: Exception) { /* skip */ }
        try { m["brightmode"] = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) } catch (e: Exception) { /* skip */ }
        try { m["rotate"] = Settings.System.getInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION) } catch (e: Exception) { /* skip */ }
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            m["dnd"] = nm.currentInterruptionFilter
        } catch (e: Exception) { /* skip */ }
        try {
            val w = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            m["wifi"] = if (w.isWifiEnabled) 1 else 0
        } catch (e: Exception) { /* skip */ }
        try {
            val b = BluetoothAdapter.getDefaultAdapter()
            if (b != null) m["bt"] = if (b.isEnabled) 1 else 0
        } catch (e: Exception) { /* skip */ }
        if (torch.isNotEmpty()) m["torch"] = if (torch.values.any { it }) 1 else 0
        return m
    }

    @Suppress("DEPRECATION")
    private fun restore(c: Context, key: String, v: Int): Boolean = try {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (key) {
            "vol_music" -> { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); true }
            "vol_ring" -> { am.setStreamVolume(AudioManager.STREAM_RING, v, 0); true }
            "vol_alarm" -> { am.setStreamVolume(AudioManager.STREAM_ALARM, v, 0); true }
            "vol_notif" -> { am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, v, 0); true }
            "ringer" -> { am.ringerMode = v; true }
            "bright" -> Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            "brightmode" -> Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, v)
            "rotate" -> Settings.System.putInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION, v)
            "dnd" -> {
                val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.setInterruptionFilter(v)
                true
            }
            "wifi" -> {
                val w = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                w.setWifiEnabled(v == 1)
            }
            "bt" -> {
                val b = BluetoothAdapter.getDefaultAdapter()
                if (b == null) false else if (v == 1) b.enable() else b.disable()
            }
            "torch" -> {
                val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                for (id in torch.keys) cm.setTorchMode(id, v == 1)
                torch.isNotEmpty()
            }
            else -> false
        }
    } catch (e: Exception) { false }

    // ------------------------------------------------------------------ the record

    private fun record(c: Context, said: String, reply: String, before: Map<String, Int>, after: Map<String, Int>) {
        val ch = ArrayList<Change>()
        for ((k, b) in before) {
            val a = after[k] ?: continue
            if (a != b) ch.add(Change(k, b, a))
        }
        val s = said.trim().take(80)
        synchronized(entries) {
            val last = entries.lastOrNull()
            if (last != null && last.said == s && System.currentTimeMillis() - last.at < 2500L) {
                if (last.changes.isEmpty() && ch.isNotEmpty()) entries[entries.size - 1] = Entry(last.at, last.said, last.reply, ch, false)
            } else {
                entries.add(Entry(System.currentTimeMillis(), s, reply.take(120), ch, false))
                while (entries.size > MAX) entries.removeAt(0)
            }
        }
        save(c)
    }

    private fun file(c: Context) = File(c.filesDir, "blackbox.json")

    private fun load(c: Context) {
        try {
            val f = file(c)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            synchronized(entries) {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val ch = ArrayList<Change>()
                    val ca = o.optJSONArray("c")
                    if (ca != null) {
                        for (k in 0 until ca.length()) {
                            val x = ca.optJSONObject(k) ?: continue
                            ch.add(Change(x.optString("k"), x.optInt("b"), x.optInt("a")))
                        }
                    }
                    entries.add(Entry(o.optLong("t"), o.optString("s"), o.optString("r"), ch, o.optBoolean("u")))
                }
            }
        } catch (e: Exception) { /* start with an empty record */ }
    }

    private fun save(c: Context) {
        try {
            val a = JSONArray()
            synchronized(entries) {
                for (en in entries) {
                    val ca = JSONArray()
                    for (x in en.changes) ca.put(JSONObject().put("k", x.key).put("b", x.before).put("a", x.after))
                    a.put(JSONObject().put("t", en.at).put("s", en.said).put("r", en.reply).put("c", ca).put("u", en.undone))
                }
            }
            file(c).writeText(a.toString())
        } catch (e: Exception) { /* ignore */ }
    }

    // ------------------------------------------------------------------ questions

    private fun query(c: Context, raw: String): String? {
        val t = Local.strip(raw.lowercase(Locale.ROOT))
        fun has(vararg w: String) = w.any { t.contains(Local.strip(it)) }
        if (has("undo", "अनडू", "pichla kaam wapas", "पिछला काम वापस", "pehle jaisa kar do", "पहले जैसा कर दो", "ulta kar do", "उल्टा कर दो")) return undo(c)
        if (has("kya kya kiya", "क्या क्या किया", "aaj kya kiya", "आज क्या किया", "action log", "black box", "blackbox", "pichle kaam", "पिछले काम", "abhi kya kiya", "अभी क्या किया")) return history()
        return null
    }

    private fun history(): String {
        val list = synchronized(entries) { entries.takeLast(6).reversed() }
        if (list.isEmpty()) return "अभी तक मेरे रिकॉर्ड में कोई काम नहीं है।"
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sb = StringBuilder("मैंने ये किया, नया पहले: ")
        list.forEachIndexed { i, en ->
            sb.append(i + 1).append(". ").append(f.format(Date(en.at))).append(" ").append(en.said.take(40))
            if (en.changes.isNotEmpty()) sb.append(" (").append(en.changes.joinToString(", ") { LABEL[it.key] ?: it.key }).append(" बदला)")
            if (en.undone) sb.append(" [वापस किया]")
            sb.append("। ")
        }
        return sb.toString().trim()
    }

    private fun undo(c: Context): String {
        val last = synchronized(entries) { entries.lastOrNull { !it.undone } }
            ?: return "वापस करने के लिए कोई पिछला काम मेरे रिकॉर्ड में नहीं है।"
        if (last.changes.isEmpty()) return "पिछला काम '${last.said.take(40)}' ऐसा था जो वापस नहीं हो सकता, जैसे ऐप खोलना या मैसेज भेजना।"
        val done = ArrayList<String>()
        val failed = ArrayList<String>()
        for (x in last.changes) {
            val label = LABEL[x.key] ?: x.key
            if (restore(c, x.key, x.before)) done.add("$label ${show(x.key, x.after)} से ${show(x.key, x.before)}") else failed.add(label)
        }
        last.undone = true
        save(c)
        val sb = StringBuilder()
        if (done.isNotEmpty()) sb.append("ठीक है, '${last.said.take(40)}' वापस कर दिया: ").append(done.joinToString(", ")).append("।")
        if (failed.isNotEmpty()) sb.append(" ये वापस नहीं हो पाए: ").append(failed.joinToString(", ")).append("।")
        return sb.toString().trim()
    }
}
