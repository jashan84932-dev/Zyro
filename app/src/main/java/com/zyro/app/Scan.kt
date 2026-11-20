package com.zyro.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Photo scanning: reads text and things in one or many photos, remembers the last scan for follow-up
 * questions, and speaks the answer (through Live when Live is on).
 */
object Scan {
    val images = ArrayList<String>()
    @Volatile var lastAnswer = ""
    @Volatile var lastAt = 0L
    @Volatile var addMode = false
    private var doc = false
    private var cam: String? = null

    private const val SCAN_PROMPT = "यह फोटो ध्यान से देखो। अगर इसमें कुछ लिखा है (बिल, किताब, बोर्ड, लेबल, स्क्रीन), तो उसकी मुख्य बातें पढ़कर बताओ। " +
        "अगर कोई चीज़, जगह, पौधा, खाना या इंसान है, तो बताओ कि वह क्या है और काम की बात क्या है। " +
        "अगर यह कोई फॉर्म है जिसमें खाली खाने हैं, तो बताओ कि कौन-कौन से खाने भरने हैं। " +
        "हिंदी (थोड़ी English मिलाकर) में 3 से 4 छोटे वाक्यों में बताओ, जैसे दोस्त को बता रहे हो। कोई सूची, तारे या चिह्न मत लिखो। " +
        "सिर्फ़ अगर फोटो किसी दुकान का बिल या रसीद है, तो सबसे आख़िर में एक अलग लाइन जोड़ो, ठीक इस रूप में: BILL|दुकान का नाम|कुल रकम सिर्फ़ अंक में। बाकी सब चीज़ों के लिए यह लाइन मत लिखो।"
    private const val MULTI_PROMPT = "ये कई फोटो एक साथ हैं। सबको मिलाकर देखो और बताओ कि कुल मिलाकर इनमें क्या है, और हर फोटो की ज़रूरी बात क्या है। " +
        "अगर इनमें लिखा हुआ है (बिल, पन्ने, लेबल), तो उसकी मुख्य बातें जोड़ो। हिंदी (थोड़ी English मिलाकर) में 4 से 5 छोटे वाक्यों में बताओ। कोई सूची, तारे या चिह्न मत लिखो।"

    /** Quick check used by the camera and by voice follow-ups: a scan from the last 20 minutes. */
    fun fresh(): Boolean = images.isNotEmpty() && System.currentTimeMillis() - lastAt < 20 * 60_000L

    fun begin(b64: String) {
        synchronized(images) {
            if (!addMode || !fresh()) {
                images.clear()
                doc = Pro.docMode
                Pro.docMode = false
                cam = Plus.camPrompt
                Plus.camPrompt = null
            }
            addMode = false
            images.add(b64)
            while (images.size > 5) images.removeAt(0)
            lastAt = System.currentTimeMillis()
        }
    }

    fun hasVisionKey(): Boolean = Prefs.geminiKeysRaw.isNotBlank() || Prefs.viaProxy

    fun analyze(): String {
        if (!hasVisionKey()) return "फोटो देखने के लिए Gemini key चाहिए। Groq फोटो नहीं देखता।"
        val list = synchronized(images) { ArrayList(images) }
        val prompt = when {
            doc -> Pro.DOC_PROMPT
            cam != null -> cam!!
            list.size > 1 -> MULTI_PROMPT
            else -> SCAN_PROMPT
        }
        val raw = try { Brain.describeImages(list, prompt) } catch (e: Exception) { "फोटो नहीं देख पाया।" }
        var reply = if (doc) Pro.docReply(raw) else raw.replace(Regex("[*#]+"), "").trim()
        // a bill: the last line carries the shop and the total; offer to note it as an expense
        val m = Regex("BILL\\|([^|\\n]*)\\|\\s*([0-9][0-9,]*)").find(reply)
        if (m != null) {
            val amt = m.groupValues[2].replace(",", "").toIntOrNull() ?: 0
            reply = reply.replace(Regex("BILL\\|[^\\n]*"), "").trim()
            if (amt > 0) {
                pendingBill = Pair(m.groupValues[1].trim().ifBlank { "बिल" }, amt)
                pendingBillAt = System.currentTimeMillis()
                reply = reply + " कुल $amt रुपये का बिल है। इसे खर्चे में लिख लूँ?"
            }
        }
        doc = false
        cam = null
        lastAnswer = reply
        lastAt = System.currentTimeMillis()
        return reply
    }

    @Volatile private var pendingBill: Pair<String, Int>? = null
    @Volatile private var pendingBillAt = 0L

    /** "haan" after "kharche mein likh loon?" notes the bill as an expense. */
    fun handlePending(t: String, words: List<String>): String? {
        val b = pendingBill ?: return null
        if (System.currentTimeMillis() - pendingBillAt > 3 * 60_000L) { pendingBill = null; return null }
        val ws = words.map { Local.strip(it) }
        val yes = listOf("haan", "han", "ha", "yes", "ji", "ok", "okay", "kar do", "likh", "हाँ", "हां", "हा", "जी", "ठीक", "लिख", "कर दो").any { ws.contains(Local.strip(it)) }
        val no = listOf("nahi", "nahin", "no", "mat", "नहीं", "मत").any { ws.contains(Local.strip(it)) }
        if (no) { pendingBill = null; return "ठीक है, नहीं लिखा।" }
        if (!yes) return null
        pendingBill = null
        return try {
            val a = try { org.json.JSONArray(Prefs.expenses) } catch (e: Exception) { org.json.JSONArray() }
            a.put(org.json.JSONObject().put("a", b.second).put("c", b.first).put("ts", System.currentTimeMillis()))
            Prefs.expenses = a.toString()
            "${b.first} के ${b.second} रुपये खर्चे में लिख लिए।"
        } catch (e: Exception) { "लिख नहीं पाया।" }
    }

    fun follow(question: String): String {
        if (!hasVisionKey()) return "फोटो देखने के लिए Gemini key चाहिए।"
        val list = synchronized(images) { ArrayList(images) }
        if (list.isEmpty()) return "अभी मेरे पास कोई फोटो नहीं है।"
        val p = "पहले इन फोटो के बारे में यह बताया गया था: \"$lastAnswer\"\nअब सवाल: $question\n" +
            "फोटो फिर से देखकर हिंदी (थोड़ी English मिलाकर) में 2 से 3 छोटे वाक्यों में सीधा जवाब दो। कोई सूची, तारे या चिह्न मत लिखो।"
        val r = try { Brain.describeImages(list, p).replace(Regex("[*#]+"), "").trim() } catch (e: Exception) { "जवाब नहीं मिल पाया।" }
        lastAnswer = r
        lastAt = System.currentTimeMillis()
        return r
    }

    /** Says the answer: through Live when it is on, otherwise through the normal voice. */
    fun deliver(ctx: Context, text: String) {
        val a = Assistant.get(ctx)
        a.note("${Prefs.friendName}: $text")
        a.announce(text)
    }
}

class ScanActivity : Activity() {
    private lateinit var view: ScanView
    private lateinit var card: LinearLayout
    private lateinit var answer: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var typing: Runnable? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Scan.images.isEmpty()) { finish(); return }
        val root = FrameLayout(this)
        view = ScanView(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f).toInt(), dp(16f).toInt(), dp(18f).toInt(), dp(14f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(22f)
                setColor(Color.argb(235, 8, 18, 38))
                setStroke(dp(1.2f).toInt(), Color.argb(150, 47, 140, 255))
            }
            alpha = 0f
        }
        answer = TextView(this).apply {
            setTextColor(Color.rgb(226, 236, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(dp(3f), 1f)
        }
        val sv = ScrollView(this)
        sv.addView(answer)
        card.addView(sv, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        row.addView(roundBtn("＋") { addPhoto() })
        row.addView(roundBtn("🎙") { askMore() })
        row.addView(roundBtn("✕") { finish() })
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f).toInt() })

        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            val m = dp(14f).toInt(); setMargins(m, m, m, m + dp(6f).toInt())
        }
        root.addView(card, lp)
        setContentView(root)

        showImage()
        runAnalysis { Scan.analyze() }
    }

    private fun roundBtn(label: String, onTap: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(200, 20, 60, 130))
            setStroke(dp(1.2f).toInt(), Color.argb(190, 90, 170, 255))
        }
        val s = dp(52f).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(dp(10f).toInt(), 0, dp(10f).toInt(), 0) }
        setOnClickListener { onTap() }
    }

    private fun showImage() {
        val last = synchronized(Scan.images) { Scan.images.lastOrNull() } ?: return
        val bytes = Base64.decode(last, Base64.DEFAULT)
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        view.setImage(b, Scan.images.size)
    }

    private fun runAnalysis(job: () -> String) {
        typing?.let { ui.removeCallbacks(it) }
        card.animate().alpha(0f).translationY(dp(40f)).setDuration(180).start()
        view.rescan()
        val app = applicationContext
        Thread {
            val reply = try { job() } catch (e: Exception) { "फोटो नहीं देख पाया।" }
            ui.post {
                if (isFinishing) return@post
                view.finishScan()
                reveal(reply)
            }
            Scan.deliver(app, reply)
        }.start()
    }

    private fun reveal(text: String) {
        answer.text = ""
        card.translationY = dp(40f)
        card.animate().alpha(1f).translationY(0f).setDuration(320).start()
        var i = 0
        val r = object : Runnable {
            override fun run() {
                if (isFinishing) return
                i = minOf(text.length, i + 2)
                answer.text = text.substring(0, i)
                if (i < text.length) ui.postDelayed(this, 16)
            }
        }
        typing = r
        ui.postDelayed(r, 260)
    }

    private fun addPhoto() {
        Scan.addMode = true
        startActivity(Intent(this, CameraActivity::class.java))
        finish()
    }

    private fun askMore() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(i, 21)
        } catch (e: Exception) {
            reveal("बोलकर पूछने की सुविधा इस फोन में नहीं मिली।")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21 || resultCode != RESULT_OK) return
        val q = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        runAnalysis { Scan.follow(q) }
    }

    override fun onDestroy() {
        typing?.let { ui.removeCallbacks(it) }
        super.onDestroy()
    }
}

/** The look of the scan: a blue light sweeps over the photo, corner brackets pulse, data points twinkle. */
class ScanView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private var count = 1
    private var done = false
    private var doneAt = 0L
    private val t0 = SystemClock.uptimeMillis()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val dots: Array<FloatArray>
    private val dd = resources.displayMetrics.density

    init {
        val rnd = java.util.Random(11)
        dots = Array(30) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()) }
    }

    fun setImage(b: Bitmap?, n: Int) { bmp = b; count = n; done = false; invalidate() }
    fun rescan() { done = false }
    fun finishScan() {
        done = true; doneAt = SystemClock.uptimeMillis()
        try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (e: Exception) { }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(3, 9, 20))
        val b = bmp ?: return
        val now = SystemClock.uptimeMillis()
        val t = now - t0
        val margin = 22 * dd
        val sc = minOf((width - 2 * margin) / b.width, (height * 0.58f) / b.height)
        val w = b.width * sc
        val h = b.height * sc
        val l = (width - w) / 2f
        val tp = margin * 2.4f
        dst.set(l, tp, l + w, tp + h)

        p.reset(); p.isFilterBitmap = true; p.isAntiAlias = true
        if (!done) p.colorFilter = LightingColorFilter(Color.rgb(165, 200, 255), 0)
        c.drawBitmap(b, null, dst, p)
        p.reset(); p.isAntiAlias = true

        c.save()
        c.clipRect(dst)
        // faint grid
        p.color = Color.argb(if (done) 14 else 34, 120, 190, 255)
        p.strokeWidth = 1f
        val step = 40 * dd
        var gx = dst.left
        while (gx < dst.right) { c.drawLine(gx, dst.top, gx, dst.bottom, p); gx += step }
        var gy = dst.top
        while (gy < dst.bottom) { c.drawLine(dst.left, gy, dst.right, gy, p); gy += step }

        val ph = ((t % 2400L) / 2400f)
        val tri = if (ph < 0.5f) ph * 2f else 2f - ph * 2f
        val ease = tri * tri * (3f - 2f * tri)
        val ly = dst.top + ease * dst.height()

        if (!done) {
            // twinkling data points in the part that was already scanned
            for (d in dots) {
                val dy = dst.top + d[1] * dst.height()
                if (dy < ly) {
                    val a = (0.5f + 0.5f * Math.sin(t / 260.0 + d[2] * 6.28)).toFloat()
                    p.color = Color.argb((60 + 170 * a).toInt(), 140, 215, 255)
                    c.drawCircle(dst.left + d[0] * dst.width(), dy, (1.6f + 1.6f * a) * dd, p)
                }
            }
            // glow behind the line, then the line itself
            p.shader = LinearGradient(0f, ly - 90 * dd, 0f, ly, Color.argb(0, 47, 140, 255), Color.argb(120, 47, 140, 255), Shader.TileMode.CLAMP)
            c.drawRect(dst.left, ly - 90 * dd, dst.right, ly, p)
            p.shader = LinearGradient(dst.left, 0f, dst.right, 0f,
                intArrayOf(Color.argb(0, 150, 215, 255), Color.argb(255, 190, 235, 255), Color.argb(0, 150, 215, 255)), null, Shader.TileMode.CLAMP)
            p.strokeWidth = 3f * dd
            c.drawLine(dst.left, ly, dst.right, ly, p)
            p.shader = null
        } else {
            val a = 1f - ((now - doneAt) / 800f).coerceIn(0f, 1f)
            if (a > 0f) {
                p.color = Color.argb((90 * a).toInt(), 190, 230, 255)
                c.drawRect(dst, p)
            }
        }
        // reticle that hops between points while the scan runs: looks like it is picking out details
        if (!done) {
            val hop = (t / 900L).toInt()
            val hr = java.util.Random(hop * 7919L + 3)
            val rx = dst.left + (0.15f + 0.7f * hr.nextFloat()) * dst.width()
            val ry = dst.top + (0.15f + 0.7f * hr.nextFloat()) * dst.height()
            val hp = ((t % 900L) / 900f)
            val rs = (34f - 16f * minOf(1f, hp * 3f)) * dd
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.8f * dd
            p.color = Color.argb((200 * (1f - hp * hp)).toInt(), 255, 170, 80)
            c.drawRect(rx - rs, ry - rs, rx + rs, ry + rs, p)
            c.drawLine(rx - rs * 1.5f, ry, rx - rs * 0.6f, ry, p)
            c.drawLine(rx + rs * 0.6f, ry, rx + rs * 1.5f, ry, p)
            c.drawLine(rx, ry - rs * 1.5f, rx, ry - rs * 0.6f, p)
            c.drawLine(rx, ry + rs * 0.6f, rx, ry + rs * 1.5f, p)
            p.style = Paint.Style.FILL
        }
        c.restore()

        // completion: two rings leave the middle of the picture
        if (done) {
            val age = (now - doneAt) / 1000f
            for (k in 0..1) {
                val f = ((age - k * 0.18f) / 0.9f)
                if (f in 0f..1f) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = (4f * (1f - f) + 1f) * dd
                    p.color = Color.argb(((1f - f) * (1f - f) * 220).toInt(), 150, 230, 255)
                    c.drawCircle(dst.centerX(), dst.centerY(), f * maxOf(dst.width(), dst.height()) * 0.7f, p)
                    p.style = Paint.Style.FILL
                }
            }
        }

        // corner brackets
        val pulse = 0.5f + 0.5f * Math.sin(t / 380.0).toFloat()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3.2f * dd
        p.strokeCap = Paint.Cap.ROUND
        p.color = if (done) Color.argb(255, 150, 230, 255) else Color.argb((170 + 85 * pulse).toInt(), 70, 160, 255)
        val len = 30 * dd
        val snap = if (done) 1f - ((now - doneAt) / 420f).coerceIn(0f, 1f) else 0f
        val o = (6 + 16 * snap * snap) * dd
        val x1 = dst.left - o; val x2 = dst.right + o; val y1 = dst.top - o; val y2 = dst.bottom + o
        c.drawLine(x1, y1, x1 + len, y1, p); c.drawLine(x1, y1, x1, y1 + len, p)
        c.drawLine(x2, y1, x2 - len, y1, p); c.drawLine(x2, y1, x2, y1 + len, p)
        c.drawLine(x1, y2, x1 + len, y2, p); c.drawLine(x1, y2, x1, y2 - len, p)
        c.drawLine(x2, y2, x2 - len, y2, p); c.drawLine(x2, y2, x2, y2 - len, p)

        // one small dot per photo taken so far
        p.style = Paint.Style.FILL
        for (i in 0 until count) {
            p.color = if (i == count - 1) Color.rgb(150, 215, 255) else Color.argb(120, 90, 160, 255)
            c.drawCircle(width / 2f + (i - (count - 1) / 2f) * 16 * dd, dst.bottom + 22 * dd, 3.4f * dd, p)
        }
        postInvalidateOnAnimation()
    }
}
