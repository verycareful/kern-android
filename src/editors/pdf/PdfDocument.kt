package dev.kern.editors.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Read-only PDF page source backed by the framework [PdfRenderer].
 *
 * The kernkraft engine draws the pages, reads the text and makes the edits. This
 * class gives the page count at open and the page widths, and draws the pages
 * only when the engine cannot open the file.
 *
 * [PdfRenderer] is not thread-safe and only one page may be open at a time, so
 * every render is serialized on [lock]. Always [close] to release the fd.
 */
class PdfDocument private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    private val lock = Any()

    val pageCount: Int get() = renderer.pageCount

    /**
     * Renders [index] into an ARGB bitmap [targetWidthPx] wide, height following the
     * page aspect ratio. The page is painted onto a white background first, since
     * PDF pages are transparent where they have no content.
     */
    fun renderPage(index: Int, targetWidthPx: Int): Bitmap = synchronized(lock) {
        renderer.openPage(index).use { page ->
            val width = targetWidthPx.coerceAtLeast(1)
            val height = (width * page.height.toFloat() / page.width).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    /** The width of [index] as displayed, in PDF points (1/72 inch). */
    fun pageWidthPoints(index: Int): Int = synchronized(lock) { renderer.openPage(index).use { it.width } }

    override fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    companion object {
        /** Opens the PDF in [file] for reading. Throws [IOException] if it can't be opened. */
        fun open(file: File): PdfDocument {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfDocument(pfd, PdfRenderer(pfd))
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        }

        /** Opens the PDF at [uri] for reading. Throws [IOException] if it can't be opened. */
        fun open(context: Context, uri: Uri): PdfDocument {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Could not open $uri")
            return try {
                PdfDocument(pfd, PdfRenderer(pfd))
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        }
    }
}
