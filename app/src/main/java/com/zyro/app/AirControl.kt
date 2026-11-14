package com.zyro.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Air control. The front camera watches your hand.
 *   open hand            : the cursor follows your palm, smooth and instant
 *   close the hand       : the cursor freezes where it was
 *   open it again        : tap there  (close, hold about 1.5 s, open = long press)
 *   close, move, open    : drag / swipe from there to here
 *   only the index finger: scroll - move the finger up or down
 *   thumb touches the index fingertip, like touching a screen:
 *     quick touch = tap, touch + slide the hand = swipe / scroll (reels), touch and hold = long press
 *   two fingers (V)      : hold briefly = Back, hold longer = Home
 *   the cursor is pulled gently toward the button under it, and that button is outlined
 * Needs: camera, "display over other apps", Zyro's accessibility service, and the hand model file
 * (the GitHub build downloads it into assets).
 */
object AirControl {
    private const val MODEL = "hand_landmarker.task"

    @Volatile var running = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var landmarker: HandLandmarker? = null
    private var sensorOrientation = 270
    private val actions = Executors.newSingleThreadExecutor()
    private val actionBusy = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)

    private var wm: WindowManager? = null
    private var overlay: CursorOverlay? = null
    private var appCtx: Context? = null
    private var handsRef: Hands? = null

    private var screenW = 1080
    private var screenH = 1920
    private var lastHandAt = 0L

    // ---------------------------------------------------------------- smoothing (One Euro filter)

    private class OneEuro(private val minCut: Double, private val beta: Double, private val dCut: Double = 1.0) {
        private var xPrev = 0.0
        private var dxPrev = 0.0
        private var tPrev = 0L
        private var init = false
        private fun alpha(cut: Double, dt: Double): Double {
            val tau = 1.0 / (2 * PI * cut)
            return 1.0 / (1.0 + tau / dt)
        }
        fun reset() { init = false }
        fun filter(x: Double, tMs: Long): Double {
            if (!init) {
                init = true; xPrev = x; dxPrev = 0.0; tPrev = tMs
                return x
            }
            val dt = ((tMs - tPrev) / 1000.0).coerceIn(0.005, 0.2)
            tPrev = tMs
            val dx = (x - xPrev) / dt
            val ad = alpha(dCut, dt)
            dxPrev += ad * (dx - dxPrev)
            val cut = minCut + beta * abs(dxPrev)
            val a = alpha(cut, dt)
            xPrev += a * (x - xPrev)
            return xPrev
        }
    }

    private val fx = OneEuro(1.0, 16.0)
    private val fy = OneEuro(1.0, 16.0)
    private val fScroll = OneEuro(2.0, 10.0)

    // ---------------------------------------------------------------- state

    private const val OPEN = 1
    private const val FIST = 2
    private const val POINT = 3
    private const val VEE = 4
    private const val PINCH = 5

    private var stable = 0           // debounced pose
    private var candidate = 0
    private var candidateCount = 0

    private var curX = -1f           // where the cursor should be (screen px)
    private var curY = -1f
    private val trail = ArrayDeque<FloatArray>() // t, x, y of the open-hand cursor, for the click anchor

    private var anchorX = 0f
    private var anchorY = 0f
    private var fistSince = 0L
    private var fistPalmX = 0f
    private var fistPalmY = 0f
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f

    private var scrollRef = -1.0
    private var lastTapAt = 0L
    private var vSince = 0L
    private var dragStartAt = 0L

    // buttons on screen, refreshed in the background, used to pull the cursor toward the right button
    @Volatile private var targets: List<Rect> = emptyList()
    private val scanner = Executors.newSingleThreadExecutor()
    private val scanBusy = AtomicBoolean(false)
    private var lastScan = 0L
    private var prevCurX = -1f
    private var prevCurY = -1f

    private fun scanTargets(now: Long) {
        if (now - lastScan < 450 || !scanBusy.compareAndSet(false, true)) return
        lastScan = now
        scanner.execute {
            try {
                val root = handsRef?.service?.rootInActiveWindow
                val out = ArrayList<Rect>()
                if (root != null) {
                    val limit = screenW.toLong() * screenH * 0.25
                    fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                        if (n == null || depth > 30 || out.size >= 140) return
                        if (n.isClickable && n.isVisibleToUser) {
                            val r = Rect()
                            n.getBoundsInScreen(r)
                            if (r.width() > 0 && r.height() > 0 && r.width().toLong() * r.height() < limit) out.add(r)
                        }
                        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
                    }
                    walk(root, 0)
                }
                targets = out
            } catch (e: Exception) {
            } finally {
                scanBusy.set(false)
            }
        }
    }

    /** The button the cursor is on (smallest one containing it) or the nearest one within about 70 px. */
    private fun nearestTarget(x: Float, y: Float): Rect? {
        var best: Rect? = null
        var bestScore = Float.MAX_VALUE
        for (r in targets) {
            val inside = r.contains(x.toInt(), y.toInt())
            val dd = d(x, y, r.centerX().toFloat(), r.centerY().toFloat())
            if (!inside && dd > 70f) continue
            val score = if (inside) r.width().toFloat() * r.height() else 1e9f + dd
            if (score < bestScore) { bestScore = score; best = r }
        }
        return best
    }

    // ---------------------------------------------------------------- the cursor view (draws at display speed)

    private class CursorOverlay(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = ctx.resources.displayMetrics.density
        var tx = 0f
        var ty = 0f
        var mode = 0                 // 0 lost, 1 open, 2 fist, 3 scroll
        var dragLine = false
        var fromX = 0f
        var fromY = 0f
        var rippleAt = 0L
        var rippleX = 0f
        var rippleY = 0f
        var scrollDir = 0
        var hl: RectF? = null
        var vProg = 0f
        private val rf = RectF()
        private var x = -1f
        private var y = -1f
        private var last = 0L
        private var t = 0f
        private var grow = 1f

        override fun onDraw(c: Canvas) {
            val now = System.nanoTime()
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
            last = now
            t += dt
            if (x < 0f) { x = tx; y = ty }
            // glide toward the target: quick, but never jumpy
            val k = 1f - exp(-dt * 26f)
            x += (tx - x) * k
            y += (ty - y) * k
            val wantGrow = if (mode == 2) 0.62f else 1f
            grow += (wantGrow - grow) * (1f - exp(-dt * 20f))

            val alpha = if (mode == 0) 90 else 255
            val r = 30f * d * grow
            val blue = Color.rgb(47, 160, 255)
            val ice = Color.rgb(200, 235, 255)

            if (dragLine) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f * d
                p.color = Color.argb(160, 47, 160, 255)
                c.drawLine(fromX, fromY, x, y, p)
            }
            // outline of the button that a tap would hit
            val h = hl
            if (h != null && mode == 1) {
                p.style = Paint.Style.FILL
                p.color = Color.argb(34, 47, 160, 255)
                c.drawRoundRect(h, 10f * d, 10f * d, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.4f * d
                p.color = Color.argb(210, 150, 215, 255)
                c.drawRoundRect(h, 10f * d, 10f * d, p)
            }
            // soft glow
            p.style = Paint.Style.FILL
            p.color = Color.argb(alpha / 5, 47, 160, 255)
            c.drawCircle(x, y, r * 1.9f, p)
            p.color = Color.argb(alpha / 3, 47, 160, 255)
            c.drawCircle(x, y, r * 1.25f, p)
            // ring
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f * d
            p.color = Color.argb(alpha, Color.red(blue), Color.green(blue), Color.blue(blue))
            c.drawCircle(x, y, r, p)
            when (mode) {
                2 -> {
                    p.style = Paint.Style.FILL
                    p.color = Color.argb(alpha, Color.red(ice), Color.green(ice), Color.blue(ice))
                    c.drawCircle(x, y, r * 0.55f, p)
                }
                3 -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 3.4f * d
                    p.strokeCap = Paint.Cap.ROUND
                    p.color = ice
                    val a = 11f * d
                    for (s in intArrayOf(-1, 1)) {
                        val off = s * 14f * d
                        val lit = if (scrollDir == s) 1f else 0.45f
                        p.alpha = (255 * lit).toInt()
                        c.drawLine(x - a, y + off + s * a * 0.1f - s * 0f, x, y + off - s * a * 0.9f, p)
                        c.drawLine(x, y + off - s * a * 0.9f, x + a, y + off + s * a * 0.1f, p)
                    }
                }
                4 -> {
                    // back / home: a "<" and a ring that fills (full ring = Home)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 3.6f * d
                    p.strokeCap = Paint.Cap.ROUND
                    p.color = ice
                    val a = 9f * d
                    c.drawLine(x + a * 0.5f, y - a, x - a * 0.5f, y, p)
                    c.drawLine(x - a * 0.5f, y, x + a * 0.5f, y + a, p)
                    rf.set(x - r * 1.3f, y - r * 1.3f, x + r * 1.3f, y + r * 1.3f)
                    p.color = Color.argb(230, 150, 215, 255)
                    c.drawArc(rf, -90f, 360f * vProg, false, p)
                }
                else -> {
                    p.style = Paint.Style.FILL
                    p.color = Color.argb(alpha, 230, 245, 255)
                    c.drawCircle(x, y, 4.5f * d, p)
                }
            }
            // tap ripple
            val age = (SystemClock.uptimeMillis() - rippleAt)
            if (rippleAt > 0 && age < 420) {
                val f = age / 420f
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f * d * (1f - f)
                p.color = Color.argb((220 * (1f - f)).toInt(), 150, 215, 255)
                c.drawCircle(rippleX, rippleY, r * (0.8f + 1.8f * f), p)
            }
            postInvalidateOnAnimation()
        }
    }

    // ---------------------------------------------------------------- voice entry

    private val TRIGGERS = listOf(
        "air control", "air mouse", "hawa me", "hawa mein", "हवा में", "हवा से", "haath se control", "हाथ से कंट्रोल", "हाथ से कण्ट्रोल",
        "हाथ के इशारे", "haath ke ishare", "hand control", "hand gesture", "gesture control", "इशारों से", "ishaaron se", "एयर कंट्रोल", "एयर कण्ट्रोल"
    )
    private val STOPS = listOf("band", "बंद", "बन्द", "stop", "off", "khatam", "खत्म", "रोको", "ruko", "रुको")

    @Volatile private var gain = 1.0f

    fun handle(ctx: Context, hands: Hands, t: String): String? {
        if (running && (t.contains(Local.strip("cursor")) || t.contains(Local.strip("कर्सर")))) {
            if (listOf("tez", "तेज़", "तेज", "fast", "badhao", "बढ़ाओ").any { t.contains(Local.strip(it)) }) { gain = (gain * 1.25f).coerceAtMost(2.2f); return "कर्सर तेज़ कर दिया।" }
            if (listOf("dheema", "धीमा", "slow", "kam", "कम").any { t.contains(Local.strip(it)) }) { gain = (gain / 1.25f).coerceAtLeast(0.5f); return "कर्सर धीमा कर दिया।" }
        }
        if (TRIGGERS.none { t.contains(Local.strip(it)) }) return null
        return if (STOPS.any { t.contains(Local.strip(it)) }) stop() else start(ctx, hands)
    }

    // ---------------------------------------------------------------- start and stop

    fun start(ctx: Context, hands: Hands): String {
        if (running) return "हाथ के इशारे पहले से चल रहे हैं।"
        val app = ctx.applicationContext
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return "कैमरे की अनुमति चाहिए। Zyro खोलकर पावर बटन दबाओ, वहाँ अनुमति दे दो।"
        }
        if (!Settings.canDrawOverlays(app)) {
            return "Settings में 'Allow display over other apps' चालू कर दो, ताकि स्क्रीन पर कर्सर दिख सके।"
        }
        if (hands.service == null) return "पहले Accessibility में Zyro चालू करो, उसी से मैं टैप और स्क्रॉल कर पाऊँगा।"
        val hasModel = try { app.assets.open(MODEL).use { true } } catch (e: Exception) { false }
        if (!hasModel) return "हाथ पहचानने वाली मॉडल फ़ाइल ऐप में नहीं है। ऐप को GitHub से दोबारा बनाओ, बिल्ड के समय वह अपने आप डाउनलोड होती है।"
        appCtx = app
        handsRef = hands
        running = true
        resetState()
        Thread {
            try {
                val opts = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { res, _ -> onResult(res) }
                    .setErrorListener { _ -> inFlight.set(false) }
                    .build()
                landmarker = HandLandmarker.createFromOptions(app, opts)
                main.post { showCursor(app) }
                openCamera(app)
            } catch (e: Exception) {
                running = false
                Assistant.get(app).announce("हाथ के इशारे शुरू नहीं हो पाए।")
                cleanup()
            }
        }.start()
        return "हाथ के इशारे चालू। खुला हाथ दिखाओ, कर्सर हाथ के साथ चलेगा। अँगूठे को पहली उँगली की नोक से छुओ और छोड़ो तो टैप, छूकर हाथ ऊपर-नीचे घुमाओ तो स्वाइप या रील स्क्रॉल। (हाथ बंद करके छोड़ना भी चलता है।) सिर्फ़ पहली उँगली से स्क्रॉल होगा। दो उँगलियाँ (V) थोड़ी देर रखो तो पीछे, ज़्यादा देर रखो तो होम। बंद करने के लिए बोलो, हाथ के इशारे बंद।"
    }

    fun stop(): String {
        if (!running) return "हाथ के इशारे चल ही नहीं रहे थे।"
        running = false
        cleanup()
        return "हाथ के इशारे बंद।"
    }

    private fun resetState() {
        stable = 0
        candidate = 0
        candidateCount = 0
        curX = -1f
        curY = -1f
        trail.clear()
        dragging = false
        scrollRef = -1.0
        targets = emptyList()
        prevCurX = -1f
        prevCurY = -1f
        fx.reset(); fy.reset(); fScroll.reset()
        inFlight.set(false)
        lastHandAt = SystemClock.uptimeMillis()
    }

    private fun cleanup() {
        try { session?.close() } catch (e: Exception) { /* ignore */ }
        try { camera?.close() } catch (e: Exception) { /* ignore */ }
        try { reader?.close() } catch (e: Exception) { /* ignore */ }
        try { landmarker?.close() } catch (e: Exception) { /* ignore */ }
        session = null
        camera = null
        reader = null
        landmarker = null
        try { thread?.quitSafely() } catch (e: Exception) { /* ignore */ }
        thread = null
        main.post {
            try { overlay?.let { wm?.removeView(it) } } catch (e: Exception) { /* ignore */ }
            overlay = null
        }
    }

    // ---------------------------------------------------------------- overlay

    private fun showCursor(ctx: Context) {
        try {
            val m = ctx.resources.displayMetrics
            screenW = m.widthPixels
            screenH = m.heightPixels
            val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = w
            val v = CursorOverlay(ctx)
            v.tx = screenW / 2f
            v.ty = screenH / 2f
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            w.addView(v, lp)
            overlay = v
        } catch (e: Exception) {
            // gestures still work without the drawn cursor
        }
    }

    private fun show(x: Float, y: Float, mode: Int) {
        val v = overlay ?: return
        v.tx = x
        v.ty = y
        v.mode = mode
    }

    // ---------------------------------------------------------------- camera

    private fun openCamera(app: Context) {
        val t = HandlerThread("air-camera", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        t.start()
        thread = t
        val h = Handler(t.looper)
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        if (id == null) {
            running = false
            Assistant.get(app).announce("आगे वाला कैमरा नहीं मिला।")
            cleanup()
            return
        }
        val chars = cm.getCameraCharacteristics(id)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
        // small frame = fast; 320 to 480 wide is plenty to see a hand
        val size = sizes?.filter { it.width in 320..480 }?.maxByOrNull { it.width * it.height }
            ?: sizes?.minByOrNull { it.width * it.height }
        if (size == null) {
            running = false
            cleanup()
            return
        }
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, h)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    try {
                        @Suppress("DEPRECATION")
                        cam.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    req.addTarget(r.surface)
                                    s.setRepeatingRequest(req.build(), null, h)
                                } catch (e: Exception) {
                                    running = false
                                    cleanup()
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                running = false
                                cleanup()
                            }
                        }, h)
                    } catch (e: Exception) {
                        running = false
                        cleanup()
                    }
                }

                override fun onDisconnected(cam: CameraDevice) {
                    running = false
                    cleanup()
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    running = false
                    Assistant.get(app).announce("कैमरा नहीं खुल पाया। शायद कोई दूसरा ऐप इस्तेमाल कर रहा है।")
                    cleanup()
                }
            }, h)
        } catch (e: SecurityException) {
            running = false
            cleanup()
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val w = img.width
        val h = img.height
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val yB = yP.buffer
        val uB = uP.buffer
        val vB = vP.buffer
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val yRow = y * yP.rowStride
            val uvRow = (y shr 1) * uP.rowStride
            for (x in 0 until w) {
                val yy = yB.get(yRow + x * yP.pixelStride).toInt() and 0xFF
                val u = (uB.get(uvRow + (x shr 1) * uP.pixelStride).toInt() and 0xFF) - 128
                val v = (vB.get((y shr 1) * vP.rowStride + (x shr 1) * vP.pixelStride).toInt() and 0xFF) - 128
                val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (yy - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val raw = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.postRotate(sensorOrientation.toFloat())
        m.postScale(-1f, 1f) // mirror, like a selfie: hand right = cursor right
        return Bitmap.createBitmap(raw, 0, 0, w, h, m, true)
    }

    private fun onFrame(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return
        if (!running || !inFlight.compareAndSet(false, true)) {
            img.close()
            return
        }
        val bmp = try { toBitmap(img) } catch (e: Exception) { null } finally { img.close() }
        val lm = landmarker
        if (bmp == null || lm == null) {
            inFlight.set(false)
            return
        }
        try {
            lm.detectAsync(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis())
        } catch (e: Exception) {
            inFlight.set(false)
        }
    }

    // ---------------------------------------------------------------- result for one frame

    private fun onResult(result: HandLandmarkerResult) {
        inFlight.set(false)
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val pts = result.landmarks().firstOrNull()
        if (pts == null || pts.size < 21) {
            handLost(now)
            return
        }
        lastHandAt = now
        val x = FloatArray(21) { pts[it].x() }
        val y = FloatArray(21) { pts[it].y() }
        try { process(x, y, now) } catch (e: Exception) { /* skip a bad frame */ }
    }

    private fun handLost(now: Long) {
        candidate = 0
        candidateCount = 0
        if (stable == FIST && now - fistSince < 700) {
            // a one-frame dropout while closing: keep waiting
        } else {
            stable = 0
            dragging = false
            overlay?.dragLine = false
        }
        scrollRef = -1.0
        show(if (curX < 0) screenW / 2f else curX, if (curY < 0) screenH / 2f else curY, 0)
        if (now - lastHandAt > 180_000) {
            running = false
            cleanup()
            appCtx?.let { Assistant.get(it).announce("तीन मिनट से हाथ नहीं दिखा, इसलिए हाथ के इशारे बंद कर दिए।") }
        }
    }

    private fun d(ax: Float, ay: Float, bx: Float, by: Float) = hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

    private fun process(x: FloatArray, y: FloatArray, now: Long) {
        // a finger is "up" when its tip is clearly farther from the wrist than its middle joint (works at any hand angle)
        fun up(tip: Int, pip: Int) = d(x[tip], y[tip], x[0], y[0]) > d(x[pip], y[pip], x[0], y[0]) * 1.10f
        val idx = up(8, 6)
        val mid = up(12, 10)
        val ring = up(16, 14)
        val pinky = up(20, 18)
        // palm centre: steady even while the fingers curl
        val px = (x[0] + x[5] + x[9] + x[13] + x[17]) / 5f
        val py = (y[0] + y[5] + y[9] + y[13] + y[17]) / 5f
        val n = (if (idx) 1 else 0) + (if (mid) 1 else 0) + (if (ring) 1 else 0) + (if (pinky) 1 else 0)
        val palmLen = d(x[0], y[0], x[9], y[9]).coerceAtLeast(0.01f)
        val pd = d(x[4], y[4], x[8], y[8]) / palmLen
        val others = (if (mid) 1 else 0) + (if (ring) 1 else 0) + (if (pinky) 1 else 0)
        val pinchNow = others >= 2 && (if (stable == PINCH) pd < 0.55f else pd < 0.34f)
        val raw = when {
            pinchNow -> PINCH
            n >= 3 -> OPEN
            idx && mid && !ring && !pinky -> VEE
            idx && !mid && !ring && !pinky -> POINT
            n == 0 -> FIST
            else -> 0
        }
        // a pose must hold for two frames; in-between shapes do not change anything
        if (stable != FIST && stable != PINCH) { lastPx = px; lastPy = py }
        if (raw != 0) {
            if (raw == candidate) candidateCount++ else { candidate = raw; candidateCount = 1 }
            if (candidateCount >= 2 && candidate != stable) changePose(candidate, now)
        }

        when (stable) {
            OPEN -> {
                // the middle part of the camera view covers the whole screen, so small hand moves are enough
                val tx = ((((px - 0.5f) * gain / 0.68f) + 0.5f).coerceIn(0f, 1f)) * screenW
                val ty = ((((py - 0.45f) * gain / 0.62f) + 0.5f).coerceIn(0f, 1f)) * screenH
                var cx = fx.filter(tx.toDouble(), now).toFloat()
                var cy = fy.filter(ty.toDouble(), now).toFloat()
                scanTargets(now)
                val tg = nearestTarget(cx, cy)
                if (tg != null && prevCurX >= 0f && d(cx, cy, prevCurX, prevCurY) < 16f) {
                    // moving slowly toward a button: ease the cursor onto its centre
                    cx += (tg.centerX() - cx) * 0.4f
                    cy += (tg.centerY() - cy) * 0.4f
                }
                prevCurX = cx
                prevCurY = cy
                overlay?.hl = tg?.let { RectF(it) }
                curX = cx
                curY = cy
                trail.addLast(floatArrayOf(now.toFloat(), curX, curY))
                while (trail.size > 1 && now - trail.first()[0] > 600) trail.removeFirst()
                show(curX, curY, 1)
            }
            FIST, PINCH -> {
                val pz = stable == PINCH
                if (!dragging && d(px, py, fistPalmX, fistPalmY) > (if (pz) 0.04f else 0.07f)) { dragging = true; dragStartAt = now }
                if (dragging) {
                    val pg = if (pz) 1.5f else 1f // a small thumb-style flick travels a long way
                    dragX = (anchorX + (px - fistPalmX) * gain * pg / 0.68f * screenW).coerceIn(0f, screenW.toFloat())
                    dragY = (anchorY + (py - fistPalmY) * gain * pg / 0.62f * screenH).coerceIn(0f, screenH.toFloat())
                    overlay?.fromX = anchorX
                    overlay?.fromY = anchorY
                    overlay?.dragLine = true
                    show(dragX, dragY, 2)
                } else {
                    show(anchorX, anchorY, 2)
                }
            }
            POINT -> scroll(y[8], now)
            VEE -> {
                overlay?.hl = null
                overlay?.vProg = ((now - vSince) / 1400f).coerceIn(0f, 1f)
                show(if (curX < 0) screenW / 2f else curX, if (curY < 0) screenH / 2f else curY, 4)
            }
            else -> show(if (curX < 0) screenW / 2f else curX, if (curY < 0) screenH / 2f else curY, 1)
        }
    }

    private fun changePose(next: Int, now: Long) {
        val prev = stable
        stable = next
        if ((prev == FIST && next == PINCH) || (prev == PINCH && next == FIST)) { stable = next; return }
        if (prev == POINT) { scrollRef = -1.0; overlay?.scrollDir = 0 }
        if (next != OPEN) overlay?.hl = null
        if (next == VEE) vSince = now
        if (prev == VEE && next != VEE) fireV(now)
        if (next == FIST || next == PINCH) {
            // the click goes where the cursor was just BEFORE the hand started to close
            val cut = now - 220
            var a = trail.lastOrNull()
            for (p in trail) if (p[0] <= cut) a = p
            anchorX = a?.get(1) ?: if (curX < 0) screenW / 2f else curX
            anchorY = a?.get(2) ?: if (curY < 0) screenH / 2f else curY
            fistSince = now
            dragging = false
            fistPalmX = lastPalmX()
            fistPalmY = lastPalmY()
        }
        if ((prev == FIST || prev == PINCH) && next != FIST && next != PINCH) release(now, prev == PINCH)
        if (next == OPEN) {
            // restart smoothing from the current spot so the cursor does not jump
            fx.reset(); fy.reset()
            trail.clear()
        }
    }

    /** Two fingers held: a short hold = Back, a long hold = Home. */
    private fun fireV(now: Long) {
        val hands = handsRef ?: return
        overlay?.vProg = 0f
        val held = now - vSince
        if (held < 350) return
        overlay?.let { it.rippleX = curX.coerceAtLeast(0f); it.rippleY = curY.coerceAtLeast(0f); it.rippleAt = SystemClock.uptimeMillis() }
        if (held >= 1400) act { hands.press("home") } else act { hands.press("back") }
    }

    private var lastPx = 0.5f
    private var lastPy = 0.5f
    private fun lastPalmX() = lastPx
    private fun lastPalmY() = lastPy

    private fun release(now: Long, pinch: Boolean) {
        val hands = handsRef ?: return
        overlay?.dragLine = false
        val held = now - fistSince
        val ax = anchorX.toInt()
        val ay = anchorY.toInt()
        if (dragging) {
            val ex = dragX.toInt()
            val ey = dragY.toInt()
            dragging = false
            curX = dragX
            curY = dragY
            // a quick flick = a fast swipe (reels, pages); a slow drag stays slow
            val dur = if (pinch) (now - dragStartAt).toInt().coerceIn(110, 450) else 320
            act { hands.swipe(ax, ay, ex, ey, dur) }
        } else if (held in 60..4000) {
            if (now - lastTapAt < 250) return
            lastTapAt = now
            overlay?.let { it.rippleX = anchorX; it.rippleY = anchorY; it.rippleAt = SystemClock.uptimeMillis() }
            if (held > (if (pinch) 800 else 1500)) {
                act { val p = snap(hands, ax, ay); hands.swipe(p[0], p[1], p[0] + 1, p[1], 800) }
            } else {
                act { val p = snap(hands, ax, ay); hands.tap(p[0], p[1]) }
            }
        }
        curX = anchorX
        curY = anchorY
    }

    /** Index finger only: moving it up or down scrolls the page. */
    private fun scroll(tipY: Float, now: Long) {
        val hands = handsRef ?: return
        val v = fScroll.filter(tipY.toDouble(), now)
        show(if (curX < 0) screenW / 2f else curX, if (curY < 0) screenH / 2f else curY, 3)
        if (scrollRef < 0) { scrollRef = v; return }
        val dy = (scrollRef - v).toFloat() // positive = finger moved up
        overlay?.scrollDir = if (dy > 0.01f) -1 else if (dy < -0.01f) 1 else 0
        if (abs(dy) > 0.055f && !actionBusy.get()) {
            scrollRef = v
            val len = (abs(dy) * screenH * 3.2f).coerceIn(screenH * 0.14f, screenH * 0.5f).toInt()
            val cx = screenW / 2
            val mid = (screenH * 0.55f).toInt()
            // finger up = page moves up, like dragging it with your own finger
            val from = if (dy > 0) mid + len / 2 else mid - len / 2
            val to = if (dy > 0) mid - len / 2 else mid + len / 2
            act { hands.swipe(cx, from, cx, to, 170) }
        }
    }

    /** Finds the real button under (or near) the cursor so that small buttons are easy to hit. */
    private fun snap(hands: Hands, x: Int, y: Int): IntArray {
        val root = try { hands.service?.rootInActiveWindow } catch (e: Exception) { null } ?: return intArrayOf(x, y)
        val r = Rect()
        var bestScore = Float.MAX_VALUE
        var bx = x
        var by = y
        val limit = screenW.toLong() * screenH * 0.25
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30) return
            if (n.isClickable && n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    val area = r.width().toLong() * r.height()
                    if (area < limit) {
                        val inside = r.contains(x, y)
                        val dd = d(x.toFloat(), y.toFloat(), r.centerX().toFloat(), r.centerY().toFloat())
                        if (inside || dd < 90f) {
                            val score = if (inside) area.toFloat() else 1e12f + dd
                            if (score < bestScore) {
                                bestScore = score
                                bx = r.centerX()
                                by = r.centerY()
                            }
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        try { walk(root, 0) } catch (e: Exception) { /* use the plain point */ }
        return intArrayOf(bx, by)
    }

    private fun act(block: () -> Unit) {
        if (!actionBusy.compareAndSet(false, true)) return
        actions.execute {
            try {
                block()
            } catch (e: Exception) {
                // ignore a failed gesture
            } finally {
                actionBusy.set(false)
            }
        }
    }
}
