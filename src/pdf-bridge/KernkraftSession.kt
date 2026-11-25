// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Sricharan Suresh (github.com/verycareful)

package dev.kern.pdfbridge

import dev.kern.pdfbridge.KernkraftPdf.Native
import java.io.Closeable
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** A failure the engine reported. [kind] is its error name (for example `RebuiltNotEditable`), when it gave one. */
class PdfEngineException(val kind: String?, message: String) : IOException(message) {

    /** The file cannot be edited in place; the only way out is a compacted copy (7.5.6, 7.5.8). */
    val needsCompactedCopy: Boolean get() = kind == "RebuiltNotEditable" || kind == "RevisionNotEditable"
}

/**
 * What one step of an open document answered with. Pages are 0-based here: this file is
 * the only place the engine's 1-based page numbers are converted.
 */
data class PdfStep(
    val revision: Long,
    val pageCount: Int,
    val changedPages: List<Int>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val modified: Boolean,
)

/** A change to the document. Pages are 0-based; [toJson] writes the engine's 1-based request. */
sealed interface PdfOp {
    fun toJson(): JSONObject

    /** Turns [pages] clockwise by [degrees] (90, 180 or 270); no pages is every page. */
    data class Rotate(val pages: List<Int>, val degrees: Int) : PdfOp {
        override fun toJson(): JSONObject =
            JSONObject().put("op", "rotate").put("pages", oneBased(pages)).put("degrees", degrees)
    }

    /** The new page order: [order] lists every page once, by its current number. */
    data class Reorder(val order: List<Int>) : PdfOp {
        override fun toJson(): JSONObject = JSONObject().put("op", "reorder").put("order", oneBased(order))
    }

    data class Remove(val pages: List<Int>) : PdfOp {
        override fun toJson(): JSONObject = JSONObject().put("op", "remove").put("pages", oneBased(pages))
    }

    companion object {
        internal fun oneBased(pages: List<Int>): JSONArray = JSONArray(pages.map { it + 1 })
    }
}

/**
 * An open document of the engine: the edits are kept in the engine and written out only by
 * [appendSince] or [compact]. The file opened is read by the engine for as long as the session
 * lives, so nothing may write to it.
 *
 * A session is not tied to a thread, but the callers keep one call at a time, in order.
 */
class KernkraftSession private constructor(
    private val handle: Long,
    val pageCount: Int,
) : Closeable {

    private var closed = false

    fun apply(op: PdfOp): PdfStep = step(Native.Apply, JSONObject().put("handle", handle).put("op", op.toJson()))

    fun undo(): PdfStep = step(Native.Undo, handleRequest())

    fun redo(): PdfStep = step(Native.Redo, handleRequest())

    /**
     * Writes the bytes that take a file that is [baseLength] bytes long (the length the document was
     * opened at, or the [Appended.length] of an append that [confirmAppended] was told about) to
     * the document as it stands into the new file [out]. [modified] is the PDF date of the save.
     * Nothing is written when there is nothing to append.
     */
    fun appendSince(baseLength: Long, out: File, modified: String): Appended {
        val answer = KernkraftPdf.callNative(
            Native.AppendSince,
            handleRequest().put("base_length", baseLength).put("out", out.path).put("modified", modified),
        )
        return Appended(length = answer.getLong("length"), appended = answer.getLong("appended"))
    }

    /** Tells the engine that the bytes of the append that answered [length] are written to the file. */
    fun confirmAppended(length: Long): Boolean =
        KernkraftPdf.callNative(Native.ConfirmAppended, handleRequest().put("length", length)).getBoolean("modified")

    /** Writes the document as it stands as the new, compacted file [out]. The opened file is untouched. */
    fun compact(out: File) {
        KernkraftPdf.callNative(Native.Compact, handleRequest().put("out", out.path))
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { KernkraftPdf.callNative(Native.Close, handleRequest()) }
    }

    private fun handleRequest() = JSONObject().put("handle", handle)

    private fun step(native: Native, request: JSONObject): PdfStep = parseStep(KernkraftPdf.callNative(native, request))

    /** [length] is the length the file has after the bytes are appended; [appended] is how many bytes there are. */
    data class Appended(val length: Long, val appended: Long)

    companion object {
        /** Opens the document at [path] for editing. */
        fun open(path: File): KernkraftSession {
            val answer = KernkraftPdf.callNative(Native.Open, JSONObject().put("path", path.path))
            return KernkraftSession(answer.getLong("handle"), answer.getInt("pages"))
        }

        internal fun parseStep(answer: JSONObject): PdfStep {
            val changed = answer.optJSONArray("changed_pages")
            return PdfStep(
                revision = answer.getLong("revision"),
                pageCount = answer.getInt("pages"),
                changedPages = List(changed?.length() ?: 0) { changed!!.getInt(it) - 1 },
                canUndo = answer.getBoolean("can_undo"),
                canRedo = answer.getBoolean("can_redo"),
                modified = answer.getBoolean("modified"),
            )
        }
    }
}
