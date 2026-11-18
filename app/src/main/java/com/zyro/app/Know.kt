package com.zyro.app

/**
 * What a smart person already knows about phones and apps, written down for the agent.
 * Only the parts that match the task and the app on screen are put in the prompt, so it stays fast.
 */
object Know {

    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }

    /** Words people use for things in apps (so "reel", "short", "status" mean something to the agent). */
    private val WORDS = listOf(
        listOf("reel", "रील", "reels", "short", "शॉर्ट") to "Reel/Short = a short vertical full-screen video. Instagram: Reels tab (bottom bar, film icon). YouTube: Shorts tab (bottom bar). Swipe UP (scroll_down) = next reel. Right-side icons: heart=like, speech bubble=comments, paper plane=share/send, ⋮=more, music note=audio.",
        listOf("story", "स्टोरी") to "Story = a photo/video that disappears after 24 h. Instagram: round profile pictures in a row at the top of Home; tap one to watch, tap right edge for the next.",
        listOf("status", "स्टेटस") to "WhatsApp Status = like a story. WhatsApp bottom tab 'Updates' (older versions 'Status'); contacts' statuses are listed under 'Recent updates'; tap to watch.",
        listOf("dm", "inbox", "message", "मैसेज") to "DM = private chat. Instagram: paper-plane icon top-right. Facebook: Messenger. Type in the box 'Message…' and tap Send only if the user asked to send.",
        listOf("screenshot", "स्क्रीनशॉट") to "Screenshot = power + volume-down; Zyro can also do it by the global action 'screenshot' if available. Saved in Gallery > Screenshots.",
        listOf("hotspot", "हॉटस्पॉट") to "Hotspot = Settings > Network > Hotspot & tethering > Wi-Fi hotspot toggle.",
        listOf("playlist", "प्लेलिस्ट") to "Playlist = saved list of videos/songs. YouTube: Library tab. Spotify: Your Library.",
        listOf("subscribe", "सब्सक्राइब") to "Subscribe (YouTube) = follow a channel (red button under the video next to the channel name). This is free. Do NOT confuse with paid subscription/Premium.",
        listOf("follow", "फॉलो") to "Follow = Instagram/Facebook blue 'Follow' button on a profile.",
        listOf("like", "लाइक") to "Like = heart (Instagram) or thumbs-up (YouTube/Facebook) icon under/next to the post.",
        listOf("comment", "कमेंट") to "Comment = speech-bubble icon; the box says 'Add a comment…'; tap the arrow/Post to send (only if the user asked).",
    )

    /** How-to knowledge keyed by what the user wants to do. */
    private val HOWTO = listOf(
        listOf("download", "डाउनलोड", "save video", "वीडियो सेव") to
            "DOWNLOADING VIDEOS: YouTube: open the video (not full screen); under the player is a row of buttons (Like, Dislike, Share, Remix, Download, Save...) that scrolls sideways, scroll_find \"Download\" swipes it for you. Tap Download, pick a quality if asked (the default is fine). It is done when the button reads Downloaded or shows a tick. Some videos do not allow downloading and have no button: then tell the user honestly. Do not open downloader websites or apps (unsafe). Instagram: on a public reel tap the paper-plane (Share), then look for Download in the sheet; if it is missing the reel cannot be saved. Facebook: ⋮ > Save video (saves inside Facebook). WhatsApp: media goes to Gallery when auto-download is on; for a status use the download/save icon if shown. Telegram: tap the video then ⋮ > Save to Gallery.",
        listOf("record", "रिकॉर्ड") to "Screen recording = Quick Settings tile 'Screen recorder' (swipe down twice), then Start. Zyro cannot do it silently.",
        listOf("alarm", "अलार्म", "timer", "टाइमर") to "Alarms and timers: the Clock app; Zyro normally sets them directly without the UI.",
        listOf("wifi", "वाईफाई", "वाई-फाई", "bluetooth", "ब्लूटूथ", "torch", "टॉर्च", "flashlight") to "Toggles live in Quick Settings: swipe down from the top (twice for the full panel) and tap the tile. Long-press a tile for its settings.",
        listOf("uninstall", "अनइंस्टॉल", "delete app") to "Uninstall: Settings > Apps > the app > Uninstall, then OK. Ask the user first.",
        listOf("install", "इंस्टॉल", "play store", "प्ले स्टोर") to "Install: Play Store, search bar on top, tap the app, green 'Install', wait until 'Open' shows. Check the app name and developer before installing.",
        listOf("photo", "फोटो", "gallery", "गैलरी", "camera", "कैमरा") to "Camera app: shutter = big round button at the bottom; the small round thumbnail at a corner opens the last photo; Gallery/Photos app lists everything by date.",
        listOf("call", "कॉल", "dial") to "Calls: use the Phone app, or Zyro's contact call. Contacts may be saved in English while the user speaks Hindi (लकी = Lucky); try both spellings.",
        listOf("whatsapp", "व्हाट्सएप", "व्हाट्सऐप") to "WhatsApp: top bar search (magnifier), tap the chat, box 'Type a message', the green Send arrow. Voice note = hold the mic. Attach = paper-clip. Calls = phone/video icons on the chat top bar.",
        listOf("music", "song", "गाना", "गाने", "gaana") to "Music: Spotify/YouTube Music/Gaana — Search, type the song, tap the first matching track, then Play. For YouTube, add 'song' to the query.",
        listOf("navigate", "रास्ता", "direction", "डायरेक्शन", "map") to "Maps: search box 'Search here', choose the result, 'Directions', pick the mode, then 'Start'.",
    )

    /** Extra, compact phone-use skills that apply to every task. */
    private const val CORE = "PHONE SKILLS: (1) Prefer the app's search box over scrolling. (2) A bottom bar of 4-5 icons = the app's main sections; the top-right icons are usually search/notifications/menu. (3) ⋮ or ⋯ = More options; ≡ = menu; ← or < = back; ✕ = close. (4) A bottom sheet or dialog must be dealt with before anything behind it works. (5) Loading spinner = use wait, do not tap. (6) If an element is not in the list, it may need a scroll or a swipe; rows of buttons can scroll sideways. (7) Be fast: one decisive action per step, no repeating something that did nothing."

    /** Returns the knowledge that fits this task and this app, or an empty string. */
    fun forTask(job: String, pkg: String): String {
        val t = job.lowercase()
        val sb = StringBuilder()
        sb.append(CORE).append('\n')
        for ((keys, text) in WORDS) if (keys.any { t.contains(it) }) sb.append(text).append('\n')
        for ((keys, text) in HOWTO) if (keys.any { t.contains(it) }) sb.append(text).append('\n')
        val app = when {
            pkg.contains("facebook.katana") || pkg.contains("facebook.lite") -> "Facebook: bottom/top tabs Home, Reels/Video, Friends, Marketplace, Notifications, Menu. Search = magnifier at the top. A post's Like/Comment/Share are under it."
            pkg.contains("telegram") -> "Telegram: search = magnifier at the top; chats list; message box 'Message'; send arrow on the right. Channels have no message box."
            pkg.contains("snapchat") -> "Snapchat opens on the camera; swipe right for chats, left for stories. The big circle is the shutter."
            pkg.contains("gm") && pkg.contains("google.android") -> "Gmail: search bar at the top ('Search in mail'); Compose = the pencil button bottom-right; the To field, Subject, then body; Send = paper-plane at the top right (only if the user asked to send)."
            pkg.contains("zomato") || pkg.contains("swiggy") -> "Food apps: search the dish or restaurant at the top, open the restaurant, ADD on the item, then View Cart. NEVER tap Place Order or Pay: tell the user the cart is ready."
            pkg.contains("ola") || pkg.contains("uber") || pkg.contains("rapido") -> "Ride apps: set Drop (where to), pick the ride type, then stop before Confirm/Book unless the user clearly said to book."
            pkg.contains("dialer") || pkg.contains("contacts") || pkg.contains("incallui") -> "Phone: keypad icon shows the dial pad; green phone button calls; contacts are searched with the box at the top."
            pkg.contains("deskclock") || pkg.contains("clock") -> "Clock: tabs Alarm, Clock, Timer, Stopwatch at the bottom; + adds an alarm; set the time, then OK/Save."
            pkg.contains("calculator") -> "Calculator: tap the number and operator keys one by one, = for the result; read the result from the top."
            pkg.contains("photos") || pkg.contains("gallery") -> "Gallery: photos are listed newest first; tap one to open; the share icon is at the bottom; trash icon deletes (ask first)."
            pkg.contains("camera") -> "Camera: the big round button takes the photo; switch cameras with the circular-arrows icon; modes (Photo, Video, Portrait) swipe along the bottom."
            else -> null
        }
        if (app != null) sb.append(app).append('\n')
        if (pkg.contains("instagram") && has(t, "reel", "रील")) sb.append("Open Reels from the bottom bar (film/clapper icon), not from Home.\n")
        return sb.toString().take(2400)
    }
}
