package com.zyro.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings

/** "Zyro check": finds out what is switched off or broken and says exactly what to fix. */
object Health {
    fun run(ctx: Context, hands: Hands): String {
        val bad = ArrayList<String>()
        val good = ArrayList<String>()
        fun chk(ok: Boolean, okText: String, badText: String) { if (ok) good.add(okText) else bad.add(badText) }

        chk(hands.service != null, "स्क्रीन पढ़ना और टैप करना चालू",
            "Accessibility बंद है: Settings → Accessibility में Zyro चालू करो (एक बार बंद करके फिर चालू भी करके देखो)")
        // the service can be "on" in Settings but dead; reading the live screen proves it really works
        if (hands.service != null) {
            val live = try { hands.service?.rootInActiveWindow != null } catch (e: Exception) { false }
            chk(live, "स्क्रीन सच में पढ़ी जा रही है",
                "Accessibility चालू दिखती है पर स्क्रीन नहीं पढ़ पा रही: उसे एक बार बंद करके फिर चालू करो")
        }
        chk(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED, "माइक की अनुमति है",
            "माइक की अनुमति नहीं है: फोन Settings → Apps → Zyro → Permissions → Microphone")
        chk(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED, "कैमरे की अनुमति है",
            "कैमरे की अनुमति नहीं है: Air control और फोटो के काम नहीं करेंगे")
        chk(try { Settings.canDrawOverlays(ctx) } catch (e: Exception) { true }, "स्क्रीन के ऊपर दिखाने की अनुमति है",
            "'Display over other apps' बंद है: Air control का कर्सर नहीं दिखेगा")
        chk(try { (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains(ctx.packageName) } catch (e: Exception) { true },
            "नोटिफ़िकेशन पढ़ना चालू", "Notification access बंद है: नोटिफ़िकेशन वाले काम नहीं चलेंगे")
        chk(try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) } catch (e: Exception) { true },
            "बैटरी सेवर Zyro को नहीं रोकेगा", "बैटरी सेवर Zyro को बीच में बंद कर सकता है: Battery में Zyro को 'No restrictions' करो")

        if (!Prefs.hasGemini) {
            bad.add("Gemini key या server नहीं है: Settings में key डालो")
        } else {
            val t0 = System.currentTimeMillis()
            val r = Brain.quickJson("Reply with exactly {\"a\":1}", 20, 12000, true)
            val ms = System.currentTimeMillis() - t0
            if (r != null) good.add("AI से बात हो रही है (${ms} ms, ${if (Prefs.viaProxy) "सर्वर से" else "सीधे"})")
            else bad.add(Prefs.explainFail(Brain.lastFail, ""))
        }
        // the second quick call shows whether a fast reply is steady or only the first one was quick
        if (Prefs.hasGemini && bad.none { it.startsWith("Gemini") }) {
            val t1 = System.currentTimeMillis()
            if (Brain.quickJson("Reply with exactly {\"a\":2}", 20, 12000, true) != null) {
                val ms2 = System.currentTimeMillis() - t1
                if (ms2 > 4000) bad.add("AI का जवाब धीमा है (${ms2} ms): नेट कमज़ोर है, इसलिए एजेंट और Live धीमे चलेंगे")
            }
        }
        good.add(if (Vision.on) "एजेंट विज़न चालू" else "एजेंट विज़न बंद (बिना नाम वाले बटन के लिए Settings में चालू करो)")

        val report = buildString {
            append("ZYRO CHECK\n")
            good.forEach { append("OK  ").append(it).append('\n') }
            bad.forEach { append("FIX ").append(it).append('\n') }
        }
        try { Assistant.get(ctx).note(report.trim()) } catch (e: Exception) {}
        return if (bad.isEmpty()) "सब ठीक है। ${good.size} चीज़ें जाँची, कोई दिक्कत नहीं मिली।"
        else "${bad.size} चीज़ ठीक करनी है। " + bad.take(3).mapIndexed { i, s -> "${i + 1}: $s" }.joinToString("। ") + "। पूरी लिस्ट लॉग में है।"
    }
}
