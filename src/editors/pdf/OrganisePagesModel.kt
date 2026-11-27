package dev.kern.editors.pdf

import dev.kern.pdfbridge.PdfOp

/** The page-order and selection rules of the Organise pages sheet. Pages are 0-based. */
internal object PageOrder {

    const val ROTATE_RIGHT = 90
    const val ROTATE_LEFT = 270

    /**
     * The order [PdfOp.Reorder] takes to move page [from] so that it ends up at position [to]:
     * entry `i` is the current number of the page that becomes page `i`.
     */
    fun move(pageCount: Int, from: Int, to: Int): List<Int> {
        require(from in 0 until pageCount) { "Page $from is not one of $pageCount pages." }
        require(to in 0 until pageCount) { "Position $to is not one of $pageCount pages." }
        val order = (0 until pageCount).toMutableList()
        order.add(to, order.removeAt(from))
        return order
    }

    /** Whether [pages] can be deleted: at least one, all of them in the document, and not every page. */
    fun canRemove(pageCount: Int, pages: Set<Int>): Boolean =
        pages.isNotEmpty() && pages.all { it in 0 until pageCount } && pages.size < pageCount

    /** The edit that turns [pages] by [degrees], in page order. */
    fun rotate(pages: Set<Int>, degrees: Int): PdfOp = PdfOp.Rotate(pages.sorted(), degrees)

    /** The edit that deletes [pages], in page order. */
    fun remove(pages: Set<Int>): PdfOp = PdfOp.Remove(pages.sorted())

    /** [selected] with [page] added when it is not in it, and taken out when it is. */
    fun toggle(selected: Set<Int>, page: Int): Set<Int> = if (page in selected) selected - page else selected + page
}
