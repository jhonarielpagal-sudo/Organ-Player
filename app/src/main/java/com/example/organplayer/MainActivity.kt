package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), MidiInputManager.Listener {

    private lateinit var statusView: TextView
    private lateinit var organInfo: TextView
    private lateinit var memView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var manualSpinner: Spinner
    private lateinit var transposeField: NumberField
    private lateinit var pitchField: NumberField
    private lateinit var volumeField: NumberField
    private lateinit var settings: Settings
    private lateinit var c: OrganController
    private val ui = Handler(Looper.getMainLooper())
    private var midi: MidiInputManager? = null
    private var folder: OrganFolder? = null
    private var organFiles: List<OrganFolder.OrganRef> = emptyList()
    private val lines = ArrayDeque<String>()
    private val prefs by lazy { getSharedPreferences("organ", MODE_PRIVATE) }
    private val dynButtons = LinkedHashMap<String, Button>()
    private var engineRunning = false
    private var midiRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = Settings(this)
        Lang.fil = settings.language == "fil"
        UiKit.dark = when (settings.theme) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (UiKit.dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        c = OrganController(this, settings, prefs, { log(it) }, { ui.post(it) })
        buildUi()
        c.addListener { onStateChanged() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 77)
        }

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            midi = MidiInputManager(this, this)
        } else onStatus(tr("This device has no MIDI support.", "Walang MIDI support ang device na ito."))

        prefs.getString(KEY_TREE, null)?.let {
            try { loadFolder(Uri.parse(it)) } catch (e: Exception) { Log.w(TAG, "restore failed", e) }
        }
    }

    override fun onStart() {
        super.onStart()
        applyScreenFlag()
        if (!engineRunning) {
            if (AudioEngine.start()) engineRunning = true
            else onStatus(tr("Audio engine failed to start (Logcat: OrganEngine)", "Hindi nagsimula ang audio engine (Logcat: OrganEngine)"))
            settings.applyToEngine()
            c.applySwells()
            c.repushTremulants()
            c.applyIr()
        }
        if (!midiRunning && midi != null) { midi?.start(); midiRunning = true }
        if (settings.backgroundAudio) AudioService.start(this)
    }

    override fun onStop() {
        if (!settings.backgroundAudio) stopAll()      // with background audio on, everything keeps running
        super.onStop()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun stopAll() {
        if (midiRunning) { midi?.stop(); midiRunning = false }
        c.performer.panic()
        if (engineRunning) { AudioEngine.stop(); engineRunning = false }
        AudioService.stop(this)
    }

    private fun applyScreenFlag() {
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun applyUiSettings() {
        applyScreenFlag()
        if (settings.backgroundAudio) AudioService.start(this) else AudioService.stop(this)
    }

    // ---------------- Organ folder / ODF ----------------

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_FOLDER)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { Log.w(TAG, "persist failed", e) }
            prefs.edit().putString(KEY_TREE, uri.toString()).remove(KEY_ORGAN).apply()
            loadFolder(uri)
        }
        if (requestCode == REQ_IR && resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { Log.w(TAG, "persist failed", e) }
            c.setIrFile(uri)
        }
    }

    private fun pickIrFile() {
        if (c.organ == null) { onStatus(tr("Load an organ first.", "Mag-load muna ng organ.")); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_IR)
    }

    private fun organId(r: OrganFolder.OrganRef) = "${r.dirName}/${r.name}"

    private fun loadFolder(uri: Uri) {
        onStatus(tr("Looking for .organ files...", "Hinahanap ang .organ file..."))
        Thread {
            try {
                val f = OrganFolder(this, uri)
                val files = f.findOrganFiles()
                runOnUiThread {
                    folder = f
                    organFiles = files
                    when {
                        files.isEmpty() -> onStatus(tr("No .organ file in this folder (is the archive extracted?)",
                            "Walang .organ file sa folder na ito (na-extract na ba ang rar?)"))
                        files.size == 1 -> loadOrgan(f, files[0])
                        else -> {
                            val saved = prefs.getString(KEY_ORGAN, null)
                            val match = files.firstOrNull { organId(it) == saved }
                            if (match != null) loadOrgan(f, match) else chooseOrgan()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadFolder", e)
                onStatus(tr("Folder error: ", "Error sa folder: ") + "${e.message}")
            }
        }.start()
    }

    /** Choose among the .organ files in the folder (sample sets can have several). */
    private fun chooseOrgan() {
        val f = folder
        if (f == null || organFiles.isEmpty()) { onStatus(tr("Choose an organ folder first.", "Pumili muna ng organ folder.")); return }
        val labels = organFiles.map { if (it.dirName.isEmpty()) it.name else "${it.name}  (${it.dirName})" }
        AlertDialog.Builder(this)
            .setTitle(tr("Choose an .organ file (${organFiles.size})", "Pumili ng .organ (${organFiles.size})"))
            .setItems(labels.toTypedArray()) { _, w ->
                prefs.edit().putString(KEY_ORGAN, organId(organFiles[w])).apply()
                loadOrgan(f, organFiles[w])
            }
            .show()
    }

    private fun loadOrgan(f: OrganFolder, ref: OrganFolder.OrganRef) {
        onStatus(tr("Reading ${ref.name}...", "Binabasa ang ${ref.name}..."))
        Thread {
            try {
                val text = OrganFolder.decode(f.readBytes(ref.docId))
                val o = OdfParser.parse(text, ref.name.removeSuffix(".organ"))
                runOnUiThread { showOrgan(o, f, ref) }
            } catch (e: Exception) {
                Log.e(TAG, "loadOrgan", e)
                onStatus(tr("ODF error: ", "Error sa ODF: ") + "${e.message}")
            }
        }.start()
    }

    private fun showOrgan(o: Organ, f: OrganFolder, ref: OrganFolder.OrganRef) {
        c.attach(o, f, ref, organId(ref))
        val stopCount = o.manuals.sumOf { it.stops.size }
        val couplerCount = o.manuals.sumOf { it.couplers.size }
        organInfo.text = "${o.name}\n${ref.name}  -  ${o.manuals.size} manuals, $stopCount stops, " +
            "$couplerCount couplers, ${o.tremulants.size} tremulants"
        onStatus(tr("Loaded: ", "Nabasa: ") + o.name)
        setupManualSpinner(o)
        onStateChanged()
    }

    private fun setupManualSpinner(o: Organ) {
        manualSpinner.onItemSelectedListener = null
        manualSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            o.manuals.map { (if (it.isPedal) "Pedal: " else "") + it.name }
        )
        val cur = o.manuals.indexOfFirst { it === c.performer.activeManual }.coerceAtLeast(0)
        manualSpinner.setSelection(cur, false)
        manualSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val m = o.manuals[pos]
                c.setActiveManual(m)
                log("Manual: ${m.name}")
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** Called whenever something changes (UI, MIDI, loading). */
    private fun onStateChanged() {
        transposeField.setValue(settings.transpose)
        pitchField.setValue(settings.tuningA4)
        volumeField.setValue(settings.masterVolume)
        memView.text = "Samples: ${AudioEngine.loadedCount()} " + tr("loaded", "naka-load") +
            " (${AudioEngine.loadedBytes() / 1_048_576} MB)"
        for ((id, b) in dynButtons) UiKit.setLit(b, c.activePreset == id)
        c.organ?.let { o ->      // keep the Manual selector in sync (MIDI can change the active manual)
            val idx = o.manuals.indexOfFirst { it === c.performer.activeManual }
            if (idx >= 0 && manualSpinner.selectedItemPosition != idx) manualSpinner.setSelection(idx, false)
        }
    }

    // ---------------- MIDI callbacks (MIDI thread) ----------------

    override fun onNoteOn(channel: Int, note: Int, velocity: Int, lagMs: Double) {
        c.pulseMidi()
        if (c.onMidi(MidiMap.NOTE, channel, note, velocity)) return
        if (!c.performer.noteOn(channel, note, velocity)) {
            AudioEngine.noteOn(note, velocity)      // no organ loaded: wavetable test sound
        }
        log("NOTE ON  ch=${channel + 1} note=$note vel=$velocity lag=${fmt(lagMs)}")
    }

    override fun onNoteOff(channel: Int, note: Int, velocity: Int, lagMs: Double) {
        c.pulseMidi()
        if (c.onMidi(MidiMap.NOTE, channel, note, 0)) return
        c.performer.noteOff(channel, note)
        AudioEngine.noteOff(note)
        log("NOTE OFF ch=${channel + 1} note=$note lag=${fmt(lagMs)}")
    }

    override fun onControlChange(channel: Int, controller: Int, value: Int) {
        c.pulseMidi()
        c.onMidi(MidiMap.CC, channel, controller, value)
    }

    override fun onProgramChange(channel: Int, program: Int) {
        c.pulseMidi()
        c.onMidi(MidiMap.PC, channel, program, 0)
    }

    override fun onDevicesChanged() {
        c.midiDevices = midi?.deviceNames() ?: emptyList()
        c.notifyChanged()
    }

    override fun onStatus(message: String) {
        runOnUiThread { statusView.text = message }
        Log.i(TAG, message)
    }

    // ---------------- UI ----------------

    private fun fmt(lagMs: Double) = if (lagMs < 0) "n/a" else "%.2f ms".format(lagMs)
    private fun dp(v: Int) = UiKit.dp(this, v)

    private fun log(line: String) {
        Log.d(TAG, line)
        runOnUiThread {
            lines.addLast(line)
            while (lines.size > 80) lines.removeFirst()
            logView.text = lines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun buttonRow(vararg items: Pair<String, () -> Unit>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((t, a) in items) addView(UiKit.button(context, t, a), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun confirmSave(id: String) {
        val name = OrganController.presetName(id)
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(tr("Save the current stops, couplers and tremulants to this button?",
                "I-save sa button na ito ang kasalukuyang stops, couplers at tremulants?"))
            .setPositiveButton(tr("Save", "Save")) { _, _ ->
                c.savePreset(id)
                Toast.makeText(this, tr("Saved $name", "Na-save ang $name"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(tr("Cancel", "Kanselahin"), null)
            .show()
    }

    private fun openConsole() = ConsolePanel(this, c) { StopsPanel(this, c).show() }.show()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(28), dp(12), dp(8))
        }
        statusView = TextView(this).apply { textSize = 13f; text = tr("Waiting for a MIDI device...", "Naghihintay ng MIDI device...") }

        val hooks = SettingsHooks(
            tremolo = { c.repushTremulants() },
            ui = { applyUiSettings() },
            language = { ui.post { recreate() } },
            theme = { ui.post { recreate() } },
            cacheUsedMb = { c.cache.usedBytes() / 1_048_576 },
            clearCache = { c.cache.clear() },
            ir = c,
            pickIr = { pickIrFile() },
            irRebuild = { c.rebuildIr() },
            console = { c.notifyChanged() }
        )
        val row1 = buttonRow(
            tr("Organ folder", "Organ folder") to { pickFolder() },
            tr("Choose .organ", "Piliin ang .organ") to { chooseOrgan() },
            "Settings" to { SettingsDialog.show(this, settings, hooks) }
        )
        val row2 = buttonRow(
            "Console" to { openConsole() },
            "Stops" to { StopsPanel(this, c).show() },
            "Presets" to { PresetsDialog.show(this, c) },
            "Swell" to { SwellDialog.show(this, c) }
        )
        val row3 = buttonRow(
            "MIDI map" to { MidiMapDialog.show(this, c) },
            "Channels" to { ChannelMapDialog.show(this, c) },
            "PANIC" to { c.panic() }
        )

        // Quick panel: organ file, manual, transpose, master volume
        organInfo = TextView(this).apply {
            textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            text = tr("No organ loaded yet.", "Wala pang organ na naka-load.")
            setOnClickListener { chooseOrgan() }
        }
        manualSpinner = Spinner(this)
        val manualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply { text = "Manual:"; textSize = 14f }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(manualSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        transposeField = NumberField(this, "Transpose", -12, 12, "st", settings.transpose) { v -> c.setTranspose(v) }
        pitchField = NumberField(this, "Pitch (A4)", 415, 466, "Hz", settings.tuningA4) { v -> c.setPitch(v) }
        volumeField = NumberField(this, "Master volume", 0, 100, "%", settings.masterVolume, 5) { v -> c.setVolume(v) }
        memView = TextView(this).apply { textSize = 11f }
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(if (UiKit.dark) 0x22FFFFFF else 0x11000000)
            addView(organInfo)
            addView(manualRow)
            addView(transposeField)
            addView(pitchField)
            addView(volumeField)
            addView(memView)
        }

        // General set: GC, P MP MF F T (tap = recall, hold = save) + previous / next preset
        val dynHint = TextView(this).apply {
            textSize = 11f
            text = tr("General set:  tap = recall,  hold = save", "General set:  tap = recall,  hold = i-save")
        }
        val dynRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(UiKit.button(context, "GC") { c.generalCancel() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            for (id in OrganController.DYNAMICS) {
                val b = UiKit.button(context, id) {
                    if (c.hasPreset(id)) c.recallPreset(id)
                    else Toast.makeText(context, tr("${OrganController.presetName(id)} is empty. Hold the button to save.",
                        "Walang laman ang ${OrganController.presetName(id)}. Hawakan ang button para i-save."), Toast.LENGTH_SHORT).show()
                }
                b.setOnLongClickListener { confirmSave(id); true }
                dynButtons[id] = b
                addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        }
        for (b in dynButtons.values) UiKit.setLit(b, false)
        val stepRow = buttonRow(
            "\u25C0 " + tr("Prev", "Nakaraan") to { c.stepPreset(-1) },
            tr("Next", "Susunod") + " \u25B6" to { c.stepPreset(1) }
        )

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        val footer = TextView(this).apply {
            text = "Organ Player v$version   \u00B7   \u00A9 2026 Ariel Pagal"
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }

        root.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row3, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(quick, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        root.addView(dynHint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(dynRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(stepRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    companion object {
        private const val TAG = "OrganMIDI"
        private const val REQ_FOLDER = 1001
        private const val REQ_IR = 1002
        private const val KEY_TREE = "tree_uri"
        private const val KEY_ORGAN = "organ_id"
    }
}
