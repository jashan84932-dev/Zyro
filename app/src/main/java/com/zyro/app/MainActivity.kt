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

/**
 * Voice-only home screen: a big orb, one Start/Stop button, and an optional text view.
 * There is no typing - you just talk to Zyro.
 */
class MainActivity : Activity() {

    private companion object {
        val BG = Color.rgb(5, 8, 12)
        val GOLD = Color.rgb(255, 181, 46)
        val SOFT = Color.rgb(240, 226, 190)
        val DIM = Color.rgb(138, 122, 82)
    }

    private lateinit var assistant: Assistant
    private lateinit var orb: OrbView
    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private lateinit var textButton: Button
    private lateinit var liveButton: Button
    private lateinit var proxyUrl: EditText
    private lateinit var proxyToken: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var apiKey: EditText
    private lateinit var geminiKey: EditText
    private lateinit var friendName: EditText
    private lateinit var userName: EditText
    private lateinit var customVoice: CheckBox
    private lateinit var voiceKey: EditText
    private lateinit var voiceId: EditText

    private var startAfterPermission = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assistant = Assistant.get(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(36), dp(18), dp(24))
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
            backgroundTintList = ColorStateList.valueOf(Color.argb(150, 255, 181, 46))
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
        }

        fun button(text: String, filled: Boolean = false, action: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(if (filled) BG else GOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(if (filled) GOLD else Color.argb(24, 255, 181, 46))
                setStroke(dp(1), Color.argb(190, 255, 181, 46))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener { action() }
        }

        // --- Title + orb + status ---
        box.addView(TextView(this).apply {
            text = "Z Y R O"
            textSize = 26f
            letterSpacing = 0.4f
            gravity = Gravity.CENTER
            setTextColor(GOLD)
        })

        orb = OrbView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340))
            modeSource = { assistant.mode }
            levelSource = { assistant.level }
            setOnClickListener { toggle() }
        }
        box.addView(orb)

        statusView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setTextColor(SOFT)
            setPadding(0, dp(4), 0, dp(8))
        }
        box.addView(statusView)

        // --- Voice controls (no typing) ---
        startButton = button("Start Zyro", filled = true) { toggle() }
        box.addView(startButton)

        liveButton = button("⚡ LIVE — सीधी बात (fast)", filled = true) { toggleLive() }
        box.addView(liveButton)

        textButton = button("Show text") {
            Prefs.showText = !Prefs.showText
            refresh()
        }
        box.addView(textButton)

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240))
                .apply { topMargin = dp(10) }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(14, 255, 181, 46))
                setStroke(dp(1), Color.argb(70, 255, 181, 46))
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
        box.addView(button("1. Allow microphone, notifications, contacts, calls") { askPermissions() })
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
        box.addView(option("Wake word: answer only after I say \"Hey Zyro\"", Prefs.wakeWord) { Prefs.wakeWord = it })
        box.addView(option("Wake the screen when I say \"Hey Zyro\" and the phone is asleep", Prefs.autoWake) { Prefs.autoWake = it })
        box.addView(option("Let me interrupt Zyro by saying \"ruko\" / \"stop\"", Prefs.bargeIn) { Prefs.bargeIn = it })
        box.addView(option("Start Zyro automatically when the phone restarts", Prefs.autoStart) { Prefs.autoStart = it })
        box.addView(option("Clap twice to find my phone (Zyro shouts \"I am here\")", Prefs.clapFind) { Prefs.clapFind = it })
        box.addView(option("Shake the phone twice to toggle the torch", Prefs.shakeTorch) { Prefs.shakeTorch = it })
        box.addView(option("Face down = Zyro stays quiet and collects messages", Prefs.faceDownQuiet) { Prefs.faceDownQuiet = it })
        box.addView(option("Pocket mode: read new messages aloud, do not wake the screen", Prefs.pocketMode) { Prefs.pocketMode = it })
        box.addView(option("Mood aware: kind hint when I sound tired or stressed (a guess only)", Prefs.moodAware) { Prefs.moodAware = it })

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

        box.addView(button("Save settings") { saveSettings() })
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

    // ---------- start / stop ----------

    private fun toggle() {
        if (assistant.active) assistant.stopConversation() else startTalking()
    }

    private fun toggleLive() {
        if (Live.active) {
            Live.stop()
            refresh()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions()
            return
        }
        if (!assistant.active) ListenService.start(this)
        Toast.makeText(this, "Live शुरू हो रहा है…", Toast.LENGTH_SHORT).show()
        orb.postDelayed({
            val r = Live.start(this, assistant.hands, 0L)
            if (r.isNotBlank() && !r.startsWith("ठीक है")) Toast.makeText(this, r, Toast.LENGTH_LONG).show()
            refresh()
        }, 1200)
    }

    private fun startTalking() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true
            askPermissions()
            return
        }
        if (ZyroService.instance == null) {
            Toast.makeText(
                this,
                "Turn ON Zyro in Accessibility (setup step 2) so I can control the phone",
                Toast.LENGTH_LONG
            ).show()
        }
        ListenService.start(this)
    }

    private fun askPermissions() {
        requestPermissions(
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (startAfterPermission) {
            startAfterPermission = false
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startTalking()
            }
        }
    }

    private fun saveSettings() {
        Prefs.geminiKey = geminiKey.text.toString()
        Prefs.apiKey = apiKey.text.toString()
        Prefs.liveProxy = proxyUrl.text.toString()
        Prefs.liveToken = proxyToken.text.toString()
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
        if (!assistant.voice.hindiAvailable) {
            Toast.makeText(
                this,
                "No Hindi voice on this phone. Settings > Text-to-speech > download Hindi voice.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onPause() {
        assistant.onChange = null
        super.onPause()
    }

    private fun refresh() {
        val hands = if (ZyroService.instance != null) "phone control ON" else "phone control OFF"
        statusView.text = "${assistant.status}  •  $hands"
        startButton.text = if (assistant.active) "Stop Zyro" else "Start Zyro"
        liveButton.text = if (Live.active) "⏹ LIVE बंद करो" else "⚡ LIVE — सीधी बात (fast)"

        val show = Prefs.showText
        textButton.text = if (show) "Hide text" else "Show text"
        logScroll.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            logView.text = assistant.transcript.toString()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
