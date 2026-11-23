package no.ariescontrol

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Aries' music player, controlled with the open OpenHome standard
 * (the same one Linn Kazoo, Lumin and BubbleUPnP use): Product (sources, standby), Info (track),
 * Time (position), Volume, Playlist (queue, play/pause), Transport (other sources), Radio, Credentials.
 */
class UpnpService(val type: String, val control: String) {
    /** "Playlist", "Volume", "AVTransport" … */
    val short: String get() = type.split(':').getOrNull(3) ?: type
}

class Renderer(val name: String, val descriptionUrl: String, val services: Map<String, UpnpService>) {
    fun has(s: String) = services.containsKey(s)
}

class Source(val index: Int, val name: String, val type: String, val visible: Boolean)
class QueueItem(val id: Long, val title: String, val artist: String)

class Track(
    val title: String, val artist: String, val album: String, val art: String?,
    val codec: String, val bitDepth: Int, val sampleRate: Int, val bitRate: Int, val lossless: Boolean,
)

object OpenHome {
    private val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }

    private fun parse(text: String): Element =
        dbf.newDocumentBuilder().parse(ByteArrayInputStream(text.trim().toByteArray(Charsets.UTF_8))).documentElement

    private fun Element.kids(name: String? = null): List<Element> {
        val out = ArrayList<Element>()
        val nl = childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && (name == null || n.tagName == name || n.tagName.endsWith(":$name"))) out.add(n)
        }
        return out
    }

    private fun Element.all(name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) { for (k in e.kids()) { if (k.tagName == name || k.tagName.endsWith(":$name")) out.add(k); walk(k) } }
        walk(this)
        return out
    }

    private fun Element.text(name: String) = kids(name).firstOrNull()?.textContent?.trim().orEmpty()

    // ---------- Finding the player ----------

    /**
     * Finds the OpenHome player on the Aries at [ip]: first by asking the network (SSDP),
     * then by trying the Aries' own devices ([udnPorts] from port 9998) at their usual description address.
     */
    suspend fun find(ctx: Context, ip: String, udnPorts: List<Pair<String, Int>>): Renderer? = withContext(Dispatchers.IO) {
        val host = ip.substringBefore(':')
        val tried = HashSet<String>()
        fun tryUrl(u: String): Renderer? {
            if (!tried.add(u)) return null
            val xml = Discovery.httpGet(u, 2500, null, 256 * 1024) ?: return null
            return runCatching { parseDescription(u, xml) }.getOrNull()?.takeIf { it.has("Product") || it.has("Playlist") || it.has("AVTransport") }
        }
        // 1) The Aries' own devices (fast, works without multicast)
        for ((udn, port) in udnPorts) {
            for (path in listOf("/$udn/Upnp/device.xml", "/$udn/device.xml")) tryUrl("http://$host:$port$path")?.let { return@withContext it }
        }
        // 2) Ask the network
        val answers = Discovery.ssdpAnswers(ctx, listOf("urn:av-openhome-org:service:Product:1", "urn:schemas-upnp-org:device:MediaRenderer:1", "ssdp:all"))
        val mine = answers.keys.filter { runCatching { URL(it).host }.getOrNull() == host }
        val best = mine.sortedByDescending { answers[it]?.get("st")?.contains("openhome") == true }
        for (loc in best) tryUrl(loc)?.let { return@withContext it }
        null
    }

    private fun parseDescription(url: String, xml: String): Renderer {
        val root = parse(xml)
        val base = root.text("URLBase").ifEmpty { url }
        val services = LinkedHashMap<String, UpnpService>()
        var name = ""
        for (dev in root.all("device")) {
            if (name.isEmpty()) name = dev.text("friendlyName")
            for (sl in dev.kids("serviceList")) for (s in sl.kids("service")) {
                val type = s.text("serviceType")
                val ctrl = s.text("controlURL")
                if (type.isEmpty() || ctrl.isEmpty()) continue
                val svc = UpnpService(type, URL(URL(base), ctrl).toString())
                // Prefer OpenHome over plain UPnP when both have the same short name
                if (!services.containsKey(svc.short) || type.contains("openhome")) services[svc.short] = svc
            }
        }
        return Renderer(name.ifEmpty { "Aries" }, url, services)
    }

    // ---------- SOAP ----------

    suspend fun call(r: Renderer, service: String, action: String, vararg args: Pair<String, String>): Map<String, String> = withContext(Dispatchers.IO) {
        val svc = r.services[service] ?: throw java.io.IOException("The Aries has no $service service")
        val argXml = args.joinToString("") { (k, v) -> "<$k>${Aries.esc(v)}</$k>" }
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" " +
            "xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:$action xmlns:u=\"${svc.type}\">$argXml</u:$action></s:Body></s:Envelope>"
        val c = URL(svc.control).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000; c.readTimeout = 6000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPAction", "\"${svc.type}#$action\"")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("$action: HTTP $code")
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val resp = parse(text).all("${action}Response").firstOrNull() ?: return@withContext emptyMap()
            resp.kids().associate { it.tagName.substringAfter(':') to it.textContent }
        } finally { c.disconnect() }
    }

    // ---------- Reading ----------

    suspend fun sources(r: Renderer): List<Source> {
        val xml = call(r, "Product", "SourceXml")["Value"].orEmpty()
        if (xml.isBlank()) return emptyList()
        return runCatching {
            parse(xml).kids("Source").mapIndexed { i, s ->
                Source(i, s.text("Name"), s.text("Type"), s.text("Visible").let { it.isEmpty() || it == "true" || it == "1" })
            }
        }.getOrDefault(emptyList())
    }

    suspend fun sourceIndex(r: Renderer) = call(r, "Product", "SourceIndex")["Value"]?.toIntOrNull() ?: 0

    suspend fun standby(r: Renderer) = call(r, "Product", "Standby")["Value"].let { it == "true" || it == "1" }

    suspend fun track(r: Renderer): Track {
        val t = call(r, "Info", "Track")
        val meta = t["Metadata"].orEmpty()
        val d = runCatching { call(r, "Info", "Details") }.getOrDefault(emptyMap())
        fun m(tag: String) = Regex("<$tag[^>]*>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL)).find(meta)?.groupValues?.get(1)?.let { unesc(it).trim() }.orEmpty()
        return Track(
            title = m("dc:title"),
            artist = m("upnp:artist").ifEmpty { m("dc:creator") },
            album = m("upnp:album"),
            art = m("upnp:albumArtURI").ifEmpty { null },
            codec = d["CodecName"].orEmpty(),
            bitDepth = d["BitDepth"]?.toIntOrNull() ?: 0,
            sampleRate = d["SampleRate"]?.toIntOrNull() ?: 0,
            bitRate = d["BitRate"]?.toIntOrNull() ?: 0,
            lossless = d["Lossless"] == "true" || d["Lossless"] == "1",
        )
    }

    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

    /** Seconds played and track length. */
    suspend fun time(r: Renderer): Pair<Int, Int> {
        val t = call(r, "Time", "Time")
        return (t["Seconds"]?.toIntOrNull() ?: 0) to (t["Duration"]?.toIntOrNull() ?: 0)
    }

    suspend fun volume(r: Renderer): Int = call(r, "Volume", "Volume")["Value"]?.toIntOrNull() ?: 0
    suspend fun volumeMax(r: Renderer): Int = runCatching { call(r, "Volume", "VolumeLimit")["Value"]?.toIntOrNull() }.getOrNull()
        ?: runCatching { call(r, "Volume", "Characteristics")["VolumeMax"]?.toIntOrNull() }.getOrNull() ?: 100
    suspend fun mute(r: Renderer) = call(r, "Volume", "Mute")["Value"].let { it == "true" || it == "1" }

    /** Which service runs play/pause for the current source. */
    fun transportFor(r: Renderer, sourceType: String): String = when {
        sourceType.equals("Playlist", true) && r.has("Playlist") -> "Playlist"
        sourceType.equals("Radio", true) && r.has("Radio") -> "Radio"
        r.has("Transport") -> "Transport"
        r.has("Playlist") -> "Playlist"
        else -> "Radio"
    }

    /** "Playing", "Paused", "Stopped", "Buffering" … */
    suspend fun state(r: Renderer, transport: String): String = when (transport) {
        "Transport" -> call(r, "Transport", "TransportState")["State"] ?: call(r, "Transport", "TransportState")["Value"].orEmpty()
        else -> call(r, transport, "TransportState")["Value"].orEmpty()
    }

    suspend fun shuffleRepeat(r: Renderer): Pair<Boolean, Boolean> {
        if (!r.has("Playlist")) return false to false
        val s = runCatching { call(r, "Playlist", "Shuffle")["Value"] }.getOrNull()
        val p = runCatching { call(r, "Playlist", "Repeat")["Value"] }.getOrNull()
        return (s == "true" || s == "1") to (p == "true" || p == "1")
    }

    /** The queue (Playlist source): token changes when the list changes. */
    suspend fun queueToken(r: Renderer): String = call(r, "Playlist", "IdArray")["Token"].orEmpty()

    suspend fun queue(r: Renderer, max: Int = 150): Pair<List<QueueItem>, Long> {
        val ids = idArray(call(r, "Playlist", "IdArray")["Array"].orEmpty()).take(max)
        val current = runCatching { call(r, "Playlist", "Id")["Value"]?.toLongOrNull() }.getOrNull() ?: 0L
        if (ids.isEmpty()) return emptyList<QueueItem>() to current
        val items = ArrayList<QueueItem>()
        for (chunk in ids.chunked(40)) {
            val xml = call(r, "Playlist", "ReadList", "IdList" to chunk.joinToString(" "))["TrackList"].orEmpty()
            if (xml.isBlank()) continue
            runCatching {
                for (e in parse(xml).kids("Entry")) {
                    val meta = e.text("Metadata")
                    fun m(tag: String) = Regex("<$tag[^>]*>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL)).find(meta)?.groupValues?.get(1)?.let { unesc(it).trim() }.orEmpty()
                    items.add(QueueItem(e.text("Id").toLongOrNull() ?: 0, m("dc:title").ifEmpty { "Track" }, m("upnp:artist").ifEmpty { m("dc:creator") }))
                }
            }
        }
        return items to current
    }

    private fun idArray(b64: String): List<Long> {
        if (b64.isBlank()) return emptyList()
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Long>()
        var i = 0
        while (i + 3 < bytes.size) {
            out.add(((bytes[i].toLong() and 0xff) shl 24) or ((bytes[i + 1].toLong() and 0xff) shl 16) or ((bytes[i + 2].toLong() and 0xff) shl 8) or (bytes[i + 3].toLong() and 0xff))
            i += 4
        }
        return out
    }

    /** Whether the Aries takes logins for streaming services from other apps (OpenHome Credentials). */
    suspend fun credentialIds(r: Renderer): String? {
        if (!r.has("Credentials")) return null
        return runCatching { call(r, "Credentials", "GetIds")["Ids"] }.getOrNull() ?: ""
    }
}
