package com.zyro.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Zyro's face: the plasma-sphere picture itself, alive.
 * The picture breathes slowly, a warm light swells from its heart with your voice, and a cyan flash runs through it
 * while Zyro thinks.
 *   IDLE   - slow breathing
 *   LISTEN - the heart glows with how loud you are
 *   THINK  - quick pulses and a cyan flash
 *   SPEAK  - the heart pulses with Zyro's voice
 */
class OrbView(context: Context) : View(context) {

    companion object {
        const val IDLE = 0
        const val LISTEN = 1
        const val THINK = 2
        const val SPEAK = 3
    }

    var modeSource: (() -> Int)? = null
    var levelSource: (() -> Float)? = null

    /** Hologram mode: pure black background, centred - for a Pepper's-ghost pyramid. */
    var holo = false

    private val art: Bitmap? = try {
        val b = ZyroArt.jpegBytes()
        BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (e: Throwable) { null }

    private val pic = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    private var t = 0f
    private var last = 0L
    private var energy = 0.1f
    private var flare = 0f

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

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
        val wantEnergy = when (mode) {
            LISTEN -> 0.15f + level * 0.85f
            THINK -> 0.55f + 0.25f * sin(t * 9f)
            SPEAK -> 0.35f + 0.45f * abs(sin(t * 7.3f) * cos(t * 3.1f)) + level * 0.3f
            else -> 0.08f + 0.05f * sin(t * 1.3f)
        }
        energy = lerp(energy, wantEnergy, 1f - exp(-dt * if (wantEnergy > energy) 14f else 5f))
        flare = lerp(flare, if (mode == THINK) 1f else 0f, 1f - exp(-dt * 3f))

        val bg = if (holo) Color.BLACK else Color.rgb(3, 5, 10)
        c.drawColor(bg)
        val a = art
        if (a == null) { postInvalidateOnAnimation(); return }

        // the picture, fitted to the screen width and centred, breathing a little
        val breathe = 1f + 0.012f * sin(t * 0.9f) + 0.05f * energy
        val sc = (w / a.width) * breathe
        val iw = a.width * sc
        val ih = a.height * sc
        val cx = w / 2f + sin(t * 0.35f) * w * 0.004f
        val cy = if (holo) h * 0.5f else h * 0.45f
        dst.set(cx - iw / 2f, cy - ih / 2f, cx + iw / 2f, cy + ih / 2f)
        pic.alpha = 255
        c.drawBitmap(a, null, dst, pic)

        // soften the top and bottom edges into the dark background
        val edge = ih * 0.22f
        fade.shader = LinearGradient(0f, dst.top, 0f, dst.top + edge, bg, Color.argb(0, Color.red(bg), Color.green(bg), Color.blue(bg)), Shader.TileMode.CLAMP)
        c.drawRect(0f, dst.top - 2f, w, dst.top + edge, fade)
        fade.shader = LinearGradient(0f, dst.bottom - edge, 0f, dst.bottom, Color.argb(0, Color.red(bg), Color.green(bg), Color.blue(bg)), bg, Shader.TileMode.CLAMP)
        c.drawRect(0f, dst.bottom - edge, w, dst.bottom + 2f, fade)

        // a warm light swelling from the heart of the sphere with the voice
        val hx = dst.left + dst.width() * 0.50f
        val hy = dst.top + dst.height() * 0.40f
        val gr = dst.width() * (0.30f + 0.20f * energy)
        glowP.shader = RadialGradient(
            hx, hy, gr,
            intArrayOf(Color.argb((40 + 150 * energy).toInt().coerceAtMost(220), 255, 200, 120), Color.argb((20 + 60 * energy).toInt(), 255, 110, 30), Color.argb(0, 255, 90, 20)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(hx, hy, gr, glowP)

        // cyan flash while thinking
        if (flare > 0.02f) {
            val fr = dst.width() * 0.55f
            glowP.shader = RadialGradient(
                hx, hy, fr,
                intArrayOf(Color.argb((70 * flare * (0.6f + 0.4f * sin(t * 11f))).toInt().coerceIn(0, 255), 80, 200, 255), Color.argb(0, 40, 150, 255)),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(hx, hy, fr, glowP)
        }
        glowP.shader = null

        postInvalidateOnAnimation()
    }
}
