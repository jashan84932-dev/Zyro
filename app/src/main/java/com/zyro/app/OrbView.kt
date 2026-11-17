package com.zyro.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zyro's face: a glass sphere of orange circuit-city above and cyan storm below, ringed with streaking light,
 * with white-cyan lightning veins running through it and a long strand falling to the bottom of the screen.
 * The still parts are painted once into a bitmap; the ring turns, the beam breathes and the lightning re-forks live.
 *   IDLE   - slow turning, calm veins
 *   LISTEN - the heart and beam swell with how loud you are
 *   THINK  - fast forking and a cyan flash
 *   SPEAK  - the heart pulses with Zyro's voice
 */
class OrbView(context: Context) : View(context), SensorEventListener {

    companion object {
        const val IDLE = 0
        const val LISTEN = 1
        const val THINK = 2
        const val SPEAK = 3
        private const val TAU = 6.2831855f
    }

    var modeSource: (() -> Int)? = null
    var levelSource: (() -> Float)? = null

    /** Hologram mode: pure black background, centred - for a Pepper's-ghost pyramid. */
    var holo = false

    private val addMode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val addP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = addMode }
    private val strokeAdd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = addMode; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bmpAdd = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { xfermode = addMode }

    @Volatile private var still: Bitmap? = null
    @Volatile private var ring: Bitmap? = null
    private var building = false
    private var builtW = 0
    private var builtH = 0
    private var cx = 0f
    private var cy = 0f
    private var R = 1f
    private var k = 1f

    private var t = 0f
    private var last = 0L
    private var energy = 0.1f
    private var flare = 0f
    private var spin = 0f

    // lightning: three groups, each rebuilt on its own timer and drawn every frame from a cached path
    private val rnd = Random()
    private val trunkPath = Path()
    private val curvePath = Path()
    private val thinPath = Path()
    private var trunkNext = 0f
    private var curveNext = 0f
    private var thinNext = 0f

    private val bokeh = FloatArray(110 * 5)

    // shockwave rings that leave the sphere whenever Zyro changes what it is doing
    private val shockAge = FloatArray(3) { 2f }
    private val shockHue = IntArray(3)
    private var lastMode = -1
    private var shockNext = 0

    // gentle 3D parallax: the sphere, the ring and the specks shift a little at different depths as the phone tilts
    private var tiltX = 0f
    private var tiltY = 0f
    private var tiltTX = 0f
    private var tiltTY = 0f
    private var sm: SensorManager? = null
    private val nodes = FloatArray(10 * 2)
    private var nodeCount = 0

    init {
        val r = Random(5)
        for (i in 0 until 110) {
            bokeh[i * 5] = r.nextFloat()            // x
            bokeh[i * 5 + 1] = r.nextFloat() * 0.9f // y
            bokeh[i * 5 + 2] = 1f + r.nextFloat() * r.nextFloat() * 5f // radius
            bokeh[i * 5 + 3] = if (r.nextBoolean()) 1f else 0f         // warm
            bokeh[i * 5 + 4] = r.nextFloat() * TAU  // phase
        }
    }

    private fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x

    // ---------------------------------------------------------------------------------------------------------
    // still layers
    // ---------------------------------------------------------------------------------------------------------

    private fun buildLayers(w: Int, h: Int, holoMode: Boolean) {
        val kk = w / 720f
        val ccx = w / 2f
        val ccy = if (holoMode) h * 0.5f else h * 0.44f
        val rr = min(w, h) * 0.455f
        val r = Random(12345)
        fun rn() = r.nextFloat()

        // ---- the still picture ------------------------------------------------------------------------
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val add = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = addMode }
        val addS = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = addMode; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

        c.drawColor(if (holoMode) Color.BLACK else Color.rgb(5, 7, 14))
        if (!holoMode) {
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Color.rgb(5, 7, 15), Color.rgb(7, 11, 22), Color.rgb(4, 6, 12)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
            // a few faint steps at the lower right
            for (i in 0 until 7) {
                val y = ccy + rr * 0.78f + i * rr * 0.075f
                p.color = Color.argb((26 + 13 * i / 7), 60, 80, 110)
                c.drawRect(w * 0.55f + i * 6 * kk, y, w * 1.05f, y + (4 + i) * kk, p)
            }
            // haze: warm to the upper left, cool to the lower right
            add.shader = RadialGradient(ccx - rr * 0.2f, ccy - rr * 0.15f, rr * 1.9f,
                intArrayOf(Color.argb(56, 255, 120, 40), Color.argb(20, 120, 50, 30), Color.argb(0, 0, 0, 0)), floatArrayOf(0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), add)
            add.shader = RadialGradient(ccx + rr * 0.5f, ccy + rr * 0.5f, rr * 1.6f,
                intArrayOf(Color.argb(50, 40, 170, 255), Color.argb(0, 0, 0, 0)), floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), add)
            add.shader = null
        }

        // ---- interior of the sphere ------------------------------------------------------------------------
        c.save()
        val clip = Path().apply { addCircle(ccx, ccy, rr * 0.97f, Path.Direction.CW) }
        c.clipPath(clip)
        p.shader = LinearGradient(0f, ccy - rr, 0f, ccy + rr,
            intArrayOf(Color.rgb(42, 18, 8), Color.rgb(122, 52, 18), Color.rgb(90, 51, 34), Color.rgb(23, 65, 79), Color.rgb(10, 58, 72), Color.rgb(4, 32, 42)),
            floatArrayOf(0f, 0.36f, 0.5f, 0.6f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(ccx - rr, ccy - rr, ccx + rr, ccy + rr, p)
        p.shader = null

        // dome arcs
        for (i in 0 until 16) {
            val rad = rr * (0.22f + i * 0.05f)
            addS.strokeWidth = (1f + rn() * 2.5f) * kk
            addS.color = Color.argb(((0.18f + 0.25f * rn()) * 255).toInt(), 255, 150 + i * 3, 60)
            c.drawArc(RectF(ccx - rad, ccy + rr * 0.2f - rad, ccx + rad, ccy + rr * 0.2f + rad), 184f, 172f, false, addS)
        }
        // circuit boxes
        for (i in 0 until 140) {
            val x = ccx + (rn() - 0.5f) * rr * 1.7f
            val y = ccy - rr * 0.9f + rn() * rr * 1.05f
            val bw = (8f + rn() * 60f) * kk
            val bh = (4f + rn() * 28f) * kk
            if (hypot(x - ccx, y - ccy) > rr * 0.95f) continue
            addS.strokeWidth = (1f + rn() * 1.5f) * kk
            addS.color = Color.argb(((0.25f + 0.4f * rn()) * 255).toInt(), 255, 170 + (rn() * 60).toInt(), 60 + (rn() * 60).toInt())
            c.drawRect(x, y, x + bw, y + bh, addS)
            if (rn() < 0.3f) {
                add.color = Color.argb(((0.08f + 0.12f * rn()) * 255).toInt(), 255, 170, 70)
                c.drawRect(x, y, x + bw, y + bh, add)
            }
        }
        for (i in 0 until 60) {
            val y = ccy - rr * 0.9f + rn() * rr * 1.1f
            val x = ccx - rr + rn() * rr * 2f
            val len = (30f + rn() * 220f) * kk
            addS.strokeWidth = (1f + rn() * 1.2f) * kk
            addS.color = Color.argb(((0.15f + 0.35f * rn()) * 255).toInt(), 255, 160 + (rn() * 70).toInt(), 80)
            c.drawLine(x, y, x + len, y, addS)
        }
        for (i in 0 until 50) {
            val x = ccx - rr + rn() * rr * 2f
            val y = ccy - rr * 0.9f + rn() * rr
            val len = (20f + rn() * 120f) * kk
            addS.strokeWidth = kk
            addS.color = Color.argb(((0.12f + 0.3f * rn()) * 255).toInt(), 255, 190, 100)
            c.drawLine(x, y, x, y + len, addS)
        }
        // lower turbulence: cyan and orange scribbles
        val scr = Path()
        for (i in 0 until 900) {
            val a = rn() * TAU
            val dist = sqrt(rn()) * rr * 0.95f
            val x = ccx + cos(a) * dist
            val y = ccy + rr * 0.05f + abs(sin(a)) * dist * 0.95f
            if (y < ccy + rr * 0.05f) continue
            val l = (10f + rn() * 50f) * kk
            val an = rn() * TAU
            val cyan = rn() < 0.72f
            addS.strokeWidth = (0.8f + rn() * 1.6f) * kk
            addS.color = if (cyan) Color.argb(((0.14f + 0.4f * rn()) * 255).toInt(), 60 + (rn() * 60).toInt(), 190 + (rn() * 50).toInt(), 255)
            else Color.argb(((0.12f + 0.3f * rn()) * 255).toInt(), 255, 120 + (rn() * 80).toInt(), 60)
            scr.reset()
            scr.moveTo(x, y)
            val an2 = an + 1.5f * (rn() - 0.5f)
            scr.quadTo(x + cos(an) * l, y + sin(an) * l, x + cos(an2) * l * 1.6f, y + sin(an2) * l * 1.6f)
            c.drawPath(scr, addS)
        }
        // soft colour clouds: cool below, warm above, so the two halves melt into each other
        for (i in 0 until 7) {
            val a = rn() * TAU
            val dd = rn() * rr * 0.7f
            val x = ccx + cos(a) * dd
            val y = ccy + rr * 0.25f + abs(sin(a)) * dd * 0.8f
            val q = rr * (0.25f + rn() * 0.25f)
            add.shader = RadialGradient(x, y, q, intArrayOf(Color.argb(56, 60, 200, 255), Color.argb(0, 0, 80, 120)), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, q, add)
        }
        for (i in 0 until 6) {
            val a = rn() * TAU
            val dd = rn() * rr * 0.6f
            val x = ccx + cos(a) * dd
            val y = ccy - rr * 0.3f - abs(sin(a)) * dd * 0.6f
            val q = rr * (0.2f + rn() * 0.25f)
            add.shader = RadialGradient(x, y, q, intArrayOf(Color.argb(66, 255, 150, 60), Color.argb(0, 120, 40, 10)), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, q, add)
        }
        add.shader = null
        for (i in 0 until 500) {
            val x = ccx + (rn() - 0.5f) * 2f * rr
            val y = ccy + (rn() - 0.5f) * 2f * rr
            if (hypot(x - ccx, y - ccy) > rr * 0.95f) continue
            val sz = (1f + rn() * 2.2f) * kk
            add.color = if (y < ccy) Color.argb(((0.15f + 0.4f * rn()) * 255).toInt(), 255, 200, 120) else Color.argb(((0.15f + 0.4f * rn()) * 255).toInt(), 150, 230, 255)
            c.drawRect(x, y, x + sz, y + sz, add)
        }
        // a warm-to-cool glow along the seam
        c.save()
        c.scale(1f, 0.35f, ccx, ccy + rr * 0.1f)
        add.shader = RadialGradient(ccx, ccy + rr * 0.1f, rr * 1.1f, intArrayOf(Color.argb(80, 255, 170, 110), Color.argb(0, 255, 120, 60)), null, Shader.TileMode.CLAMP)
        c.drawCircle(ccx, ccy + rr * 0.1f, rr * 1.1f, add)
        add.shader = null
        c.restore()
        // warm heart
        add.shader = RadialGradient(ccx, ccy - rr * 0.25f, rr * 0.8f,
            intArrayOf(Color.argb(190, 255, 240, 200), Color.argb(150, 255, 190, 100), Color.argb(70, 255, 110, 40), Color.argb(0, 200, 60, 20)),
            floatArrayOf(0f, 0.2f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(ccx - rr, ccy - rr, ccx + rr, ccy + rr, add)
        add.shader = null
        c.restore()

        // glass rim highlights
        sp.strokeWidth = 2f * kk
        sp.color = Color.argb(90, 255, 255, 255)
        c.drawArc(RectF(ccx - rr * 0.985f, ccy - rr * 0.985f, ccx + rr * 0.985f, ccy + rr * 0.985f), 194f, 61f, false, sp)
        sp.color = Color.argb(56, 180, 235, 255)
        c.drawArc(RectF(ccx - rr * 0.985f, ccy - rr * 0.985f, ccx + rr * 0.985f, ccy + rr * 0.985f), 18f, 36f, false, sp)

        // bokeh is drawn live; converging streaks at the bottom are still
        if (!holoMode) {
            for (i in 0 until 9) {
                val x0 = ccx + (rn() - 0.5f) * rr * 1.1f
                val y0 = ccy + rr * 0.95f + rn() * rr * 0.2f
                val x1 = ccx + (rn() - 0.5f) * rr * 0.2f - rr * 0.05f
                addS.strokeWidth = (1f + rn() * 2f) * kk
                addS.color = Color.argb(((0.35f + 0.4f * rn()) * 255).toInt(), 120, 220, 255)
                c.drawLine(x0, y0, x1, h.toFloat(), addS)
            }
        }

        // darken the far corners
        p.shader = RadialGradient(ccx, ccy, hypot(w.toFloat(), h.toFloat()) * 0.55f,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(140, 0, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // ---- the ring: streaking dashes round the rim (turns live) ------------------------------------------
        val s = (rr * 2.9f).toInt()
        val rb = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val rc = Canvas(rb)
        val m = s / 2f
        for (i in 0 until 2800) {
            val a = rn() * TAU
            val warm = if (sin(a - 0.6f) > -0.1f) rn() < 0.85f else rn() < 0.25f
            val rad = rr * (0.93f + rn() * rn() * 0.2f + if (warm) 0f else 0.02f)
            val l = (3f + rn() * rn() * 40f) * kk
            sp.strokeWidth = (0.7f + rn() * 1.6f) * kk
            val x = m + cos(a) * rad
            val y = m + sin(a) * rad
            val tx = -sin(a); val ty = cos(a)
            sp.color = if (warm) Color.argb(((0.3f + 0.7f * rn()) * 255).toInt(), 255, 130 + (rn() * 100).toInt(), 40 + (rn() * 50).toInt())
            else Color.argb(((0.3f + 0.7f * rn()) * 255).toInt(), 80 + (rn() * 100).toInt(), 200 + (rn() * 50).toInt(), 255)
            rc.drawLine(x, y, x + tx * l, y + ty * l, sp)
        }
        // soft glow under the bright arcs
        for (i in 0 until 26) {
            val a0 = rn() * 360f
            val warm = sin(a0 * TAU / 360f - 0.6f) > -0.1f
            sp.strokeWidth = (14f + rn() * 14f) * kk
            sp.color = if (warm) Color.argb(26, 255, 140, 50) else Color.argb(26, 70, 200, 255)
            val q = rr * (0.97f + rn() * 0.06f)
            rc.drawArc(RectF(m - q, m - q, m + q, m + q), a0, 6f + rn() * 30f, false, sp)
        }
        // long thin streaks outside the rim give the feeling of motion
        for (i in 0 until 220) {
            val a = rn() * 360f
            val warm = if (sin(a * TAU / 360f - 0.6f) > -0.1f) rn() < 0.8f else rn() < 0.3f
            val q = rr * (1.04f + rn() * 0.28f)
            sp.strokeWidth = (0.7f + rn() * 1.5f) * kk
            sp.color = if (warm) Color.argb(((0.12f + 0.4f * rn()) * 255).toInt(), 255, 140 + (rn() * 90).toInt(), 60)
            else Color.argb(((0.12f + 0.4f * rn()) * 255).toInt(), 110, 215, 255)
            rc.drawArc(RectF(m - q, m - q, m + q, m + q), a, 1f + rn() * rn() * 9f, false, sp)
        }
        for (i in 0 until 14) {
            val a0 = 180f * (0.75f + rn() * 0.9f) / 1f
            sp.strokeWidth = (2f + rn() * 5f) * kk
            sp.color = Color.argb(((0.35f + 0.4f * rn()) * 255).toInt(), 255, 140 + (rn() * 80).toInt(), 50)
            val q = rr * (0.97f + rn() * 0.07f)
            rc.drawArc(RectF(m - q, m - q, m + q, m + q), a0, 30f + rn() * 70f, false, sp)
        }
        for (i in 0 until 10) {
            sp.strokeWidth = (2f + rn() * 4f) * kk
            sp.color = Color.argb(((0.3f + 0.4f * rn()) * 255).toInt(), 90, 210, 255)
            val q = rr * (0.97f + rn() * 0.07f)
            rc.drawArc(RectF(m - q, m - q, m + q, m + q), rn() * 360f, 30f + rn() * 80f, false, sp)
        }

        still = bmp
        ring = rb
        builtW = w
        builtH = h
        building = false
    }

    // ---------------------------------------------------------------------------------------------------------
    // lightning
    // ---------------------------------------------------------------------------------------------------------

    private fun walk(path: Path, x0: Float, y0: Float, x1: Float, y1: Float, wob: Float, n: Int) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val nx = -dy / len; val ny = dx / len
        var off = 0f
        path.moveTo(x0, y0)
        for (i in 1..n) {
            off += (rnd.nextFloat() - 0.5f) * wob
            off *= 0.82f
            path.lineTo(x0 + dx * i / n + nx * off, y0 + dy * i / n + ny * off)
        }
    }

    private fun rebuildTrunk(h: Float) {
        trunkPath.reset()
        // a long strand from the heart to the bottom of the screen, with branches off it
        val pts = ArrayList<FloatArray>()
        val x0 = cx + R * 0.02f; val y0 = cy - R * 0.02f
        val x1 = cx - R * 0.08f + (rnd.nextFloat() - 0.5f) * R * 0.1f; val y1 = h * 0.97f
        val n = 28
        val dx = x1 - x0; val dy = y1 - y0
        val len = hypot(dx, dy); val nx = -dy / len; val ny = dx / len
        var off = 0f
        trunkPath.moveTo(x0, y0)
        pts.add(floatArrayOf(x0, y0))
        nodeCount = 0
        for (i in 1..n) {
            off += (rnd.nextFloat() - 0.5f) * R * 0.11f
            off *= 0.82f
            val px = x0 + dx * i / n + nx * off; val py = y0 + dy * i / n + ny * off
            trunkPath.lineTo(px, py)
            pts.add(floatArrayOf(px, py))
            if (i % 3 == 0 && nodeCount < 10) { nodes[nodeCount * 2] = px; nodes[nodeCount * 2 + 1] = py; nodeCount++ }
        }
        for (b in 0 until 9) {
            val pt = pts[3 + b * 2]
            val side = if (rnd.nextBoolean()) -1f else 1f
            val ex = pt[0] + side * R * (0.3f + rnd.nextFloat() * 0.7f)
            val ey = pt[1] + R * (0.1f + rnd.nextFloat() * 0.5f) * (if (rnd.nextFloat() < 0.7f) 1f else -1f)
            walk(trunkPath, pt[0], pt[1], ex, ey, R * 0.08f, 10)
        }
    }

    private fun rebuildCurves() {
        curvePath.reset()
        fun curve(x0: Float, y0: Float, x1: Float, y1: Float, qx: Float, qy: Float) {
            curvePath.moveTo(x0, y0)
            var tt = 0.04f
            while (tt <= 1.001f) {
                val u = 1f - tt
                curvePath.lineTo(u * u * x0 + 2 * u * tt * qx + tt * tt * x1 + (rnd.nextFloat() - 0.5f) * 4f * k,
                    u * u * y0 + 2 * u * tt * qy + tt * tt * y1 + (rnd.nextFloat() - 0.5f) * 4f * k)
                tt += 0.04f
            }
        }
        curve(cx - R * 0.95f, cy + R * 0.1f, cx + R * 0.02f, cy, cx - R * 0.5f, cy + R * 0.45f)
        curve(cx - R * 0.15f, cy + R * 0.5f, cx + R * 0.95f, cy + R * 0.28f, cx + R * 0.4f, cy + R * 0.2f)
        curve(cx + R * 0.95f, cy - R * 0.55f, cx + R * 0.1f, cy - R * 0.1f, cx + R * 0.5f, cy - R * 0.5f)
        curve(cx + R * 0.6f, cy + R * 0.85f, cx + R * 1.25f, cy + R * 1.2f, cx + R * 1.0f, cy + R * 0.95f)
    }

    private fun rebuildThin(count: Int) {
        thinPath.reset()
        for (i in 0 until count) {
            val a = rnd.nextFloat() * TAU
            val r0 = R * (0.05f + rnd.nextFloat() * 0.5f)
            val r1 = R * (0.8f + rnd.nextFloat() * 0.9f)
            walk(thinPath, cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * r1, cy + sin(a) * r1, R * 0.06f, 9)
        }
    }

    private fun vein(c: Canvas, path: Path, wd: Float, st: Float) {
        val s = st.coerceIn(0f, 1f)
        strokeAdd.strokeWidth = wd * 5f * k
        strokeAdd.color = Color.argb((41 * s).toInt(), 40, 170, 255)
        c.drawPath(path, strokeAdd)
        strokeAdd.strokeWidth = wd * 2f * k
        strokeAdd.color = Color.argb((128 * s).toInt(), 110, 220, 255)
        c.drawPath(path, strokeAdd)
        strokeAdd.strokeWidth = wd * 0.8f * k
        strokeAdd.color = Color.argb((255 * s).toInt(), 240, 252, 255)
        c.drawPath(path, strokeAdd)
    }

    // ---------------------------------------------------------------------------------------------------------
    // frame
    // ---------------------------------------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
        last = now
        t += dt

        // make the still layers once (or again if the size / holo mode changed), off the UI thread
        if ((still == null || builtW != w || builtH != h) && !building) {
            building = true
            val holoNow = holo
            Thread {
                try { buildLayers(w, h, holoNow) } catch (e: Throwable) { building = false }
            }.start()
        }

        val mode = modeSource?.invoke() ?: IDLE
        val level = (levelSource?.invoke() ?: 0f).coerceIn(0f, 1f)
        val wantEnergy = when (mode) {
            LISTEN -> 0.15f + level * 0.85f
            THINK -> 0.6f + 0.25f * sin(t * 9f)
            SPEAK -> 0.35f + 0.45f * abs(sin(t * 7.3f) * cos(t * 3.1f)) + level * 0.3f
            else -> 0.08f + 0.05f * sin(t * 1.3f)
        }
        if (mode != lastMode) {
            if (lastMode != -1 && mode != IDLE) {
                shockAge[shockNext] = 0f
                shockHue[shockNext] = when (mode) { LISTEN -> 0; SPEAK -> 1; else -> 2 }
                shockNext = (shockNext + 1) % shockAge.size
            }
            lastMode = mode
        }
        val intro = (t / 1.8f).coerceIn(0f, 1f)
        val introE = 1f - (1f - intro) * (1f - intro) * (1f - intro)
        tiltX = lerp(tiltX, tiltTX, 1f - exp(-dt * 4f))
        tiltY = lerp(tiltY, tiltTY, 1f - exp(-dt * 4f))
        energy = lerp(energy, wantEnergy, 1f - exp(-dt * if (wantEnergy > energy) 14f else 5f))
        flare = lerp(flare, if (mode == THINK) 1f else 0f, 1f - exp(-dt * 3f))
        spin += dt * (0.12f + 0.5f * energy + 0.5f * flare)
        energy = (energy + (1f - introE) * 0.6f).coerceAtMost(1f)

        c.drawColor(if (holo) Color.BLACK else Color.rgb(3, 5, 10))
        val st = still
        val rg = ring
        if (st == null || rg == null) { postInvalidateOnAnimation(); return }

        k = w / 720f
        cx = w / 2f + tiltX * 22f * k
        cy = (if (holo) h * 0.5f else h * 0.44f) + tiltY * 22f * k
        R = min(w, h) * 0.455f * (1f + 0.025f * energy) * (0.88f + 0.12f * introE)

        // the still picture lies deepest, so it moves least; it is a little oversized so its edge never shows
        c.save()
        c.scale(1.03f, 1.03f, w / 2f, h / 2f)
        c.translate(tiltX * 8f * k, tiltY * 8f * k)
        c.drawBitmap(st, 0f, 0f, bmpP)
        c.restore()

        // the ring turns slowly
        c.save()
        c.rotate(spin * 6f, cx, cy)
        c.drawBitmap(rg, cx - rg.width / 2f, cy - rg.height / 2f, bmpAdd)
        c.restore()

        // heart pulse and the horizontal beam, swelling with the voice
        val hx = cx
        val hy = cy - R * 0.25f
        val gr = R * (0.45f + 0.35f * energy)
        addP.shader = RadialGradient(hx, hy, gr,
            intArrayOf(Color.argb((50 + 160 * energy).toInt().coerceAtMost(230), 255, 235, 190), Color.argb((20 + 70 * energy).toInt(), 255, 120, 40), Color.argb(0, 255, 90, 20)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(hx, hy, gr, addP)
        val by = cy - R * 0.28f
        val bh = R * (0.018f + 0.02f * energy)
        addP.shader = LinearGradient(cx - R * 1.3f, 0f, cx + R * 1.3f, 0f,
            intArrayOf(Color.argb(0, 255, 160, 80), Color.argb((140 + 80 * energy).toInt().coerceAtMost(255), 255, 200, 130), Color.argb(255, 255, 250, 235), Color.argb((140 + 80 * energy).toInt().coerceAtMost(255), 255, 200, 130), Color.argb(0, 255, 160, 80)),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(cx - R * 1.3f, by - bh, cx + R * 1.3f, by + bh, addP)
        addP.alpha = 90
        c.drawRect(cx - R * 1.3f, by - bh * 4f, cx + R * 1.3f, by + bh * 4f, addP)
        addP.shader = null
        addP.alpha = 255

        // cyan flash while thinking
        if (flare > 0.02f) {
            val fr = R * 1.5f
            addP.shader = RadialGradient(cx, cy + R * 0.2f, fr,
                intArrayOf(Color.argb((80 * flare * (0.6f + 0.4f * sin(t * 11f))).toInt().coerceIn(0, 255), 80, 200, 255), Color.argb(0, 40, 150, 255)),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy + R * 0.2f, fr, addP)
            addP.shader = null
        }

        // lightning veins: re-fork faster the more energy there is
        val speed = 0.7f + energy * 1.6f + flare
        if (t > trunkNext) { rebuildTrunk(h.toFloat()); trunkNext = t + (0.16f + rnd.nextFloat() * 0.2f) / speed }
        if (t > curveNext) { rebuildCurves(); curveNext = t + (0.2f + rnd.nextFloat() * 0.3f) / speed }
        if (t > thinNext) { rebuildThin(if (energy > 0.4f) 36 else 22); thinNext = t + (0.1f + rnd.nextFloat() * 0.18f) / speed }
        val flick = 0.82f + 0.18f * sin(t * 23f + 1f)
        vein(c, thinPath, 1.0f, (0.45f + 0.4f * energy) * flick)
        vein(c, curvePath, 2.1f, (0.8f + 0.2f * energy) * flick)
        vein(c, trunkPath, 2.4f, (0.9f + 0.1f * energy) * flick)

        // glowing knots where the strand brightens
        for (i in 0 until nodeCount) {
            val nx = nodes[i * 2]; val ny = nodes[i * 2 + 1]
            val pulse = 0.6f + 0.4f * sin(t * 6f + i * 1.7f)
            addP.color = Color.argb((46 * pulse).toInt(), 70, 190, 255)
            c.drawCircle(nx, ny, 11f * k, addP)
            addP.color = Color.argb((120 * pulse).toInt(), 150, 230, 255)
            c.drawCircle(nx, ny, 5f * k, addP)
            addP.color = Color.argb((230 * pulse).toInt(), 255, 255, 255)
            c.drawCircle(nx, ny, 2f * k, addP)
        }

        // shockwave rings
        for (i in shockAge.indices) {
            if (shockAge[i] >= 1f) continue
            shockAge[i] += dt / 1.1f
            val f = shockAge[i].coerceIn(0f, 1f)
            val rad = R * (1.0f + f * 1.5f)
            val a = ((1f - f) * (1f - f) * 200f).toInt()
            val (r1, g1, b1) = when (shockHue[i]) { 0 -> Triple(110, 215, 255); 1 -> Triple(255, 160, 70); else -> Triple(255, 245, 225) }
            strokeAdd.strokeWidth = (10f * (1f - f) + 2f) * k
            strokeAdd.color = Color.argb(a / 4, r1, g1, b1)
            c.drawCircle(cx, cy, rad, strokeAdd)
            strokeAdd.strokeWidth = (2.4f * (1f - f) + 0.8f) * k
            strokeAdd.color = Color.argb(a, r1, g1, b1)
            c.drawCircle(cx, cy, rad, strokeAdd)
        }

        // drifting specks of light
        if (!holo) {
            for (i in 0 until 110) {
                val depth = 0.5f + bokeh[i * 5 + 2] / 4f
                val x = bokeh[i * 5] * w + tiltX * 18f * k * depth
                val y = bokeh[i * 5 + 1] * h + tiltY * 18f * k * depth
                val tw = 0.55f + 0.45f * sin(t * 1.4f + bokeh[i * 5 + 4])
                addP.color = if (bokeh[i * 5 + 3] > 0.5f) Color.argb((150 * tw).toInt(), 255, 160, 70) else Color.argb((150 * tw).toInt(), 100, 210, 255)
                c.drawCircle(x, y, bokeh[i * 5 + 2] * k, addP)
            }
        }

        // ignition: the sphere fades up out of the dark when the app opens
        if (intro < 1f) {
            c.drawColor(Color.argb(((1f - introE) * 255).toInt(), 0, 0, 0))
        }

        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            val m = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val a = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (a != null) { m.registerListener(this, a, SensorManager.SENSOR_DELAY_UI); sm = m }
        } catch (e: Exception) { }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try { sm?.unregisterListener(this) } catch (e: Exception) { }
        sm = null
        last = 0L
    }

    override fun onSensorChanged(e: SensorEvent) {
        // phone held upright: x tilts left/right, y changes with forward/back lean
        tiltTX = (-e.values[0] / 9.81f).coerceIn(-0.6f, 0.6f)
        tiltTY = ((e.values[1] - 6f) / 9.81f).coerceIn(-0.6f, 0.6f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
