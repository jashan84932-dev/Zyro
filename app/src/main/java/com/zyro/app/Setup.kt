package com.zyro.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object Setup {
    fun handle(ctx: Context, t: String): String? {
        fun any(vararg w: String) = w.any { t.contains(Local.strip(it)) }
        if ((any("setup", "सेटअप") && any("dobara", "दोबारा", "phir se", "फिर से", "shuru", "शुरू", "karo", "करो")) ) {
            Prefs.setupDone = false
            try { ctx.startActivity(Intent(ctx, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            return "सेटअप खोल रहा हूँ।"
        }
        return null
    }

    fun a11yOn(ctx: Context): Boolean = ZyroService.instance != null
    fun notifOn(ctx: Context): Boolean = try {
        (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains(ctx.packageName)
    } catch (e: Exception) { false }
    fun overlayOn(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(ctx) else true
    fun batteryOn(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 23) (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) else true
    } catch (e: Exception) { false }
    fun micOn(ctx: Context): Boolean = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/** First-run guide: one thing at a time, in plain words. Each step moves on by itself once it is done. */
class SetupActivity : Activity() {
    private class Step(val title: String, val body: String, val button: String, val done: (Context) -> Boolean, val go: (Activity) -> Unit, val optional: Boolean = false)

    private lateinit var steps: List<Step>
    private var idx = 0
    private lateinit var titleV: TextView
    private lateinit var bodyV: TextView
    private lateinit var mainBtn: Button
    private lateinit var skipBtn: TextView
    private lateinit var field: EditText
    private lateinit var dots: LinearLayout
    private var inputKind = 0 // 0 none, 1 key, 2 name

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.rgb(2, 11, 18)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        steps = listOf(
            Step("माइक और अनुमतियाँ", "मुझे सुनने, कॉल, मैसेज और कैमरे के लिए अनुमति चाहिए। अगली स्क्रीन पर सबको 'Allow' दबा दो।", "अनुमति दो", { micOn(it) }, { Perms.request(it) }),
            Step("Accessibility", "इसी से मैं तुम्हारे कहने पर ऐप में टैप और स्क्रॉल कर पाता हूँ। लिस्ट में 'Zyro' खोलो और चालू कर दो।", "खोलो", { Setup.a11yOn(it) }, { it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
            Step("नोटिफिकेशन पढ़ना", "ताकि नए मैसेज और कॉल मैं तुम्हें बोलकर बता सकूँ। लिस्ट में 'Zyro' चालू करो।", "खोलो", { Setup.notifOn(it) }, { it.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }),
            Step("दूसरे ऐप के ऊपर दिखना", "ताकि मैं किसी भी ऐप के ऊपर दिख सकूँ। 'Zyro' के आगे स्विच चालू करो।", "खोलो", { Setup.overlayOn(it) }, {
                try { it.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + it.packageName))) } catch (e: Exception) { }
            }, true),
            Step("बैटरी", "ताकि फ़ोन मुझे बैकग्राउंड में बंद न कर दे। 'Allow' दबाओ।", "खोलो", { Setup.batteryOn(it) }, {
                try { it.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + it.packageName))) } catch (e: Exception) { }
            }, true),
            Step("Gemini key", "मेरे दिमाग़ के लिए मुफ़्त Gemini key चाहिए। aistudio.google.com/apikey पर बनाओ, कॉपी करके यहाँ पेस्ट करो।", "सेव करो", { Prefs.geminiKeysRaw.isNotBlank() }, { }),
            Step("तुम्हारा नाम", "मैं तुम्हें किस नाम से बुलाऊँ?", "सेव करो", { Prefs.userName.isNotBlank() }, { }, true)
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(24), dp(56), dp(24), dp(24))
        }
        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(dots, LinearLayout.LayoutParams(-1, dp(14)))
        val sp = View(this); root.addView(sp, LinearLayout.LayoutParams(-1, dp(40)))
        titleV = TextView(this).apply { setTextColor(Color.rgb(223, 250, 255)); textSize = 26f; typeface = Typeface.DEFAULT_BOLD }
        root.addView(titleV)
        bodyV = TextView(this).apply { setTextColor(Color.rgb(150, 190, 205)); textSize = 16f; setPadding(0, dp(14), 0, dp(20)) }
        root.addView(bodyV)
        field = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(90, 120, 135)); visibility = View.GONE; maxLines = 3
            background = GradientDrawable().apply { setColor(Color.rgb(8, 28, 40)); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(60, 210, 250)) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(field, LinearLayout.LayoutParams(-1, -2))
        mainBtn = Button(this).apply {
            setTextColor(Color.rgb(2, 11, 18)); textSize = 17f; isAllCaps = false
            background = GradientDrawable().apply { setColor(Color.rgb(60, 210, 250)); cornerRadius = dp(28).toFloat() }
            setOnClickListener { onMain() }
        }
        root.addView(mainBtn, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(24) })
        skipBtn = TextView(this).apply {
            setTextColor(Color.rgb(120, 160, 175)); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18)); text = "अभी छोड़ो"
            setOnClickListener { next() }
        }
        root.addView(skipBtn, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        idx = steps.indexOfFirst { !it.done(this) }.let { if (it < 0) steps.size else it }
        show()
    }

    override fun onResume() {
        super.onResume()
        if (!::steps.isInitialized) return
        // came back from a system screen: move on if that step is now done
        if (idx < steps.size && inputKind == 0 && steps[idx].done(this)) next()
    }

    private fun show() {
        if (idx >= steps.size) { finishUp(); return }
        val s = steps[idx]
        titleV.text = s.title
        bodyV.text = s.body
        mainBtn.text = s.button
        skipBtn.visibility = View.VISIBLE
        inputKind = when (idx) { 5 -> 1; 6 -> 2; else -> 0 }
        field.visibility = if (inputKind == 0) View.GONE else View.VISIBLE
        field.setText("")
        field.hint = if (inputKind == 1) "AIza..." else "नाम"
        field.inputType = if (inputKind == 1) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        dots.removeAllViews()
        for (i in steps.indices) {
            val d = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i < idx) Color.rgb(60, 210, 250) else if (i == idx) Color.WHITE else Color.rgb(30, 60, 75)) }
            }
            dots.addView(d, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginStart = dp(5); marginEnd = dp(5) })
        }
    }

    private fun onMain() {
        val s = steps[idx]
        when (inputKind) {
            1 -> { val v = field.text.toString().trim(); if (v.length > 10) { Prefs.geminiKeysRaw = v; next() } else field.error = "सही key पेस्ट करो" }
            2 -> { val v = field.text.toString().trim(); if (v.isNotEmpty()) Prefs.userName = v; next() }
            else -> try { s.go(this) } catch (e: Exception) { next() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (idx < steps.size && steps[idx].done(this)) next() else if (idx == 0) next()
    }

    private fun next() { idx++; show() }

    private fun finishUp() {
        Prefs.setupDone = true
        titleV.text = "सब तैयार!"
        bodyV.text = "अब होम स्क्रीन पर नीचे वाला बटन दबाकर बोलो, जैसे: 'Wake up Zyro'।"
        field.visibility = View.GONE
        skipBtn.visibility = View.GONE
        mainBtn.text = "शुरू करें"
        mainBtn.setOnClickListener { finish() }
        inputKind = 3
    }
}
