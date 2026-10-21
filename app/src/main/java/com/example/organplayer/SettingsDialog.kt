package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class SettingsHooks(
    val tremolo: () -> Unit = {},
    val ui: () -> Unit = {},          // keep-screen-on / background audio changed
    val language: () -> Unit = {}
)

/** Settings: type the number (or use - / +). */
object SettingsDialog {
    fun show(act: Activity, s: Settings, hooks: SettingsHooks = SettingsHooks()) {
        val pad = UiKit.dp(act, 16)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val info = TextView(act).apply { textSize = 12f; text = AudioEngine.info() }
        box.addView(info)
        val fields = ArrayList<NumberField>()
        val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = UiKit.dp(act, 10) } }

        fun field(label: String, min: Int, max: Int, unit: String, value: Int, step: Int = 1, onChange: (Int) -> Unit) {
            val f = NumberField(act, label, min, max, unit, value, step) { v ->
                onChange(v); s.applyToEngine(); info.text = AudioEngine.info()
            }
            fields += f
            box.addView(f, lp())
        }
        fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
            box.addView(Switch(act).apply {
                text = label; isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }, lp())
        }

        box.addView(UiKit.header(act, tr("App", "App")))
        val langSpinner = Spinner(act).apply {
            adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, listOf("English", "Filipino"))
            setSelection(if (s.language == "fil") 1 else 0, false)
        }
        box.addView(TextView(act).apply { text = tr("Language", "Wika (Language)"); textSize = 14f })
        box.addView(langSpinner)
        toggle(tr("Keep screen on", "Huwag patayin ang screen"), s.keepScreenOn) { s.keepScreenOn = it; hooks.ui() }
        toggle(tr("Keep playing in the background (foreground service)", "Tuloy ang tunog sa background (foreground service)"), s.backgroundAudio) {
            s.backgroundAudio = it; hooks.ui()
        }

        box.addView(UiKit.header(act, tr("Sound", "Tunog")))
        field(tr("Master volume", "Master volume"), 0, 100, "%", s.masterVolume, 5) { s.masterVolume = it }
        field(tr("Transpose", "Transpose"), -12, 12, tr("semitones", "semitones"), s.transpose) { s.transpose = it }
        field(tr("Tuning (A4)", "Tuning (A4)"), 415, 466, "Hz", s.tuningA4) { s.tuningA4 = it }
        field(tr("Attack", "Attack"), 1, 100, "ms", s.attackMs) { s.attackMs = it }
        field(tr("Release", "Release"), 10, 3000, "ms", s.releaseMs, 10) { s.releaseMs = it }
        field(tr("Velocity sensitivity", "Velocity sensitivity"), 0, 100, "%", s.velocitySens, 5) { s.velocitySens = it }

        box.addView(UiKit.header(act, "Reverb"))
        toggle(tr("Reverb on", "Reverb naka-on"), s.reverbOn) { s.reverbOn = it; s.applyToEngine() }
        field(tr("Level (wet)", "Level (wet)"), 0, 100, "%", s.reverbLevel, 5) { s.reverbLevel = it }
        field(tr("Room size / decay", "Laki ng kwarto / decay"), 0, 100, "%", s.reverbRoom, 5) { s.reverbRoom = it }
        field(tr("Damping (darker tail)", "Damping (mas madilim na tail)"), 0, 100, "%", s.reverbDamp, 5) { s.reverbDamp = it }
        field(tr("Pre-delay", "Pre-delay"), 0, 250, "ms", s.reverbPre, 5) { s.reverbPre = it }

        box.addView(UiKit.header(act, "Tremulant"))
        field(tr("Tremulant speed", "Bilis ng tremulant"), 25, 200, "%", s.tremSpeed, 5) { s.tremSpeed = it; hooks.tremolo() }
        field(tr("Tremulant depth", "Lalim ng tremulant"), 0, 300, "%", s.tremDepth, 5) { s.tremDepth = it; hooks.tremolo() }

        box.addView(UiKit.header(act, tr("Performance", "Performance")))
        field(tr("Audio buffer", "Audio buffer"), 1, 8, "bursts", s.bufferBursts) { s.bufferBursts = it }
        field(tr("Max simultaneous voices", "Max na sabay-sabay na boses"), 16, 512, "", s.maxVoices, 16) { s.maxVoices = it }
        field(tr("Sample memory cap", "Memory cap ng samples"), 100, 2000, "MB", s.memCapMb, 50) { s.memCapMb = it }

        val dlg = AlertDialog.Builder(act)
            .setTitle(tr("Settings", "Settings"))
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("OK") { _, _ -> fields.forEach { it.commit() } }
            .setNeutralButton("Reset") { _, _ -> s.reset(); s.applyToEngine(); hooks.tremolo(); show(act, s, hooks) }
            .create()
        dlg.setOnDismissListener { fields.forEach { it.commit() } }
        var langInit = true
        langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (langInit) { langInit = false; return }
                val code = if (pos == 1) "fil" else "en"
                if (code != s.language) {
                    s.language = code
                    Lang.fil = code == "fil"
                    dlg.dismiss()
                    hooks.language()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        dlg.show()
    }
}
