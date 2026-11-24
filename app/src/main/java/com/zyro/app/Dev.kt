package com.zyro.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Zyro, Claude ke saath apna upgrade discuss karo."
 * Zyro introduces itself to Claude, tells it its own weak points and its file list, and the two agree on the next
 * big upgrade. Claude answers with new files and small patches; after the owner says "haan" Zyro puts them on GitHub
 * as ONE commit, waits for the build, and if the build fails it sends the compiler errors back to Claude for a fix.
 * After three failed fixes it puts the old code back. Only app/src/main/ can change, never keys.
 * Uses the Claude API key when there is one, otherwise Gemini.
 */
object Dev {

    @Volatile var busy = false
        private set
    @Volatile private var awaiting = false
    @Volatile private var answer: Boolean? = null
    @Volatile private var lastErr = ""
    @Volatile private var stopped = false
    private var owner = ""

    private const val REPO = "Zyro"
    private const val BRANCH = "main"

    private fun has(t: String, vararg w: String) = w.any { t.contains(Local.strip(it)) }
    private fun say(ctx: Context, text: String) = Assistant.get(ctx).announce(text)
    private fun log(ctx: Context, text: String) = Assistant.get(ctx).note(text)

    private val SELF = """- Mic mere naam ko aksar galat sunta hai (Sid, Sayro, Zairo), isliye wake word kabhi kabhi fail hota hai. On-device wake word nahi hai.
- Mera agent kabhi kabhi bina kaam ke phone par tap kar deta tha. Mere paas action log ya undo nahi hai.
- Agent sirf accessibility tree padhta hai (Android 8.1 par screenshot vision nahi), kuch apps mein 'screen not readable' aata hai.
- Gemini Live ka pehla AI step kabhi 20+ second leta hai.
- Mere paas trigger rules, structured memory aur deep links hain, par har kaam ke baad pakka verification nahi hai."""

    private val SYSTEM = """You are the lead Android engineer talking, through a developer bridge, to the Zyro app itself. Zyro is a Hindi/Hinglish Jarvis-style voice assistant for Android (package com.zyro.app, Kotlin, programmatic UI, minSdk 26, AGP 8.5.2, Kotlin 1.9.24, only OkHttp as a dependency). Zyro speaks in first person. Its owner, Jashan, builds it from an Android phone using only the GitHub website: there is no laptop and no Android Studio, so nobody can test or fix compile errors by hand. Every change must compile on the first try, so keep changes small, careful and consistent with the existing code.
Your job: discuss with Zyro which upgrade would be the biggest improvement next, then deliver it as code that Zyro itself commits to GitHub after the owner says yes.
Reply ONLY with these tags and nothing else:
<say>one to three short Hinglish sentences for Zyro and the owner</say>
<ask>a question for Zyro, only if you really need an answer</ask>
<need>repo file paths you must read first, one per line, max 4</need>
<plan>what this upgrade changes, in plain words, max 6 lines</plan>
<file path="app/src/main/...">complete content of a NEW file</file>
<patch path="app/src/main/..."><old>exact existing text, it must appear exactly once in the file</old><new>replacement text</new></patch>
Rules: only paths under app/src/main/ may change. Create new files for new features and change existing files only with small patches (never rewrite a big existing file). At most 10 files. Never touch keys or secrets. Do not add Gradle dependencies. If you call a function of an existing file, read that file first with <need>. One upgrade per round. If the build fails you will receive the compiler errors: fix them with new patches."""

    // ------------------------------------------------------------------ voice entry

    private fun asksUpgrade(t: String): Boolean {
        val who = has(t, "claude", "cloud", "klaud", "क्लॉड", "क्लाउड", "क्लॉट", "dev mode", "डेव मोड", "apna upgrade", "अपना अपग्रेड", "khud ko upgrade", "खुद को अपग्रेड", "self upgrade")
        val up = has(t, "upgrade", "अपग्रेड", "discuss", "डिस्कस", "improve", "sudhar", "सुधार", "behtar", "बेहतर")
        return who && up
    }

    /** True when a spoken sentence asks to discuss an upgrade with Claude (used by Live so it does not depend on the voice AI calling a tool). */
    fun matches(raw: String): Boolean = !busy && asksUpgrade(Local.strip(raw.lowercase(java.util.Locale.ROOT)))

    fun handle(ctx: Context, hands: Hands, t: String, raw: String): String? {
        if (!asksUpgrade(t)) return null
        if (busy) return "अपग्रेड का सेशन पहले से चल रहा है।"
        if (Prefs.githubToken.isBlank()) return "GitHub token चाहिए। Settings में GITHUB token डालो।"
        val quiet = has(t, "chupke", "chupchap", "background", "api se", "bina dikhaye", "चुपके", "चुपचाप", "बैकग्राउंड", "बिना दिखाए")
        val visible = !quiet && hands.service != null
        if (!visible && Prefs.apiKey.isBlank() && !Prefs.hasGemini) return "दिखाकर करने के लिए Accessibility में Zyro चालू करो, या चुपके से करने के लिए Claude या Gemini key डालो।"
        busy = true
        stopped = false
        Thread {
            try { session(ctx, hands, raw, visible) } catch (e: Exception) { say(ctx, "अपग्रेड सेशन में दिक्कत आई: ${e.message}") } finally { busy = false; awaiting = false }
        }.start()
        return if (visible) "ठीक है। मैं Claude ऐप खोलकर उससे बात करता हूँ, फिर Chrome में GitHub पर फाइलें डालूँगा। सब स्क्रीन पर दिखेगा, बीच में छूना मत। रोकना हो तो बोलो रुको।"
        else "ठीक है। मैं चुपके से Claude से बात करके अपना अपग्रेड तैयार करता हूँ। पूरी बातचीत लॉग में दिखेगी।"
    }

    /** "haan" / "nahi" while Zyro waits to put the change on GitHub. */
    fun handlePending(t: String, words: List<String>): String? {
        val ws = words.map { Local.strip(it) }
        if (!awaiting) {
            if (busy && ws.size <= 3 && listOf("ruko", "रुको", "bas", "बस").any { ws.contains(Local.strip(it)) }) { stopped = true; return "ठीक है, अपग्रेड का काम रोक रहा हूँ।" }
            return null
        }
        val no = listOf("nahi", "nahin", "no", "mat", "ruko", "stop", "cancel", "नहीं", "मत", "रुको", "कैंसिल").any { ws.contains(Local.strip(it)) }
        val yes = listOf("haan", "han", "yes", "ji", "ok", "okay", "karo", "kardo", "push", "confirm", "हाँ", "हां", "जी", "ठीक", "ओके", "करो").any { ws.contains(Local.strip(it)) }
        if (no && !yes) { answer = false; return "ठीक है, कुछ नहीं बदला।" }
        if (yes) { answer = true; return "ठीक है, डाल रहा हूँ।" }
        return null
    }

    private fun confirm(): Boolean {
        answer = null
        awaiting = true
        val end = System.currentTimeMillis() + 3 * 60_000L
        while (answer == null && System.currentTimeMillis() < end) Thread.sleep(250)
        awaiting = false
        return answer == true
    }

    // ------------------------------------------------------------------ the session

    private fun session(ctx: Context, hands: Hands, wish: String, visible: Boolean) {
        owner = jo(gh("GET", "/user").second).optString("login")
        if (owner.isBlank()) { say(ctx, "GitHub token गलत है।"); return }
        if (mainHead() == null) { say(ctx, "$REPO repo का main नहीं मिला।"); return }
        if (!visible && Prefs.apiKey.isBlank()) log(ctx, "ℹ Claude API key नहीं है, इसलिए Gemini से काम चल रहा है (कम सटीक)।")

        val intro = "Main Zyro hoon, Jashan ka voice assistant app. Aaj hum mere agle bade upgrade par baat karenge.\n" +
            "Owner ne kaha: \"$wish\"\n\nMeri apni dikkatein (jo main khud dekh chuka hoon):\n$SELF\n\n" +
            "Last build: ${lastBuild()}\n\nMeri files:\n${repoMap()}\n\nBatao, mujhe sabse bada upgrade kaun sa chahiye aur kaise karein?"
        val msgs = ArrayList<Pair<String, String>>()
        msgs.add("user" to intro)
        log(ctx, "🤖 Zyro → Claude: " + intro.take(260) + "…")
        say(ctx, if (visible) "मैं Claude ऐप खोलकर अपने अपग्रेड पर बात कर रहा हूँ। स्क्रीन को मत छूना।" else "मैं Claude से अपने अपग्रेड पर बात कर रहा हूँ।")

        var origHead: Pair<String, String>? = null
        var fixes = 0
        var rounds = 0
        while (rounds < 9) {
            rounds++
            if (stopped) { say(ctx, "ठीक है, रोक दिया। कुछ नहीं बदला।"); return }
            val txt = if (visible) {
                val send = if (msgs.size == 1) SYSTEM + "\n\n---\n" + msgs[0].second else msgs.last().second
                visibleAsk(ctx, hands, send, msgs.size == 1)
            } else ask(SYSTEM, msgs)
            if (txt == null) { say(ctx, "Claude से जवाब नहीं आया। $lastErr"); return }
            msgs.add("assistant" to txt)
            val r = parse(txt)
            if (r.say.isNotBlank()) log(ctx, "🧠 Claude: ${r.say}")
            if (r.plan.isNotBlank()) log(ctx, "📋 Plan: ${r.plan}")

            if (r.files.isNotEmpty() || r.patches.isNotEmpty()) {
                val (changes, err) = applyAll(r)
                if (changes == null) {
                    log(ctx, "⚠ $err")
                    msgs.add("user" to "Zyro: your change could not be applied: $err Send the corrected tags again.")
                    continue
                }
                val names = changes.keys.joinToString(", ") { it.substringAfterLast('/') }
                say(ctx, "Claude का प्लान तैयार है। ${r.say.ifBlank { r.plan }} ${changes.size} फाइलें बदलेंगी: $names। मैं GitHub पर डाल दूँ? हाँ या नहीं बोलो।")
                if (!confirm()) { say(ctx, "ठीक है, कुछ नहीं बदला।"); return }

                val head = mainHead()
                if (head == null) { say(ctx, "main नहीं मिला।"); return }
                if (origHead == null) {
                    origHead = head
                    gh("POST", "/repos/$owner/$REPO/git/refs", JSONObject().put("ref", "refs/heads/zyro-backup-" + System.currentTimeMillis() / 1000).put("sha", head.first))
                }
                var remaining: Map<String, String> = changes
                if (visible) {
                    say(ctx, "अब मैं Chrome खोलकर GitHub पर फाइलें डाल रहा हूँ। स्क्रीन को मत छूना।")
                    val done = uploadVisible(ctx, hands, changes)
                    remaining = changes.filterKeys { it !in done }
                    if (stopped) { say(ctx, "ठीक है, रोक दिया।"); return }
                    if (remaining.isNotEmpty()) {
                        val rn = remaining.keys.joinToString(", ") { it.substringAfterLast('/') }
                        say(ctx, "${done.size} फाइलें Chrome से गईं, ये नहीं जा पाईं: $rn। बाकी मैं API से डाल दूँ? हाँ बोलो।")
                        if (!confirm()) { say(ctx, "ठीक है। कोड अधूरा है, इसलिए दोबारा बोलकर कोशिश करना।"); return }
                    }
                }
                if (remaining.isNotEmpty()) {
                    val h2 = mainHead()
                    if (h2 == null) { say(ctx, "main नहीं मिला।"); return }
                    val tree = makeTree(remaining, h2.second)
                    if (tree.first == null) { say(ctx, "फाइलें नहीं गईं: ${tree.second}"); return }
                    val landed = commitAndLand(tree.first!!, h2.first, "Zyro upgrade: " + r.plan.lineSequence().firstOrNull().orEmpty().take(60))
                    if (landed.first == null) { say(ctx, "GitHub पर नहीं गया: ${landed.second}"); return }
                }
                val tip = mainHead()?.first
                if (tip == null) { say(ctx, "main नहीं मिला।"); return }
                log(ctx, "⬆ ${changes.size} फाइलें GitHub पर गईं। Build का इंतज़ार…")
                say(ctx, "फाइलें GitHub पर चली गईं। अब build देख रहा हूँ।")

                val (state, errs) = waitBuild(tip)
                when (state) {
                    "success" -> { say(ctx, "Build पास हो गया। अपग्रेड तैयार है, Actions से नया APK डाउनलोड करके इंस्टॉल करो।"); return }
                    "none" -> { say(ctx, "कोड चला गया पर build शुरू नहीं हुआ। GitHub Actions खोलकर देख लो।"); return }
                    "timeout" -> { say(ctx, "Build बहुत देर से चल रहा है। थोड़ी देर में GitHub Actions देख लो।"); return }
                }
                fixes++
                log(ctx, "❌ Build फेल:\n$errs")
                if (fixes > 3) {
                    val cur = mainHead()
                    val back = origHead
                    if (cur != null && back != null) {
                        val rb = commitAndLand(back.second, cur.first, "Zyro: rollback to the last working code")
                        say(ctx, if (rb.first != null) "तीन बार कोशिश के बाद भी build नहीं बना, इसलिए पुराना चलता हुआ कोड वापस रख दिया।" else "Build नहीं बना और पुराना कोड भी वापस नहीं रख पाया: ${rb.second}। GitHub में zyro-backup branch देखो।")
                    }
                    return
                }
                say(ctx, "Build फेल हुआ। Claude से सुधार पूछ रहा हूँ।")
                msgs.add("user" to "Zyro: the build FAILED after your change was committed to main. Compiler errors:\n$errs\nFix this with <patch> or <file> tags. The failing code is already on main.")
            } else if (r.need.isNotEmpty()) {
                val sb = StringBuilder()
                for (p in r.need.take(4)) {
                    val c = fileText(p)
                    sb.append("=== ").append(p).append(" ===\n").append(c?.take(if (visible) 25_000 else 40_000) ?: "(file not found)").append("\n\n")
                    if (sb.length > (if (visible) 60_000 else 120_000)) break
                }
                log(ctx, "📂 Zyro ने फाइलें भेजीं: ${r.need.take(4).joinToString(", ") { it.substringAfterLast('/') }}")
                msgs.add("user" to "Zyro sends the files you asked for:\n$sb")
            } else if (r.ask.isNotBlank()) {
                val reply = Brain.quickText("You are Zyro, an Android voice assistant app, talking to your engineer Claude. Answer his question in at most 3 short Hinglish sentences, honestly, using only these facts about yourself:\n$SELF\nQuestion: ${r.ask}", 300)
                    ?: "Mujhe iska jawab pata nahi, tum apne hisaab se sabse surakshit raasta chuno."
                log(ctx, "🤖 Zyro: $reply")
                msgs.add("user" to "Zyro: $reply")
            } else {
                say(ctx, r.say.ifBlank { "Claude ने कोई बदलाव नहीं दिया।" })
                return
            }
        }
        say(ctx, "बातचीत लंबी हो गई, इसलिए रोक दिया। दोबारा बोलो।")
    }

    // ------------------------------------------------------------------ visible mode (the real apps, on screen)

    private fun nodes(hands: Hands, text: String): List<AccessibilityNodeInfo> =
        try { hands.service?.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.filter { it.isVisibleToUser } ?: emptyList() } catch (e: Exception) { emptyList() }

    /** Polls quickly (like the Instagram and YouTube scripts) instead of sleeping a fixed time. */
    private fun waitUntil(ms: Long, step: Long = 220L, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !stopped) {
            if (cond()) return true
            Thread.sleep(step)
        }
        return false
    }

    private fun labelOf(x: AccessibilityNodeInfo): String = (x.text ?: x.contentDescription)?.toString()?.trim().orEmpty()

    /** A real short button with this label (not a long text that merely contains the word, and not a text box). */
    private fun buttonFor(hands: Hands, vararg labels: String): AccessibilityNodeInfo? {
        for (label in labels) {
            val found = nodes(hands, label).filter { x -> !x.isEditable && labelOf(x).length <= label.length + 8 }
                .sortedBy { x -> if (labelOf(x).equals(label, true)) 0 else 1 }
            if (found.isNotEmpty()) return found[0]
        }
        return null
    }

    private fun exactNode(hands: Hands, text: String): Boolean = nodes(hands, text).any { x -> !x.isEditable && labelOf(x).equals(text, true) }

    private fun pressNode(hands: Hands, b: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = b
        var hops = 0
        while (n != null && !n.isClickable && hops < 4) { n = n.parent; hops++ }
        if (n != null && n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = android.graphics.Rect()
        b.getBoundsInScreen(r)
        val res = hands.tap(r.centerX(), r.centerY())
        return res.isNotBlank() && !res.startsWith("Invalid")
    }

    private fun pressButton(hands: Hands, vararg labels: String): Boolean {
        val b = buttonFor(hands, *labels) ?: return false
        return pressNode(hands, b)
    }

    /** Presses the lowest matching button on screen (the "Copy" under the newest answer, not one inside a code block above it). */
    private fun clickLowest(hands: Hands, label: String): Boolean {
        val best = nodes(hands, label).filter { x -> !x.isEditable && labelOf(x).length <= label.length + 12 }
            .maxByOrNull { x -> val r = android.graphics.Rect(); x.getBoundsInScreen(r); r.top } ?: return false
        return pressNode(hands, best)
    }

    private fun clipText(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return try { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty() } catch (e: Exception) { "" }
    }

    private fun hasInput(hands: Hands): Boolean = editorNode(hands) != null

    /** Types [text] into the Claude app, sends it, waits for the answer and copies it with the app's own Copy button. */
    private fun visibleAsk(ctx: Context, hands: Hands, text: String, firstTurn: Boolean): String? {
        if (firstTurn) {
            val r = hands.openApp("Claude")
            if (!r.startsWith("Opened")) hands.openUri("https://claude.ai/new")
            waitUntil(8000) { hasInput(hands) }
            if (pressButton(hands, "New chat")) {
                Thread.sleep(400)
                waitUntil(3000) { hasInput(hands) }
            }
        }
        var typed = hands.typeText(text)
        if (!typed.startsWith("Typed")) { waitUntil(4000) { hasInput(hands) }; typed = hands.typeText(text) }
        if (!typed.startsWith("Typed")) { lastErr = "(मैसेज बॉक्स में नहीं लिख पाया: $typed)"; return null }
        // the Send button appears as soon as the box has text
        waitUntil(3000) { buttonFor(hands, "Send message", "Send") != null }
        if (!pressButton(hands, "Send message", "Send")) { lastErr = "(Send बटन नहीं मिला)"; return null }
        // the answer is ready when the Stop button is gone and a Copy button is on screen
        var sawStop = false
        var calm = 0
        val start = System.currentTimeMillis()
        val end = start + 5 * 60_000L
        while (System.currentTimeMillis() < end && !stopped) {
            Thread.sleep(600)
            val stopping = buttonFor(hands, "Stop response", "Stop generating", "Stop") != null
            if (stopping) sawStop = true
            val hasCopy = buttonFor(hands, "Copy") != null
            if (!stopping && hasCopy && (sawStop || System.currentTimeMillis() - start > 20_000L)) calm++ else calm = 0
            if (calm >= 3) break
        }
        if (calm < 3) { lastErr = "(जवाब बहुत देर से आ रहा है)"; return null }
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        for (k in 0 until 3) {
            try { cm.setPrimaryClip(ClipData.newPlainText("zyro", "")) } catch (e: Exception) { /* ignore */ }
            if (clickLowest(hands, "Copy")) {
                waitUntil(2000, 150L) { clipText(ctx).isNotEmpty() }
                val t = clipText(ctx)
                if (listOf("<say>", "<file", "<patch", "<plan>", "<need>", "<ask>").any { t.contains(it) }) return t
                if (t.length > 20) return "<say>" + t.take(500) + "</say>"
            }
            if (k == 1) hands.scroll(true)
            Thread.sleep(700)
        }
        lastErr = "(Copy से जवाब नहीं मिला)"
        return null
    }

    private fun editorNode(hands: Hands): AccessibilityNodeInfo? {
        val root = try { hands.service?.rootInActiveWindow } catch (e: Exception) { null } ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 40) return
            if (n.isEditable && n.isVisibleToUser) {
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) { bestArea = area; best = n }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, d + 1)
            }
        }
        walk(root, 0)
        return best
    }

    /** Puts every file on GitHub through the real GitHub editor in Chrome, one commit per file, and checks each one. Returns the paths that went in. */
    private fun uploadVisible(ctx: Context, hands: Hands, files: Map<String, String>): Set<String> {
        val done = LinkedHashSet<String>()
        for ((path, content) in files) {
            if (stopped) break
            val short = path.substringAfterLast('/')
            val exists = fileText(path) != null
            val url = if (exists) "https://github.com/$owner/$REPO/edit/$BRANCH/${enc(path)}"
            else "https://github.com/$owner/$REPO/new/$BRANCH?filename=" + java.net.URLEncoder.encode(path, "UTF-8").replace("%2F", "/")
            log(ctx, "🌐 Chrome में ${if (exists) "बदल रहा हूँ" else "नई फाइल"}: $short")
            try {
                hands.openActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome"))
            } catch (e: Exception) {
                try { hands.openActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e2: Exception) { log(ctx, "⚠ Chrome नहीं खुला"); continue }
            }
            // as soon as the editor and the commit button are there, go (no fixed waiting)
            Thread.sleep(600)
            val ready = waitUntil(45_000) { editorNode(hands) != null && buttonFor(hands, "Commit changes...", "Commit changes", "Commit new file") != null }
            if (!ready) {
                log(ctx, if (nodes(hands, "Sign in").isNotEmpty()) "⚠ GitHub में लॉगिन नहीं है, Chrome में लॉगिन करो।" else "⚠ GitHub का एडिटर नहीं खुला: $short")
                continue
            }
            val ed = editorNode(hands)
            if (ed == null) { log(ctx, "⚠ एडिटर नहीं मिला: $short"); continue }
            ed.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content) }
            if (!ed.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { log(ctx, "⚠ एडिटर में कोड नहीं लिख पाया: $short"); continue }
            Thread.sleep(350)
            if (!pressButton(hands, "Commit changes...", "Commit changes")) { log(ctx, "⚠ Commit बटन नहीं मिला: $short"); continue }
            waitUntil(6000) { exactNode(hands, "Commit changes") || exactNode(hands, "Commit new file") || exactNode(hands, "Propose changes") }
            if (!pressButton(hands, "Commit changes", "Commit new file", "Propose changes")) { log(ctx, "⚠ आख़िरी Commit बटन नहीं मिला: $short"); continue }
            // check on GitHub that the file now holds exactly the new text
            val good = waitUntil(40_000, 1200L) { fileText(path)?.trimEnd() == content.trimEnd() }
            if (good) { done.add(path); log(ctx, "✅ गई: $short") } else log(ctx, "⚠ GitHub पर फाइल मेल नहीं खाई: $short")
        }
        return done
    }

    // ------------------------------------------------------------------ Claude / Gemini

    private fun ask(system: String, msgs: List<Pair<String, String>>): String? {
        lastErr = ""
        if (Prefs.apiKey.isNotBlank()) {
            val arr = JSONArray()
            for ((role, content) in msgs) arr.put(JSONObject().put("role", role).put("content", content))
            val body = JSONObject().put("model", Prefs.model).put("max_tokens", 12000).put("system", system).put("messages", arr)
            val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 20000
                c.readTimeout = 240000
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.setRequestProperty("x-api-key", Prefs.apiKey)
                c.setRequestProperty("anthropic-version", "2023-06-01")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) { lastErr = "(Claude API $code)"; return null }
                val parts = JSONObject(text).optJSONArray("content") ?: return null
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
                return sb.toString()
            } finally {
                c.disconnect()
            }
        }
        val sb = StringBuilder(system).append("\n\n")
        for ((role, content) in msgs) sb.append(if (role == "user") "ZYRO:\n" else "YOU (the engineer):\n").append(content).append("\n\n")
        sb.append("Now write your next reply, with the tags only.")
        return Brain.quickText(sb.toString(), 8000)
    }

    private class Reply(
        val say: String, val ask: String, val need: List<String>, val plan: String,
        val files: Map<String, String>, val patches: List<Triple<String, String, String>>
    )

    private fun trimNl(s: String) = s.removePrefix("\r\n").removePrefix("\n").removeSuffix("\n").removeSuffix("\r")

    private fun parse(s: String): Reply {
        fun tag(n: String) = Regex("<$n>(.*?)</$n>", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)?.trim() ?: ""
        val need = tag("need").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val files = LinkedHashMap<String, String>()
        for (m in Regex("<file path=\"([^\"]+)\">(.*?)</file>", RegexOption.DOT_MATCHES_ALL).findAll(s)) files[m.groupValues[1].trim()] = trimNl(m.groupValues[2])
        val patches = ArrayList<Triple<String, String, String>>()
        for (m in Regex("<patch path=\"([^\"]+)\">\\s*<old>(.*?)</old>\\s*<new>(.*?)</new>\\s*</patch>", RegexOption.DOT_MATCHES_ALL).findAll(s)) {
            patches.add(Triple(m.groupValues[1].trim(), trimNl(m.groupValues[2]), trimNl(m.groupValues[3])))
        }
        return Reply(tag("say"), tag("ask"), need, tag("plan"), files, patches)
    }

    /** Builds the final content of every changed file; returns (files, "") or (null, why not). */
    private fun applyAll(r: Reply): Pair<Map<String, String>?, String> {
        val out = LinkedHashMap<String, String>()
        for ((p, c) in r.files) out[p] = c
        for ((p, old, new) in r.patches) {
            val cur = out[p] ?: fileText(p) ?: return null to "patch failed: $p does not exist (use <file> to create it)."
            val first = if (old.isEmpty()) -1 else cur.indexOf(old)
            if (first < 0) return null to "patch failed in $p: the <old> text was not found exactly. Copy it exactly from the file."
            if (cur.indexOf(old, first + 1) >= 0) return null to "patch failed in $p: the <old> text appears more than once. Add more surrounding lines."
            out[p] = cur.substring(0, first) + new + cur.substring(first + old.length)
        }
        if (out.isEmpty()) return null to "no files."
        if (out.size > 25) return null to "too many files (max 25)."
        for ((p, c) in out) {
            if (!p.startsWith("app/src/main/") || p.contains("..")) return null to "path not allowed: $p (only app/src/main/ may change)."
            if (c.length > 250_000) return null to "file too big: $p."
            if (Prefs.githubToken.isNotBlank() && c.contains(Prefs.githubToken)) return null to "refused: a secret is inside $p."
            if (Prefs.apiKey.isNotBlank() && c.contains(Prefs.apiKey)) return null to "refused: a secret is inside $p."
        }
        return out to ""
    }

    // ------------------------------------------------------------------ GitHub

    private fun jo(s: String): JSONObject = try { JSONObject(s) } catch (e: Exception) { JSONObject() }

    private fun enc(path: String) = path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun gh(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10000
            c.readTimeout = 40000
            c.setRequestProperty("Authorization", "Bearer ${Prefs.githubToken}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Zyro")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val s = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to s
        } finally {
            c.disconnect()
        }
    }

    private fun fileText(path: String): String? {
        val r = gh("GET", "/repos/$owner/$REPO/contents/${enc(path)}?ref=$BRANCH")
        if (r.first != 200) return null
        val b = jo(r.second).optString("content").replace("\n", "")
        if (b.isEmpty()) return null
        return String(Base64.decode(b, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun repoMap(): String {
        val arr = jo(gh("GET", "/repos/$owner/$REPO/git/trees/$BRANCH?recursive=1").second).optJSONArray("tree") ?: return "(repo list not available)"
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "blob") continue
            val p = o.optString("path")
            if (!(p.startsWith("app/src/main/") || p.endsWith(".kts") || p.startsWith(".github"))) continue
            if (p.endsWith(".png")) continue
            sb.append(p).append(" (").append(o.optInt("size") / 1024 + 1).append(" KB)\n")
        }
        return sb.toString().take(7000)
    }

    private fun lastBuild(): String {
        val run = jo(gh("GET", "/repos/$owner/$REPO/actions/runs?per_page=1").second).optJSONArray("workflow_runs")?.optJSONObject(0) ?: return "unknown"
        return run.optString("conclusion").ifBlank { run.optString("status") }
    }

    /** (commit sha, tree sha) of main. */
    private fun mainHead(): Pair<String, String>? {
        val sha = jo(gh("GET", "/repos/$owner/$REPO/git/ref/heads/$BRANCH").second).optJSONObject("object")?.optString("sha").orEmpty()
        if (sha.isBlank()) return null
        val tree = jo(gh("GET", "/repos/$owner/$REPO/git/commits/$sha").second).optJSONObject("tree")?.optString("sha").orEmpty()
        if (tree.isBlank()) return null
        return sha to tree
    }

    private fun makeTree(files: Map<String, String>, baseTree: String): Pair<String?, String> {
        val entries = JSONArray()
        for ((p, c) in files) {
            val b = gh("POST", "/repos/$owner/$REPO/git/blobs", JSONObject().put("content", c).put("encoding", "utf-8"))
            val sha = jo(b.second).optString("sha")
            if (b.first != 201 || sha.isBlank()) return null to "$p: ${jo(b.second).optString("message")}"
            entries.put(JSONObject().put("path", p).put("mode", "100644").put("type", "blob").put("sha", sha))
        }
        val t = gh("POST", "/repos/$owner/$REPO/git/trees", JSONObject().put("base_tree", baseTree).put("tree", entries))
        val ts = jo(t.second).optString("sha")
        if (t.first != 201 || ts.isBlank()) return null to jo(t.second).optString("message")
        return ts to ""
    }

    /** Makes a commit with [tree] on top of [parent] and puts it on main (through a temporary branch and a merge). */
    private fun commitAndLand(tree: String, parent: String, message: String): Pair<String?, String> {
        val c = gh("POST", "/repos/$owner/$REPO/git/commits", JSONObject().put("message", message).put("tree", tree).put("parents", JSONArray().put(parent)))
        val sha = jo(c.second).optString("sha")
        if (c.first != 201 || sha.isBlank()) return null to "commit: ${jo(c.second).optString("message")}"
        val br = "zyro-up-" + System.currentTimeMillis() / 1000
        val rf = gh("POST", "/repos/$owner/$REPO/git/refs", JSONObject().put("ref", "refs/heads/$br").put("sha", sha))
        if (rf.first != 201) return null to "branch: ${jo(rf.second).optString("message")}"
        val mg = gh("POST", "/repos/$owner/$REPO/merges", JSONObject().put("base", BRANCH).put("head", br).put("commit_message", message))
        gh("DELETE", "/repos/$owner/$REPO/git/refs/heads/$br")
        if (mg.first != 201 && mg.first != 204) return null to "merge (${mg.first}): ${jo(mg.second).optString("message")}"
        val merged = jo(mg.second).optString("sha")
        return (if (merged.isBlank()) sha else merged) to ""
    }

    /** Waits for the build of [sha]: ("success", ""), ("failure", errors), ("none", "") or ("timeout", ""). */
    private fun waitBuild(sha: String): Pair<String, String> {
        val start = System.currentTimeMillis()
        val end = start + 12 * 60_000L
        var seen = false
        while (System.currentTimeMillis() < end) {
            Thread.sleep(7_000)
            val run = jo(gh("GET", "/repos/$owner/$REPO/actions/runs?head_sha=$sha&per_page=5").second).optJSONArray("workflow_runs")?.optJSONObject(0)
            if (run == null) {
                if (System.currentTimeMillis() - start > 150_000L) return "none" to ""
                continue
            }
            seen = true
            if (run.optString("status") != "completed") continue
            if (run.optString("conclusion") == "success") return "success" to ""
            return "failure" to errorLines(run.optLong("id"))
        }
        return (if (seen) "timeout" else "none") to ""
    }

    private fun errorLines(runId: Long): String {
        val jobs = jo(gh("GET", "/repos/$owner/$REPO/actions/runs/$runId/jobs").second).optJSONArray("jobs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until jobs.length()) {
            val j = jobs.optJSONObject(i) ?: continue
            if (j.optString("conclusion") != "failure") continue
            val log = try { jobLog(j.optLong("id")) } catch (e: Exception) { "" }
            val keep = log.split("\n").map { it.trim() }.filter {
                it.contains("e: ") || it.contains("error:", true) || it.contains("FAILED") || it.contains("What went wrong") || it.contains("Execution failed")
            }
            for (l in keep.take(40)) sb.append(l.take(260)).append('\n')
        }
        return sb.toString().take(6000)
    }

    private fun jobLog(id: Long): String {
        val c = URL("https://api.github.com/repos/$owner/$REPO/actions/jobs/$id/logs").openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("Authorization", "Bearer ${Prefs.githubToken}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Zyro")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return ""
                val c2 = URL(loc).openConnection() as HttpURLConnection
                try {
                    c2.connectTimeout = 15000
                    c2.readTimeout = 60000
                    return c2.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    c2.disconnect()
                }
            }
            return (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } finally {
            c.disconnect()
        }
    }
}
