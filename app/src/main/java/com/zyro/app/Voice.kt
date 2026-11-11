package com.zyro.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The friend's voice.
 * Default: the phone's built-in Hindi voice.
 * Custom voice: an ElevenLabs voice (your cloned/chosen voice) when enabled in settings.
 * If the custom voice fails (no internet, wrong key), it falls back to the built-in voice.
 */
class Voice(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile var hindiAvailable = true
        private set
    private var player: MediaPlayer? = null
    private var onDone: (() -> Unit)? = null
    private var token = 0
    /** Language tag used by the built-in voice for the next sentences (translator). Null = Hindi. */
    @Volatile var overrideLang: String? = null

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.forLanguageTag("hi-IN"))
                hindiAvailable = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (Prefs.jarvisMode) {
                    // deeper, slightly slower and calmer
                    tts?.setPitch(0.78f)
                    tts?.setSpeechRate(0.93f)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { finish(utteranceId) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { finish(utteranceId) }
                })
                ttsReady = true
            }
        }
    }

    /** Speaks [text], then calls [done] on the main thread. */
    fun speak(text: String, done: () -> Unit) {
        main.post {
            stopNow()
            val my = ++token
            onDone = done
            val clean = text.take(1500)
            if (overrideLang == null && Prefs.customVoice && Prefs.voiceKey.isNotBlank() && Prefs.voiceId.isNotBlank()) {
                Thread {
                    val file = fetchCustom(clean)
                    main.post {
                        if (my != token) return@post
                        if (file != null && playFile(file, my)) return@post
                        builtIn(clean, my)
                    }
                }.start()
            } else {
                builtIn(clean, my)
            }
        }
    }

    fun stop() {
        main.post {
            token++
            onDone = null
            stopNow()
        }
    }

    fun destroy() {
        stopNow()
        tts?.shutdown()
    }

    // ---------- internals (main thread) ----------

    private fun stopNow() {
        tts?.stop()
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private fun finish(utteranceId: String?) {
        val my = utteranceId?.toIntOrNull() ?: return
        main.post {
            if (my != token) return@post
            val cb = onDone
            onDone = null
            cb?.invoke()
        }
    }

    private fun builtIn(text: String, my: Int) {
        if (!ttsReady) {
            val cb = onDone; onDone = null; cb?.invoke()
            return
        }
        try { tts?.language = Locale.forLanguageTag(overrideLang ?: "hi-IN") } catch (e: Exception) { /* keep current */ }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, my.toString())
    }

    private fun playFile(file: File, my: Int): Boolean {
        return try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .build()
            )
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener {
                finish(my.toString())
            }
            p.setOnErrorListener { _, _, _ -> finish(my.toString()); true }
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Background thread. Returns an mp3 file or null on any failure. */
    private fun fetchCustom(text: String): File? {
        var conn: HttpURLConnection? = null
        return try {
            val id = Prefs.voiceId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$id?output_format=mp3_44100_128")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("xi-api-key", Prefs.voiceKey)
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("accept", "audio/mpeg")
            val body = JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2").toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val out = File(ctx.cacheDir, "voice_${System.nanoTime()}.mp3")
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            ctx.cacheDir.listFiles { f -> f.name.startsWith("voice_") && f != out }?.forEach { it.delete() }
            out
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
