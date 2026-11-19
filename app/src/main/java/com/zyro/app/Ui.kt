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
        private val ICE = Color.rgb(223, 250, 255)
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
    private var ripple = 1f

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { pressedAmt = 1f; ripple = 0f; try { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) } catch (x: Exception) { }; invalidate() }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { pressedAmt = 0f; invalidate() }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        // the same size as the design preview: the circle fills the whole 48 dp / 96 dp button
        val r = minOf(w, h) / 2f - 1.5f * d

        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
        last = now
        t += dt
        val on = isOn()
        lit += ((if (on) 1f else 0f) - lit) * (1f - Math.exp((-dt * 8f).toDouble()).toFloat())

        // the whole button dips slightly while pressed
        c.save()
        val sc = 1f - 0.07f * pressedAmt
        c.scale(sc, sc, cx, cy)

        // tap ripple: a ring that leaves the button
        if (ripple < 1f) {
            ripple = minOf(1f, ripple + dt / 0.55f)
            ring.strokeWidth = (3f * (1f - ripple) + 0.8f) * d
            ring.color = Color.argb(((1f - ripple) * (1f - ripple) * 210).toInt(), 90, 220, 255)
            c.drawCircle(cx, cy, r * (1f + ripple * 0.75f), ring)
        }

        // soft outer glow when active
        if (lit > 0.02f) {
            val pulse = 0.85f + 0.15f * sin(t * 3.2f)
            glow.shader = RadialGradient(
                cx, cy, r * 1.55f,
                intArrayOf(Color.argb((150 * lit * pulse).toInt(), 40, 190, 240), Color.argb(0, 30, 170, 230)),
                floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r * 1.55f, glow)
        }

        // glass disc
        val boost = (60 * lit).toInt() + (40 * pressedAmt).toInt()
        if (kind == GEAR) {
            // flat tinted glass, like the preview
            fill.shader = null
            fill.color = Color.argb(31 + boost, 40, 160, 200)
        } else {
            fill.shader = RadialGradient(
                cx, cy - r * 0.4f, r * 1.25f,
                intArrayOf(Color.argb(102 + boost, 60, 200, 240), Color.argb(128, 5, 25, 35)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawCircle(cx, cy, r, fill)
        ring.strokeWidth = 1.3f * d
        ring.color = Color.argb((if (kind == GEAR) 153 else 204) + (50 * lit).toInt().coerceAtMost(50), 90, 220, 255)
        c.drawCircle(cx, cy, r, ring)

        // a bright spark circles the rim while active
        if (lit > 0.02f) {
            ring.strokeWidth = 2.6f * d
            ring.strokeCap = Paint.Cap.ROUND
            box.set(cx - r, cy - r, cx + r, cy + r)
            val a0 = (t * 160f) % 360f
            ring.color = Color.argb((235 * lit).toInt(), 225, 250, 255)
            c.drawArc(box, a0, 34f, false, ring)
            ring.color = Color.argb((90 * lit).toInt(), 110, 220, 255)
            c.drawArc(box, a0 - 40f, 40f, false, ring)
            ring.strokeCap = Paint.Cap.BUTT
        }

        // icon
        val s = r * 0.46f
        ink.strokeWidth = maxOf(2f, r * 0.085f)
        val gu = r / 24f  // gear: the preview's 24 unit icon inside the 48 dp circle
        val lu = r / 48f  // live bars: the preview's dp sizes inside the 96 dp circle
        ink.color = if (on) Color.WHITE else ICE
        solid.color = ink.color
        when (kind) {
            GEAR -> {
                c.save()
                c.translate(cx, cy)
                c.rotate(t * 8f * lit)
                ink.strokeWidth = 2f * gu
                ink.style = Paint.Style.STROKE
                for (i in 0 until 8) {
                    c.save()
                    c.rotate(i * 45f)
                    c.drawLine(0f, -6.5f * gu, 0f, -9.5f * gu, ink)
                    c.restore()
                }
                c.drawCircle(0f, 0f, 3.2f * gu, ink)
                c.restore()
            }
            LIVE -> {
                val hs = floatArrayOf(10f, 18f, 25f, 18f, 10f)  // half heights of the preview's 20, 36, 50, 36, 20 dp bars
                ink.strokeWidth = 5f * lu
                for (i in 0 until 5) {
                    val wob = if (on) 0.55f + 0.45f * abs(sin(t * (3.1f + i * 0.9f) + i)) else 1f
                    val hh = (hs[i] * lu - 2.5f * lu) * wob  // round caps add the rest
                    val x = cx + (i - 2) * 11f * lu
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
        c.restore()
        // keep animating (cheap: only a few small views)
        if (on || lit > 0.02f || ripple < 1f) postInvalidateOnAnimation()
    }
}
