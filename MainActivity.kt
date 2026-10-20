package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), MidiInputManager.Listener {

    private lateinit var statusView: TextView
    private lateinit var organInfo: TextView
    private lateinit var memView: TextView
    private lateinit var stopsBox: LinearLayout
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var settings: Settings
    private var midi: MidiInputManager? = null
    private var folder: OrganFolder? = null
    private var organFiles: List<OrganFolder.OrganRef> = emptyList()
    @Volatile private var samples: SampleManager? = null
    @Volatile private var activeManual: Manual? = null
    private val sounding = arrayOfNulls<IntArray>(128)      // note -> pipe keys na tumutunog
    private val lines = ArrayDeque<String>()
    private val prefs by lazy { getSharedPreferences("organ", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        buildUi()

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

    private fun label(s: Stop) = "${s.name}   [${s.pipeCount} pipes]"

    private fun updateMem() {
        memView.text = "Samples: ${AudioEngine.loadedCount()} naka-load (${AudioEngine.loadedBytes() / 1_048_576} MB)"
    }

    private fun showOrgan(o: Organ, f: OrganFolder, ref: OrganFolder.OrganRef) {
        samples?.unloadAll()                       // alisin ang samples ng naunang organ
        sounding.fill(null)
        val sm = SampleManager(this, f, ref.baseDirId, o, settings)
        samples = sm
        stopsBox.removeAllViews()

        val stopCount = o.manuals.sumOf { it.stops.size }
        val couplerCount = o.manuals.sumOf { it.couplers.size }
        organInfo.text = "${o.name}  (${ref.name})\n${o.manuals.size} manual, $stopCount stops, $couplerCount couplers (naka-off lahat; samples ng naka-on lang ang ilo-load)"
        updateMem()
        onStatus("Nabasa: ${o.name}")

        // Aling manual ang tutugtugin ng MIDI keyboard
        val defaultIdx = o.manuals.indexOfFirst { !it.isPedal }.takeIf { it >= 0 } ?: 0
        activeManual = o.manuals.getOrNull(defaultIdx)
        stopsBox.addView(TextView(this).apply { text = "Tinutugtog ng MIDI keyboard:"; textSize = 13f })
        stopsBox.addView(Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                o.manuals.map { (if (it.isPedal) "Pedal: " else "") + it.name }
            )
            setSelection(defaultIdx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    activeManual = o.manuals[pos]
                    log("Manual: ${o.manuals[pos].name}")
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

        for (m in o.manuals) {
            val visible = m.stops.filter { !it.name.contains("noise", ignoreCase = true) }
            stopsBox.addView(TextView(this).apply {
                text = (if (m.isPedal) "PEDAL: " else "") + m.name + "  (${visible.size} stops)"
                textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(4))
            })
            for (s in visible) {
                stopsBox.addView(Switch(this).apply {
                    text = label(s)
                    isChecked = s.enabled
                    setOnCheckedChangeListener { btn, on -> onStopToggled(sm, btn as Switch, m, s, on) }
                })
            }
            if (m.couplers.isNotEmpty()) {
                stopsBox.addView(TextView(this).apply {
                    text = "Couplers ng ${m.name}"
                    textSize = 13f; setTypeface(typeface, Typeface.ITALIC)
                    setPadding(0, dp(8), 0, 0)
                })
                for (c in m.couplers) {
                    val dest = o.manuals.firstOrNull { it.index == c.destManual }?.name ?: "#${c.destManual}"
                    val desc = when {
                        c.unisonOff -> "unison off"
                        c.keyShift == 0 -> "-> $dest"
                        else -> "-> $dest (${if (c.keyShift > 0) "+" else ""}${c.keyShift} semitones)"
                    }
                    stopsBox.addView(Switch(this).apply {
                        text = "${c.name}   [$desc]"
                        isChecked = c.engaged
                        setOnCheckedChangeListener { _, on ->
                            c.engaged = on
                            log("COUPLER ${if (on) "ON " else "OFF"} ${m.name}: ${c.name}")
                        }
                    })
                }
            }
        }
    }

    /** Lazy loading: samples ng stop ay ilo-load lang kapag in-on, at i-u-unload kapag in-off. */
    private fun onStopToggled(sm: SampleManager, btn: Switch, m: Manual, s: Stop, on: Boolean) {
        s.enabled = on
        if (on) {
            btn.text = label(s) + "  ...naglo-load"
            val t0 = System.currentTimeMillis()
            sm.acquire(s) { r ->
                runOnUiThread {
                    btn.text = label(s) + when {
                        r.failed > 0 -> "  (${r.failed} hindi na-load)"
                        r.unresolved > 0 -> "  (${r.unresolved} walang sample)"
                        else -> ""
                    }
                    updateMem()
                    log("STOP ON  ${m.name}: ${s.name} -> ${r.loaded} loaded, ${r.shared} shared, " +
                        "${r.failed} failed, ${r.unresolved} walang path/REF, ${System.currentTimeMillis() - t0} ms")
                    if (r.reasons.isNotEmpty()) log("  ! ${r.reasons}")
                    r.firstError?.let { log("  ! $it") }
                }
            }
        } else {
            sm.release(s)
            btn.text = label(s)
            updateMem()
            log("STOP OFF ${m.name}: ${s.name}")
        }
    }

    // ---------------- MIDI callbacks (MIDI thread) ----------------

    override fun onNoteOn(channel: Int, note: Int, velocity: Int, lagMs: Double) {
        val m = activeManual
        val sm = samples
        if (m != null && sm != null) {
            sounding[note]?.let { old -> old.forEach { AudioEngine.pipeOff(it) } }
            val keys = sm.keysForPlayedNote(m, note)     // unison + couplers + transpose
            sounding[note] = keys
            keys.forEach { AudioEngine.pipeOn(it, velocity) }
        } else {
            AudioEngine.noteOn(note, velocity)      // walang organ na naka-load: wavetable test sound
        }
        log("NOTE ON  ch=${channel + 1} note=$note vel=$velocity lag=${fmt(lagMs)}")
    }

    override fun onNoteOff(channel: Int, note: Int, velocity: Int, lagMs: Double) {
        sounding[note]?.let { keys -> keys.forEach { AudioEngine.pipeOff(it) } }
        sounding[note] = null
        AudioEngine.noteOff(note)
        log("NOTE OFF ch=${channel + 1} note=$note lag=${fmt(lagMs)}")
    }

    override fun onStatus(message: String) {
        runOnUiThread { statusView.text = message }
        Log.i(TAG, message)
    }

    // ---------------- UI helpers ----------------

    private fun fmt(lagMs: Double) = if (lagMs < 0) "n/a" else "%.2f ms".format(lagMs)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun log(line: String) {
        Log.d(TAG, line)
        runOnUiThread {
            lines.addLast(line)
            while (lines.size > 80) lines.removeFirst()
            logView.text = lines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(8))
        }
        statusView = TextView(this).apply { textSize = 14f; text = "Naghihintay ng MIDI device..." }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Organ folder"
                setOnClickListener { pickFolder() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "Piliin ang .organ"
                setOnClickListener { chooseOrgan() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Settings"
                setOnClickListener { SettingsDialog.show(this@MainActivity, settings) }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "Test C4"
                setOnClickListener { AudioEngine.noteOn(60, 100); postDelayed({ AudioEngine.noteOff(60) }, 500) }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        organInfo = TextView(this).apply { textSize = 13f; text = "Wala pang organ na naka-load." }
        memView = TextView(this).apply { textSize = 12f }
        stopsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stopsScroll = ScrollView(this).apply { addView(stopsBox) }

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }

        root.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(organInfo, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(memView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(stopsScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(150)))
        setContentView(root)
    }

    companion object {
        private const val TAG = "OrganMIDI"
        private const val REQ_FOLDER = 1001
        private const val KEY_TREE = "tree_uri"
        private const val KEY_ORGAN = "organ_id"
    }
}
