package com.zyro.app

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * "Goa trip plan karo": looks things up on the web, compares options, writes a day-by-day plan
 * and, only after you say yes, opens the calendar with the trip filled in (you press Save).
 *
 * It never books or pays for anything. If you want to book, it opens the app and stops before paying.
 */
object Trip {
    private fun strip(s: String) = Local.strip(s)

    private class Pending(val title: String, val start: LocalDate, val days: Int, val desc: String)
    private var pending: Pending? = null
    private var pendingAt = 0L

    private val YES = listOf("हाँ", "हां", "haan", "han", "yes", "ok", "okay", "ओके", "ठीक", "daal", "डाल", "karo", "करो", "kar", "कर", "do", "दो")
    private val NO = listOf("नहीं", "nahi", "nahin", "no", "cancel", "mat", "मत", "रहने", "कैंसिल")

    fun handlePending(t: String, words: List<String>): String? {
        val p = pending ?: return null
        if (System.currentTimeMillis() - pendingAt > 3 * 60_000L) { pending = null; return null }
        if (words.size > 4) return null
        val no = words.any { w -> NO.any { strip(it) == w } }
        val yes = words.any { w -> YES.any { strip(it) == w } }
        if (no) { pending = null; return "ठीक है, कैलेंडर में कुछ नहीं डाला।" }
        if (!yes) return null
        pending = null
        val ctx = Extras.appContext() ?: return "कैलेंडर नहीं खुल पाया।"
        return try {
            val zone = ZoneId.systemDefault()
            val begin = p.start.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = p.start.plusDays(p.days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, p.title)
                .putExtra(CalendarContract.Events.DESCRIPTION, p.desc.take(1500))
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            "कैलेंडर खुल गया, सब भरा हुआ है। बस Save दबा दो।"
        } catch (e: Exception) {
            "कैलेंडर ऐप नहीं खुल पाया। प्लान कन्वर्सेशन में लिखा है, वहाँ से देख लो।"
        }
    }

    fun handle(ctx: Context, t: String, raw: String): String? {
        fun any(vararg w: String) = w.any { t.contains(strip(it)) }
        val trip = any("trip", "ट्रिप", "yatra", "यात्रा", "ghumne", "घूमने", "tour", "टूर", "vacation", "वेकेशन", "holiday plan")
        val plan = any("plan", "प्लान", "banao", "बनाओ", "suggest", "सुझाओ", "dhundo", "ढूंढो")
        if (!trip || !plan) return null
        if (!Prefs.hasGemini) return "ट्रिप प्लान के लिए Gemini key चाहिए। Settings में डाल दो।"

        val today = LocalDate.now()
        val j = try {
            val r = Brain.quickJson(
                "Today is $today. The user said: \"${raw.take(300)}\". Extract the trip request. Reply ONLY JSON " +
                    "{\"dest\":\"place\",\"days\":number of days (default 3),\"start\":\"YYYY-MM-DD or empty if not said\",\"people\":number (default 1),\"budget\":\"as said or empty\",\"from\":\"city or empty\"}", 200, 12000, true
            ) ?: return "अभी जगह नहीं समझ पाया, जगह का नाम फिर से बोलो।"
            JSONObject(r.substring(r.indexOf('{'), r.lastIndexOf('}') + 1))
        } catch (e: Exception) { return "ट्रिप की बात ठीक से समझ नहीं आई, एक बार और बोलो, जैसे: गोवा ट्रिप प्लान करो तीन दिन के लिए।" }
        val dest = j.optString("dest").trim()
        if (dest.isBlank()) return "कहाँ घूमने जाना है? जगह का नाम बोलो।"
        val days = j.optInt("days", 3).coerceIn(1, 14)
        val people = j.optInt("people", 1).coerceIn(1, 20)
        val budget = j.optString("budget")
        val from = j.optString("from")
        val start = try { LocalDate.parse(j.optString("start")).let { if (it.isBefore(today)) null else it } } catch (e: Exception) { null }
            ?: today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))

        Assistant.get(ctx).note("ट्रिप प्लान बना रहा हूँ: $dest, $days दिन… (वेब पर खोज रहा हूँ)")
        val text = Brain.quickText(
            "Plan a $days-day trip to $dest for $people ${if (people == 1) "person" else "people"} starting $start" +
                (if (from.isNotBlank()) ", travelling from $from" else "") + (if (budget.isNotBlank()) ", budget $budget" else "") + ". " +
                "Use current web information. Answer in simple Hinglish (Hindi in Roman letters), plain text, no markdown symbols. Format:\\n" +
                "BEST: one sentence on the best way to go and when.\\n" +
                "OPTIONS: compare 3 stay options (name, rough price per night in rupees, one line why) and 2 travel options (mode, rough price, time).\\n" +
                "PLAN: Day 1, Day 2... one line each with places and food.\\n" +
                "COST: rough total per person in rupees.\\n" +
                "TIPS: 2 short tips. Say prices are estimates to check before booking.",
            1100, true
        ) ?: return "अभी खोज नहीं हो पाई, इंटरनेट या Gemini की limit देखो।"
        val clean = text.replace(Regex("[*#`_]+"), "").replace("\\n", "\n").trim()
        Assistant.get(ctx).note("🧭 $dest ट्रिप ($days दिन, $start से):\n$clean")

        val best = Regex("BEST:\\s*(.+)").find(clean)?.groupValues?.get(1)?.trim().orEmpty()
        val cost = Regex("COST:\\s*(.+)").find(clean)?.groupValues?.get(1)?.trim().orEmpty()
        pending = Pending("$dest ट्रिप", start, days, clean)
        pendingAt = System.currentTimeMillis()
        return "$dest का $days दिन का प्लान बन गया। " + (if (best.isNotBlank()) best.take(220) + " " else "") +
            (if (cost.isNotBlank()) "खर्चा करीब " + cost.take(120) + "। " else "") +
            "पूरा प्लान, तीन होटल और दो सफ़र के विकल्प कन्वर्सेशन में लिखे हैं, और ये सब अंदाज़े हैं, बुक करने से पहले चेक कर लेना। मैं बुकिंग या पेमेंट खुद कभी नहीं करता। कैलेंडर में ${start} से डाल दूँ?"
    }
}
