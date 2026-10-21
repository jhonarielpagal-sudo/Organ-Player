package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity(), MidiInputManager.Listener {

    private lateinit var statusView: TextView
    private lateinit var organInfo: TextView
    private lateinit var memView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var manualSpinner: Spinner
    private lateinit var transposeField: NumberField
    private lateinit var volumeField: NumberField
    private lateinit var settings: Settings
    private lateinit var c: OrganController
    private val ui = Handler(Looper.getMainLooper())
    private var midi: MidiInputManager? = null
    private var folder: OrganFolder? = null
    private var organFiles: List<OrganFolder.OrganRef> = emptyList()
    private val lines = ArrayDeque<String>()
    private val prefs by lazy { getSharedPreferences("organ", MODE_PRIVATE) }
    private var spinnerOrgan: Organ? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        c = OrganController(this, settings, prefs, { log(it) }, { ui.post(it) })
        buildUi()
        c.addListener { onStateChanged() }

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            midi = MidiInputManager(this, this)
        } else onStatus("Walang MIDI support ang device na ito.")

        prefs.getString(KEY_TREE, null)?.let {
            try { loadFolder(Uri.parse(it)) } catch (e: Exception) { Log.w(TAG, "restore failed", e) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!AudioEngine.start()) onStatus("Hindi nagsimula ang audio engine (Logcat: OrganEngine)")
        settings.applyToEngine()
        midi?.start()
    }

    override fun onStop() {
        midi?.stop()
        c.performer.panic()
        AudioEngine.stop()
        super.onStop()
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
    }

    private fun organId(r: OrganFolder.OrganRef) = "${r.dirName}/${r.name}"

    private fun loadFolder(uri: Uri) {
        onStatus("Hinahanap ang .organ file...")
        Thread {
            try {
                val f = OrganFolder(this, uri)
                val files = f.findOrganFiles()
                runOnUiThread {
                    folder = f
                    organFiles = files
                    when {
                        files.isEmpty() -> onStatus("Walang .organ file sa folder na ito (na-extract na ba ang rar?)")
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
                onStatus("Error sa folder: ${e.message}")
            }
        }.start()
    }

    /** Pumili sa mga .organ file sa folder (may mga sample set na maraming .organ). */
    private fun chooseOrgan() {
        val f = folder
        if (f == null || organFiles.isEmpty()) { onStatus("Pumili muna ng organ folder."); return }
        val labels = organFiles.map { if (it.dirName.isEmpty()) it.name else "${it.name}  (${it.dirName})" }
        AlertDialog.Builder(this)
            .setTitle("Pumili ng .organ (${organFiles.size})")
            .setItems(labels.toTypedArray()) { _, w ->
                prefs.edit().putString(KEY_ORGAN, organId(organFiles[w])).apply()
                loadOrgan(f, organFiles[w])
            }
            .show()
    }

    private fun loadOrgan(f: OrganFolder, ref: OrganFolder.OrganRef) {
        onStatus("Binabasa ang ${ref.name}...")
        Thread {
            try {
                val text = OrganFolder.decode(f.readBytes(ref.docId))
                val o = OdfParser.parse(text, ref.name.removeSuffix(".organ"))
                runOnUiThread { showOrgan(o, f, ref) }
            } catch (e: Exception) {
                Log.e(TAG, "loadOrgan", e)
                onStatus("Error sa ODF: ${e.message}")
            }
        }.start()
    }

    private fun showOrgan(o: Organ, f: OrganFolder, ref: OrganFolder.OrganRef) {
        c.attach(o, f, ref, organId(ref))
        val stopCount = o.manuals.sumOf { it.stops.size }
        val couplerCount = o.manuals.sumOf { it.couplers.size }
        organInfo.text = "${o.name}\n${ref.name}  -  ${o.manuals.size} manual, $stopCount stops, " +
            "$couplerCount couplers, ${o.tremulants.size} tremulants"
        onStatus("Nabasa: ${o.name}")
        setupManualSpinner(o)
        onStateChanged()
    }

    private fun setupManualSpinner(o: Organ) {
        spinnerOrgan = o
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

    /** Tinatawag kapag may nagbago (UI, MIDI, load). */
    private fun onStateChanged() {
        transposeField.setValue(settings.transpose)
        volumeField.setValue(settings.masterVolume)
        memView.text = "Samples: ${AudioEngine.loadedCount()} naka-load (${AudioEngine.loadedBytes() / 1_048_576} MB)"
    }

    // ---------------- MIDI callbacks (MIDI thread) ----------------

    override fun onNoteOn(channel: Int, note: Int, velocity: Int, lagMs: Double) {
        if (c.onMidi(MidiMap.NOTE, channel, note, velocity)) return
        if (!c.performer.noteOn(channel, note, velocity)) {
            AudioEngine.noteOn(note, velocity)      // walang organ na naka-load: wavetable test sound
        }
        log("NOTE ON  ch=${channel + 1} note=$note vel=$velocity lag=${fmt(lagMs)}")
    }

    override fun onNoteOff(channel: Int, note: Int, velocity: Int, lagMs: Double) {
        if (c.onMidi(MidiMap.NOTE, channel, note, 0)) return
        c.performer.noteOff(channel, note)
        AudioEngine.noteOff(note)
        log("NOTE OFF ch=${channel + 1} note=$note lag=${fmt(lagMs)}")
    }

    override fun onControlChange(channel: Int, controller: Int, value: Int) {
        c.onMidi(MidiMap.CC, channel, controller, value)
    }

    override fun onProgramChange(channel: Int, program: Int) {
        c.onMidi(MidiMap.PC, channel, program, 0)
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

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(28), dp(12), dp(8))
        }
        statusView = TextView(this).apply { textSize = 13f; text = "Naghihintay ng MIDI device..." }

        val row1 = buttonRow(
            "Organ folder" to { pickFolder() },
            "Piliin ang .organ" to { chooseOrgan() },
            "Settings" to { SettingsDialog.show(this, settings) }
        )
        val row2 = buttonRow(
            "Stops" to { StopsPanel(this, c).show() },
            "Presets" to { PresetsDialog.show(this, c) },
            "MIDI map" to { MidiMapDialog.show(this, c) },
            "Channels" to { ChannelMapDialog.show(this, c) }
        )

        // Maliit na quick panel: organ file, manual, transpose, master volume
        organInfo = TextView(this).apply {
            textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            text = "Wala pang organ na naka-load."
            setOnClickListener { chooseOrgan() }
        }
        manualSpinner = Spinner(this)
        val manualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply { text = "Manual:"; textSize = 14f }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(manualSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        transposeField = NumberField(this, "Transpose", -12, 12, "st", settings.transpose) { v -> c.setTranspose(v) }
        volumeField = NumberField(this, "Master volume", 0, 100, "%", settings.masterVolume, 5) { v -> c.setVolume(v) }
        memView = TextView(this).apply { textSize = 11f }
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0x11000000)
            addView(organInfo)
            addView(manualRow)
            addView(transposeField)
            addView(volumeField)
            addView(memView)
        }
        val panicRow = buttonRow("PANIC (All Notes Off)" to { c.panic() })

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }

        root.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(quick, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        root.addView(panicRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    companion object {
        private const val TAG = "OrganMIDI"
        private const val REQ_FOLDER = 1001
        private const val KEY_TREE = "tree_uri"
        private const val KEY_ORGAN = "organ_id"
    }
}
