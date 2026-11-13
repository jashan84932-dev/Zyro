package com.zyro.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Everything that used to clutter the home screen lives here. */
class SettingsActivity : Activity() {

    private companion object {
        val BG = Color.rgb(4, 9, 22)
        val GOLD = Color.rgb(47, 140, 255)
        val SOFT = Color.rgb(222, 236, 255)
        val DIM = Color.rgb(112, 140, 190)
    }

    private lateinit var assistant: Assistant
    private lateinit var statusView: TextView
    private lateinit var proxyUrl: EditText
    private lateinit var proxyToken: EditText
    private lateinit var githubTok: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var apiKey: EditText
    private lateinit var geminiKey: EditText
    private lateinit var friendName: EditText
    private lateinit var userName: EditText
    private lateinit var customVoice: CheckBox
    private lateinit var voiceKey: EditText
    private lateinit var voiceId: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assistant = Assistant.get(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(34), dp(18), dp(28))
        }

        fun label(text: String, size: Float = 13f) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(DIM)
            letterSpacing = 0.06f
            setPadding(0, dp(18), 0, dp(4))
        }

        fun field(hint: String, secret: Boolean = false, value: String = "") = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            setTextColor(SOFT)
            setHintTextColor(DIM)
            backgroundTintList = ColorStateList.valueOf(Color.argb(150, 47, 140, 255))
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
        }

        fun button(text: String, filled: Boolean = false, action: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(if (filled) BG else GOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(if (filled) GOLD else Color.argb(24, 47, 140, 255))
                setStroke(dp(1), Color.argb(190, 47, 140, 255))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener { action() }
        }

        // --- top bar: back icon only ---
        box.addView(IconView(this, IconView.BACK).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { finish() }
        })

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(SOFT)
            setPadding(0, dp(10), 0, dp(4))
        }
        box.addView(statusView)

        box.addView(label("CONVERSATION", 13f))
        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(22, 47, 140, 255))
                setStroke(dp(1), Color.argb(90, 47, 140, 255))
            }
        }
        logView = TextView(this).apply {
            textSize = 15f
            setTextColor(SOFT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        logScroll.addView(logView)
        box.addView(logScroll)

        // --- Setup (do once) ---
        box.addView(label("SETUP - DO ONCE", 15f))
        box.addView(button("1. Allow microphone, notifications, contacts, calls") { Perms.request(this) })
        box.addView(button("2. Turn ON Zyro in Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        box.addView(button("3. Allow Zyro to keep running in background") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        box.addView(button("4. Allow display over other apps (helps open apps)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        box.addView(button("Air control: start / stop (hand gestures)") {
            val a = Assistant.get(this)
            val r = if (AirControl.running) AirControl.stop() else AirControl.start(this, a.hands)
            Toast.makeText(this, r, Toast.LENGTH_LONG).show()
        })

        box.addView(button("Allow Zyro to read notifications (optional)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        box.addView(label("VOICE OPTIONS", 15f))
        fun option(text: String, on: Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply {
            this.text = text
            setTextColor(SOFT)
            buttonTintList = ColorStateList.valueOf(GOLD)
            isChecked = on
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        box.addView(option("Jarvis style (calm, formal, calls me Sir) - restart Zyro after changing", Prefs.jarvisMode) { Prefs.jarvisMode = it })
        box.addView(option("Standby: stay ready all day; say \"wake up\" or \"Hey Zyro\" and Live starts", Prefs.standby) {
            Prefs.standby = it
            if (it) ListenService.start(this) else { Live.stop(); ListenService.stop(this) }
        })
        box.addView(option("Wake word: answer only after I say \"Hey Zyro\"", Prefs.wakeWord) { Prefs.wakeWord = it })
        box.addView(option("Wake the screen when I say \"Hey Zyro\" and the phone is asleep", Prefs.autoWake) { Prefs.autoWake = it })
        box.addView(option("Let me interrupt Zyro by saying \"ruko\" / \"stop\"", Prefs.bargeIn) { Prefs.bargeIn = it })
        box.addView(option("Start Zyro automatically when the phone restarts", Prefs.autoStart) { Prefs.autoStart = it })
        box.addView(option("Shake the phone twice to toggle the torch", Prefs.shakeTorch) { Prefs.shakeTorch = it })
        box.addView(option("Face down = Zyro stays quiet and collects messages", Prefs.faceDownQuiet) { Prefs.faceDownQuiet = it })
        box.addView(option("Pocket mode: read new messages aloud, do not wake the screen", Prefs.pocketMode) { Prefs.pocketMode = it })

        box.addView(label("SMART TV (Android TV / Aiwa) - optional", 15f))
        box.addView(label("Turn the TV on and connect it to this phone's hotspot (or the same Wi-Fi)."))
        box.addView(button("5. Pair my TV - show code on TV") {
            Toast.makeText(this, "Looking for the TV...", Toast.LENGTH_SHORT).show()
            Thread {
                val r = Tv.startPairing()
                runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
            }.start()
        })
        val tvCode = field("code shown on the TV")
        box.addView(tvCode)
        box.addView(button("6. Confirm TV code") {
            val c = tvCode.text.toString()
            Thread {
                val r = Tv.finishPairing(c)
                runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
            }.start()
        })
        box.addView(button("Test TV: volume up") {
            Thread {
                val r = Tv.run("volume_up")
                runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
            }.start()
        })

        box.addView(label("Free Gemini key (BEST free brain) - get it at aistudio.google.com"))
        geminiKey = field("paste Gemini key here", secret = true, value = Prefs.geminiKey)
        box.addView(geminiKey)

        box.addView(label("ZYRO SERVER (optional, hides your key) - worker URL like wss://xxx.workers.dev"))
        proxyUrl = field("wss://your-worker.workers.dev", value = Prefs.liveProxy)
        box.addView(proxyUrl)
        proxyToken = field("server password (APP_TOKEN)", secret = true, value = Prefs.liveToken)
        box.addView(proxyToken)

        box.addView(label("GITHUB token (for 'GitHub par repo banao / files upload karo') - github.com > Settings > Developer settings > Tokens, repo permission"))
        githubTok = field("ghp_... or github_pat_...", secret = true, value = Prefs.githubToken)
        box.addView(githubTok)

        box.addView(label("Claude API key (optional)"))
        apiKey = field("leave empty if not needed", secret = true, value = Prefs.apiKey)
        box.addView(apiKey)

        box.addView(label("Friend's name"))
        friendName = field("Zyro", value = Prefs.friendName)
        box.addView(friendName)

        box.addView(label("Your name"))
        userName = field("Your name", value = Prefs.userName)
        box.addView(userName)

        box.addView(label("Custom voice (ElevenLabs)", 15f))
        customVoice = CheckBox(this).apply {
            text = "Use my custom voice"
            setTextColor(SOFT)
            buttonTintList = ColorStateList.valueOf(GOLD)
            isChecked = Prefs.customVoice
        }
        box.addView(customVoice)
        voiceKey = field("ElevenLabs API key", secret = true, value = Prefs.voiceKey)
        box.addView(voiceKey)
        voiceId = field("Voice ID", value = Prefs.voiceId)
        box.addView(voiceId)

        box.addView(button("Save settings", filled = true) { saveSettings() })
        box.addView(button("Forget everything (delete memory)") {
            Memory.clearAll()
            Toast.makeText(this, "Memory cleared", Toast.LENGTH_SHORT).show()
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(box)
        })
    }

    private fun saveSettings() {
        Prefs.geminiKey = geminiKey.text.toString()
        Prefs.apiKey = apiKey.text.toString()
        Prefs.liveProxy = proxyUrl.text.toString()
        Prefs.liveToken = proxyToken.text.toString()
        Prefs.githubToken = githubTok.text.toString()
        Prefs.friendName = friendName.text.toString()
        Prefs.userName = userName.text.toString()
        Prefs.customVoice = customVoice.isChecked
        Prefs.voiceKey = voiceKey.text.toString()
        Prefs.voiceId = voiceId.text.toString()
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        refresh()
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
        val hands = if (ZyroService.instance != null) "phone control ON" else "phone control OFF"
        statusView.text = "${assistant.status}  •  $hands"
        logView.text = assistant.transcript.toString()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
