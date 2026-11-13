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
            setOnClickListener { toggleLive() }
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

        liveBtn = IconView(this, IconView.LIVE) { Live.active }.apply {
            contentDescription = "Live conversation"
            setOnClickListener { toggleLive() }
        }
        root.addView(liveBtn, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(56) })

        setContentView(root)
    }

    private fun toggleLive() {
        if (Live.active) {
            Live.stop()
            refresh()
            return
        }
        startTalking()
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
        if (Prefs.standby && !assistant.active && !Live.active &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ListenService.start(this)
        }
        assistant.onChange = { refresh() }
        refresh()
    }

    override fun onPause() {
        assistant.onChange = null
        super.onPause()
    }

    private fun refresh() {
        liveBtn.invalidate()
    }
}
