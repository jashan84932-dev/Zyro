package com.zyro.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Hologram mode. Lay the phone flat, screen up, in a dark room, and stand a clear four-sided pyramid
 * (cut from a CD case or acrylic sheet, narrow end down) in the middle of the screen.
 * Zyro's core is drawn four times, once facing each side of the pyramid, so a floating copy
 * appears inside it. Tap anywhere to close.
 */
class HoloActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 1f
        window.attributes = lp
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val a = Assistant.get(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.post {
            val w = root.width
            val h = root.height
            val s = minOf(w, h)
            val cell = (s * 0.34f).toInt()
            val cxm = w / 2
            val cym = h / 2
            val gap = (s * 0.17f).toInt() // half the empty square in the middle, where the pyramid stands
            fun add(x: Int, y: Int, rot: Float) {
                val v = OrbView(this).apply {
                    holo = true
                    modeSource = { a.mode }
                    levelSource = { a.level }
                    rotation = rot
                }
                val p = FrameLayout.LayoutParams(cell, cell)
                p.leftMargin = x - cell / 2
                p.topMargin = y - cell / 2
                root.addView(v, p)
            }
            add(cxm, cym - gap - cell / 2, 180f) // top, upside down
            add(cxm, cym + gap + cell / 2, 0f)   // bottom
            add(cxm - gap - cell / 2, cym, 90f)  // left
            add(cxm + gap + cell / 2, cym, 270f) // right
        }
        root.setOnClickListener { finish() }
        setContentView(root)
    }
}
