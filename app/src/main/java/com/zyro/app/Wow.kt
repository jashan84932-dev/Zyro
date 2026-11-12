package com.zyro.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import java.util.Locale

/**
 * Small "wow" features:
 *  - Clipboard brain: read it aloud, summarise, translate, rewrite, paste it, or push it to GitHub.
 *  - Watch list: "Papa ka message aaye to turant bata dena" - spoken the moment it arrives.
 *  - "screen ka text copy karo" - everything readable on screen goes to the clipboard.
 */
object Wow {

    private val CLIP = listOf("clipboard", "क्लिपबोर्ड", "क्लिप बोर्ड", "clip board", "क्लिपबोड")
    private val GH = listOf("github", "गिटहब", "गिट हब", "git hub", "गिथब")

    private fun has(t: String, list: List<String>) = list.any { t.contains(Local.strip(it)) }

    private fun clip(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val c = try { cm.primaryClip } catch (e: Exception) { null } ?: return null
        if (c.itemCount == 0) return null
        return c.getItemAt(0).coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun setClip(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Zyro", text))
    }

    private fun say(ctx: Context, text: String) = Assistant.get(ctx).announce(text)

    fun handle(ctx: Context, hands: Hands, t: String, raw: String): String? {
        watchCommand(ctx, raw)?.let { return it }

        // ----- copy what is on the screen -----
        if ((t.contains("screen") || t.contains(Local.strip("स्क्रीन"))) &&
            has(t, listOf("copy", "कॉपी", "kapi")) && has(t, listOf("text", "टेक्स्ट", "likha", "लिखा", "sab", "सब"))) {
            val s = hands.readScreen()
            if (s.isBlank() || s.startsWith("The screen") || s.contains("Accessibility")) return "स्क्रीन पढ़ नहीं पाया।"
            setClip(ctx, s)
            return "स्क्रीन का टेक्स्ट कॉपी कर लिया।"
        }

        if (!has(t, CLIP)) return null

        // ----- paste -----
        if (has(t, listOf("paste", "पेस्ट", "yahan likho", "यहाँ लिखो", "यहां लिखो", "type"))) {
            val c = clip(ctx) ?: return "क्लिपबोर्ड खाली है, या Android ने उसे पढ़ने नहीं दिया।"
            val r = hands.typeText(c)
            return if (r.startsWith("Typed") || r.startsWith("Done") || r.contains("ok", true)) "" else "यहाँ टाइप नहीं हो पाया।"
        }

        val text = clip(ctx)
            ?: return "क्लिपबोर्ड खाली है। पहले कुछ कॉपी करो। (नए Android में ऐप खुला हो तभी क्लिपबोर्ड दिखता है।)"
        val body = text.take(6000)

        Thread {
            try {
                when {
                    has(t, GH) -> {
                        val repo = Regex("([A-Za-z0-9._-]+)\\s+(?:naam\\s+(?:ke|ka|ki|se)\\s+|name\\s+)?(?:repo|repository|रेपो)", RegexOption.IGNORE_CASE)
                            .find(raw)?.groupValues?.get(1)?.takeIf { !it.equals("a", true) && !it.equals("ek", true) && !it.equals("new", true) } ?: "zyro-notes"
                        val name = (Brain.quickText("Give one good file name with extension for this content. Output only the file name, no spaces, no explanation.\n\n" + body.take(800), 20) ?: "clipboard.txt")
                            .trim().trim('`', '"', '\'').replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "clipboard.txt" }
                        say(ctx, Github.pushText(text, name, repo))
                    }
                    has(t, listOf("padho", "पढ़ो", "padh", "पढ", "sunao", "सुनाओ", "read", "kya hai", "क्या है")) ->
                        say(ctx, body.take(500))
                    has(t, listOf("summary", "summarize", "saar", "सार", "short", "chhota", "छोटा", "samjhao", "समझाओ")) -> {
                        val r = Brain.quickText("Summarise this in Hinglish (Hindi words in Devanagari, English words in English), at most 3 short sentences:\n\n$body", 200)
                        say(ctx, r ?: "सार नहीं बन पाया।")
                    }
                    else -> {
                        val instr = raw
                        val r = Brain.quickText(
                            "The user said (voice): \"$instr\". The text on their clipboard is below. Do exactly what they asked with that text " +
                                "(translate, rewrite, fix, shorten, explain...). Output ONLY the resulting text.\n\n$body",
                            1500, search = false
                        )
                        if (r.isNullOrBlank()) {
                            say(ctx, "नहीं हो पाया।")
                        } else {
                            setClip(ctx, r.trim())
                            say(ctx, "हो गया, क्लिपबोर्ड में कॉपी है।")
                        }
                    }
                }
            } catch (e: Exception) {
                say(ctx, "क्लिपबोर्ड का काम नहीं हुआ।")
            }
        }.start()
        return ""
    }

    // ---------------------------------------------------------------- watch list

    private fun names(): MutableList<String> {
        val a = try { JSONArray(Prefs.watchList) } catch (e: Exception) { JSONArray() }
        return MutableList(a.length()) { a.getString(it) }
    }

    private fun save(l: List<String>) {
        Prefs.watchList = JSONArray(l).toString()
    }

    private fun watchCommand(ctx: Context, raw: String): String? {
        val low = raw.lowercase(Locale.ROOT)
        val msg = listOf("message", "msg", "मैसेज", "मेसेज", "whatsapp", "व्हाट्सएप", "व्हाट्सऐप")
        val come = listOf("aaye to", "aye to", "aaya to", "आए तो", "आये तो", "आया तो", "आने पर", "aane par", "aaye toh", "आए तो")
        val tell = listOf("bata", "बता", "bol", "बोल", "sunao", "सुना", "alert", "बताना")

        if (low.contains("watch list") || low.contains("वॉच लिस्ट") || low.contains("kinpar nazar") || low.contains("किन पर नज़र") || low.contains("किन पर नजर")) {
            val l = names()
            return if (l.isEmpty()) "अभी किसी पर नज़र नहीं रख रहा।" else "इन पर नज़र है: " + l.joinToString(", ")
        }
        if ((low.contains("nazar") || low.contains("नज़र") || low.contains("नजर") || low.contains("watch")) &&
            (low.contains("hatao") || low.contains("हटाओ") || low.contains("mat rakho") || low.contains("मत रखो"))) {
            val l = names()
            val hit = l.firstOrNull { low.contains(it.lowercase(Locale.ROOT)) }
            if (hit != null) { l.remove(hit); save(l); return "ठीक है, $hit को लिस्ट से हटा दिया।" }
            return null
        }
        if (come.any { low.contains(it) } && msg.any { low.contains(it) } && tell.any { low.contains(it) }) {
            val m = Regex("^(.*?)\\s+(?:ka|ki|ke|का|की|के)\\s+(?:${msg.joinToString("|")})", RegexOption.IGNORE_CASE).find(raw.trim()) ?: return null
            var name = m.groupValues[1].trim()
            for (f in listOf("zyro", "ज़ायरो", "जायरो", "agar", "अगर", "jab", "जब", "yaar", "यार", "sun", "सुन")) {
                name = name.removePrefix(f).trim()
            }
            if (name.isBlank() || name.split(" ").size > 3) return null
            val l = names()
            if (l.none { it.equals(name, true) }) { l.add(name); save(l) }
            return "ठीक है, $name का मैसेज आते ही बता दूँगा।"
        }
        return null
    }

    /** Called for every message notification (before anything else). */
    @Volatile private var lastAlert = ""
    @Volatile private var lastAlertAt = 0L

    fun onNotification(ctx: Context, title: String, text: String) {
        if (title.isBlank()) return
        val l = names()
        if (l.isEmpty()) return
        val tl = title.lowercase(Locale.ROOT)
        val hit = l.firstOrNull { tl.contains(it.lowercase(Locale.ROOT)) } ?: return
        val now = System.currentTimeMillis()
        if (hit == lastAlert && now - lastAlertAt < 8000) return
        lastAlert = hit
        lastAlertAt = now
        say(ctx, "$hit का मैसेज आया: " + text.take(140))
    }
}
