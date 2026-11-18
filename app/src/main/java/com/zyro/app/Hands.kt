package com.zyro.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.ContactsContract
import android.provider.Settings
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything the friend can physically do on the phone.
 * These are generic building blocks (see the screen, tap, type, scroll, open things) –
 * the AI combines them itself, so there is no fixed list of voice commands.
 * Call from a background thread (gestures wait for a callback on the main thread).
 */
class Hands(private val app: Context) {

    @Volatile
    var service: AccessibilityService? = null

    private val ctx: Context get() = service ?: app

    private val needService =
        "The Accessibility service is off, so I can't see or control the screen. " +
            "Ask the user to turn Zyro on in Accessibility settings."

    /** Starts a screen of this app even while Zyro is in the background (uses the accessibility service). */
    fun openActivity(intent: Intent) = launch(intent)

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    // ---------- Seeing the screen ----------

    fun readScreen(): String {
        val svc = service ?: return needService
        val root = svc.rootInActiveWindow ?: return "The screen can't be read right now (maybe it is locked or loading)."
        val sb = StringBuilder("App: ${root.packageName}\n")
        var count = 0

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= 120 || depth > 30) return
            val label = if (n.isPassword) {
                "(password field)"
            } else {
                listOfNotNull(n.text?.toString()?.take(80), n.contentDescription?.toString()?.take(80))
                    .filter { it.isNotBlank() }
                    .joinToString(" | ")
            }
            val interesting = label.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable
            if (interesting && n.isVisibleToUser) {
                val r = Rect()
                n.getBoundsInScreen(r)
                sb.append("- ")
                if (label.isNotEmpty()) sb.append('"').append(label).append('"') else sb.append("(no label)")
                val flags = ArrayList<String>()
                if (n.isClickable) flags.add("clickable")
                if (n.isEditable) flags.add("editable")
                if (n.isScrollable) flags.add("scrollable")
                if (n.isChecked) flags.add("checked")
                if (flags.isNotEmpty()) sb.append(" [").append(flags.joinToString(",")).append("]")
                sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
                count++
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }

        walk(root, 0)
        if (count == 0) sb.append("(nothing readable on screen – it may be a game or video. Try tap with coordinates.)\n")
        if (count >= 120) sb.append("(list cut off – scroll for more)\n")
        sb.append("Coordinates are x,y in pixels of the screen centre of each item.")
        return sb.toString()
    }

    // ---------- Touching the screen ----------

    fun click(label: String): String {
        val svc = service ?: return needService
        val root = svc.rootInActiveWindow ?: return "The screen can't be read right now."
        if (label.isBlank()) return "No label given."
        // exact label matches first ("Send" before "Send later"), then partial ones
        val found = root.findAccessibilityNodeInfosByText(label).sortedBy { nd ->
            val t = (nd.text ?: nd.contentDescription)?.toString()?.trim() ?: ""
            if (t.equals(label.trim(), ignoreCase = true)) 0 else 1
        }
        for (node in found) {
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "Clicked \"$label\"."
            }
        }
        // nothing clickable around the text: tap the middle of the visible text itself
        for (node in found) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (node.isVisibleToUser && r.width() > 0 && r.height() > 0) {
                val res = tap(r.centerX(), r.centerY())
                if (res.isNotBlank() && !res.startsWith("Invalid")) return "Tapped \"$label\"."
            }
        }
        return "Couldn't find a clickable \"$label\". Use read_screen and tap by coordinates instead."
    }

    fun tap(x: Int, y: Int): String {
        if (x < 0 || y < 0) return "Invalid coordinates."
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(p, 60L)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return "Invalid coordinates."
        val p = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return gesture(p, durationMs.toLong().coerceIn(100L, 2000L))
    }

    private fun gesture(path: Path, durationMs: Long): String {
        val svc = service ?: return needService
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val sent = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        if (!sent) return "The gesture could not be sent."
        latch.await(3, TimeUnit.SECONDS)
        return if (ok.get()) "Done." else "The gesture was cancelled."
    }

    // ---------- Typing and scrolling ----------

    /** Replaces the text in the focused box (or the first text box on screen). */
    fun typeText(text: String): String {
        val svc = service ?: return needService
        val root = svc.rootInActiveWindow ?: return "The screen can't be read right now."
        var field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (field == null) {
            field = findFirst(root) { it.isEditable && it.isVisibleToUser }
            field?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        if (field == null) return "No text box found. Tap one first."
        if (field.isPassword) return "That is a password field. I don't type passwords – ask the user to type it."
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed." else "Couldn't type there."
    }

    fun scroll(down: Boolean): String {
        val svc = service ?: return needService
        val root = svc.rootInActiveWindow ?: return "The screen can't be read right now."
        val node = findFirst(root) { it.isScrollable } ?: return "Nothing scrollable on this screen."
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (node.performAction(action)) "Scrolled." else "Couldn't scroll (maybe already at the end)."
    }

    fun press(button: String): String {
        val svc = service ?: return needService
        val action = when (button.lowercase(Locale.ROOT)) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            "power_menu" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> return "Unknown button. Use back, home, recents, notifications, quick_settings, lock_screen, screenshot or power_menu."
        }
        return if (svc.performGlobalAction(action)) "Pressed $button." else "Couldn't press $button."
    }

    private fun findFirst(
        n: AccessibilityNodeInfo,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (pred(n)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findFirst(c, pred)?.let { return it }
        }
        return null
    }

    // ---------- Opening things ----------

    private fun squash(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace("\u093C", "")
            .replace(Regex("[\\s\\-_.]+"), "")

    /** Spoken names (also Hindi spellings the speech recogniser produces) -> real app names. */
    private val appAliases: Map<String, List<String>> = mapOf(
        "यूट्यूब" to listOf("youtube"), "यूटयूब" to listOf("youtube"), "यूटूब" to listOf("youtube"), "यट्यूब" to listOf("youtube"),
        "व्हाट्सएप" to listOf("whatsapp"), "व्हाट्सऐप" to listOf("whatsapp"), "वाट्सएप" to listOf("whatsapp"),
        "वाट्सऐप" to listOf("whatsapp"), "वॉट्सऐप" to listOf("whatsapp"), "वॉट्सएप" to listOf("whatsapp"),
        "व्हॉट्सऐप" to listOf("whatsapp"), "व्हॉट्सएप" to listOf("whatsapp"), "वाटसअप" to listOf("whatsapp"),
        "कैमरा" to listOf("camera"), "कैमेरा" to listOf("camera"),
        "गैलरी" to listOf("gallery", "photos"), "फोटो" to listOf("photos", "gallery"), "फोटोज" to listOf("photos", "gallery"),
        "क्रोम" to listOf("chrome"), "ब्राउज़र" to listOf("chrome", "browser", "internet"), "ब्राउजर" to listOf("chrome", "browser", "internet"),
        "गूगल" to listOf("google"), "जीमेल" to listOf("gmail"), "मेल" to listOf("gmail", "mail"),
        "मैप्स" to listOf("maps"), "मैप" to listOf("maps"), "गूगलमैप" to listOf("maps"), "गूगलमैप्स" to listOf("maps"),
        "सेटिंग्स" to listOf("settings"), "सेटिंग" to listOf("settings"), "सेटिंगस" to listOf("settings"),
        "फोन" to listOf("phone", "dialer"), "डायलर" to listOf("phone", "dialer"), "डायलर" to listOf("phone", "dialer"),
        "मैसेज" to listOf("messages", "messaging", "sms"), "मैसेजेस" to listOf("messages", "messaging", "sms"), "एसएमएस" to listOf("messages", "messaging", "sms"),
        "कैलकुलेटर" to listOf("calculator"), "कैल्कुलेटर" to listOf("calculator"),
        "घड़ी" to listOf("clock"), "क्लॉक" to listOf("clock"), "अलार्म" to listOf("clock"),
        "कैलेंडर" to listOf("calendar"), "कॉन्टैक्ट" to listOf("contacts"), "कॉन्टैक्ट्स" to listOf("contacts"), "कांटेक्ट" to listOf("contacts"),
        "इंस्टाग्राम" to listOf("instagram"), "इंस्टा" to listOf("instagram"), "फेसबुक" to listOf("facebook"), "फ़ेसबुक" to listOf("facebook"),
        "स्नैपचैट" to listOf("snapchat"), "टेलीग्राम" to listOf("telegram"), "स्पॉटिफाई" to listOf("spotify"), "स्पोटिफाई" to listOf("spotify"),
        "नेटफ्लिक्स" to listOf("netflix"), "अमेज़न" to listOf("amazon"), "अमेजन" to listOf("amazon"),
        "फ्लिपकार्ट" to listOf("flipkart"), "पेटीएम" to listOf("paytm"), "फोनपे" to listOf("phonepe"), "फोनपै" to listOf("phonepe"),
        "गूगलपे" to listOf("google pay", "gpay", "pay"), "जीपे" to listOf("google pay", "gpay", "pay"),
        "प्लेस्टोर" to listOf("play store"), "प्लेस्टोर" to listOf("play store"), "ट्विटर" to listOf("x", "twitter"),
        "ज़ूम" to listOf("zoom"), "जूम" to listOf("zoom"), "मीट" to listOf("meet"), "गूगलमीट" to listOf("meet"),
        "फाइल" to listOf("files"), "फाइल्स" to listOf("files"), "फाइलमैनेजर" to listOf("files", "file manager", "my files"),
        "नोट्स" to listOf("notes", "keep"), "म्यूजिक" to listOf("music", "youtube music", "spotify"),
        "वीडियो" to listOf("video", "youtube"), "टॉर्च" to listOf("flashlight", "torch"),
        "हॉटस्टार" to listOf("jiohotstar", "hotstar"), "हॉटस्टार" to listOf("jiohotstar", "hotstar"), "जियोहॉटस्टार" to listOf("jiohotstar", "hotstar"),
        "जियोहोटस्टार" to listOf("jiohotstar", "hotstar"), "होटस्टार" to listOf("jiohotstar", "hotstar"), "जियोसिनेमा" to listOf("jiocinema", "jiohotstar"),
        "प्राइम" to listOf("prime video", "amazon prime"), "प्राइमवीडियो" to listOf("prime video", "amazon prime"),
        "ज़ोमैटो" to listOf("zomato"), "जोमैटो" to listOf("zomato"), "ज़ोमेटो" to listOf("zomato"), "स्विगी" to listOf("swiggy"),
        "ओला" to listOf("ola"), "उबर" to listOf("uber"), "रैपिडो" to listOf("rapido"),
        "एमएक्सप्लेयर" to listOf("mx player"), "सोनीलिव" to listOf("sonyliv", "sony liv"), "जी5" to listOf("zee5"), "ज़ी5" to listOf("zee5"),
        "शेयरचैट" to listOf("sharechat"), "मोज" to listOf("moj"), "मिंत्रा" to listOf("myntra"), "मीशो" to listOf("meesho"),
        "बुकमायशो" to listOf("bookmyshow"), "आईआरसीटीसी" to listOf("irctc"), "लिंक्डइन" to listOf("linkedin"), "पिनटेरेस्ट" to listOf("pinterest"),
        "डिस्कॉर्ड" to listOf("discord"), "रेडिट" to listOf("reddit"), "हॉटस्टारटीवी" to listOf("jiohotstar", "hotstar")
    )

    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 2) return 99
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    fun openApp(name: String): String {
        val q = squash(name)
        if (q.isEmpty()) return "No app name given."
        val wanted = ((appAliases[q] ?: appAliases.entries.firstOrNull { squash(it.key) == q }?.value ?: emptyList()) + name.trim())
            .map { squash(it) }.filter { it.isNotEmpty() }.distinct()
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        fun label(i: ResolveInfo) = squash(i.loadLabel(pm).toString())
        val match: ResolveInfo? =
            wanted.firstNotNullOfOrNull { w -> apps.firstOrNull { label(it) == w } }
                ?: wanted.firstNotNullOfOrNull { w -> apps.filter { w.length >= 3 && label(it).contains(w) }.minByOrNull { label(it).length } }
                ?: wanted.firstNotNullOfOrNull { w -> apps.firstOrNull { w.length >= 4 && squash(it.activityInfo.packageName).contains(w) } }
                // speech often drops or swaps a letter ("instagrm", "whatsap"): accept a close spelling
                ?: wanted.filter { it.length >= 5 }.firstNotNullOfOrNull { w -> apps.filter { editDistance(label(it), w) <= (if (w.length >= 8) 2 else 1) }.minByOrNull { editDistance(label(it), w) } }
        if (match == null) return "No installed app matches \"$name\"."
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "That app can't be launched."
        launch(intent)
        return "Opened ${match.loadLabel(pm)}."
    }

    // ---------- Device controls ----------

    fun volume(action: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        return when (action.lowercase(Locale.ROOT)) {
            "up" -> {
                repeat(3) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, if (it == 2) AudioManager.FLAG_SHOW_UI else 0) }
                "Volume increased."
            }
            "down" -> {
                repeat(3) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, if (it == 2) AudioManager.FLAG_SHOW_UI else 0) }
                "Volume decreased."
            }
            "max" -> {
                am.setStreamVolume(stream, am.getStreamMaxVolume(stream), AudioManager.FLAG_SHOW_UI)
                "Volume set to maximum."
            }
            else -> "Unknown volume action. Use up, down or max."
        }
    }

    fun torch(on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight on this phone."
            cm.setTorchMode(id, on)
            if (on) "Flashlight is on." else "Flashlight is off."
        } catch (e: Exception) {
            "Couldn't use the flashlight: ${e.message}"
        }
    }

    /** level: 0-100, or relative change via delta (e.g. +20 / -20). */
    fun brightness(level: Int?, delta: Int = 0): String {
        return try {
            if (!Settings.System.canWrite(ctx)) {
                val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return "Brightness needs one permission. Please allow Zyro to modify system settings, then ask again."
            }
            val cr = ctx.contentResolver
            val now = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val target = if (level != null) level * 255 / 100 else now + delta * 255 / 100
            val v = target.coerceIn(5, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
            "Brightness set to about ${v * 100 / 255} percent."
        } catch (e: Exception) {
            "Couldn't change brightness: ${e.message}"
        }
    }

    fun openSettings(page: String): String {
        val action = when (page.lowercase(Locale.ROOT)) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            launch(Intent(action))
            "Opened the $page settings page."
        } catch (e: Exception) {
            "Couldn't open that settings page."
        }
    }

    fun hasContactsPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Returns (name, number) of the first contact whose name contains [name], or null. */
    private val KIN = listOf(
        listOf("mummy", "mom", "mama", "maa", "ammi", "mother", "मम्मी", "माँ", "मां", "माता", "अम्मी", "मॉम", "मम्मा"),
        listOf("papa", "dad", "daddy", "pita", "father", "पापा", "पिता", "डैड", "अब्बू", "abbu", "पप्पा"),
        listOf("bhai", "bhaiya", "brother", "भाई", "भैया", "bro"),
        listOf("didi", "sister", "behen", "bahan", "दीदी", "बहन", "बहिन"),
        listOf("dadi", "दादी"), listOf("nani", "नानी"), listOf("dada", "दादा"), listOf("nana", "नाना"),
        listOf("wife", "biwi", "patni", "बीवी", "पत्नी"),
        listOf("husband", "pati", "पति")
    )

    /** Hindi letters to rough English letters, so a spoken Hindi name can be matched with a contact saved in English (and back). */
    private fun translit(t: String): String {
        val cons = mapOf('क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
            'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
            'ष' to "sh", 'स' to "s", 'ह' to "h")
        val vow = mapOf('अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o")
        val mat = mapOf('ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ं' to "n", 'ँ' to "n")
        val u = t.replace("\u093c", "")
        val sb = StringBuilder()
        var i = 0
        while (i < u.length) {
            val ch = u[i]
            val c = cons[ch]
            if (c != null) {
                sb.append(c)
                val nx = u.getOrNull(i + 1)
                val end = nx == null || !(nx in 'ऀ'..'ॿ')
                if (nx == '्') { i += 2; continue }
                if (nx != null && mat.containsKey(nx)) { sb.append(mat[nx]); i += 2; continue }
                // inherent "a", dropped at the end of a word
                if (!end) sb.append("a")
            } else if (vow.containsKey(ch)) sb.append(vow[ch])
            else if (ch == '़' || ch == '्') { }
            else sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /** A sound key: "Lucky", "लकी" and "Lakki" all become "lk". */
    private fun phon(t: String): String {
        var x = translit(t.lowercase()).replace(Regex("[^a-z]"), "")
        x = x.replace("ch", "C").replace("sh", "S").replace("kh", "k").replace("gh", "g").replace("ph", "f").replace("th", "t").replace("dh", "d").replace("bh", "b").replace("jh", "j")
        x = x.replace("c", "k").replace("q", "k").replace("w", "v").replace("z", "j").replace("x", "ks")
        x = x.replace(Regex("[aeiouyh]"), "")
        return x.replace(Regex("(.)\\1+"), "$1")
    }

    /** Best matching contact for a spoken name: knows family words in both scripts, prefers exact / starred / often-called / mobile numbers. */
    fun findContact(name: String): Pair<String, String>? {
        if (name.isBlank() || !hasContactsPermission()) return null
        val n = name.trim().lowercase()
        val terms = LinkedHashSet<String>()
        terms.add(n)
        val parts = n.split(Regex("\\s+"))
        for (g in KIN) if (g.any { it.lowercase() == n || parts.contains(it.lowercase()) }) g.forEach { terms.add(it.lowercase()) }
        var bestName: String? = null
        var bestNum: String? = null
        var bestScore = 0
        try {
            val cols = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                "starred",
                "times_contacted"
            )
            val cur = ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)
            cur?.use {
                while (it.moveToNext()) {
                    val dn = (it.getString(0) ?: "").lowercase()
                    val num = it.getString(1) ?: continue
                    if (dn.isEmpty()) continue
                    var s = 0
                    for (t in terms) {
                        val sc = when {
                            dn == t -> 100
                            dn.startsWith("$t ") -> 85
                            dn.startsWith(t) -> 75
                            dn.split(Regex("\\s+")).contains(t) -> 65
                            dn.contains(t) -> 40
                            else -> 0
                        }
                        if (sc > s) s = sc
                    }
                    if (s == 0) continue
                    if (it.getInt(3) == 1) s += 10
                    s += minOf(it.getInt(4), 50) / 5
                    if (it.getInt(2) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) s += 5
                    if (s > bestScore) { bestScore = s; bestName = it.getString(0); bestNum = num }
                }
            }
        } catch (e: Exception) {
            // fall back to the simple search below
        }
        if (bestName != null && bestNum != null) return Pair(bestName!!, bestNum!!)
        // sound-alike pass: "लकी" (Hindi letters) finds a contact saved as "Lucky" and the other way round
        try {
            val want = phon(name)
            if (want.length >= 2) {
                var pn: String? = null
                var pnum: String? = null
                var pscore = 0
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, "starred", "times_contacted"),
                    null, null, null
                )?.use {
                    while (it.moveToNext()) {
                        val dn = it.getString(0) ?: continue
                        val num = it.getString(1) ?: continue
                        val words = dn.split(Regex("\\s+")).filter { w -> w.isNotEmpty() }
                        var sc = 0
                        if (phon(dn) == want) sc = 90
                        else if (words.any { w -> phon(w) == want }) sc = 70
                        else if (want.length >= 3 && words.any { w -> phon(w).startsWith(want) }) sc = 50
                        if (sc == 0) continue
                        if (it.getInt(2) == 1) sc += 10
                        sc += minOf(it.getInt(3), 50) / 5
                        if (sc > pscore) { pscore = sc; pn = dn; pnum = num }
                    }
                }
                if (pn != null && pnum != null) return Pair(pn!!, pnum!!)
            }
        } catch (e: Exception) { }
        val cur = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%" + name.trim() + "%"),
            null
        ) ?: return null
        cur.use {
            if (it.moveToFirst()) return it.getString(0) to it.getString(1)
        }
        return null
    }

    /** Calls directly when the phone-call permission is granted, otherwise opens the dialer. */
    fun callNumber(number: String): String {
        val n = number.filter { it.isDigit() || it == '+' }
        if (n.length < 3) return "Invalid number."
        val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        return try {
            launch(Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))
            if (granted) "Calling now." else "Dialer opened with the number. The user must press call."
        } catch (e: Exception) {
            "Couldn't start the call."
        }
    }

    private val allowedSchemes = setOf(
        "http", "https", "tel", "sms", "smsto", "mailto", "geo",
        "google.navigation", "market", "whatsapp", "vnd.youtube", "spotify"
    )

    fun openUri(uri: String): String {
        val u = Uri.parse(uri.trim())
        val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return "That isn't a valid link."
        if (scheme !in allowedSchemes) return "That kind of link isn't allowed."
        return try {
            launch(Intent(Intent.ACTION_VIEW, u))
            when (scheme) {
                "tel" -> "Dialer opened with the number. The call is NOT placed until the user presses call."
                "sms", "smsto", "mailto", "whatsapp" -> "Opened the draft. Nothing is sent until the user presses send."
                else -> "Opened."
            }
        } catch (e: ActivityNotFoundException) {
            "No app on this phone can open that."
        }
    }

    fun webSearch(query: String): String {
        if (query.isBlank()) return "No search text given."
        return openUri("https://www.google.com/search?q=" + Uri.encode(query))
    }

    fun setAlarm(hour: Int, minute: Int, label: String): String {
        if (hour !in 0..23 || minute !in 0..59) return "That time is invalid."
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return try {
            launch(i)
            String.format(Locale.ENGLISH, "Alarm set for %02d:%02d.", hour, minute)
        } catch (e: ActivityNotFoundException) {
            "No clock app found."
        }
    }

    fun setTimer(seconds: Int, label: String): String {
        if (seconds !in 1..86400) return "Timer must be between 1 second and 24 hours."
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return try {
            launch(i)
            "Timer started for $seconds seconds."
        } catch (e: ActivityNotFoundException) {
            "No clock app found."
        }
    }
}
