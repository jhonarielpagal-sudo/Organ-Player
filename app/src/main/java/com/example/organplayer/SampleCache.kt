package com.example.organplayer

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional private copy of the sample files in app storage. Reading them is plain file I/O instead of going
 * through the Storage Access Framework, so later loads are faster. Costs disk space (limit in Settings).
 */
class SampleCache(context: Context, private val settings: Settings) {
    private val root: File =
        (context.getExternalFilesDir("samplecache") ?: File(context.filesDir, "samplecache")).also { it.mkdirs() }
    private val used = AtomicLong(-1)

    val enabled: Boolean get() = settings.sampleCache

    init { Thread { scan() }.start() }

    private fun scan() {
        used.set(root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.sumOf { it.length() })
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun fileFor(tag: String, path: String): File =
        File(File(root, sha1(tag).substring(0, 12)), sha1(path.replace('\\', '/').lowercase()) + ".wav")

    fun usedBytes(): Long = used.get().coerceAtLeast(0)

    fun hasRoom(): Boolean = enabled && usedBytes() < settings.cacheCapMb * 1024L * 1024L

    /** Copies the stream into the cache. Returns the cached file, or null if it failed. */
    fun store(target: File, input: InputStream): File? {
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            input.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out, 256 * 1024) } }
            if (!tmp.renameTo(target)) { tmp.delete(); null }
            else { if (used.get() >= 0) used.addAndGet(target.length()); target }
        } catch (e: Exception) { null }
    }

    fun clear() {
        root.deleteRecursively()
        root.mkdirs()
        used.set(0)
    }
}
