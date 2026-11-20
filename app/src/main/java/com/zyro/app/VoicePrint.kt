package com.zyro.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A simple voice fingerprint (MFCC-style average of the sound's shape). It tells "owner or somebody else" roughly,
 * and it is used as an extra check before Live runs phone actions. It is NOT a security lock: a recording of the
 * owner, or a very similar voice, can pass, and a cold or a noisy room can make the owner fail.
 */
object VoicePrint {
    private const val RATE = 16000
    private const val N = 512          // 32 ms frame
    private const val BANDS = 24
    private const val COEF = 13

    @Volatile private var profile: DoubleArray? = null
    @Volatile private var thresh = 0.0

    // live utterance collection
    private val sum = DoubleArray(COEF)
    private var frames = 0
    private var lastVoiced = 0L
    @Volatile private var lastResultAt = 0L
    @Volatile private var lastResultOk = true

    private val hamming = DoubleArray(N) { 0.54 - 0.46 * cos(2 * PI * it / (N - 1)) }
    private val cosT: Array<DoubleArray> by lazy { Array(N / 2) { k -> DoubleArray(N / 2) { m -> cos(-2 * PI * k * (2 * m) / N) } } }
    private val sinT: Array<DoubleArray> by lazy { Array(N / 2) { k -> DoubleArray(N / 2) { m -> sin(-2 * PI * k * (2 * m) / N) } } }
    private val melEdges: IntArray by lazy {
        fun mel(f: Double) = 2595.0 * Math.log10(1 + f / 700.0)
        fun inv(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1)
        val lo = mel(100.0); val hi = mel(4000.0)
        IntArray(BANDS + 2) { i -> ((N + 1) * inv(lo + (hi - lo) * i / (BANDS + 1)) / RATE).toInt().coerceIn(1, N / 2 - 1) }
    }

    fun enrolled(): Boolean = loadProfile()
    private fun loadProfile(): Boolean {
        if (profile != null) return true
        val p = Prefs.voicePrint
        if (p.isBlank()) return false
        return try {
            val parts = p.split(";")
            thresh = parts[0].toDouble()
            profile = parts[1].split(",").map { it.toDouble() }.toDoubleArray()
            profile != null && profile!!.size == COEF
        } catch (e: Exception) { false }
    }

    /** Coefficients of one frame, or null when the frame is too quiet. */
    private fun frame(x: DoubleArray): DoubleArray? {
        var e = 0.0
        for (v in x) e += v * v
        if (sqrt(e / N) < 700.0) return null
        // a plain DFT on the bins we need is cheap enough at this size
        val half = N / 2
        val re = DoubleArray(half); val im = DoubleArray(half)
        val w = DoubleArray(N) { x[it] * hamming[it] }
        val lo = melEdges.first(); val hi = melEdges.last()
        for (k in lo..hi) {
            var r = 0.0; var i = 0.0
            val ct = cosT[k]; val st = sinT[k]
            for (m in 0 until N / 2) { val v = w[2 * m]; r += v * ct[m]; i += v * st[m] }
            re[k] = r; im[k] = i
        }
        val logE = DoubleArray(BANDS)
        for (b in 0 until BANDS) {
            var s = 0.0
            val a = melEdges[b]; val c = melEdges[b + 1]; val d = melEdges[b + 2]
            for (k in a..d) {
                val wgt = if (k <= c) (k - a).toDouble() / maxOf(1, c - a) else (d - k).toDouble() / maxOf(1, d - c)
                s += wgt * (re[k] * re[k] + im[k] * im[k])
            }
            logE[b] = ln(s + 1.0)
        }
        val out = DoubleArray(COEF)
        for (j in 0 until COEF) {
            var s = 0.0
            for (b in 0 until BANDS) s += logE[b] * cos(PI * j * (b + 0.5) / BANDS)
            out[j] = s
        }
        return out
    }

    class Carry { val a = DoubleArray(N * 4); var n = 0 }

    private fun toFrames(buf: ByteArray, len: Int, carry: Carry): List<DoubleArray> {
        val res = ArrayList<DoubleArray>()
        var i = 0
        while (i + 1 < len) {
            if (carry.n >= carry.a.size) carry.n = 0
            carry.a[carry.n++] = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toDouble()
            i += 2
        }
        while (carry.n >= N) {
            frame(carry.a.copyOf(N))?.let { res.add(it) }
            System.arraycopy(carry.a, N, carry.a, 0, carry.n - N)
            carry.n -= N
        }
        return res
    }

    private fun mean(list: List<DoubleArray>): DoubleArray {
        val m = DoubleArray(COEF)
        for (f in list) for (j in 0 until COEF) m[j] += f[j]
        for (j in 0 until COEF) m[j] = m[j] / list.size.toDouble()
        return m
    }

    // c0 is the loudness, so it is left out of the comparison
    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (j in 1 until COEF) { val d = a[j] - b[j]; s += d * d }
        return sqrt(s)
    }

    // ------------------------------------------------------------------ enrolment

    /** Records 3 short samples of the owner (about 14 seconds of speaking in all). Blocks; call from a thread. */
    fun enroll(ctx: Context, say: (String) -> Unit): String {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return "पहले माइक की अनुमति दो।"
        if (Live.active) return "पहले लाइव मोड बंद करो, फिर बोलो 'मेरी आवाज़ याद करो'।"
        val minIn = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn * 2, 8192))
        if (r.state != AudioRecord.STATE_INITIALIZED) return "माइक चालू नहीं हो पाया।"
        val means = ArrayList<DoubleArray>()
        try {
            r.startRecording()
            for (round in 1..3) {
                say(if (round == 1) "ठीक है। अब आराम से कुछ भी बोलते रहो, जैसे अपना दिन बताओ। मैं सुन रहा हूँ।" else "बहुत बढ़िया। एक बार और, कुछ और बोलो।")
                Thread.sleep(3500)
                val carry = Carry()
                val got = ArrayList<DoubleArray>()
                val buf = ByteArray(1024)
                val end = System.currentTimeMillis() + 6000L
                while (System.currentTimeMillis() < end) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) got.addAll(toFrames(buf, n, carry))
                }
                if (got.size < 40) return "आवाज़ बहुत कम आई। शांत जगह पर, फ़ोन थोड़ा पास रखकर दोबारा बोलो 'मेरी आवाज़ याद करो'।"
                means.add(mean(got))
            }
        } finally {
            try { r.stop() } catch (e: Exception) { }
            r.release()
        }
        val prof = mean(means)
        var spread = 0.0
        for (m in means) spread = maxOf(spread, dist(m, prof))
        val th = maxOf(spread * 2.2, 6.0)
        Prefs.voicePrint = th.toString() + ";" + prof.joinToString(",")
        profile = prof; thresh = th
        return "हो गया, तुम्हारी आवाज़ याद कर ली। अब बोलो 'वॉइस लॉक चालू' तो लाइव में फ़ोन के काम सिर्फ़ तुम्हारी आवाज़ पर होंगे।"
    }

    // ------------------------------------------------------------------ live use

    /** Live feeds every microphone block here while the lock is on. */
    fun feed(buf: ByteArray, n: Int, carry: Carry) {
        if (!Prefs.voiceLock || !loadProfile()) return
        val fr = toFrames(buf, n, carry)
        val now = System.currentTimeMillis()
        if (fr.isNotEmpty()) {
            synchronized(sum) {
                for (f in fr) { for (j in 0 until COEF) sum[j] += f[j]; frames++ }
                lastVoiced = now
            }
        } else {
            synchronized(sum) {
                // 0.7 s of quiet after speech = the sentence is over: judge it
                if (frames > 0 && now - lastVoiced > 700) {
                    if (frames >= 15) {
                        val m = DoubleArray(COEF) { sum[it] / frames }
                        lastResultOk = dist(m, profile!!) <= thresh
                        lastResultAt = now
                    }
                    java.util.Arrays.fill(sum, 0.0); frames = 0
                }
            }
        }
    }

    /** May a phone action run now? Allowed when the lock is off, nobody was judged lately, or the last speaker matched. */
    fun allowed(): Boolean {
        if (!Prefs.voiceLock || !loadProfile()) return true
        val before = lastResultAt
        var w = 0
        while (frames >= 15 && lastResultAt == before && w < 10) { try { Thread.sleep(100) } catch (e: Exception) { }; w++ }
        if (System.currentTimeMillis() - lastResultAt > 25_000) return true
        return lastResultOk
    }

    fun handle(ctx: Context, t: String): String? {
        fun any(vararg w: String) = w.any { t.contains(Local.strip(it)) }
        if (any("meri awaz yaad", "मेरी आवाज़ याद", "मेरी आवाज याद", "awaaz yaad karo", "आवाज़ याद करो", "voice enroll", "voice yaad")) {
            Thread {
                val r = try { enroll(ctx) { Assistant.get(ctx).announce(it) } } catch (e: Exception) { "आवाज़ याद नहीं हो पाई।" }
                Assistant.get(ctx).announce(r)
            }.start()
            return "ठीक है, शुरू करता हूँ।"
        }
        if (any("voice lock", "वॉइस लॉक", "वोइस लॉक", "awaaz lock", "आवाज़ लॉक")) {
            if (any("band", "बंद", "off", "hata", "हटा")) { Prefs.voiceLock = false; return "वॉइस लॉक बंद कर दिया।" }
            if (!enrolled()) return "पहले बोलो 'मेरी आवाज़ याद करो', ताकि मैं तुम्हारी आवाज़ पहचान सकूँ।"
            Prefs.voiceLock = true
            return "वॉइस लॉक चालू। लाइव में फ़ोन के काम तुम्हारी आवाज़ पर होंगे। ध्यान रखना, ये हल्का सा चेक है, बैंक जैसी सुरक्षा नहीं।"
        }
        return null
    }
}
