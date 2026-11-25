package dev.kern.editors.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** PDFs made with the framework for the instrumented tests. */
object TestPdfs {

    const val WIDTH = 300
    const val HEIGHT = 400

    /** A PDF of [pages] portrait pages, each showing its own number. */
    fun make(file: File, pages: Int): File {
        val doc = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 24f }
        repeat(pages) { index ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, index + 1).create())
            page.canvas.drawText("page ${index + 1}", 20f, 40f, paint)
            doc.finishPage(page)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** A PDF with one page per entry of [widths], each that wide and [HEIGHT] tall, so a page is told by its width. */
    fun makeSized(file: File, widths: List<Int>): File {
        val doc = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 24f }
        widths.forEachIndexed { index, width ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(width, HEIGHT, index + 1).create())
            page.canvas.drawText("page ${index + 1}", 20f, 40f, paint)
            doc.finishPage(page)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** The width of every page of [file], in page order. */
    fun pageWidths(file: File): List<Int> = open(file) { r -> (0 until r.pageCount).map { i -> r.openPage(i).use { it.width } } }

    fun pageCount(file: File): Int = open(file) { it.pageCount }

    /** The width and height of page [index] (0-based) as the renderer reports them. */
    fun pageSize(file: File, index: Int): Pair<Int, Int> =
        open(file) { r -> r.openPage(index).use { it.width to it.height } }

    private fun <T> open(file: File, block: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use(block)
        }
}
