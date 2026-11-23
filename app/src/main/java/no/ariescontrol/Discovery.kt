package no.ariescontrol

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds the Aries on the home network.
 * 1) SSDP (UPnP): asks the network "which media players are here?" and picks the one made by AURALiC.
 * 2) If nothing answers: checks every address on the home network for the Aries web page.
 */
object Discovery {
    private val SEARCH_TARGETS = listOf(
        "ssdp:all",
        "upnp:rootdevice",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:av-openhome-org:service:Product:1",
    )

    /** The phone's Wi-Fi (or Ethernet) network, i.e. the home network. */
    @Suppress("DEPRECATION")
    fun lanNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    fun onLocalNetwork(ctx: Context) = lanNetwork(ctx) != null

    private fun localIpv4(ctx: Context): Inet4Address? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val n = lanNetwork(ctx) ?: return null
        return cm.getLinkProperties(n)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }

    /** Finds Auralic streamers. [onStep] gets short progress texts (called from a background thread). */
    suspend fun find(ctx: Context, onStep: (String) -> Unit): List<Streamer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, Streamer>()
        onStep("Looking for your Aries …")
        runCatching { ssdp(ctx) }.getOrDefault(emptyList()).forEach { found.putIfAbsent(hostOf(it.ip), it) }
        if (found.isEmpty()) {
            onStep("Checking every address on your network …")
            runCatching { scan(ctx, onStep) }.getOrDefault(emptyList()).forEach { found.putIfAbsent(hostOf(it.ip), it) }
        }
        found.values.toList()
    }

    // ---------- 1) SSDP ----------

    private suspend fun ssdp(ctx: Context): List<Streamer> {
        val answers = ssdpAnswers(ctx, SEARCH_TARGETS)
        val net = lanNetwork(ctx)
        return coroutineScope {
            answers.map { (loc, h) -> async { checkDevice(loc, h, net) } }.awaitAll().filterNotNull()
        }
    }

    /** Asks the network for UPnP devices; returns description URL -> answer headers ("_from" = sender IP). */
    suspend fun ssdpAnswers(ctx: Context, targets: List<String>, listenMs: Long = 3500): Map<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("aries-ssdp")?.apply { setReferenceCounted(false) }
        runCatching { lock?.acquire() }
        val answers = LinkedHashMap<String, Map<String, String>>()
        try {
            DatagramSocket().use { sock ->
                lanNetwork(ctx)?.let { n -> runCatching { n.bindSocket(sock) } }
                sock.soTimeout = 300
                val group = InetAddress.getByName("239.255.255.250")
                fun sendAll() {
                    for (st in targets) {
                        val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\nUSER-AGENT: Android UPnP/1.1 AriesControl/1.0\r\n\r\n").toByteArray()
                        runCatching { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                    }
                }
                sendAll()
                val start = SystemClock.elapsedRealtime()
                var resent = false
                val buf = ByteArray(8192)
                while (SystemClock.elapsedRealtime() - start < listenMs) {
                    if (!resent && SystemClock.elapsedRealtime() - start > 1200) { sendAll(); resent = true }
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (e: SocketTimeoutException) { continue }
                    val h = parseHeaders(String(p.data, 0, p.length))
                    val loc = h["location"] ?: continue
                    answers.putIfAbsent(loc, h + ("_from" to (p.address?.hostAddress ?: "")))
                }
            }
        } catch (e: Exception) {
            // no Wi-Fi / no multicast: no answers
        } finally {
            runCatching { lock?.release() }
        }
        answers
    }

    private fun parseHeaders(text: String): Map<String, String> =
        text.split("\r\n", "\n").mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()

    /** Reads the device description and keeps it if it is made by AURALiC. */
    private fun checkDevice(location: String, headers: Map<String, String>, net: Network?): Streamer? {
        val host = runCatching { URL(location).host }.getOrNull() ?: headers["_from"] ?: return null
        val desc = httpGet(location, 2500, net) ?: ""
        val manufacturer = tag(desc, "manufacturer")
        val model = tag(desc, "modelName")
        val friendly = tag(desc, "friendlyName")
        val all = listOf(headers["server"] ?: "", manufacturer, model, friendly).joinToString(" ").lowercase()
        if (!all.contains("auralic") && !all.contains("aries")) return null
        return Streamer(host, friendly.ifBlank { model.ifBlank { "Aries" } }, model, "ssdp")
    }

    // ---------- 2) Address scan ----------

    private suspend fun scan(ctx: Context, onStep: (String) -> Unit): List<Streamer> = coroutineScope {
        val me = localIpv4(ctx) ?: return@coroutineScope emptyList()
        val net = lanNetwork(ctx)
        val b = me.address
        val base = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
        val mine = b[3].toInt() and 255
        val targets = (1..254).filter { it != mine }
        val sem = Semaphore(40)
        val done = AtomicInteger()
        targets.map { i ->
            async {
                sem.withPermit {
                    val r = probe(base + i, net)
                    val n = done.incrementAndGet()
                    if (n % 20 == 0) onStep("Checking every address on your network … ${n * 100 / targets.size} %")
                    r
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun probe(ip: String, net: Network?): Streamer? {
        if (!portOpen(ip, 80, 350, net)) return null
        val html = httpGet("http://$ip/", 2000, net) ?: return null
        val lc = html.lowercase()
        if (!lc.contains("auralic") && !lc.contains("lightning") && !lc.contains("aries")) return null
        return Streamer(ip, tag(html, "title").ifBlank { "Aries" }, "", "scan")
    }

    // ---------- Helpers ----------

    /** Is something answering at this address (web page port)? */
    suspend fun reachable(address: String, timeoutMs: Int = 2000): Boolean = withContext(Dispatchers.IO) {
        val host = hostOf(address)
        val port = address.substringAfter(':', "80").toIntOrNull() ?: 80
        portOpen(host, port, timeoutMs, null)
    }

    /** Name of the streamer at an address the user typed (from its web page title), if any. */
    suspend fun nameAt(address: String): String? = withContext(Dispatchers.IO) {
        tag(httpGet("http://$address/", 2500, null) ?: "", "title").ifBlank { null }
    }

    private fun hostOf(address: String) = address.substringBefore(':')

    private fun portOpen(host: String, port: Int, timeoutMs: Int, net: Network?): Boolean = runCatching {
        val s = net?.socketFactory?.createSocket() ?: Socket()
        s.use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)

    fun httpGet(url: String, timeoutMs: Int, net: Network?, max: Int = 65536): String? = runCatching {
        val u = URL(url)
        val c = (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..399) return@runCatching null
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() < max) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }.getOrNull()

    fun tag(xml: String, name: String): String =
        Regex("<$name[^>]*>(.*?)</$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.get(1)?.trim()
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&#39;", "'")?.replace("&quot;", "\"")
            ?: ""
}
