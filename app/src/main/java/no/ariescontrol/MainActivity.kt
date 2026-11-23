package no.ariescontrol

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Aries Control: finds the Auralic Aries on the home network and shows its own
 * settings page (the Lightning web interface) inside the app.
 */
class MainActivity : AppCompatActivity() {
    private val accent = Color.parseColor("#E0A84A")
    private val bg = Color.parseColor("#111418")

    private lateinit var web: WebView
    private lateinit var statusBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: LinearLayout
    private lateinit var title: TextView
    private lateinit var updateBtn: TextView

    private var current: Streamer? = null
    private var searchJob: Job? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.apkName = "AriesControl.apk"
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack() else finish()
            }
        })

        // Address given at start (used by the automatic test, harmless otherwise)
        intent?.getStringExtra("ip")?.let { Prefs.cleanAddress(it) }?.let { Prefs.save(this, Streamer(it, "Aries", "", "manual")) }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs().getBoolean("askedNotif", false)
        ) {
            prefs().edit().putBoolean("askedNotif", true).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        connect(forceSearch = false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Updater.available, Updater.status) { a, s -> a to s }.collect { (a, s) ->
                    updateBtn.visibility = if (a != null) View.VISIBLE else View.GONE
                    if (a != null) {
                        val busy = s.startsWith("Downloading") || s.startsWith("Installing") || s.startsWith("Allow") || s.startsWith("Update failed")
                        updateBtn.text = if (busy) s else "Update to version ${a.versionName}"
                    }
                }
            }
        }
        if (Updater.autoCheck(this)) lifecycleScope.launch { Updater.check(this@MainActivity, 3 * 3_600_000L, notify = true) }
        if (intent?.getBooleanExtra("aries_update", false) == true) lifecycleScope.launch { Updater.install(this@MainActivity) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("aries_update", false)) lifecycleScope.launch { Updater.install(this@MainActivity) }
    }

    // ---------- Finding and opening the Aries ----------

    private fun connect(forceSearch: Boolean) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            showStatus("Looking for your Aries …", busy = true)
            val saved = Prefs.load(this@MainActivity)
            if (!forceSearch && saved != null) {
                showStatus("Connecting to ${saved.name} …", busy = true)
                if (Discovery.reachable(saved.ip)) { open(saved); return@launch }
            }
            if (!Discovery.onLocalNetwork(this@MainActivity)) {
                showStatus("Your phone isn't connected to Wi-Fi.\n\nConnect it to the same home network as the Aries, then tap Search again.", busy = false)
                return@launch
            }
            val list = Discovery.find(this@MainActivity) { step -> runOnUiThread { if (progress.visibility == View.VISIBLE) statusText.text = step } }
            when {
                list.size == 1 -> { Prefs.save(this@MainActivity, list[0]); open(list[0]) }
                list.size > 1 -> choose(list)
                else -> showStatus(
                    "Couldn't find the Aries on your network.\n\n" +
                        "Check that it is switched on and connected to the same network as the phone, then tap Search again.\n\n" +
                        "You can also type in its IP address – it is shown on the Aries display.",
                    busy = false
                )
            }
        }
    }

    private fun choose(list: List<Streamer>) {
        showStatus("Found ${list.size} streamers.", busy = false)
        AlertDialog.Builder(this)
            .setTitle("Choose your streamer")
            .setItems(list.map { "${it.name}  (${it.ip})" }.toTypedArray()) { _, i ->
                Prefs.save(this, list[i]); open(list[i])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun open(s: Streamer) {
        current = s
        title.text = s.name.ifBlank { "Aries" }
        statusBox.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl("http://${s.ip}/")
    }

    private fun reload() {
        if (current != null && web.visibility == View.VISIBLE) web.reload() else connect(forceSearch = false)
    }

    private fun askAddress() {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(Prefs.load(this@MainActivity)?.ip ?: "")
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Aries IP address")
            .setMessage("The address is shown on the Aries display.")
            .setView(box)
            .setPositiveButton("Connect") { _, _ ->
                val addr = Prefs.cleanAddress(input.text.toString())
                if (addr == null) { toast("That doesn't look like an IP address"); return@setPositiveButton }
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    showStatus("Connecting to $addr …", busy = true)
                    if (Discovery.reachable(addr)) {
                        val s = Streamer(addr, Discovery.nameAt(addr) ?: "Aries", "", "manual")
                        Prefs.save(this@MainActivity, s); open(s)
                    } else {
                        showStatus("No answer from $addr.\n\nCheck the address, and that the phone is on the same Wi-Fi as the Aries.", busy = false)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStatus(msg: String, busy: Boolean) {
        web.visibility = View.GONE
        statusBox.visibility = View.VISIBLE
        statusText.text = msg
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        buttons.visibility = if (busy) View.GONE else View.VISIBLE
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Search for the Aries again")
        m.menu.add(0, 2, 1, "Enter IP address")
        m.menu.add(0, 3, 2, "Open in browser")
        m.menu.add(0, 4, 3, "Check for updates")
        m.menu.add(0, 5, 4, "Check for updates automatically").apply { isCheckable = true; isChecked = Updater.autoCheck(this@MainActivity) }
        m.menu.add(0, 6, 5, "About")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> connect(forceSearch = true)
                2 -> askAddress()
                3 -> current?.let { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${it.ip}/"))) } } ?: toast("No streamer connected yet")
                4 -> lifecycleScope.launch {
                    toast("Checking for updates …")
                    val u = Updater.check(this@MainActivity)
                    toast(if (u != null) "Version ${u.versionName} is available – tap the Update bar" else Updater.status.value)
                }
                5 -> prefs().edit().putBoolean("autoUpdate", !Updater.autoCheck(this)).apply()
                6 -> {
                    val (_, name) = Updater.currentVersion(this)
                    val c = current
                    AlertDialog.Builder(this)
                        .setTitle("Aries Control $name")
                        .setMessage(
                            "Shows the settings page of your AURALiC Aries.\n\n" +
                                (if (c != null) "Connected to: ${c.name}\nAddress: ${c.ip}" else "Not connected")
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            true
        }
        m.show()
    }

    // ---------- Layout ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(4), 0)
        }
        title = TextView(this).apply {
            text = "Aries Control"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton("⟳", "Reload") { reload() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(iconButton("⋮", "Menu") { showMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        updateBtn = TextView(this).apply {
            setTextColor(Color.parseColor("#1A1206"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(10).toFloat() }
            visibility = View.GONE
            setOnClickListener { lifecycleScope.launch { Updater.install(this@MainActivity) } }
        }
        root.addView(updateBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), 0, dp(12), dp(8))
        })

        val content = FrameLayout(this)
        web = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    val host = url.host ?: return false
                    val mine = current?.ip?.substringBefore(':')
                    // Links that lead away from the Aries open in the normal browser
                    if (mine != null && host != mine && (url.scheme == "http" || url.scheme == "https")) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                        return true
                    }
                    return false
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        showStatus(
                            "Lost contact with the Aries (${error.description}).\n\n" +
                                "Tap Search again – it may have got a new address.",
                            busy = false
                        )
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                // Lets the settings page pick a file (for example a firmware file), if it asks for one
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try { filePicker.launch(params.createIntent()); true } catch (e: Exception) { fileCallback = null; false }
                }
            }
        }
        content.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        progress = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(accent) }
        statusBox.addView(progress, LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(20) })
        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#D7DCE2"))
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        statusBox.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        buttons.addView(MaterialButton(this).apply {
            text = "Search again"
            setOnClickListener { connect(forceSearch = true) }
        }, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        buttons.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Enter IP address"
            setOnClickListener { askAddress() }
        }, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        statusBox.addView(buttons)
        content.addView(statusBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Keep clear of the status bar, navigation bar and keyboard (Android 15 draws edge to edge)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    private fun iconButton(symbol: String, label: String, onClick: (View) -> Unit) = TextView(this).apply {
        text = symbol
        contentDescription = label
        setTextColor(Color.WHITE)
        textSize = 22f
        gravity = Gravity.CENTER
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick(it) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
