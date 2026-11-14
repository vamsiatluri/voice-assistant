package org.voiceassistant

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Here's what's on your Wi-Fi": finds smart devices the way they announce themselves (mDNS and
 * SSDP/UPnP), reads each one's own description, and says what the assistant can do with it.
 * Nothing is connected by scanning; pairing is a separate tap per device.
 */
object HomeScan {
    private const val TAG = "Assistant"

    enum class Support { CONNECT, PLANNED, LIMITED }

    data class Device(
        val ip: String,
        val name: String,
        val brand: String,
        val kind: String,          // TV, speaker, lights, hub, streaming box, ...
        val support: Support,
        val note: String,          // one line on what connecting means, or why it can't
        val via: String,           // how it was found (for the debug log)
    )

    /** Service types worth asking for. Each says something about what the device is. */
    private val MDNS = listOf(
        "_samsungmsf._tcp", "_googlecast._tcp", "_hue._tcp", "_sonos._tcp", "_home-assistant._tcp",
        "_airplay._tcp", "_raop._tcp", "_amzn-wplay._tcp", "_hap._tcp", "_matter._tcp",
        "_spotify-connect._tcp", "_roku-rcp._tcp", "_shelly._tcp", "_elg._tcp", "_ipp._tcp",
    )

    /** Blocking, ~6 s. Call off the main thread. */
    fun scan(c: Context): List<Device> {
        val wifi = c.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("assistant-home-scan").apply { setReferenceCounted(false); acquire() }
        val hits = ConcurrentHashMap<String, MutableList<Hint>>()   // ip -> everything learned about it
        try {
            val pool = Executors.newFixedThreadPool(2)
            pool.submit { runCatching { ssdp(hits) }.onFailure { Log.w(TAG, "home scan ssdp: ${it.message}") } }
            pool.submit { runCatching { mdns(c, hits) }.onFailure { Log.w(TAG, "home scan mdns: ${it.message}") } }
            pool.shutdown(); pool.awaitTermination(12, TimeUnit.SECONDS)
        } finally { lock.release() }
        val devices = hits.map { (ip, h) -> classify(ip, h) }.filterNotNull()
            .sortedWith(compareBy<Device>({ it.support.ordinal }, { it.kind }, { it.name }))
        Log.i(TAG, "home scan: ${devices.size} devices: " + devices.joinToString(" | ") { "${it.brand} ${it.kind} (${it.support}, ${it.via})" })
        save(c, devices)
        return devices
    }

    /** What one source said about an address. */
    private data class Hint(val via: String, val service: String = "", val name: String = "", val maker: String = "",
                            val model: String = "", val server: String = "", val st: String = "")

    // ---------- SSDP / UPnP ----------
    private fun ssdp(hits: ConcurrentHashMap<String, MutableList<Hint>>) {
        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n".toByteArray()
        val locations = ConcurrentHashMap<String, Hint>()
        DatagramSocket().use { s ->
            s.soTimeout = 500
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { s.send(DatagramPacket(msg, msg.size, group, 1900)); Thread.sleep(150) }
            val end = System.currentTimeMillis() + 3500
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                val headers = String(p.data, 0, p.length).split("\r\n").mapNotNull { l ->
                    l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() }
                }.toMap()
                val ip = p.address.hostAddress ?: continue
                val hint = Hint("ssdp", server = headers["server"].orEmpty(), st = headers["st"].orEmpty())
                hits.getOrPut(ip) { mutableListOf() }.add(hint)
                headers["location"]?.let { if (it.startsWith("http://")) locations.putIfAbsent(it, hint) }
            }
        }
        // Each device's own description: friendly name, manufacturer, model. One fetch per address is plenty.
        val pool = Executors.newFixedThreadPool(8)
        locations.keys.groupBy { runCatching { URI(it).host }.getOrNull() }.forEach { (host, urls) ->
            host ?: return@forEach
            pool.submit {
                for (u in urls.take(3)) {
                    val xml = httpGet(u) ?: continue
                    fun tag(t: String) = Regex("<$t>([^<]{1,120})</$t>").find(xml)?.groupValues?.get(1)?.trim().orEmpty()
                    val name = tag("friendlyName")
                    if (name.isBlank() && tag("manufacturer").isBlank()) continue
                    hits.getOrPut(host) { mutableListOf() }.add(Hint("upnp", name = unescape(name), maker = tag("manufacturer"), model = tag("modelName").ifBlank { tag("modelDescription") }))
                    break
                }
            }
        }
        pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
    }

    /** Plain HTTP GET on the home network over a socket (Android blocks cleartext HttpURLConnection). */
    private fun httpGet(url: String): String? = runCatching {
        val u = URI(url)
        Socket().use { s ->
            s.connect(InetSocketAddress(u.host, if (u.port > 0) u.port else 80), 800); s.soTimeout = 1500
            val path = (u.rawPath ?: "/").ifBlank { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: ${u.host}:${u.port}\r\nConnection: close\r\n\r\n".toByteArray())
            s.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n", "").take(20_000)
        }
    }.getOrNull()

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    // ---------- mDNS ----------
    private fun mdns(c: Context, hits: ConcurrentHashMap<String, MutableList<Hint>>) {
        val nsd = c.getSystemService(NsdManager::class.java)
        val found = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val listeners = MDNS.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(s: NsdServiceInfo) { found.add(s) }
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onServiceLost(s: NsdServiceInfo) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
            }.also { l -> runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) } }
        }
        // Resolve one at a time: older Android refuses overlapping resolves.
        val end = System.currentTimeMillis() + 5500
        val seen = HashSet<String>()
        while (System.currentTimeMillis() < end) {
            val s = found.poll(300, TimeUnit.MILLISECONDS) ?: continue
            if (!seen.add("${s.serviceType}|${s.serviceName}")) continue
            val done = CountDownLatch(1)
            runCatching {
                @Suppress("DEPRECATION")
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val ip = r.host?.hostAddress
                        if (ip != null && !ip.contains(':')) {
                            val attr = r.attributes.mapValues { (_, v) -> v?.let { String(it) }.orEmpty() }
                            val name = (attr["fn"] ?: attr["name"] ?: attr["n"] ?: r.serviceName).replace(Regex("^[0-9A-Fa-f]{12}@"), "")
                            hits.getOrPut(ip) { mutableListOf() }.add(Hint("mdns", service = r.serviceType.trim('.').lowercase(),
                                name = name, model = attr["md"] ?: attr["model"] ?: attr["am"] ?: attr["modelid"] ?: "", maker = attr["manufacturer"].orEmpty()))
                        }
                        done.countDown()
                    }
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) { done.countDown() }
                })
            }.onFailure { done.countDown() }
            done.await(2, TimeUnit.SECONDS)
        }
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
    }

    // ---------- what is it, and what can we do with it ----------
    private fun classify(ip: String, h: List<Hint>): Device? {
        val services = h.map { it.service }.filter { it.isNotBlank() }.toSet()
        fun has(s: String) = services.any { it.startsWith(s) }
        val text = h.joinToString(" ") { "${it.name} ${it.maker} ${it.model} ${it.server} ${it.st}" }.lowercase()
        fun says(vararg w: String) = w.any { text.contains(it) }
        val name = h.firstOrNull { it.via == "upnp" && it.name.isNotBlank() }?.name
            ?: h.firstOrNull { it.via == "mdns" && it.name.isNotBlank() && !it.name.matches(Regex("[0-9a-fA-F-]{20,}")) }?.name
        val via = h.map { it.via }.distinct().joinToString("+")
        val models = h.map { it.model }.filter { it.isNotBlank() }
        // Computers announce AirPlay / file sharing too; they aren't something to control by voice.
        val computer = models.any { it.matches(Regex("^(Mac|iMac|MacBook|Macmini).*", RegexOption.IGNORE_CASE)) } ||
            (name ?: "").contains(Regex("MacBook|iMac|Mac mini|Mac Studio|-MAC$|'s Mac|’s Mac|Windows|DESKTOP-|LAPTOP-"))
        fun d(brand: String, kind: String, support: Support, note: String) = Device(ip, name ?: "$brand $kind", brand, kind, support, note, via)

        return when {
            computer -> null
            // Routers and the phone's own network plumbing: not something to control by voice.
            says("internetgatewaydevice", "wanipconnection", "wfadevice") && !says("samsung", "sonos", "roku", "philips", "signify") -> null
            has("_samsungmsf") || (says("samsung") && says("tv", "tizen", "dmr", "mediarenderer")) ->
                d("Samsung", "TV", Support.CONNECT, "On/off, apps, volume, pause and skip. Press Allow on the TV once.")
            says("roku") || h.any { it.st.contains("roku:ecp") } ->
                d("Roku", "TV / streaming box", Support.PLANNED, "Roku control is next on the list: no pairing needed.")
            says("lg electronics", "webos") ->
                d("LG", "TV", Support.PLANNED, "LG webOS support is planned: you'll accept a prompt on the TV.")
            has("_hue") || says("philips hue", "signify", "ipbridge") ->
                d("Philips Hue", "lights", Support.PLANNED, "Hue support is planned: you'll press the button on the bridge.")
            has("_sonos") || says("sonos") ->
                d("Sonos", "speaker", Support.PLANNED, "Sonos support is planned: no pairing needed.")
            has("_home-assistant") ->
                d("Home Assistant", "hub", Support.PLANNED, "Planned: connect once with a token and control everything Home Assistant does.")
            has("_shelly") || says("shelly") ->
                d("Shelly", "switch", Support.PLANNED, "Shelly has an open local API; support is planned.")
            has("_elg") ->
                d("Elgato", "light", Support.PLANNED, "Elgato lights have an open local API; support is planned.")
            has("_googlecast") ->
                d("Google Cast", if (says("speaker", "home mini", "nest audio", "nest mini")) "speaker" else "TV / streaming", Support.LIMITED,
                    "Cast devices only take media you send them; there's no open way to switch them on or open apps.")
            has("_amzn-wplay") || says("amazon", "fire tv", "echo") ->
                d("Amazon", if (says("echo")) "Echo speaker" else "Fire TV", Support.LIMITED, "Amazon devices don't offer local control to other apps.")
            has("_airplay") || has("_raop") ->
                d("Apple", when { says("appletv", "apple tv") -> "Apple TV"; says("audioaccessory", "homepod") -> "HomePod"; else -> "AirPlay speaker" },
                    Support.LIMITED, "AirPlay devices need Apple's own pairing; not supported.")
            has("_hap") -> d("HomeKit", "accessory", Support.LIMITED, "Paired to Apple Home; not controllable from Android.")
            has("_matter") -> d("Matter", "device", Support.LIMITED, "Matter devices need Matter pairing; planned for later.")
            has("_spotify-connect") -> Device(ip, name?.takeUnless { it.matches(Regex("(?i)^(spotify ?connect.*|spzc-.*)$")) } ?: "Speaker at $ip",
                "Spotify Connect", "speaker", Support.LIMITED, "Plays music you send from Spotify; nothing else to control.", via)
            has("_ipp") || says("printer") -> null   // printers: found, but nothing to say to them
            says("mediarenderer", "dmr") -> d(h.firstOrNull { it.maker.isNotBlank() }?.maker ?: "Media", "player", Support.LIMITED, "A media player that accepts streams; nothing more.")
            name != null && h.any { it.maker.isNotBlank() } -> d(h.first { it.maker.isNotBlank() }.maker, "device", Support.LIMITED, "Not something the assistant knows yet.")
            else -> null
        }
    }

    // ---------- last scan, kept for the screen ----------
    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    private fun save(c: Context, ds: List<Device>) {
        val a = JSONArray(ds.map { JSONObject().put("ip", it.ip).put("name", it.name).put("brand", it.brand).put("kind", it.kind).put("support", it.support.name).put("note", it.note).put("via", it.via) })
        prefs(c).edit().putString("homeScan", a.toString()).putLong("homeScanAt", System.currentTimeMillis()).apply()
    }
    fun last(c: Context): List<Device> = runCatching {
        val a = JSONArray(prefs(c).getString("homeScan", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Device(it.getString("ip"), it.getString("name"), it.getString("brand"), it.getString("kind"), Support.valueOf(it.getString("support")), it.getString("note"), it.optString("via")) } }
    }.getOrDefault(emptyList())
}
