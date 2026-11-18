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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

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
    private var basePitch = 1.0f
    private var baseRate = 1.0f
    @Volatile private var mood = "calm"
    /** Language tag used by the built-in voice for the next sentences (translator). Null = Hindi. */
    @Volatile var overrideLang: String? = null

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.forLanguageTag("hi-IN"))
                hindiAvailable = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (Prefs.jarvisMode) {
                    // deeper, slightly slower and calmer
                    basePitch = 0.78f
                    baseRate = 0.93f
                }
                tts?.setPitch(basePitch)
                tts?.setSpeechRate(baseRate)
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
            val (m, body) = Emotion.parse(text)
            mood = m
            val clean = speechClean(body).take(1500)
            if (clean.isBlank()) { val cb = onDone; onDone = null; cb?.invoke(); return@post }
            if (overrideLang == null && Prefs.customVoice && Prefs.voiceKey.isNotBlank() && Prefs.voiceId.isNotBlank()) {
                customPipeline(clean, my)
            } else {
                builtIn(clean, my)
            }
        }
    }

    private val pool = Executors.newFixedThreadPool(3)

    /** Removes things that sound silly when read out: markdown marks, emoji, links, stray symbols. */
    private fun speechClean(t: String): String {
        var x = t
        x = x.replace(Regex("https?://\\S+"), " link ")
        x = x.replace(Regex("[*_`#~>|\\[\\]{}^\\\\]+"), " ")
        x = x.replace(Regex("[\\p{So}\\p{Cs}\\u200d\\ufe0f]+"), " ")
        x = x.replace(Regex("(?m)^\\s*[-•]\\s+"), "")
        x = x.replace("&", " aur ").replace("%", " percent ").replace("₹", " rupaye ")
        x = x.replace(Regex("[ \\t]{2,}"), " ")
        return x.trim()
    }

    /**
     * Custom voice, fast: split into sentences, fetch them all at once in the background and
     * start playing the first one as soon as it is ready while the rest download.
     * If any piece fails, that piece (and the rest) is spoken by the built-in voice instead.
     */
    private fun customPipeline(text: String, my: Int) {
        val parts = text.split(Regex("(?<=[।.!?\\n])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            .fold(mutableListOf<String>()) { acc, p ->
                // glue tiny pieces to the previous one so we make fewer requests
                if (acc.isNotEmpty() && p.length < 18) acc[acc.size - 1] = acc.last() + " " + p else acc.add(p)
                acc
            }
        val jobs: List<Future<File?>> = parts.mapIndexed { i, p -> pool.submit(Callable { fetchCustom(p, i) }) }
        Thread {
            for ((i, f) in jobs.withIndex()) {
                if (my != token) { jobs.forEach { it.cancel(true) }; return@Thread }
                val file = try { f.get() } catch (e: Exception) { null }
                val last = i == jobs.size - 1
                if (my != token) return@Thread
                val lock = Object()
                var played = false
                var ok = false
                main.post {
                    if (my != token) { synchronized(lock) { played = true; lock.notifyAll() }; return@post }
                    ok = file != null && playPiece(file, my, last) { synchronized(lock) { played = true; lock.notifyAll() } }
                    if (!ok) synchronized(lock) { played = true; lock.notifyAll() }
                }
                synchronized(lock) { while (!played) lock.wait(100) }
                if (!ok) {
                    // fall back to the built-in voice for the remainder
                    val rest = parts.drop(i).joinToString(" ")
                    main.post { if (my == token) builtIn(rest, my) }
                    return@Thread
                }
            }
        }.start()
    }

    private fun playPiece(file: File, my: Int, last: Boolean, next: () -> Unit): Boolean {
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
                try { it.release() } catch (_: Exception) {}
                if (player === it) player = null
                if (last) finish(my.toString())
                next()
            }
            p.setOnErrorListener { mp, _, _ ->
                try { mp.release() } catch (_: Exception) {}
                if (last) finish(my.toString())
                next(); true
            }
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Exception) { false }
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
        val st = Emotion.style(mood)
        tts?.setPitch((basePitch * st.pitch).coerceIn(0.5f, 2f))
        tts?.setSpeechRate((baseRate * st.rate).coerceIn(0.5f, 2f))
        // speak sentence by sentence: the first one starts sooner, the callback fires after the last
        val parts = text.split(Regex("(?<=[।.!?\\n])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, my.toString())
        } else {
            for ((i, p) in parts.withIndex()) {
                val id = if (i == parts.size - 1) my.toString() else "p$my"
                // each sentence gets its own small tune: questions lift, exclamations brighten, trailing dots slow down
                var pt = basePitch * st.pitch
                var rt = baseRate * st.rate
                when {
                    p.endsWith("?") || p.endsWith("?\"") -> { pt *= 1.06f }
                    p.endsWith("!") -> { pt *= 1.04f; rt *= 1.04f }
                    p.endsWith("...") || p.endsWith("…") -> { rt *= 0.9f }
                }
                tts?.setPitch(pt.coerceIn(0.5f, 2f))
                tts?.setSpeechRate(rt.coerceIn(0.5f, 2f))
                tts?.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
                // a tiny breath between sentences, like a person
                if (i < parts.size - 1) tts?.playSilence(if (p.endsWith("?")) 220 else 140, TextToSpeech.QUEUE_ADD, null)
            }
        }
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
    private fun fetchCustom(text: String, idx: Int = 0): File? {
        var conn: HttpURLConnection? = null
        return try {
            val id = Prefs.voiceId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$id?output_format=mp3_44100_64&optimize_streaming_latency=3")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("xi-api-key", Prefs.voiceKey)
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("accept", "audio/mpeg")
            val body = JSONObject().put("text", text).put("model_id", "eleven_flash_v2_5").put("voice_settings", JSONObject().put("stability", 0.45).put("similarity_boost", 0.8).put("style", 0.25).put("use_speaker_boost", true)).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val out = File(ctx.cacheDir, "voice_${System.nanoTime()}_$idx.mp3")
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            ctx.cacheDir.listFiles { f -> f.name.startsWith("voice_") && f != out && System.currentTimeMillis() - f.lastModified() > 120000 }?.forEach { it.delete() }
            out
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
