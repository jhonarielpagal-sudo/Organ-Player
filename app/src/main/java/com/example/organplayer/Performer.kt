package com.example.organplayer

import java.util.TreeSet

/**
 * Playing logic (MIDI thread + UI thread, so synchronized):
 *  - unison + NORMAL couplers
 *  - MELODY coupler: only the highest held note sounds
 *  - BASS coupler: only the lowest held note sounds
 *  - MIDI channel -> manual
 *  - refreshAll(): when stops/couplers change while keys are held, the held notes
 *    get the added pipes (or lose the removed ones) immediately, like a real organ.
 */
class Performer(private val settings: Settings) {
    @Volatile var samples: SampleManager? = null
    @Volatile var organ: Organ? = null
    @Volatile var activeManual: Manual? = null
    @Volatile var channelMap: IntArray = IntArray(16) { -1 }      // -1 = use the active manual

    private class Special(val note: Int, val hits: SampleManager.Hits)

    private val held = HashMap<Int, TreeSet<Int>>()               // manual -> held notes
    private val vels = HashMap<Int, Int>()                        // manual*128+note -> velocity
    private val owner = HashMap<Int, Int>()                       // channel*128+note -> manual
    private val unison = HashMap<Int, SampleManager.Hits>()       // manual*128+note -> sounding pipes
    private val special = HashMap<Int, Special>()                 // coupler id -> sounding pipes

    private fun manualFor(ch: Int): Manual? {
        val o = organ ?: return null
        val idx = channelMap.getOrElse(ch) { -1 }
        if (idx >= 0) o.manuals.firstOrNull { it.index == idx }?.let { return it }
        return activeManual
    }

    private fun trigger(h: SampleManager.Hits, vel: Int) {
        for (i in h.keys.indices) AudioEngine.pipeOn(h.keys[i], vel, h.masks[i])
    }

    private fun release(h: SampleManager.Hits) {
        for (k in h.keys) AudioEngine.pipeOff(k)
    }

    /** Starts only the pipes that were added and releases only the pipes that were removed. */
    private fun diff(old: SampleManager.Hits, new: SampleManager.Hits, vel: Int) {
        val oldSet = old.keys.toHashSet()
        val newSet = new.keys.toHashSet()
        for (k in old.keys) if (k !in newSet) AudioEngine.pipeOff(k)
        for (i in new.keys.indices) if (new.keys[i] !in oldSet) AudioEngine.pipeOn(new.keys[i], vel, new.masks[i])
    }

    /** false = no organ/manual (the test wavetable is used instead) */
    @Synchronized
    fun noteOn(channel: Int, note: Int, vel: Int): Boolean {
        val sm = samples ?: return false
        val m = manualFor(channel) ?: return false
        val id = m.index * 128 + note
        unison.remove(id)?.let { release(it) }                    // re-strike
        owner[channel * 128 + note] = m.index
        held.getOrPut(m.index) { TreeSet() }.add(note)
        vels[id] = vel
        val h = sm.normalHits(m, note)
        unison[id] = h
        trigger(h, vel)
        updateSpecial(m, false)
        return true
    }

    @Synchronized
    fun noteOff(channel: Int, note: Int): Boolean {
        val mi = owner.remove(channel * 128 + note) ?: return false
        val id = mi * 128 + note
        held[mi]?.remove(note)
        vels.remove(id)
        unison.remove(id)?.let { release(it) }
        organ?.manuals?.firstOrNull { it.index == mi }?.let { updateSpecial(it, false) }
        return true
    }

    /** Call after any stop / coupler / transpose change: updates the notes that are being held. */
    @Synchronized
    fun refreshAll() {
        val sm = samples ?: return
        val o = organ ?: return
        for ((mi, set) in held) {
            val m = o.manuals.firstOrNull { it.index == mi } ?: continue
            for (note in set) {
                val id = mi * 128 + note
                val old = unison[id] ?: continue
                val new = sm.normalHits(m, note)
                diff(old, new, vels[id] ?: 100)
                unison[id] = new
            }
        }
        for (m in o.manuals) updateSpecial(m, true)
    }

    fun refreshCouplers() = refreshAll()

    private fun updateSpecial(m: Manual, refresh: Boolean) {
        val sm = samples ?: return
        val o = organ ?: return
        val set = held[m.index]
        for (c in m.couplers) {
            if (c.kind == CouplerKind.NORMAL) continue
            val cur = special[c.id]
            val target: Int? = when {
                !c.engaged || set == null || set.isEmpty() -> null
                c.kind == CouplerKind.MELODY -> set.last()
                else -> set.first()
            }
            if (cur == null && target == null) continue
            val dest = o.manuals.firstOrNull { it.index == c.destManual }
            if (cur != null && target != null && cur.note == target) {
                if (refresh && dest != null) {
                    val h = sm.hitsForNote(dest, target + c.keyShift + settings.transpose)
                    diff(cur.hits, h, vels[m.index * 128 + target] ?: 100)
                    special[c.id] = Special(target, h)
                }
                continue
            }
            if (cur != null) { release(cur.hits); special.remove(c.id) }
            if (target != null && dest != null) {
                val h = sm.hitsForNote(dest, target + c.keyShift + settings.transpose)
                special[c.id] = Special(target, h)
                trigger(h, vels[m.index * 128 + target] ?: 100)
            }
        }
    }

    /** All Notes Off */
    @Synchronized
    fun panic() {
        unison.values.forEach { release(it) }
        special.values.forEach { release(it.hits) }
        unison.clear(); special.clear(); held.clear(); vels.clear(); owner.clear()
        AudioEngine.panic()
    }
}
