package no.ariescontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Talks to the Aries the same way its own web page does:
 * - http://<ip>:9998 lists the Aries' settings services (Streamer, Processor, Hardware, Library …)
 * - each service answers OpenHome SOAP calls: Get…Config returns an XML that describes every setting,
 *   Set…Config takes <root><node type=".." id=".."><value>..</value></node></root>.
 */
data class AriesService(
    val name: String, val udn: String, val port: Int,
    val getAction: String, val setAction: String, val property: String,
) {
    val title: String
        get() = when (name) {
            "WebRendererConfig" -> "Streamer"
            "WebProcessorConfig" -> "Processor"
            "WebDeviceConfig" -> "Hardware"
            "ServerConfig" -> "Library"
            "WebDACConfig" -> "DAC"
            "WebClockConfig" -> "Clock"
            else -> name.removePrefix("Web").removeSuffix("Config")
        }
    val order: Int
        get() = listOf("WebRendererConfig", "WebProcessorConfig", "WebDACConfig", "WebClockConfig", "ServerConfig", "WebDeviceConfig")
            .indexOf(name).let { if (it < 0) 99 else it }
}

data class AriesInfo(val name: String, val serial: String, val services: List<AriesService>)

/** English texts from the Aries' own page (l10n/en.js): labels and help texts. */
class Labels(val labels: Map<String, String>, val comments: Map<String, String>) {
    fun label(key: String?): String? = key?.let { labels[it] }
    fun comment(key: String?): String? = key?.let { comments[it] }

    companion object {
        val EMPTY = Labels(emptyMap(), emptyMap())
    }
}

// ---------- Settings model (built from the XML the Aries sends) ----------

class Opt(val id: String, val label: String)
class Rule(val textRule: String, val min: Double?, val max: Double?, val minLen: Int?, val maxLen: Int?, val precision: Int?)
class Attached(val condition: String, val nodes: List<CNode>)

class CNode(
    val type: String,
    val id: String,
    val labelKey: String?,
    val label: String,
    val editable: Boolean,
    /** Switch: "1"/"0". List: selected option id. Text: the text. Compound: summary text. */
    val value: String,
    /** List: label of the current choice. */
    val valueLabel: String,
    val options: List<Opt>,
    val rule: Rule?,
    val password: Boolean,
    val attached: List<Attached>,
    val sub: List<CNode>,
    val warning: String?,
    val hasComment: Boolean,
)

class CGroup(val id: String, val labelKey: String?, val label: String, val nodes: List<CNode>, val menu: List<CNode>)

object Aries {
    private val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }

    private fun parseXml(text: String): Document =
        dbf.newDocumentBuilder().parse(ByteArrayInputStream(text.trim().toByteArray(Charsets.UTF_8)))

    private fun Element.children(name: String? = null): List<Element> {
        val out = ArrayList<Element>()
        val nl = childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && (name == null || n.tagName == name)) out.add(n)
        }
        return out
    }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()
    private fun Element.childText(name: String): String? = child(name)?.textContent?.trim()?.ifEmpty { null }

    private fun http(url: String, timeoutMs: Int = 6000, post: String? = null, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (post != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return body
        } finally { c.disconnect() }
    }

    private fun hostOnly(address: String) = address.substringBefore(':')

    /** Reads the list of settings services from port 9998. */
    suspend fun info(address: String): AriesInfo = withContext(Dispatchers.IO) {
        val doc = parseXml(http("http://${hostOnly(address)}:9998/", 4000))
        val root = doc.documentElement
        val services = ArrayList<AriesService>()
        for (d in root.children("device")) {
            val udn = d.childText("udn") ?: continue
            val port = d.childText("port")?.toIntOrNull() ?: continue
            for (s in d.children("service")) {
                val name = s.getAttribute("name")
                if (name.isEmpty()) continue
                var get = ""; var set = ""
                for (a in s.children("action")) when (a.getAttribute("type")) {
                    "get" -> get = a.textContent.trim()
                    "set" -> set = a.textContent.trim()
                }
                val prop = s.childText("property") ?: name.removePrefix("Web")
                if (get.isNotEmpty()) services.add(AriesService(name, udn, port, get, set, prop))
            }
        }
        AriesInfo(root.childText("device_name") ?: "Aries", root.childText("device_sn") ?: "", services.sortedBy { it.order })
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private suspend fun soap(address: String, svc: AriesService, action: String, args: List<Pair<String, String>>): Element = withContext(Dispatchers.IO) {
        val urn = "urn:av-openhome-org:service:${svc.name}:1"
        val argXml = args.joinToString("") { (k, v) -> "<$k>${esc(v)}</$k>" }
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" " +
            "xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:$action xmlns:u=\"$urn\">$argXml</u:$action></s:Body></s:Envelope>"
        val url = "http://${hostOnly(address)}:${svc.port}/${svc.udn}/av.openhome.org-${svc.name}-1/control"
        val text = http(url, 8000, body, mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPAction" to "\"$urn#$action\""))
        val doc = parseXml(text)
        // Find the …Response element
        fun find(e: Element): Element? {
            if (e.tagName.endsWith("Response")) return e
            for (c in e.children()) find(c)?.let { return it }
            return null
        }
        find(doc.documentElement) ?: throw java.io.IOException("Unexpected answer from the Aries")
    }

    /** Fetches the settings of one service. */
    suspend fun load(address: String, svc: AriesService, labels: Labels): List<CGroup> {
        val resp = soap(address, svc, svc.getAction, emptyList())
        val xml = resp.children().firstOrNull()?.textContent ?: throw java.io.IOException("Empty answer")
        return withContext(Dispatchers.Default) { parseConfig(xml, labels) }
    }

    /** Sends changed settings: [nodesXml] is one or more <node …><value>…</value></node>. */
    suspend fun set(address: String, svc: AriesService, nodesXml: String) {
        if (svc.setAction.isEmpty()) throw java.io.IOException("This part can't be changed")
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root>$nodesXml</root>"
        soap(address, svc, svc.setAction, listOf(svc.property to xml))
    }

    // ---------- Network setup (same commands as the Aries' own "Wireless Configuration") ----------

    class WifiNet(val ssid: String, val name: String, val signal: Int, val locked: Boolean, val current: Boolean)

    /** The Wi-Fi networks the Aries can see. */
    suspend fun wifiList(address: String, svc: AriesService): List<WifiNet> {
        val resp = soap(address, svc, "GetWiFiList", emptyList())
        val xml = resp.children().firstOrNull()?.textContent?.trim().orEmpty()
        if (xml.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            val root = parseXml(xml).documentElement
            val out = ArrayList<WifiNet>()
            for (g in root.children("group")) for (n in g.children("node")) {
                val v = n.child("value") ?: continue
                val raw = v.childText("SSID") ?: continue
                val ssid = decodeEscapes(raw)
                out.add(WifiNet(
                    ssid = raw,
                    name = ssid,
                    signal = v.childText("signal_strength")?.toIntOrNull() ?: 0,
                    locked = v.childText("encryption_type") == "on",
                    current = n.getAttribute("current") == "1",
                ))
            }
            // Current network first, then strongest; one entry per name
            out.sortedWith(compareByDescending<WifiNet> { it.current }.thenByDescending { it.signal }).distinctBy { it.ssid }
        }
    }

    /** Network names with special characters come as "\xF0\x9F…" (UTF-8 bytes). */
    fun decodeEscapes(s: String): String {
        if (!s.contains("\\x")) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (i + 3 < s.length && s[i] == '\\' && s[i + 1] == 'x') {
                val h = s.substring(i + 2, i + 4).toIntOrNull(16)
                if (h != null) { bytes.write(h); i += 4; continue }
            }
            bytes.write(s.substring(i, i + 1).toByteArray(Charsets.UTF_8)); i++
        }
        return bytes.toString("UTF-8")
    }

    /**
     * Asks the Aries to join a network, exactly like its web page does.
     * [interfaceType] "wlan0" (Wi-Fi) or "eth0" (cable). [encryption] only for a network typed in by hand.
     */
    suspend fun joinNetwork(
        address: String, svc: AriesService, interfaceType: String, ssid: String, password: String,
        encryption: String?, dhcp: Boolean, ip: String, netmask: String, gateway: String, dns: String,
    ) {
        val fields = LinkedHashMap<String, String>()
        fields["dhcp"] = if (dhcp) "1" else "0"
        fields["wifi_password"] = password
        fields["wifi_ssid"] = ssid
        if (encryption != null) fields["encryption_type"] = encryption
        fields["ip_address"] = if (dhcp) "" else ip
        fields["dns"] = if (dhcp) "" else dns
        fields["netmask"] = if (dhcp) "" else netmask
        fields["gateway"] = if (dhcp) "" else gateway
        fields["interface_type"] = interfaceType
        set(address, svc, fields.entries.joinToString("") { (k, v) -> "<node id=\"$k\"><value>${esc(v)}</value></node>" })
    }

    /** Makes the Aries start its own Wi-Fi hotspot. */
    suspend fun startHotspot(address: String, svc: AriesService) =
        set(address, svc, "<node id=\"interface_type\"><value>AP</value></node>")

    fun nodeXml(type: String, id: String, value: String) = "<node type=\"$type\" id=\"$id\"><value>${esc(value)}</value></node>"

    /** Labels and help texts from the Aries' own page. */
    suspend fun labels(address: String): Labels = withContext(Dispatchers.IO) {
        runCatching {
            val j = JSONObject(http("http://$address/l10n/en.js", 6000))
            val l = HashMap<String, String>()
            val c = HashMap<String, String>()
            for (section in listOf("server", "network-config", "nav", "general")) {
                val o = j.optJSONObject(section) ?: continue
                for (k in o.keys()) o.optString(k).takeIf { it.isNotEmpty() }?.let { l.putIfAbsent(k, it) }
            }
            j.optJSONObject("comment")?.let { o -> for (k in o.keys()) c[k] = o.optString(k) }
            Labels(l, c)
        }.getOrDefault(Labels.EMPTY)
    }

    // ---------- XML → model ----------

    private fun pretty(id: String) = id.replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun parseConfig(xml: String, labels: Labels): List<CGroup> {
        val root = parseXml(xml).documentElement
        return root.children("group").map { g ->
            val key = g.getAttribute("language_index").ifEmpty { null }
            CGroup(
                id = g.getAttribute("id"),
                labelKey = key,
                label = labels.label(key) ?: key?.let { pretty(it.removePrefix("group_")) } ?: "",
                nodes = g.children("node").map { parseNode(it, labels) },
                menu = g.children("menu").flatMap { m -> m.children("node").map { parseNode(it, labels) } },
            )
        }
    }

    private fun textOf(e: Element?, labels: Labels): String? {
        if (e == null) return null
        e.childText("language_index")?.let { return labels.label(it) ?: pretty(it) }
        return e.childText("display_name")
    }

    private fun parseNode(e: Element, labels: Labels): CNode {
        val type = e.getAttribute("type")
        val id = e.getAttribute("id")
        val key = e.child("key")
        val labelKey = key?.childText("language_index")
        val label = textOf(key, labels) ?: pretty(id)
        val v = e.child("value")
        val editable = v != null && v.getAttribute("editable") != "0"
        val ruleEl = v?.child("value_rule")
        val options = ruleEl?.children("option")?.map { o -> Opt(o.getAttribute("id"), textOf(o, labels) ?: o.getAttribute("id")) } ?: emptyList()
        val shownLabel = textOf(v, labels) ?: ""
        val value = when (type) {
            "switch" -> v?.childText("switch_status") ?: "0"
            "list" -> {
                val sel = ruleEl?.children("option")?.firstOrNull { it.getAttribute("selected") == "1" }?.getAttribute("id")
                sel ?: options.firstOrNull { it.label == shownLabel }?.id ?: options.firstOrNull()?.id ?: ""
            }
            else -> shownLabel
        }
        val valueLabel = if (type == "list") options.firstOrNull { it.id == value }?.label ?: shownLabel else shownLabel
        val rule = ruleEl?.let {
            Rule(
                textRule = it.childText("text_rule") ?: "",
                min = it.childText("min_range")?.toDoubleOrNull(),
                max = it.childText("max_range")?.toDoubleOrNull(),
                minLen = it.childText("min_length")?.toIntOrNull(),
                maxLen = it.childText("max_length")?.toIntOrNull(),
                precision = it.childText("precision")?.toIntOrNull(),
            )
        }
        val password = v?.child("display_name")?.getAttribute("password") == "1"
        val attached = e.children("attached_node").map { a -> Attached(a.getAttribute("value_condition"), a.children("node").map { parseNode(it, labels) }) }
        val sub = e.child("sub_node")?.children("node")?.map { parseNode(it, labels) } ?: emptyList()
        val warning = e.child("warning")?.let { w -> textOf(w, labels) }
        return CNode(
            type, id, labelKey, label, editable, value, valueLabel, options, rule, password, attached, sub, warning,
            hasComment = labels.comment(labelKey) != null,
        )
    }
}
