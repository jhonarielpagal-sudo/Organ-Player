package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), MidiInputManager.Listener {

    private lateinit var statusView: TextView
    private lateinit var organInfo: TextView
    private lateinit var stopsBox: LinearLayout
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private var midi: MidiInputManager? = null
    private var folder: OrganFolder? = null
    private var organ: Organ? = null
    private val lines = ArrayDeque<String>()
    private val prefs by lazy { getSharedPreferences("organ", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            midi = MidiInputManager(this, this)
        } else onStatus("Walang MIDI support ang device na ito.")

        // Buksan ulit ang huling organ folder (kung may persisted permission pa)
        prefs.getString(KEY_TREE, null)?.let {
            try { loadFolder(Uri.parse(it)) } catch (e: Exception) { Log.w(TAG, "restore failed", e) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!AudioEngine.start()) onStatus("Hindi nagsimula ang audio engine (Logcat: OrganEngine)")
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
            prefs.edit().putString(KEY_TREE, uri.toString()).apply()
            loadFolder(uri)
        }
    }

    private fun loadFolder(uri: Uri) {
        onStatus("Hinahanap ang .organ file...")
        Thread {
            try {
                val f = OrganFolder(this, uri)
                val files = f.findOrganFiles()
                runOnUiThread {
                    folder = f
                    when (files.size) {
                        0 -> onStatus("Walang .organ file sa folder na ito (na-extract na ba ang rar?)")
                        1 -> loadOrgan(f, files[0])
                        else -> AlertDialog.Builder(this)
                            .setTitle("Pumili ng organ")
                            .setItems(files.map { it.name }.toTypedArray()) { _, w -> loadOrgan(f, files[w]) }
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadFolder", e)
                onStatus("Error sa folder: ${e.message}")
            }
        }.start()
    }

    private fun loadOrgan(f: OrganFolder, ref: OrganFolder.OrganRef) {
        onStatus("Binabasa ang ${ref.name}...")
        Thread {
            try {
                val text = OrganFolder.decode(f.readBytes(ref.docId))
                val o = OdfParser.parse(text, ref.name.removeSuffix(".organ"))
                runOnUiThread { showOrgan(o) }
            } catch (e: Exception) {
                Log.e(TAG, "loadOrgan", e)
                onStatus("Error sa ODF: ${e.message}")
            }
        }.start()
    }

    private fun showOrgan(o: Organ) {
        organ = o
        stopsBox.removeAllViews()
        val stopCount = o.manuals.sumOf { it.stops.size }
        val pipeCount = o.manuals.sumOf { m -> m.stops.sumOf { it.pipeCount } }
        organInfo.text = "${o.name}\n${o.manuals.size} manual, $stopCount stops, $pipeCount pipes (naka-off lahat; walang sample na naka-load)"
        onStatus("Nabasa: ${o.name}")

        for (m in o.manuals) {
            stopsBox.addView(TextView(this).apply {
                text = (if (m.isPedal) "PEDAL: " else "") + m.name + "  (${m.stops.size} stops)"
                textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(4))
            })
            for (s in m.stops) {
                stopsBox.addView(Switch(this).apply {
                    text = "${s.name}   [${s.pipeCount} pipes]"
                    isChecked = s.enabled
                    setOnCheckedChangeListener { _, on -> onStopToggled(m, s, on) }
                })
            }
        }
    }

    /** Dito sa stage 2 ilo-load/i-u-unload ang samples ng stop (lazy loading). */
    private fun onStopToggled(m: Manual, s: Stop, on: Boolean) {
        s.enabled = on
        log("STOP ${if (on) "ON " else "OFF"} ${m.name}: ${s.name}")
    }

    // ---------------- MIDI callbacks (MIDI thread) ----------------

    override fun onNoteOn(channel: Int, note: Int, velocity: Int, lagMs: Double) {
        AudioEngine.noteOn(note, velocity)
        log("NOTE ON  ch=${channel + 1} note=$note vel=$velocity lag=${fmt(lagMs)}")
    }

    override fun onNoteOff(channel: Int, note: Int, velocity: Int, lagMs: Double) {
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
            while (lines.size > 60) lines.removeFirst()
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
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Buksan ang organ folder"
                setOnClickListener { pickFolder() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "Test C4"
                setOnClickListener { AudioEngine.noteOn(60, 100); postDelayed({ AudioEngine.noteOff(60) }, 500) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        organInfo = TextView(this).apply { textSize = 13f; text = "Wala pang organ na naka-load." }
        stopsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stopsScroll = ScrollView(this).apply { addView(stopsBox) }

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f }
        logScroll = ScrollView(this).apply { addView(logView) }

        root.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(organInfo, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(stopsScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(130)))
        setContentView(root)
    }

    companion object {
        private const val TAG = "OrganMIDI"
        private const val REQ_FOLDER = 1001
        private const val KEY_TREE = "tree_uri"
    }
}
