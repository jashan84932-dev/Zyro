package com.zyro.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Starts Zyro after the phone restarts, if you switched that on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            Prefs.init(ctx.applicationContext)
            if (!Prefs.autoStart) return
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            ListenService.start(ctx.applicationContext)
        } catch (e: Exception) {
            // some phones block this; the app can still be started by hand
        }
    }
}
