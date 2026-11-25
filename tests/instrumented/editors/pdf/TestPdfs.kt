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

    /**
     * A one page PDF, 300 x 400 points, that carries annotations Kern did not make, at these places
     * (fractions of the page, from the top left): a web link at x .07 to .40, y .68 to .75; a form field
     * at x .07 to .40, y .43 to .50; a stamp at x .50 to .83, y .43 to .50; and a locked note reading
     * `Locked` at x .50 to .58, y .19 to .25.
     */
    fun withForeignAnnotations(file: File): File {
        val bodies = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $WIDTH $HEIGHT] /Annots [4 0 R 5 0 R 6 0 R 7 0 R] >>",
            "<< /Type /Annot /Subtype /Link /Rect [20 100 120 130] /Border [0 0 0] /A << /S /URI /URI (https://example.org) >> >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /Rect [20 200 120 230] /F 4 >>",
            "<< /Type /Annot /Subtype /Stamp /Rect [150 200 250 230] /F 4 >>",
            "<< /Type /Annot /Subtype /Text /Name /Note /Rect [150 300 175 325] /Contents (Locked) /C [1 1 0] /F 132 >>",
        )
        val out = java.io.ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
        write("%PDF-1.4\n")
        val offsets = bodies.mapIndexed { index, body -> out.size().also { write("${index + 1} 0 obj\n$body\nendobj\n") } }
        val xref = out.size()
        write("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(it)) }
        write("trailer\n<< /Size ${bodies.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
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
