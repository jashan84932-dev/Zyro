package com.zyro.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Shared permission request for the home screen and the settings page. */
object Perms {
    fun request(a: Activity) {
        a.requestPermissions(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                "android.permission.POST_NOTIFICATIONS",
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                "android.permission.ACTIVITY_RECOGNITION",
                "android.permission.RECEIVE_SMS",
                Manifest.permission.CAMERA,
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_EXTERNAL_STORAGE"
            ),
            1
        )
    }
}

/**
 * A round glass button with a hand-drawn icon (no text anywhere).
 * kinds: GEAR, LIVE (sound bars), POWER, BACK
 */
class IconView(context: Context, private val kind: Int, private val isOn: () -> Boolean = { false }) : View(context) {

    companion object {
        const val GEAR = 0
        const val LIVE = 1
        const val POWER = 2
        const val BACK = 3
        private val BLUE = Color.rgb(47, 140, 255)
        private val ICE = Color.rgb(190, 228, 255)
    }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ICE
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ICE }
    private val path = Path()
    private val box = RectF()
    private var t = 0f
    private var last = 0L
    private var lit = 0f
    private var pressedAmt = 0f

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { pressedAmt = 1f; invalidate() }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { pressedAmt = 0f; invalidate() }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f - 5f * d

        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
        last = now
        t += dt
        val on = isOn()
        lit += ((if (on) 1f else 0f) - lit) * (1f - Math.exp((-dt * 8f).toDouble()).toFloat())

        // soft outer glow when active
        if (lit > 0.02f) {
            val pulse = 0.85f + 0.15f * sin(t * 3.2f)
            glow.shader = RadialGradient(
                cx, cy, r * 1.55f,
                intArrayOf(Color.argb((150 * lit * pulse).toInt(), 47, 140, 255), Color.argb(0, 47, 140, 255)),
                floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r * 1.55f, glow)
        }

        // glass disc
        val base = 26 + (70 * lit).toInt() + (30 * pressedAmt).toInt()
        fill.shader = RadialGradient(
            cx, cy - r * 0.35f, r * 1.25f,
            intArrayOf(Color.argb(base + 40, 70, 150, 255), Color.argb(base, 14, 40, 110), Color.argb(base / 2, 6, 16, 50)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, fill)
        ring.strokeWidth = 1.3f * d
        ring.color = Color.argb(120 + (100 * lit).toInt(), 110, 185, 255)
        c.drawCircle(cx, cy, r, ring)

        // icon
        val s = r * 0.46f
        ink.strokeWidth = maxOf(2f, r * 0.085f)
        ink.color = if (on) Color.WHITE else ICE
        solid.color = ink.color
        when (kind) {
            GEAR -> {
                c.save()
                c.translate(cx, cy)
                c.rotate(t * 8f * lit)
                for (i in 0 until 8) {
                    c.save()
                    c.rotate(i * 45f)
                    box.set(-s * 0.16f, -s * 1.12f, s * 0.16f, -s * 0.78f)
                    c.drawRoundRect(box, 2f * d, 2f * d, solid)
                    c.restore()
                }
                ink.style = Paint.Style.STROKE
                c.drawCircle(0f, 0f, s * 0.80f, ink)
                c.drawCircle(0f, 0f, s * 0.30f, ink)
                c.restore()
            }
            LIVE -> {
                val hs = floatArrayOf(0.45f, 0.95f, 1.5f, 0.95f, 0.45f)
                val gap = s * 0.52f
                for (i in 0 until 5) {
                    val wob = if (on) 0.55f + 0.45f * abs(sin(t * (3.1f + i * 0.9f) + i)) else 1f
                    val hh = hs[i] * s * wob
                    val x = cx + (i - 2) * gap
                    c.drawLine(x, cy - hh, x, cy + hh, ink)
                }
            }
            POWER -> {
                box.set(cx - s, cy - s, cx + s, cy + s)
                c.drawArc(box, -55f, 290f, false, ink)
                c.drawLine(cx, cy - s * 1.15f, cx, cy - s * 0.1f, ink)
            }
            BACK -> {
                path.reset()
                path.moveTo(cx + s * 0.35f, cy - s * 0.9f)
                path.lineTo(cx - s * 0.5f, cy)
                path.lineTo(cx + s * 0.35f, cy + s * 0.9f)
                c.drawPath(path, ink)
            }
        }
        // keep animating (cheap: only a few small views)
        if (on || lit > 0.02f) postInvalidateOnAnimation()
    }
}
