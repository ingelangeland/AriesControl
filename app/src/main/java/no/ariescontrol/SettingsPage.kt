package no.ariescontrol

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Colours for the settings screens. */
object Ui {
    val BG = Color.parseColor("#111418")
    val CARD = Color.parseColor("#1A1F26")
    val STROKE = Color.parseColor("#262C35")
    val DIVIDER = Color.parseColor("#232932")
    val TEXT = Color.parseColor("#F2F4F7")
    val SUB = Color.parseColor("#98A2B0")
    val ACCENT = Color.parseColor("#E0A84A")
    val DANGER = Color.parseColor("#FF7A6B")
}

/**
 * One tab (Streamer, Processor, Hardware, Library …): draws the Aries' settings as cards
 * and sends changes straight back to the Aries.
 */
class SettingsPage(
    private val act: AppCompatActivity,
    private val address: String,
    val svc: AriesService,
    private val deviceName: String,
    private val labels: Labels,
    private val openClassic: () -> Unit,
) {
    private val dp = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dp).toInt()

    val view: FrameLayout = FrameLayout(act)
    private val scroll = ScrollView(act).apply { isFillViewport = true; clipToPadding = false }
    private val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)) }
    private val loading = ProgressBar(act).apply { indeterminateTintList = ColorStateList.valueOf(Ui.ACCENT) }

    private var groups: List<CGroup>? = null
    private var loadError: String? = null
    private val overrides = HashMap<String, String>()                 // values just changed (until the Aries confirms)
    private val pending = HashMap<String, HashMap<String, String>>()  // unsaved edits inside a group card (compound)
    private val expanded = HashSet<String>()
    private var loadJob: Job? = null

    init {
        scroll.addView(content)
        view.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.addView(loading, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        loading.visibility = View.GONE
    }

    /** Fetches the settings from the Aries again. */
    fun reload(quiet: Boolean = false) {
        loadJob?.cancel()
        loadJob = act.lifecycleScope.launch {
            if (!quiet || groups == null) loading.visibility = View.VISIBLE
            try {
                groups = Aries.load(address, svc, labels)
                loadError = null
                overrides.clear()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loadError = e.message ?: e.javaClass.simpleName
            }
            loading.visibility = View.GONE
            render()
        }
    }

    // ---------- Drawing ----------

    private fun render() {
        content.removeAllViews()
        content.addView(TextView(act).apply {
            text = svc.title
            setTextColor(Ui.TEXT); textSize = 30f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lp().apply { topMargin = dp(8) })
        content.addView(TextView(act).apply {
            text = deviceName
            setTextColor(Ui.SUB); textSize = 14f
        }, lp().apply { bottomMargin = dp(12) })

        val gs = groups
        if (gs == null) {
            if (loadError != null) {
                content.addView(TextView(act).apply {
                    text = "Couldn't read the settings from the Aries.\n($loadError)"
                    setTextColor(Ui.SUB); textSize = 15f
                }, lp().apply { topMargin = dp(24) })
                content.addView(MaterialButton(act).apply { text = "Try again"; setOnClickListener { reload() } }, lp().apply { topMargin = dp(16) })
                content.addView(MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Open classic view"; setOnClickListener { openClassic() }
                }, lp())
            }
            return
        }
        for (g in gs) {
            if (g.nodes.isEmpty() && g.menu.isEmpty()) continue
            // Section title
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(act).apply {
                text = g.label.uppercase()
                setTextColor(Ui.ACCENT); textSize = 12f; letterSpacing = 0.08f
                typeface = Typeface.DEFAULT_BOLD
            })
            labels.comment(g.labelKey)?.let { c -> head.addView(infoIcon(g.label, c)) }
            content.addView(head, lp().apply { topMargin = dp(22); bottomMargin = dp(8); leftMargin = dp(4) })

            val card = card()
            for (n in g.nodes) addNode(card, n, null, 0)
            if (g.menu.isNotEmpty()) {
                addRow(card, row(g.menu.joinToString(" · ") { it.label }, "Open in classic view", null, 0, chevron()) { openClassic() })
            }
            content.addView(card, lp())
        }
        if (loadError != null) {
            content.addView(TextView(act).apply {
                text = "Last update failed: $loadError"; setTextColor(Ui.DANGER); textSize = 13f
            }, lp().apply { topMargin = dp(16) })
        }
    }

    private fun current(n: CNode, comp: CNode?): String =
        comp?.let { pending[it.id]?.get(n.id) } ?: overrides[n.id] ?: n.value

    private fun addNode(card: LinearLayout, n: CNode, comp: CNode?, indent: Int) {
        val info = labels.comment(n.labelKey)
        when (n.type) {
            "switch" -> {
                val on = current(n, comp) == "1"
                val sw = MaterialSwitch(act).apply {
                    isChecked = on
                    isEnabled = n.editable
                    setOnCheckedChangeListener { _, checked -> change(n, if (checked) "1" else "0", comp) }
                }
                addRow(card, row(n.label, null, info, indent, sw) { if (n.editable) sw.toggle() })
                addAttached(card, n, comp, indent)
            }
            "list" -> {
                val cur = current(n, comp)
                val curLabel = n.options.firstOrNull { it.id == cur }?.label ?: n.valueLabel
                val canPick = n.editable && n.options.size > 1
                addRow(card, row(n.label, curLabel, info, indent, if (canPick) chevron() else null) {
                    if (canPick) pickOption(n, cur, comp)
                })
                addAttached(card, n, comp, indent)
            }
            "text" -> {
                val cur = current(n, comp)
                val shown = if (n.password && cur.isNotEmpty()) "••••" else cur
                if (n.editable) {
                    addRow(card, row(n.label, shown.ifEmpty { "–" }, info, indent, chevron()) { editText(n, cur, comp) })
                } else {
                    addRow(card, infoRow(n.label, shown, indent))
                }
                addAttached(card, n, comp, indent)
            }
            "button" -> {
                addRow(card, row(n.label, null, info, indent, null, Ui.ACCENT) {
                    AlertDialog.Builder(act)
                        .setTitle(n.label)
                        .setMessage(n.warning ?: "Do you want to do this now?")
                        .setPositiveButton(n.label) { _, _ ->
                            send(Aries.nodeXml("button", n.id, Math.random().toString()), n.label)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                })
            }
            "compound" -> addCompound(card, n, indent)
            else -> {
                addRow(card, row(n.label, "Open in classic view", info, indent, chevron()) { openClassic() })
            }
        }
    }

    private fun addAttached(card: LinearLayout, n: CNode, comp: CNode?, indent: Int) {
        val v = current(n, comp)
        for (a in n.attached) if (a.condition == v) for (child in a.nodes) addNode(card, child, comp, indent + 1)
    }

    /** A group of settings that is saved together (for example network or resampler settings). */
    private fun addCompound(card: LinearLayout, n: CNode, indent: Int) {
        val open = n.id in expanded
        val arrow = chevron(if (open) 90f else 0f)
        addRow(card, row(n.label, n.valueLabel.ifEmpty { null }, labels.comment(n.labelKey), indent, arrow) {
            if (open) { expanded.remove(n.id); pending.remove(n.id) } else expanded.add(n.id)
            render()
        })
        if (!open) return
        for (s in n.sub) addNode(card, s, n, indent + 1)
        val edits = pending[n.id]
        if (!edits.isNullOrEmpty()) {
            val bar = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                setPadding(dp(16), dp(4), dp(12), dp(10))
            }
            bar.addView(MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Cancel"
                setOnClickListener { pending.remove(n.id); render() }
            })
            bar.addView(MaterialButton(act).apply {
                text = "Save"
                setOnClickListener { saveCompound(n) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            card.addView(bar)
        }
    }

    private fun saveCompound(n: CNode) {
        val sb = StringBuilder()
        fun collect(nodes: List<CNode>) {
            for (c in nodes) {
                if (c.type != "button" && c.type != "compound") sb.append(Aries.nodeXml(c.type, c.id, current(c, n)))
                val v = current(c, n)
                for (a in c.attached) if (a.condition == v) collect(a.nodes)
            }
        }
        collect(n.sub)
        val doSave = {
            val xml = sb.toString()
            // keep what the user chose on screen until the Aries confirms
            pending[n.id]?.let { overrides.putAll(it) }
            pending.remove(n.id)
            render()
            send(xml, n.label)
        }
        val risky = n.id.contains("network") || n.sub.any { it.id == "dhcp" }
        if (risky) {
            AlertDialog.Builder(act)
                .setTitle(n.label)
                .setMessage("Changing network settings can make the Aries drop off the network. Continue?")
                .setPositiveButton("Save") { _, _ -> doSave() }
                .setNegativeButton("Cancel", null)
                .show()
        } else doSave()
    }

    // ---------- Changing values ----------

    private fun change(n: CNode, value: String, comp: CNode?) {
        if (value == current(n, comp)) return
        if (comp != null) {
            pending.getOrPut(comp.id) { HashMap() }[n.id] = value
            render()
            return
        }
        overrides[n.id] = value
        // Redraw on the next frame (so a switch finishes its animation first)
        view.post { render() }
        send(Aries.nodeXml(n.type, n.id, value), n.label)
    }

    private fun send(xml: String, what: String) {
        act.lifecycleScope.launch {
            try {
                Aries.set(address, svc, xml)
                snack("$what – saved")
                delay(1200)
                reload(quiet = true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                snack("Couldn't save $what (${e.message ?: "no answer"})")
                overrides.clear()
                reload(quiet = true)
            }
        }
    }

    private fun pickOption(n: CNode, cur: String, comp: CNode?) {
        val sheet = BottomSheetDialog(act)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(24)) }
        box.addView(TextView(act).apply {
            text = n.label; setTextColor(Ui.TEXT); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(24), dp(12), dp(24), dp(12))
        })
        for (o in n.options) {
            val selected = o.id == cur
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(14), dp(24), dp(14))
                background = ripple()
                setOnClickListener { sheet.dismiss(); change(n, o.id, comp) }
            }
            r.addView(TextView(act).apply {
                text = o.label; textSize = 16f
                setTextColor(if (selected) Ui.ACCENT else Ui.TEXT)
                if (selected) typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) r.addView(TextView(act).apply { text = "✓"; setTextColor(Ui.ACCENT); textSize = 18f })
            box.addView(r)
        }
        val sc = ScrollView(act).apply { addView(box) }
        sheet.setContentView(sc)
        sheet.show()
    }

    private fun editText(n: CNode, cur: String, comp: CNode?) {
        val r = n.rule
        val isNumber = r?.textRule == "number"
        // Whole numbers in a small range: slider
        if (isNumber && r?.min != null && r.max != null && (r.precision ?: 0) == 0 && r.max - r.min in 1.0..1000.0) {
            val min = r.min.toFloat(); val max = r.max.toFloat()
            val start = (cur.toFloatOrNull() ?: min).coerceIn(min, max).roundToInt().toFloat()
            val valueText = TextView(act).apply {
                text = start.roundToInt().toString(); setTextColor(Ui.ACCENT); textSize = 34f; gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            val slider = Slider(act).apply {
                valueFrom = min; valueTo = max; stepSize = 1f; value = start
                addOnChangeListener { _, v, _ -> valueText.text = v.roundToInt().toString() }
            }
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
                addView(valueText); addView(slider)
                addView(TextView(act).apply { text = "${min.roundToInt()} – ${max.roundToInt()}"; setTextColor(Ui.SUB); gravity = Gravity.CENTER })
            }
            AlertDialog.Builder(act)
                .setTitle(n.label)
                .setView(box)
                .setPositiveButton("Set") { _, _ -> change(n, slider.value.roundToInt().toString(), comp) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val input = EditText(act).apply {
            setText(if (n.password) "" else cur)
            setSingleLine()
            inputType = when {
                n.password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isNumber -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                else -> InputType.TYPE_CLASS_TEXT
            }
            hint = if (r?.textRule == "ip-address") "e.g. 192.168.1.20" else ""
        }
        val box = FrameLayout(act).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        val d = AlertDialog.Builder(act)
            .setTitle(n.label)
            .setView(box)
            .setPositiveButton("Set", null)
            .setNegativeButton("Cancel", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = input.text.toString().trim()
                val err = validate(v, r)
                if (err != null) input.error = err
                else { d.dismiss(); change(n, v, comp) }
            }
        }
        d.show()
    }

    private fun validate(v: String, r: Rule?): String? {
        if (r == null) return null
        r.minLen?.let { if (v.length < it) return "At least $it characters" }
        r.maxLen?.let { if (v.length > it) return "At most $it characters" }
        when (r.textRule) {
            "number" -> {
                val x = v.toDoubleOrNull() ?: return "Must be a number"
                r.min?.let { if (x < it) return "Lowest value is ${fmt(it)}" }
                r.max?.let { if (x > it) return "Highest value is ${fmt(it)}" }
                r.precision?.let { p -> val dec = v.substringAfter('.', ""); if (dec.length > p) return if (p == 0) "Whole numbers only" else "At most $p decimals" }
            }
            "ip-address" -> {
                val ok = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$").matches(v)
                if (!ok) return "Not a valid IP address"
            }
            "ascii" -> if (v.any { it.code > 127 }) return "Only plain letters and numbers"
            "digit" -> if (!v.all { it.isDigit() }) return "Digits only"
        }
        return null
    }

    private fun fmt(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    // ---------- Building blocks ----------

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun card() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Ui.CARD); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Ui.STROKE)
        }
        clipToOutline = true
    }

    private fun addRow(card: LinearLayout, v: View) {
        if (card.childCount > 0) {
            card.addView(View(act).apply { setBackgroundColor(Ui.DIVIDER) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { leftMargin = dp(16) })
        }
        card.addView(v)
    }

    private fun ripple() = TypedValue().let { tv ->
        act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        androidx.core.content.ContextCompat.getDrawable(act, tv.resourceId)
    }

    private fun row(label: String, value: String?, info: String?, indent: Int, trailing: View?, labelColor: Int = Ui.TEXT, onClick: (() -> Unit)?): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16) + dp(18) * indent, dp(13), dp(12), dp(13))
            minimumHeight = dp(56)
            if (onClick != null) { background = ripple(); setOnClickListener { onClick() } }
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(act).apply {
            text = label; setTextColor(labelColor); textSize = 16f
            if (labelColor != Ui.TEXT) typeface = Typeface.DEFAULT_BOLD
        })
        if (info != null) top.addView(infoIcon(label, info))
        col.addView(top)
        if (!value.isNullOrEmpty()) col.addView(TextView(act).apply {
            text = value; setTextColor(Ui.SUB); textSize = 14f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { r.addView(it) }
        return r
    }

    private fun infoRow(label: String, value: String, indent: Int): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16) + dp(18) * indent, dp(14), dp(16), dp(14))
        }
        r.addView(TextView(act).apply { text = label; setTextColor(Ui.SUB); textSize = 15f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(act).apply {
            text = value.ifEmpty { "–" }; setTextColor(Ui.TEXT); textSize = 15f; gravity = Gravity.END
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        return r
    }

    private fun chevron(rotation: Float = 0f) = TextView(act).apply {
        text = "›"; setTextColor(Ui.SUB); textSize = 24f; this.rotation = rotation
        setPadding(dp(8), 0, dp(4), dp(2))
    }

    private fun infoIcon(title: String, html: String) = TextView(act).apply {
        text = "ⓘ"; setTextColor(Ui.SUB); textSize = 15f
        setPadding(dp(8), dp(2), dp(8), dp(2))
        setOnClickListener {
            AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun snack(msg: String) {
        runCatching { Snackbar.make(view, msg, Snackbar.LENGTH_SHORT).show() }
    }
}
