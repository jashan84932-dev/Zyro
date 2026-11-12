package com.zyro.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps Zyro alive and listening when the app is minimised or the screen is off.
 * Start it once; it keeps running (with a small notification) until you press Stop.
 */
class ListenService : Service() {

    companion object {
        private const val CHANNEL = "micro_listen"
        private const val NOTIF_ID = 77
        const val ACTION_STOP = "com.zyro.app.STOP"
        const val ACTION_FIND = "com.zyro.app.FIND"
        const val ACTION_FIND_STOP = "com.zyro.app.FIND_STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ListenService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ListenService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FIND_STOP) {
            FindMe.stop(this)
            if (!Assistant.get(this).active) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_FIND) {
            try {
                goForeground()
            } catch (e: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
            FindMe.ring(this)
            val to = intent.getStringExtra("to")
            if (to != null) {
                val app = applicationContext
                Thread {
                    try {
                        val l = Safety.fresh(app)
                        val text = "Zyro: your phone is ringing now. " + if (l != null) "Location: " + Safety.link(l) else "Location not found yet."
                        val sm = android.telephony.SmsManager.getDefault()
                        sm.sendMultipartTextMessage(to, null, sm.divideMessage(text), null, null)
                    } catch (e: Exception) {
                        // the ringing is the important part
                    }
                }.start()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                FindMe.stop(this)
                if (!Assistant.get(this).active) stopSelf()
            }, 90_000)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            Assistant.get(this).stopConversation()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            goForeground()
        } catch (e: Exception) {
            // for example the microphone permission is missing
            stopSelf()
            return START_NOT_STICKY
        }
        Assistant.get(this).startConversation()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Zyro listening", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Zyro is listening")
            .setContentText("Just talk to me. Tap Stop to turn me off.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val cam = Build.VERSION.SDK_INT >= 30 &&
                checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if (cam) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0))
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        val a = Assistant.get(this)
        if (a.active) a.stopConversation()
        super.onDestroy()
    }
}
