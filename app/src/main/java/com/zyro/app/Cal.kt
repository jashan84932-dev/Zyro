package com.zyro.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.Calendar

/** Reads the phone's Calendar (any account synced to it: Google, Samsung...). "Aaj ki meeting?", "kal ka schedule", "agla event kab hai?" + a reminder 15 minutes before. */
object Cal {

    class Ev(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val where: String)

    fun granted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun events(ctx: Context, from: Long, to: Long): List<Ev> {
        val out = ArrayList<Ev>()
        if (!granted(ctx)) return out
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION
        )
        try {
            val c = CalendarContract.Instances.query(ctx.contentResolver, proj, from, to)
            if (c != null) {
                c.use {
                    while (it.moveToNext()) {
                        out.add(Ev(it.getLong(0), (it.getString(1) ?: "").ifBlank { "बिना नाम का इवेंट" }, it.getLong(2), it.getLong(3), it.getInt(4) == 1, it.getString(5) ?: ""))
                    }
                }
            }
        } catch (e: Exception) { }
        return out.sortedBy { it.begin }
    }

    private fun dayStart(offset: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, offset)
        return c.timeInMillis
    }

    /** "सुबह 9 बजे", "शाम 5:30 बजे" */
    fun say(ms: Long): String {
        val c = Calendar.getInstance(); c.timeInMillis = ms
        val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        val part = when { h < 4 -> "रात"; h < 12 -> "सुबह"; h < 16 -> "दोपहर"; h < 20 -> "शाम"; else -> "रात" }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$part $h12" + (if (m != 0) ":" + m.toString().padStart(2, '0') else "") + " बजे"
    }

    private fun line(e: Ev) = (if (e.allDay) "पूरे दिन" else say(e.begin)) + " " + e.title + (if (e.where.isNotBlank()) " (${e.where.take(30)})" else "")

    /** For the morning briefing: one short sentence, or "" when nothing / no permission. */
    fun todayLine(ctx: Context): String {
        val now = System.currentTimeMillis()
        val ev = events(ctx, now - 30 * 60_000L, dayStart(1)).filter { !it.allDay || it.end > now }
        if (ev.isEmpty()) return ""
        return "आज के इवेंट: " + ev.take(3).joinToString("। ") { line(it) } + (if (ev.size > 3) "। और ${ev.size - 3} हैं।" else "।") + " "
    }

    fun handle(ctx: Context, t: String): String? {
        fun any(vararg w: String) = w.any { t.contains(Local.strip(it)) }
        if (any("yaad", "याद", "remind", "रिमाइंड", "add", "jod", "जोड़", "banao", "बनाओ", "set ", "सेट", "alarm", "अलार्म", "delete", "हटा", "bhej", "भेज")) return null
        val subj = any("calendar", "कैलेंडर", "schedule", "शेड्यूल", "meeting", "मीटिंग", "event", "इवेंट", "appointment", "अपॉइंटमेंट", "exam", "एग्जाम")
        if (!subj) return null
        if (!any("aaj", "आज", "kal", "कल", "parso", "परसों", "hafte", "हफ्ते", "week", "agla", "अगला", "agli", "अगली", "next", "kya", "क्या", "kab", "कब", "kitne", "कितने", "bata", "बता", "dikha", "दिखा", "koi", "कोई", "hai", "है")) return null
        if (!granted(ctx)) return "कैलेंडर देखने की अनुमति चाहिए। Zyro में पहला बटन दबाकर permission दे दो।"
        val now = System.currentTimeMillis()
        if (any("agla", "अगला", "agli", "अगली", "next", "kab hai", "कब है")) {
            val ev = events(ctx, now, now + 30L * 86400_000L).firstOrNull { !it.allDay || true }
                ?: return "अगले 30 दिन में कोई इवेंट नहीं है।"
            val d = ((dayStart(0).let { ev.begin - it }) / 86400_000L).toInt()
            val whenS = when { ev.allDay && d <= 0 -> "आज पूरे दिन"; d <= 0 -> "आज " + say(ev.begin); d == 1 -> "कल " + say(ev.begin); else -> "$d दिन बाद " + say(ev.begin) }
            return "अगला इवेंट: ${ev.title}, $whenS।" + (if (ev.where.isNotBlank()) " जगह: ${ev.where.take(40)}।" else "")
        }
        val week = any("hafte", "हफ्ते", "hafta", "हफ्ता", "week", "सप्ताह", "7 din", "सात दिन")
        val (from, to, name) = when {
            week -> Triple(now, dayStart(7), "अगले 7 दिन")
            any("parso", "परसों") -> Triple(dayStart(2), dayStart(3), "परसों")
            any("kal", "कल") -> Triple(dayStart(1), dayStart(2), "कल")
            else -> Triple(dayStart(0), dayStart(1), "आज")
        }
        val ev = events(ctx, from, to)
        if (ev.isEmpty()) return "$name कोई इवेंट नहीं है। (अगर कैलेंडर में है पर मैं नहीं देख पा रहा, तो फ़ोन के Calendar में अकाउंट sync ON करो।)"
        val body = ev.take(6).joinToString("। ") { line(it) }
        return "$name के ${ev.size} इवेंट: $body" + (if (ev.size > 6) "। और ${ev.size - 6} हैं।" else "।")
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("zyro_cal", Context.MODE_PRIVATE)

    /** Called from the habit tick: says "15 minute baad: X" once per event. */
    fun tick(ctx: Context) {
        if (!Prefs.nudgeOn || !granted(ctx)) return
        if (!Daily.screenIsOn(ctx) || Live.active || Prefs.busyMode) return
        val now = System.currentTimeMillis()
        val ev = events(ctx, now + 5 * 60_000L, now + 20 * 60_000L).firstOrNull { !it.allDay } ?: return
        val key = "${ev.id}:${ev.begin}"
        val p = sp(ctx)
        if (p.getString("last", "") == key) return
        p.edit().putString("last", key).apply()
        val mins = ((ev.begin - now) / 60_000L).toInt().coerceAtLeast(1)
        Assistant.get(ctx).announce("$mins मिनट बाद ${ev.title}" + (if (ev.where.isNotBlank()) ", जगह ${ev.where.take(30)}" else "") + "।")
    }
}
