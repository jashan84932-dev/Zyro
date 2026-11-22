package com.zyro.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.json.JSONObject

/**
 * "Mission: kal Bathinda se Delhi ka sasta bus ticket dhundo": a goal, not a command.
 *
 * 1. The planner (Gemini/Groq) splits the goal into 2-5 small phone jobs and says the plan out loud.
 * 2. Only after "haan" does it run them one after another through the normal Agent (which still asks before
 *    paying, sending, deleting, and never touches banking/UPI apps).
 * 3. Each step gets the result of the previous one, a notification shows "Mission 2/4", "ruko" stops everything.
 * 4. At the end you get a short spoken report; "mission report" reads it again.
 */
object Mission {

    private class Plan(val goal: String, val steps: List<String>, val summary: String)

    @Volatile private var pending: Plan? = null
    @Volatile private var pendingAt = 0L
    @Volatile var active = false
        private set
    @Volatile private var cancelled = false
    @Volatile private var stepNow = 0
    @Volatile private var stepsN = 0
    @Volatile private var goalNow = ""
    @Volatile private var stepText = ""
    private val results = ArrayList<String>()

    private const val CHANNEL = "zyro_mission"
    private const val NOTIF_ID = 7712

    private fun strip(s: String) = Local.strip(s)
    private fun sp(ctx: Context) = ctx.getSharedPreferences("zyro_mission", Context.MODE_PRIVATE)

    private val YES = listOf("हाँ", "हां", "haan", "han", "yes", "ok", "okay", "ओके", "ठीक", "shuru", "शुरू", "karo", "करो", "chalu", "चालू", "ji", "जी")
    private val NO = listOf("नहीं", "nahi", "nahin", "no", "cancel", "mat", "मत", "रहने", "कैंसिल", "ruko", "रुको")

    fun handlePending(t: String, words: List<String>): String? {
        val p = pending ?: return null
        if (System.currentTimeMillis() - pendingAt > 3 * 60_000L) { pending = null; return null }
        val ws = words.map { strip(it) }
        val no = NO.any { ws.contains(strip(it)) }
        val yes = YES.any { ws.contains(strip(it)) }
        if (no && !yes) { pending = null; return "ठीक है, मिशन रद्द।" }
        if (!yes) return null
        pending = null
        val ctx = appCtx ?: return null
        val hands = lastHands ?: return "Accessibility में Zyro चालू करो, फिर मिशन चलेगा।"
        run(ctx, hands, p)
        return "मिशन शुरू। ${p.steps.size} स्टेप हैं। रोकना हो तो बोलो रुको।"
    }

    @Volatile private var appCtx: Context? = null
    @Volatile private var lastHands: Hands? = null
    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    fun handle(ctx: Context, hands: Hands, t: String, raw: String): String? {
        appCtx = ctx.applicationContext
        lastHands = hands
        val tn = t
        val key = listOf("mission", "मिशन", "mishan").firstOrNull { tn.contains(strip(it)) } ?: return null
        fun any(vararg w: String) = w.any { tn.contains(strip(it)) }

        if (any("report", "रिपोर्ट", "rapat", "nateeja", "नतीजा", "result")) {
            val r = sp(ctx).getString("report", "") ?: ""
            return if (r.isBlank()) "अभी तक कोई मिशन पूरा नहीं हुआ।" else r
        }
        if (any("status", "स्टेटस", "kahan tak", "कहाँ तक", "kya hua", "क्या हुआ", "kitna hua")) {
            return if (active) "मिशन चल रहा है: स्टेप $stepNow/$stepsN, $stepText" else "अभी कोई मिशन नहीं चल रहा।"
        }
        if (any("ruko", "रुको", "cancel", "कैंसिल", "band", "बंद", "stop", "roko", "रोको")) {
            if (active) { cancelled = true; Agent.cancel(); return "मिशन रोक रहा हूँ।" }
            if (pending != null) { pending = null; return "मिशन रद्द।" }
            return "कोई मिशन नहीं चल रहा।"
        }
        if (active) return "एक मिशन चल रहा है (स्टेप $stepNow/$stepsN)। पहले उसे रोको या पूरा होने दो।"
        if (!Prefs.hasGemini) return "मिशन के लिए Gemini key चाहिए।"
        if (hands.service == null) return "मिशन के लिए Accessibility में Zyro चालू करो।"

        // the goal = the sentence without the word "mission"
        var goal = raw.trim()
        val i = listOf("mission", "मिशन", "mishan").map { goal.lowercase().indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (i != null) {
            val w = listOf("mission", "मिशन", "mishan").first { goal.lowercase().indexOf(it) == i }
            goal = (goal.substring(0, i) + " " + goal.substring(i + w.length)).trim(' ', ',', ':', '.')
        }
        if (goal.length < 6) return "बताओ मिशन क्या है? जैसे: मिशन, कल का सबसे सस्ता बस टिकट बठिंडा से दिल्ली ढूंढो।"

        val plan = makePlan(goal) ?: return "प्लान नहीं बन पाया, इंटरनेट या Gemini key देखो।"
        pending = plan
        pendingAt = System.currentTimeMillis()
        try { Assistant.get(ctx).note("🎯 मिशन: ${plan.goal}") } catch (e: Exception) { }
        plan.steps.forEachIndexed { n, s -> try { Assistant.get(ctx).note("  ${n + 1}. $s") } catch (e: Exception) { } }
        return "${plan.steps.size} स्टेप का प्लान बनाया: ${plan.summary} शुरू करूँ?"
    }

    private fun makePlan(goal: String): Plan? {
        val prompt = "तुम एक Android फ़ोन-एजेंट के प्लानर हो। यूज़र का लक्ष्य: \"$goal\"\n" +
            "इसे 2 से 5 छोटे, अलग-अलग स्टेप में तोड़ो। हर स्टेप ऐसा काम हो जो फ़ोन-एजेंट एक या दो ऐप में खुद कर सके, पूरे वाक्य में, ऐप का नाम लेकर " +
            "(जैसे \"Chrome में ... खोजकर टॉप 3 नतीजे और दाम नोट करो\", \"Google Maps में ... की दूरी देखो\"). पिछले स्टेप का नतीजा अगले स्टेप को दिया जाएगा।\n" +
            "नियम: पैसे देना, ऑर्डर/बुकिंग कन्फ़र्म करना, मैसेज भेजना, कुछ डिलीट करना, बैंक/UPI ऐप खोलना कभी स्टेप न बनाओ। इनकी जगह " +
            "\"सिर्फ़ तैयार करो और कन्फ़र्म करने से पहले रुक जाओ\" वाला स्टेप लिखो।\n" +
            "सिर्फ़ JSON दो: {\"summary\":\"एक छोटा हिंदी वाक्य जो प्लान बताए\",\"steps\":[\"...\",\"...\"]}"
        val out = Brain.quickJson(prompt, 700, 25000) ?: return null
        return try {
            val j = JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1))
            val a = j.getJSONArray("steps")
            val steps = (0 until minOf(a.length(), 5)).map { a.getString(it).trim() }.filter { it.length > 5 }
            if (steps.isEmpty()) null else Plan(goal, steps, j.optString("summary", "").ifBlank { "" })
        } catch (e: Exception) { null }
    }

    private fun notif(ctx: Context, title: String, text: String, ongoing: Boolean) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Zyro mission", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOngoing(ongoing).setOnlyAlertOnce(true).build()
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) { }
    }

    private fun run(ctx: Context, hands: Hands, plan: Plan) {
        active = true; cancelled = false; goalNow = plan.goal; stepsN = plan.steps.size; stepNow = 0
        results.clear()
        Thread {
            try {
                for ((i, step) in plan.steps.withIndex()) {
                    if (cancelled) break
                    stepNow = i + 1; stepText = step
                    notif(ctx, "Zyro मिशन ${i + 1}/${plan.steps.size}", step, true)
                    Agent.lastSay = ""
                    val prev = results.lastOrNull()?.takeIf { it.isNotBlank() }
                    val job = if (prev == null) step else "$step\n(पिछले स्टेप का नतीजा: ${prev.take(350)})"
                    val r = Agent.start(ctx, hands, job)
                    if (!Agent.running) { results.add("स्टेप ${i + 1} शुरू नहीं हो पाया: $r"); break }
                    var waited = 0
                    while (Agent.running && !cancelled && waited < 7 * 60_000) { Thread.sleep(500); waited += 500 }
                    if (cancelled || Agent.wasCancelled) { cancelled = true; Agent.cancel(); break }
                    results.add(Agent.lastSay.ifBlank { "हो गया।" })
                }
            } catch (e: Exception) {
                results.add("दिक्कत: ${e.message}")
            }
            finish(ctx, plan)
        }.start()
    }

    private fun finish(ctx: Context, plan: Plan) {
        val done = results.size
        val stopped = cancelled
        val joined = results.mapIndexed { i, r -> "स्टेप ${i + 1}: ${r.take(400)}" }.joinToString("\n")
        var report: String? = null
        if (done > 0 && !stopped) {
            report = Brain.quickJson(
                "लक्ष्य: \"${plan.goal}\"\nफ़ोन-एजेंट के स्टेप-वार नतीजे:\n$joined\n\n" +
                    "यूज़र को बोलकर सुनाने के लिए 2-3 छोटे हिंदी वाक्य में रिपोर्ट लिखो: क्या मिला, और क्या बाकी या कन्फ़र्म करना है। सिर्फ़ वही लिखो जो नतीजों में है, कुछ मत गढ़ो। JSON नहीं, सादा टेक्स्ट।",
                300, 20000, true
            )?.trim()
        }
        val text = when {
            stopped -> "मिशन रोक दिया गया। $done स्टेप हो चुके थे।" + if (done > 0) " आख़िरी नतीजा: ${results.last().take(200)}" else ""
            !report.isNullOrBlank() -> report
            done == 0 -> "मिशन शुरू नहीं हो पाया।"
            else -> "मिशन पूरा। " + results.joinToString(" ") { it.take(120) }
        }
        sp(ctx).edit().putString("report", text).apply()
        active = false; cancelled = false
        notif(ctx, if (stopped) "Zyro मिशन रुका" else "Zyro मिशन पूरा", text, false)
        try { Assistant.get(ctx).announce(text) } catch (e: Exception) { }
    }
}
