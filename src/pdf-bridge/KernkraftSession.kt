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
    /** The annotation the step added, when it added one. */
    val annotation: PdfAnnotationId? = null,
    /** What the engine says the user should know about the step, such as a substitute font it set text in. */
    val notes: List<String> = emptyList(),
)

/** The name the engine gives an annotation: its object and generation. Operations name an annotation by it. */
data class PdfAnnotationId(val objectNumber: Long, val generation: Int) {
    internal fun toJson(): JSONObject = JSONObject().put("object", objectNumber).put("generation", generation)
}

/** What an annotation is. [Other] is any other PDF subtype, by [Other.subtype]. */
sealed interface PdfAnnotationKind {
    data object Highlight : PdfAnnotationKind
    data object Underline : PdfAnnotationKind
    data object StrikeOut : PdfAnnotationKind
    data object Ink : PdfAnnotationKind

    /** A note, shown as an icon. */
    data object Note : PdfAnnotationKind
    data class Other(val subtype: String) : PdfAnnotationKind
}

/**
 * One annotation of a page. Geometry is as fractions of the displayed page, y down. An annotation
 * with no [id] was written directly in the page's `Annots` and no operation can name it; one with
 * [drawnByLibrary] false keeps the appearance it came with, so its colour and place cannot be changed.
 */
data class PdfAnnotation(
    val id: PdfAnnotationId?,
    val kind: PdfAnnotationKind,
    val drawnByLibrary: Boolean,
    val rect: PdfBox,
    val quads: List<PdfBox>,
    val strokes: List<List<PdfPoint>>,
    val contents: String?,
    val colour: PdfColour?,
    /** The `F` flags: bit 1 of the PDF is value 1, bit 2 is value 2, and so on. */
    val flags: Long,
)

/** A box on a page as fractions of the displayed page (CropBox, rotation applied), y down. */
data class PdfBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * One line of a page's text and the box it is drawn in. [glyphs] are the boxes of its glyphs in
 * LOGICAL order (the order the characters are read in, not left to right); a glyph of text of no
 * size has a degenerate box.
 */
data class PdfTextLineBox(val text: String, val box: PdfBox, val glyphs: List<PdfBox> = emptyList())

/**
 * A block of a page's text: the unit a text edit replaces. [id] is good only at the [PdfPageBlocks.revision]
 * it was read at. [reason] is the engine's name for what stops an edit, and is set when [editable] is false.
 * [text] is the block's text as it is typed: a line the text wrapped at runs on into the next with a space,
 * and a line its writer ended is followed by a line feed. [lineCount] is how many lines the page shows.
 */
data class PdfTextBlock(
    val id: Int,
    val box: PdfBox,
    val editable: Boolean,
    val reason: String?,
    val text: String,
    val lineCount: Int,
)

/** The blocks of one page, as the document stood at [revision]. */
data class PdfPageBlocks(val revision: Long, val blocks: List<PdfTextBlock>)

/**
 * A font a page's text is set in, with the [size] (as the reader sees it, in points) and [colour] its [blocks] use;
 * [chars] is how many characters they hold. [nearest] marks the entry whose block new text at the point asked
 * about would follow. [colour] is null for text painted with a pattern.
 */
data class PdfPageFont(
    val font: String,
    val embedded: Boolean,
    val size: Double,
    val colour: PdfColour?,
    val blocks: List<Int>,
    val chars: Int,
    val nearest: Boolean,
)

/** The fonts of one page, most used first, as the document stood at [revision]; [nearest] is the block new text would follow. */
data class PdfPageFonts(val revision: Long, val fonts: List<PdfPageFont>, val nearest: Int?)

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

    /** Adds a note on [page] whose icon's top left corner is at [at] (fractions of the page), reading [text]. */
    data class AddNote(
        val page: Int,
        val at: PdfPoint,
        val text: String,
        val colour: PdfColour,
        val modified: String,
    ) : PdfOp {
        override fun toJson(): JSONObject = JSONObject()
            .put("op", "addNote")
            .put("page", page + 1)
            .put("at", JSONArray(listOf(at.x, at.y)))
            .put("text", text)
            .put("colour", colour.toJson())
            .put("modified", modified)
    }

    /**
     * Changes the annotation [id] of [page]: each field that is set replaces what it holds. [translate]
     * moves it by that many fractions of the page. A kind the engine does not draw refuses a colour or a move.
     */
    data class EditAnnotation(
        val page: Int,
        val id: PdfAnnotationId,
        val modified: String,
        val colour: PdfColour? = null,
        val contents: String? = null,
        val translate: PdfPoint? = null,
    ) : PdfOp {
        override fun toJson(): JSONObject = JSONObject()
            .put("op", "editAnnotation")
            .put("page", page + 1)
            .put("id", id.toJson())
            .put("modified", modified)
            .apply {
                colour?.let { put("colour", it.toJson()) }
                contents?.let { put("contents", it) }
                translate?.let { put("translate", JSONArray(listOf(it.x, it.y))) }
            }
    }

    /** Takes the annotation [id] off [page], with the replies and popups that belong to it. */
    data class DeleteAnnotation(val page: Int, val id: PdfAnnotationId) : PdfOp {
        override fun toJson(): JSONObject =
            JSONObject().put("op", "deleteAnnotation").put("page", page + 1).put("id", id.toJson())
    }

    /**
     * Sets [text] as the text of block [block] of [page], which was read at [revision]; the engine refuses
     * the edit when the document has moved on since. Empty text takes the block's text out. A field that is
     * not set keeps what the block had.
     */
    data class EditText(
        val page: Int,
        val block: Int,
        val revision: Long,
        val text: String,
        val family: String? = null,
        val size: Double? = null,
        val colour: PdfColour? = null,
    ) : PdfOp {
        override fun toJson(): JSONObject = JSONObject()
            .put("op", "editText")
            .put("page", page + 1)
            .put("block", block)
            .put("revision", revision)
            .put("text", text)
            .apply {
                family?.let { put("family", it) }
                size?.let { put("size", it) }
                colour?.let { put("colour", it.toJson()) }
            }
    }

    /**
     * Puts [text] on [page] in a box whose top left corner is [at] and whose width is [width], as fractions of
     * the page; the lines grow downward and wrap at the width. The font, size and colour are those of the
     * nearest text block, or of block [likeBlock] read at [likeRevision]; each style field that is set replaces
     * its part. A page with no text to follow needs the [size].
     */
    data class AddText(
        val page: Int,
        val at: PdfPoint,
        val width: Double,
        val text: String,
        val likeBlock: Int? = null,
        val likeRevision: Long? = null,
        val family: String? = null,
        val size: Double? = null,
        val colour: PdfColour? = null,
    ) : PdfOp {
        init {
            require((likeBlock == null) == (likeRevision == null)) { "a block to follow needs the revision it was read at" }
        }

        override fun toJson(): JSONObject = JSONObject()
            .put("op", "addText")
            .put("page", page + 1)
            .put("at", JSONArray(listOf(at.x, at.y)))
            .put("width", width)
            .put("text", text)
            .apply {
                if (likeBlock != null) put("like", JSONObject().put("block", likeBlock).put("revision", likeRevision))
                family?.let { put("family", it) }
                size?.let { put("size", it) }
                colour?.let { put("colour", it.toJson()) }
            }
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

    /** The text blocks of [page] (0-based) and the revision of the document they were read at. */
    fun textBlocks(page: Int): PdfPageBlocks =
        parseBlocks(KernkraftPdf.callNative(Native.Text, pageRequest(page)))

    /**
     * The fonts the text of [page] (0-based) is set in. With [near], the entry whose block text added at that
     * point would follow is marked.
     */
    fun pageFonts(page: Int, near: PdfPoint? = null): PdfPageFonts {
        val request = pageRequest(page)
        near?.let { request.put("near", JSONArray(listOf(it.x, it.y))) }
        return parsePageFonts(KernkraftPdf.callNative(Native.PageFonts, request))
    }

    /** Every match of [query] on [page] (0-based): literal, ignoring case. */
    fun search(page: Int, query: String): List<PdfFoundText> =
        parseMatches(KernkraftPdf.callNative(Native.Search, pageRequest(page).put("query", query)))

    /**
     * The links of [page] (0-based) that go somewhere. A link whose destination the engine cannot
     * resolve to an address or a page has nowhere to take a tap and is left out.
     */
    fun links(page: Int): List<PdfEngineLink> =
        parseLinks(KernkraftPdf.callNative(Native.Links, pageRequest(page)))

    /** The annotations of [page] (0-based), in the order the page draws them: the last is on top. */
    fun annotations(page: Int): List<PdfAnnotation> =
        parseAnnotations(KernkraftPdf.callNative(Native.Annotations, pageRequest(page)))

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

        internal fun parseBlocks(answer: JSONObject): PdfPageBlocks {
            val lines = answer.getJSONArray("lines")
            val linesOf = HashMap<Int, MutableList<BlockLine>>()
            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                linesOf.getOrPut(line.getInt("block")) { mutableListOf() }
                    .add(BlockLine(line.getString("text"), line.getBoolean("wrapped")))
            }
            val blocks = answer.getJSONArray("blocks")
            return PdfPageBlocks(
                revision = answer.getLong("revision"),
                blocks = List(blocks.length()) { i ->
                    val b = blocks.getJSONObject(i)
                    val id = b.getInt("id")
                    PdfTextBlock(
                        id = id,
                        box = box(b.getJSONArray("rect")),
                        editable = b.getBoolean("editable"),
                        reason = if (b.isNull("reason")) null else b.optString("reason"),
                        text = typedText(linesOf[id].orEmpty()),
                        lineCount = linesOf[id]?.size ?: 0,
                    )
                },
            )
        }

        /** One line of a block as `text` gives it: its text, and whether it ended where the text wrapped. */
        private data class BlockLine(val text: String, val wrapped: Boolean)

        /**
         * The text of a block's [lines] as the user would type it. A wrapped line runs on into the next with one
         * space, so an edit reflows the paragraph in the block's width; a line its writer ended keeps its line feed,
         * which the engine sets as a line break.
         */
        private fun typedText(lines: List<BlockLine>): String = buildString {
            lines.forEachIndexed { i, line ->
                if (i == lines.lastIndex) append(line.text)
                else if (line.wrapped) append(line.text.trimEnd()).append(' ')
                else append(line.text).append('\n')
            }
        }

        internal fun parsePageFonts(answer: JSONObject): PdfPageFonts {
            val fonts = answer.getJSONArray("fonts")
            return PdfPageFonts(
                revision = answer.getLong("revision"),
                fonts = List(fonts.length()) { i ->
                    val f = fonts.getJSONObject(i)
                    val blocks = f.getJSONArray("blocks")
                    val colour = if (f.isNull("colour")) null else f.getJSONArray("colour")
                    PdfPageFont(
                        font = f.getString("font"),
                        embedded = f.getBoolean("embedded"),
                        size = f.getDouble("size"),
                        colour = colour?.let { PdfColour(it.getDouble(0), it.getDouble(1), it.getDouble(2)) },
                        blocks = List(blocks.length()) { blocks.getInt(it) },
                        chars = f.getInt("chars"),
                        nearest = f.getBoolean("nearest"),
                    )
                },
                nearest = if (answer.isNull("nearest")) null else answer.getInt("nearest"),
            )
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

        internal fun parseAnnotations(answer: JSONObject): List<PdfAnnotation> {
            val list = answer.getJSONArray("annotations")
            return List(list.length()) { i ->
                val a = list.getJSONObject(i)
                val id = a.optJSONObject("id")
                val strokes = a.optJSONArray("strokes")
                val colour = a.optJSONArray("colour")
                PdfAnnotation(
                    id = id?.let { PdfAnnotationId(it.getLong("object"), it.getInt("generation")) },
                    kind = annotationKind(a.getString("kind"), a.optString("subtype")),
                    drawnByLibrary = a.getBoolean("drawn_by_library"),
                    rect = box(a.getJSONArray("rect")),
                    quads = boxes(a.optJSONArray("quads") ?: JSONArray()),
                    strokes = List(strokes?.length() ?: 0) { s ->
                        val points = strokes!!.getJSONArray(s)
                        List(points.length()) { p -> points.getJSONArray(p).let { PdfPoint(it.getDouble(0), it.getDouble(1)) } }
                    },
                    contents = if (a.isNull("contents")) null else a.getString("contents"),
                    colour = colour?.let { PdfColour(it.getDouble(0), it.getDouble(1), it.getDouble(2)) },
                    flags = a.optLong("flags"),
                )
            }
        }

        private fun annotationKind(kind: String, subtype: String): PdfAnnotationKind = when (kind) {
            "Highlight" -> PdfAnnotationKind.Highlight
            "Underline" -> PdfAnnotationKind.Underline
            "StrikeOut" -> PdfAnnotationKind.StrikeOut
            "Ink" -> PdfAnnotationKind.Ink
            "Note" -> PdfAnnotationKind.Note
            else -> PdfAnnotationKind.Other(subtype.ifEmpty { kind })
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
                annotation = answer.optJSONObject("annotation")?.let { PdfAnnotationId(it.getLong("object"), it.getInt("generation")) },
                notes = answer.optJSONArray("notes")?.let { notes -> List(notes.length()) { notes.getString(it) } } ?: emptyList(),
            )
        }
    }
}
