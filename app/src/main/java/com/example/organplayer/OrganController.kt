package com.example.organplayer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Hawak ang estado ng organ: stops (load/mute), couplers, tremulants, presets, MIDI actions, save/restore. */
class OrganController(
    private val context: Context,
    val settings: Settings,
    private val prefs: SharedPreferences,
    private val logFn: (String) -> Unit,
    private val runUi: (Runnable) -> Unit
) {
    val performer = Performer(settings)
    val midiMap = MidiMap(prefs)
    @Volatile var organ: Organ? = null
    @Volatile var organId: String = ""
    @Volatile private var samples: SampleManager? = null
    @Volatile private var restoring = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun log(s: String) = logFn(s)
    fun notifyChanged() = runUi(Runnable { listeners.forEach { it() } })
    private fun changed() {
        if (!restoring) { performer.refreshAll(); saveState() }     // held keys follow the new registration
        notifyChanged()
    }

    // ---------------- organ ----------------

    fun attach(o: Organ, f: OrganFolder, ref: OrganFolder.OrganRef, id: String) {
        performer.panic()
        performer.samples = null; performer.organ = null
        samples?.unloadAll()
        organ = o; organId = id
        val sm = SampleManager(context, f, ref.baseDirId, o, settings)
        samples = sm
        performer.samples = sm; performer.organ = o
        performer.channelMap = loadChannelMap(id)
        val savedIdx = prefs.getInt("active_$id", -1)
        performer.activeManual = o.manuals.firstOrNull { it.index == savedIdx }
            ?: o.manuals.firstOrNull { !it.isPedal } ?: o.manuals.firstOrNull()
        midiMap.load(id)
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
        s.loaded = true; s.ready = false; s.enabled = sound; s.status = "loading..."
        val t0 = System.currentTimeMillis()
        val mName = organ?.manuals?.firstOrNull { it.index == s.manualIndex }?.name ?: "?"
        sm.acquire(s) { r ->
            s.status = when {
                r.failed > 0 -> "${r.failed} failed to load"
                r.unresolved > 0 -> "${r.unresolved} missing samples"
                else -> ""
            }
            if (s.loaded) s.ready = true
            log("STOP LOAD $mName: ${s.name} -> ${r.loaded} loaded, ${r.shared} shared, ${r.failed} failed, " +
                "${r.unresolved} no path/REF, ${System.currentTimeMillis() - t0} ms")
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

    // ---------------- couplers / tremulants ----------------

    fun setCoupler(c: Coupler, on: Boolean) {
        c.engaged = on
        performer.refreshCouplers()
        log("COUPLER ${if (on) "ON " else "OFF"} ${c.name}")
        changed()
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
        log("PRESET $id")
        changed()
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
                MidiMap.CC -> if (a == "vol" || midiMap.rising(a, value)) run(a, value)
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
            a == "vol" -> setVolume(v * 100 / 127)
            a.startsWith("preset:") -> recallPreset(a.substring(7))
            a.startsWith("stop:") -> stopByKey(a.substring(5))?.let { toggleStop(it) }
            a.startsWith("cp:") -> couplerById(a.substring(3).toIntOrNull())?.let { setCoupler(it, !it.engaged) }
            a.startsWith("trem:") -> tremById(a.substring(5).toIntOrNull())?.let { setTremulant(it, !it.on) }
        }
    }

    companion object {
        val DYNAMICS = listOf("P", "MP", "MF", "F", "T")
        val NUMBERED = (1..8).map { it.toString() }
        fun presetName(id: String) = when (id) {
            "P" -> "Piano (P)"; "MP" -> "Mezzo piano (MP)"; "MF" -> "Mezzo forte (MF)"
            "F" -> "Forte (F)"; "T" -> "Tutti (T)"; else -> "Preset $id"
        }
    }
}
