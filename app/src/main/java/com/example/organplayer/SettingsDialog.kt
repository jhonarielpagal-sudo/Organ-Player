package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Settings: type the number (or use - / +). */
object SettingsDialog {
    fun show(act: Activity, s: Settings, onTremChanged: () -> Unit = {}) {
        val pad = UiKit.dp(act, 16)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val info = TextView(act).apply { textSize = 12f; text = AudioEngine.info() }
        box.addView(info)
        val fields = ArrayList<NumberField>()

        fun field(label: String, min: Int, max: Int, unit: String, value: Int, step: Int = 1, onChange: (Int) -> Unit) {
            val f = NumberField(act, label, min, max, unit, value, step) { v ->
                onChange(v); s.applyToEngine(); info.text = AudioEngine.info()
            }
            fields += f
            box.addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = UiKit.dp(act, 10)
            })
        }

        field("Master volume", 0, 100, "%", s.masterVolume, 5) { s.masterVolume = it }
        field("Transpose", -12, 12, "semitones", s.transpose) { s.transpose = it }
        field("Tuning (A4)", 415, 466, "Hz", s.tuningA4) { s.tuningA4 = it }
        field("Audio buffer", 1, 8, "bursts", s.bufferBursts) { s.bufferBursts = it }
        field("Attack", 1, 100, "ms", s.attackMs) { s.attackMs = it }
        field("Release", 10, 3000, "ms", s.releaseMs, 10) { s.releaseMs = it }
        field("Velocity sensitivity", 0, 100, "%", s.velocitySens, 5) { s.velocitySens = it }
        field("Tremulant speed", 25, 200, "%", s.tremSpeed, 5) { s.tremSpeed = it; onTremChanged() }
        field("Tremulant depth", 0, 300, "%", s.tremDepth, 5) { s.tremDepth = it; onTremChanged() }
        field("Max simultaneous voices", 16, 512, "", s.maxVoices, 16) { s.maxVoices = it }
        field("Sample memory cap", 100, 2000, "MB", s.memCapMb, 50) { s.memCapMb = it }

        val dlg = AlertDialog.Builder(act)
            .setTitle("Settings")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("OK") { _, _ -> fields.forEach { it.commit() } }
            .setNeutralButton("Reset") { _, _ -> s.reset(); s.applyToEngine(); onTremChanged(); show(act, s, onTremChanged) }
            .create()
        dlg.setOnDismissListener { fields.forEach { it.commit() } }
        dlg.show()
    }
}
