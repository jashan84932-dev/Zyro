package com.zyro.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.util.Locale

/**
 * Voice to APK. "Zyro, ek calculator app banao" -> Zyro writes the whole app, puts it on your GitHub,
 * GitHub builds it, and Zyro opens the download link when the APK is ready.
 * The apps are single-page web apps inside a tiny Android shell (WebView), so the build is reliable.
 */
object Forge {

    @Volatile private var busy = false

    private fun has(t: String, vararg w: String) = w.any { t.contains(Local.strip(it)) }

    fun handle(ctx: Context, hands: Hands, t: String, raw: String): String? {
        val isApp = has(t, "app", "ऐप", "एप", "apk", "एपीके")
        val make = has(t, "banao", "बनाओ", "bana do", "बना दो", "bana de", "बना दे", "create")
        if (!isApp || !make) return null
        if (has(t, "github", "गिटहब", "tasveer", "तस्वीर", "image", "logo", "लोगो", "poster", "पोस्टर", "wallpaper")) return null
        if (has(t, "kaise", "कैसे")) return null
        if (!Prefs.hasGemini) return "ऐप बनाने के लिए Gemini key चाहिए।"
        if (Prefs.githubToken.isBlank()) return "ऐप बनाने के लिए GitHub token चाहिए। Settings में GITHUB token डालो। उसमें repo और workflow की अनुमति होनी चाहिए।"
        if (busy) return "मैं पहले से एक ऐप बना रहा हूँ।"
        val idea = raw.replace(Regex("(?i)(zyro|ज़ायरो|जायरो|banao|बनाओ|bana do|बना दो|bana de|बना दे|ek|एक|mujhe|मुझे|mere liye|मेरे लिए|apk|एपीके|create)"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (idea.length < 3) return "कैसा ऐप बनाना है? जैसे: एक कैलकुलेटर ऐप बनाओ, या खर्चे का हिसाब रखने वाला ऐप बनाओ।"
        busy = true
        Thread {
            val a = Assistant.get(ctx)
            try {
                a.announce(run(ctx, hands, idea))
            } catch (e: Exception) {
                a.announce("ऐप नहीं बन पाया: ${e.message}")
            } finally {
                busy = false
            }
        }.start()
        return "ठीक है, ऐप बना रहा हूँ। चार-पाँच मिनट लगेंगे, तैयार होते ही बता दूँगा।"
    }

    private fun run(ctx: Context, hands: Hands, idea: String): String {
        val prompt = """The user (speaking Hinglish) asked for an Android app: "$idea".
Write the COMPLETE app as ONE self-contained mobile web page (HTML + CSS + JavaScript all inline, no external libraries, no CDN, no network needed). It must look modern and premium: dark navy background, blue accents, large touch-friendly buttons, responsive for phones, smooth, fully working with real logic. Save user data with localStorage when it makes sense. Use the interface language the user spoke (Hindi in Devanagari / English / Hinglish).
Reply as strict JSON only: {"name":"short app name (max 3 words)","html":"<!doctype html>..."}"""
        val j = Brain.quickJson(prompt, 14000, 150000) ?: return "ऐप का कोड नहीं बन पाया।"
        val o = JSONObject(j.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        val name = o.optString("name").ifBlank { "Zyro App" }.take(30)
        val html = o.optString("html")
        if (html.length < 200 || !html.contains("<html", true)) return "ऐप का कोड अधूरा आया। दोबारा बोलो।"

        val slug = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").ifBlank { "app" }.take(20)
        val pkg = "com.zyro.made.a$slug"
        val repo = "app-" + slug + "-" + (System.currentTimeMillis() / 1000 % 100000)
        val files = listOf(
            "settings.gradle.kts" to SETTINGS,
            "build.gradle.kts" to ROOT,
            "gradle.properties" to PROPS,
            "app/build.gradle.kts" to APP.replace("@@PKG@@", pkg),
            "app/src/main/AndroidManifest.xml" to MANIFEST.replace("@@NAME@@", xml(name)),
            "app/src/main/java/com/zyro/made/MainActivity.kt" to MAIN,
            "app/src/main/assets/index.html" to html,
            ".github/workflows/build.yml" to WORKFLOW.replace("DOLLAR", "$").replace("@@NAME@@", name.replace("\"", ""))
        )
        val (me, msg) = Github.pushFiles(repo, files, "App made by Zyro: $name")
        if (me == null) return msg

        // wait for GitHub to build (up to about 12 minutes)
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < 12 * 60_000L) {
            Thread.sleep(20_000)
            val r = Github.get("/repos/$me/$repo/actions/runs?per_page=1")
            if (r.first != 200) continue
            val run = JSONObject(r.second).optJSONArray("workflow_runs")?.optJSONObject(0) ?: continue
            if (run.optString("status") != "completed") continue
            if (run.optString("conclusion") != "success") {
                open(hands, "https://github.com/$me/$repo/actions")
                return "$name का बिल्ड फेल हो गया। GitHub का पेज खोल दिया है, वहाँ लॉग देख सकते हो। मुझे वह भेज दो तो मैं ठीक कर दूँगा।"
            }
            val rel = Github.get("/repos/$me/$repo/releases/latest")
            val url = JSONObject(rel.second).optJSONArray("assets")?.optJSONObject(0)?.optString("browser_download_url")
            if (url.isNullOrBlank()) continue
            open(hands, url)
            return "$name तैयार है। डाउनलोड शुरू कर दिया है। खत्म होने पर उसे खोलकर इंस्टॉल कर लो।"
        }
        open(hands, "https://github.com/$me/$repo/actions")
        return "बिल्ड में ज़्यादा समय लग रहा है। GitHub का पेज खोल दिया है, वहाँ देख लो।"
    }

    private fun open(hands: Hands, url: String) {
        hands.openActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    // ---------------------------------------------------------------- the fixed Android shell

    private const val SETTINGS = """pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "App"
include(":app")
"""

    private const val ROOT = """plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
"""

    private const val PROPS = """org.gradle.jvmargs=-Xmx2g
android.useAndroidX=true
"""

    private const val APP = """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zyro.made"
    compileSdk = 34

    defaultConfig {
        applicationId = "@@PKG@@"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
"""

    private const val MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:label="@@NAME@@"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar"
        android:usesCleartextTraffic="false">
        <activity
            android:name="com.zyro.made.MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""

    private const val MAIN = """package com.zyro.made

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(3, 6, 16)
        window.navigationBarColor = Color.rgb(3, 6, 16)
        web = WebView(this)
        web.setBackgroundColor(Color.rgb(3, 6, 16))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
"""

    private const val WORKFLOW = """name: Build app
on:
  push:
    branches: [ main ]
  workflow_dispatch:
permissions:
  contents: write
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.9
      - name: Build
        shell: bash
        run: gradle assembleDebug --no-daemon --stacktrace
      - name: Release
        shell: bash
        env:
          GH_TOKEN: DOLLAR{{ secrets.GITHUB_TOKEN }}
        run: gh release create "v1" app/build/outputs/apk/debug/app-debug.apk --title "@@NAME@@" --notes "Made by Zyro"
"""
}
