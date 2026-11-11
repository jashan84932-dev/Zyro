package com.zyro.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Keeps the latest notifications in memory (never saved or sent anywhere) so Zyro can read them out. */
class NotifService : NotificationListenerService() {

    class LastMsg(val pkg: String, val app: String, val title: String, val text: String, val action: Notification.Action?, val at: Long)

    companion object {
        @Volatile var last: LastMsg? = null
        val recent = ArrayList<String>()
        private val MSG_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca",
            "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging"
        )
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName == packageName) return
        val n = s.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val e = n.extras ?: return
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.packageName, 0)).toString()
        } catch (ex: Exception) {
            s.packageName
        }
        if ((Prefs.readAloud || Gestures.pocketActive()) && (n.category == Notification.CATEGORY_MESSAGE || s.packageName in MSG_APPS)) {
            val say = (if (title.isBlank()) "$app पर नया मैसेज: $text" else "$app पर $title: $text").take(170)
            Assistant.get(applicationContext).announce(say)
        }
        if (n.category == Notification.CATEGORY_MESSAGE || s.packageName in MSG_APPS) {
            val act = n.actions?.firstOrNull { it.remoteInputs != null && it.remoteInputs.isNotEmpty() }
            if (act != null) {
                last = LastMsg(s.packageName, app, title, text, act, System.currentTimeMillis())
                Genius.onMessage(applicationContext, s.packageName, title, text, act)
            }
        }
        synchronized(recent) {
            recent.add("$app: $title $text".trim())
            while (recent.size > 20) recent.removeAt(0)
        }
    }
}
