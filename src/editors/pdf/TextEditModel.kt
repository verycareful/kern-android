package dev.kern.editors.pdf

import dev.kern.pdfbridge.PdfTextBlock

/** The rules of editing the text of a page: which block a tap means, why a block is refused, and the size of the field laid over it. */
internal object TextEditModel {

    /** How far, as a fraction of the page, a tap may miss a block and still mean it. */
    const val TAP_SLOP = 0.004f

    /** The share of a line's height the field's letters take. */
    private const val LETTER_FILL = 0.75f

    /** The smallest letters, in pixels, the field is set in: smaller text is not readable under a finger. */
    const val MIN_LETTER_PX = 14f

    /** What a tap on a page where there is no text says: new text cannot be put on a page yet. */
    const val NOTHING_THERE = "Kern changes text that is already on the page. Putting new text on a page is not available yet."

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

    /** The note to show for what an edit says the user should know. */
    fun noteFor(notes: List<String>): String? =
        if (notes.isEmpty()) null else notes.joinToString("\n") { it.replaceFirstChar(Char::uppercase) }
}
