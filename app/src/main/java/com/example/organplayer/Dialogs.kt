package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

fun noteName(n: Int): String {
    val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return names[((n % 12) + 12) % 12] + (n / 12 - 1)
}

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

private fun hint(act: Activity, text: String) = TextView(act).apply { textSize = 12f; this.text = text }

/** Global MIDI mappings (stops/couplers/tremulants are mapped in the Stops panel or the Console). */
object MidiMapDialog {
    fun show(act: Activity, c: OrganController) {
        liveDialog(act, c, "MIDI mapping", tr("Close", "Isara"),
            tr("Clear all", "Burahin lahat") to { c.midiMap.clearAll(); c.notifyChanged() }) { box ->
            box.addView(hint(act, tr(
                "Tap a button, then press a key / piston / knob on your MIDI device. Stops, couplers and tremulants are mapped in the Stops panel or in the Console (turn on MAP). Swell shoes are mapped in the Swell screen.",
                "Pindutin ang button, saka pindutin ang key / piston / knob sa MIDI device. Ang stops, couplers at tremulants ay minamapa sa Stops panel o sa Console (i-on ang MAP). Ang swell ay minamapa sa Swell screen.")))
            fun add(action: String, label: String) =
                box.addView(labelRow(act, label, UiKit.mapButton(act, c, action, label)))
            add("gc", "General Cancel")
            add("panic", tr("Panic (All Notes Off)", "Panic (All Notes Off)"))
            add("tp+", tr("Transpose +1", "Transpose +1"))
            add("tp-", tr("Transpose -1", "Transpose -1"))
            add("tp0", tr("Transpose 0", "Transpose 0"))
            add("vol", tr("Master volume (CC knob/fader)", "Master volume (CC knob/fader)"))
            add("preset+", tr("Next preset", "Susunod na preset"))
            add("preset-", tr("Previous preset", "Nakaraang preset"))
            box.addView(UiKit.header(act, tr("Dynamics", "Dynamics")))
            for (id in OrganController.DYNAMICS) add("preset:$id", tr("Recall ", "I-recall ang ") + OrganController.presetName(id))
            box.addView(UiKit.header(act, tr("Numbered presets", "Numbered presets")))
            for (id in OrganController.NUMBERED) add("preset:$id", tr("Recall preset $id", "I-recall ang preset $id"))
        }
    }
}

object PresetsDialog {
    fun show(act: Activity, c: OrganController) {
        if (c.organ == null) { c.log("Load an organ first."); return }
        liveDialog(act, c, tr("Presets (combinations)", "Presets (combinations)"), tr("Close", "Isara"), null) { box ->
            box.addView(hint(act, tr(
                "Set up your stops, couplers and tremulants, then tap Save. Recall brings that registration back.",
                "I-set ang stops, couplers at tremulants, saka pindutin ang Save. Ibabalik ng Recall ang registration na iyon.")))
            fun addRow(id: String) {
                val has = c.hasPreset(id)
                val name = OrganController.presetName(id)
                val label = name + (if (c.activePreset == id) "  \u25CF" else "") + (if (has) "" else tr("  (empty)", "  (walang laman)"))
                box.addView(labelRow(act, label,
                    UiKit.button(act, tr("Save", "Save")) { c.savePreset(id) },
                    UiKit.button(act, tr("Recall", "Recall")) { c.recallPreset(id) }.apply { isEnabled = has },
                    UiKit.mapButton(act, c, "preset:$id", tr("Recall ", "I-recall ang ") + name)))
            }
            box.addView(UiKit.header(act, tr("Dynamics", "Dynamics")))
            OrganController.DYNAMICS.forEach { addRow(it) }
            box.addView(UiKit.header(act, tr("Numbered presets (1-16)", "Numbered presets (1-16)")))
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
        box.addView(hint(act, tr(
            "Choose which manual each MIDI channel plays. \"Active manual\" = the one selected on the main screen.",
            "Piliin kung aling manual ang tutugtugin ng bawat MIDI channel. \"Active manual\" = ang napili sa main screen.")))
        val map = c.performer.channelMap.copyOf()
        val names = listOf(tr("Active manual", "Active manual")) + o.manuals.map { (if (it.isPedal) "Pedal: " else "") + it.name }
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
        AlertDialog.Builder(act).setTitle(tr("MIDI channel -> manual", "MIDI channel -> manual"))
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("OK") { _, _ -> c.setChannelMap(map) }
            .setNegativeButton(tr("Cancel", "Kanselahin"), null)
            .show()
    }
}

/** Swell / expression shoes. Add as many as your console has; each controls the manuals you tick. */
object SwellDialog {
    fun show(act: Activity, c: OrganController) {
        val o = c.organ
        if (o == null) { c.log("Load an organ first."); return }
        val pad = UiKit.dp(act, 12)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val bars = HashMap<Int, SeekBar>()

        fun build() {
            box.removeAllViews(); bars.clear()
            box.addView(hint(act, tr(
                "Add one swell for each expression shoe / knob on your console, choose the manuals it controls, and map its MIDI control (CC). Minimum level = volume when the shoe is fully closed.",
                "Magdagdag ng isang swell para sa bawat expression shoe / knob ng console mo, piliin ang mga manual na kokontrolin niya, at i-map ang MIDI control (CC) niya. Minimum level = lakas ng tunog kapag sarado ang shoe.")))
            for (s in c.swells) {
                box.addView(UiKit.header(act, s.name))
                box.addView(EditText(act).apply {
                    setText(s.name); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
                    hint = tr("Name", "Pangalan")
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, d: Int) {}
                        override fun onTextChanged(t: CharSequence?, a: Int, b: Int, d: Int) {}
                        override fun afterTextChanged(e: Editable?) { s.name = e?.toString() ?: ""; c.swellEdited() }
                    })
                })
                box.addView(TextView(act).apply { textSize = 12f; text = tr("Controls these manuals:", "Kinokontrol ang mga manual na ito:") })
                for (m in o.manuals) {
                    box.addView(CheckBox(act).apply {
                        text = (if (m.isPedal) "Pedal: " else "") + m.name
                        isChecked = m.index in s.manuals
                        setOnCheckedChangeListener { _, on ->
                            s.manuals = if (on) s.manuals + m.index else s.manuals - m.index
                            c.swellEdited()
                        }
                    })
                }
                box.addView(NumberField(act, tr("Minimum level (closed)", "Minimum level (sarado)"), 0, 100, "%", s.minPct, 5) { v ->
                    s.minPct = v; c.swellEdited()
                })
                val bar = SeekBar(act).apply {
                    max = 100; progress = s.pos
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) c.setSwellPos(s, p) }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                bars[s.id] = bar
                box.addView(labelRow(act, tr("Position (test)", "Position (test)")))
                box.addView(bar)
                box.addView(labelRow(act, tr("MIDI control (CC)", "MIDI control (CC)"),
                    UiKit.mapButton(act, c, "swell:${s.id}", "Swell: ${s.name}"),
                    UiKit.button(act, tr("Delete", "Burahin")) { c.removeSwell(s) }))
            }
            box.addView(UiKit.button(act, tr("+ Add swell", "+ Magdagdag ng swell")) { c.addSwell() })
        }

        build()
        val dlg = AlertDialog.Builder(act).setTitle("Swell / Expression")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton(tr("Close", "Isara"), null)
            .create()
        val rebuild: () -> Unit = { if (dlg.isShowing) build() }
        val follow: () -> Unit = { for (s in c.swells) bars[s.id]?.let { if (it.progress != s.pos) it.progress = s.pos } }
        c.addListener(rebuild)
        c.addSwellListener(follow)
        dlg.setOnDismissListener { c.removeListener(rebuild); c.removeSwellListener(follow) }
        dlg.show()
    }
}

/** Per-stop adjustments for a loaded stop: volume, own release time, tuning, and single-key tuning. */
object StopSettingsDialog {
    fun show(act: Activity, c: OrganController, s: Stop) {
        val pad = UiKit.dp(act, 14)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val fields = ArrayList<NumberField>()
        fun addField(f: NumberField) {
            fields += f
            box.addView(f, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = UiKit.dp(act, 8) })
        }

        addField(NumberField(act, tr("Volume", "Lakas (volume)"), 10, 300, "%", s.gainPct, 5) { s.gainPct = it; c.saveStopConfigs() })
        addField(NumberField(act, "Release", 0, 5000, "ms", s.releaseMs, 10) { s.releaseMs = it; c.saveStopConfigs() })
        box.addView(hint(act, tr("Release 0 = use the global release from Settings.", "Release 0 = gamitin ang global release sa Settings.")))
        addField(NumberField(act, tr("Tuning (whole stop)", "Tuning (buong stop)"), -100, 100, "cents", s.tuneCents) { s.tuneCents = it; c.saveStopConfigs() })

        box.addView(UiKit.header(act, tr("Tune a single key", "I-tune ang isang key")))
        box.addView(hint(act, tr(
            "Pick the key (type the MIDI note, or play it on your keyboard and tap \"Use last played key\"), then type the cents. Only that pipe changes.",
            "Piliin ang key (i-type ang MIDI note, o tugtugin sa keyboard at pindutin ang \"Use last played key\"), saka i-type ang cents. Ang pipe lang na iyon ang magbabago.")))
        var sel = c.performer.lastPipeNote.takeIf { it in 0..127 } ?: 60
        val noteLabel = TextView(act).apply { textSize = 13f }
        val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        lateinit var centsField: NumberField
        lateinit var noteField: NumberField

        fun rebuildList() {
            listBox.removeAllViews()
            for ((n, cents) in s.pipeCents.entries.sortedBy { it.key }) {
                listBox.addView(labelRow(act, "${noteName(n)} ($n):  ${if (cents > 0) "+" else ""}$cents cents",
                    UiKit.button(act, "X") { s.pipeCents.remove(n); c.saveStopConfigs(); rebuildList() }))
            }
        }
        fun select(n: Int) {
            sel = n.coerceIn(0, 127)
            noteLabel.text = tr("Selected key: ", "Napiling key: ") + "${noteName(sel)} ($sel)"
            if (::centsField.isInitialized) centsField.setValue(s.pipeCents[sel] ?: 0)
        }

        noteField = NumberField(act, tr("Key (MIDI note)", "Key (MIDI note)"), 0, 127, "", sel) { select(it) }
        centsField = NumberField(act, tr("Key tuning", "Tuning ng key"), -100, 100, "cents", s.pipeCents[sel] ?: 0) { v ->
            if (v == 0) s.pipeCents.remove(sel) else s.pipeCents[sel] = v
            c.saveStopConfigs(); rebuildList()
        }
        fields += noteField; fields += centsField
        box.addView(noteField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = UiKit.dp(act, 8) })
        box.addView(noteLabel)
        box.addView(UiKit.button(act, tr("Use last played key", "Use last played key")) {
            val n = c.performer.lastPipeNote
            if (n in 0..127) { noteField.setValue(n); select(n) }
        })
        box.addView(centsField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = UiKit.dp(act, 8) })
        select(sel)
        box.addView(listBox)
        rebuildList()

        val dlg = AlertDialog.Builder(act).setTitle(s.name)
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton(tr("Close", "Isara")) { _, _ -> fields.forEach { it.commit() } }
            .setNeutralButton("Reset") { _, _ ->
                s.gainPct = 100; s.releaseMs = 0; s.tuneCents = 0; s.pipeCents.clear()
                c.saveStopConfigs(); show(act, c, s)
            }
            .create()
        dlg.setOnDismissListener { fields.forEach { it.commit() }; c.saveStopConfigs() }
        dlg.show()
    }
}
