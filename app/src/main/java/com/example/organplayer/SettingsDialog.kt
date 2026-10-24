package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class SettingsHooks(
    val tremolo: () -> Unit = {},
    val ui: () -> Unit = {},          // keep-screen-on / background audio changed
    val language: () -> Unit = {},
    val theme: () -> Unit = {},
    val cacheUsedMb: () -> Long = { 0L },
    val clearCache: () -> Unit = {},
    val ir: OrganController? = null,        // impulse response is saved per organ
    val pickIr: () -> Unit = {},
    val irRebuild: () -> Unit = {},
    val console: () -> Unit = {}            // console colours changed
)

/** Settings: type the number (or use - / +). */
object SettingsDialog {
    fun show(act: Activity, s: Settings, hooks: SettingsHooks = SettingsHooks()) {
        val pad = UiKit.dp(act, 16)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val deviceLine = run {
            val pm = act.packageManager
            val mi = android.app.ActivityManager.MemoryInfo()
            (act.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), " +
                "${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}, ${mi.totalMem / 1_048_576} MB RAM, " +
                "${Runtime.getRuntime().availableProcessors()} cores\n" +
                "Low-latency audio: " + (if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUDIO_LOW_LATENCY)) "yes" else "no") +
                ",  Pro audio: " + (if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUDIO_PRO)) "yes" else "no") + "\n"
        }
        val info = TextView(act).apply { textSize = 12f; text = deviceLine + AudioEngine.info() }
        box.addView(info)
        val fields = ArrayList<NumberField>()
        val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = UiKit.dp(act, 10) } }

        fun field(label: String, min: Int, max: Int, unit: String, value: Int, step: Int = 1, onChange: (Int) -> Unit) {
            val f = NumberField(act, label, min, max, unit, value, step) { v ->
                onChange(v); s.applyToEngine(); info.text = deviceLine + AudioEngine.info()
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
        val themeSpinner = Spinner(act).apply {
            adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item,
                listOf(tr("Follow system", "Sundin ang system"), tr("Light", "Maliwanag"), tr("Dark", "Madilim")))
            setSelection(when (s.theme) { "light" -> 1; "dark" -> 2; else -> 0 }, false)
        }
        box.addView(TextView(act).apply { text = tr("Theme", "Theme"); textSize = 14f })
        box.addView(themeSpinner)
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

        // ---- Impulse response (convolution reverb) ----
        var dlgRef: AlertDialog? = null
        box.addView(UiKit.header(act, tr("Impulse response (this organ)", "Impulse response (para sa organ na ito)")))
        val irc = hooks.ir
        if (irc == null || irc.organ == null) {
            box.addView(TextView(act).apply {
                textSize = 12f
                text = tr("Load an organ first. The impulse response is saved per organ file.", "Mag-load muna ng organ. Naka-save bawat organ file ang impulse response.")
            })
        } else {
            val nameView = TextView(act).apply {
                textSize = 13f
                text = if (irc.ir.uri == null) tr("No IR file chosen.", "Wala pang napiling IR file.")
                       else irc.ir.name + (if (irc.irStatus.isNotEmpty()) "\n" + irc.irStatus else "")
            }
            box.addView(nameView)
            val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(UiKit.button(act, tr("Choose IR (.wav)", "Pumili ng IR (.wav)")) { dlgRef?.dismiss(); hooks.pickIr() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            btns.addView(UiKit.button(act, tr("Remove IR", "Alisin ang IR")) { irc.clearIrFile(); nameView.text = tr("No IR file chosen.", "Wala pang napiling IR file.") },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(btns, lp())
            toggle(tr("Impulse response on", "Impulse response naka-on"), irc.ir.on) { irc.setIrOn(it) }
            field(tr("IR level (wet)", "IR level (wet)"), 0, 100, "%", irc.ir.level, 5) { irc.setIrLevel(it) }
            field(tr("Longest IR used", "Pinakamahabang IR na gagamitin"), 500, 8000, "ms", s.irMaxMs, 100) { s.irMaxMs = it; hooks.irRebuild() }
            box.addView(TextView(act).apply {
                textSize = 11f
                text = tr("While the IR is on, the built-in reverb above is bypassed. A longer IR uses more CPU; if you hear crackles, shorten it. The sound you play is never delayed by the IR.",
                    "Habang naka-on ang IR, naka-bypass ang built-in reverb sa itaas. Mas mahabang IR = mas mabigat sa CPU; kung may crackle, paikliin. Hindi naaantala ng IR ang tinutugtog mo.")
            })
        }

        // ---- Console colours ----
        box.addView(UiKit.header(act, tr("Console colours", "Kulay sa Console")))
        toggle(tr("Reed stops in red", "Pula ang mga reed stop"), s.autoReedColor) { s.autoReedColor = it; hooks.console() }
        box.addView(TextView(act).apply { textSize = 12f; text = tr("Reed words (a stop whose name contains one of these is red):", "Mga salita ng reed (pula ang stop na may ganitong salita sa pangalan):") })
        box.addView(EditText(act).apply {
            setText(s.reedWords); textSize = 12f; minLines = 2
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { s.reedWords = e?.toString() ?: ""; hooks.console() }
            })
        })
        box.addView(UiKit.button(act, tr("Reset reed words", "I-reset ang reed words")) {
            s.reedWords = Settings.DEFAULT_REED_WORDS; hooks.console(); dlgRef?.dismiss(); show(act, s, hooks)
        })
        box.addView(TextView(act).apply {
            textSize = 11f
            text = tr("Long-press a stop in the Console to give it its own text colour.", "I-long-press ang stop sa Console para bigyan ng sariling kulay ng text.")
        })

        box.addView(UiKit.header(act, "Tremulant"))
        field(tr("Tremulant speed", "Bilis ng tremulant"), 25, 200, "%", s.tremSpeed, 5) { s.tremSpeed = it; hooks.tremolo() }
        field(tr("Tremulant depth", "Lalim ng tremulant"), 0, 300, "%", s.tremDepth, 5) { s.tremDepth = it; hooks.tremolo() }

        box.addView(UiKit.header(act, tr("Loading speed", "Bilis ng loading")))
        toggle(tr("Cache samples in app storage (faster loading, uses space)",
            "I-cache ang samples sa app storage (mas mabilis mag-load, gumagamit ng space)"), s.sampleCache) { s.sampleCache = it }
        field(tr("Cache size limit", "Limit ng cache"), 200, 20000, "MB", s.cacheCapMb, 100) { s.cacheCapMb = it }
        val cacheInfo = TextView(act).apply { textSize = 12f; text = tr("Cache used: ", "Gamit na cache: ") + "${hooks.cacheUsedMb()} MB" }
        box.addView(cacheInfo)
        box.addView(UiKit.button(act, tr("Clear cache", "Burahin ang cache")) {
            hooks.clearCache()
            cacheInfo.text = tr("Cache used: ", "Gamit na cache: ") + "${hooks.cacheUsedMb()} MB"
        })

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
        dlgRef = dlg
        dlg.setOnDismissListener { fields.forEach { it.commit() } }
        var themeInit = true
        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (themeInit) { themeInit = false; return }
                val code = when (pos) { 1 -> "light"; 2 -> "dark"; else -> "system" }
                if (code != s.theme) {
                    s.theme = code
                    dlg.dismiss()
                    hooks.theme()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
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
