package dev.kern.editors.pdf

/**
 * A rectangle on a page as fractions of the page's width and height, so it can be
 * drawn over the page at whatever size the page is shown.
 */
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A point on a page as fractions of the page's width and height. */
data class PagePoint(val x: Float, val y: Float)

/** One match of a search: the page it is on and the boxes covering its text (one per line it spans). */
data class PdfTextMatch(val page: Int, val rects: List<PageRect>)

/**
 * One line of a page's text and the box it is drawn in. [glyphs] are the boxes of its glyphs in
 * logical order (not left to right); markup is made from them.
 */
data class PdfTextLine(val text: String, val rect: PageRect, val glyphs: List<PageRect> = emptyList())

/** A link on a page: the boxes that take a tap, and where it goes. */
sealed interface PdfLink {
    val rects: List<PageRect>

    /** A link out of the document, such as a web address. */
    data class External(override val rects: List<PageRect>, val uri: String) : PdfLink

    /** A link to another page of the same document (0-based [page]). */
    data class GoTo(override val rects: List<PageRect>, val page: Int) : PdfLink
}

/** The link whose boxes contain [point], if any. */
fun List<PdfLink>.at(point: PagePoint): PdfLink? = firstOrNull { link ->
    link.rects.any { point.x in it.left..it.right && point.y in it.top..it.bottom }
}

/**
 * What Kern can do with a PDF's text, as opposed to its pictures of pages. Pages are 0-based.
 * Every call runs wherever the layer needs it to, so callers may call from any dispatcher.
 */
interface PdfTextLayer {
    /** Every match of [query] on [page], in reading order. The search is literal and ignores case. */
    suspend fun search(page: Int, query: String): List<PdfTextMatch>

    /** The text of [page], line by line, with where each line is drawn. */
    suspend fun lines(page: Int): List<PdfTextLine>

    /** Every link on [page] that goes somewhere. */
    suspend fun links(page: Int): List<PdfLink>
}
