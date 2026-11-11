package com.zyro.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import java.util.Calendar
import java.util.Locale

/** A Jarvis style heads-up display: arc reactor, clock and phone status. Tap to close. */
class HudActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        val v = HudView(this)
        v.setOnClickListener { finish() }
        setContentView(v)
    }

    private class HudView(val ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cyan = Color.parseColor("#00E5FF")
        private val dim = Color.parseColor("#0A3B47")
        private val born = System.currentTimeMillis()

        private fun battery(): Pair<Int, Boolean> {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return Pair(if (level >= 0) level * 100 / scale else 0, charging)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.parseColor("#02070D"))
            val w = width.toFloat()
            val h = height.toFloat()
            val d = resources.displayMetrics.density
            val cx = w / 2
            val cy = h * 0.42f
            val t = (System.currentTimeMillis() - born) / 1000f
            val base = Math.min(w, h * 0.7f) * 0.42f

            // rotating rings
            p.style = Paint.Style.STROKE
            for (k in 0 until 4) {
                val r = base * (1f - k * 0.18f)
                p.strokeWidth = (3f - k * 0.5f) * d
                p.color = if (k % 2 == 0) cyan else dim
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                val dir = if (k % 2 == 0) 1 else -1
                val start = dir * t * (30f + k * 25f)
                for (seg in 0 until 4) c.drawArc(rect, start + seg * 90f, 55f, false, p)
            }
            // glowing core
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#33B8FF")
            p.alpha = (140 + 80 * Math.sin(t * 2.5)).toInt().coerceIn(60, 255)
            c.drawCircle(cx, cy, base * 0.16f, p)
            p.alpha = 255
            p.color = Color.WHITE
            c.drawCircle(cx, cy, base * 0.07f, p)

            // clock
            val cal = Calendar.getInstance()
            p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            p.color = cyan
            p.textAlign = Paint.Align.CENTER
            p.textSize = 44f * d
            c.drawText(String.format(Locale.US, "%02d:%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)), cx, h * 0.76f, p)
            p.typeface = Typeface.DEFAULT
            p.textSize = 16f * d
            p.color = Color.parseColor("#7FDBFF")
            val day = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", Locale("en", "IN")).format(cal.time)
            c.drawText(day.uppercase(Locale.ROOT), cx, h * 0.80f, p)

            // status line
            val (lvl, chg) = battery()
            p.textSize = 15f * d
            p.color = if (lvl < 20 && !chg) Color.parseColor("#FF5252") else cyan
            c.drawText("POWER $lvl%" + if (chg) "  CHARGING" else "", cx, h * 0.86f, p)
            p.color = Color.parseColor("#7FDBFF")
            c.drawText("ALL SYSTEMS ONLINE", cx, h * 0.90f, p)
            p.textSize = 12f * d
            p.color = dim
            c.drawText("TAP TO CLOSE", cx, h * 0.96f, p)
            postInvalidateOnAnimation()
        }
    }
}
