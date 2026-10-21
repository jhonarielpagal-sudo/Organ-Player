package com.example.organplayer

import java.util.TreeSet

/**
 * Lohika ng pagtugtog (MIDI thread + UI thread, kaya synchronized):
 *  - unison + NORMAL coupler
 *  - MELODY coupler: pinakamataas na nakapindot lang ang tutunog
 *  - BASS coupler: pinakamababa na nakapindot lang ang tutunog
 *  - MIDI channel -> manual
 */
class Performer(private val settings: Settings) {
    @Volatile var samples: SampleManager? = null
    @Volatile var organ: Organ? = null
    @Volatile var activeManual: Manual? = null
    @Volatile var channelMap: IntArray = IntArray(16) { -1 }      // -1 = gamitin ang active manual

    private class Special(val note: Int, val hits: SampleManager.Hits)

    private val held = HashMap<Int, TreeSet<Int>>()               // manual -> nakapindot na notes
    private val vels = HashMap<Int, Int>()                        // manual*128+note -> velocity
    private val owner = HashMap<Int, Int>()                       // channel*128+note -> manual
    private val unison = HashMap<Int, SampleManager.Hits>()       // manual*128+note -> pipes
    private val special = HashMap<Int, Special>()                 // coupler id -> kasalukuyang tunog

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

    /** false = walang organ/manual (gagamitin ang test wavetable) */
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
        updateSpecial(m)
        return true
    }

    @Synchronized
    fun noteOff(channel: Int, note: Int): Boolean {
        val mi = owner.remove(channel * 128 + note) ?: return false
        val id = mi * 128 + note
        held[mi]?.remove(note)
        vels.remove(id)
        unison.remove(id)?.let { release(it) }
        organ?.manuals?.firstOrNull { it.index == mi }?.let { updateSpecial(it) }
        return true
    }

    /** Tawagin kapag may in-on/off na coupler habang may nakapindot na key. */
    @Synchronized
    fun refreshCouplers() {
        organ?.manuals?.forEach { updateSpecial(it) }
    }

    private fun updateSpecial(m: Manual) {
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
            if (cur != null && cur.note == target) continue
            if (cur == null && target == null) continue
            if (cur != null) { release(cur.hits); special.remove(c.id) }
            if (target != null) {
                val dest = o.manuals.firstOrNull { it.index == c.destManual } ?: continue
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
