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

    private val BLOCKED_PKGS = listOf(
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "com.sbi.upi", "com.csam.icici.bank.imobile", "com.snapwork.hdfc", "com.axis.mobile", "com.dreamplug.androidapp",
        "com.freecharge.android", "com.amazon.mShop.android.shopping.pay", "com.android.settings", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.vending"
    )

    private val RISKY = listOf(
        "pay", "place order", "order now", "buy", "purchase", "checkout", "confirm", "send", "submit", "delete", "remove",
        "transfer", "book", "subscribe", "proceed", "continue to pay", "upi", "bid", "post", "tweet", "uninstall", "factory",
        "भुगतान", "पेमेंट", "ऑर्डर", "खरीद", "भेज", "हटा", "डिलीट", "बुक", "कन्फर्म", "पक्का", "सबमिट"
    )

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun low(x: String) = x.lowercase(Locale.ROOT)

    // ------------------------------------------------------------------ voice entry

    private val APPS = listOf(
        "zomato", "swiggy", "blinkit", "zepto", "instamart", "amazon", "flipkart", "myntra", "meesho", "uber", "ola", "rapido",
        "irctc", "makemytrip", "redbus", "bookmyshow", "youtube", "instagram", "spotify", "maps", "netflix", "hotstar", "gmail",
        "जोमैटो", "ज़ोमैटो", "स्विगी", "ब्लिंकिट", "ज़ेप्टो", "अमेज़न", "अमेजन", "फ्लिपकार्ट", "मिंत्रा", "उबर", "ओला", "रैपिडो"
    )
    private val ACTS = listOf(
        "order", "book", "buy", "search", "download", "install", "ऑर्डर", "बुक", "खरीद", "मंगा", "मँगा", "मंगवा", "ढूंढ", "खोज",
        "mangwa", "manga", "kharid", "dhundh", "khoj", "add to cart", "cart"
    )
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
        if (!explicit && !smart) return null
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
        if (Prefs.geminiKey.isBlank()) return "एजेंट के लिए Gemini key चाहिए। सेटिंग में डालो।"
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
            if (list.size >= 70 || depth > 30) return
            val label = if (n.isPassword) "(password field)" else listOfNotNull(n.text?.toString()?.take(70), n.contentDescription?.toString()?.take(70))
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
            sb.append(if (e.label.isEmpty()) "(no label)" else "\"" + e.label + "\"")
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

    private fun loop(ctx: Context, hands: Hands, job: String) {
        val history = ArrayList<String>()
        val started = System.currentTimeMillis()
        var lastSig = ""
        var same = 0
        var step = 0
        say("ठीक है, शुरू कर रहा हूँ।")
        while (step < 25 && !cancelled) {
            if (System.currentTimeMillis() - started > 5 * 60_000L) {
                say("काम में बहुत देर लग गई, इसलिए रोक दिया।")
                return
            }
            step++
            val obs = observe(hands)
            if (obs == null) {
                Thread.sleep(1000)
                if (step > 3 && history.isEmpty()) {
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
            val prompt = buildString {
                append("You are an agent that operates an Android phone for the user. User's task: \"").append(job).append("\"\n")
                append("The user speaks Hindi/English/Hinglish; reply fields in the same style.\n")
                append("Current app package: ").append(pkg).append("\n")
                append("Screen elements (id: label [tap/input]):\n").append(text.ifEmpty { "(nothing readable)\n" })
                append("Actions done so far:\n")
                if (history.isEmpty()) append("(none)\n") else history.takeLast(10).forEach { append("- ").append(it).append('\n') }
                if (same >= 2) append("WARNING: the screen did not change after your last actions. Try something different (scroll, back, another element).\n")
                append("""
Choose ONE next action and reply with ONLY JSON:
{"thought":"short reason","action":"tap|type|enter|scroll_down|scroll_up|back|home|open_app|wait|ask_user|done|fail","id":0,"text":"","app":"","say":""}
Rules:
- tap: id = element number. type: id = input element number (or 0 for the focused box), text = text to type. enter: press the keyboard search/enter key. open_app: app = app name (do this first if the right app is not open).
- If the task is finished, use done and put a short spoken result in say. If impossible or stuck, use fail with the reason in say.
- If you need information only the user knows (address choice, size, which item), use ask_user with the question in say.
- Never type passwords, OTPs or PINs. Never enter payment details. Do not skip the confirmation: tap the final order/pay/send button only when the task clearly asks for it; the app asks the user separately.
- Prefer search boxes, then results; scroll if the item is not visible. One action at a time.
""")
            }
            val reply = Brain.quickJson(prompt, 400)
            if (reply == null) {
                say("इंटरनेट या Gemini से जवाब नहीं मिला। रुक रहा हूँ।")
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
                    say(sayText.ifBlank { "काम हो गया।" })
                    return
                }
                "fail" -> {
                    say(sayText.ifBlank { "मैं यह नहीं कर पाया।" })
                    return
                }
                "ask_user" -> {
                    val r = askUser(sayText.ifBlank { "मुझे थोड़ी जानकारी चाहिए।" })
                    if (r == null) {
                        if (!cancelled) say("जवाब नहीं मिला, काम रोक दिया।")
                        return
                    }
                    history.add("asked user, user said: $r")
                }
                "tap" -> {
                    if (el == null) {
                        history.add("tap failed: no element $id")
                    } else {
                        if (el.label == "(password field)") {
                            say("यहाँ पासवर्ड माँगा जा रहा है, वह आप खुद डालो।")
                            return
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
                "type" -> {
                    if (el != null && el.label == "(password field)") {
                        say("यहाँ पासवर्ड है, वह आप खुद डालो।")
                        return
                    }
                    if (el != null) {
                        tapEl(hands, el)
                        Thread.sleep(500)
                    }
                    val r = hands.typeText(txt)
                    history.add("typed \"$txt\" -> $r")
                }
                "enter" -> history.add("enter -> " + pressEnter(hands))
                "scroll_down" -> history.add("scroll down -> " + hands.scroll(true))
                "scroll_up" -> history.add("scroll up -> " + hands.scroll(false))
                "back" -> history.add("back -> " + hands.press("back"))
                "home" -> history.add("home -> " + hands.press("home"))
                "open_app" -> {
                    val r = hands.openApp(j.optString("app", txt))
                    history.add("open_app ${j.optString("app", txt)} -> $r")
                    Thread.sleep(2500)
                }
                "wait" -> Thread.sleep(2000)
                else -> history.add("unknown action '$action'")
            }
            Thread.sleep(1300)
        }
        if (cancelled) say("ठीक है, रुक गया।") else say("काफ़ी कोशिश की पर पूरा नहीं हुआ। बाकी आप देख लो।")
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
