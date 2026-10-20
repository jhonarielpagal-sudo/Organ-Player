package com.example.organplayer

import android.content.Context
import kotlin.math.ln

/** General settings (naka-save sa SharedPreferences). Walang hard-coded na gain/buffer/release. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile var masterVolume: Int = p.getInt("master", 50)           // 0..100 %
        set(v) { field = v; p.edit().putInt("master", v).apply() }
    @Volatile var bufferBursts: Int = p.getInt("bursts", 2)            // 1..8 (mas mababa = mas mababang latency)
        set(v) { field = v; p.edit().putInt("bursts", v).apply() }
    @Volatile var attackMs: Int = p.getInt("attack", 4)
        set(v) { field = v; p.edit().putInt("attack", v).apply() }
    @Volatile var releaseMs: Int = p.getInt("release", 120)
        set(v) { field = v; p.edit().putInt("release", v).apply() }
    @Volatile var transpose: Int = p.getInt("transpose", 0)            // semitones
        set(v) { field = v; p.edit().putInt("transpose", v).apply() }
    @Volatile var tuningA4: Int = p.getInt("a4", 440)                  // Hz
        set(v) { field = v; p.edit().putInt("a4", v).apply() }
    @Volatile var velocitySens: Int = p.getInt("velsens", 0)           // 0 = pantay ang lakas (gaya ng pipe organ)
        set(v) { field = v; p.edit().putInt("velsens", v).apply() }
    @Volatile var maxVoices: Int = p.getInt("voices", 256)
        set(v) { field = v; p.edit().putInt("voices", v).apply() }
    @Volatile var memCapMb: Int = p.getInt("memcap", 700)
        set(v) { field = v; p.edit().putInt("memcap", v).apply() }

    fun reset() {
        masterVolume = 50; bufferBursts = 2; attackMs = 4; releaseMs = 120; transpose = 0
        tuningA4 = 440; velocitySens = 0; maxVoices = 256; memCapMb = 700
    }

    fun applyToEngine() {
        val cents = (1200.0 * ln(tuningA4 / 440.0) / ln(2.0)).toFloat()
        AudioEngine.setParams(
            masterVolume / 100f * 0.6f, attackMs.toFloat(), releaseMs.toFloat(),
            velocitySens / 100f, cents, maxVoices, memCapMb
        )
        AudioEngine.setBufferBursts(bufferBursts)
        AudioEngine.setTranspose(transpose)       // para sa wavetable test voice
    }
}
