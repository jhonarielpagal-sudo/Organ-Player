package com.example.organplayer

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Lazy loading ng samples: kapag in-on ang stop, ang samples lang ng stop na iyon ang ilo-load.
 * May reference count (shared ang rank/pipe sa mga stop na gumagamit ng REF).
 */
class SampleManager(
    private val context: Context,
    private val folder: OrganFolder,
    private val baseDirId: String,
    private val organ: Organ,
    private val settings: Settings
) {
    class Report(
        val loaded: Int, val failed: Int, val shared: Int, val unresolved: Int,
        val reasons: String, val firstError: String?
    )

    private class Target(val key: Int, val pipe: PipeDef)

    private val pool = Executors.newFixedThreadPool(3)
    private val lock = Any()
    private val refs = HashMap<Int, Int>()

    fun pipeKey(rank: Rank, pipeNo: Int): Int = (rank.id + 100_000) * 1024 + pipeNo

    /** Sinusundan ang "REF:manual:stop:pipe" papunta sa tunay na pipe na may WAV path. */
    private fun target(rank: Rank, pipeNo: Int, depth: Int = 0): Target? {
        val p = rank.pipes.getOrNull(pipeNo - 1) ?: return null
        val path = p.path ?: return null
        if (!path.startsWith("REF:", ignoreCase = true)) return Target(pipeKey(rank, pipeNo), p)
        if (depth > 5) return null
        val parts = path.substring(4).split(':')
        if (parts.size < 3) return null
        val m = parts[0].trim().toIntOrNull() ?: return null
        val s = parts[1].trim().toIntOrNull() ?: return null
        val n = parts[2].trim().toIntOrNull() ?: return null
        val stop = organ.manuals.firstOrNull { it.index == m }?.stops?.getOrNull(s - 1) ?: return null
        var remaining = n
        for (r in stop.ranks) {
            if (remaining in 1..r.pipeCount) return target(r.rank, r.firstPipeNumber + remaining - 1, depth + 1)
            remaining -= r.pipeCount
        }
        return null
    }

    /** Lahat ng natatanging pipe ng stop (key -> Target) at bilang ng hindi ma-resolve. */
    private fun collect(stop: Stop): Pair<List<Target>, Int> {
        val out = LinkedHashMap<Int, Target>()
        var unresolved = 0
        for (r in stop.ranks) {
            for (i in 0 until r.pipeCount) {
                val t = target(r.rank, r.firstPipeNumber + i)
                if (t == null) unresolved++ else out[t.key] = t
            }
        }
        return out.values.toList() to unresolved
    }

    fun acquire(stop: Stop, onDone: (Report) -> Unit) {
        val (targets, unresolved) = collect(stop)
        val toLoad = ArrayList<Target>()
        var shared = 0
        synchronized(lock) {
            for (t in targets) {
                val n = (refs[t.key] ?: 0) + 1
                refs[t.key] = n
                if (n == 1) toLoad += t else shared++
            }
        }
        if (toLoad.isEmpty()) { onDone(Report(0, 0, shared, unresolved, "", null)); return }

        val pending = AtomicInteger(toLoad.size)
        val failed = AtomicInteger(0)
        val notFound = AtomicInteger(0)
        val openFail = AtomicInteger(0)
        val badWav = AtomicInteger(0)
        val other = AtomicInteger(0)
        val firstError = AtomicReference<String?>(null)

        for (t in toLoad) {
            pool.execute {
                var code = 0
                try {
                    if (wanted(t.key)) {
                        code = loadFile(t, firstError)
                        if (code == 0 && !wanted(t.key)) AudioEngine.unloadSample(t.key)   // na-off habang naglo-load
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "load failed: ${t.pipe.path}", e)
                    firstError.compareAndSet(null, "Exception: ${e.message}  [${t.pipe.path}]")
                    code = 99
                }
                if (code != 0) {
                    failed.incrementAndGet()
                    when (code) {
                        -1 -> notFound.incrementAndGet()
                        -2 -> openFail.incrementAndGet()
                        2 -> badWav.incrementAndGet()
                        else -> other.incrementAndGet()
                    }
                }
                if (pending.decrementAndGet() == 0) {
                    val reasons = listOf(
                        "file not found" to notFound.get(), "cannot open" to openFail.get(),
                        "unsupported WAV" to badWav.get(), "other" to other.get()
                    ).filter { it.second > 0 }.joinToString(", ") { "${it.first}: ${it.second}" }
                    onDone(Report(toLoad.size - failed.get(), failed.get(), shared, unresolved, reasons, firstError.get()))
                }
            }
        }
    }

    fun release(stop: Stop) {
        val dead = ArrayList<Int>()
        synchronized(lock) {
            for (t in collect(stop).first) {
                val n = (refs[t.key] ?: 0) - 1
                if (n <= 0) { refs.remove(t.key); dead += t.key } else refs[t.key] = n
            }
        }
        dead.forEach { AudioEngine.unloadSample(it) }
    }

    fun unloadAll() {
        val keys = synchronized(lock) { refs.keys.toList().also { refs.clear() } }
        keys.forEach { AudioEngine.unloadSample(it) }
        pool.shutdownNow()
    }

    /** key = pipe key, masks = tremulant mask ng bawat pipe (parehong haba). */
    class Hits(val keys: IntArray, val masks: IntArray)

    private fun collectNote(manual: Manual, note: Int, out: LinkedHashMap<Int, Int>) {
        val k = note - manual.firstMidiNote + 1          // logical key number (1-based)
        for (s in manual.stops) {
            if (!s.enabled || !s.ready) continue            // muted / off / still loading
            for (r in s.ranks) {
                if (k < r.firstAccessibleKey || k >= r.firstAccessibleKey + r.pipeCount) continue
                val t = target(r.rank, r.firstPipeNumber + (k - r.firstAccessibleKey)) ?: continue
                out[t.key] = (out[t.key] ?: 0) or r.rank.tremMask
            }
        }
    }

    private fun toHits(m: LinkedHashMap<Int, Int>) = Hits(m.keys.toIntArray(), m.values.toIntArray())

    /** Mga pipe na tutunog sa manual na ito para sa eksaktong note (walang transpose/coupler). */
    fun hitsForNote(manual: Manual, note: Int): Hits =
        toHits(LinkedHashMap<Int, Int>().also { collectNote(manual, note, it) })

    /** Pinindot na note: unison (kung hindi naka-off) + mga naka-engage na NORMAL coupler. Kasama ang transpose. */
    fun normalHits(src: Manual, note: Int): Hits {
        val t = settings.transpose
        val out = LinkedHashMap<Int, Int>()
        var unison = true
        for (c in src.couplers) {
            if (!c.engaged || c.kind != CouplerKind.NORMAL) continue
            if (c.unisonOff) { unison = false; continue }
            val dest = organ.manuals.firstOrNull { it.index == c.destManual } ?: continue
            collectNote(dest, note + c.keyShift + t, out)
        }
        if (unison) collectNote(src, note + t, out)
        return toHits(out)
    }

    private fun wanted(key: Int) = synchronized(lock) { (refs[key] ?: 0) > 0 }

    /** Hinahanap ang file: base dir, pagkatapos root ng folder, pagkatapos tinatanggal ang mga unang segment. */
    private fun findFile(path: String): Uri? {
        val segs = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        for (skip in 0..minOf(3, segs.size - 1)) {
            val rel = segs.drop(skip).joinToString("/")
            folder.resolve(baseDirId, rel)?.let { return it }
            folder.resolve(folder.rootId, rel)?.let { return it }
        }
        return null
    }

    /** 0 = ok, -1 = walang file, -2 = hindi mabuksan, iba = code mula sa native */
    private fun loadFile(t: Target, firstError: AtomicReference<String?>): Int {
        val path = t.pipe.path ?: return -1
        val uri = findFile(path)
        if (uri == null) {
            firstError.compareAndSet(null, "File not found: $path")
            return -1
        }
        val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
        if (pfd == null) {
            firstError.compareAndSet(null, "Cannot open: $path")
            return -2
        }
        val code = AudioEngine.loadSample(t.key, pfd.detachFd(), t.pipe.tuneCents, t.pipe.gainLinear)
        if (code != 0) firstError.compareAndSet(null, "${AudioEngine.lastError()}  [$path]")
        return code
    }

    companion object { private const val TAG = "OrganSamples" }
}
