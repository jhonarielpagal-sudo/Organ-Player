package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.graphics.Typeface
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
 * with its loaded stops and couplers; tremulants in their own row; presets 1-16, P-T, GC at the bottom.
 * Yellow-orange = ON / selected. Tap = toggle, long-press a stop = stop settings.
 * MAP (header) = tap anything to assign a MIDI control to it.
 */
class ConsolePanel(private val act: Activity, private val c: OrganController, private val onOpenStops: () -> Unit) {
    private val dialog = Dialog(act, android.R.style.Theme_Material_NoActionBar)
    private val rows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val presetStrip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private lateinit var title: TextView
    private lateinit var mapChip: TextView
    private var mapMode = false
    private val listener: () -> Unit = { if (dialog.isShowing) rebuild() }

    private val bg = 0xFF0E0F12.toInt()
    private val chipOff = 0xFF23262D.toInt()
    private val textOn = 0xFF1A1200.toInt()
    private val textOff = 0xFFC9CED6.toInt()
    private val strokeStop = 0xFF3A3F4A.toInt()
    private val strokeCoupler = 0xFF3FA9F5.toInt()
    private val strokeTrem = 0xFFC77DFF.toInt()
    private val strokeGc = 0xFFE5484D.toInt()

    private fun dp(v: Int) = UiKit.dp(act, v)

    private class Item(
        val text: String, val lit: Boolean, val stroke: Int, val dim: Boolean,
        val action: String, val title: String, val onTap: () -> Unit, val onLong: (() -> Unit)? = null
    )

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
        mapChip = headerChip("MAP") { mapMode = !mapMode; rebuild() }
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
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
            c.removeListener(listener)
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        rebuild()
        dialog.show()
        dialog.window?.apply {
            setLayout(MATCH_PARENT, MATCH_PARENT)
            if (c.settings.keepScreenOn) addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
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
        background = UiKit.shape(act, if (it.lit) UiKit.AMBER else chipOff, if (it.lit) 0xFFFFD54F.toInt() else it.stroke, 6)
        setTextColor(if (it.lit) textOn else textOff)
        alpha = if (it.dim) 0.5f else 1f
        setOnClickListener { _ -> if (mapMode) MidiLearn.show(act, c, it.action, it.title) else it.onTap() }
        it.onLong?.let { long -> setOnLongClickListener { _ -> long(); true } }
    }

    private fun chipLp() = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }

    private fun manualRow(label: String, items: List<Item>, perLine: Int): Pair<LinearLayout, Int> {
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
            addView(lbl, LinearLayout.LayoutParams(dp(68), MATCH_PARENT))
            addView(col, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        return row to lines
    }

    private fun rebuild() {
        rows.removeAllViews()
        presetStrip.removeAllViews()
        val o = c.organ
        title.text = (o?.name ?: tr("No organ loaded", "Walang organ na naka-load")) + if (mapMode) "   \u2014   " + tr("MAP MODE: tap an item, then press a MIDI control", "MAP MODE: pindutin ang item, saka MIDI control") else ""
        UiKit.setLit(mapChip, mapMode, dark = true)
        if (o == null) return

        val dm = act.resources.displayMetrics
        val wDp = max(dm.widthPixels, dm.heightPixels) / dm.density - 68f - 24f
        val perLine = (wDp / 64f).toInt().coerceIn(6, 16)

        val ordered = o.manuals.filter { !it.isPedal }.sortedByDescending { it.index } + o.manuals.filter { it.isPedal }
        var any = false
        for (m in ordered) {
            val items = ArrayList<Item>()
            for (s in c.visibleStops(m)) {
                if (!s.loaded) continue
                items += Item(s.name, s.enabled, strokeStop, !s.ready, "stop:${s.key}",
                    tr("Stop: ", "Stop: ") + "${s.name} (${m.name})", { c.setSound(s, !s.enabled) }, { StopSettingsDialog.show(act, c, s) })
            }
            for (cp in m.couplers) {
                items += Item(cp.name, cp.engaged, strokeCoupler, false, "cp:${cp.id}",
                    tr("Coupler: ", "Coupler: ") + cp.name, { c.setCoupler(cp, !cp.engaged) })
            }
            if (items.isEmpty()) continue
            any = true
            val (row, lines) = manualRow(m.name, items, perLine)
            rows.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, lines.toFloat()).apply { bottomMargin = dp(2) })
        }
        if (o.tremulants.isNotEmpty()) {
            any = true
            val items = o.tremulants.map { t ->
                Item(t.name, t.on, strokeTrem, false, "trem:${t.id}", tr("Tremulant: ", "Tremulant: ") + t.name, { c.setTremulant(t, !t.on) })
            }
            val (row, lines) = manualRow("TREM", items, perLine)
            rows.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, lines.toFloat()))
        }
        if (!any) {
            rows.addView(TextView(act).apply {
                setTextColor(textOff); gravity = Gravity.CENTER; textSize = 14f
                text = tr("No stops loaded yet. Tap \"+ Stops\" to load some.", "Wala pang naka-load na stop. Pindutin ang \"+ Stops\" para mag-load.")
            }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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
