package com.example.organplayer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    private val settings: Settings,
    private val cache: SampleCache? = null,
    private val cacheTag: String = ""
) {
    class Report(
        val loaded: Int, val failed: Int, val shared: Int, val unresolved: Int,
        val reasons: String, val firstError: String?, val timing: String = ""
    )

    /** Where the loading time goes (summed over the worker threads). */
    private class Timing {
        val resolveNs = AtomicLong(); val openNs = AtomicLong(); val nativeNs = AtomicLong()
        val bytes = AtomicLong(); val hits = AtomicInteger(); val stored = AtomicInteger()
        fun summary(n: Int): String {
            if (n == 0) return ""
            fun ms(v: AtomicLong) = "%.1f".format(v.get() / 1e6 / n)
            return "per file avg: find ${ms(resolveNs)} ms, open/copy ${ms(openNs)} ms, decode ${ms(nativeNs)} ms; " +
                "${bytes.get() / 1_048_576} MB; cache hits ${hits.get()}/$n, newly cached ${stored.get()}"
        }
    }

    private class Target(val key: Int, val pipe: PipeDef)

    private val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
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
        val tm = Timing()

        for (t in toLoad) {
            pool.execute {
                var code = 0
                try {
                    if (wanted(t.key)) {
                        code = loadFile(t, firstError, tm)
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
                    onDone(Report(toLoad.size - failed.get(), failed.get(), shared, unresolved, reasons, firstError.get(), tm.summary(toLoad.size)))
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

    /** One sounding pipe with its per-stop adjustments. id = unique per (pipe sample, stop). */
    class PipeInfo(
        val key: Int, val mask: Int, val tag: Int, val group: Int,
        val gain: Float, val cents: Float, val relMs: Float
    ) {
        val id: Long = (key.toLong() shl 32) or (tag.toLong() and 0xffffffffL)
    }

    class Hits(val pipes: List<PipeInfo>)

    private fun collectNote(manual: Manual, note: Int, out: LinkedHashMap<Long, PipeInfo>) {
        val k = note - manual.firstMidiNote + 1          // logical key number (1-based)
        for (s in manual.stops) {
            if (!s.enabled || !s.ready) continue          // muted / off / still loading
            val gain = s.gainPct / 100f
            val cents = (s.tuneCents + (s.pipeCents[note] ?: 0)).toFloat()
            for (r in s.ranks) {
                if (k < r.firstAccessibleKey || k >= r.firstAccessibleKey + r.pipeCount) continue
                val t = target(r.rank, r.firstPipeNumber + (k - r.firstAccessibleKey)) ?: continue
                val p = PipeInfo(t.key, r.rank.tremMask, s.tag, minOf(15, manual.index), gain, cents, s.releaseMs.toFloat())
                if (!out.containsKey(p.id)) out[p.id] = p
            }
        }
    }

    /** Pipes for the exact note on this manual (no transpose / coupler). */
    fun hitsForNote(manual: Manual, note: Int): Hits =
        Hits(LinkedHashMap<Long, PipeInfo>().also { collectNote(manual, note, it) }.values.toList())

    /** Played note: unison (unless off) + engaged NORMAL couplers. Includes transpose. */
    fun normalHits(src: Manual, note: Int): Hits {
        val t = settings.transpose
        val out = LinkedHashMap<Long, PipeInfo>()
        var unison = true
        for (c in src.couplers) {
            if (!c.engaged || c.kind != CouplerKind.NORMAL) continue
            if (c.unisonOff) { unison = false; continue }
            val dest = organ.manuals.firstOrNull { it.index == c.destManual } ?: continue
            collectNote(dest, note + c.keyShift + t, out)
        }
        if (unison) collectNote(src, note + t, out)
        return Hits(out.values.toList())
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

    /** 0 = ok, -1 = file not found, -2 = cannot open, other = code from the native decoder */
    private fun loadFile(t: Target, firstError: AtomicReference<String?>, tm: Timing): Int {
        val path = t.pipe.path ?: return -1
        val cf: File? = if (cache != null && cache.enabled) cache.fileFor(cacheTag, path) else null

        // 1) already in the app cache: plain file I/O
        if (cf != null && cf.isFile && cf.length() > 0) {
            val t0 = System.nanoTime()
            val cached = try { ParcelFileDescriptor.open(cf, ParcelFileDescriptor.MODE_READ_ONLY) } catch (e: Exception) { null }
            tm.openNs.addAndGet(System.nanoTime() - t0)
            if (cached != null) {
                tm.hits.incrementAndGet(); tm.bytes.addAndGet(cf.length())
                return decode(t, cached, path, firstError, tm)
            }
        }

        // 2) from the organ folder (Storage Access Framework), copying into the cache if enabled
        val r0 = System.nanoTime()
        val uri = findFile(path)
        tm.resolveNs.addAndGet(System.nanoTime() - r0)
        if (uri == null) {
            firstError.compareAndSet(null, "File not found: $path")
            return -1
        }
        val o0 = System.nanoTime()
        var pfd: ParcelFileDescriptor? = null
        if (cache != null && cf != null && cache.hasRoom()) {
            val stored = try { context.contentResolver.openInputStream(uri)?.let { cache.store(cf, it) } } catch (e: Exception) { null }
            if (stored != null) {
                pfd = try { ParcelFileDescriptor.open(stored, ParcelFileDescriptor.MODE_READ_ONLY) } catch (e: Exception) { null }
                if (pfd != null) tm.stored.incrementAndGet()
            }
        }
        if (pfd == null) pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
        tm.openNs.addAndGet(System.nanoTime() - o0)
        if (pfd == null) {
            firstError.compareAndSet(null, "Cannot open: $path")
            return -2
        }
        tm.bytes.addAndGet(maxOf(0L, pfd.statSize))
        return decode(t, pfd, path, firstError, tm)
    }

    private fun decode(t: Target, pfd: ParcelFileDescriptor, path: String,
                       firstError: AtomicReference<String?>, tm: Timing): Int {
        val n0 = System.nanoTime()
        val code = AudioEngine.loadSample(t.key, pfd.detachFd(), t.pipe.tuneCents, t.pipe.gainLinear)
        tm.nativeNs.addAndGet(System.nanoTime() - n0)
        if (code != 0) firstError.compareAndSet(null, "${AudioEngine.lastError()}  [$path]")
        return code
    }

    companion object { private const val TAG = "OrganSamples" }
}
