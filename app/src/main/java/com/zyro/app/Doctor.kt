package com.zyro.app

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Zyro's own doctor. It keeps the last crash (CrashLog) and a short list of problems it noticed in its own log,
 * and on "apne bug theek karo" it gives that evidence to Claude through the Dev session, which fixes the code with
 * small patches, commits them, watches the build and puts the old code back if the fix does not build.
 */
object Doctor {
    private val lock = Any()

    /** Called for every log line: remembers the lines that look like a problem (a few KB at most). */
    fun soft(ctx: Context, line: String) {
        val l = line.lowercase(Locale.ROOT)
        val bad = line.contains("⚠") || line.contains("❌") || l.contains("not readable") || l.contains("failed") ||
            l.contains("exception") || l.contains("not found after")
        if (!bad) return
        try {
            synchronized(lock) {
                val f = File(ctx.applicationContext.filesDir, "soft.txt")
                val old = if (f.exists()) f.readText() else ""
                val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(java.util.Date())
                f.writeText((old + stamp + " " + line.take(220) + "\n").takeLast(6000))
            }
        } catch (e: Exception) { /* the list is optional */ }
    }

    /** What Claude gets to read: the last crash and the recent problems. Empty when there is nothing wrong. */
    fun evidence(ctx: Context): String {
        val crash = CrashLog.last(ctx).trim()
        val soft = try {
            synchronized(lock) {
                val f = File(ctx.applicationContext.filesDir, "soft.txt")
                if (f.exists()) f.readText().takeLast(1800) else ""
            }
        } catch (e: Exception) { "" }
        val sb = StringBuilder()
        if (crash.isNotEmpty()) sb.append("LAST CRASH:\n").append(crash.take(2500)).append("\n\n")
        if (soft.isNotBlank()) sb.append("RECENT PROBLEMS FROM MY OWN LOG:\n").append(soft.trim()).append("\n")
        return sb.toString().trim()
    }

    /** Tells the user (in the log) once when the app crashed since last time. */
    fun onStart(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                Thread.sleep(8000)
                val crash = File(app.filesDir, "crash.txt")
                if (!crash.exists()) return@Thread
                val seen = File(app.filesDir, "crash_seen.txt")
                val last = crash.lastModified()
                val prev = if (seen.exists()) seen.readText().trim().toLongOrNull() ?: 0L else 0L
                if (last > prev) {
                    seen.writeText(last.toString())
                    Assistant.get(app).note("🩺 पिछली बार ऐप क्रैश हुआ था। बोलो 'अपने बग ठीक करो', तो मैं Claude से ठीक करवाता हूँ।")
                }
            } catch (e: Exception) { /* optional */ }
        }.start()
    }

    fun handle(ctx: Context, hands: Hands, t: String, raw: String): String? {
        fun has(vararg w: String) = w.any { t.contains(Local.strip(it)) }
        val bug = has("bug", "crash", "क्रैश", "kharabi", "खराबी", "dikkat", "दिक्कत", "problem", "galti", "गलती")
        val fix = has("theek", "ठीक", "fix", "sudhar", "सुधार", "thik")
        val me = has("apne", "अपने", "apni", "अपनी", "apna", "अपना", "khud", "खुद")
        val doctor = has("doctor", "डॉक्टर", "self repair", "self fix", "khud ko theek", "खुद को ठीक")
        if (!(doctor || (bug && fix && me))) return null
        val ev = evidence(ctx)
        if (ev.isEmpty()) return "मेरे रिकॉर्ड में कोई क्रैश या दिक्कत नहीं है, इसलिए ठीक करने को कुछ नहीं है।"
        val quiet = has("chupke", "चुपके", "chupchap", "चुपचाप", "api se", "background", "बैकग्राउंड")
        val wish = "BUG-FIX ROUND (not a new feature). Fix only the problems below, with small patches, and read the failing files first with <need>.\n\n$ev"
        return Dev.beginWith(ctx, hands, wish, quiet)
    }
}
