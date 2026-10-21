package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** Dialog whose rows refresh when something changes (e.g. a MIDI learn finished). */
private fun liveDialog(act: Activity, c: OrganController, title: String, closeText: String,
                       neutral: Pair<String, () -> Unit>?, fill: (LinearLayout) -> Unit): AlertDialog {
    val pad = UiKit.dp(act, 12)
    val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
    val build = { box.removeAllViews(); fill(box) }
    build()
    val b = AlertDialog.Builder(act).setTitle(title)
        .setView(ScrollView(act).apply { addView(box) })
        .setPositiveButton(closeText, null)
    if (neutral != null) b.setNeutralButton(neutral.first) { _, _ -> neutral.second() }
    val dlg = b.create()
    val l: () -> Unit = { if (dlg.isShowing) build() }
    c.addListener(l)
    dlg.setOnDismissListener { c.removeListener(l) }
    dlg.show()
    return dlg
}

private fun labelRow(act: Activity, text: String, vararg views: View): LinearLayout =
    LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(act).apply { this.text = text; textSize = 14f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        views.forEach { addView(it) }
    }

/** Global MIDI mappings (stops/couplers/tremulants are mapped in the Stops panel). */
object MidiMapDialog {
    fun show(act: Activity, c: OrganController) {
        liveDialog(act, c, "MIDI mapping", "Close", "Clear all" to { c.midiMap.clearAll(); c.notifyChanged() }) { box ->
            box.addView(TextView(act).apply {
                textSize = 12f
                text = "Tap a button, then press a key / piston / knob on your MIDI device. " +
                    "Stops, couplers and tremulants are mapped in the Stops panel (each has its own MIDI button)."
            })
            fun add(action: String, label: String) =
                box.addView(labelRow(act, label, UiKit.mapButton(act, c, action, label)))
            add("gc", "General Cancel")
            add("panic", "Panic (All Notes Off)")
            add("tp+", "Transpose +1")
            add("tp-", "Transpose -1")
            add("tp0", "Transpose 0")
            add("vol", "Master volume (CC knob/fader)")
            box.addView(UiKit.header(act, "Dynamics"))
            for (id in OrganController.DYNAMICS) add("preset:$id", "Recall " + OrganController.presetName(id))
            box.addView(UiKit.header(act, "Numbered presets"))
            for (id in OrganController.NUMBERED) add("preset:$id", "Recall preset $id")
        }
    }
}

object PresetsDialog {
    fun show(act: Activity, c: OrganController) {
        if (c.organ == null) { c.log("Load an organ first."); return }
        liveDialog(act, c, "Presets (combinations)", "Close", null) { box ->
            box.addView(TextView(act).apply {
                textSize = 12f
                text = "Set up your stops, couplers and tremulants, then tap Save. Recall brings that registration back."
            })
            fun addRow(id: String) {
                val has = c.hasPreset(id)
                val name = OrganController.presetName(id)
                box.addView(labelRow(act, name + if (has) "" else "  (empty)",
                    UiKit.button(act, "Save") { c.savePreset(id) },
                    UiKit.button(act, "Recall") { c.recallPreset(id) }.apply { isEnabled = has },
                    UiKit.mapButton(act, c, "preset:$id", "Recall $name")))
            }
            box.addView(UiKit.header(act, "Dynamics"))
            OrganController.DYNAMICS.forEach { addRow(it) }
            box.addView(UiKit.header(act, "Numbered presets"))
            OrganController.NUMBERED.forEach { addRow(it) }
        }
    }
}

object ChannelMapDialog {
    fun show(act: Activity, c: OrganController) {
        val o = c.organ
        if (o == null) { c.log("Load an organ first."); return }
        val pad = UiKit.dp(act, 12)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        box.addView(TextView(act).apply {
            textSize = 12f
            text = "Choose which manual each MIDI channel plays. \"Active manual\" = the one selected on the main screen."
        })
        val map = c.performer.channelMap.copyOf()
        val names = listOf("Active manual") + o.manuals.map { (if (it.isPedal) "Pedal: " else "") + it.name }
        for (ch in 0 until 16) {
            val sp = Spinner(act).apply {
                adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, names)
                val cur = o.manuals.indexOfFirst { it.index == map[ch] }
                setSelection(if (cur >= 0) cur + 1 else 0)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        map[ch] = if (pos == 0) -1 else o.manuals[pos - 1].index
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }
            box.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(act).apply { text = "Channel ${ch + 1}" }, LinearLayout.LayoutParams(UiKit.dp(act, 90), WRAP_CONTENT))
                addView(sp, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
        }
        AlertDialog.Builder(act).setTitle("MIDI channel -> manual")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("OK") { _, _ -> c.setChannelMap(map) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
