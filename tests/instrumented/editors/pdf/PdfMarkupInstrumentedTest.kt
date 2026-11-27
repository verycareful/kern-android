package dev.kern.editors.pdf

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.pdfbridge.KernkraftSession
import dev.kern.pdfbridge.MarkupKind
import dev.kern.pdfbridge.PdfBox
import dev.kern.pdfbridge.PdfColour
import dev.kern.pdfbridge.PdfOp
import dev.kern.pdfbridge.PdfPoint
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Markup and ink on the kernkraft engine, on a device: the glyph boxes of the text layer become the
 * quads of a highlight, a stroke becomes an ink annotation, and each is seen in the rendered page,
 * gone after undo, back after redo, and still there in the saved file.
 *
 * The page is 300 x 400 pt and shows "Hello Kern world" in 24 pt Helvetica, its baseline 50 pt below
 * the top. It is rendered 300 px wide, so a page point is a pixel.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PdfMarkupInstrumentedTest {

    private lateinit var dir: File
    private lateinit var pdf: File
    private lateinit var session: PdfEditSession
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val nothing = {}
    private val date = "D:20261125120000+05'30'"

    @Before
    fun openSession() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cache, "markup-test").apply { deleteRecursively(); mkdirs() }
        pdf = File(dir, "text.pdf").apply { writeBytes(textPdf()) }
        // The session gets a folder of its own: a session deletes every entry beside it that no open session holds.
        session = PdfEditSession.create(File(dir, "sessions/session"), FileDocumentTarget(pdf)) { date }
    }

    @After
    fun cleanUp() {
        session.close()
        dir.deleteRecursively()
    }

    private val yellow = PdfColour(1.0, 1.0, 0.0)

    /** The boxes that mark the first five glyphs, "Hello", made the way the screen makes them. */
    private fun helloQuads(): List<PdfBox> = runBlocking {
        val glyphs = PageGlyphs.of(session.textLayer(dispatcher).lines(0))
        assertTrue("the page has glyphs", glyphs.boxes.size >= HELLO_LENGTH)
        glyphs.quads(0, HELLO_LENGTH - 1).map { PdfBox(it.left, it.top, it.right, it.bottom) }
    }

    private fun highlightHello() = PdfOp.AddMarkup(MarkupKind.Highlight, 0, helloQuads(), yellow, date)

    private fun inkAcrossTheMiddle() = PdfOp.AddInk(
        page = 0,
        strokes = listOf(listOf(PdfPoint(0.1, INK_Y), PdfPoint(0.5, INK_Y), PdfPoint(0.9, INK_Y))),
        widthPoints = INK_WIDTH,
        colour = PdfColour(1.0, 0.0, 0.0),
        modified = date,
    )

    /** The page as the engine draws it for the screen: the open session's document, edits and annotations included. */
    private fun rendered(): Bitmap = session.render(0, PAGE_WIDTH)

    /** The page of the file at [file] as the engine draws it, as when the saved file is opened again. */
    private fun renderedFromDisk(file: File): Bitmap =
        KernkraftSession.open(file).use { it.render(0, PAGE_WIDTH) }

    /** How many pixels inside [box] (fractions of the page) are yellow: red and green high, blue low. */
    private fun yellowPixels(bitmap: Bitmap, box: PdfBox): Int {
        var count = 0
        for (x in (box.left * bitmap.width).toInt() until (box.right * bitmap.width).toInt()) {
            for (y in (box.top * bitmap.height).toInt() until (box.bottom * bitmap.height).toInt()) {
                val p = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(p) > 200 && android.graphics.Color.green(p) > 200 && android.graphics.Color.blue(p) < 140) count++
            }
        }
        return count
    }

    /** How many pixels on the row of the ink are red. */
    private fun redPixels(bitmap: Bitmap): Int {
        val y = (INK_Y * bitmap.height).toInt()
        return (0 until bitmap.width).count { x ->
            val p = bitmap.getPixel(x, y)
            android.graphics.Color.red(p) > 200 && android.graphics.Color.green(p) < 90 && android.graphics.Color.blue(p) < 90
        }
    }

    private fun area(box: PdfBox, bitmap: Bitmap) =
        ((box.right - box.left) * bitmap.width).toInt() * ((box.bottom - box.top) * bitmap.height).toInt()

    @Test
    fun a_page_is_drawn_as_wide_as_asked_with_its_own_height() {
        val picture = session.render(0, 600)
        assertEquals(600, picture.width)
        assertEquals(800, picture.height)
        val second = session.render(0, PAGE_WIDTH)
        assertEquals(PAGE_WIDTH, second.width)
        assertEquals(400, second.height)
    }

    @Test
    fun a_page_without_annotations_has_no_colour_where_they_would_be() {
        assertEquals(0, yellowPixels(rendered(), helloQuads().single()))
        assertEquals(0, redPixels(rendered()))
    }

    @Test
    fun a_highlight_over_a_word_shows_on_the_page_and_undo_takes_it_off() {
        val quads = helloQuads()
        assertEquals("one line, one run", 1, quads.size)
        assertEquals(0, yellowPixels(rendered(), quads.single()))

        session.apply(highlightHello(), nothing)
        val marked = rendered()
        assertTrue("yellow over most of the word", yellowPixels(marked, quads.single()) > area(quads.single(), marked) / 3)

        session.undo(nothing)
        assertEquals(0, yellowPixels(rendered(), quads.single()))

        session.redo(nothing)
        assertTrue(yellowPixels(rendered(), quads.single()) > 0)
    }

    @Test
    fun a_highlight_stays_off_the_text_it_does_not_cover() {
        val quads = helloQuads().single()
        session.apply(highlightHello(), nothing)
        val marked = rendered()
        val beyond = PdfBox(quads.right + 0.25f, quads.top, quads.right + 0.35f, quads.bottom)
        assertEquals(0, yellowPixels(marked, beyond))
    }

    @Test
    fun an_ink_stroke_shows_on_the_page_and_undo_takes_it_off() {
        assertEquals(0, redPixels(rendered()))

        session.apply(inkAcrossTheMiddle(), nothing)
        assertTrue("a red line across the page", redPixels(rendered()) > PAGE_WIDTH / 2)

        session.undo(nothing)
        assertEquals(0, redPixels(rendered()))
    }

    @Test
    fun a_highlight_and_an_ink_stroke_are_undone_one_at_a_time() {
        val quads = helloQuads().single()
        session.apply(highlightHello(), nothing)
        session.apply(inkAcrossTheMiddle(), nothing)

        session.undo(nothing)
        val afterOneUndo = rendered()
        assertEquals(0, redPixels(afterOneUndo))
        assertTrue("the highlight is still there", yellowPixels(afterOneUndo, quads) > 0)

        session.undo(nothing)
        assertEquals(0, yellowPixels(rendered(), quads))
    }

    @Test
    fun saved_markup_and_ink_are_in_the_file_when_it_is_opened_again() {
        val quads = helloQuads().single()
        session.apply(highlightHello(), nothing)
        session.apply(inkAcrossTheMiddle(), nothing)
        assertTrue(session.save(nothing) is PdfEditSession.SaveOutcome.Saved)
        session.close()

        val reopened = renderedFromDisk(pdf)
        assertTrue("the highlight was saved", yellowPixels(reopened, quads) > area(quads, reopened) / 3)
        assertTrue("the ink was saved", redPixels(reopened) > PAGE_WIDTH / 2)
    }

    /** A one-page PDF with the text described in the class comment, its cross-reference table computed. */
    private fun textPdf(): ByteArray {
        val content = "BT /F1 24 Tf 20 350 Td (Hello Kern world) Tj ET"
        val bodies = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val out = ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
        write("%PDF-1.4\n")
        val offsets = bodies.mapIndexed { index, body ->
            out.size().also { write("${index + 1} 0 obj\n$body\nendobj\n") }
        }
        val xref = out.size()
        write("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(it)) }
        write("trailer\n<< /Size ${bodies.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private companion object {
        const val PAGE_WIDTH = 300
        const val HELLO_LENGTH = 5
        const val INK_Y = 0.6
        const val INK_WIDTH = 6.0
    }
}
