package com.zyro.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Agent vision: a small picture of the current screen for the agent, so it can also see icons,
 * video controls and anything without a text label. Works on every Android version from 5 up,
 * after a one-time "Start now" permission. The picture goes to Gemini only while the agent needs it
 * and never when a password field is on screen.
 */
object Vision {
    @Volatile var on = false
    internal var projection: MediaProjection? = null
    internal var display: VirtualDisplay? = null
    internal var reader: ImageReader? = null
    internal var held: Image? = null
    internal var thread: HandlerThread? = null
    @Volatile internal var lastUse = 0L
    internal val lock = Any()

    fun ask(ctx: Context): String {
        if (on) return "स्क्रीन देखना पहले से चालू है।"
        val i = Intent(ctx, VisionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "ठीक है, अनुमति वाली स्क्रीन खुल रही है। उसमें 'Start now' दबाओ।"
    }

    fun stop(ctx: Context): String {
        ctx.stopService(Intent(ctx, VisionService::class.java))
        return "स्क्रीन देखना बंद।"
    }

    /** The real screen size in pixels. */
    fun realSize(ctx: Context): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    /** The current screen as a small JPEG (base64), or null when vision is off. */
    fun grab(): String? {
        if (!on) return null
        lastUse = System.currentTimeMillis()
        synchronized(lock) {
            val img = held ?: return null
            return try {
                val p = img.planes[0]
                val w = img.width
                val h = img.height
                val pad = (p.rowStride - p.pixelStride * w) / p.pixelStride
                val bmp = Bitmap.createBitmap(w + pad, h, Bitmap.Config.ARGB_8888)
                p.buffer.rewind()
                bmp.copyPixelsFromBuffer(p.buffer)
                val crop = Bitmap.createBitmap(bmp, 0, 0, w, h)
                val bos = ByteArrayOutputStream()
                crop.compress(Bitmap.CompressFormat.JPEG, 62, bos)
                Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Transparent screen that shows Android's "Start recording or casting?" question once. */
class VisionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), 77)
        } catch (e: Exception) {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, VisionService::class.java).putExtra("code", resultCode).putExtra("data", data)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } catch (e: Exception) { /* ignore */ }
        }
        finish()
    }
}

class VisionService : Service() {
    private var hd: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("zyro_vision", "Zyro screen vision", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(this, "zyro_vision")
                .setContentTitle("Zyro agent vision is on")
                .setContentText("The agent can see the screen while it works. Turn it off in Zyro settings.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(4242, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(4242, n)

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            val w = 540
            val h = (dm.heightPixels * 540f / dm.widthPixels).toInt().coerceAtLeast(200)
            val t = HandlerThread("zyro-vision")
            t.start()
            val handler = Handler(t.looper)
            hd = handler
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Vision.on = false
                    stopSelf()
                }
            }, handler)
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            r.setOnImageAvailableListener({ rd ->
                val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
                if (img != null) synchronized(Vision.lock) {
                    Vision.held?.close()
                    Vision.held = img
                }
            }, handler)
            val vd = mp.createVirtualDisplay("zyro-vision", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
            Vision.projection = mp
            Vision.reader = r
            Vision.display = vd
            Vision.thread = t
            Vision.lastUse = System.currentTimeMillis()
            Vision.on = true
            // switch itself off after 30 quiet minutes (saves battery)
            val idle = object : Runnable {
                override fun run() {
                    if (System.currentTimeMillis() - Vision.lastUse > 30 * 60_000L) stopSelf() else handler.postDelayed(this, 60_000L)
                }
            }
            handler.postDelayed(idle, 60_000L)
            try { Assistant.get(applicationContext).note("${Prefs.friendName}: (एजेंट अब स्क्रीन देख सकता है)") } catch (e: Exception) {}
        } catch (e: Exception) {
            Vision.on = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Vision.on = false
        synchronized(Vision.lock) {
            try { Vision.held?.close() } catch (e: Exception) {}
            Vision.held = null
        }
        try { Vision.display?.release() } catch (e: Exception) {}
        try { Vision.reader?.close() } catch (e: Exception) {}
        try { Vision.projection?.stop() } catch (e: Exception) {}
        try { Vision.thread?.quitSafely() } catch (e: Exception) {}
        Vision.display = null
        Vision.reader = null
        Vision.projection = null
        Vision.thread = null
        super.onDestroy()
    }
}
