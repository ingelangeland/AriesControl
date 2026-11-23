package no.ariescontrol

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

/**
 * Network setup for the Aries:
 * - if it isn't on any network, the phone first joins the Aries' own Wi-Fi hotspot,
 * - then the Aries is told which Wi-Fi to join (or to use the Ethernet cable / start its hotspot).
 * Extras: "address" (Aries already reachable), "mode" = wifi | wired | ap.
 */
class WifiSetupActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private lateinit var titleView: TextView

    private var address: String? = null
    private var svc: AriesService? = null
    private var hotspotNetwork: Network? = null
    private var pollJob: Job? = null
    private var stepBack: (() -> Unit)? = null

    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { stepBack?.invoke() ?: finish() }
        })
        address = intent.getStringExtra("address")
        val mode = intent.getStringExtra("mode") ?: "wifi"
        lifecycleScope.launch {
            val a = address
            if (a != null && findService(a)) {
                when (mode) {
                    "wired" -> showJoinForm(null, wired = true)
                    "ap" -> confirmHotspot()
                    else -> showWifiList()
                }
            } else showConnectStep()
        }
    }

    override fun onDestroy() {
        releaseHotspot()
        super.onDestroy()
    }

    // ---------- Step 0: reach the Aries ----------

    private fun showConnectStep() {
        stopPolling()
        stepBack = null
        clear("Set up the Aries network")
        text("If the Aries isn't connected to any network, it starts its own Wi-Fi hotspot (named something like “AURALiC-Lightning”). " +
            "Your phone connects to that hotspot, and then you choose which Wi-Fi the Aries should use.")
        text("Switch the Aries on and wait about a minute before you start.", Ui.SUB)
        text("1. Open Wi-Fi settings and connect the phone to the AURALiC network.\n2. Come back here and tap “I'm connected to the Aries hotspot”.\n\nAndroid may say the network has no internet – choose to stay connected.", Ui.SUB)
        button("Open Wi-Fi settings") { openWifiSettings() }
        button("I'm connected to the Aries hotspot", outlined = true) { lifecycleScope.launch { useCurrentWifi() } }
        button("The Aries is on my home network", outlined = true) { lifecycleScope.launch { searchHome() } }
    }

    private fun openWifiSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
    }

    private fun failConnect(msg: String) {
        clear("Set up the Aries network")
        text(msg)
        text("Check that the phone is connected to the AURALiC network in Wi-Fi settings, then try again.", Ui.SUB)
        button("Open Wi-Fi settings") { openWifiSettings() }
        button("I'm connected to the Aries hotspot", outlined = true) { lifecycleScope.launch { useCurrentWifi() } }
        button("The Aries is on my home network", outlined = true) { lifecycleScope.launch { searchHome() } }
        stepBack = { showConnectStep() }
    }

    private suspend fun useCurrentWifi() {
        val n = Discovery.lanNetwork(this)
        if (n == null) { failConnect("Your phone isn't connected to any Wi-Fi."); return }
        hotspotNetwork = n
        findOnNetwork(n)
    }

    /** Routes the app's traffic through this Wi-Fi (the hotspot has no internet) and finds the Aries on it. */
    private suspend fun findOnNetwork(network: Network) {
        busy("Connected. Looking for the Aries …")
        cm.bindProcessToNetwork(network)
        val candidates = LinkedHashSet<String>()
        cm.getLinkProperties(network)?.let { lp ->
            lp.routes.mapNotNull { it.gateway }.filterIsInstance<Inet4Address>().filter { !it.isAnyLocalAddress }.forEach { candidates.add(it.hostAddress ?: "") }
            if (android.os.Build.VERSION.SDK_INT >= 30) (lp.dhcpServerAddress as? Inet4Address)?.hostAddress?.let { candidates.add(it) }
        }
        candidates.remove("")
        for (c in candidates) if (findService(c)) { showWifiList(); return }
        // Not the gateway: search the network
        val found = withTimeoutOrNull(60_000) { Discovery.find(this@WifiSetupActivity) { s -> runOnUiThread { setBusyText(s) } } }.orEmpty()
        for (s in found) if (findService(s.ip)) { showWifiList(); return }
        failConnect("Connected to the Wi-Fi, but couldn't find the Aries on it.")
    }

    private suspend fun searchHome() {
        releaseHotspot()
        busy("Looking for the Aries on your network …")
        val saved = Prefs.load(this)
        if (saved != null && Discovery.reachable(saved.ip) && findService(saved.ip)) { showWifiList(); return }
        val found = Discovery.find(this) { s -> runOnUiThread { setBusyText(s) } }
        for (s in found) if (findService(s.ip)) { showWifiList(); return }
        failConnect("Couldn't find the Aries on your network.")
    }

    /** Reads the Aries' services at this address and keeps the one that handles network settings. */
    private suspend fun findService(a: String): Boolean {
        val info = runCatching { Aries.info(a) }.getOrNull() ?: return false
        val s = info.services.firstOrNull { it.name == "WebDeviceConfig" } ?: return false
        address = a; svc = s
        return true
    }

    // ---------- Step 1: choose a Wi-Fi ----------

    private fun showWifiList() {
        stopPolling()
        stepBack = null
        clear("Choose Wi-Fi for the Aries")
        text("Networks the Aries can see:", Ui.SUB)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = card().apply { addView(list) }
        body.addView(card, lp().apply { topMargin = dp(8) })
        val status = TextView(this).apply { text = "Searching …"; setTextColor(Ui.SUB); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        list.addView(status)

        val more = card()
        addCardRow(more, "Other network …", "Type the name of a hidden network") { showJoinForm(null, wired = false) }
        addCardRow(more, "Use the Ethernet cable instead", "The Aries uses its network socket") { showJoinForm(null, wired = true) }
        if (hotspotNetwork == null) addCardRow(more, "Start the Aries hotspot", "For setting up from scratch") { confirmHotspot() }
        body.addView(more, lp().apply { topMargin = dp(16) })

        pollJob = lifecycleScope.launch {
            var first = true
            while (isActive) {
                val a = address; val s = svc
                if (a == null || s == null) break
                val nets = runCatching { Aries.wifiList(a, s) }
                nets.onSuccess { l ->
                    list.removeAllViews()
                    if (l.isEmpty()) list.addView(status.apply { text = "No networks found yet …" })
                    for (n in l) {
                        val bars = when { n.signal >= 60 -> "▂▄▆█"; n.signal >= 40 -> "▂▄▆"; n.signal >= 20 -> "▂▄"; else -> "▂" }
                        addCardRow(list, n.name.ifBlank { "(hidden)" }, (if (n.current) "Connected · " else "") + bars + (if (n.locked) "  🔒" else ""),
                            highlight = n.current) {
                            showJoinForm(n, wired = false)
                        }
                    }
                }.onFailure { e ->
                    if (first) { list.removeAllViews(); list.addView(status.apply { text = "Couldn't get the list (${e.message}). Retrying …" }) }
                }
                first = false
                delay(5000)
            }
        }
    }

    // ---------- Step 2: password and IP settings ----------

    private fun showJoinForm(net: Aries.WifiNet?, wired: Boolean) {
        stopPolling()
        stepBack = { showWifiList() }
        val other = net == null && !wired
        clear(when { wired -> "Ethernet cable"; other -> "Other network"; else -> net!!.name })

        val ssidIn = if (other) field("Network name", "") else null
        var encryption = "WPA2"
        val encRow = if (other) {
            val tv = TextView(this)
            val pick = {
                val opts = arrayOf("None", "WEP", "WPA2")
                AlertDialog.Builder(this).setTitle("Encryption")
                    .setSingleChoiceItems(opts, opts.indexOf(encryption)) { d, i -> encryption = opts[i]; tv.text = "Encryption: $encryption"; d.dismiss() }
                    .show()
            }
            tv.apply { text = "Encryption: $encryption"; setTextColor(Ui.TEXT); textSize = 16f; setPadding(dp(4), dp(14), dp(4), dp(14)); setOnClickListener { pick() } }
            body.addView(tv)
            tv
        } else null

        val needsPassword = !wired && (other || net?.locked == true)
        val pwIn = if (needsPassword) field("Password", "", password = true) else null
        if (pwIn != null) {
            val show = MaterialSwitch(this).apply {
                text = "Show password"; setTextColor(Ui.SUB)
                setOnCheckedChangeListener { _, on ->
                    pwIn.inputType = InputType.TYPE_CLASS_TEXT or (if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                    pwIn.setSelection(pwIn.text.length)
                }
            }
            body.addView(show, lp())
        }

        val dhcp = MaterialSwitch(this).apply { text = "Get IP address automatically (DHCP)"; setTextColor(Ui.TEXT); isChecked = true }
        body.addView(dhcp, lp().apply { topMargin = dp(8) })
        val staticBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        body.addView(staticBox, lp())
        val ip = field("IP address", "", parent = staticBox, ipAddress = true)
        val mask = field("Subnet mask", "255.255.255.0", parent = staticBox, ipAddress = true)
        val gw = field("Gateway", "", parent = staticBox, ipAddress = true)
        val dns = field("DNS", "", parent = staticBox, ipAddress = true)
        dhcp.setOnCheckedChangeListener { _, on -> staticBox.visibility = if (on) View.GONE else View.VISIBLE }

        if (wired) text("Plug a network cable into the Aries before you continue.", Ui.SUB)
        button(if (wired) "Use cable" else "Join") {
            val ssid = when { wired -> "eth0"; other -> ssidIn!!.text.toString().trim(); else -> net!!.ssid }
            val pw = pwIn?.text?.toString().orEmpty()
            if (other && ssid.isEmpty()) { ssidIn!!.error = "Enter the network name"; return@button }
            if (pwIn != null && pw.isEmpty() && !(other && encryption == "None")) { pwIn.error = "Enter the password"; return@button }
            if (!dhcp.isChecked) {
                val re = Regex("^[1-9]\\d{0,2}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
                for (f in listOf(ip, mask, gw, dns)) if (!re.matches(f.text.toString().trim())) { f.error = "Not a valid address"; return@button }
            }
            join(ssid, net?.name ?: ssid, pw, if (other) encryption else null, wired, dhcp.isChecked,
                ip.text.toString().trim(), mask.text.toString().trim(), gw.text.toString().trim(), dns.text.toString().trim())
        }
    }

    private fun join(ssid: String, name: String, pw: String, enc: String?, wired: Boolean, dhcp: Boolean, ip: String, mask: String, gw: String, dns: String) {
        val a = address ?: return
        val s = svc ?: return
        busy(if (wired) "Telling the Aries to use the cable …" else "Telling the Aries to join “$name” …")
        lifecycleScope.launch {
            // The Aries changes network right away and may not answer – that is normal.
            val r = runCatching {
                withTimeoutOrNull(15_000) { Aries.joinNetwork(a, s, if (wired) "eth0" else "wlan0", ssid, pw, enc, dhcp, ip, mask, gw, dns) }
            }
            val err = r.exceptionOrNull()
            if (err is java.io.IOException && err.message?.startsWith("HTTP") == true) {
                busyDone("The Aries refused the settings (${err.message}).")
                button("Back") { showWifiList() }
                return@launch
            }
            showDone(name, wired)
        }
    }

    // ---------- Step 3: done ----------

    private fun showDone(name: String, wired: Boolean) {
        stepBack = null
        releaseHotspot()
        clear("Settings sent")
        text(if (wired) "The Aries is switching to the network cable." else "The Aries is now joining “$name”. This can take up to a minute; the display shows the new IP address when it is connected.")
        if (!wired) text("Make sure your phone is on the same network as the Aries, then tap Find the Aries.", Ui.SUB)
        button("Find the Aries") {
            setResult(RESULT_OK)
            startActivity(Intent(this, MainActivity::class.java).putExtra("search", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        button("Open Wi-Fi settings", outlined = true) { openWifiSettings() }
    }

    private fun confirmHotspot() {
        AlertDialog.Builder(this)
            .setTitle("Start the Aries hotspot")
            .setMessage("The Aries leaves your network and starts its own Wi-Fi hotspot. Connect your phone to it afterwards to set up Wi-Fi from scratch. Continue?")
            .setPositiveButton("Start hotspot") { _, _ ->
                val a = address ?: return@setPositiveButton
                val s = svc ?: return@setPositiveButton
                busy("Starting the hotspot …")
                lifecycleScope.launch {
                    runCatching { withTimeoutOrNull(15_000) { Aries.startHotspot(a, s) } }
                    clear("Hotspot started")
                    text("Wait about a minute, then connect your phone to the Aries hotspot.")
                    button("Open Wi-Fi settings") { openWifiSettings() }
                    button("I'm connected to the Aries hotspot", outlined = true) { lifecycleScope.launch { useCurrentWifi() } }
                    stepBack = { showConnectStep() }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> if (svc != null && body.childCount == 0) finish() }
            .setOnCancelListener { if (body.childCount == 0) finish() }
            .show()
    }

    private fun releaseHotspot() {
        stopPolling()
        runCatching { cm.bindProcessToNetwork(null) }
        hotspotNetwork = null
    }

    private fun stopPolling() { pollJob?.cancel(); pollJob = null }

    // ---------- Layout helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(16), 0) }
        bar.addView(TextView(this).apply {
            text = "←"; setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            contentDescription = "Back"
            val tv = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setBackgroundResource(tv.resourceId)
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1 }
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(32)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    private fun clear(title: String) {
        body.removeAllViews()
        titleView.text = "Network setup"
        body.addView(TextView(this).apply {
            text = title; setTextColor(Ui.TEXT); textSize = 26f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lp().apply { topMargin = dp(8); bottomMargin = dp(8) })
    }

    private var busyText: TextView? = null
    private fun busy(msg: String) {
        stopPolling()
        clear("Please wait")
        body.addView(ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT) },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(24); bottomMargin = dp(16) })
        busyText = text(msg)
    }
    private fun setBusyText(s: String) { busyText?.text = s }
    private fun busyDone(msg: String) { clear("Something went wrong"); text(msg) }

    private fun text(s: String, color: Int = Ui.TEXT) = TextView(this).apply {
        text = s; setTextColor(color); textSize = 16f; setLineSpacing(0f, 1.15f)
    }.also { body.addView(it, lp().apply { topMargin = dp(8) }) }

    private fun button(label: String, outlined: Boolean = false, onClick: () -> Unit) {
        val b = if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) else MaterialButton(this)
        b.text = label
        b.setOnClickListener { onClick() }
        body.addView(b, lp().apply { topMargin = dp(if (outlined) 6 else 20) })
    }

    private fun field(hint: String, value: String, password: Boolean = false, parent: LinearLayout = body, ipAddress: Boolean = false): EditText {
        val e = EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine()
            setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                ipAddress -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        parent.addView(TextView(this).apply { text = hint; setTextColor(Ui.SUB); textSize = 13f }, lp().apply { topMargin = dp(12) })
        parent.addView(e, lp())
        return e
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Ui.CARD); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Ui.STROKE) }
        clipToOutline = true
    }

    private fun addCardRow(card: LinearLayout, title: String, sub: String?, highlight: Boolean = false, onClick: () -> Unit) {
        if (card.childCount > 0) card.addView(View(this).apply { setBackgroundColor(Ui.DIVIDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { leftMargin = dp(16) })
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            val tv = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
        r.addView(TextView(this).apply { text = title; setTextColor(if (highlight) Ui.ACCENT else Ui.TEXT); textSize = 16f })
        if (!sub.isNullOrEmpty()) r.addView(TextView(this).apply { text = sub; setTextColor(Ui.SUB); textSize = 13f })
        card.addView(r)
    }
}
