package com.zyro.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Home screen design 2, "Arc Reactor". The artwork is the exact picture from the design preview, cut into layers
 * (glow, outer rings, inner rings, ticks, triangles, core) so that it looks the same, and each layer is turned,
 * pulsed and lit by Zyro's mode (idle / listening / thinking / speaking) and the voice level.
 */
class ReactorView(context: Context) : View(context) {
    var modeSource: (() -> Int)? = null
    var levelSource: (() -> Float)? = null
    var holo = false

    private val d = resources.displayMetrics.density
    private val bgP = Paint()
    private val layerP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    @Volatile private var glowB: Bitmap? = null
    @Volatile private var outerB: Bitmap? = null
    @Volatile private var innerB: Bitmap? = null
    @Volatile private var ticksB: Bitmap? = null
    @Volatile private var triB: Bitmap? = null
    @Volatile private var coreB: Bitmap? = null
    @Volatile private var ready = false

    private var t = 0f
    private var last = 0L
    private var energy = 0.15f
    private var spinA = 0f
    private var spinB = 0f
    private var flare = 0f
    private var lastMode = -1
    private val shockAge = FloatArray(3) { 2f }
    private var shockNext = 0
    private var touchKick = 0f

    init {
        // the pictures are decoded once, off the main thread
        Thread {
            try {
                val o = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val r = resources
                glowB = BitmapFactory.decodeResource(r, R.drawable.reactor_glow, o)
                outerB = BitmapFactory.decodeResource(r, R.drawable.reactor_outer, o)
                innerB = BitmapFactory.decodeResource(r, R.drawable.reactor_inner, o)
                ticksB = BitmapFactory.decodeResource(r, R.drawable.reactor_ticks, o)
                triB = BitmapFactory.decodeResource(r, R.drawable.reactor_tri, o)
                coreB = BitmapFactory.decodeResource(r, R.drawable.reactor_core, o)
                ready = glowB != null && outerB != null && innerB != null && ticksB != null && triB != null && coreB != null
            } catch (e: Throwable) { ready = false }
            postInvalidate()
        }.start()
    }

    private fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // same background as the preview: an oval radial gradient (#06222e to #020b12), unit sized and stretched when drawn
        bgP.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.rgb(6, 34, 46), Color.rgb(2, 11, 18), Color.rgb(2, 11, 18)),
            floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) touchKick = 1f
        return super.onTouchEvent(e)
    }

    /** One picture layer, centred on the reactor, turned by [deg] and drawn at [scale] times its normal size. */
    private fun layer(c: Canvas, b: Bitmap?, size: Float, deg: Float, scale: Float, alpha: Float) {
        if (b == null) return
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        if (a <= 0) return
        layerP.alpha = a
        c.save()
        c.rotate(deg)
        val s = size / b.width * scale
        c.scale(s, s)
        c.drawBitmap(b, -b.width / 2f, -b.height / 2f, layerP)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
        last = now
        t += dt

        val mode = modeSource?.invoke() ?: OrbView.IDLE
        val level = (levelSource?.invoke() ?: 0f).coerceIn(0f, 1f)
        val want = when (mode) {
            OrbView.LISTEN -> 0.25f + level * 0.75f
            OrbView.THINK -> 0.9f
            OrbView.SPEAK -> 0.4f + 0.35f * abs(sin(t * 7.3f) * cos(t * 3.1f)) + level * 0.3f
            else -> 0.12f + 0.04f * sin(t * 1.6f)
        }
        energy = lerp(energy, want, 1f - exp(-dt * (if (want > energy) 12f else 4f)))
        touchKick = lerp(touchKick, 0f, 1f - exp(-dt * 3f))
        val en = (energy + touchKick * 0.5f).coerceAtMost(1f)
        flare = lerp(flare, if (mode == OrbView.THINK) 1f else 0f, 1f - exp(-dt * 6f))

        if (mode != lastMode) {
            if (lastMode != -1 && mode != OrbView.IDLE) { shockAge[shockNext] = 0f; shockNext = (shockNext + 1) % shockAge.size }
            lastMode = mode
        }
        // resting speed = the preview (outer one turn in 40 s, inner the other way in 26 s); it speeds up with energy
        spinA += dt * (9f + 70f * en + 100f * flare)
        spinB -= dt * (13.8f + 55f * en + 130f * flare)

        // fade up out of the dark when the app opens
        val intro = (t / 1.5f).coerceIn(0f, 1f)
        val ie = 1f - (1f - intro) * (1f - intro) * (1f - intro)

        // background: oval gradient, 100% x 70% of the screen, centred at 50% 45%
        c.save()
        c.translate(w / 2f, h * 0.45f)
        c.scale(w, h * 0.7f)
        c.drawRect(-3f, -3f, 3f, 3f, bgP)
        c.restore()

        val cx = w / 2f
        val cy = if (holo) h * 0.5f else h * 0.45f
        // the preview's reactor box is 340 of 390 wide
        val size = min(w, h) * (340f / 390f) * (0.92f + 0.08f * ie) * (1f + 0.02f * en)

        c.save()
        c.translate(cx, cy)

        val pulse = 0.5f + 0.5f * sin(t * 2.6f + en * 2f)
        // glow
        layer(c, glowB, size, 0f, 1.04f + 0.06f * en, (0.9f + 0.3f * en) * ie)
        layer(c, glowB, size, 0f, 1f + 0.04f * en, (0.4f + 0.2f * en) * ie)
        // outer rings turn one way, inner rings the other
        layer(c, outerB, size, spinA, 1f, ie)
        layer(c, innerB, size, spinB, 1f, ie)
        layer(c, ticksB, size, 0f, 1f, ie)
        // triangles breathe like the preview
        layer(c, triB, size, 0f, 1f, (0.7f + 0.3f * pulse) * ie)
        // thinking: a second, white copy of the outer rings flashes on top
        if (flare > 0.02f) layer(c, outerB, size, -spinA * 1.6f, 1.03f, flare * 0.5f * ie)
        // the core grows and brightens with energy
        layer(c, coreB, size, 0f, 1f + 0.35f * en + 0.15f * flare, (0.75f + 0.25f * pulse) * ie)
        if (en > 0.55f) layer(c, coreB, size, 0f, 0.8f + 0.25f * en, (en - 0.55f) * 1.6f * ie)
        // soft bloom round the core
        layer(c, coreB, size, 0f, 1.25f + 0.35f * en, (0.38f + 0.1f * pulse) * ie)
        layer(c, coreB, size, 0f, 1.6f + 0.35f * en, (0.3f + 0.08f * pulse) * ie)

        // shock rings when Zyro starts listening, thinking or speaking
        val R = size / 2f
        for (i in shockAge.indices) {
            if (shockAge[i] >= 1f) continue
            shockAge[i] += dt / 1.0f
            val f = shockAge[i].coerceIn(0f, 1f)
            line.strokeWidth = (4f * (1f - f) + 1f) * d
            line.color = Color.argb(((1f - f) * (1f - f) * 210).toInt(), 150, 240, 255)
            c.drawCircle(0f, 0f, R * (0.9f + f * 1.3f), line)
        }
        c.restore()

        if (intro < 1f) c.drawColor(Color.argb(((1f - ie) * 255).toInt(), 0, 0, 0))
        postInvalidateOnAnimation()
    }
}
