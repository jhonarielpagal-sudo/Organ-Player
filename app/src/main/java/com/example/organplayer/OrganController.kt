package com.example.organplayer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Swell / expression shoe: controls the volume of the chosen manuals. Created by the user, saved per organ. */
class Swell(val id: Int, var name: String, var manuals: Set<Int>, var minPct: Int) {
    @Volatile var pos = 100        // 0..100 (current shoe position)
}

/** Impulse response (convolution reverb) of one organ. Saved per organ. */
class IrConfig(var uri: String? = null, var name: String = "", var on: Boolean = false, var level: Int = 40)

/** Hawak ang estado ng organ: stops (load/mute), couplers, tremulants, presets, MIDI actions, save/restore. */
class OrganController(
    private val context: Context,
    val settings: Settings,
    private val prefs: SharedPreferences,
    private val logFn: (String) -> Unit,
    private val runUi: (Runnable) -> Unit
) {
    val performer = Performer(settings)
    val cache = SampleCache(context, settings)
    val midiMap = MidiMap(prefs)
    @Volatile var organ: Organ? = null
    @Volatile var organId: String = ""
    @Volatile private var samples: SampleManager? = null
    @Volatile private var restoring = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    val swells = CopyOnWriteArrayList<Swell>()
    private val swellListeners = CopyOnWriteArrayList<() -> Unit>()
    private val swellPosted = AtomicBoolean(false)
    @Volatile var activePreset: String? = null
    private var lastStepped: String? = null

    // MIDI keyboards (connected names + time of the last MIDI message, for the console indicator)
    @Volatile var midiDevices: List<String> = emptyList()
    @Volatile var midiActivityNs = 0L
    fun pulseMidi() { midiActivityNs = System.nanoTime() }

    // Impulse response of the current organ
    @Volatile var ir = IrConfig()
    @Volatile var irStatus = ""
    private val irExec = Executors.newSingleThreadExecutor()
    @Volatile private var irGen = 0

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun log(s: String) = logFn(s)
    fun notifyChanged() = runUi(Runnable { listeners.forEach { it() } })
    private fun changed() {
        if (!restoring) {
            performer.refreshAll()                                   // held keys follow the new registration
            if (activePreset != null && !presetStillActive()) activePreset = null   // piston goes dark when you change anything
            saveState()
        }
        notifyChanged()
    }

    // ---------------- organ ----------------

    fun attach(o: Organ, f: OrganFolder, ref: OrganFolder.OrganRef, id: String) {
        performer.panic()
        performer.samples = null; performer.organ = null
        samples?.unloadAll()
        organ = o; organId = id
        val sm = SampleManager(context, f, ref.baseDirId, o, settings, cache, id)
        samples = sm
        performer.samples = sm; performer.organ = o
        performer.channelMap = loadChannelMap(id)
        val savedIdx = prefs.getInt("active_$id", -1)
        performer.activeManual = o.manuals.firstOrNull { it.index == savedIdx }
            ?: o.manuals.firstOrNull { !it.isPedal } ?: o.manuals.firstOrNull()
        midiMap.load(id)
        activePreset = null
        loadStopConfigs()
        loadSwells()
        applySwells()
        loadIrConfig()
        startIrLoad()
        AudioEngine.clearTremulants()
        o.tremulants.forEach { pushTrem(it) }
        for (m in o.manuals) for (c in m.couplers) {
            if (c.kind != CouplerKind.NORMAL) log("Coupler ${m.name}: ${c.name} = ${c.kind}")
        }
        restoreState()
        changed()
    }

    fun setActiveManual(m: Manual) {
        performer.activeManual = m
        prefs.edit().putInt("active_$organId", m.index).apply()
    }

    fun allStops(): List<Stop> = organ?.manuals?.flatMap { it.stops } ?: emptyList()
    fun visibleStops(m: Manual): List<Stop> = m.stops.filter { !it.name.contains("noise", ignoreCase = true) }
    private fun stopByKey(k: String) = allStops().firstOrNull { it.key == k }
    private fun couplerById(id: Int?) = organ?.manuals?.flatMap { it.couplers }?.firstOrNull { it.id == id }
    private fun tremById(id: Int?) = organ?.tremulants?.firstOrNull { it.id == id }

    // ---------------- stops ----------------

    private fun doLoad(s: Stop, sound: Boolean) {
        val sm = samples ?: return
        if (s.loaded) { s.enabled = sound; return }
        s.loaded = true; s.ready = false; s.enabled = sound; s.status = tr("loading...", "naglo-load...")
        val t0 = System.currentTimeMillis()
        val mName = organ?.manuals?.firstOrNull { it.index == s.manualIndex }?.name ?: "?"
        sm.acquire(s) { r ->
            s.status = when {
                r.failed > 0 -> tr("${r.failed} failed to load", "${r.failed} hindi na-load")
                r.unresolved > 0 -> tr("${r.unresolved} missing samples", "${r.unresolved} walang sample")
                else -> ""
            }
            if (s.loaded) s.ready = true
            log("STOP LOAD $mName: ${s.name} -> ${r.loaded} loaded, ${r.shared} shared, ${r.failed} failed, " +
                "${r.unresolved} no path/REF, ${System.currentTimeMillis() - t0} ms")
            if (r.timing.isNotEmpty()) log("  timing: ${r.timing}")
            if (r.reasons.isNotEmpty()) log("  ! ${r.reasons}")
            r.firstError?.let { log("  ! $it") }
            changed()
        }
    }

    private fun doUnload(s: Stop) {
        if (!s.loaded) return
        s.enabled = false; s.ready = false; s.loaded = false; s.status = ""
        samples?.release(s)
    }

    fun loadStop(s: Stop) { doLoad(s, true); changed() }
    fun unloadStop(s: Stop) { doUnload(s); changed() }
    fun setSound(s: Stop, on: Boolean) {
        if (!s.loaded) { if (on) doLoad(s, true) } else s.enabled = on
        changed()
    }
    fun toggleStop(s: Stop) = setSound(s, !(s.loaded && s.enabled))

    /** General Cancel: stops muted (still loaded), all couplers disengaged, all tremulants off. */
    fun generalCancel() {
        allStops().forEach { if (it.loaded) it.enabled = false }
        organ?.let { o ->
            o.manuals.forEach { m -> m.couplers.forEach { it.engaged = false } }
            o.tremulants.forEach { if (it.on) { it.on = false; pushTrem(it) } }
        }
        log("GENERAL CANCEL (stops, couplers, tremulants off)")
        changed()
    }

    fun unloadAllStops() { allStops().forEach { doUnload(it) }; changed() }

    fun panic() { performer.panic(); log("PANIC") }

    fun toggleReverb() {
        settings.reverbOn = !settings.reverbOn
        settings.applyToEngine()
        log("REVERB ${if (settings.reverbOn) "ON" else "OFF"}")
        changed()
    }

    fun setReverbLevel(v: Int) { settings.reverbLevel = v.coerceIn(0, 100); settings.applyToEngine() }

    fun stepManual(dir: Int) {
        val o = organ ?: return
        if (o.manuals.isEmpty()) return
        val cur = o.manuals.indexOfFirst { it === performer.activeManual }.coerceAtLeast(0)
        val next = o.manuals[(cur + dir + o.manuals.size) % o.manuals.size]
        setActiveManual(next)
        log("Active manual: ${next.name}")
        notifyChanged()
    }

    fun allTremulantsOff() {
        organ?.tremulants?.forEach { if (it.on) { it.on = false; pushTrem(it) } }
        log("ALL TREMULANTS OFF")
        changed()
    }

    fun allCouplersOff() {
        organ?.manuals?.forEach { m -> m.couplers.forEach { it.engaged = false } }
        log("ALL COUPLERS OFF")
        changed()
    }

    // ---------------- couplers / tremulants ----------------

    fun setCoupler(c: Coupler, on: Boolean) {
        c.engaged = on
        performer.refreshCouplers()
        log("COUPLER ${if (on) "ON " else "OFF"} ${c.name}")
        changed()
    }

    /** Tremulants that act on this manual (any of its ranks sits on a windchest with that tremulant). */
    fun tremulantsFor(m: Manual): List<Tremulant> {
        val o = organ ?: return emptyList()
        var mask = 0
        for (s in m.stops) for (r in s.ranks) mask = mask or r.rank.tremMask
        return o.tremulants.filter { (mask and (1 shl it.slot)) != 0 }
    }

    /** Constant speed and depth from start to release (fast fixed fade only to avoid clicks). */
    private fun pushTrem(t: Tremulant) {
        val speed = settings.tremSpeed.coerceAtLeast(10) / 100f
        val depth = settings.tremDepth / 100f
        AudioEngine.setTremulant(t.slot, t.on, t.periodMs / speed, (t.depthPct * depth).coerceAtMost(100f), 60f, 120f)
    }

    fun repushTremulants() { organ?.tremulants?.forEach { pushTrem(it) } }

    fun setTremulant(t: Tremulant, on: Boolean) {
        t.on = on
        pushTrem(t)
        log("TREMULANT ${if (on) "ON " else "OFF"} ${t.name}")
        changed()
    }

    // ---------------- settings na ginagamit sa quick panel / MIDI ----------------

    fun setTranspose(v: Int) { settings.transpose = v.coerceIn(-12, 12); settings.applyToEngine(); changed() }
    fun setPitch(v: Int) { settings.tuningA4 = v.coerceIn(415, 466); settings.applyToEngine(); changed() }
    fun setVolume(v: Int) { settings.masterVolume = v.coerceIn(0, 100); settings.applyToEngine(); changed() }

    // ---------------- state / presets ----------------

    private fun capture(withLoaded: Boolean): JSONObject {
        val o = organ ?: return JSONObject()
        val j = JSONObject()
        val stops = allStops()
        if (withLoaded) j.put("loaded", JSONArray(stops.filter { it.loaded }.map { it.key }))
        j.put("on", JSONArray(stops.filter { it.loaded && it.enabled }.map { it.key }))
        j.put("cp", JSONArray(o.manuals.flatMap { it.couplers }.filter { it.engaged }.map { it.id.toString() }))
        j.put("tr", JSONArray(o.tremulants.filter { it.on }.map { it.id.toString() }))
        return j
    }

    private fun strSet(a: JSONArray?): Set<String> {
        val out = HashSet<String>()
        if (a != null) for (i in 0 until a.length()) out += a.optString(i)
        return out
    }

    private fun applyCouplersAndTrem(j: JSONObject) {
        val o = organ ?: return
        if (j.has("cp")) {
            val cp = strSet(j.optJSONArray("cp"))
            for (m in o.manuals) for (c in m.couplers) c.engaged = c.id.toString() in cp
        }
        if (j.has("tr")) {
            val tr = strSet(j.optJSONArray("tr"))
            for (t in o.tremulants) { t.on = t.id.toString() in tr; pushTrem(t) }
        }
        performer.refreshCouplers()
    }

    fun saveState() {
        if (organ == null) return
        prefs.edit().putString("state_$organId", capture(true).toString()).apply()
    }

    private fun restoreState() {
        val txt = prefs.getString("state_$organId", null) ?: return
        restoring = true
        try {
            val j = JSONObject(txt)
            val loaded = strSet(j.optJSONArray("loaded"))
            val on = strSet(j.optJSONArray("on"))
            for (s in allStops()) if (s.key in loaded) doLoad(s, s.key in on)
            applyCouplersAndTrem(j)
        } catch (e: Exception) {
            log("Could not restore the previous setup: ${e.message}")
        } finally { restoring = false }
    }

    private fun presets(): JSONObject =
        try { JSONObject(prefs.getString("presets_$organId", null) ?: "{}") } catch (_: Exception) { JSONObject() }

    fun hasPreset(id: String) = presets().has(id)

    fun savePreset(id: String) {
        if (organ == null) return
        val p = presets()
        p.put(id, capture(false))
        prefs.edit().putString("presets_$organId", p.toString()).apply()
        activePreset = id
        log("PRESET $id saved")
        notifyChanged()
    }

    fun recallPreset(id: String) {
        val j = presets().optJSONObject(id) ?: run { log("PRESET $id: empty"); return }
        val on = strSet(j.optJSONArray("on"))
        for (s in allStops()) {
            if (s.key in on) { if (s.loaded) s.enabled = true else doLoad(s, true) }
            else if (s.loaded) s.enabled = false
        }
        applyCouplersAndTrem(j)
        activePreset = id
        lastStepped = id
        log("PRESET $id")
        changed()
    }

    private fun presetStillActive(): Boolean {
        val id = activePreset ?: return true
        val j = presets().optJSONObject(id) ?: return false
        val cur = capture(false)
        return strSet(cur.optJSONArray("on")) == strSet(j.optJSONArray("on")) &&
            strSet(cur.optJSONArray("cp")) == strSet(j.optJSONArray("cp")) &&
            strSet(cur.optJSONArray("tr")) == strSet(j.optJSONArray("tr"))
    }

    /** Next / previous numbered preset (only the ones that have been saved). */
    fun stepPreset(dir: Int) {
        val list = NUMBERED.filter { hasPreset(it) }
        if (list.isEmpty()) { log("No numbered presets saved yet"); return }
        val cur = activePreset?.takeIf { it in NUMBERED } ?: lastStepped?.takeIf { it in NUMBERED }
        val idx = list.indexOf(cur)
        val next = when {
            idx < 0 -> if (dir > 0) list.first() else list.last()
            else -> list[(idx + dir + list.size) % list.size]
        }
        recallPreset(next)
    }

    // ---------------- per-stop adjustments (volume / release / tuning) ----------------

    fun saveStopConfigs() {
        if (organ == null) return
        val j = JSONObject()
        for (s in allStops()) {
            if (s.gainPct == 100 && s.releaseMs == 0 && s.tuneCents == 0 && s.pipeCents.isEmpty() && s.textColor == Stop.COLOR_AUTO) continue
            val o = JSONObject().put("g", s.gainPct).put("r", s.releaseMs).put("c", s.tuneCents).put("tc", s.textColor)
            val p = JSONObject()
            for ((n, cents) in s.pipeCents) p.put(n.toString(), cents)
            o.put("p", p)
            j.put(s.key, o)
        }
        prefs.edit().putString("stopcfg_$organId", j.toString()).apply()
    }

    private fun loadStopConfigs() {
        val txt = prefs.getString("stopcfg_$organId", null) ?: return
        try {
            val j = JSONObject(txt)
            for (k in j.keys()) {
                val s = stopByKey(k) ?: continue
                val o = j.getJSONObject(k)
                s.gainPct = o.optInt("g", 100); s.releaseMs = o.optInt("r", 0); s.tuneCents = o.optInt("c", 0)
                s.textColor = o.optInt("tc", Stop.COLOR_AUTO)
                s.pipeCents.clear()
                o.optJSONObject("p")?.let { p -> for (n in p.keys()) s.pipeCents[n.toInt()] = p.getInt(n) }
            }
        } catch (_: Exception) { }
    }

    // ---------------- stop text colours (console) ----------------

    private fun reedWordList(): List<String> =
        settings.reedWords.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun isReed(name: String, words: List<String> = reedWordList()): Boolean {
        val n = name.lowercase()
        return words.any { n.contains(it) }
    }

    /** Text colour of a stop in the console. 0 = normal. */
    fun stopTextColor(s: Stop, words: List<String> = reedWordList()): Int = when {
        s.textColor == Stop.COLOR_PLAIN -> 0
        s.textColor != Stop.COLOR_AUTO -> s.textColor
        settings.autoReedColor && isReed(s.name, words) -> REED_RED
        else -> 0
    }

    // ---------------- impulse response (per organ) ----------------

    private fun loadIrConfig() {
        ir = IrConfig()
        val txt = prefs.getString("ir_$organId", null) ?: return
        try {
            val j = JSONObject(txt)
            ir = IrConfig(j.optString("uri", "").ifEmpty { null }, j.optString("name", ""), j.optBoolean("on", false), j.optInt("level", 40))
        } catch (_: Exception) { }
    }

    private fun saveIrConfig() {
        if (organ == null) return
        val j = JSONObject().put("uri", ir.uri ?: "").put("name", ir.name).put("on", ir.on).put("level", ir.level)
        prefs.edit().putString("ir_$organId", j.toString()).apply()
    }

    /** Sends on/off + level to the engine (call after the engine starts, too). */
    fun applyIr() { AudioEngine.setIr(ir.on && ir.uri != null, ir.level / 100f) }

    /** (Re)loads the IR file of the current organ on a background thread; the newest request wins. */
    private fun startIrLoad() {
        val gen = ++irGen
        val uri = ir.uri
        applyIr()
        irExec.execute {
            if (gen != irGen) return@execute
            if (uri == null) { AudioEngine.clearIr(); irStatus = ""; return@execute }
            try {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
                    ?: throw java.io.IOException("cannot open the file")
                val code = AudioEngine.loadIr(pfd.detachFd(), settings.irMaxMs)
                irStatus = if (code == 0) AudioEngine.irInfo() else AudioEngine.lastError()
                log(if (code == 0) irStatus else "IR load failed: $irStatus")
            } catch (e: Exception) {
                irStatus = e.message ?: "error"
                log("IR load failed: $irStatus")
            }
            runUi(Runnable { applyIr(); notifyChanged() })
        }
    }

    fun setIrFile(uri: Uri) {
        if (organ == null) { log("Load an organ first."); return }
        var name = uri.lastPathSegment ?: "IR"
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> name = n }
            }
        } catch (_: Exception) { }
        ir = IrConfig(uri.toString(), name, true, ir.level)
        saveIrConfig()
        startIrLoad()
        notifyChanged()
    }

    fun clearIrFile() {
        ir = IrConfig(null, "", false, ir.level)
        saveIrConfig()
        startIrLoad()
        notifyChanged()
    }

    fun setIrOn(on: Boolean) { ir.on = on; saveIrConfig(); applyIr(); notifyChanged() }
    fun setIrLevel(v: Int) { ir.level = v.coerceIn(0, 100); saveIrConfig(); applyIr() }

    /** The max IR length changed in Settings: rebuild the convolver from the stored IR. */
    fun rebuildIr() {
        if (ir.uri == null) return
        irExec.execute {
            AudioEngine.rebuildIr(settings.irMaxMs)
            irStatus = AudioEngine.irInfo()
            runUi(Runnable { notifyChanged() })
        }
    }

    // ---------------- swell / expression ----------------

    fun addSwellListener(l: () -> Unit) { swellListeners.add(l) }
    fun removeSwellListener(l: () -> Unit) { swellListeners.remove(l) }

    fun swellById(id: Int?) = swells.firstOrNull { it.id == id }

    private fun loadSwells() {
        swells.clear()
        val txt = prefs.getString("swells_$organId", null) ?: return
        try {
            val a = JSONArray(txt)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ms = HashSet<Int>()
                o.optJSONArray("m")?.let { for (k in 0 until it.length()) ms += it.getInt(k) }
                swells.add(Swell(o.getInt("id"), o.optString("name", "Swell"), ms, o.optInt("min", 25)))
            }
        } catch (_: Exception) { }
    }

    private fun saveSwells() {
        val a = JSONArray()
        for (s in swells) {
            a.put(JSONObject().put("id", s.id).put("name", s.name).put("min", s.minPct).put("m", JSONArray(s.manuals.toList())))
        }
        prefs.edit().putString("swells_$organId", a.toString()).apply()
    }

    /** Volume of every manual = product of the swell shoes that control it. */
    fun applySwells() {
        val g = FloatArray(16) { 1f }
        for (s in swells) {
            val level = (s.minPct + (100 - s.minPct) * s.pos / 100f) / 100f
            for (m in s.manuals) if (m in 0..15) g[m] *= level
        }
        for (i in 0..15) AudioEngine.setGroupGain(i, g[i])
    }

    fun addSwell(): Swell {
        val id = (swells.maxOfOrNull { it.id } ?: 0) + 1
        val ms = organ?.manuals?.filter { !it.isPedal }?.map { it.index }?.toSet() ?: emptySet()
        val s = Swell(id, "Swell $id", ms, 25)
        swells.add(s)
        saveSwells(); applySwells(); notifyChanged()
        return s
    }

    fun removeSwell(s: Swell) {
        swells.remove(s)
        midiMap.clear("swell:${s.id}")
        saveSwells(); applySwells(); notifyChanged()
    }

    /** After editing name / manuals / minimum level. */
    fun swellEdited() { saveSwells(); applySwells() }

    fun setSwellPos(s: Swell, pos: Int) {
        s.pos = pos.coerceIn(0, 100)
        applySwells()
        if (swellPosted.compareAndSet(false, true)) {
            runUi(Runnable { swellPosted.set(false); swellListeners.forEach { it() } })
        }
    }

    // ---------------- MIDI channel -> manual ----------------

    private fun loadChannelMap(id: String): IntArray {
        val out = IntArray(16) { -1 }
        val txt = prefs.getString("chmap_$id", null) ?: return out
        val parts = txt.split(',')
        for (i in 0 until minOf(16, parts.size)) out[i] = parts[i].toIntOrNull() ?: -1
        return out
    }

    fun setChannelMap(map: IntArray) {
        performer.channelMap = map.copyOf()
        prefs.edit().putString("chmap_$organId", map.joinToString(",")).apply()
    }

    // ---------------- MIDI input (MIDI thread) ----------------

    /** true = ginamit ang message bilang control (huwag patugtugin bilang nota). */
    fun onMidi(type: Int, ch: Int, num: Int, value: Int): Boolean {
        val learn = midiMap.learning
        if (learn != null) {
            val accept = when (type) { MidiMap.PC -> true; else -> value > 0 }
            if (accept) {
                midiMap.bind(learn, MidiMap.Binding(type, ch, num))
                midiMap.learning = null
                log("MIDI MAP $learn <- ch${ch + 1} #$num")
                runUi(Runnable { midiMap.onLearned?.invoke() })
            }
            return true
        }
        val actions = midiMap.find(type, ch, num)
        if (actions.isEmpty()) return false
        for (a in actions) {
            when (type) {
                MidiMap.NOTE -> if (value > 0) run(a, value)
                MidiMap.CC -> if (a == "vol" || a == "revlvl" || a.startsWith("swell:") || midiMap.rising(a, value)) run(a, value)
                else -> run(a, value)
            }
        }
        return true
    }

    private fun run(a: String, v: Int) {
        when {
            a == "gc" -> generalCancel()
            a == "panic" -> panic()
            a == "tp+" -> setTranspose(settings.transpose + 1)
            a == "tp-" -> setTranspose(settings.transpose - 1)
            a == "tp0" -> setTranspose(0)
            a == "rev" -> toggleReverb()
            a == "revlvl" -> setReverbLevel(v * 100 / 127)
            a == "man+" -> stepManual(1)
            a == "man-" -> stepManual(-1)
            a == "tremoff" -> allTremulantsOff()
            a == "cpoff" -> allCouplersOff()
            a == "preset+" -> stepPreset(1)
            a == "preset-" -> stepPreset(-1)
            a.startsWith("swell:") -> swellById(a.substring(6).toIntOrNull())?.let { setSwellPos(it, v * 100 / 127) }
            a == "vol" -> setVolume(v * 100 / 127)
            a.startsWith("preset:") -> recallPreset(a.substring(7))
            a.startsWith("stop:") -> stopByKey(a.substring(5))?.let { toggleStop(it) }
            a.startsWith("cp:") -> couplerById(a.substring(3).toIntOrNull())?.let { setCoupler(it, !it.engaged) }
            a.startsWith("trem:") -> tremById(a.substring(5).toIntOrNull())?.let { setTremulant(it, !it.on) }
        }
    }

    companion object {
        const val REED_RED = 0xFFFF5A5F.toInt()
        val DYNAMICS = listOf("P", "MP", "MF", "F", "T")
        val NUMBERED = (1..16).map { it.toString() }
        fun presetName(id: String) = when (id) {
            "P" -> "Piano (P)"; "MP" -> "Mezzo piano (MP)"; "MF" -> "Mezzo forte (MF)"
            "F" -> "Forte (F)"; "T" -> "Tutti (T)"; else -> "Preset $id"
        }
    }
}
