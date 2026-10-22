package com.example.organplayer

import android.app.Activity
import android.app.Dialog
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Button

/**
 * Panel ng stops/ranks, tremulants at couplers.
 *  - tr("Loaded", "Naka-load"): stops in memory; Switch = unmute/mute (stays loaded), X = unload
 *  - tr("All stops", "Lahat ng stops"): every stop; tick = load (and sound), untick = unload
 * Bawat item ay may MIDI button para sa mapping.
 */
class StopsPanel(private val act: Activity, private val c: OrganController) {
    private val dialog = Dialog(act, if (UiKit.dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
    private val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(act).apply { addView(content) }
    private lateinit var tabLoaded: Button
    private lateinit var tabAll: Button
    private var tab = 0
    private val listener: () -> Unit = { if (dialog.isShowing) rebuild() }

    fun show() {
        val pad = UiKit.dp(act, 10)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, UiKit.dp(act, 28), pad, pad)
        }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(act).apply { text = tr("Stops / Couplers / Tremulants", "Stops / Couplers / Tremulants"); textSize = 16f },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(UiKit.button(act, tr("Close", "Isara")) { dialog.dismiss() })
        root.addView(top)

        val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        tabLoaded = UiKit.button(act, tr("Loaded", "Naka-load")) { tab = 0; rebuild() }
        tabAll = UiKit.button(act, tr("All stops", "Lahat ng stops")) { tab = 1; rebuild() }
        tabs.addView(tabLoaded, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tabs.addView(tabAll, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(tabs)

        val tools = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(UiKit.button(act, "General Cancel") { c.generalCancel() })
        tools.addView(UiKit.mapButton(act, c, "gc", tr("General Cancel (stops, couplers, tremulants off)", "General Cancel (patay ang stops, couplers, tremulants)")))
        tools.addView(UiKit.button(act, tr("Unload all", "I-unload lahat")) { c.unloadAllStops() })
        root.addView(tools)

        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)
        c.addListener(listener)
        dialog.setOnDismissListener { c.removeListener(listener) }
        rebuild()
        dialog.show()
        dialog.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
    }

    private fun row(view: CompoundButton, action: String, title: String, vararg extra: Button): LinearLayout =
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            extra.forEach { addView(it) }
            addView(UiKit.mapButton(act, c, action, title))
        }

    private fun toggleRow(text: String, checked: Boolean, action: String, title: String,
                          vararg extra: Button, onToggle: (Boolean) -> Unit): LinearLayout {
        val sw = Switch(act).apply {
            this.text = text
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onToggle(on) }
        }
        return row(sw, action, title, *extra)
    }

    private fun rebuild() {
        val y = scroll.scrollY
        content.removeAllViews()
        tabLoaded.alpha = if (tab == 0) 1f else 0.5f
        tabAll.alpha = if (tab == 1) 1f else 0.5f
        val o = c.organ
        if (o == null) {
            content.addView(TextView(act).apply { text = tr("No organ loaded yet.", "Wala pang organ na naka-load.") })
            return
        }

        if (o.tremulants.isNotEmpty()) {
            content.addView(UiKit.header(act, tr("Tremulants", "Tremulants")))
            for (t in o.tremulants) {
                content.addView(toggleRow(t.name, t.on, "trem:${t.id}", tr("Tremulant: ", "Tremulant: ") + t.name) { on -> c.setTremulant(t, on) })
            }
        }

        for (m in o.manuals) {
            if (m.couplers.isEmpty()) continue
            content.addView(UiKit.header(act, tr("Couplers - ", "Couplers - ") + m.name))
            for (cp in m.couplers) {
                val dest = o.manuals.firstOrNull { it.index == cp.destManual }?.name ?: "#${cp.destManual}"
                val desc = when {
                    cp.kind == CouplerKind.MELODY -> tr("melody -> $dest", "melody -> $dest")
                    cp.kind == CouplerKind.BASS -> "bass -> $dest"
                    cp.unisonOff -> "unison off"
                    cp.keyShift == 0 -> "-> $dest"
                    else -> "-> $dest (${if (cp.keyShift > 0) "+" else ""}${cp.keyShift})"
                }
                content.addView(toggleRow("${cp.name}  [$desc]", cp.engaged, "cp:${cp.id}", tr("Coupler: ", "Coupler: ") + cp.name) { on ->
                    c.setCoupler(cp, on)
                })
            }
        }

        var shown = 0
        for (m in o.manuals) {
            val visible = c.visibleStops(m)
            val list = if (tab == 0) visible.filter { it.loaded } else visible
            if (list.isEmpty()) continue
            content.addView(UiKit.header(act, (if (m.isPedal) "PEDAL: " else "") + m.name + "  (${list.size} stops)"))
            for (s in list) {
                val label = "${s.name}  [${s.pipeCount}]" + if (s.status.isNotEmpty()) "  ${s.status}" else ""
                val action = "stop:${s.key}"
                val title = tr("Stop: ", "Stop: ") + "${s.name} (${m.name})"
                shown++
                if (tab == 0) {
                    val unload = UiKit.button(act, "X") { c.unloadStop(s) }
                    val cfg = UiKit.button(act, "\u2699") { StopSettingsDialog.show(act, c, s) }
                    content.addView(toggleRow(label, s.enabled, action, title, cfg, unload) { on -> c.setSound(s, on) })
                } else {
                    val cb = CheckBox(act).apply {
                        text = label
                        isChecked = s.loaded
                        setOnCheckedChangeListener { _, on -> if (on) c.loadStop(s) else c.unloadStop(s) }
                    }
                    content.addView(row(cb, action, title))
                }
            }
        }
        if (shown == 0 && tab == 0) {
            content.addView(TextView(act).apply {
                text = "\n" + tr("No stops loaded yet. Go to \"All stops\" and tick the ones you want to load.", "Wala pang naka-load na stop. Pumunta sa \"Lahat ng stops\" at i-check ang gusto mong i-load.")
            })
        }
        scroll.post { scroll.scrollTo(0, y) }
    }
}
