package com.zyro.app

import android.content.Context
import java.util.Calendar
import org.json.JSONObject

/**
 * Zyro speaks first, like a friend: a good-morning, a "we have not talked for a while", and a follow-up on something
 * you mentioned (interview, exam...). At most two a day, only while the screen is on, never at night.
 * "proactive band" / "proactive chalu" switches it.
 */
object Nudge {
    private fun st(): JSONObject = try { JSONObject(Prefs.nudgeState) } catch (e: Exception) { JSONObject() }

    private fun day(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
    }

    fun tick(ctx: Context) {
        if (!Prefs.nudgeOn) return
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        if (h < 7 || h >= 22) return
        if (!Daily.screenIsOn(ctx) || Live.active) return
        if (Prefs.busyMode) return
        val s = st()
        val today = day()
        if (s.optString("day") != today) { s.put("day", today); s.put("count", 0); s.put("kinds", "") }
        val now = System.currentTimeMillis()
        if (s.optInt("count") >= 2 || now - s.optLong("at") < 4 * 3600_000L) { Prefs.nudgeState = s.toString(); return }
        // do not break in right after the user was talking to Zyro
        if (now - Soul.lastSeen() < 3 * 60_000L) return
        val kinds = s.optString("kinds")
        val name = Prefs.userName.ifBlank { "" }
        val call = if (name.isNotBlank()) "$name, " else ""
        var text: String? = null
        var kind = ""
        if (h in 7..10 && !kinds.contains("m")) {
            kind = "m"
            val th = Soul.threadFor(3 * 3600_000L)
            text = if (th != null) listOf(
                "सुप्रभात ${call}उठ गए? कल तुमने बताया था: $th। उसका क्या हुआ?",
                "गुड मॉर्निंग ${call}वो वाली बात याद है: $th? बताओ कैसा रहा।"
            ).random() else listOf(
                "सुप्रभात ${call}उठ गए? आज का क्या प्लान है?",
                "गुड मॉर्निंग ${call}चाय पी या अभी नहीं? आज कुछ करना है तो बता देना।",
                "सुबह हो गई ${call}आज का दिन मस्त बनाते हैं। कुछ करवाना हो तो बोलो।"
            ).random()
        } else if (h in 11..20 && !kinds.contains("g") && Soul.lastSeen() > 0 && now - Soul.lastSeen() > 10 * 3600_000L) {
            kind = "g"
            text = listOf("${call}काफ़ी देर से बात नहीं हुई, सब ठीक है? कुछ करवाना हो तो बोलो।", "अरे ${call}गायब ही हो गए! सब बढ़िया?").random()
        } else if (h in 17..21 && !kinds.contains("t")) {
            val th = Soul.threadFor(3 * 3600_000L)
            if (th != null) { kind = "t"; text = "${call}वो जो तुमने बताया था: $th, उसका क्या रहा?" }
        }
        if (text == null) return
        s.put("count", s.optInt("count") + 1)
        s.put("kinds", kinds + kind)
        s.put("at", now)
        Prefs.nudgeState = s.toString()
        Assistant.get(ctx).announce(text)
    }

    fun handle(t: String): String? {
        fun any(vararg w: String) = w.any { t.contains(Local.strip(it)) }
        if (!any("proactive", "प्रोएक्टिव", "khud se bolna", "खुद से बोलना", "khud bola", "पहले बोला")) return null
        if (any("band", "बंद", "off", "mat", "मत")) { Prefs.nudgeOn = false; return "ठीक है, अब मैं खुद से नहीं बोलूँगा, जब तुम बुलाओगे तभी।" }
        if (any("chalu", "चालू", "on", "shuru", "शुरू")) { Prefs.nudgeOn = true; return "ठीक है, कभी-कभी खुद से हालचाल पूछ लिया करूँगा। दिन में दो बार से ज़्यादा नहीं।" }
        return null
    }
}
