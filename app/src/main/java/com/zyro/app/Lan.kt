package com.zyro.app

import java.io.File
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds the devices connected to this phone's hotspot (or the Wi-Fi it is on) and controls the ones
 * that can be controlled over the network without a cloud account:
 *   Android TV, Roku, Shelly, Tasmota and WLED devices.
 * Samsung / LG TVs and Chromecast are only recognised (they need a special pairing).
 */
object Lan {

    class Dev(val ip: String, val kind: String, val label: String, val mac: String)

    @Volatile private var devices: List<Dev> = emptyList()

    private val PORTS = intArrayOf(8060, 6466, 8001, 3000, 8008, 80)

    // ------------------------------------------------------------------ scanning

    private fun subnets(): Pair<List<String>, Set<String>> {
        val prefixes = ArrayList<String>()
        val own = HashSet<String>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val h = a.hostAddress ?: continue
                        own.add(h)
                        val p = h.substringBeforeLast('.')
                        if (p !in prefixes) prefixes.add(p)
                    }
                }
            }
        } catch (e: Exception) {
            // no network
        }
        return prefixes to own
    }

    /** Devices the phone has recently talked to (works on older Android versions). */
    private fun arp(): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[2] == "0x2" && p[3] != "00:00:00:00:00:00") out[p[0]] = p[3]
            }
        } catch (e: Exception) {
            // not readable on newer Android versions
        }
        return out
    }

    private fun httpGet(url: String, timeout: Int = 1500): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = timeout
            c.readTimeout = timeout
            val s = c.inputStream.bufferedReader().use { it.readText().take(6000) }
            c.disconnect()
            s
        } catch (e: Exception) {
            null
        }
    }

    private fun httpSend(url: String, method: String, body: String? = null): Boolean {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 2500
            c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            } else if (method == "POST") {
                c.doOutput = true
                c.outputStream.use { it.write(ByteArray(0)) }
            }
            val code = c.responseCode
            c.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    private fun tag(xml: String?, name: String): String? {
        if (xml == null) return null
        return Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun jsonName(json: String?): String? {
        if (json == null) return null
        return Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    }

    private fun identify(ip: String, ports: Set<Int>, mac: String): Dev {
        if (8060 in ports) {
            val body = httpGet("http://$ip:8060/query/device-info")
            val name = tag(body, "friendly-device-name") ?: tag(body, "user-device-name") ?: tag(body, "model-name")
            if (body != null) return Dev(ip, "roku", "Roku ${name ?: ""}".trim(), mac)
        }
        if (6466 in ports) return Dev(ip, "androidtv", "Android TV", mac)
        if (8001 in ports) {
            val n = jsonName(httpGet("http://$ip:8001/api/v2/"))
            return Dev(ip, "samsung", "Samsung TV ${n ?: ""}".trim(), mac)
        }
        if (3000 in ports) return Dev(ip, "lg", "LG TV", mac)
        if (8008 in ports) {
            val n = jsonName(httpGet("http://$ip:8008/setup/eureka_info?params=name"))
            return Dev(ip, "cast", "Chromecast ${n ?: ""}".trim(), mac)
        }
        if (80 in ports) {
            val shelly = httpGet("http://$ip/shelly")
            if (shelly != null && shelly.contains("\"mac\"")) return Dev(ip, "shelly", "Shelly switch", mac)
            val wled = httpGet("http://$ip/json/info")
            if (wled != null && wled.contains("WLED", ignoreCase = true)) return Dev(ip, "wled", "WLED light", mac)
            val tas = httpGet("http://$ip/cm?cmnd=Status")
            if (tas != null && tas.contains("\"Status\"")) return Dev(ip, "tasmota", "Tasmota switch", mac)
            val page = httpGet("http://$ip/")
            val title = page?.let { Regex("<title>([^<]{1,40})</title>", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim() }
            return Dev(ip, "web", if (title.isNullOrBlank()) "Web device" else "Web device $title", mac)
        }
        return Dev(ip, "unknown", "Unknown device", mac)
    }

    fun scan(): List<Dev> {
        val (prefixes, own) = subnets()
        if (prefixes.isEmpty()) {
            devices = emptyList()
            return devices
        }
        val open = ConcurrentHashMap<String, MutableSet<Int>>()
        val pool = Executors.newFixedThreadPool(96)
        for (p in prefixes) {
            for (h in 1..254) {
                val host = "$p.$h"
                if (host in own) continue
                for (port in PORTS) {
                    pool.execute {
                        try {
                            Socket().use { it.connect(InetSocketAddress(host, port), 350) }
                            open.getOrPut(host) { Collections.synchronizedSet(HashSet<Int>()) }.add(port)
                        } catch (e: Exception) {
                            // closed or nobody there
                        }
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(25, TimeUnit.SECONDS)
        pool.shutdownNow()

        val macs = arp()
        val all = HashSet<String>(open.keys)
        for ((ip, _) in macs) if (ip.substringBeforeLast('.') in prefixes && ip !in own) all.add(ip)

        val idPool = Executors.newFixedThreadPool(8)
        val results = ConcurrentHashMap<String, Dev>()
        for (ip in all) {
            idPool.execute {
                try {
                    results[ip] = identify(ip, open[ip] ?: emptySet(), macs[ip] ?: "")
                } catch (e: Exception) {
                    results[ip] = Dev(ip, "unknown", "Unknown device", macs[ip] ?: "")
                }
            }
        }
        idPool.shutdown()
        idPool.awaitTermination(20, TimeUnit.SECONDS)
        idPool.shutdownNow()
        devices = results.values.sortedBy { ip -> ip.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
        return devices
    }

    private fun kindName(k: String) = when (k) {
        "roku", "androidtv", "samsung", "lg" -> "TV"
        "cast" -> "Chromecast"
        "shelly", "tasmota" -> "स्विच"
        "wled" -> "लाइट"
        else -> "डिवाइस"
    }

    private fun controllable(k: String) = k in setOf("roku", "androidtv", "shelly", "tasmota", "wled")

    // ------------------------------------------------------------------ voice entry

    fun handle(hands: Hands, t: String, words: List<String>): String? {
        fun any(vararg w: String) = w.any { t.contains(Local.strip(it)) }
        fun word(vararg w: String) = w.any { x -> words.contains(Local.strip(x)) }

        // "hotspot par kaun kaun connected hai"
        if ((any("hotspot", "हॉटस्पॉट", "हॉट स्पॉट", "wifi", "वाईफाई", "वाई फाई", "network", "नेटवर्क") &&
                any("connected", "कनेक्ट", "kaun", "कौन", "juda", "जुड़ा", "जुड़े", "devices", "डिवाइस", "list", "लिस्ट")) ||
            any("devices scan", "डिवाइस स्कैन", "device dhundo", "डिवाइस ढूंढो", "डिवाइस ढूँढो", "डिवाइस खोजो", "scan karo", "स्कैन करो")
        ) {
            if (any("on karo", "ऑन करो", "chalu", "चालू", "off", "बंद", "band") && !any("kaun", "कौन", "connected", "devices", "डिवाइस")) return null
            val list = scan()
            if (list.isEmpty()) return "कोई डिवाइस नहीं मिला। देखो कि हॉटस्पॉट चालू है और डिवाइस उससे जुड़ा है।"
            val parts = list.mapIndexed { i, d -> "${i + 1}: ${d.label} (${if (controllable(d.kind)) "कंट्रोल हो सकता है" else "सिर्फ़ पहचाना"})" }
            return "${list.size} डिवाइस मिले। " + parts.joinToString("। ") + "। कंट्रोल करने के लिए बोलो: डिवाइस नंबर और ऑन या ऑफ़।"
        }

        val list = devices
        // "device 2 on karo"
        val idx = if (any("device", "डिवाइस", "number", "नंबर")) words.firstNotNullOfOrNull { Local.numberOf(it) } else null
        val byNumber = if (idx != null && idx in 1..list.size) list[idx - 1] else null

        val off = word("off", "band", "bujhao", "stop") || any("ऑफ", "बंद", "बुझाओ")
        val on = !off && (word("on", "chalu", "jalao", "start") || any("ऑन", "चालू", "जलाओ", "शुरू"))
        val torch = any("flash", "फ्लैश", "torch", "टॉर्च", "टार्च", "tourch")
        val kindWord = if (torch) "" else when {
            any("roku", "रोकू") -> "roku"
            any("plug", "प्लग", "switch", "स्विच", "pankha", "पंखा", "fan", "फैन", "shelly", "tasmota") -> "switch"
            any("bulb", "बल्ब", "light", "लाइट", "wled", "बत्ती", "batti") -> "light"
            else -> ""
        }
        val target: Dev? = byNumber ?: when (kindWord) {
            "roku" -> list.firstOrNull { it.kind == "roku" }
            "switch" -> list.firstOrNull { it.kind == "shelly" || it.kind == "tasmota" }
            "light" -> list.firstOrNull { it.kind == "wled" } ?: list.firstOrNull { it.kind == "shelly" || it.kind == "tasmota" }
            else -> null
        }
        if (target == null) {
            if (kindWord.isNotEmpty() && (on || off) && list.isNotEmpty()) {
                return "वह डिवाइस मुझे नहीं मिला। दोबारा स्कैन करवाओ: हॉटस्पॉट पर कौन जुड़ा है।"
            }
            return null
        }
        return when (target.kind) {
            "shelly", "tasmota", "wled" -> {
                if (!on && !off) return null
                if (switchPower(target, on)) "${kindName(target.kind)} ${if (on) "चालू" else "बंद"} कर दिया।"
                else "${target.label} से बात नहीं हो पाई।"
            }
            "roku" -> rokuCommand(target, t, words)
            "androidtv" -> {
                val c = when {
                    off || on -> "power"
                    any("home", "होम") -> "home"
                    any("back", "बैक") -> "back"
                    any("volume", "वॉल्यूम") -> "volume_up"
                    else -> return "Android TV के लिए बोलो: टीवी वॉल्यूम बढ़ाओ, टीवी होम वगैरह।"
                }
                val r = Tv.run(c)
                if (r.startsWith("Sent")) "ठीक है।" else r
            }
            else -> "${target.label} सिर्फ़ पहचाना गया है, इसे कंट्रोल करना अभी मुझे नहीं आता।"
        }
    }

    // ------------------------------------------------------------------ controlling

    private fun switchPower(d: Dev, on: Boolean): Boolean {
        val ip = d.ip
        return when (d.kind) {
            "tasmota" -> httpGet("http://$ip/cm?cmnd=Power%20${if (on) "On" else "Off"}") != null
            "wled" -> httpSend("http://$ip/json/state", "POST", "{\"on\":${if (on) "true" else "false"}}")
            else -> {
                // Shelly: older (gen 1) first, then newer (gen 2)
                httpGet("http://$ip/relay/0?turn=${if (on) "on" else "off"}") != null ||
                    httpGet("http://$ip/rpc/Switch.Set?id=0&on=${if (on) "true" else "false"}") != null
            }
        }
    }

    private fun rokuCommand(d: Dev, t: String, words: List<String>): String {
        fun any(vararg w: String) = w.any { t.contains(Local.strip(it)) }
        fun word(vararg w: String) = w.any { x -> words.contains(Local.strip(x)) }
        val base = "http://${d.ip}:8060"
        val key = when {
            any("youtube", "यूट्यूब") -> {
                return if (httpSend("$base/launch/837", "POST")) "Roku पर YouTube खोल दिया।" else "Roku से बात नहीं हो पाई।"
            }
            any("volume", "वॉल्यूम", "आवाज") ->
                if (any("kam", "कम", "घटा", "ghata", "down", "नीचे")) "VolumeDown" else "VolumeUp"
            any("mute", "म्यूट") -> "VolumeMute"
            any("home", "होम") -> "Home"
            any("back", "बैक", "पीछे") -> "Back"
            any("pause", "play", "पॉज", "प्ले") -> "Play"
            word("off", "band") || any("ऑफ", "बंद") -> "PowerOff"
            word("on", "chalu") || any("ऑन", "चालू") -> "Power"
            any("left", "लेफ्ट", "बाएं") -> "Left"
            any("right", "राइट", "दाएं") -> "Right"
            word("up", "ऊपर") -> "Up"
            word("down", "नीचे") -> "Down"
            word("ok", "okay", "select") || any("ओके", "सेलेक्ट") -> "Select"
            else -> return "Roku के लिए बोलो: रोकू होम, वॉल्यूम बढ़ाओ, ऊपर, नीचे, ओके, बंद।"
        }
        return if (httpSend("$base/keypress/$key", "POST")) "ठीक है।" else "Roku से बात नहीं हो पाई।"
    }
}
