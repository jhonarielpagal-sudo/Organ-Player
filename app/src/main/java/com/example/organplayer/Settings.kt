package com.example.organplayer

import android.content.Context
import kotlin.math.ln

/** General settings (saved in SharedPreferences). */
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
    @Volatile var tremSpeed: Int = p.getInt("tremspeed", 100)         // % of the speed written in the ODF
        set(v) { field = v; p.edit().putInt("tremspeed", v).apply() }
    @Volatile var tremDepth: Int = p.getInt("tremdepth", 100)         // % of the depth written in the ODF
        set(v) { field = v; p.edit().putInt("tremdepth", v).apply() }
    @Volatile var memCapMb: Int = p.getInt("memcap", 700)
        set(v) { field = v; p.edit().putInt("memcap", v).apply() }

    // Reverb
    @Volatile var reverbOn: Boolean = p.getBoolean("revon", false)
        set(v) { field = v; p.edit().putBoolean("revon", v).apply() }
    @Volatile var reverbLevel: Int = p.getInt("revlevel", 25)          // % wet
        set(v) { field = v; p.edit().putInt("revlevel", v).apply() }
    @Volatile var reverbRoom: Int = p.getInt("revroom", 60)            // % room size / decay
        set(v) { field = v; p.edit().putInt("revroom", v).apply() }
    @Volatile var reverbDamp: Int = p.getInt("revdamp", 50)            // % damping (higher = darker tail)
        set(v) { field = v; p.edit().putInt("revdamp", v).apply() }
    @Volatile var reverbPre: Int = p.getInt("revpre", 20)              // ms pre-delay
        set(v) { field = v; p.edit().putInt("revpre", v).apply() }

    // Melody / Bass coupler: how soon after a new key is pressed the old melody key may hand over to it
    @Volatile var handoverMs: Int = p.getInt("handover", 100)
        set(v) { field = v; p.edit().putInt("handover", v).apply() }

    // Impulse response (the IR file itself, its on/off and level are saved per organ)
    @Volatile var irMaxMs: Int = p.getInt("irmax", 3000)               // longest IR used (longer = more CPU)
        set(v) { field = v; p.edit().putInt("irmax", v).apply() }

    // Console colours
    @Volatile var autoReedColor: Boolean = p.getBoolean("reedcolor", true)
        set(v) { field = v; p.edit().putBoolean("reedcolor", v).apply() }
    @Volatile var reedWords: String = p.getString("reedwords", DEFAULT_REED_WORDS) ?: DEFAULT_REED_WORDS
        set(v) { field = v; p.edit().putString("reedwords", v).apply() }

    // Loading speed
    @Volatile var sampleCache: Boolean = p.getBoolean("scache", false)
        set(v) { field = v; p.edit().putBoolean("scache", v).apply() }
    @Volatile var cacheCapMb: Int = p.getInt("scachecap", 2000)
        set(v) { field = v; p.edit().putInt("scachecap", v).apply() }

    // App behaviour
    @Volatile var theme: String = p.getString("theme", "system") ?: "system"     // "system" | "light" | "dark"
        set(v) { field = v; p.edit().putString("theme", v).apply() }
    @Volatile var keepScreenOn: Boolean = p.getBoolean("keepon", true)
        set(v) { field = v; p.edit().putBoolean("keepon", v).apply() }
    @Volatile var backgroundAudio: Boolean = p.getBoolean("bgaudio", true)
        set(v) { field = v; p.edit().putBoolean("bgaudio", v).apply() }
    @Volatile var language: String = p.getString("lang", "en") ?: "en"  // "en" | "fil"
        set(v) { field = v; p.edit().putString("lang", v).apply() }

    fun reset() {
        masterVolume = 50; bufferBursts = 2; attackMs = 4; releaseMs = 120; transpose = 0
        tuningA4 = 440; velocitySens = 0; maxVoices = 256; memCapMb = 700
        tremSpeed = 100; tremDepth = 100
        reverbOn = false; reverbLevel = 25; reverbRoom = 60; reverbDamp = 50; reverbPre = 20
        handoverMs = 100; irMaxMs = 3000; autoReedColor = true; reedWords = DEFAULT_REED_WORDS
    }

    companion object {
        /** Stops whose name contains one of these words are shown in red in the console (editable in Settings). */
        const val DEFAULT_REED_WORDS = "trompet, trumpet, tromba, posaun, trombone, bombard, tuba, hautbois, oboe, hautboy, " +
            "clairon, clarion, clarin, cromorne, krummhorn, schalmei, shawm, regal, rankett, fagott, fagot, bassoon, " +
            "basson, dulzian, englischhorn, english horn, cor anglais, cornopean, vox humana, voix humaine, clarinet, chamade"
    }

    fun applyToEngine() {
        val cents = (1200.0 * ln(tuningA4 / 440.0) / ln(2.0)).toFloat()
        AudioEngine.setParams(
            masterVolume / 100f * 0.6f, attackMs.toFloat(), releaseMs.toFloat(),
            velocitySens / 100f, cents, maxVoices, memCapMb
        )
        AudioEngine.setReverb(reverbOn, reverbLevel / 100f, reverbRoom / 100f, reverbDamp / 100f, reverbPre.toFloat())
        AudioEngine.setBufferBursts(bufferBursts)
        AudioEngine.setTranspose(transpose)       // para sa wavetable test voice
    }
}
