package com.zyro.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.Locale

/**
 * Listens for one special SMS: "micro find <your secret code>".
 * Only that exact text with your own code makes the phone ring and reply with its location.
 */
class SmsFindReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val app = ctx.applicationContext
            Prefs.init(app)
            val code = Prefs.findCode.trim().lowercase(Locale.ROOT)
            if (code.length < 4) return
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
            val from = msgs.firstOrNull()?.originatingAddress ?: return
            val body = msgs.joinToString("") { it.messageBody ?: "" }.trim().lowercase(Locale.ROOT)
            when (body) {
                "micro find $code" ->
                    app.startForegroundService(Intent(app, ListenService::class.java).setAction(ListenService.ACTION_FIND).putExtra("to", from))
                "micro stop $code" ->
                    app.startService(Intent(app, ListenService::class.java).setAction(ListenService.ACTION_FIND_STOP))
            }
        } catch (e: Exception) {
            // never crash on an incoming SMS
        }
    }
}
