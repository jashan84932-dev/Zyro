package com.zyro.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.View

/** A simple bar chart of this month's spending. Tap anywhere to close. */
class ReportActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val title = intent.getStringExtra("title") ?: "खर्च"
        val labels = intent.getStringArrayListExtra("labels") ?: arrayListOf()
        val values = intent.getIntegerArrayListExtra("values") ?: arrayListOf()
        val v = ChartView(this, title, labels, values)
        v.setOnClickListener { finish() }
        setContentView(v)
    }

    private class ChartView(ctx: Context, val title: String, val labels: List<String>, val values: List<Int>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val colors = listOf("#FFC107", "#FF9800", "#FFB74D", "#FFD54F", "#FFE082", "#FFCA28").map { Color.parseColor(it) }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.parseColor("#0B0F14"))
            val w = width.toFloat()
            val d = resources.displayMetrics.density
            p.color = Color.parseColor("#FFD54F")
            p.textSize = 22f * d
            p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(title, 24f * d, 70f * d, p)
            val max = (values.maxOrNull() ?: 1).coerceAtLeast(1)
            var y = 120f * d
            val barH = 34f * d
            for (i in values.indices) {
                p.typeface = Typeface.DEFAULT
                p.textSize = 15f * d
                p.color = Color.WHITE
                c.drawText(labels.getOrElse(i) { "" }, 24f * d, y, p)
                y += 8f * d
                val len = (w - 48f * d) * values[i] / max
                p.color = colors[i % colors.size]
                c.drawRoundRect(RectF(24f * d, y, 24f * d + len.coerceAtLeast(6f * d), y + barH), 8f * d, 8f * d, p)
                p.color = Color.parseColor("#0B0F14")
                p.textSize = 14f * d
                p.typeface = Typeface.DEFAULT_BOLD
                c.drawText("₹${values[i]}", 34f * d, y + barH * 0.68f, p)
                y += barH + 30f * d
            }
            p.color = Color.parseColor("#7A8794")
            p.typeface = Typeface.DEFAULT
            p.textSize = 13f * d
            c.drawText("बंद करने के लिए कहीं भी टच करो", 24f * d, height - 40f * d, p)
        }
    }
}
