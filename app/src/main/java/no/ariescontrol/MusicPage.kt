package no.ariescontrol

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * The music dashboard: what the Aries is playing, play/pause, position, volume, sources, queue and standby.
 * Music is chosen in the TIDAL app (TIDAL Connect) or other OpenHome apps; this page controls the Aries.
 */
class MusicPage(
    private val act: AppCompatActivity,
    private val address: String,
    private val udnPorts: List<Pair<String, Int>>,
) {
    private val density = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    val view = FrameLayout(act)
    private val scroll = ScrollView(act).apply { isFillViewport = true }
    private val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
    private val bgDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.BG, Ui.BG))

    var renderer: Renderer? = null; private set
    private var findJob: Job? = null
    private var pollJob: Job? = null

    // Views
    private lateinit var art: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var quality: TextView
    private lateinit var seek: Slider
    private lateinit var tNow: TextView
    private lateinit var tEnd: TextView
    private lateinit var playBtn: ImageView
    private lateinit var shuffleBtn: ImageView
    private lateinit var repeatBtn: ImageView
    private lateinit var vol: Slider
    private lateinit var volIcon: ImageView
    private lateinit var volText: TextView
    private lateinit var sourceRow: LinearLayout
    private lateinit var queueBox: LinearLayout
    private lateinit var queueCard: LinearLayout
    private lateinit var powerBtn: MaterialButton
    private lateinit var sourceLabel: TextView

    // State
    private var sources: List<Source> = emptyList()
    private var sourceIndex = 0
    private var transport = "Playlist"
    private var playing = false
    private var standby = false
    private var shuffle = false
    private var repeat = false
    private var volMax = 100
    private var muted = false
    private var duration = 0
    private var draggingSeek = false
    private var draggingVol = false
    private var lastVolSent = 0L
    private var artUrl: String? = null
    private var queueToken = ""
    private var currentId = 0L

    init {
        view.background = bgDrawable
        scroll.addView(content)
        view.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Called when the page is shown. */
    fun start() {
        if (renderer == null) { if (findJob?.isActive != true) find() } else startPolling()
    }

    /** Called when the page is hidden. */
    fun stop() {
        pollJob?.cancel(); pollJob = null
        if (renderer == null) { findJob?.cancel(); findJob = null }
    }

    fun refresh() {
        if (renderer == null) find() else { queueToken = ""; startPolling() }
    }

    private fun find() {
        findJob?.cancel()
        findJob = act.lifecycleScope.launch {
            content.removeAllViews()
            message("Looking for the Aries' player …", busy = true)
            var r: Renderer? = null
            for (attempt in 1..3) {
                r = runCatching { OpenHome.find(act, address, udnPorts) }.getOrNull()
                if (r != null) break
                delay(2000)
            }
            if (r == null) {
                content.removeAllViews()
                message(
                    "Couldn't find the Aries' music player.\n\n" +
                        "In Setup → Streamer, check that “UPnP type” is set to Lightning Device (OpenHome), then tap Try again.",
                    busy = false,
                )
                content.addView(MaterialButton(act).apply { text = "Try again"; setOnClickListener { find() } }, lp().apply { topMargin = dp(16) })
                tidalButton()
                // Keep trying quietly while the page is open (the Aries may still be starting)
                delay(15_000)
                find()
                return@launch
            }
            renderer = r
            buildPlayer()
            startPolling()
        }
    }

    // ---------- Polling ----------

    private fun startPolling() {
        pollJob?.cancel()
        val r = renderer ?: return
        pollJob = act.lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                try {
                    if (tick % 5 == 0) {
                        if (r.has("Product")) {
                            if (sources.isEmpty()) { sources = OpenHome.sources(r); showSources() }
                            val si = OpenHome.sourceIndex(r)
                            val sb = runCatching { OpenHome.standby(r) }.getOrDefault(false)
                            if (si != sourceIndex || sb != standby) { sourceIndex = si; standby = sb; showSources() }
                        }
                        transport = OpenHome.transportFor(r, sources.getOrNull(sourceIndex)?.type ?: "Playlist")
                        if (r.has("Info")) showTrack(OpenHome.track(r))
                        val (sh, rp) = OpenHome.shuffleRepeat(r)
                        shuffle = sh; repeat = rp; showModes()
                        if (r.has("Volume") && tick == 0) { volMax = OpenHome.volumeMax(r).coerceAtLeast(1); vol.valueTo = volMax.toFloat() }
                        if (r.has("Playlist")) {
                            val tok = OpenHome.queueToken(r)
                            if (tok != queueToken) { queueToken = tok; val (items, cur) = OpenHome.queue(r); currentId = cur; showQueue(items) }
                            else { val cur = runCatching { OpenHome.call(r, "Playlist", "Id")["Value"]?.toLongOrNull() }.getOrNull() ?: currentId; if (cur != currentId) { currentId = cur; highlightQueue() } }
                        }
                    }
                    val st = runCatching { OpenHome.state(r, transport) }.getOrDefault("")
                    playing = st.equals("Playing", true) || st.equals("Buffering", true)
                    playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                    playBtn.contentDescription = if (playing) "Pause" else "Play"
                    if (r.has("Time")) {
                        val (sec, dur) = OpenHome.time(r)
                        duration = dur
                        if (!draggingSeek) {
                            seek.valueTo = dur.coerceAtLeast(1).toFloat()
                            seek.value = sec.coerceIn(0, dur.coerceAtLeast(1)).toFloat()
                            seek.isEnabled = dur > 0 && transport != "Radio"
                        }
                        tNow.text = fmt(sec); tEnd.text = if (dur > 0) fmt(dur) else ""
                    }
                    if (r.has("Volume") && !draggingVol && System.currentTimeMillis() - lastVolSent > 1500) {
                        val v = OpenHome.volume(r)
                        vol.value = v.coerceIn(0, volMax).toFloat(); volText.text = v.toString()
                        muted = runCatching { OpenHome.mute(r) }.getOrDefault(false)
                        volIcon.setImageResource(if (muted) R.drawable.ic_mute else R.drawable.ic_volume)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (tick % 10 == 0) snack("No contact with the Aries (${e.message ?: "timeout"})")
                }
                tick++
                delay(1000)
            }
        }
    }

    // ---------- Actions ----------

    private fun doAction(what: String, block: suspend (Renderer) -> Unit) {
        val r = renderer ?: return
        act.lifecycleScope.launch {
            try { block(r) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                snack("Couldn't $what (${e.message ?: "no answer"})")
            }
        }
    }

    private fun togglePlay() {
        val wasPlaying = playing
        playing = !playing
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        doAction("change playback") { r -> OpenHome.call(r, transport, if (wasPlaying) "Pause" else "Play") }
    }

    private fun skip(next: Boolean) = doAction("skip") { r ->
        val a = if (transport == "Transport") (if (next) "SkipNext" else "SkipPrevious") else (if (next) "Next" else "Previous")
        OpenHome.call(r, transport, a)
    }

    // ---------- Layout ----------

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun message(msg: String, busy: Boolean) {
        content.addView(TextView(act).apply { text = "Music"; setTextColor(Ui.TEXT); textSize = 30f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, lp())
        if (busy) content.addView(ProgressBar(act).apply { indeterminateTintList = ColorStateList.valueOf(Ui.ACCENT) },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(40); bottomMargin = dp(16) })
        content.addView(TextView(act).apply { text = msg; setTextColor(Ui.SUB); textSize = 16f; gravity = if (busy) Gravity.CENTER else Gravity.START }, lp().apply { topMargin = dp(16) })
    }

    private fun iconBtn(res: Int, desc: String, size: Int, tint: Int = Ui.TEXT, onClick: () -> Unit) = ImageView(act).apply {
        setImageResource(res); contentDescription = desc
        imageTintList = ColorStateList.valueOf(tint)
        val pad = size / 4; setPadding(pad, pad, pad, pad)
        val tv = TypedValue()
        if (act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
    }

    private fun buildPlayer() {
        content.removeAllViews()
        // Source and standby
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sourceLabel = TextView(act).apply { text = "NOW PLAYING"; setTextColor(Ui.ACCENT); textSize = 12f; letterSpacing = 0.1f; typeface = Typeface.DEFAULT_BOLD }
        top.addView(sourceLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        powerBtn = MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Standby"; textSize = 12f
            setIconResource(R.drawable.ic_power); iconSize = dp(16)
            setOnClickListener { val to = !standby; doAction("change standby") { r -> OpenHome.call(r, "Product", "SetStandby", "Value" to to.toString()); standby = to; showSources() } }
            visibility = if (renderer?.has("Product") == true) View.VISIBLE else View.GONE
        }
        top.addView(powerBtn)
        content.addView(top, lp())

        // Cover
        val artFrame = object : FrameLayout(act) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
        }
        art = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(Ui.CARD); cornerRadius = dp(20).toFloat() }
            clipToOutline = true
            setImageResource(R.drawable.ic_tab_music); imageTintList = ColorStateList.valueOf(Ui.STROKE)
            setPadding(dp(60), dp(60), dp(60), dp(60))
        }
        artFrame.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        artFrame.elevation = dp(12).toFloat()
        content.addView(artFrame, lp().apply { topMargin = dp(14) })

        title = TextView(act).apply { setTextColor(Ui.TEXT); textSize = 22f; typeface = Typeface.DEFAULT_BOLD; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; text = "–" }
        content.addView(title, lp().apply { topMargin = dp(18) })
        artist = TextView(act).apply { setTextColor(Ui.SUB); textSize = 16f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        content.addView(artist, lp().apply { topMargin = dp(2) })
        quality = TextView(act).apply {
            setTextColor(Ui.ACCENT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply { setStroke(dp(1), Ui.ACCENT); cornerRadius = dp(6).toFloat() }
            visibility = View.GONE
        }
        content.addView(quality, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        // Position
        seek = Slider(act).apply {
            valueFrom = 0f; valueTo = 1f; value = 0f; isEnabled = false
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) { draggingSeek = true }
                override fun onStopTrackingTouch(s: Slider) {
                    draggingSeek = false
                    val sec = s.value.toInt().toString()
                    doAction("seek") { r -> OpenHome.call(r, if (transport == "Playlist") "Playlist" else transport, "SeekSecondAbsolute", "Value" to sec) }
                }
            })
            addOnChangeListener { _, v, fromUser -> if (fromUser) tNow.text = fmt(v.toInt()) }
        }
        content.addView(seek, lp().apply { topMargin = dp(10) })
        val times = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0) }
        tNow = TextView(act).apply { setTextColor(Ui.SUB); textSize = 12f; text = "0:00" }
        tEnd = TextView(act).apply { setTextColor(Ui.SUB); textSize = 12f; gravity = Gravity.END }
        times.addView(tNow, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(tEnd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(times, lp())

        // Controls
        val ctrl = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        shuffleBtn = iconBtn(R.drawable.ic_shuffle, "Shuffle", dp(48), Ui.SUB) {
            val to = !shuffle; shuffle = to; showModes(); doAction("change shuffle") { r -> OpenHome.call(r, "Playlist", "SetShuffle", "Value" to to.toString()) }
        }
        val prev = iconBtn(R.drawable.ic_prev, "Previous", dp(64)) { skip(false) }
        playBtn = iconBtn(R.drawable.ic_play, "Play", dp(84), Color.parseColor("#1A1206")) { togglePlay() }.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT) }
            elevation = dp(6).toFloat()
        }
        val next = iconBtn(R.drawable.ic_next, "Next", dp(64)) { skip(true) }
        repeatBtn = iconBtn(R.drawable.ic_repeat, "Repeat", dp(48), Ui.SUB) {
            val to = !repeat; repeat = to; showModes(); doAction("change repeat") { r -> OpenHome.call(r, "Playlist", "SetRepeat", "Value" to to.toString()) }
        }
        ctrl.addView(shuffleBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        ctrl.addView(prev, LinearLayout.LayoutParams(dp(64), dp(64)).apply { leftMargin = dp(10) })
        ctrl.addView(playBtn, LinearLayout.LayoutParams(dp(76), dp(76)).apply { leftMargin = dp(10); rightMargin = dp(10) })
        ctrl.addView(next, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(10) })
        ctrl.addView(repeatBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(ctrl, lp().apply { topMargin = dp(6) })

        // Volume
        val volRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        volIcon = iconBtn(R.drawable.ic_volume, "Mute", dp(44), Ui.SUB) {
            val to = !muted; muted = to
            volIcon.setImageResource(if (to) R.drawable.ic_mute else R.drawable.ic_volume)
            doAction("mute") { r -> OpenHome.call(r, "Volume", "SetMute", "Value" to to.toString()) }
        }
        vol = Slider(act).apply {
            valueFrom = 0f; valueTo = 100f; value = 0f; stepSize = 1f
            isTickVisible = false
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) { draggingVol = true }
                override fun onStopTrackingTouch(s: Slider) { draggingVol = false; sendVolume(s.value.toInt()) }
            })
            addOnChangeListener { _, v, fromUser ->
                volText.text = v.toInt().toString()
                if (fromUser && System.currentTimeMillis() - lastVolSent > 200) sendVolume(v.toInt())
            }
        }
        volText = TextView(act).apply { setTextColor(Ui.SUB); textSize = 13f; gravity = Gravity.END; minWidth = dp(30) }
        volRow.addView(volIcon, LinearLayout.LayoutParams(dp(44), dp(44)))
        volRow.addView(vol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        volRow.addView(volText)
        if (renderer?.has("Volume") == true) content.addView(volRow, lp().apply { topMargin = dp(8) })

        // Sources
        content.addView(section("SOURCE"))
        sourceRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(sourceRow) }, lp())

        tidalButton()

        // Queue
        queueCard = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        queueCard.addView(section("QUEUE"))
        queueBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Ui.CARD); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Ui.STROKE) }
            clipToOutline = true
        }
        queueCard.addView(queueBox, lp())
        content.addView(queueCard, lp())
    }

    private fun tidalButton() {
        val pkg = "com.aspiro.tidal"
        val installed = act.packageManager.getLaunchIntentForPackage(pkg)
        content.addView(MaterialButton(act).apply {
            text = if (installed != null) "Choose music in TIDAL" else "Get the TIDAL app"
            setOnClickListener {
                runCatching {
                    if (installed != null) act.startActivity(installed)
                    else act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                }
            }
        }, lp().apply { topMargin = dp(20) })
        content.addView(TextView(act).apply {
            text = "In TIDAL, tap the speaker icon and choose the Aries (TIDAL Connect). It is then controlled from here."
            setTextColor(Ui.SUB); textSize = 13f
        }, lp().apply { topMargin = dp(4) })
    }

    private fun section(t: String) = TextView(act).apply {
        text = t; setTextColor(Ui.ACCENT); textSize = 12f; letterSpacing = 0.08f; typeface = Typeface.DEFAULT_BOLD
        layoutParams = lp().apply { topMargin = dp(24); bottomMargin = dp(8); leftMargin = dp(4) }
    }

    private fun sendVolume(v: Int) {
        lastVolSent = System.currentTimeMillis()
        doAction("change volume") { r -> OpenHome.call(r, "Volume", "SetVolume", "Value" to v.toString()) }
    }

    // ---------- Showing state ----------

    private fun showSources() {
        if (!::sourceRow.isInitialized) return
        sourceRow.removeAllViews()
        for (s in sources.filter { it.visible }) {
            val sel = s.index == sourceIndex
            sourceRow.addView(TextView(act).apply {
                text = s.name.ifBlank { s.type }
                textSize = 14f
                setTextColor(if (sel) Color.parseColor("#1A1206") else Ui.TEXT)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    if (sel) setColor(Ui.ACCENT) else { setColor(Ui.CARD); setStroke(dp(1), Ui.STROKE) }
                }
                setOnClickListener {
                    sourceIndex = s.index; showSources()
                    doAction("change source") { r -> OpenHome.call(r, "Product", "SetSourceIndex", "Value" to s.index.toString()) }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        val cur = sources.getOrNull(sourceIndex)
        sourceLabel.text = if (standby) "STANDBY" else "NOW PLAYING" + (cur?.let { " · " + it.name.ifBlank { it.type }.uppercase() } ?: "")
        powerBtn.text = if (standby) "Wake up" else "Standby"
    }

    private fun showModes() {
        if (!::shuffleBtn.isInitialized) return
        val has = renderer?.has("Playlist") == true && transport == "Playlist"
        shuffleBtn.visibility = if (has) View.VISIBLE else View.INVISIBLE
        repeatBtn.visibility = if (has) View.VISIBLE else View.INVISIBLE
        shuffleBtn.imageTintList = ColorStateList.valueOf(if (shuffle) Ui.ACCENT else Ui.SUB)
        repeatBtn.imageTintList = ColorStateList.valueOf(if (repeat) Ui.ACCENT else Ui.SUB)
    }

    private fun showTrack(t: Track) {
        title.text = t.title.ifBlank { if (standby) "Standby" else "Nothing playing" }
        artist.text = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" — ")
        val q = buildString {
            if (t.codec.isNotBlank()) append(t.codec.uppercase())
            if (t.bitDepth > 0 && t.sampleRate > 0) {
                if (isNotEmpty()) append("  ·  ")
                append("${t.bitDepth}-bit / ${khz(t.sampleRate)} kHz")
            } else if (t.bitRate > 0) {
                if (isNotEmpty()) append("  ·  ")
                append("${t.bitRate / 1000} kbps")
            }
        }
        quality.text = q
        quality.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        if (t.art != artUrl) { artUrl = t.art; loadArt(t.art) }
    }

    private fun khz(hz: Int): String {
        val k = hz / 1000.0
        return if (k == k.toLong().toDouble()) k.toLong().toString() else String.format("%.1f", k)
    }

    private fun loadArt(url: String?) {
        if (url == null) {
            art.setImageResource(R.drawable.ic_tab_music); art.imageTintList = ColorStateList.valueOf(Ui.STROKE); art.setPadding(dp(60), dp(60), dp(60), dp(60))
            tint(Ui.BG)
            return
        }
        act.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URL(url).openConnection(); c.connectTimeout = 4000; c.readTimeout = 8000
                    val bytes = c.getInputStream().use { it.readBytes() }
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var sample = 1
                    while (o.outWidth / sample > 1200) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            }
            if (url != artUrl) return@launch
            if (bmp == null) { loadArt(null); return@launch }
            art.imageTintList = null; art.setPadding(0, 0, 0, 0); art.setImageBitmap(bmp)
            tint(averageColor(bmp))
        }
    }

    private fun averageColor(b: Bitmap): Int = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)

    /** Colours the background softly after the cover. */
    private fun tint(c: Int) {
        val top = ColorUtils.blendARGB(c, Ui.BG, 0.55f)
        bgDrawable.colors = intArrayOf(top, Ui.BG, Ui.BG)
    }

    private var queueItems: List<QueueItem> = emptyList()

    private fun showQueue(items: List<QueueItem>) {
        queueItems = items
        queueCard.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        highlightQueue()
    }

    private fun highlightQueue() {
        queueBox.removeAllViews()
        for ((i, q) in queueItems.withIndex()) {
            if (i > 0) queueBox.addView(View(act).apply { setBackgroundColor(Ui.DIVIDER) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { leftMargin = dp(16) })
            val cur = q.id == currentId
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(11), dp(16), dp(11))
                val tv = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
                setOnClickListener { currentId = q.id; highlightQueue(); doAction("play that track") { r -> OpenHome.call(r, "Playlist", "SeekId", "Value" to q.id.toString()) } }
            }
            row.addView(TextView(act).apply {
                text = (if (cur) "▶  " else "") + q.title; textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (cur) Ui.ACCENT else Ui.TEXT); if (cur) typeface = Typeface.DEFAULT_BOLD
            })
            if (q.artist.isNotBlank()) row.addView(TextView(act).apply { text = q.artist; textSize = 13f; setTextColor(Ui.SUB); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            queueBox.addView(row)
        }
    }

    private fun fmt(s: Int): String = if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)

    private fun snack(msg: String) { runCatching { Snackbar.make(view, msg, Snackbar.LENGTH_SHORT).show() } }
}
