package org.voiceassistant

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * "Turn the TV off / on": the Samsung TV on the user's home Wi-Fi, driven straight from the phone.
 * Off = the remote's power key over the TV's local remote-control socket (the TV asks once,
 * on screen, to allow "the assistant"; the token it gives back is kept). On = Wake-on-LAN to the TV's
 * Wi-Fi address, which works when the TV's "Power On with Mobile" setting is on.
 * Home Wi-Fi only: none of this goes through the internet.
 */
object SamsungTv {
    private const val TAG = "Assistant"
    private fun p(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    /** Returns null when done, or a short reason it couldn't. Blocking: call off the main thread. */
    private const val WAKE_HELP = "I sent the wake signal but the TV didn't come on. On the TV, go to Settings, General, Network, Expert Settings, and turn on Power On with Mobile."

    fun power(c: Context, on: Boolean): String? {
        val mac = p(c).getString("tvMac", null)
        val cached = p(c).getString("tvIp", null)
        if (on) {
            // An off TV doesn't answer the network, so don't hunt for it: wake it at the address we know.
            if (cached != null && info(c, cached)?.optString("PowerState") == "on") return null
            mac ?: return "I've never seen the TV switched on, so I don't know how to wake it. Turn it on once by hand, then ask me again."
            val ip = cached ?: return "I can't find the TV on your Wi-Fi. Are you home?"
            repeat(5) { wake(mac, ip); Thread.sleep(300) }
            for (i in 0 until 16) { Thread.sleep(500); if (info(c, ip)?.optString("PowerState") == "on") return null }
            return WAKE_HELP
        }
        val ip = find(c) ?: return "I can't find the TV on your Wi-Fi. Are you home?"
        if (info(c, ip)?.optString("PowerState").let { it == null || it == "standby" }) return null   // already off
        // The power key toggles, so press it, check, and press once more only if it is still on
        // (the first press right after the user allows the assistant on the TV is sometimes ignored).
        for (attempt in 0 until 2) {
            key(c, ip, "KEY_POWER")?.let { return it }
            for (i in 0 until 8) { Thread.sleep(500); if (info(c, ip)?.optString("PowerState") != "on") return null }
        }
        return "I pressed the power key twice but the TV is still on."
    }

    /** Apps installed on the user's TV (ids read off this TV's own /api/v2/applications on 11-13). */
    val APPS = mapOf(
        "netflix" to "3201907018807", "prime video" to "3201512006785", "disney+" to "3201901017640",
        "youtube" to "111299001912", "hulu" to "3201601007625", "max" to "3202301029760",
        "apple tv" to "3201807016597", "peacock" to "3202006020991", "spotify" to "3201606009684",
    )

    /** Open an app on the TV, switching the TV on first if needed. Null when it's showing. */
    fun openApp(c: Context, app: String): String? {
        val id = APPS[app] ?: return "I don't know $app on your TV."
        val ip = p(c).getString("tvIp", null) ?: find(c) ?: return "I can't find the TV on your Wi-Fi. Are you home?"
        if (info(c, ip)?.optString("PowerState") != "on") {
            power(c, true)?.let { return it }
            Thread.sleep(4000)   // the TV answers before its app launcher is ready
        }
        // The TV's own launch endpoint first, then the same launch over the remote socket.
        if (tvRequest(ip, "POST", "/api/v2/applications/$id").startsWith("HTTP/1.1 200") && showing(ip, id)) return null
        key(c, ip, null, JSONObject().put("method", "ms.channel.emit").put("params", JSONObject()
            .put("event", "ed.apps.launch").put("to", "host").put("data", JSONObject().put("appId", id).put("action_type", "DEEP_LINK"))))?.let { return it }
        return if (showing(ip, id)) null else "I asked the TV to open ${app.replaceFirstChar { it.uppercase() }}, but it didn't come up."
    }

    private fun showing(ip: String, id: String): Boolean {
        for (i in 0 until 10) {
            Thread.sleep(500)
            val body = runCatching { tvRequest(ip, "GET", "/api/v2/applications/$id").substringAfter("\r\n\r\n") }.getOrDefault("")
            if (runCatching { JSONObject(body).optBoolean("visible") }.getOrDefault(false)) return true
        }
        return false
    }

    // ---------- playback: the remote's own buttons, so it works in any app (Netflix, HBO Max, YouTube, ...) ----------
    /**
     * pause / play / skip_back / skip_forward (seconds; the apps skip 10 s per arrow press) / home / back / ok.
     * Null when sent.
     */
    fun playback(c: Context, action: String, seconds: Int = 0): String? {
        val ip = p(c).getString("tvIp", null) ?: find(c) ?: return "I can't find the TV on your Wi-Fi. Are you home?"
        if (info(c, ip)?.optString("PowerState") != "on") return "The TV is off."
        val steps = maxOf(1, (seconds + 9) / 10).coerceAtMost(30)
        val keys = when (action) {
            "pause" -> listOf("KEY_PAUSE")
            "play" -> listOf("KEY_PLAY")
            "skip_back" -> List(steps) { "KEY_LEFT" }
            "skip_forward" -> List(steps) { "KEY_RIGHT" }
            "home" -> listOf("KEY_HOME")
            "back" -> listOf("KEY_RETURN")
            "ok" -> listOf("KEY_ENTER")
            else -> return "I don't know that TV button."
        }
        Log.i(TAG, "tv playback $action ${if (seconds > 0) "${seconds}s " else ""}-> ${keys.size} x ${keys.first()}")
        return key(c, ip, null, presses = keys)
    }

    // ---------- volume: the TV's own UPnP RenderingControl (port 9197), exact levels 0-100 ----------
    private fun soap(ip: String, action: String, args: String): String {
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"""
        return runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(ip, 9197), 1500); s.soTimeout = 3000
                val bytes = body.toByteArray()
                s.getOutputStream().write(("POST /upnp/control/RenderingControl1 HTTP/1.1\r\nHost: $ip:9197\r\nContent-Type: text/xml; charset=\"utf-8\"\r\n" +
                    "SOAPACTION: \"urn:schemas-upnp-org:service:RenderingControl:1#$action\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                s.getInputStream().bufferedReader().readText()
            }
        }.getOrDefault("")
    }

    fun volume(c: Context): Int? {
        val ip = p(c).getString("tvIp", null) ?: return null
        return Regex("<CurrentVolume>(\\d+)</CurrentVolume>").find(soap(ip, "GetVolume", "<Channel>Master</Channel>"))?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Set the volume to a level, or change it by delta. Null when done. */
    fun setVolume(c: Context, level: Int? = null, delta: Int = 0): String? {
        val ip = p(c).getString("tvIp", null) ?: find(c) ?: return "I can't find the TV on your Wi-Fi. Are you home?"
        val now = volume(c) ?: return "The TV isn't answering. Is it on?"
        val want = (level ?: (now + delta)).coerceIn(0, 100)
        val r = soap(ip, "SetVolume", "<Channel>Master</Channel><DesiredVolume>$want</DesiredVolume>")
        Log.i(TAG, "tv volume $now -> $want: ${if (r.startsWith("HTTP/1.1 200")) "ok" else r.lineSequence().firstOrNull()}")
        return if (volume(c) == want) null else "I asked the TV for volume $want, but it didn't take it."
    }

    fun mute(c: Context, on: Boolean): String? {
        val ip = p(c).getString("tvIp", null) ?: find(c) ?: return "I can't find the TV on your Wi-Fi. Are you home?"
        val r = soap(ip, "SetMute", "<Channel>Master</Channel><DesiredMute>${if (on) 1 else 0}</DesiredMute>")
        return if (r.startsWith("HTTP/1.1 200")) null else "The TV didn't take that. Is it on?"
    }

    /** At start-up: find the TV and learn its Wi-Fi address while it's on, so "turn it on" works later. */
    fun prime(c: Context) {
        val ip = runCatching { find(c) }.onFailure { Log.w(TAG, "tv lookup failed: ${it.message}") }.getOrNull()
        Log.i(TAG, "tv: ${ip ?: "not found"}, wake address ${if (p(c).getString("tvMac", null) != null) "known" else "unknown"}")
    }

    /** The TV's address: the last one that worked, re-found on the network if it has moved. */
    private fun find(c: Context): String? {
        val cached = p(c).getString("tvIp", null)
        if (cached != null && info(c, cached) != null) return cached
        // Not answering: it may have a new address, or it may simply be off (then the cached one is right for waking it).
        return discover(c) ?: scan(c) ?: cached
    }

    /** Backup when mDNS finds nothing: ask every address on the phone's Wi-Fi /24 for a Samsung TV (~2 s). */
    private fun scan(c: Context): String? {
        val me = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull() ?: return null
        val base = me.substringBeforeLast('.')
        val pool = java.util.concurrent.Executors.newFixedThreadPool(48)
        val hits = (1..254).map { n -> pool.submit<String?> { val ip = "$base.$n"; if (ip != me && probe(ip)) ip else null } }
        val found = hits.firstNotNullOfOrNull { runCatching { it.get(4, TimeUnit.SECONDS) }.getOrNull() }
        pool.shutdownNow()
        Log.i(TAG, "tv scan of $base.0/24: ${found ?: "none"}")
        found?.let { ip -> info(c, ip); p(c).edit().putString("tvIp", ip).apply() }
        return found
    }

    private fun probe(ip: String): Boolean = runCatching { tvGet(ip, 400).contains("Samsung", ignoreCase = true) }.getOrDefault(false)

    /** GET http://ip:8001/api/v2/ over a plain socket: Android's cleartext-HTTP block (rightly) stops
     *  HttpURLConnection, and this is the one plain-HTTP call the app makes, to the TV on the home network. */
    private fun tvGet(ip: String, connectMs: Int = 1500): String = tvRequest(ip, "GET", "/api/v2/", connectMs).substringAfter("\r\n\r\n", "")

    /** Raw HTTP to the TV's local API; returns the whole response, status line first. */
    private fun tvRequest(ip: String, method: String, path: String, connectMs: Int = 1500): String = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(ip, 8001), connectMs); s.soTimeout = 4000
            s.getOutputStream().write("$method $path HTTP/1.1\r\nHost: $ip:8001\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            s.getInputStream().bufferedReader().readText()
        }
    }.getOrDefault("")

    private fun discover(c: Context): String? {
        val nsd = c.getSystemService(NsdManager::class.java)
        val done = CountDownLatch(1)
        var found: String? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        @Suppress("DEPRECATION") val ip = r.host?.hostAddress ?: return
                        if (found == null && info(c, ip) != null) { found = ip; done.countDown() }
                    }
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                })
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { Log.w(TAG, "tv mDNS failed to start: $e"); done.countDown() }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        runCatching { nsd.discoverServices("_samsungmsf._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
        done.await(5, TimeUnit.SECONDS)
        runCatching { nsd.stopServiceDiscovery(listener) }
        found?.let { ip ->
            p(c).edit().putString("tvIp", ip).apply()
            Log.i(TAG, "tv found at $ip")
        }
        return found
    }

    /** The TV's own description (model, power state, Wi-Fi MAC). Remembers the MAC for waking it. */
    private fun info(c: Context, ip: String): JSONObject? = runCatching {
        val d = JSONObject(tvGet(ip)).getJSONObject("device")
        d.optString("wifiMac").takeIf { it.count { ch -> ch == ':' } == 5 }?.let { mac -> p(c).edit().putString("tvMac", mac).apply() }
        d
    }.getOrNull()

    private fun wake(mac: String, ip: String) {
        val m = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
        val packet = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { m.toList() }
        val bcast = ip.substringBeforeLast('.') + ".255"
        // Broadcast AND straight to the TV's own address: on 11-13 only the direct one woke it (tested from the Mac).
        var sent = 0
        DatagramSocket().use { s ->
            s.broadcast = true
            for (to in listOf(ip, bcast, "255.255.255.255")) for (port in listOf(9, 7))
                runCatching { s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(to), port)); sent++ }
                    .onFailure { Log.w(TAG, "tv wake to $to:$port failed: ${it.message}") }
        }
        Log.i(TAG, "tv wake: $sent packets to $mac via $ip / $bcast")
    }

    /** One remote-control key press. The first time, the TV shows an Allow prompt for "the assistant". */
    private fun key(c: Context, ip: String, k: String?, raw: JSONObject? = null, presses: List<String> = emptyList()): String? {
        val name = Base64.encodeToString(Persona.NAME.toByteArray(), Base64.NO_WRAP)
        val token = p(c).getString("tvToken", null)
        val url = "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name" + (token?.let { "&token=$it" } ?: "")
        // The TV's certificate is self-signed; trust it for this one address on the home network only.
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(a: Array<X509Certificate>, t: String) {}
            override fun checkServerTrusted(a: Array<X509Certificate>, t: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { h, _ -> h == ip }
            .readTimeout(35, TimeUnit.SECONDS).build()
        val done = CountDownLatch(1)
        var why: String? = "The TV didn't answer."
        client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (j.optString("event")) {
                    "ms.channel.connect" -> {
                        j.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }?.let { p(c).edit().putString("tvToken", it).apply() }
                        fun press(key: String) = ws.send(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
                            .put("Cmd", "Click").put("DataOfCmd", key).put("Option", "false").put("TypeOfRemote", "SendRemoteKey")).toString())
                        when {
                            raw != null -> ws.send(raw.toString())
                            presses.isNotEmpty() -> presses.forEachIndexed { i, pk -> if (i > 0) Thread.sleep(350); press(pk) }
                            else -> press(k!!)
                        }
                        why = null
                        Thread.sleep(400)
                        ws.close(1000, null); done.countDown()
                    }
                    "ms.channel.unauthorized" -> { why = "The TV said no. On the TV, allow ${Persona.NAME} under Settings, General, External Device Manager, Device Connect Manager, Device List."; ws.close(1000, null); done.countDown() }
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) { why = "I couldn't reach the TV's remote control: ${t.message}"; done.countDown() }
        })
        // The first time, the user has up to 30 s to press Allow on the TV.
        done.await(32, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        Log.i(TAG, "tv ${k ?: raw?.optString("method")}: ${why ?: "sent"}")
        return why
    }
}
