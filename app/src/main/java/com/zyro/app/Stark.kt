package com.zyro.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale

/** Tony Stark style extras: HUD screen, protocols, mission report and proactive weather warnings. */
object Stark {
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun s(x: String) = Local.strip(x)

    private fun sir() = if (Prefs.jarvisMode) "सर, " else ""

    private fun today(): String {
        val c = Calendar.getInstance()
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    // ------------------------------------------------------------------ protocols

    private fun partyProtocol(hands: Hands): String {
        Thread {
            try {
                hands.volume("max")
                Local.handle(hands, "youtube par party songs search karo")
                Thread.sleep(2500)
                for (i in 0 until 6) {
                    hands.torch(true)
                    Thread.sleep(250)
                    hands.torch(false)
                    Thread.sleep(250)
                }
            } catch (e: Exception) {
                // the party goes on anyway
            }
        }.start()
        return "हाउस पार्टी प्रोटोकॉल सक्रिय। आवाज़ फुल, पार्टी का म्यूज़िक और लाइट शो शुरू!"
    }

    private fun cleanSlate(hands: Hands): String {
        Thread {
            try {
                Local.handle(hands, "silent mode chalu karo")
                hands.brightness(15)
            } catch (e: Exception) {
                // ignore
            }
        }.start()
        return "क्लीन स्लेट प्रोटोकॉल। फ़ोन शांत, स्क्रीन धीमी। अब सिर्फ़ आप और आपका काम।"
    }

    private fun morningProtocol(t: String, words: List<String>): String {
        val bi = words.indexOfFirst { it == s("baje") || it == s("बजे") }
        val hour = if (bi > 0) (words[bi - 1].toIntOrNull() ?: Local.numberOf(words[bi - 1])) else 7
        if (hour == null || hour !in 0..23) return "कितने बजे? जैसे: मॉर्निंग प्रोटोकॉल 7 बजे।"
        val a = try { JSONArray(Prefs.rules) } catch (e: Exception) { JSONArray() }
        val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("tag") != "morning") keep.put(a.get(i))
        val at = String.format(Locale.US, "%02d:00", hour)
        for (cmd in listOf("briefing", "mausam batao", "aaj ki khabrein batao")) {
            keep.put(JSONObject().put("id", System.nanoTime()).put("k", "time").put("at", at).put("cmd", cmd).put("last", "").put("tag", "morning"))
        }
        Prefs.rules = keep.toString()
        return "मॉर्निंग प्रोटोकॉल तय। रोज़ सुबह $at बजे मैं ब्रीफ़िंग, मौसम और ताज़ा खबरें सुनाऊँगा। Zyro चालू रखना।"
    }

    // ------------------------------------------------------------------ mission report

    private fun missionReport(ctx: Context): String {
        val td = today()
        val sb = StringBuilder(sir() + "आज की मिशन रिपोर्ट। ")
        val water = try { JSONObject(Prefs.water).optInt(td) } catch (e: Exception) { 0 }
        sb.append("पानी $water गिलास। ")
        var spent = 0
        try {
            val a = JSONArray(Prefs.expenses)
            val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optLong("ts") >= start) spent += o.optInt("a")
            }
        } catch (e: Exception) {
            // no expenses
        }
        sb.append("आज का खर्च $spent रुपये। ")
        try {
            val d = JSONObject(Prefs.streaks)
            if (d.length() > 0) {
                var done = 0
                val it = d.keys()
                while (it.hasNext()) if (d.getJSONObject(it.next()).optString("d") == td) done++
                sb.append("आदतें: ${d.length()} में से $done पूरी। ")
            }
        } catch (e: Exception) {
            // no habits
        }
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        if (lvl >= 0) sb.append("बैटरी $lvl प्रतिशत। ")
        sb.append("सभी सिस्टम सामान्य।")
        return sb.toString()
    }

    // ------------------------------------------------------------------ proactive weather warning

    /** Checks the forecast twice a day and warns about rain or heat. */
    fun tick() {
        val ctx = appCtx ?: return
        val cal = Calendar.getInstance()
        val mod = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val slot = when (mod) {
            in 420..435 -> "am"
            in 960..975 -> "pm"
            else -> return
        }
        val key = today() + slot
        if (Prefs.weatherNag == key) return
        Prefs.weatherNag = key
        Thread {
            try {
                val conn = URL("https://wttr.in/?format=j1").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                val body = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
                val day = JSONObject(body).getJSONArray("weather").getJSONObject(0)
                val hourly = day.getJSONArray("hourly")
                var rain = 0
                for (i in 0 until hourly.length()) rain = maxOf(rain, hourly.getJSONObject(i).optString("chanceofrain", "0").toIntOrNull() ?: 0)
                val maxT = day.optString("maxtempC", "0").toIntOrNull() ?: 0
                val msg = when {
                    rain >= 60 -> "${sir()}आज बारिश की संभावना $rain प्रतिशत है। छाता साथ रखिए।"
                    maxT >= 40 -> "${sir()}आज तापमान $maxT डिग्री तक जाएगा। पानी खूब पीजिए और धूप से बचिए।"
                    else -> null
                }
                if (msg != null) Assistant.get(ctx).announce(msg)
            } catch (e: Exception) {
                // no internet, no warning
            }
        }.start()
    }

    // ------------------------------------------------------------------ voice commands

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(s(it)) }

        if (any("hud", "एचयूडी", "एच यू डी", "heads up", "हेड्स अप", "arc reactor", "आर्क रिएक्टर", "holo screen", "होलो स्क्रीन")) {
            return try {
                hands.openActivity(Intent(ctx, HudActivity::class.java))
                "${sir()}एचयूडी चालू। बंद करने के लिए स्क्रीन को टच कीजिए।"
            } catch (e: Exception) {
                "एचयूडी नहीं खुल पाया।"
            }
        }
        if (any("house party", "हाउस पार्टी", "party protocol", "पार्टी प्रोटोकॉल")) return partyProtocol(hands)
        if (any("clean slate", "क्लीन स्लेट")) return cleanSlate(hands)
        if (any("morning protocol", "मॉर्निंग प्रोटोकॉल", "सुबह का प्रोटोकॉल", "subah ka protocol")) return morningProtocol(t, words)
        if (any("protocol", "प्रोटोकॉल") && any("list", "लिस्ट", "kaun", "कौन", "batao", "बताओ", "kya kya", "क्या क्या")) {
            return "मेरे प्रोटोकॉल: हाउस पार्टी, क्लीन स्लेट, और मॉर्निंग प्रोटोकॉल, जैसे मॉर्निंग प्रोटोकॉल 7 बजे। साथ में एचयूडी, गार्ड मोड और ड्राइविंग मोड भी हैं।"
        }
        if (any("mission report", "मिशन रिपोर्ट", "aaj ki report", "आज की रिपोर्ट", "status report", "स्टेटस रिपोर्ट", "din ki report", "दिन की रिपोर्ट")) return missionReport(ctx)
        return null
    }
}
