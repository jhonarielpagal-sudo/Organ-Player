package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil
import kotlin.math.max

/**
 * Full-screen, dark, landscape console. One row per manual (highest manual on top, pedal at the bottom)
 * with its loaded stops, couplers and the tremulants that act on that manual; presets 1-16, P-T, GC at the bottom.
 * Yellow-orange = ON / selected. Tap = toggle, long-press a stop = stop settings (volume, tuning, text colour).
 * Reed stops are red; couplers have a blue border and tremulants a purple one (also when ON).
 * Header: MIDI keyboard indicator, Pitch and Transpose boxes (tap = edit, hold = reset), MAP, + Stops, Swell.
 * MAP (header) = tap anything to assign a MIDI control to it.
 */
class ConsolePanel(private val act: Activity, private val c: OrganController, private val onOpenStops: () -> Unit) {
    private val dialog = Dialog(act, android.R.style.Theme_Material_NoActionBar)
    private val rows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val presetStrip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private lateinit var title: TextView
    private lateinit var mapChip: TextView
    private lateinit var pitchChip: TextView
    private lateinit var transChip: TextView
    private lateinit var midiBox: LinearLayout
    private lateinit var midiDot: TextView
    private lateinit var midiText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val pulse = object : Runnable {
        override fun run() {
            if (!dialog.isShowing) return
            updateMidi()
            handler.postDelayed(this, 100)
        }
    }
    private var mapMode = false
    private val listener: () -> Unit = { if (dialog.isShowing) rebuild() }

    private val bg = 0xFF0E0F12.toInt()
    private val chipOff = 0xFF23262D.toInt()
    private val textOn = 0xFF1A1200.toInt()
    private val textOff = 0xFFC9CED6.toInt()
    private val strokeStop = 0xFF3A3F4A.toInt()
    private val strokeCoupler = 0xFF3FA9F5.toInt()
    private val strokeTrem = 0xFFC77DFF.toInt()
    private val textCoupler = 0xFF8CCBFF.toInt()
    private val textTrem = 0xFFE0B0FF.toInt()
    private val strokeGc = 0xFFE5484D.toInt()

    private fun dp(v: Int) = UiKit.dp(act, v)

    private class Item(
        val text: String, val lit: Boolean, val stroke: Int, val dim: Boolean,
        val action: String, val title: String, val onTap: () -> Unit, val onLong: (() -> Unit)? = null,
        val textColor: Int = 0,            // 0 = normal
        val strokeDp: Int = 1,
        val keepStroke: Boolean = false    // keep the type colour of the border when ON (couplers, tremulants)
    )

    private class RowSpec(val label: String, val items: List<Item>)

    /** Darker shade of a colour: readable on the amber background of an ON stop. */
    private fun darken(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = hsv[2] * 0.42f
        return Color.HSVToColor(hsv)
    }

    fun show() {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        title = TextView(act).apply {
            setTextColor(0xFFE6E9EF.toInt()); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL; maxLines = 1
        }
        title.ellipsize = TextUtils.TruncateAt.END
        mapChip = headerChip("MAP") { mapMode = !mapMode; rebuild() }
        pitchChip = headerChip("") {
            editNumber(tr("Pitch (A4)", "Pitch (A4)"), 415, 466, "Hz", c.settings.tuningA4, 1, 440) { c.setPitch(it) }
        }.also { it.setOnLongClickListener { _ -> c.setPitch(440); true } }
        transChip = headerChip("") {
            editNumber("Transpose", -12, 12, tr("semitones", "semitones"), c.settings.transpose, 1, 0) { c.setTranspose(it) }
        }.also { it.setOnLongClickListener { _ -> c.setTranspose(0); true } }
        midiDot = TextView(act).apply { text = "\u25CF"; textSize = 14f; gravity = Gravity.CENTER }
        midiText = TextView(act).apply {
            textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(130)
            gravity = Gravity.CENTER_VERTICAL; setTextColor(textOff)
        }
        midiBox = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(10), 0)
            background = UiKit.shape(act, chipOff, 0x33000000, 8)
            addView(midiDot, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            addView(midiText, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { leftMargin = dp(4) })
            setOnClickListener {
                val d = c.midiDevices
                Toast.makeText(act, if (d.isEmpty()) tr("No MIDI keyboard connected.", "Walang nakakonektang MIDI keyboard.")
                    else tr("MIDI: ", "MIDI: ") + d.joinToString(", "), Toast.LENGTH_SHORT).show()
            }
        }
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(midiBox, headerLp())
            addView(pitchChip, headerLp())
            addView(transChip, headerLp())
            addView(mapChip, headerLp())
            addView(headerChip("+ Stops") { onOpenStops() }, headerLp())
            addView(headerChip("Swell") { SwellDialog.show(act, c) }, headerLp())
            addView(headerChip("\u2715") { dialog.dismiss() }, headerLp())
        }
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, dp(36)))
        root.addView(rows, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(presetStrip, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)).apply { topMargin = dp(4) })

        dialog.setContentView(root)
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        c.addListener(listener)
        dialog.setOnDismissListener {
            handler.removeCallbacks(pulse)
            c.removeListener(listener)
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        rebuild()
        dialog.show()
        handler.post(pulse)
        dialog.window?.apply {
            setLayout(MATCH_PARENT, MATCH_PARENT)
            if (c.settings.keepScreenOn) addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    /** Small dialog to type a number (or use - / +). */
    private fun editNumber(title: String, min: Int, max: Int, unit: String, value: Int, step: Int, default: Int, onSet: (Int) -> Unit) {
        val nf = NumberField(act, title, min, max, unit, value, step) { onSet(it) }
        val box = LinearLayout(act).apply { setPadding(dp(16), dp(12), dp(16), dp(4)); addView(nf, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        val dlg = AlertDialog.Builder(act).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> nf.commit() }
            .setNeutralButton(tr("Reset ($default)", "I-reset ($default)")) { _, _ -> onSet(default) }
            .create()
        dlg.setOnDismissListener { nf.commit() }
        dlg.show()
    }

    /** MIDI keyboard indicator: grey = none, green = connected, bright flash = receiving. */
    private fun updateMidi() {
        val devs = c.midiDevices
        val active = devs.isNotEmpty() && System.nanoTime() - c.midiActivityNs < 200_000_000L
        midiDot.setTextColor(when {
            devs.isEmpty() -> 0xFF6B7280.toInt()
            active -> 0xFFD7FF6B.toInt()
            else -> 0xFF2ECC71.toInt()
        })
        val label = if (devs.isEmpty()) tr("No MIDI", "Walang MIDI") else devs.first() + if (devs.size > 1) " +${devs.size - 1}" else ""
        if (midiText.text.toString() != label) midiText.text = label
    }

    private fun headerLp() = LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }

    private fun headerChip(text: String, onClick: () -> Unit) = TextView(act).apply {
        this.text = text; textSize = 12f; gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        UiKit.setLit(this, false, dark = true)
        setOnClickListener { onClick() }
    }

    private fun makeChip(it: Item): TextView = TextView(act).apply {
        text = it.text
        gravity = Gravity.CENTER
        maxLines = 2
        setPadding(dp(2), 0, dp(2), 0)
        setTypeface(typeface, if (it.lit) Typeface.BOLD else Typeface.NORMAL)
        setAutoSizeTextTypeUniformWithConfiguration(7, 14, 1, TypedValue.COMPLEX_UNIT_SP)
        val border = if (it.lit && !it.keepStroke) 0xFFFFD54F.toInt() else it.stroke
        background = UiKit.shape(act, if (it.lit) UiKit.AMBER else chipOff, border, 6, it.strokeDp)
        setTextColor(when {
            it.lit -> if (it.textColor != 0) darken(it.textColor) else textOn
            it.textColor != 0 -> it.textColor
            else -> textOff
        })
        alpha = if (it.dim) 0.5f else 1f
        setOnClickListener { _ -> if (mapMode) MidiLearn.show(act, c, it.action, it.title) else it.onTap() }
        it.onLong?.let { long -> setOnLongClickListener { _ -> long(); true } }
    }

    private fun chipLp() = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }

    private fun manualRow(label: String, items: List<Item>, perLine: Int, labelPx: Int): Pair<LinearLayout, Int> {
        val n = items.size
        val lines = ceil(n / perLine.toFloat()).toInt().coerceAtLeast(1)
        val perRow = ceil(n / lines.toFloat()).toInt().coerceAtLeast(1)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        for (li in 0 until lines) {
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until perRow) {
                val idx = li * perRow + k
                if (idx < n) line.addView(makeChip(items[idx]), chipLp()) else line.addView(View(act), chipLp())
            }
            col.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        val lbl = TextView(act).apply {
            text = label; gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFF8FA3BF.toInt()); setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(lbl, LinearLayout.LayoutParams(labelPx, MATCH_PARENT))
            addView(col, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        return row to lines
    }

    private fun tremItem(t: Tremulant) = Item(
        t.name, t.on, strokeTrem, false, "trem:${t.id}", tr("Tremulant: ", "Tremulant: ") + t.name,
        { c.setTremulant(t, !t.on) }, textColor = textTrem, strokeDp = 2, keepStroke = true
    )

    private fun rebuild() {
        rows.removeAllViews()
        presetStrip.removeAllViews()
        val o = c.organ
        title.text = (o?.name ?: tr("No organ loaded", "Walang organ na naka-load")) + if (mapMode) "   \u2014   " + tr("MAP MODE: tap an item, then press a MIDI control", "MAP MODE: pindutin ang item, saka MIDI control") else ""
        UiKit.setLit(mapChip, mapMode, dark = true)
        val a4 = c.settings.tuningA4
        val tp = c.settings.transpose
        pitchChip.text = "Pitch  $a4 Hz"
        transChip.text = "Transpose  " + (if (tp > 0) "+$tp" else "$tp")
        pitchChip.setTextColor(if (a4 != 440) UiKit.AMBER else textOff)       // amber = not the default
        transChip.setTextColor(if (tp != 0) UiKit.AMBER else textOff)
        updateMidi()
        if (o == null) return

        val ordered = o.manuals.filter { !it.isPedal }.sortedByDescending { it.index } + o.manuals.filter { it.isPedal }
        val reedWords = c.settings.reedWords.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val specs = ArrayList<RowSpec>()
        val shownTrem = HashSet<Int>()
        for (m in ordered) {
            val items = ArrayList<Item>()
            for (s in c.visibleStops(m)) {
                if (!s.loaded) continue
                items += Item(s.name, s.enabled, strokeStop, !s.ready, "stop:${s.key}",
                    tr("Stop: ", "Stop: ") + "${s.name} (${m.name})", { c.setSound(s, !s.enabled) }, { StopSettingsDialog.show(act, c, s) },
                    textColor = c.stopTextColor(s, reedWords))
            }
            for (cp in m.couplers) {
                items += Item(cp.name, cp.engaged, strokeCoupler, false, "cp:${cp.id}",
                    tr("Coupler: ", "Coupler: ") + cp.name, { c.setCoupler(cp, !cp.engaged) },
                    textColor = textCoupler, strokeDp = 2, keepStroke = true)
            }
            if (items.isEmpty()) continue
            for (t in c.tremulantsFor(m)) { items += tremItem(t); shownTrem += t.id }     // tremulant sits in its manual's row
            specs += RowSpec(m.name, items)
        }
        // Tremulants that no visible manual row carries stay reachable in their own row
        val leftover = o.tremulants.filter { it.id !in shownTrem }
        if (leftover.isNotEmpty()) specs += RowSpec("TREM", leftover.map { tremItem(it) })

        if (specs.isEmpty()) {
            rows.addView(TextView(act).apply {
                setTextColor(textOff); gravity = Gravity.CENTER; textSize = 14f
                text = tr("No stops loaded yet. Tap \"+ Stops\" to load some.", "Wala pang naka-load na stop. Pindutin ang \"+ Stops\" para mag-load.")
            }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            buildPresets()
            return
        }

        // Label column: the same width for every row, fitted to the longest manual / pedal name
        val dm = act.resources.displayMetrics
        val paint = Paint().apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, dm)
        }
        val widest = specs.maxOf { paint.measureText(it.label) }
        val labelPx = (widest + dp(10)).coerceIn(dp(38).toFloat(), dp(150).toFloat()).toInt()
        val wDp = max(dm.widthPixels, dm.heightPixels) / dm.density - labelPx / dm.density - 24f
        val perLine = (wDp / 64f).toInt().coerceIn(6, 16)

        for (sp in specs) {
            val (row, lines) = manualRow(sp.label, sp.items, perLine, labelPx)
            rows.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, lines.toFloat()).apply { bottomMargin = dp(2) })
        }
        buildPresets()
    }

    private fun pill(text: String, lit: Boolean, stroke: Int, textColor: Int, action: String, title: String,
                     onTap: () -> Unit, onLong: (() -> Unit)? = null): TextView = TextView(act).apply {
        this.text = text
        gravity = Gravity.CENTER
        maxLines = 1
        setTypeface(typeface, Typeface.BOLD)
        setAutoSizeTextTypeUniformWithConfiguration(7, 13, 1, TypedValue.COMPLEX_UNIT_SP)
        background = UiKit.shape(act, if (lit) UiKit.AMBER else chipOff, if (lit) 0xFFFFD54F.toInt() else stroke, 20)
        setTextColor(if (lit) textOn else textColor)
        setOnClickListener { if (mapMode) MidiLearn.show(act, c, action, title) else onTap() }
        onLong?.let { l -> setOnLongClickListener { l(); true } }
    }

    private fun slotLp() = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
    private fun gap() = View(act).also { presetStrip.addView(it, LinearLayout.LayoutParams(dp(8), MATCH_PARENT)) }

    private fun confirmSave(id: String) {
        val name = OrganController.presetName(id)
        AlertDialog.Builder(act).setTitle(name)
            .setMessage(tr("Save the current stops, couplers and tremulants here?", "I-save dito ang kasalukuyang stops, couplers at tremulants?"))
            .setPositiveButton(tr("Save", "Save")) { _, _ -> c.savePreset(id) }
            .setNegativeButton(tr("Cancel", "Kanselahin"), null)
            .show()
    }

    private fun presetPill(id: String): TextView {
        val has = c.hasPreset(id)
        return pill(id, c.activePreset == id, strokeStop, if (has) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt(),
            "preset:$id", tr("Recall ", "I-recall ang ") + OrganController.presetName(id),
            {
                if (c.hasPreset(id)) c.recallPreset(id)
                else Toast.makeText(act, tr("${OrganController.presetName(id)} is empty. Hold to save.", "Walang laman ang ${OrganController.presetName(id)}. Hawakan para i-save."), Toast.LENGTH_SHORT).show()
            },
            { confirmSave(id) })
    }

    private fun buildPresets() {
        presetStrip.addView(pill("GC", false, strokeGc, 0xFFFF8A8F.toInt(), "gc", "General Cancel", { c.generalCancel() }), slotLp())
        gap()
        for (id in OrganController.DYNAMICS) presetStrip.addView(presetPill(id), slotLp())
        gap()
        for (id in OrganController.NUMBERED) presetStrip.addView(presetPill(id), slotLp())
        gap()
        presetStrip.addView(pill("\u25C0", false, strokeStop, 0xFFFFFFFF.toInt(), "preset-", tr("Previous preset", "Nakaraang preset"), { c.stepPreset(-1) }), slotLp())
        presetStrip.addView(pill("\u25B6", false, strokeStop, 0xFFFFFFFF.toInt(), "preset+", tr("Next preset", "Susunod na preset"), { c.stepPreset(1) }), slotLp())
    }
}
