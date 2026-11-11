package com.zyro.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator

/** Rings the phone loudly when the owner texts "micro find <code>" from another phone. */
object FindMe {
    private var player: MediaPlayer? = null
    private var oldVolume = -1

    fun ring(ctx: Context) {
        stop(ctx)
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setDataSource(ctx, uri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // no sound available
        }
        try {
            (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        } catch (e: Exception) {
            // ignore
        }
    }

    fun stop(ctx: Context) {
        try { player?.stop(); player?.release() } catch (e: Exception) { /* ignore */ }
        player = null
        try { (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() } catch (e: Exception) { /* ignore */ }
        try {
            if (oldVolume >= 0) {
                (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0)
            }
        } catch (e: Exception) {
            // ignore
        }
        oldVolume = -1
    }
}
