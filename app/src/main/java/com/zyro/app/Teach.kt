package com.zyro.app

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * "Zyro seekho": do a job on your phone once while Zyro watches, then Zyro can repeat it by name.
 *   "Zyro seekho subah ka routine"   -> recording starts, do the job yourself
 *   "seekhna band"                    -> saved
 *   "subah ka routine chalao"         -> Zyro repeats every step
 * It records taps, typing and scrolling per app (never passwords, never payment or bank apps).
 */
object Teach {

    @Volatile var recording = false
        private set

    private var name = ""
    private val steps = ArrayList<JSONObject>()
    private var lastPkg = ""
    private var startedAt = 0L
    private var lastScrollY = Int.MIN_VALUE

    @Volatile private var replaying = false
    @Volatile private var cancel = false

    private fun has(t: String, vararg w: String) = w.any { t.contains(Local.strip(it)) }

    // ---------------------------------------------------------------- recording

    fun onEvent(e: AccessibilityEvent) {
        if (!recording) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == "com.zyro.app" || Agent.BLOCKED_PKGS.any { pkg.startsWith(it) } || pkg == "com.android.systemui") return
        if (SystemClock.elapsedRealtime() - startedAt > 5 * 60_000L) { recording = false; return }
        if (steps.size >= 80) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != lastPkg) {
                    lastPkg = pkg
                    steps.add(JSONObject().put("k", "app").put("pkg", pkg))
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val n = e.source ?: return
                if (n.isPassword) return
                val r = Rect()
                n.getBoundsInScreen(r)
                val text = (n.text?.toString() ?: e.text?.joinToString(" ") ?: "").take(60)
                steps.add(
                    JSONObject().put("k", "click").put("pkg", pkg).put("id", n.viewIdResourceName ?: "")
                        .put("text", text).put("desc", n.contentDescription?.toString()?.take(60) ?: "")
                        .put("x", r.centerX()).put("y", r.centerY())
                )
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val n = e.source ?: return
                if (n.isPassword) return
                val txt = e.text?.joinToString("") ?: return
                val id = n.viewIdResourceName ?: ""
                // typing is many events: keep only the last text of the same field
                val prev = steps.lastOrNull()
                if (prev != null && prev.optString("k") == "type" && prev.optString("id") == id) steps.removeAt(steps.size - 1)
                steps.add(JSONObject().put("k", "type").put("pkg", pkg).put("id", id).put("text", txt.take(300)))
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val y = e.scrollY
                if (lastScrollY != Int.MIN_VALUE && y != lastScrollY) {
                    val down = y > lastScrollY
                    val prev = steps.lastOrNull()
                    if (prev == null || prev.optString("k") != "scroll" || prev.optBoolean("down") != down) {
                        steps.add(JSONObject().put("k", "scroll").put("pkg", pkg).put("down", down))
                    }
                }
                lastScrollY = y
            }
        }
    }

    private fun taught(): JSONObject = try { JSONObject(Prefs.taught) } catch (e: Exception) { JSONObject() }

    private fun save() {
        val r = taught()
        r.put(name, JSONArray(steps))
        // keep at most 20 routines
        while (r.length() > 20) r.remove(r.keys().next())
        Prefs.taught = r.toString()
    }

    // ---------------------------------------------------------------- voice entry

    fun handle(ctx: Context, hands: Hands, t: String, raw: String): String? {
        if (recording && has(t, "seekhna band", "सीखना बंद", "bas ho gaya", "बस हो गया", "save karo", "सेव करो", "yaad kar lo", "याद कर लो", "ho gaya seekh")) {
            recording = false
            if (steps.isEmpty()) return "कुछ रिकॉर्ड नहीं हुआ। Accessibility एक बार बंद करके फिर चालू करो, फिर दोबारा सिखाओ।"
            save()
            return "सीख लिया। ${steps.size} कदम याद हैं। \"$name चलाओ\" बोलकर दोहरा सकते हो।"
        }
        if (replaying && has(t, "ruko", "रुको", "stop", "band karo", "बंद करो", "cancel")) {
            cancel = true
            return "रुक गया।"
        }

        // start
        if (has(t, "seekho", "सीखो", "sikho", "teach mode", "सिखाता हूँ", "sikhata hoon", "sikhaata hoon", "seekhna shuru", "सीखना शुरू")) {
            if (hands.service == null) return "पहले Accessibility में Zyro चालू करो।"
            var nm = raw.replace(Regex("(?i)(zyro|ज़ायरो|जायरो|seekho|सीखो|sikho|सीख|teach mode|teach|सिखाता हूँ|sikhata hoon|sikhaata hoon|seekhna shuru|सीखना शुरू|ye|यह|ये|kaam|काम|ko|को|naam|नाम|se|से|karo|करो)"), " ")
                .replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)
            if (nm.isBlank()) nm = "kaam " + (taught().length() + 1)
            name = nm
            steps.clear()
            lastPkg = ""
            lastScrollY = Int.MIN_VALUE
            startedAt = SystemClock.elapsedRealtime()
            recording = true
            return "ठीक है, \"$nm\" सीखने के लिए तैयार हूँ। अब आप वह काम करके दिखाओ। हो जाए तो बोलना, सीखना बंद।"
        }

        // list / delete
        if (has(t, "kya kya seekha", "क्या क्या सीखा", "seekhe hue", "सीखे हुए")) {
            val r = taught()
            if (has(t, "mita", "मिटा", "hatao", "हटाओ", "delete", "bhool", "भूल")) {
                val hit = r.keys().asSequence().firstOrNull { t.contains(Local.strip(it)) }
                return if (hit != null) { r.remove(hit); Prefs.taught = r.toString(); "$hit भूल गया।" } else { Prefs.taught = "{}"; "सारे सीखे हुए काम मिटा दिए।" }
            }
            return if (r.length() == 0) "अभी कुछ नहीं सीखा। बोलो: ज़ायरो सीखो, और नाम।" else "मुझे ये काम आते हैं: " + r.keys().asSequence().joinToString(", ")
        }

        // run: "<name> chalao"
        if (has(t, "chalao", "चलाओ", "run", "dohrao", "दोहराओ", "karke dikhao", "करके दिखाओ", "kar do", "कर दो", "shuru karo", "शुरू करो")) {
            val r = taught()
            val hit = r.keys().asSequence().filter { t.contains(Local.strip(it)) }.maxByOrNull { it.length } ?: return null
            if (hands.service == null) return "पहले Accessibility में Zyro चालू करो।"
            if (replaying) return "मैं पहले से एक काम दोहरा रहा हूँ।"
            val arr = r.getJSONArray(hit)
            replaying = true
            cancel = false
            Thread {
                val a = Assistant.get(ctx)
                val msg = try { replay(ctx, hands, arr) } catch (e: Exception) { "बीच में दिक्कत आ गई।" } finally { replaying = false }
                a.announce(msg)
            }.start()
            return ""
        }
        return null
    }

    // ---------------------------------------------------------------- replay

    private fun root(hands: Hands): AccessibilityNodeInfo? = try { hands.service?.rootInActiveWindow } catch (e: Exception) { null }

    private fun findNode(hands: Hands, s: JSONObject): AccessibilityNodeInfo? {
        val r = root(hands) ?: return null
        val id = s.optString("id")
        if (id.isNotBlank()) {
            val list = r.findAccessibilityNodeInfosByViewId(id)
            val n = list?.firstOrNull { it.isVisibleToUser }
            if (n != null) return n
        }
        val text = s.optString("text")
        val desc = s.optString("desc")
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || found != null || depth > 30) return
            if (n.isVisibleToUser) {
                val nt = n.text?.toString() ?: ""
                val nd = n.contentDescription?.toString() ?: ""
                if ((text.isNotBlank() && nt == text) || (desc.isNotBlank() && nd == desc)) { found = n; return }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(r, 0)
        return found
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        var hops = 0
        while (x != null && hops < 6) {
            if (x.isClickable && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            x = x.parent
            hops++
        }
        return false
    }

    private fun replay(ctx: Context, hands: Hands, arr: JSONArray): String {
        var done = 0
        for (i in 0 until arr.length()) {
            if (cancel) return "रोक दिया।"
            val s = arr.getJSONObject(i)
            val pkg = s.optString("pkg")
            if (Agent.BLOCKED_PKGS.any { pkg.startsWith(it) }) continue
            when (s.optString("k")) {
                "app" -> {
                    val cur = root(hands)?.packageName?.toString()
                    if (cur != pkg) {
                        val li = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return "$pkg ऐप नहीं मिला।"
                        hands.openActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        Thread.sleep(1800)
                    }
                }
                "click" -> {
                    var ok = false
                    for (tries in 0 until 8) {
                        if (cancel) return "रोक दिया।"
                        val n = findNode(hands, s)
                        if (n != null && clickNode(n)) { ok = true; break }
                        Thread.sleep(500)
                    }
                    if (!ok) {
                        val x = s.optInt("x", -1)
                        val y = s.optInt("y", -1)
                        if (x < 0 || y < 0) return "एक बटन नहीं मिला, इसलिए रुक गया।"
                        hands.tap(x, y)
                    }
                }
                "type" -> {
                    var ok = false
                    for (tries in 0 until 6) {
                        val n = findNode(hands, JSONObject().put("id", s.optString("id")))
                        if (n != null && !n.isPassword) {
                            val args = android.os.Bundle()
                            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s.optString("text"))
                            if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { ok = true; break }
                        }
                        Thread.sleep(500)
                    }
                    if (!ok) hands.typeText(s.optString("text"))
                }
                "scroll" -> hands.scroll(s.optBoolean("down", true))
            }
            done++
            Thread.sleep(800)
        }
        return "हो गया। $done कदम दोहरा दिए।"
    }
}
