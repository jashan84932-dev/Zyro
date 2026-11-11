package com.zyro.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Home screen: just the glowing orb. No text at all.
 *   orb / power icon : start or stop listening
 *   wave icon        : live conversation
 *   gear icon        : everything else (settings, conversation, setup)
 */
class MainActivity : Activity() {

    private lateinit var assistant: Assistant
    private lateinit var orb: OrbView
    private lateinit var powerBtn: IconView
    private lateinit var liveBtn: IconView
    private lateinit var gearBtn: IconView

    private var startAfterPermission = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assistant = Assistant.get(this)
        val bg = Color.rgb(3, 6, 16)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = FrameLayout(this).apply { setBackgroundColor(bg) }

        orb = OrbView(this).apply {
            modeSource = { assistant.mode }
            levelSource = { assistant.level }
            setOnClickListener { toggle() }
        }
        root.addView(orb, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        gearBtn = IconView(this, IconView.GEAR).apply {
            contentDescription = "Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(gearBtn, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(40)
            marginEnd = dp(18)
        })

        powerBtn = IconView(this, IconView.POWER) { assistant.active }.apply {
            contentDescription = "Start or stop"
            setOnClickListener { toggle() }
        }
        liveBtn = IconView(this, IconView.LIVE) { Live.active }.apply {
            contentDescription = "Live conversation"
            setOnClickListener { toggleLive() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bar.addView(powerBtn, LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(30) })
        bar.addView(liveBtn, LinearLayout.LayoutParams(dp(84), dp(84)))
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(56) })

        setContentView(root)
    }

    private fun toggle() {
        if (assistant.active) assistant.stopConversation() else startTalking()
        refresh()
    }

    private fun toggleLive() {
        if (Live.active) {
            Live.stop()
            refresh()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = false
            Perms.request(this)
            return
        }
        if (!assistant.active) ListenService.start(this)
        orb.postDelayed({
            val r = Live.start(this, assistant.hands, 0L)
            if (r.isNotBlank() && !r.startsWith("ठीक है")) Toast.makeText(this, r, Toast.LENGTH_LONG).show()
            refresh()
        }, 1200)
    }

    private fun startTalking() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true
            Perms.request(this)
            return
        }
        if (ZyroService.instance == null) {
            Toast.makeText(this, "Settings में Accessibility चालू करो", Toast.LENGTH_LONG).show()
        }
        ListenService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (startAfterPermission) {
            startAfterPermission = false
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startTalking()
        }
    }

    override fun onResume() {
        super.onResume()
        assistant.onChange = { refresh() }
        refresh()
    }

    override fun onPause() {
        assistant.onChange = null
        super.onPause()
    }

    private fun refresh() {
        powerBtn.invalidate()
        liveBtn.invalidate()
    }
}
