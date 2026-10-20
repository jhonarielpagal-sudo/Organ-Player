package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

object SettingsDialog {
    fun show(act: Activity, s: Settings) {
        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val info = TextView(act).apply { textSize = 12f; text = AudioEngine.info() }
        box.addView(info)

        fun slider(label: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
            val tv = TextView(act).apply { text = "$label: ${fmt(value)}"; setPadding(0, pad, 0, 0) }
            val sb = SeekBar(act).apply { this.max = max - min; progress = value - min }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    tv.text = "$label: ${fmt(v)}"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) { info.text = AudioEngine.info() }
            })
            box.addView(tv)
            box.addView(sb)
        }

        slider("Master volume", 0, 100, s.masterVolume, { "$it%" }) { s.masterVolume = it; s.applyToEngine() }
        slider("Audio buffer", 1, 8, s.bufferBursts, { "$it burst" }) { s.bufferBursts = it; s.applyToEngine() }
        slider("Attack", 1, 100, s.attackMs, { "$it ms" }) { s.attackMs = it; s.applyToEngine() }
        slider("Release", 10, 3000, s.releaseMs, { "$it ms" }) { s.releaseMs = it; s.applyToEngine() }
        slider("Transpose", -12, 12, s.transpose, { if (it > 0) "+$it" else "$it" }) { s.transpose = it; s.applyToEngine() }
        slider("Tuning (A4)", 415, 466, s.tuningA4, { "$it Hz" }) { s.tuningA4 = it; s.applyToEngine() }
        slider("Velocity sensitivity", 0, 100, s.velocitySens, { "$it%" }) { s.velocitySens = it; s.applyToEngine() }
        slider("Max na sabay na tunog", 16, 512, s.maxVoices, { "$it" }) { s.maxVoices = it; s.applyToEngine() }
        slider("Memory cap (samples)", 100, 1500, s.memCapMb, { "$it MB" }) { s.memCapMb = it; s.applyToEngine() }

        AlertDialog.Builder(act)
            .setTitle("Settings")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("OK", null)
            .setNeutralButton("Reset") { _, _ -> s.reset(); s.applyToEngine() }
            .show()
    }
}
