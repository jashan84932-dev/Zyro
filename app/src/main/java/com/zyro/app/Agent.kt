package com.zyro.app

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.Locale

/**
 * Phone agent: "Zyro, Zomato par 2 biryani order karo".
 * Loop: read the screen -> ask Gemini what to do next -> do it -> repeat.
 * Risky steps (paying, ordering, sending, deleting) always need a spoken "haan".
 * It never types into password fields and never works inside payment apps.
 */
object Agent {

    private var appCtx: Context? = null

    @Volatile var running = false
        private set
    @Volatile private var cancelled = false
    @Volatile private var awaiting: String? = null // question waiting for yes/no
    @Volatile private var answer: Boolean? = null
    @Volatile private var userReply: String? = null

    private class El(val id: Int, val node: AccessibilityNodeInfo, val label: String, val r: Rect, val clickable: Boolean, val editable: Boolean)

    internal val BLOCKED_PKGS = listOf(
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "com.sbi.upi", "com.csam.icici.bank.imobile", "com.snapwork.hdfc", "com.axis.mobile", "com.dreamplug.androidapp",
        "com.freecharge.android", "com.amazon.mShop.android.shopping.pay", "com.android.settings", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.vending"
    )

    private val RISKY = listOf(
        "pay", "place order", "order now", "buy", "purchase", "checkout", "send", "delete", "remove",
        "transfer", "book", "subscribe", "continue to pay", "upi", "tweet", "uninstall", "factory",
        "भुगतान", "पेमेंट", "ऑर्डर", "खरीद", "भेज", "हटा", "डिलीट", "बुक"
    )

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun low(x: String) = x.lowercase(Locale.ROOT)

    // ------------------------------------------------------------------ voice entry

    private val APPS = listOf(
        "zomato", "swiggy", "blinkit", "zepto", "instamart", "amazon", "flipkart", "myntra", "meesho", "uber", "ola", "rapido",
        "irctc", "makemytrip", "redbus", "bookmyshow", "youtube", "instagram", "spotify", "maps", "netflix", "hotstar", "jiohotstar", "jio hotstar", "gmail", "prime video", "sonyliv", "zee5", "whatsapp", "telegram",
        "जोमैटो", "ज़ोमैटो", "स्विगी", "ब्लिंकिट", "ज़ेप्टो", "अमेज़न", "अमेजन", "फ्लिपकार्ट", "मिंत्रा", "उबर", "ओला", "रैपिडो"
    )
    private val ACTS = listOf(
        "order", "book", "buy", "search", "download", "install", "ऑर्डर", "बुक", "खरीद", "मंगा", "मँगा", "मंगवा", "ढूंढ", "खोज",
        "mangwa", "manga", "kharid", "dhundh", "khoj", "add to cart", "cart"
    )
    private val ACTS2 = listOf("chalu", "चालू", "chala", "चला", "laga", "लगा", "play", "login", "log in", "लॉगिन", "dekh", "देख", "match", "मैच", "sign in")
    private val TRIGGERS = listOf(
        "agent", "एजेंट", "autopilot", "ऑटोपायलट", "khud kar", "खुद कर", "apne aap kar", "अपने आप कर", "mere liye kar", "मेरे लिए कर"
    )

    fun handle(hands: Hands, t: String, words: List<String>, raw: String): String? {
        val ctx = appCtx ?: return null
        val rl = low(raw)
        if (running && (rl.contains("agent status") || rl.contains("kya kar rahe") || rl.contains("क्या कर रहे"))) {
            return "मैं अभी आपका काम कर रहा हूँ: $task"
        }
        val tn = Local.strip(rl)
        val explicit = TRIGGERS.any { tn.contains(Local.strip(it)) }
        val smart = !explicit && APPS.any { tn.contains(Local.strip(it)) } && ACTS.any { tn.contains(Local.strip(it)) } &&
            (rl.contains(" par ") || rl.contains(" पर ") || rl.contains(" me ") || rl.contains(" में ") || rl.contains(" on ") || rl.contains(" se ") || rl.contains(" से "))
        val heavy = !explicit && !smart && APPS.any { tn.contains(Local.strip(it)) } && ACTS2.any { tn.contains(Local.strip(it)) } &&
            (tn.contains(Local.strip("login")) || tn.contains(Local.strip("लॉगिन")) || tn.contains(Local.strip("karke")) || tn.contains(Local.strip("करके")) || raw.trim().split(Regex("\\s+")).size >= 6)
        val connectors = listOf("phir", "फिर", "uske baad", "उसके बाद", "then", "jakar", "जाकर", "jaakar", "karke", "करके")
        val multi = !explicit && !smart && raw.trim().split(Regex("\\s+")).size >= 9 &&
            connectors.any { tn.contains(Local.strip(it)) } &&
            listOf("open", "kholo", "खोल", "baat", "बात", "upload", "bhej", "भेज", "search", "ढूंढ", "download", "लिख", "likh").any { tn.contains(Local.strip(it)) }
        if (!explicit && !smart && !multi && !heavy) return null
        if (running) return "मैं पहले से एक काम कर रहा हूँ। उसे रोकना हो तो बोलो रुको।"
        var job = raw.trim()
        for (tr in TRIGGERS) {
            val i = low(job).indexOf(tr)
            if (i >= 0) {
                job = (job.substring(0, i) + " " + job.substring(i + tr.length)).trim()
                break
            }
        }
        job = job.trim(' ', ',', ':', '.')
        if (job.length < 4) return "बताओ क्या करना है? जैसे: एजेंट, ज़ोमैटो पर दो बिरयानी ऑर्डर करो।"
        return start(ctx, hands, job)
    }

    private val YES = listOf("haan", "han", "yes", "ha", "ji", "ok", "okay", "kar do", "karo", "हाँ", "हां", "हा", "जी", "ठीक", "ओके", "कर दो", "करो", "confirm", "कन्फर्म")
    private val NO = listOf("nahi", "nahin", "no", "mat", "ruko", "ruk", "stop", "cancel", "band", "नहीं", "मत", "रुको", "रुक", "बंद", "कैंसिल", "छोड़")

    /** Answers to the agent's questions, and "ruko" while it works. */
    fun handlePending(t: String, words: List<String>): String? {
        if (!running) return null
        val ws = words.map { Local.strip(it) }
        val no = NO.any { ws.contains(Local.strip(it)) }
        val yes = YES.any { ws.contains(Local.strip(it)) }
        if (awaiting != null) {
            if (no && !yes) {
                answer = false
                return "ठीक है, रोक दिया।"
            }
            if (yes) {
                answer = true
                return "ठीक है।"
            }
            if (awaiting == "ask") {
                userReply = t
                return "ठीक है।"
            }
            return null
        }
        if (no && ws.size <= 4) {
            cancel()
            return "ठीक है, काम रोक दिया।"
        }
        return null
    }

    fun cancel() {
        if (!running) return
        cancelled = true
        answer = false
        userReply = ""
    }

    // ------------------------------------------------------------------ the loop

    @Volatile private var task = ""

    fun start(ctx: Context, hands: Hands, job: String): String {
        if (!Prefs.hasGemini) return "एजेंट के लिए Gemini key चाहिए। सेटिंग में डालो।"
        if (hands.service == null) return "एजेंट के लिए Accessibility में Zyro चालू करो।"
        if (running) return "मैं पहले से एक काम कर रहा हूँ।"
        running = true
        cancelled = false
        awaiting = null
        answer = null
        userReply = null
        task = job
        Thread { try { loop(ctx, hands, job) } catch (e: Exception) { say("काम के बीच में दिक्कत आ गई: ${e.message}") } finally { running = false; awaiting = null } }.start()
        return "ठीक है, शुरू करता हूँ। बीच में रोकना हो तो बोलो रुको।"
    }

    private fun say(text: String) {
        val c = appCtx ?: return
        Assistant.get(c).announce(text)
    }

    private fun observe(hands: Hands): Triple<String, List<El>, String>? {
        val svc = hands.service ?: return null
        val root = svc.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: ""
        val list = ArrayList<El>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (list.size >= 55 || depth > 30) return
            val label = if (n.isPassword) "(password field)" else listOfNotNull(n.text?.toString()?.take(50), n.contentDescription?.toString()?.take(50))
                .filter { it.isNotBlank() }.joinToString(" | ")
            val interesting = label.isNotEmpty() || n.isClickable || n.isEditable
            if (interesting && n.isVisibleToUser) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) list.add(El(list.size + 1, n, label, r, n.isClickable, n.isEditable))
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        val sb = StringBuilder()
        for (e in list) {
            sb.append(e.id).append(": ")
            sb.append(if (e.label.isEmpty()) "(no label @${e.r.centerX()},${e.r.centerY()})" else "\"" + e.label + "\"")
            val f = ArrayList<String>()
            if (e.clickable) f.add("tap")
            if (e.editable) f.add("input")
            if (f.isNotEmpty()) sb.append(" [").append(f.joinToString(",")).append("]")
            sb.append('\n')
        }
        return Triple(pkg, list, sb.toString())
    }

    private fun risky(label: String): Boolean {
        val l = low(label)
        if (l.isEmpty()) return false
        return RISKY.any { l.contains(it) }
    }

    /** Asks a yes/no question out loud and waits up to 45 s. */
    private fun confirm(question: String): Boolean {
        answer = null
        awaiting = "confirm"
        say(question)
        val end = System.currentTimeMillis() + 45_000
        while (answer == null && !cancelled && System.currentTimeMillis() < end) Thread.sleep(250)
        val a = answer == true
        awaiting = null
        answer = null
        return a
    }

    private fun askUser(question: String): String? {
        userReply = null
        awaiting = "ask"
        say(question)
        val end = System.currentTimeMillis() + 60_000
        while (userReply == null && !cancelled && System.currentTimeMillis() < end) Thread.sleep(250)
        val r = userReply
        awaiting = null
        userReply = null
        return if (cancelled || r.isNullOrBlank()) null else r
    }

    @Volatile private var planText = ""

    private val APP_NAMES = mapOf(
        "hotstar" to listOf("JioHotstar", "Hotstar", "Disney+ Hotstar"),
        "jiohotstar" to listOf("JioHotstar", "Hotstar", "Disney+ Hotstar"),
        "jio hotstar" to listOf("JioHotstar", "Hotstar", "Disney+ Hotstar"),
        "prime video" to listOf("Prime Video", "Amazon Prime Video"),
        "prime" to listOf("Prime Video", "Amazon Prime Video")
    )

    private fun openSmart(hands: Hands, name: String): String {
        val names = (APP_NAMES[low(name).trim()] ?: emptyList()) + name
        var last = ""
        for (n in names) {
            last = hands.openApp(n)
            if (last.startsWith("Opened")) return last
        }
        return last
    }

    /** Hands over to the user (password, OTP ...) and continues by itself once they are done. Returns false on timeout / cancel. */
    private fun waitForUser(hands: Hands, msg: String, pwd: Boolean): Boolean {
        userReply = null
        awaiting = "ask"
        say(msg)
        val begin = System.currentTimeMillis()
        val end = begin + 5 * 60_000L
        var sawInput = true
        while (!cancelled && System.currentTimeMillis() < end) {
            if (userReply != null) break
            Thread.sleep(1000)
            val o = observe(hands) ?: continue
            val waited = System.currentTimeMillis() - begin
            if (waited < 6000) continue
            val stillPwd = o.second.any { it.label == "(password field)" }
            val anyInput = o.second.any { it.editable }
            if (pwd && !stillPwd) break
            if (!pwd) {
                if (anyInput) sawInput = true
                if (sawInput && !anyInput) break
            }
        }
        val ok = !cancelled && System.currentTimeMillis() < end
        awaiting = null
        userReply = null
        return ok
    }

    private fun verifyDone(hands: Hands, job: String, history: List<String>): Pair<Boolean, String> {
        val o = observe(hands) ?: return Pair(true, "")
        val p = buildString {
            append("Task given to a phone agent: \"").append(job).append("\"\n")
            append("Current app: ").append(o.first).append("\nScreen elements:\n").append(o.third)
            append("Recent actions:\n"); history.takeLast(8).forEach { append("- ").append(it).append('\n') }
            append("""
Is the task REALLY completed right now, judging only from the screen? A login page, home page, loading spinner, search page, error, popup, or a list where the item is not yet playing/added/opened means NOT complete. For 'play/start something' the player or live content must be visible.
Reply ONLY JSON: {"complete":true,"why":"","next":"the concrete next action if not complete"}""")
        }
        val r = Brain.quickJson(p, 160, 12000, true) ?: return Pair(true, "")
        return try {
            val j = JSONObject(r.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            Pair(j.optBoolean("complete", true), j.optString("next", j.optString("why", "")))
        } catch (e: Exception) { Pair(true, "") }
    }

    private fun loop(ctx: Context, hands: Hands, job: String) {
        val history = ArrayList<String>()
        var started = System.currentTimeMillis()
        var lastSig = ""
        var same = 0
        var step = 0
        var failPush = 0
        var doneRejects = 0
        var mainApp = ""
        var notInstalled = 0
        planText = ""
        Thread {
            planText = Brain.quickText(
                "A phone agent must do this task on an Android phone: \"$job\". Write a short numbered plan (max 6 steps, each under 10 words) including likely obstacles " +
                    "(login with phone number + OTP, permission popups, ads, 'update app', profile pick, slow loading) and how to get past each. Plain text, no preface.",
                260
            ) ?: ""
        }.start()
        // open the named app right away, no AI call needed for that
        val jl = low(job)
        val first = APPS.firstOrNull { it.all { c -> c.code < 128 } && jl.contains(it) }
        if (first != null) {
            val r = openSmart(hands, first)
            if (r.startsWith("Opened")) { mainApp = first; history.add("open_app $first -> $r"); Thread.sleep(1200) }
        }
        say("ठीक है, शुरू कर रहा हूँ।")
        while (step < 90 && !cancelled) {
            if (System.currentTimeMillis() - started > 15 * 60_000L) {
                say("काम में बहुत देर लग गई, इसलिए रोक दिया।")
                return
            }
            step++
            val obs = observe(hands)
            if (obs == null) {
                Thread.sleep(1000)
                if (step > 4 && history.isEmpty()) {
                    say("स्क्रीन पढ़ नहीं पा रहा। फोन अनलॉक करो।")
                    return
                }
                continue
            }
            val (pkg, els, text) = obs
            if (BLOCKED_PKGS.any { pkg.startsWith(it) }) {
                say("यह पेमेंट या सिस्टम वाली स्क्रीन है, यहाँ मैं हाथ नहीं लगाता। बाकी आप कर लो।")
                return
            }
            val sig = pkg + text.hashCode()
            same = if (sig == lastSig) same + 1 else 0
            lastSig = sig
            // automatic recovery when nothing changes
            if (same >= 4 && same % 4 == 0) {
                history.add("auto: screen stuck, pressed back -> " + hands.press("back"))
                Thread.sleep(800)
            }
            if (same >= 9 && mainApp.isNotBlank()) {
                history.add("auto: stuck for long, reopened $mainApp -> " + openSmart(hands, mainApp))
                same = 0
                Thread.sleep(1500)
            }
            val prompt = buildString {
                append("You are an expert, persistent agent that operates an Android phone for the user. User's task: \"").append(job).append("\"\n")
                append("The user speaks Hindi/English/Hinglish; the 'say' field must be in the same style, short.\n")
                val plan = planText
                if (plan.isNotBlank()) append("Your plan:\n").append(plan).append('\n')
                append("Current app package: ").append(pkg).append("\n")
                append("Screen elements (id: label [tap/input]); '(no label @x,y)' means an icon at that screen position:\n").append(text.ifEmpty { "(nothing readable - maybe loading or a video; try wait, back, or tap_xy)\n" })
                append("Actions done so far:\n")
                if (history.isEmpty()) append("(none)\n") else history.takeLast(8).forEach { append("- ").append(it).append('\n') }
                if (same >= 2) append("WARNING: the screen did not change after your last actions. Do something DIFFERENT (another element, scroll, back, tap_xy on an icon).\n")
                append("""
Choose ONE next action and reply with ONLY JSON:
{"thought":"short reason","action":"tap|tap_xy|type|type_enter|enter|scroll_down|scroll_up|back|home|open_app|wait|ask_user|wait_user|done|fail","id":0,"x":0,"y":0,"text":"","app":"","say":""}
Rules:
- tap: id = element number. tap_xy: x,y screen position (for icons without label, player controls, close X). type: id = input element number (0 = focused box), text = text. type_enter: type the text then press search/enter in ONE step (use for search boxes). enter: press keyboard search/enter. open_app: app = name (do it first if the right app is not open).
- You must FINISH the task. Obstacles are normal and YOU solve them: dismiss popups/ads/rating/update/notification dialogs (Skip, Not now, Later, Close, X, Got it, Maybe later), allow normal app permissions, choose a profile if asked, wait when loading, scroll to find things, use the search box, try another tab/menu/icon, go back and retry a different route. Never give up on the first problem. Use fail only after you truly tried many different ways, and explain exactly what blocks you.
- Login: if the app needs login and the phone/email field is empty, use ask_user to get the phone number or email, type it, then continue. When an OTP is asked, use ask_user to get the OTP from the user (they read it from their SMS) and type it. Passwords, PINs and card details: NEVER type them; use wait_user with a short message so the user enters them, you continue automatically afterwards.
- done only when the goal is really visible on screen (e.g. the match is actually playing). Put a short spoken result in say.
- ask_user: only for facts only the user knows. Do not ask permission for normal steps.
- Do not tap the final order/pay/send/subscribe button unless the task clearly asks for it; the app asks the user separately.
""")
            }
            var reply: String? = null
            for (t in 0..2) {
                reply = Brain.quickJson(prompt, 260)
                if (reply != null) break
                Thread.sleep(500)
            }
            if (reply == null) {
                say("इंटरनेट या Gemini से जवाब नहीं मिल रहा। रुक रहा हूँ।")
                return
            }
            val j = try { JSONObject(reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) } catch (e: Exception) {
                history.add("(bad reply, retrying)")
                continue
            }
            val action = low(j.optString("action"))
            val id = j.optInt("id", 0)
            val txt = j.optString("text", "")
            val sayText = j.optString("say", "")
            val el = els.firstOrNull { it.id == id }
            if (cancelled) break
            when (action) {
                "done" -> {
                    if (doneRejects < 4) {
                        val (ok, next) = verifyDone(hands, job, history)
                        if (!ok) {
                            doneRejects++
                            history.add("NOT finished yet (check says): $next")
                            settle(hands, sig)
                            continue
                        }
                    }
                    say(sayText.ifBlank { "काम हो गया।" })
                    return
                }
                "fail" -> {
                    failPush++
                    if (failPush <= 3) {
                        history.add("You said fail (\"${sayText.take(60)}\") but giving up is not allowed yet. Try another route: scroll, search, other tab/menu, back, reopen the app, tap_xy.")
                    } else {
                        val r = askUser((sayText.ifBlank { "यहाँ अटक गया हूँ।" }) + " क्या करूँ?")
                        if (r == null) {
                            if (!cancelled) say(sayText.ifBlank { "मैं यह नहीं कर पाया।" })
                            return
                        }
                        history.add("stuck; user guidance: $r")
                        failPush = 0
                    }
                }
                "ask_user" -> {
                    val t0 = System.currentTimeMillis()
                    val r = askUser(sayText.ifBlank { "मुझे थोड़ी जानकारी चाहिए।" })
                    started += System.currentTimeMillis() - t0
                    if (r == null) {
                        if (!cancelled) say("जवाब नहीं मिला, काम रोक दिया।")
                        return
                    }
                    history.add("asked user, user said: $r")
                }
                "wait_user" -> {
                    val t0 = System.currentTimeMillis()
                    val ok = waitForUser(hands, sayText.ifBlank { "यह आप खुद डाल दो, मैं यहीं इंतज़ार करता हूँ।" }, false)
                    started += System.currentTimeMillis() - t0
                    if (!ok) {
                        if (!cancelled) say("काफ़ी इंतज़ार किया, काम रोक रहा हूँ।")
                        return
                    }
                    history.add("user handled the screen, continuing")
                }
                "tap" -> {
                    if (el == null) {
                        history.add("tap failed: no element $id")
                    } else {
                        if (el.label == "(password field)") {
                            val t0 = System.currentTimeMillis()
                            val ok = waitForUser(hands, "पासवर्ड आप खुद डाल दो, मैं यहीं इंतज़ार करता हूँ।", true)
                            started += System.currentTimeMillis() - t0
                            if (!ok) { if (!cancelled) say("इंतज़ार बहुत हो गया, रुक रहा हूँ।"); return }
                            history.add("user entered password, continuing")
                            settle(hands, sig)
                            continue
                        }
                        if (risky(el.label)) {
                            val ok = confirm("\"" + el.label.take(40) + "\" दबाने जा रहा हूँ। पक्का? हाँ या नहीं बोलो।")
                            if (!ok) {
                                if (!cancelled) say("ठीक है, नहीं दबाया। काम रोक दिया।")
                                return
                            }
                        }
                        val r = tapEl(hands, el)
                        history.add("tapped $id \"${el.label.take(40)}\" -> $r")
                    }
                }
                "tap_xy" -> {
                    val x = j.optInt("x", -1)
                    val y = j.optInt("y", -1)
                    if (x < 0 || y < 0) history.add("tap_xy needs x and y")
                    else history.add("tapped position $x,$y -> " + hands.tap(x, y))
                }
                "type" -> {
                    if (el != null && el.label == "(password field)") {
                        val t0 = System.currentTimeMillis()
                        val ok = waitForUser(hands, "पासवर्ड आप खुद डाल दो, मैं यहीं इंतज़ार करता हूँ।", true)
                        started += System.currentTimeMillis() - t0
                        if (!ok) { if (!cancelled) say("इंतज़ार बहुत हो गया, रुक रहा हूँ।"); return }
                        history.add("user entered password, continuing")
                        settle(hands, sig)
                        continue
                    }
                    if (el != null) {
                        tapEl(hands, el)
                        Thread.sleep(500)
                    }
                    val r = hands.typeText(txt)
                    history.add("typed \"$txt\" -> $r")
                }
                "type_enter" -> {
                    if (el != null) { tapEl(hands, el); Thread.sleep(350) }
                    val r1 = hands.typeText(txt)
                    Thread.sleep(250)
                    history.add("typed \"$txt\" -> $r1; enter -> " + pressEnter(hands))
                }
                "enter" -> history.add("enter -> " + pressEnter(hands))
                "scroll_down" -> history.add("scroll down -> " + hands.scroll(true))
                "scroll_up" -> history.add("scroll up -> " + hands.scroll(false))
                "back" -> history.add("back -> " + hands.press("back"))
                "home" -> history.add("home -> " + hands.press("home"))
                "open_app" -> {
                    val nm = j.optString("app", txt)
                    val r = openSmart(hands, nm)
                    history.add("open_app $nm -> $r")
                    if (r.startsWith("Opened")) { if (mainApp.isBlank()) mainApp = nm; notInstalled = 0 }
                    else {
                        notInstalled++
                        if (notInstalled >= 2) {
                            say("$nm फोन में इंस्टॉल नहीं मिल रहा। Play Store से इंस्टॉल करके फिर बोलो।")
                            return
                        }
                    }
                    Thread.sleep(900)
                }
                "wait" -> Thread.sleep(2500)
                else -> history.add("unknown action '$action'")
            }
            settle(hands, sig)
        }
        if (cancelled) say("ठीक है, रुक गया।") else say("काफ़ी कोशिश की पर पूरा नहीं हुआ। बाकी आप देख लो।")
    }

    /** Waits only as long as the screen needs to change (up to ~2.2 s) instead of a fixed pause. */
    private fun settle(hands: Hands, before: String) {
        val end = System.currentTimeMillis() + 2200
        Thread.sleep(120)
        while (System.currentTimeMillis() < end && !cancelled) {
            val o = observe(hands)
            if (o != null && (o.first + o.third.hashCode()) != before) {
                Thread.sleep(140) // let the animation finish
                return
            }
            Thread.sleep(80)
        }
    }

    private fun tapEl(hands: Hands, el: El): String {
        var n: AccessibilityNodeInfo? = el.node
        var hops = 0
        while (n != null && !n.isClickable && hops < 4) {
            n = n.parent
            hops++
        }
        if (n != null && n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ok"
        return hands.tap(el.r.centerX(), el.r.centerY())
    }

    private fun pressEnter(hands: Hands): String {
        val svc = hands.service ?: return "no service"
        val root = svc.rootInActiveWindow ?: return "no screen"
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && Build.VERSION.SDK_INT >= 30) {
            val ok = f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (ok) return "ok"
        }
        return "Enter key not available on this phone; tap the search suggestion or search button instead."
    }
}
