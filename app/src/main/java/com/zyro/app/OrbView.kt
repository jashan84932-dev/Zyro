package com.zyro.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Zyro's face: a cinematic holographic HUD in the style of the Iron Man films.
 * Concentric glowing rings turn in opposite directions, a radar sweep circles the dial,
 * a ring of voice bars jumps with the sound, and a bright core pulses in the centre.
 *   IDLE   - calm, slow turning
 *   LISTEN - voice bars follow how loud you are
 *   THINK  - rings spin fast, the sweep accelerates
 *   SPEAK  - voice bars dance and the core pulses
 */
class OrbView(context: Context) : View(context) {

    companion object {
        const val IDLE = 0
        const val LISTEN = 1
        const val THINK = 2
        const val SPEAK = 3

        private const val BARS = 96
        private const val TICKS = 180
        private const val DUST = 36
        private const val TAU = (2.0 * PI).toFloat()
        private val CYAN = Color.rgb(25, 195, 255)
        private val BRIGHT = Color.rgb(150, 235, 255)
        private val DEEP = Color.rgb(10, 95, 200)
    }

    var modeSource: (() -> Int)? = null
    var levelSource: (() -> Float)? = null

    private val d = resources.displayMetrics.density

    // everything below is drawn in "unit space": the dial has radius 1 and the canvas is scaled to fit
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(120, 20, 140, 255), Color.argb(50, 10, 90, 220), Color.argb(0, 5, 40, 160)),
            floatArrayOf(0.25f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(255, 245, 252, 255), Color.argb(230, 130, 225, 255), Color.argb(110, 25, 160, 255), Color.argb(0, 10, 90, 220)),
            floatArrayOf(0f, 0.22f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(0, 10, 60, 160), Color.argb(34, 10, 80, 190), Color.argb(70, 15, 110, 230)),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = SweepGradient(
            0f, 0f,
            intArrayOf(Color.argb(0, 25, 195, 255), Color.argb(0, 25, 195, 255), Color.argb(34, 25, 195, 255), Color.argb(150, 120, 230, 255), Color.argb(0, 25, 195, 255)),
            floatArrayOf(0f, 0.55f, 0.85f, 0.995f, 1f)
        )
    }

    // precomputed directions
    private val bc = FloatArray(BARS) { cos(it.toFloat() / BARS * TAU) }
    private val bs = FloatArray(BARS) { sin(it.toFloat() / BARS * TAU) }
    private val tickLines = FloatArray(TICKS * 4)
    private val dustX = FloatArray(DUST) { (it * 0.6180339f) % 1f }
    private val dustY = FloatArray(DUST) { (it * 0.7548776f) % 1f }
    private val dustV = FloatArray(DUST) { 0.006f + 0.012f * ((it * 37) % 10) / 10f }
    private val dustA = FloatArray(DUST) { 0.3f + 0.7f * ((it * 53) % 10) / 10f }

    private var last = 0L
    private var t = 0f
    private var energy = 0.2f
    private var lvl = 0f
    private var speed = 1f
    private var rot = 0f      // master angle driving all rings

    init {
        for (i in 0 until TICKS) {
            val a = i.toFloat() / TICKS * TAU
            val major = i % 10 == 0
            val mid = i % 5 == 0
            val r0 = 1.56f
            val r1 = if (major) 1.68f else if (mid) 1.63f else 1.60f
            tickLines[i * 4] = cos(a) * r0
            tickLines[i * 4 + 1] = sin(a) * r0
            tickLines[i * 4 + 2] = cos(a) * r1
            tickLines[i * 4 + 3] = sin(a) * r1
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(1, 4, 10), Color.rgb(3, 12, 28), Color.rgb(1, 4, 11)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    /** One glowing arc: a wide faint stroke under a thin bright one (a cheap bloom). */
    private fun arc(c: Canvas, r: Float, start: Float, sweep: Float, w: Float, color: Int, alpha: Int) {
        box.set(-r, -r, r, r)
        line.shader = null
        line.pathEffect = null
        line.color = color
        line.strokeWidth = w * 3.2f
        line.alpha = (alpha * 0.20f).toInt()
        c.drawArc(box, start, sweep, false, line)
        line.strokeWidth = w
        line.alpha = alpha
        c.drawArc(box, start, sweep, false, line)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.nanoTime()
        var dt = if (last == 0L) 0.016f else (now - last) / 1e9f
        last = now
        dt = dt.coerceIn(0.001f, 0.05f)
        t += dt

        val mode = modeSource?.invoke() ?: IDLE
        val raw = levelSource?.invoke() ?: 0f
        lvl += (raw - lvl) * (1f - exp(-dt * 10f))

        val targetE: Float
        val targetS: Float
        when (mode) {
            LISTEN -> { targetE = 0.35f + 0.65f * lvl; targetS = 1.5f }
            THINK -> { targetE = 0.8f; targetS = 4.2f }
            SPEAK -> {
                targetE = 0.55f + 0.4f * abs(sin(t * 4.4f)) * abs(sin(t * 1.7f + 0.6f))
                targetS = 2.2f
            }
            else -> { targetE = 0.14f; targetS = 1f }
        }
        val k = 1f - exp(-dt * 3f)
        energy += (targetE - energy) * k
        speed += (targetS - speed) * k
        rot += speed * dt * 22f          // degrees per second at speed 1

        val cx = w / 2f
        val cy = h * 0.47f
        val R = min(w, h) * 0.23f * (1f + 0.025f * sin(t * 1.2f))   // radius of the main dial (r = 1)
        val px = d / R                                                // one dp in unit space

        // ---------- background ----------
        c.drawRect(0f, 0f, w, h, bgPaint)

        // faint scan lines
        line.shader = null
        line.pathEffect = null
        line.color = Color.rgb(30, 130, 220)
        line.strokeWidth = 1f
        line.alpha = 10
        var yy = (t * 14f * d) % (6f * d)
        while (yy < h) {
            c.drawLine(0f, yy, w, yy, line)
            yy += 6f * d
        }

        // dust
        for (q in 0 until DUST) {
            dustY[q] -= dustV[q] * dt
            if (dustY[q] < -0.02f) { dustY[q] = 1.02f; dustX[q] = (q * 0.6180339f + t * 0.011f) % 1f }
            val tw = 0.5f + 0.5f * sin(t * 1.2f + q * 1.9f)
            fillP.shader = null
            fillP.color = Color.rgb(140, 220, 255)
            fillP.alpha = (20 + 70 * tw * dustA[q]).toInt()
            c.drawCircle(dustX[q] * w, dustY[q] * h, (0.5f + 1.0f * dustA[q]) * d, fillP)
        }

        // corner brackets (pure decoration, no text)
        line.shader = null
        line.color = CYAN
        line.strokeWidth = 1.6f * d
        line.alpha = 150
        val m = 20f * d
        val L = 30f * d
        val top = 44f * d
        val bot = h - 30f * d
        for (sx in 0..1) {
            val x = if (sx == 0) m else w - m
            val dx = if (sx == 0) L else -L
            c.drawLine(x, top, x + dx, top, line)
            c.drawLine(x, top, x, top + L, line)
            c.drawLine(x, bot, x + dx, bot, line)
            c.drawLine(x, bot, x, bot - L, line)
        }

        // ---------- the dial (unit space) ----------
        c.save()
        c.translate(cx, cy)
        c.scale(R, R)

        // halo and disc
        c.save()
        c.scale(2.6f, 2.6f)
        haloPaint.alpha = (150 + 105 * energy).toInt().coerceIn(0, 255)
        c.drawCircle(0f, 0f, 1f, haloPaint)
        c.restore()
        c.drawCircle(0f, 0f, 1.5f, discPaint)

        val e = energy

        // outer graduated scale (ticks), slowly turning
        c.save()
        c.rotate(rot * 0.12f)
        line.shader = null
        line.pathEffect = null
        line.color = BRIGHT
        line.strokeWidth = 1.2f * px
        line.alpha = (90 + 80 * e).toInt()
        c.drawLines(tickLines, line)
        c.restore()
        arc(c, 1.53f, 0f, 360f, 1.3f * px, CYAN, 120)

        // ring A: three thick arc segments, clockwise
        c.save()
        c.rotate(rot * 0.55f)
        arc(c, 1.40f, 0f, 96f, 5.5f * px, CYAN, (190 + 60 * e).toInt().coerceAtMost(255))
        arc(c, 1.40f, 130f, 58f, 5.5f * px, CYAN, (190 + 60 * e).toInt().coerceAtMost(255))
        arc(c, 1.40f, 215f, 34f, 5.5f * px, BRIGHT, 255)
        arc(c, 1.40f, 270f, 70f, 5.5f * px, CYAN, (190 + 60 * e).toInt().coerceAtMost(255))
        c.restore()

        // ring B: dashed circle, counter-clockwise
        c.save()
        c.rotate(-rot * 0.8f)
        line.shader = null
        line.color = BRIGHT
        line.strokeWidth = 1.6f * px
        line.alpha = 170
        line.pathEffect = DashPathEffect(floatArrayOf(0.035f, 0.06f), 0f)
        box.set(-1.27f, -1.27f, 1.27f, 1.27f)
        c.drawArc(box, 0f, 360f, false, line)
        line.pathEffect = null
        c.restore()

        // ring C: segmented reactor ring, clockwise
        c.save()
        c.rotate(rot * 0.35f)
        for (i in 0 until 12) {
            arc(c, 1.13f, i * 30f + 3f, 24f, 8f * px, if (i % 4 == 0) BRIGHT else CYAN, (150 + 100 * e).toInt().coerceAtMost(255))
        }
        c.restore()
        arc(c, 1.02f, 0f, 360f, 1.4f * px, CYAN, 150)

        // radar sweep
        c.save()
        c.rotate(rot * (1.6f + 0.4f * speed))
        c.drawCircle(0f, 0f, 1.0f, sweepPaint)
        c.restore()

        // voice bars (the part that reacts to sound)
        line.shader = null
        line.pathEffect = null
        line.strokeCap = Paint.Cap.ROUND
        line.color = BRIGHT
        line.strokeWidth = 5.2f * px
        for (i in 0 until BARS) {
            val n = abs(sin(i * 0.71f + t * (2.2f + 1.6f * speed)) * cos(i * 0.33f - t * 1.3f))
            val len = 0.03f + (0.045f + 0.30f * e) * (0.25f + 0.75f * n)
            val r0 = 0.64f
            val r1 = r0 + len
            line.alpha = (110 + 140 * n * (0.4f + e)).toInt().coerceIn(60, 255)
            c.drawLine(bc[i] * r0, bs[i] * r0, bc[i] * r1, bs[i] * r1, line)
        }
        line.strokeCap = Paint.Cap.BUTT

        // inner rotating arcs
        c.save()
        c.rotate(-rot * 1.5f)
        arc(c, 0.52f, 0f, 70f, 4f * px, BRIGHT, 230)
        arc(c, 0.52f, 120f, 70f, 4f * px, CYAN, 210)
        arc(c, 0.52f, 240f, 70f, 4f * px, BRIGHT, 230)
        c.restore()
        arc(c, 0.43f, 0f, 360f, 1.2f * px, CYAN, 140)

        // core
        val pulse = 1f + 0.16f * e * sin(t * 6.5f)
        c.save()
        c.scale(0.46f * pulse, 0.46f * pulse)
        c.drawCircle(0f, 0f, 1f, corePaint)
        c.restore()
        arc(c, 0.17f * pulse, 0f, 360f, 1.6f * px, Color.WHITE, 200)

        // little orbiting beacons on the dashed ring
        for (s in 0 until 3) {
            val a = (-rot * 0.8f + s * 120f) * PI.toFloat() / 180f
            fillP.shader = null
            fillP.color = Color.WHITE
            fillP.alpha = 255
            c.drawCircle(cos(a) * 1.27f, sin(a) * 1.27f, 3.2f * px, fillP)
            fillP.color = CYAN
            fillP.alpha = 70
            c.drawCircle(cos(a) * 1.27f, sin(a) * 1.27f, 9f * px, fillP)
        }

        c.restore()
        postInvalidateOnAnimation()
    }
}
