package com.zyro.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zyro's face: a slowly turning golden hologram orb made of glowing points, a web of thin
 * lines, three tilted rings and a bright core. It reacts to what Zyro is doing:
 *   IDLE    – slow and calm
 *   LISTEN  – grows and brightens with your voice loudness
 *   THINK   – spins fast
 *   SPEAK   – ripples outward in waves
 */
class OrbView(context: Context) : View(context) {

    companion object {
        const val IDLE = 0
        const val LISTEN = 1
        const val THINK = 2
        const val SPEAK = 3

        private const val N = 240            // points on the sphere
        private const val RING_STEPS = 96
        private const val CAMERA = 5f        // perspective distance
        private val GOLD = Color.rgb(255, 181, 46)
        private val GOLD_BRIGHT = Color.rgb(255, 214, 107)
        private val GOLD_DEEP = Color.rgb(200, 112, 12)
    }

    /** Asked every frame, so the orb always follows the assistant. */
    var modeSource: (() -> Int)? = null
    var levelSource: (() -> Float)? = null

    private val d = resources.displayMetrics.density

    // unit-sphere points
    private val px = FloatArray(N)
    private val py = FloatArray(N)
    private val pz = FloatArray(N)
    // projected points
    private val sx = FloatArray(N)
    private val sy = FloatArray(N)
    private val sd = FloatArray(N) // depth 0 (back) .. 1 (front)

    private var edges = IntArray(0)   // pairs a*N+b
    private var frontLines = FloatArray(0)
    private var backLines = FloatArray(0)

    private val ringRad = floatArrayOf(1.18f, 1.34f, 1.03f)
    private val ringTiltX = floatArrayOf(1.25f, 0.55f, -0.95f)
    private val ringTiltZ = floatArrayOf(0.30f, -0.85f, 1.15f)
    private val ringSpeed = floatArrayOf(0.55f, -0.38f, 0.85f)
    private val ringPath = Path()

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * d
        color = GOLD
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * d
        color = GOLD_BRIGHT
    }
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f * d
        color = GOLD
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coreRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
        color = GOLD_BRIGHT
    }

    // animation state
    private var last = 0L
    private var t = 0f
    private var ay = 0f
    private var spin = 0.25f
    private var energy = 0.25f
    private var lvl = 0f

    init {
        val golden = (PI * (3.0 - sqrt(5.0))).toFloat()
        for (i in 0 until N) {
            val y = 1f - 2f * (i + 0.5f) / N
            val r = sqrt(max(0f, 1f - y * y))
            val th = golden * i
            px[i] = cos(th) * r
            py[i] = y
            pz[i] = sin(th) * r
        }
        // connect every point to its 3 nearest neighbours
        val set = HashSet<Int>()
        val dist = FloatArray(N)
        for (i in 0 until N) {
            for (j in 0 until N) {
                val dx = px[i] - px[j]
                val dy = py[i] - py[j]
                val dz = pz[i] - pz[j]
                dist[j] = if (i == j) 1e9f else dx * dx + dy * dy + dz * dz
            }
            repeat(3) {
                var best = 0
                for (j in 1 until N) if (dist[j] < dist[best]) best = j
                dist[best] = 1e9f
                set.add(min(i, best) * N + max(i, best))
            }
        }
        edges = set.toIntArray()
        frontLines = FloatArray(edges.size * 4)
        backLines = FloatArray(edges.size * 4)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // gradients are built around (0,0) with radius 100 and scaled when drawn
        glowPaint.shader = RadialGradient(
            0f, 0f, 100f,
            intArrayOf(Color.argb(120, 255, 176, 40), Color.argb(40, 255, 140, 10), Color.argb(0, 255, 120, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        corePaint.shader = RadialGradient(
            0f, 0f, 100f,
            intArrayOf(Color.argb(255, 255, 246, 205), Color.argb(200, 255, 200, 80), Color.argb(0, 255, 150, 0)),
            floatArrayOf(0f, 0.30f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // ---- time and smoothing ----
        val now = System.nanoTime()
        var dt = if (last == 0L) 0.016f else (now - last) / 1e9f
        last = now
        dt = dt.coerceIn(0.001f, 0.05f)
        t += dt

        val mode = modeSource?.invoke() ?: IDLE
        val rawLevel = levelSource?.invoke() ?: 0f
        lvl += (rawLevel - lvl) * (1f - exp(-dt * 12f))

        val targetSpin: Float
        val targetEnergy: Float
        when (mode) {
            LISTEN -> { targetSpin = 0.55f; targetEnergy = 0.45f + 0.55f * lvl }
            THINK -> { targetSpin = 1.9f; targetEnergy = 0.9f }
            SPEAK -> {
                targetSpin = 0.7f
                targetEnergy = 0.62f + 0.3f * abs(sin(t * 5.3f)) * abs(sin(t * 2.1f + 1f))
            }
            else -> { targetSpin = 0.22f; targetEnergy = 0.22f }
        }
        val k = 1f - exp(-dt * 3f)
        spin += (targetSpin - spin) * k
        energy += (targetEnergy - energy) * k
        ay += spin * dt

        val cx = w / 2f
        val cy = h / 2f
        val R = min(w, h) * 0.26f * (1f + 0.10f * energy)

        // ---- background ----
        c.drawColor(Color.rgb(5, 8, 12))

        // ---- big soft glow behind ----
        c.save()
        c.translate(cx, cy)
        val gs = R * 2.3f / 100f
        c.scale(gs, gs)
        glowPaint.alpha = (110 + 145 * energy).toInt().coerceIn(0, 255)
        c.drawCircle(0f, 0f, 100f, glowPaint)
        c.restore()

        // ---- rotate + project sphere points ----
        val cyaw = cos(ay)
        val syaw = sin(ay)
        val tiltX = 0.38f + 0.12f * sin(t * 0.35f)
        val ctx = cos(tiltX)
        val stx = sin(tiltX)
        val wob = 0.012f + 0.075f * energy
        for (i in 0 until N) {
            val phase = if (mode == SPEAK) t * 7f - py[i] * 8f else t * 3f + py[i] * 6f + px[i] * 3f
            val s = 1f + wob * sin(phase)
            val x = px[i] * s
            val y = py[i] * s
            val z = pz[i] * s
            val x1 = x * cyaw + z * syaw
            val z1 = -x * syaw + z * cyaw
            val y2 = y * ctx - z1 * stx
            val z2 = y * stx + z1 * ctx
            val persp = CAMERA / (CAMERA - z2)
            sx[i] = cx + x1 * R * persp
            sy[i] = cy + y2 * R * persp
            sd[i] = ((z2 + 1.1f) / 2.2f).coerceIn(0f, 1f)
        }

        // ---- spokes from the core to a few points ----
        spokePaint.alpha = (40 + 60 * energy).toInt()
        var i = 0
        while (i < N) {
            if (sd[i] > 0.35f) c.drawLine(cx, cy, sx[i], sy[i], spokePaint)
            i += 16
        }

        // ---- wire web (front lines brighter than back lines) ----
        var nf = 0
        var nb = 0
        for (e in edges) {
            val a = e / N
            val b = e % N
            if ((sd[a] + sd[b]) * 0.5f > 0.5f) {
                frontLines[nf++] = sx[a]; frontLines[nf++] = sy[a]; frontLines[nf++] = sx[b]; frontLines[nf++] = sy[b]
            } else {
                backLines[nb++] = sx[a]; backLines[nb++] = sy[a]; backLines[nb++] = sx[b]; backLines[nb++] = sy[b]
            }
        }
        linePaint.color = GOLD_DEEP
        linePaint.alpha = (60 + 40 * energy).toInt()
        c.drawLines(backLines, 0, nb, linePaint)
        linePaint.color = GOLD
        linePaint.alpha = (110 + 80 * energy).toInt().coerceAtMost(255)
        c.drawLines(frontLines, 0, nf, linePaint)

        // ---- glowing points ----
        for (p in 0 until N) {
            val dn = sd[p]
            val r = (0.7f + 1.7f * dn) * d * (0.9f + 0.4f * energy)
            if (dn > 0.55f) {
                dotPaint.color = GOLD
                dotPaint.alpha = (18 + 28 * energy).toInt()
                c.drawCircle(sx[p], sy[p], r * 2.3f, dotPaint)
            }
            dotPaint.color = if (dn > 0.6f) GOLD_BRIGHT else GOLD
            dotPaint.alpha = (50 + 205 * dn).toInt()
            c.drawCircle(sx[p], sy[p], r, dotPaint)
        }

        // ---- three tilted rings with a travelling spark ----
        ringPaint.alpha = (110 + 100 * energy).toInt().coerceAtMost(255)
        for (g in 0 until 3) {
            val rr = ringRad[g] * (1f + 0.03f * energy * sin(t * 4f + g))
            val ca = cos(ringTiltX[g]); val sa = sin(ringTiltX[g])
            val cb = cos(ringTiltZ[g]); val sb = sin(ringTiltZ[g])
            val yaw = ay * ringSpeed[g] + g * 1.7f
            val cyw = cos(yaw); val syw = sin(yaw)
            ringPath.reset()
            var sparkX = 0f
            var sparkY = 0f
            val sparkAt = ((t * (0.35f + 0.1f * g) * (1f + energy)) % 1f) * RING_STEPS
            for (s in 0..RING_STEPS) {
                val phi = (s.toFloat() / RING_STEPS) * (2f * PI.toFloat())
                val x0 = cos(phi) * rr
                val y0 = sin(phi) * rr
                // tilt about X, then about Z, then spin about Y
                val y1 = y0 * ca
                val z1 = y0 * sa
                val x2 = x0 * cb - y1 * sb
                val y2 = x0 * sb + y1 * cb
                val x3 = x2 * cyw + z1 * syw
                val z3 = -x2 * syw + z1 * cyw
                val y4 = y2 * ctx - z3 * stx
                val z4 = y2 * stx + z3 * ctx
                val persp = CAMERA / (CAMERA - z4)
                val X = cx + x3 * R * persp
                val Y = cy + y4 * R * persp
                if (s == 0) ringPath.moveTo(X, Y) else ringPath.lineTo(X, Y)
                if (s == sparkAt.toInt()) { sparkX = X; sparkY = Y }
            }
            c.drawPath(ringPath, ringPaint)
            dotPaint.color = Color.rgb(255, 246, 205)
            dotPaint.alpha = 255
            c.drawCircle(sparkX, sparkY, 2.6f * d, dotPaint)
            dotPaint.color = GOLD
            dotPaint.alpha = 70
            c.drawCircle(sparkX, sparkY, 7f * d, dotPaint)
        }

        // ---- bright core ----
        val pulse = 1f + 0.22f * energy * sin(t * 6f)
        c.save()
        c.translate(cx, cy)
        val cs = R * 0.55f * pulse / 100f
        c.scale(cs, cs)
        c.drawCircle(0f, 0f, 100f, corePaint)
        c.restore()
        coreRingPaint.alpha = (140 + 90 * energy).toInt().coerceAtMost(255)
        c.drawCircle(cx, cy, R * 0.16f * pulse, coreRingPaint)

        postInvalidateOnAnimation()
    }
}
