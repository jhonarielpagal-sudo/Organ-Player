package com.example.organplayer

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lazy loading ng samples: kapag in-on ang stop, ang samples lang ng stop na iyon ang ilo-load.
 * May reference count para sa mga rank na ginagamit ng higit sa isang stop.
 */
class SampleManager(
    private val context: Context,
    private val folder: OrganFolder,
    private val baseDirId: String
) {
    private val pool = Executors.newFixedThreadPool(3)
    private val lock = Any()
    private val refs = HashMap<Int, Int>()

    fun pipeKey(rank: Rank, pipeNo: Int): Int = (rank.id + 100_000) * 1024 + pipeNo

    /** (key, path) ng lahat ng pipe ng stop na may sariling WAV path. */
    private fun pipesOf(stop: Stop): List<Pair<Int, String>> {
        val out = LinkedHashMap<Int, String>()
        for (r in stop.ranks) {
            for (i in 0 until r.pipeCount) {
                val no = r.firstPipeNumber + i
                val path = r.rank.pipes.getOrNull(no - 1)?.path ?: continue
                if (path.startsWith("REF:", ignoreCase = true)) continue   // aayusin sa susunod
                out[pipeKey(r.rank, no)] = path
            }
        }
        return out.map { it.key to it.value }
    }

    fun acquire(stop: Stop, onDone: (loaded: Int, failed: Int) -> Unit) {
        val toLoad = ArrayList<Pair<Int, String>>()
        synchronized(lock) {
            for (p in pipesOf(stop)) {
                val n = (refs[p.first] ?: 0) + 1
                refs[p.first] = n
                if (n == 1) toLoad += p
            }
        }
        if (toLoad.isEmpty()) { onDone(0, 0); return }

        val pending = AtomicInteger(toLoad.size)
        val failed = AtomicInteger(0)
        for ((key, path) in toLoad) {
            pool.execute {
                var ok = false
                try {
                    if (wanted(key)) {
                        ok = loadFile(key, path)
                        if (ok && !wanted(key)) AudioEngine.unloadSample(key)   // na-off habang naglo-load
                    } else ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "load failed: $path", e)
                }
                if (!ok) failed.incrementAndGet()
                if (pending.decrementAndGet() == 0) onDone(toLoad.size - failed.get(), failed.get())
            }
        }
    }

    fun release(stop: Stop) {
        val dead = ArrayList<Int>()
        synchronized(lock) {
            for (p in pipesOf(stop)) {
                val n = (refs[p.first] ?: 0) - 1
                if (n <= 0) { refs.remove(p.first); dead += p.first } else refs[p.first] = n
            }
        }
        dead.forEach { AudioEngine.unloadSample(it) }
    }

    fun unloadAll() {
        val keys = synchronized(lock) { refs.keys.toList().also { refs.clear() } }
        keys.forEach { AudioEngine.unloadSample(it) }
        pool.shutdownNow()
    }

    /** Mga key ng pipe na tutunog kapag pinindot ang MIDI note sa manual na ito (enabled stops lang). */
    fun keysForNote(manual: Manual, note: Int): IntArray {
        val k = note - manual.firstMidiNote + 1          // logical key number (1-based)
        val out = ArrayList<Int>()
        for (s in manual.stops) {
            if (!s.enabled) continue
            for (r in s.ranks) {
                if (k < r.firstAccessibleKey || k >= r.firstAccessibleKey + r.pipeCount) continue
                val no = r.firstPipeNumber + (k - r.firstAccessibleKey)
                val path = r.rank.pipes.getOrNull(no - 1)?.path ?: continue
                if (path.startsWith("REF:", ignoreCase = true)) continue
                out += pipeKey(r.rank, no)
            }
        }
        return out.toIntArray()
    }

    private fun wanted(key: Int) = synchronized(lock) { (refs[key] ?: 0) > 0 }

    private fun loadFile(key: Int, path: String): Boolean {
        val uri = folder.resolve(baseDirId, path) ?: run { Log.w(TAG, "wala ang file: $path"); return false }
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
        return AudioEngine.loadSample(key, pfd.detachFd())   // native ang magsasara ng fd
    }

    companion object { private const val TAG = "OrganSamples" }
}
