package com.zyro.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zyro's face: a glowing blue "neural core".
 * A rotating geodesic lattice sphere with glowing nodes sits in the middle, a smaller lattice turns inside it,
 * tilted orbit rings circle it with bright beads and little floating tags, and a field of sparks drifts in depth
 * around everything. Spokes around the core jump with the voice.
 *   IDLE   - calm, slow turning
 *   LISTEN - the spokes follow how loud you are
 *   THINK  - everything spins fast and the core flares
 *   SPEAK  - spokes dance and the core pulses
 */
class OrbView(context: Context) : View(context) {

    companion object {
        const val IDLE = 0
        const val LISTEN = 1
        const val THINK = 2
        const val SPEAK = 3

        private const val TAU = (2.0 * PI).toFloat()
        private const val SPARKS = 240
        private const val SPOKES = 84
        private const val RING_PTS = 72
        private const val F = 3.2f // perspective distance
    }

    var modeSource: (() -> Int)? = null
    var levelSource: (() -> Float)? = null

    private val d = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val path = Path()
    private val dst = RectF()
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }

    /** One soft blue glow, made once and stamped everywhere (much cheaper than a new gradient per dot). */
    private val sprite: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
        val cv = Canvas(it)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(32f, 32f, 32f, intArrayOf(Color.argb(255, 170, 225, 255), Color.argb(110, 70, 170, 255), Color.argb(0, 30, 110, 255)), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(32f, 32f, 32f, p)
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, alpha: Int) {
        spritePaint.alpha = alpha.coerceIn(0, 255)
        dst.set(x - r, y - r, x + r, y + r)
        c.drawBitmap(sprite, null, dst, spritePaint)
    }

    // ---- geometry ----------------------------------------------------------------------------------

    /** 42-vertex geodesic sphere (icosahedron + edge midpoints) and its edges. */
    private val gv: Array<FloatArray>
    private val ge: IntArray

    /** Plain 12-vertex icosahedron for the inner lattice. */
    private val iv: Array<FloatArray>
    private val ie: IntArray

    private class Spark(val a: Float, val rad: Float, val y: Float, val size: Float, val phase: Float, val speed: Float, val warm: Float)
    private val sparks: Array<Spark>

    private class Ring(val tiltX: Float, val tiltZ: Float, val radius: Float, val speed: Float, val phase: Float, val beads: Int, val width: Float)
    private val rings = arrayOf(
        Ring(1.15f, 0.20f, 1.42f, 0.55f, 0.0f, 2, 1.0f),
        Ring(0.45f, 1.05f, 1.62f, -0.38f, 1.7f, 3, 0.8f),
        Ring(1.45f, -0.70f, 1.30f, 0.80f, 3.1f, 1, 1.2f),
        Ring(0.15f, -0.35f, 1.85f, -0.22f, 4.4f, 2, 0.6f),
        Ring(0.90f, 0.95f, 2.10f, 0.15f, 0.9f, 1, 0.5f)
    )

    init {
        // icosahedron
        val phi = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
        val base = arrayOf(
            floatArrayOf(0f, 1f, phi), floatArrayOf(0f, -1f, phi), floatArrayOf(0f, 1f, -phi), floatArrayOf(0f, -1f, -phi),
            floatArrayOf(1f, phi, 0f), floatArrayOf(-1f, phi, 0f), floatArrayOf(1f, -phi, 0f), floatArrayOf(-1f, -phi, 0f),
            floatArrayOf(phi, 0f, 1f), floatArrayOf(-phi, 0f, 1f), floatArrayOf(phi, 0f, -1f), floatArrayOf(-phi, 0f, -1f)
        )
        fun norm(v: FloatArray): FloatArray {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }
        fun dist(a: FloatArray, b: FloatArray): Float {
            val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
            return sqrt(x * x + y * y + z * z)
        }
        val ico = Array(12) { norm(base[it]) }
        // edges of the plain icosahedron = the shortest distances
        var min0 = Float.MAX_VALUE
        for (i in 0 until 12) for (j in i + 1 until 12) min0 = min(min0, dist(ico[i], ico[j]))
        val icoEdges = ArrayList<Int>()
        for (i in 0 until 12) for (j in i + 1 until 12) if (dist(ico[i], ico[j]) < min0 * 1.1f) { icoEdges.add(i); icoEdges.add(j) }
        iv = ico
        ie = icoEdges.toIntArray()

        val verts = ArrayList<FloatArray>()
        for (v in ico) verts.add(v)
        for (k in 0 until icoEdges.size step 2) {
            val a = ico[icoEdges[k]]
            val b = ico[icoEdges[k + 1]]
            verts.add(norm(floatArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2)))
        }
        gv = verts.toTypedArray()
        var m = Float.MAX_VALUE
        for (i in gv.indices) for (j in i + 1 until gv.size) m = min(m, dist(gv[i], gv[j]))
        val edges = ArrayList<Int>()
        for (i in gv.indices) for (j in i + 1 until gv.size) if (dist(gv[i], gv[j]) < m * 1.22f) { edges.add(i); edges.add(j) }
        ge = edges.toIntArray()

        val rnd = Random(7)
        sparks = Array(SPARKS) {
            Spark(
                a = rnd.nextFloat() * TAU,
                rad = 0.12f + rnd.nextFloat() * 1.25f,
                y = (rnd.nextFloat() - 0.5f) * 2.4f,
                size = 0.5f + rnd.nextFloat() * rnd.nextFloat() * 3.2f,
                phase = rnd.nextFloat() * TAU,
                speed = 0.5f + rnd.nextFloat() * 1.0f,
                warm = rnd.nextFloat()
            )
        }
    }

    // ---- animation state ----------------------------------------------------------------------------

    private var t = 0f
    private var last = 0L
    private var energy = 0.12f
    private var speed = 1f
    private var spin = 0f      // accumulated rotation, so speed changes never make it jump
    private var flare = 0f
    private val spokeLevel = FloatArray(SPOKES)
    private val tmp = FloatArray(3)

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    // ---- projection helpers -------------------------------------------------------------------------

    private var cx = 0f
    private var cy = 0f
    private var R = 1f

    private var px = 0f
    private var py = 0f
    private var pz = 0f
    private var ps = 1f

    /** Rotate (x,y,z) by yaw (around y) then pitch (around x), then project to the screen. */
    private fun project(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, scale: Float) {
        val cyw = cos(yaw); val syw = sin(yaw)
        val x1 = x * cyw + z * syw
        val z1 = -x * syw + z * cyw
        val cp = cos(pitch); val sp = sin(pitch)
        val y2 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp
        ps = F / (F - z2 * 0.55f)
        px = cx + x1 * scale * ps
        py = cy + y2 * scale * ps
        pz = z2
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
        last = now
        t += dt

        val mode = modeSource?.invoke() ?: IDLE
        val level = (levelSource?.invoke() ?: 0f).coerceIn(0f, 1f)
        val wantSpeed = when (mode) { LISTEN -> 1.3f; THINK -> 3.4f; SPEAK -> 1.7f; else -> 1f }
        val wantEnergy = when (mode) {
            LISTEN -> 0.18f + level * 0.8f
            THINK -> 0.55f + 0.2f * sin(t * 9f)
            SPEAK -> 0.35f + 0.45f * abs(sin(t * 7.3f) * cos(t * 3.1f)) + level * 0.3f
            else -> 0.10f + 0.04f * sin(t * 1.3f)
        }
        energy = lerp(energy, wantEnergy, 1f - exp(-dt * if (wantEnergy > energy) 14f else 5f))
        speed = lerp(speed, wantSpeed, 1f - exp(-dt * 3f))
        spin += dt * speed
        flare = lerp(flare, if (mode == THINK) 1f else 0f, 1f - exp(-dt * 3f))

        cx = w / 2f
        cy = h * 0.45f
        R = min(w, h) * 0.205f * (1f + 0.05f * energy)

        // ---- background -------------------------------------------------------------------------
        c.drawColor(Color.rgb(2, 5, 14))
        glowP.shader = RadialGradient(
            cx, cy, max(w, h) * 0.62f,
            intArrayOf(Color.argb(95, 8, 55, 150), Color.argb(40, 5, 25, 90), Color.argb(0, 2, 5, 14)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, glowP)
        glowP.shader = null

        // ---- spark field (drifting in depth) ------------------------------------------------------
        val fieldYaw = spin * 0.10f
        for (s in sparks) {
            val ang = s.a + fieldYaw * s.speed
            val x = cos(ang) * s.rad
            val z = sin(ang) * s.rad
            val y = s.y + sin(t * 0.4f * s.speed + s.phase) * 0.04f
            val persp = F / (F - z * 0.9f)
            val sx = cx + x * w * 0.62f * persp
            val sy = cy + y * h * 0.30f * persp
            if (sx < -20f || sx > w + 20f || sy < -20f || sy > h + 20f) continue
            val tw = 0.55f + 0.45f * sin(t * (1.5f + s.speed * 2f) + s.phase)
            val depth = (0.45f + 0.55f * (z + 1.3f) / 2.6f).coerceIn(0.2f, 1f)
            val a = (255 * tw * depth * (0.35f + 0.65f * (0.5f + energy))).toInt().coerceIn(0, 255)
            val r = s.size * d * depth * (0.8f + 0.5f * energy)
            val red = lerp(40f, 190f, s.warm * s.warm).toInt()
            val grn = lerp(150f, 235f, s.warm).toInt()
            dot.color = Color.argb(a, red, grn, 255)
            c.drawCircle(sx, sy, max(0.6f * d, r), dot)
            if (s.size > 2.4f) {
                dot.color = Color.argb(a / 4, 60, 160, 255)
                c.drawCircle(sx, sy, r * 3.2f, dot)
            }
        }

        // ---- core glow behind the lattice ------------------------------------------------------------
        val coreR = R * (1.35f + 0.55f * energy + 0.4f * flare)
        glowP.shader = RadialGradient(
            cx, cy, coreR,
            intArrayOf(
                Color.argb(255, 235, 250, 255),
                Color.argb((200 + 55 * energy).toInt().coerceAtMost(255), 90, 190, 255),
                Color.argb(110, 20, 100, 255),
                Color.argb(0, 5, 40, 160)
            ),
            floatArrayOf(0f, 0.22f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, coreR, glowP)
        glowP.shader = null

        // vertical light beam through the core, like a lens streak
        val beamA = (40 + 90 * energy).toInt().coerceIn(0, 255)
        glowP.shader = RadialGradient(cx, cy, R * 3.2f, intArrayOf(Color.argb(beamA, 90, 190, 255), Color.argb(0, 20, 90, 255)), null, Shader.TileMode.CLAMP)
        c.save()
        c.scale(0.045f, 1f, cx, cy)
        c.drawCircle(cx, cy, R * 3.2f, glowP)
        c.restore()
        c.save()
        c.scale(1f, 0.04f, cx, cy)
        c.drawCircle(cx, cy, R * 2.6f, glowP)
        c.restore()
        glowP.shader = null

        val yaw = spin * 0.42f
        val pitch = 0.42f + 0.18f * sin(t * 0.23f)

        // ---- orbit rings ---------------------------------------------------------------------------------
        for ((ri, rg) in rings.withIndex()) {
            val rr = R * rg.radius
            val rotA = spin * rg.speed * 0.9f
            path.reset()
            for (k in 0..RING_PTS) {
                val a = k.toFloat() / RING_PTS * TAU
                circlePoint(a, rg.tiltX, rg.tiltZ, rotA, tmp)
                project(tmp[0], tmp[1], tmp[2], yaw * 0.3f, pitch * 0.6f, rr)
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            line.strokeWidth = 5f * d * rg.width
            line.color = Color.argb((30 + 45 * energy).toInt(), 40, 150, 255)
            c.drawPath(path, line)
            line.strokeWidth = 1.2f * d * rg.width
            line.color = Color.argb(185, 110, 205, 255)
            c.drawPath(path, line)

            // bright beads travelling on the ring
            for (b in 0 until rg.beads) {
                val a = rg.phase + b * TAU / rg.beads + t * rg.speed * (1.2f + energy)
                circlePoint(a, rg.tiltX, rg.tiltZ, rotA, tmp)
                project(tmp[0], tmp[1], tmp[2], yaw * 0.3f, pitch * 0.6f, rr)
                val depth = (0.7f + 0.3f * pz).coerceIn(0.4f, 1f)
                glow(c, px, py, 14f * d * depth, 255)
                dot.color = Color.WHITE
                c.drawCircle(px, py, 2.2f * d * depth, dot)
            }

            // a small floating tag on every other ring
            if (ri % 2 == 0) {
                val a = rg.phase + 1.1f + t * rg.speed * 0.35f
                circlePoint(a, rg.tiltX, rg.tiltZ, rotA, tmp)
                project(tmp[0], tmp[1], tmp[2], yaw * 0.3f, pitch * 0.6f, rr)
                tag(c, px, py, if (px > cx) 1 else -1, 0.5f + 0.5f * sin(t * 1.7f + ri))
            }
        }

        // ---- the lattice sphere ----------------------------------------------------------------------------
        drawLattice(c, gv, ge, yaw, pitch, R, 1f, energy, 1f)
        drawLattice(c, iv, ie, -yaw * 1.6f, pitch + 0.3f, R * 0.52f, 0.9f, energy, 0.8f)

        // hot centre
        glowP.shader = RadialGradient(cx, cy, R * 0.55f, intArrayOf(Color.argb(255, 255, 255, 255), Color.argb(150, 150, 215, 255), Color.argb(0, 60, 160, 255)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * (0.5f + 0.16f * energy + 0.12f * sin(t * 3f)), glowP)
        glowP.shader = null

        // ---- voice spokes ------------------------------------------------------------------------------------
        for (i in 0 until SPOKES) {
            val a = i.toFloat() / SPOKES * TAU
            val n = 0.5f + 0.5f * sin(a * 7f + t * 6f) * cos(a * 3f - t * 4.3f)
            val target = (0.02f + energy * (0.12f + 0.5f * n)) * R
            spokeLevel[i] = lerp(spokeLevel[i], target, 1f - exp(-dt * if (target > spokeLevel[i]) 22f else 8f))
            val r0 = R * 1.12f
            val r1 = r0 + spokeLevel[i]
            val ca = cos(a); val sa = sin(a)
            line.strokeWidth = 1.6f * d
            line.color = Color.argb((110 + 130 * energy).toInt().coerceIn(0, 255), 120, 210, 255)
            c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * r1, cy + sa * r1, line)
        }

        postInvalidateOnAnimation()
    }

    private fun circlePoint(a: Float, tiltX: Float, tiltZ: Float, rot: Float, out: FloatArray) {
        // a circle in the xz plane, tilted around x and z, and turned by rot
        val ca = cos(a + rot)
        val sa = sin(a + rot)
        var x = ca
        var y = 0f
        var z = sa
        val cX = cos(tiltX); val sX = sin(tiltX)
        val y1 = y * cX - z * sX
        val z1 = y * sX + z * cX
        y = y1; z = z1
        val cZ = cos(tiltZ); val sZ = sin(tiltZ)
        val x2 = x * cZ - y * sZ
        val y2 = x * sZ + y * cZ
        out[0] = x2; out[1] = y2; out[2] = z
    }

    private fun drawLattice(c: Canvas, v: Array<FloatArray>, e: IntArray, yaw: Float, pitch: Float, radius: Float, alphaScale: Float, en: Float, width: Float) {
        val n = v.size
        val sx = FloatArray(n)
        val sy = FloatArray(n)
        val sz = FloatArray(n)
        val ss = FloatArray(n)
        for (i in 0 until n) {
            // gentle breathing so the lattice feels alive
            val br = 1f + 0.04f * sin(t * 1.9f + i)
            project(v[i][0] * br, v[i][1] * br, v[i][2] * br, yaw, pitch, radius)
            sx[i] = px; sy[i] = py; sz[i] = pz; ss[i] = ps
        }
        var k = 0
        while (k < e.size) {
            val a = e[k]
            val b = e[k + 1]
            val depth = ((sz[a] + sz[b]) / 2f + 1f) / 2f // 0 back .. 1 front
            val al = ((40 + 190 * depth) * alphaScale * (0.75f + 0.5f * en)).toInt().coerceIn(0, 255)
            line.strokeWidth = (0.8f + 0.9f * depth) * d * width
            line.color = Color.argb(al, 120, 205, 255)
            c.drawLine(sx[a], sy[a], sx[b], sy[b], line)
            k += 2
        }
        for (i in 0 until n) {
            val depth = (sz[i] + 1f) / 2f
            val r = (1.6f + 2.4f * depth) * d * ss[i] * width
            val a = ((70 + 185 * depth) * alphaScale).toInt().coerceIn(0, 255)
            glow(c, sx[i], sy[i], r * 4f, a)
            dot.color = Color.argb(a, 235, 250, 255)
            c.drawCircle(sx[i], sy[i], r, dot)
        }
    }

    /** A tiny HUD tag: a little frame with two bars inside, joined to its point by a thin line. */
    private fun tag(c: Canvas, x: Float, y: Float, side: Int, blink: Float) {
        val tw = 40f * d
        val th = 13f * d
        val ox = x + side * 16f * d
        val oy = y - 14f * d
        val left = if (side > 0) ox else ox - tw
        line.strokeWidth = 1f * d
        line.color = Color.argb(150, 120, 205, 255)
        c.drawLine(x, y, ox, oy + th / 2f, line)
        c.drawRect(left, oy, left + tw, oy + th, line)
        dot.color = Color.argb((120 + 120 * blink).toInt(), 190, 235, 255)
        c.drawRect(left + 4f * d, oy + 3.2f * d, left + tw * 0.62f, oy + 5.2f * d, dot)
        c.drawRect(left + 4f * d, oy + 7.4f * d, left + tw * 0.40f, oy + 9.4f * d, dot)
    }
}
