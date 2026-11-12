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

    private val fx = OneEuro(1.4, 14.0)
    private val fy = OneEuro(1.4, 14.0)
    private val fScroll = OneEuro(2.0, 10.0)

    // ---------------------------------------------------------------- state

    private const val OPEN = 1
    private const val FIST = 2
    private const val POINT = 3

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

    fun handle(ctx: Context, hands: Hands, t: String): String? {
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
        return "हाथ के इशारे चालू। खुला हाथ दिखाओ, कर्सर हाथ के साथ चलेगा। हाथ बंद करके छोड़ो तो टैप होगा। सिर्फ़ पहली उँगली से स्क्रॉल होगा। बंद करने के लिए बोलो, हाथ के इशारे बंद।"
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
        val raw = when {
            n >= 3 -> OPEN
            idx && !mid && !ring && !pinky -> POINT
            n == 0 -> FIST
            else -> 0
        }
        // a pose must hold for two frames; in-between shapes do not change anything
        if (stable != FIST) { lastPx = px; lastPy = py }
        if (raw != 0) {
            if (raw == candidate) candidateCount++ else { candidate = raw; candidateCount = 1 }
            if (candidateCount >= 2 && candidate != stable) changePose(candidate, now)
        }

        when (stable) {
            OPEN -> {
                // the middle part of the camera view covers the whole screen, so small hand moves are enough
                val tx = (((px - 0.16f) / 0.68f).coerceIn(0f, 1f)) * screenW
                val ty = (((py - 0.14f) / 0.62f).coerceIn(0f, 1f)) * screenH
                curX = fx.filter(tx.toDouble(), now).toFloat()
                curY = fy.filter(ty.toDouble(), now).toFloat()
                trail.addLast(floatArrayOf(now.toFloat(), curX, curY))
                while (trail.size > 1 && now - trail.first()[0] > 600) trail.removeFirst()
                show(curX, curY, 1)
            }
            FIST -> {
                if (!dragging && d(px, py, fistPalmX, fistPalmY) > 0.07f) dragging = true
                if (dragging) {
                    dragX = (anchorX + (px - fistPalmX) / 0.68f * screenW).coerceIn(0f, screenW.toFloat())
                    dragY = (anchorY + (py - fistPalmY) / 0.62f * screenH).coerceIn(0f, screenH.toFloat())
                    overlay?.fromX = anchorX
                    overlay?.fromY = anchorY
                    overlay?.dragLine = true
                    show(dragX, dragY, 2)
                } else {
                    show(anchorX, anchorY, 2)
                }
            }
            POINT -> scroll(y[8], now)
            else -> show(if (curX < 0) screenW / 2f else curX, if (curY < 0) screenH / 2f else curY, 1)
        }
    }

    private fun changePose(next: Int, now: Long) {
        val prev = stable
        stable = next
        if (prev == POINT) { scrollRef = -1.0; overlay?.scrollDir = 0 }
        if (next == FIST) {
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
        if (prev == FIST && next != FIST) release(now)
        if (next == OPEN) {
            // restart smoothing from the current spot so the cursor does not jump
            fx.reset(); fy.reset()
            trail.clear()
        }
    }

    private var lastPx = 0.5f
    private var lastPy = 0.5f
    private fun lastPalmX() = lastPx
    private fun lastPalmY() = lastPy

    private fun release(now: Long) {
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
            act { hands.swipe(ax, ay, ex, ey, 320) }
        } else if (held in 60..4000) {
            if (now - lastTapAt < 250) return
            lastTapAt = now
            overlay?.let { it.rippleX = anchorX; it.rippleY = anchorY; it.rippleAt = SystemClock.uptimeMillis() }
            if (held > 1500) {
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
