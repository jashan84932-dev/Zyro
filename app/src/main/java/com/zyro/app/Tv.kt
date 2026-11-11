package com.zyro.app

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Remote control for Android TV / Google TV sets (Aiwa Magnifiq, Mi, Sony, TCL ...) over the
 * local network - for example when the TV is joined to this phone's hotspot.
 *
 * It speaks the same "Android TV Remote" protocol as Google's own remote app:
 *   1. Pair once (the TV shows a 6 character code).
 *   2. After that, connect and send key presses or open apps.
 */
object Tv {

    private const val PAIR_PORT = 6467
    private const val REMOTE_PORT = 6466
    private val PW = "micro".toCharArray()

    private var appCtx: Context? = null
    private val connLock = Any()
    private val sendLock = Any()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** Android key codes the TV understands. */
    private val KEYS = mapOf(
        "power" to 26, "home" to 3, "back" to 4,
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "ok" to 23,
        "volume_up" to 24, "volume_down" to 25, "mute" to 164,
        "channel_up" to 166, "channel_down" to 167,
        "menu" to 82, "play_pause" to 85, "settings" to 176
    )

    private val APPS = mapOf(
        "youtube" to "https://www.youtube.com",
        "netflix" to "https://www.netflix.com/title",
        "prime" to "https://app.primevideo.com"
    )

    // ------------------------------------------------------------------ public API

    fun isPaired(): Boolean {
        val dir = appCtx?.filesDir ?: return false
        return File(dir, "tv_key.p8").exists() && File(dir, "tv_cert.der").exists() && Prefs.tvIp.isNotBlank()
    }

    private var pairSock: SSLSocket? = null
    private var pairIp = ""

    /** Step 1 of pairing: finds the TV and makes it show a code. Returns "Enter the code..." on success. */
    fun startPairing(): String {
        synchronized(connLock) {
            closePairing()
            val ip = Prefs.tvIp.ifBlank { null } ?: discover()
                ?: return "No TV found. Turn the TV on and connect it to this phone's hotspot, or type its IP address in settings."
            return try {
                val s = openTls(ip, PAIR_PORT)
                pairSock = s
                pairIp = ip
                val out = s.outputStream
                val inp = s.inputStream

                // 1. who we are
                writeFrame(out, outer(10, fStr(1, "atvremote") + fStr(2, "Zyro")))
                expect(inp, 11)
                // 2. which code types we can enter (6 hex characters)
                val enc = fVar(1, 3) + fVar(2, 6)
                writeFrame(out, outer(20, fBytes(1, enc) + fVar(3, 1)))
                expect(inp, 20)
                // 3. our choice
                writeFrame(out, outer(30, fBytes(1, enc) + fVar(2, 1)))
                expect(inp, 31)
                "Enter the code shown on the TV."
            } catch (e: Exception) {
                closePairing()
                "Couldn't start pairing: ${e.message}"
            }
        }
    }

    /** Step 2 of pairing: sends the code you read from the TV screen. */
    fun finishPairing(code: String): String {
        synchronized(connLock) {
            val s = pairSock ?: return "Start pairing first."
            val c = code.trim().uppercase()
            if (!Regex("[0-9A-F]{6}").matches(c)) return "Invalid code. It has 6 letters or digits."
            return try {
                val codeBytes = ByteArray(3) { i -> c.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                val serverKey = s.session.peerCertificates[0].publicKey as RSAPublicKey
                val clientKey = keys().second.publicKey as RSAPublicKey
                val md = MessageDigest.getInstance("SHA-256")
                md.update(unsigned(clientKey.modulus))
                md.update(unsigned(clientKey.publicExponent))
                md.update(unsigned(serverKey.modulus))
                md.update(unsigned(serverKey.publicExponent))
                md.update(codeBytes, 1, 2)
                val hash = md.digest()
                if (hash[0] != codeBytes[0]) {
                    return "Wrong code. Check the code on the TV and try again."
                }
                writeFrame(s.outputStream, outer(40, fBytes(1, hash)))
                expect(s.inputStream, 41)
                Prefs.tvIp = pairIp
                closePairing()
                "TV paired. You can now control it by voice."
            } catch (e: Exception) {
                closePairing()
                "Couldn't finish pairing: ${e.message}"
            }
        }
    }

    /**
     * Sends a command to the TV: power, home, back, up, down, left, right, ok, volume_up,
     * volume_down, mute, channel_up, channel_down, menu, play_pause, settings,
     * or an app name: youtube, netflix, prime.
     */
    fun run(command: String, times: Int = 1): String {
        val cmd = command.trim().lowercase().replace(" ", "_")
        synchronized(connLock) {
            if (!isPaired()) return "The TV is not paired yet. Pair it in the Zyro setup (step 5)."
            val err = ensureRemote()
            if (err != null) return err
            return try {
                val link = APPS[cmd]
                if (link != null) {
                    send(fBytes(90, fStr(1, link)))
                    return "Opened $cmd on the TV."
                }
                val code = KEYS[cmd] ?: return "Unknown TV command \"$command\"."
                repeat(times.coerceIn(1, 20)) {
                    send(fBytes(10, fVar(1, code.toLong()) + fVar(2, 3)))
                    Thread.sleep(160)
                }
                "Sent $cmd to the TV."
            } catch (e: Exception) {
                closeRemote()
                "Couldn't send to the TV: ${e.message}"
            }
        }
    }

    /** Opens YouTube search results for [query] on the TV. */
    fun search(query: String): String {
        synchronized(connLock) {
            if (!isPaired()) return "The TV is not paired yet. Pair it in the Zyro setup (step 5)."
            val err = ensureRemote()
            if (err != null) return err
            return try {
                val link = "https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query)
                send(fBytes(90, fStr(1, link)))
                "Opened YouTube search for $query on the TV."
            } catch (e: Exception) {
                closeRemote()
                "Couldn't send to the TV: ${e.message}"
            }
        }
    }

    // ------------------------------------------------------------------ remote connection

    @Volatile private var rSock: SSLSocket? = null
    @Volatile private var ready = false

    private fun ensureRemote(): String? {
        if (ready && rSock?.isClosed == false) return null
        val saved = Prefs.tvIp
        if (saved.isNotBlank() && connectRemote(saved)) return null
        val found = discover()
        if (found != null && found != saved && connectRemote(found)) {
            Prefs.tvIp = found
            return null
        }
        return if (found == null && saved.isBlank()) {
            "No TV found. Turn the TV on and connect it to this phone's hotspot."
        } else {
            "Couldn't connect to the TV. Make sure it is on, then pair it again in the Zyro setup."
        }
    }

    private fun connectRemote(ip: String): Boolean {
        closeRemote()
        return try {
            val s = openTls(ip, REMOTE_PORT)
            s.soTimeout = 0
            rSock = s
            val inp = s.inputStream
            Thread {
                try {
                    while (true) handleRemote(parse(readFrame(inp)))
                } catch (e: Exception) {
                    // connection closed
                }
                ready = false
                try { s.close() } catch (e: Exception) { /* ignore */ }
            }.start()
            val end = System.currentTimeMillis() + 6000
            while (!ready && System.currentTimeMillis() < end && !s.isClosed) Thread.sleep(50)
            ready
        } catch (e: Exception) {
            closeRemote()
            false
        }
    }

    private fun handleRemote(fields: List<Field>) {
        for (f in fields) {
            when (f.num) {
                1 -> { // TV asks who we are
                    val device = fStr(1, "Zyro") + fStr(2, "Zyro") + fVar(3, 1) + fStr(4, "1") +
                        fStr(5, "micro.remote") + fStr(6, "1.0.0")
                    send(fBytes(1, fVar(1, 622) + fBytes(2, device)))
                }
                2 -> { // TV says "go active"
                    send(fBytes(2, fVar(1, 622)))
                    ready = true
                }
                8 -> { // ping: answer with the same number
                    val v = f.bytes?.let { b -> parse(b).firstOrNull { it.num == 1 }?.long } ?: 0L
                    send(fBytes(9, fVar(1, v)))
                }
            }
        }
    }

    private fun send(msg: ByteArray) {
        val s = rSock ?: throw IOException("not connected")
        synchronized(sendLock) {
            val out = s.outputStream
            out.write(varint(msg.size.toLong()) + msg)
            out.flush()
        }
    }

    private fun closeRemote() {
        ready = false
        try { rSock?.close() } catch (e: Exception) { /* ignore */ }
        rSock = null
    }

    private fun closePairing() {
        try { pairSock?.close() } catch (e: Exception) { /* ignore */ }
        pairSock = null
    }

    // ------------------------------------------------------------------ finding the TV

    /** Looks for a TV on every local network this phone is on (including its own hotspot). */
    private fun discover(): String? {
        val own = HashSet<String>()
        val prefixes = ArrayList<String>()
        try {
            for (ni in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val host = a.hostAddress ?: continue
                        own.add(host)
                        val p = host.substringBeforeLast('.')
                        if (p !in prefixes) prefixes.add(p)
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        val found = ConcurrentLinkedQueue<String>()
        val pool = Executors.newFixedThreadPool(64)
        for (p in prefixes) {
            for (h in 1..254) {
                val host = "$p.$h"
                if (host in own) continue
                pool.execute {
                    try {
                        Socket().use { it.connect(InetSocketAddress(host, REMOTE_PORT), 600) }
                        found.add(host)
                    } catch (e: Exception) {
                        // nothing there
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        pool.shutdownNow()
        return found.firstOrNull()
    }

    // ------------------------------------------------------------------ TLS and our own certificate

    private var keyCache: Pair<PrivateKey, X509Certificate>? = null

    private fun keys(): Pair<PrivateKey, X509Certificate> {
        keyCache?.let { return it }
        val dir = appCtx?.filesDir ?: throw IOException("app not ready")
        val kf = File(dir, "tv_key.p8")
        val cf = File(dir, "tv_cert.der")
        if (kf.exists() && cf.exists()) {
            try {
                val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(cf.readBytes())) as X509Certificate
                keyCache = priv to cert
                return priv to cert
            } catch (e: Exception) {
                // damaged files: make new ones below
            }
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        val cert = makeCert(kp.public, kp.private)
        kf.writeBytes(kp.private.encoded)
        cf.writeBytes(cert.encoded)
        keyCache = kp.private to cert
        return kp.private to cert
    }

    private fun openTls(ip: String, port: Int): SSLSocket {
        val (priv, cert) = keys()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("micro", priv, PW, arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PW)
        // The TV uses its own self-signed certificate, so it cannot be checked against a public authority.
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(trust), SecureRandom())
        val raw = Socket()
        raw.connect(InetSocketAddress(ip, port), 4000)
        raw.soTimeout = 20000
        val ssl = ctx.socketFactory.createSocket(raw, ip, port, true) as SSLSocket
        ssl.startHandshake()
        return ssl
    }

    // --- a minimal self-signed X.509 certificate, built by hand ---

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val lenBytes = when {
            len < 128 -> byteArrayOf(len.toByte())
            len < 256 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        }
        return byteArrayOf(tag.toByte()) + lenBytes + content
    }

    private fun makeCert(pub: PublicKey, priv: PrivateKey): X509Certificate {
        val sigAlg = der(
            0x30,
            der(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)) +
                der(0x05, ByteArray(0))
        )
        val name = der(0x30, der(0x31, der(0x30, der(0x06, byteArrayOf(0x55, 0x04, 0x03)) + der(0x0C, "micro".toByteArray()))))
        val validity = der(0x30, der(0x17, "261101000000Z".toByteArray()) + der(0x17, "491231235959Z".toByteArray()))
        val serialBytes = ByteArray(8)
        SecureRandom().nextBytes(serialBytes)
        serialBytes[0] = ((serialBytes[0].toInt() and 0x3F) or 0x01).toByte() // positive, no leading zero
        val tbs = der(
            0x30,
            der(0xA0, der(0x02, byteArrayOf(2))) +
                der(0x02, serialBytes) +
                sigAlg + name + validity + name + pub.encoded
        )
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(priv)
        sig.update(tbs)
        val signature = sig.sign()
        val certDer = der(0x30, tbs + sigAlg + der(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun unsigned(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    // ------------------------------------------------------------------ tiny protobuf toolkit

    private class Field(val num: Int, val long: Long, val bytes: ByteArray?)

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                break
            }
            out.write(b or 0x80)
        }
        return out.toByteArray()
    }

    private fun fVar(field: Int, value: Long): ByteArray = varint((field.toLong() shl 3) or 0L) + varint(value)
    private fun fVar(field: Int, value: Int): ByteArray = fVar(field, value.toLong())
    private fun fBytes(field: Int, data: ByteArray): ByteArray =
        varint((field.toLong() shl 3) or 2L) + varint(data.size.toLong()) + data
    private fun fStr(field: Int, s: String): ByteArray = fBytes(field, s.toByteArray(Charsets.UTF_8))

    /** Pairing message wrapper: protocol version 2, status OK (200), plus one payload field. */
    private fun outer(field: Int, payload: ByteArray): ByteArray =
        fVar(1, 2) + fVar(2, 200) + fBytes(field, payload)

    private fun writeFrame(out: java.io.OutputStream, msg: ByteArray) {
        out.write(varint(msg.size.toLong()) + msg)
        out.flush()
    }

    private fun readFrame(inp: InputStream): ByteArray {
        var len = 0
        var shift = 0
        while (true) {
            val b = inp.read()
            if (b < 0) throw IOException("TV closed the connection")
            len = len or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n < 0) throw IOException("TV closed the connection")
            off += n
        }
        return buf
    }

    private fun parse(b: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var i = 0
        try {
            while (i < b.size) {
                var tag = 0L
                var shift = 0
                while (true) {
                    val x = b[i++].toInt() and 0xFF
                    tag = tag or ((x and 0x7F).toLong() shl shift)
                    if ((x and 0x80) == 0) break
                    shift += 7
                }
                val num = (tag ushr 3).toInt()
                when ((tag and 7L).toInt()) {
                    0 -> {
                        var v = 0L
                        var sh = 0
                        while (true) {
                            val x = b[i++].toInt() and 0xFF
                            v = v or ((x and 0x7F).toLong() shl sh)
                            if ((x and 0x80) == 0) break
                            sh += 7
                        }
                        out.add(Field(num, v, null))
                    }
                    2 -> {
                        var l = 0
                        var sh = 0
                        while (true) {
                            val x = b[i++].toInt() and 0xFF
                            l = l or ((x and 0x7F) shl sh)
                            if ((x and 0x80) == 0) break
                            sh += 7
                        }
                        out.add(Field(num, 0L, b.copyOfRange(i, i + l)))
                        i += l
                    }
                    1 -> i += 8
                    5 -> i += 4
                    else -> return out
                }
            }
        } catch (e: Exception) {
            // truncated message: return what we have
        }
        return out
    }

    /** Reads one pairing reply and checks that the TV said OK and sent the expected part. */
    private fun expect(inp: InputStream, field: Int) {
        val f = parse(readFrame(inp))
        val status = f.firstOrNull { it.num == 2 }?.long ?: 0L
        if (status != 200L) throw IOException("TV refused pairing (status $status)")
        if (f.none { it.num == field }) throw IOException("unexpected reply from the TV")
    }
}
