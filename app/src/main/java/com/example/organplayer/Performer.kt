package com.example.organplayer

import java.util.TreeSet

/**
 * Playing logic (MIDI thread + UI thread, so synchronized):
 *  - unison + NORMAL couplers
 *  - MELODY coupler: follows ONE key, the top note of the right hand. A key becomes the melody when it is
 *    pressed above every key that is held at that moment. When the melody key is released, the melody passes
 *    only to a key that was pressed just before that (within the "handover" time in Settings = a legato
 *    overlap of the next soprano note). A key that has been held for longer (the alto) is never promoted, so
 *    in the small gap between two soprano notes the coupler goes quiet instead of jumping to the alto.
 *    BASS coupler: the same, mirrored (lowest key; a tenor is never promoted to bass).
 *  - MIDI channel -> manual
 *  - refreshAll(): changing stops/couplers while keys are held updates the held notes immediately
 *
 * Every sounding pipe gets an engine tag = (stop tag << 12) | source, so the same pipe used
 * by two stops (or by a key and a coupler) sounds separately and is released separately.
 */
class Performer(private val settings: Settings) {
    @Volatile var samples: SampleManager? = null
    @Volatile var organ: Organ? = null
    @Volatile var activeManual: Manual? = null
    @Volatile var channelMap: IntArray = IntArray(16) { -1 }      // -1 = use the active manual
    /** Last key played (the note whose pipe actually sounds, transpose included) - used for pipe tuning. */
    @Volatile var lastPipeNote: Int = -1
    @Volatile var lastManualIndex: Int = -1

    private class Special(val note: Int, val hits: SampleManager.Hits)

    private val held = HashMap<Int, TreeSet<Int>>()               // manual -> held notes
    private val vels = HashMap<Int, Int>()                        // manual*128+note -> velocity
    private val owner = HashMap<Int, Int>()                       // channel*128+note -> manual
    private val unison = HashMap<Int, SampleManager.Hits>()       // manual*128+note -> sounding pipes
    private val special = HashMap<Int, Special>()                 // coupler id -> sounding pipes
    private var seqCounter = 0L
    private val pressSeq = HashMap<Int, Long>()                   // manual*128+note -> order of pressing
    private val pressNs = HashMap<Int, Long>()                    // manual*128+note -> time of pressing
    private val melodyKey = HashMap<Int, Int>()                   // manual -> note that the MELODY coupler follows
    private val bassKey = HashMap<Int, Int>()                     // manual -> note that the BASS coupler follows

    private fun manualFor(ch: Int): Manual? {
        val o = organ ?: return null
        val idx = channelMap.getOrElse(ch) { -1 }
        if (idx >= 0) o.manuals.firstOrNull { it.index == idx }?.let { return it }
        return activeManual
    }

    private fun tagOf(p: SampleManager.PipeInfo, src: Int) = (p.tag shl 12) or src
    private fun specialSrc(c: Coupler) = 2048 + (c.id and 2047)

    private fun on(p: SampleManager.PipeInfo, vel: Int, src: Int) =
        AudioEngine.pipeOn(p.key, vel, p.mask, tagOf(p, src), p.group, p.gain, p.cents, p.relMs)

    private fun off(p: SampleManager.PipeInfo, src: Int) = AudioEngine.pipeOff(p.key, tagOf(p, src))

    private fun trigger(h: SampleManager.Hits, vel: Int, src: Int) { for (p in h.pipes) on(p, vel, src) }
    private fun release(h: SampleManager.Hits, src: Int) { for (p in h.pipes) off(p, src) }

    /** Starts only the pipes that were added and releases only the pipes that were removed. */
    private fun diff(old: SampleManager.Hits, new: SampleManager.Hits, vel: Int, src: Int) {
        val oldIds = old.pipes.map { it.id }.toHashSet()
        val newIds = new.pipes.map { it.id }.toHashSet()
        for (p in old.pipes) if (p.id !in newIds) off(p, src)
        for (p in new.pipes) if (p.id !in oldIds) on(p, vel, src)
    }

    /** false = no organ/manual (the test wavetable is used instead) */
    @Synchronized
    fun noteOn(channel: Int, note: Int, vel: Int): Boolean {
        val sm = samples ?: return false
        val m = manualFor(channel) ?: return false
        val id = m.index * 128 + note
        unison.remove(id)?.let { release(it, id) }                // re-strike
        owner[channel * 128 + note] = m.index
        val set = held.getOrPut(m.index) { TreeSet() }
        pressSeq[id] = ++seqCounter
        pressNs[id] = System.nanoTime()
        if (set.isEmpty() || note > set.last()) melodyKey[m.index] = note     // new top note = melody
        if (set.isEmpty() || note < set.first()) bassKey[m.index] = note      // new bottom note = bass
        set.add(note)
        vels[id] = vel
        lastPipeNote = note + settings.transpose
        lastManualIndex = m.index
        val h = sm.normalHits(m, note)
        unison[id] = h
        trigger(h, vel, id)
        updateSpecial(m, false)
        return true
    }

    @Synchronized
    fun noteOff(channel: Int, note: Int): Boolean {
        val mi = owner.remove(channel * 128 + note) ?: return false
        val id = mi * 128 + note
        val set = held[mi]
        set?.remove(note)
        val mySeq = pressSeq.remove(id) ?: 0L
        pressNs.remove(id)
        if (set != null && (melodyKey[mi] == note || bassKey[mi] == note)) {
            val now = System.nanoTime()
            val windowNs = settings.handoverMs * 1_000_000L
            // a key may take over only if it was pressed after the released one AND just before this release
            val heirs = set.filter {
                (pressSeq[mi * 128 + it] ?: 0L) > mySeq && now - (pressNs[mi * 128 + it] ?: 0L) <= windowNs
            }
            if (melodyKey[mi] == note) { val h = heirs.maxOrNull(); if (h != null) melodyKey[mi] = h else melodyKey.remove(mi) }
            if (bassKey[mi] == note) { val h = heirs.minOrNull(); if (h != null) bassKey[mi] = h else bassKey.remove(mi) }
        }
        vels.remove(id)
        unison.remove(id)?.let { release(it, id) }
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
                diff(old, new, vels[id] ?: 100, id)
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
            val src = specialSrc(c)
            val cur = special[c.id]
            val target: Int? = when {
                !c.engaged || set == null || set.isEmpty() -> null
                c.kind == CouplerKind.MELODY -> melodyKey[m.index]?.takeIf { it in set }
                else -> bassKey[m.index]?.takeIf { it in set }
            }
            if (cur == null && target == null) continue
            val dest = o.manuals.firstOrNull { it.index == c.destManual }
            if (cur != null && target != null && cur.note == target) {
                if (refresh && dest != null) {
                    val h = sm.hitsForNote(dest, target + c.keyShift + settings.transpose)
                    diff(cur.hits, h, vels[m.index * 128 + target] ?: 100, src)
                    special[c.id] = Special(target, h)
                }
                continue
            }
            if (cur != null) { release(cur.hits, src); special.remove(c.id) }
            if (target != null && dest != null) {
                val h = sm.hitsForNote(dest, target + c.keyShift + settings.transpose)
                special[c.id] = Special(target, h)
                trigger(h, vels[m.index * 128 + target] ?: 100, src)
            }
        }
    }

    /** All Notes Off */
    @Synchronized
    fun panic() {
        unison.clear(); special.clear(); held.clear(); vels.clear(); owner.clear()
        pressSeq.clear(); pressNs.clear(); melodyKey.clear(); bassKey.clear()
        AudioEngine.panic()
    }
}
