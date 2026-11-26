package dev.kern.editors.pdf

import dev.kern.pdfbridge.PdfPageFont
import dev.kern.pdfbridge.PdfTextBlock

/** The rules of editing the text of a page: which block a tap means, why a block is refused, and the size of the field laid over it. */
internal object TextEditModel {

    /** What the bar says while no field is open. */
    const val TAP_HINT = "Tap text to change it, or an empty place to add text"

    /** How far, as a fraction of the page, a tap may miss a block and still mean it. */
    const val TAP_SLOP = 0.004f

    /** The share of a line's height the field's letters take. */
    private const val LETTER_FILL = 0.75f

    /** The smallest letters, in pixels, the field is set in: smaller text is not readable under a finger. */
    const val MIN_LETTER_PX = 14f

    /** The block [point] is in, and of those that hold it the smallest; null when it is in none. */
    fun blockAt(blocks: List<PdfTextBlock>, point: PagePoint): PdfTextBlock? =
        blocks.filter {
            point.x >= it.box.left - TAP_SLOP && point.x <= it.box.right + TAP_SLOP &&
                point.y >= it.box.top - TAP_SLOP && point.y <= it.box.bottom + TAP_SLOP
        }.minByOrNull { (it.box.right - it.box.left) * (it.box.bottom - it.box.top) }

    /** Why the engine refuses to change a block, as the user is told it; [reason] is the engine's name for it. */
    fun refusal(reason: String?): String = when (reason) {
        "NoGlyphs" -> "This text holds only spaces, so there is nothing to change."
        "NoSize" -> "This text has no size, so none of it is drawn and there is nothing to change."
        "NotOnPage" -> "This text is no longer on the page as it stands. Tap it again."
        "Type3Font" -> "This text is drawn with a font made of shapes, so Kern cannot type in it."
        "VerticalWriting" -> "This text runs top to bottom, which Kern cannot change."
        "TaggedFormContent" -> "This text is part of a tagged form, so Kern cannot change it."
        "ClippingRenderMode" -> "This text cuts the shape of what is drawn after it, so Kern cannot change it."
        "SplitAcrossStreams", "StraddlesStreams", "UnterminatedTextObject", "StateChangedBeforeEnd" ->
            "This text is built in a way that Kern cannot change without spoiling the page."
        else -> "Kern cannot change this text${reason?.let { " ($it)" }.orEmpty()}."
    }

    /** The size, in pixels, to set the letters of a field in, for a block [boxHeightPx] tall that holds [lines] lines. */
    fun letterPx(boxHeightPx: Float, lines: Int): Float =
        (boxHeightPx / lines.coerceAtLeast(1) * LETTER_FILL).coerceAtLeast(MIN_LETTER_PX)

    private val NO_GLYPH = Regex("has no glyph for (U\\+[0-9A-Fa-f]+)")

    /** What to tell the user when the engine says no font it has holds a letter of the new text; null for any other failure. */
    fun missingLetter(engineMessage: String?): String? =
        NO_GLYPH.find(engineMessage.orEmpty())?.let {
            "No font on this device has the letter ${it.groupValues[1]}, so Kern cannot put it in this text."
        }

    /** What to tell the user at the start of changing text when the engine has no system fonts to set text in. */
    fun fontsProblemMessage(reason: String): String =
        "Kern could not read the fonts of this device ($reason). Text can be changed only with letters its own font has."

    /** The narrowest box new text is given, as a fraction of the page width, unless the page edge is nearer. */
    const val MIN_NEW_WIDTH = 0.1f

    /** The margin, as a fraction of the page width, new text keeps from the right edge of a page with no text. */
    const val EMPTY_PAGE_MARGIN = 0.08f

    /** How near, as a fraction of the page width, a dragged width comes to a block's width before it takes that width. */
    const val WIDTH_SNAP = 0.012f

    /** The size new text on a page with no text is set in, in points, until the user picks another. */
    const val EMPTY_PAGE_SIZE = 12.0

    /** The sizes, in points, the user can set new text in. */
    val SIZES = listOf(8.0, 9.0, 10.0, 11.0, 12.0, 14.0, 16.0, 18.0, 20.0, 24.0, 28.0, 36.0)

    /**
     * How wide a box of new text starting at [x] is when the user has not set it: to the right edge of the page's
     * text, which is as far right as any block reaches. When [x] is at or past that edge, or the page has no text,
     * the box runs to a right margin as wide as the page's left margin.
     */
    fun columnWidth(blocks: List<PdfTextBlock>, x: Float): Float {
        val textRight = blocks.maxOfOrNull { it.box.right }
        val right = textRight?.takeIf { it - x >= MIN_NEW_WIDTH }
            ?: (1f - (blocks.minOfOrNull { it.box.left } ?: EMPTY_PAGE_MARGIN))
        return fit(right - x, x)
    }

    /**
     * The width a box at [x] dragged to [width] takes: the width of a block of the page when it is within
     * [WIDTH_SNAP] of one (the nearest), so new text can line up with the page's columns; otherwise [width].
     * It always stays on the page.
     */
    fun snapWidth(width: Float, x: Float, blocks: List<PdfTextBlock>): Float {
        val snapped = blocks.map { it.box.right - it.box.left }
            .filter { kotlin.math.abs(it - width) <= WIDTH_SNAP && x + it <= 1f }
            .minByOrNull { kotlin.math.abs(it - width) }
        return fit(snapped ?: width, x)
    }

    private fun fit(width: Float, x: Float): Float {
        val room = 1f - x
        return width.coerceAtLeast(minOf(MIN_NEW_WIDTH, room)).coerceAtMost(room)
    }

    /** A font's name as the user is shown it: a subset's six-letter tag (`ABCDEF+`) is left off. */
    fun fontName(font: String): String = SUBSET_TAG.replaceFirst(font, "")

    /** A size in points as the user is shown it: `11 pt`, or `10.5 pt` for a size that is not whole. */
    fun sizeLabel(size: Double): String {
        val tenths = kotlin.math.round(size * 10).toLong()
        return if (tenths % 10 == 0L) "${tenths / 10} pt" else "${tenths / 10}.${kotlin.math.abs(tenths % 10)} pt"
    }

    /** The label of the font chip: the font new text follows and its size, or the face of a page with no text. */
    fun fontLabel(font: PdfPageFont?): String = font?.let { fontName(it.font) } ?: EMPTY_PAGE_FACE

    /**
     * What the chip calls the face of new text on a page with no text. The engine takes the face of the device's
     * fonts closest to Helvetica, which differs between devices, so the chip does not name one.
     */
    const val EMPTY_PAGE_FACE = "Default font"

    private val SUBSET_TAG = Regex("^[A-Z]{6}\\+")

    /** The note to show for what an edit says the user should know. */
    fun noteFor(notes: List<String>): String? =
        if (notes.isEmpty()) null else notes.joinToString("\n") { it.replaceFirstChar(Char::uppercase) }
}
