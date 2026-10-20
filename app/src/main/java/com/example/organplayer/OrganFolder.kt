package com.example.organplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Pag-access sa organ folder gamit ang Storage Access Framework (SAF).
 * - Hinahanap ang .organ file(s)
 * - Nire-resolve ang mga path sa ODF (Windows-style "\" at case-insensitive)
 */
class OrganFolder(context: Context, private val treeUri: Uri) {

    class OrganRef(val name: String, val docId: String, val baseDirId: String, val dirName: String)
    private class Entry(val docId: String, val name: String, val isDir: Boolean)

    private val resolver = context.contentResolver
    private val listings = HashMap<String, Map<String, Entry>>()   // dirId -> (lowercase name -> Entry)

    val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private fun children(dirId: String): Map<String, Entry> = listings.getOrPut(dirId) {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        val out = LinkedHashMap<String, Entry>()
        resolver.query(
            uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out[name.lowercase()] = Entry(c.getString(0), name, c.getString(2) == Document.MIME_TYPE_DIR)
            }
        }
        out
    }

    /** Hinahanap ang *.organ hanggang 2 level ng subfolder. */
    @Synchronized fun findOrganFiles(): List<OrganRef> {
        val found = ArrayList<OrganRef>()
        fun scan(dirId: String, dirName: String, depth: Int) {
            val kids = children(dirId).values
            kids.filter { !it.isDir && it.name.endsWith(".organ", ignoreCase = true) }
                .sortedBy { it.name.lowercase() }
                .forEach { found += OrganRef(it.name, it.docId, dirId, dirName) }
            if (depth > 0) kids.filter { it.isDir }.forEach {
                scan(it.docId, if (dirName.isEmpty()) it.name else "$dirName/${it.name}", depth - 1)
            }
        }
        scan(rootId, "", 2)
        return found
    }

    fun readBytes(docId: String): ByteArray {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Hindi mabuksan ang file")
    }

    /** Hinahanap ang file (case-insensitive, "\" o "/"). Null kung wala. */
    @Synchronized fun resolve(baseDirId: String, relPath: String): Uri? {
        val stack = arrayListOf(baseDirId)
        for (seg in relPath.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.size > 1) stack.removeAt(stack.size - 1)
                else -> {
                    val hit = children(stack.last())[seg.lowercase()] ?: return null
                    stack += hit.docId
                }
            }
        }
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, stack.last())
    }

    companion object {
        /** Ang ODF ay madalas ISO-8859-1/Windows-1252; subukan muna ang UTF-8. */
        fun decode(bytes: ByteArray): String {
            val text = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                String(bytes, StandardCharsets.ISO_8859_1)
            }
            return text.removePrefix("\uFEFF")
        }
    }
}
