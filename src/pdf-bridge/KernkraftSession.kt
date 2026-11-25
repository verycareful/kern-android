// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Sricharan Suresh (github.com/verycareful)

package dev.kern.pdfbridge

import android.graphics.Bitmap
import dev.kern.pdfbridge.KernkraftPdf.Native
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
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

/** A box on a page as fractions of the displayed page (CropBox, rotation applied), y down. */
data class PdfBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * One line of a page's text and the box it is drawn in. [glyphs] are the boxes of its glyphs in
 * LOGICAL order (the order the characters are read in, not left to right); a glyph of text of no
 * size has a degenerate box.
 */
data class PdfTextLineBox(val text: String, val box: PdfBox, val glyphs: List<PdfBox> = emptyList())

/** One match of a search: the boxes covering it, one per line it spans. */
data class PdfFoundText(val boxes: List<PdfBox>)

/** A link on a page: the boxes that take a tap, and where it goes. */
sealed interface PdfEngineLink {
    val boxes: List<PdfBox>

    /** A link out of the document. */
    data class Uri(override val boxes: List<PdfBox>, val uri: String) : PdfEngineLink

    /** A link to a page of the same document (0-based [page]). */
    data class Page(override val boxes: List<PdfBox>, val page: Int) : PdfEngineLink
}

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

    /** Marks [quads] (boxes of the selected glyphs, as fractions of the page) of [page] in [colour]. */
    data class AddMarkup(
        val kind: MarkupKind,
        val page: Int,
        val quads: List<PdfBox>,
        val colour: PdfColour,
        val modified: String,
    ) : PdfOp {
        override fun toJson(): JSONObject = JSONObject()
            .put("op", "addMarkup")
            .put("kind", kind.wire)
            .put("page", page + 1)
            .put("quads", JSONArray(quads.map { box(it) }))
            .put("colour", colour.toJson())
            .put("modified", modified)
    }

    /**
     * Draws freehand [strokes] (points as fractions of the page) on [page], [widthPoints] wide, in [colour].
     * One annotation holds every stroke.
     */
    data class AddInk(
        val page: Int,
        val strokes: List<List<PdfPoint>>,
        val widthPoints: Double,
        val colour: PdfColour,
        val modified: String,
    ) : PdfOp {
        override fun toJson(): JSONObject = JSONObject()
            .put("op", "addInk")
            .put("page", page + 1)
            .put("strokes", JSONArray(strokes.map { stroke -> JSONArray(stroke.map { JSONArray(listOf(it.x, it.y)) }) }))
            .put("width", widthPoints)
            .put("colour", colour.toJson())
            .put("modified", modified)
    }

    companion object {
        internal fun oneBased(pages: List<Int>): JSONArray = JSONArray(pages.map { it + 1 })

        private fun box(b: PdfBox) = JSONArray(listOf(b.left, b.top, b.right, b.bottom))
    }
}

/** The kinds of text markup the engine writes. [wire] is the name it reads. */
enum class MarkupKind(val wire: String) {
    Highlight("highlight"),
    Underline("underline"),
    StrikeOut("strikeOut"),
}

/** A point on a page as fractions of the displayed page, y down. */
data class PdfPoint(val x: Double, val y: Double)

/** A colour as the engine reads it: red, green and blue from 0 to 1. */
data class PdfColour(val red: Double, val green: Double, val blue: Double) {
    internal fun toJson(): JSONArray = JSONArray(listOf(red, green, blue))
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

    /** The lines of [page] (0-based), in reading order, with where each is drawn. Blank lines are left out. */
    fun lines(page: Int): List<PdfTextLineBox> =
        parseLines(KernkraftPdf.callNative(Native.Text, pageRequest(page)))

    /** Every match of [query] on [page] (0-based): literal, ignoring case. */
    fun search(page: Int, query: String): List<PdfFoundText> =
        parseMatches(KernkraftPdf.callNative(Native.Search, pageRequest(page).put("query", query)))

    /**
     * The links of [page] (0-based) that go somewhere. A link whose destination the engine cannot
     * resolve to an address or a page has nowhere to take a tap and is left out.
     */
    fun links(page: Int): List<PdfEngineLink> =
        parseLinks(KernkraftPdf.callNative(Native.Links, pageRequest(page)))

    /** The buffer pictures are drawn into, kept between calls because they come one at a time and a picture is megabytes. */
    private var pixels: ByteBuffer? = null

    /**
     * Draws [page] (0-based) [widthPx] wide the way the engine draws it, annotations included. The
     * height follows the page.
     */
    fun render(page: Int, widthPx: Int): Bitmap {
        val request = pageRequest(page).put("width_px", widthPx)
        val size = KernkraftPdf.callNative(Native.RenderSize, request)
        val width = size.getInt("width")
        val height = size.getInt("height")
        val bytes = size.getLong("bytes").toInt()
        val buffer = pixels?.takeIf { it.capacity() >= bytes } ?: ByteBuffer.allocateDirect(bytes).also { pixels = it }
        KernkraftPdf.callRender(request, buffer)
        buffer.limit(bytes).position(0)
        // Straight RGBA8 with every alpha 255 is what ARGB_8888 holds in memory, so the rows go in as they are.
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(buffer) }
    }

    override fun close() {
        if (closed) return
        closed = true
        pixels = null
        runCatching { KernkraftPdf.callNative(Native.Close, handleRequest()) }
    }

    private fun handleRequest() = JSONObject().put("handle", handle)

    private fun pageRequest(page: Int) = handleRequest().put("page", page + 1)

    private fun step(native: Native, request: JSONObject): PdfStep = parseStep(KernkraftPdf.callNative(native, request))

    /** [length] is the length the file has after the bytes are appended; [appended] is how many bytes there are. */
    data class Appended(val length: Long, val appended: Long)

    companion object {
        /** Opens the document at [path] for editing. */
        fun open(path: File): KernkraftSession {
            val answer = KernkraftPdf.callNative(Native.Open, JSONObject().put("path", path.path))
            return KernkraftSession(answer.getLong("handle"), answer.getInt("pages"))
        }

        internal fun parseLines(answer: JSONObject): List<PdfTextLineBox> {
            val lines = answer.getJSONArray("lines")
            return (0 until lines.length()).map { lines.getJSONObject(it) }
                .filter { it.getString("text").isNotBlank() }
                .map {
                    val glyphs = it.optJSONArray("glyphs")
                    PdfTextLineBox(
                        it.getString("text"),
                        box(it.getJSONArray("rect")),
                        if (glyphs == null) emptyList() else boxes(glyphs),
                    )
                }
        }

        internal fun parseMatches(answer: JSONObject): List<PdfFoundText> {
            val matches = answer.getJSONArray("matches")
            return List(matches.length()) { PdfFoundText(boxes(matches.getJSONObject(it).getJSONArray("rects"))) }
        }

        internal fun parseLinks(answer: JSONObject): List<PdfEngineLink> {
            val links = answer.getJSONArray("links")
            return (0 until links.length()).mapNotNull { i ->
                val link = links.getJSONObject(i)
                val boxes = boxes(link.getJSONArray("rects"))
                when {
                    link.has("uri") -> PdfEngineLink.Uri(boxes, link.getString("uri"))
                    link.has("page") -> PdfEngineLink.Page(boxes, link.getInt("page") - 1)
                    else -> null
                }
            }
        }

        private fun boxes(rects: JSONArray) = List(rects.length()) { box(rects.getJSONArray(it)) }

        private fun box(rect: JSONArray) = PdfBox(
            rect.getDouble(0).toFloat(), rect.getDouble(1).toFloat(), rect.getDouble(2).toFloat(), rect.getDouble(3).toFloat(),
        )

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
