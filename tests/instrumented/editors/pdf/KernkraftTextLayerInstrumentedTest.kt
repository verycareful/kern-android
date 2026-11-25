package dev.kern.editors.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.pdfbridge.PdfOp
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
 * The text layer on the kernkraft engine, on a device: search, lines and links of a PDF built
 * by hand, so every expected box follows from how the file is laid out.
 *
 * Page 1 (300 x 400 pt) shows "Hello Kern world" with its baseline 50 pt below the top, and
 * "needle here" with its baseline 100 pt below the top. It carries three links: a bare /Dest
 * array to page 2, a named destination to page 2, and a web address. Page 2 shows "second page".
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class KernkraftTextLayerInstrumentedTest {

    private lateinit var dir: File
    private lateinit var session: PdfEditSession
    private lateinit var layer: PdfTextLayer
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    @Before
    fun openSession() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cache, "text-layer-test").apply { deleteRecursively(); mkdirs() }
        val pdf = File(dir, "linked.pdf").apply { writeBytes(linkedPdf()) }
        session = PdfEditSession.create(File(dir, "session"), FileDocumentTarget(pdf)) { "D:20261125120000+05'30'" }
        layer = session.textLayer(dispatcher)
    }

    @After
    fun cleanUp() {
        session.close()
        dir.deleteRecursively()
    }

    @Test
    fun search_finds_the_word_where_it_is_drawn_whatever_the_case() = runBlocking {
        val found = layer.search(0, "NEEDLE")
        assertEquals(1, found.size)
        assertEquals(0, found[0].page)
        val box = found[0].rects.single()
        // Baseline at 100 pt of 400, 24 pt Helvetica: the box sits between about 0.20 and 0.27 of the height.
        assertTrue("left ${box.left}", box.left in 0.05f..0.09f)
        assertTrue("top ${box.top}", box.top in 0.15f..0.26f)
        assertTrue("bottom ${box.bottom}", box.bottom in 0.22f..0.32f)
        assertEquals(emptyList<PdfTextMatch>(), layer.search(1, "needle"))
    }

    @Test
    fun lines_come_in_reading_order_with_their_boxes() = runBlocking {
        val lines = layer.lines(0)
        assertEquals(listOf("Hello Kern world", "needle here"), lines.map { it.text })
        assertTrue(lines[0].rect.bottom <= lines[1].rect.top + 0.03f)
        assertEquals(listOf("second page"), layer.lines(1).map { it.text })
    }

    @Test
    fun links_follow_a_bare_dest_a_named_destination_and_a_web_address() = runBlocking {
        val links = layer.links(0)
        assertEquals(3, links.size)
        val bare = links.filterIsInstance<PdfLink.GoTo>().single { it.rects.single().top in 0.40f..0.45f }
        assertEquals(1, bare.page)
        // Rect [20 200 120 230] on a 400 pt page: top 170 pt, bottom 200 pt from the top.
        assertEquals(20f / 300f, bare.rects.single().left, 0.002f)
        assertEquals(170f / 400f, bare.rects.single().top, 0.002f)
        val named = links.filterIsInstance<PdfLink.GoTo>().single { it.rects.single().top in 0.55f..0.60f }
        assertEquals(1, named.page)
        val web = links.filterIsInstance<PdfLink.External>().single()
        assertEquals("https://example.org", web.uri)
        assertEquals(bare, links.at(PagePoint(0.2f, 0.45f)))
    }

    @Test
    fun the_layer_answers_for_the_edited_document() = runBlocking {
        session.apply(PdfOp.Reorder(listOf(1, 0)), {})
        assertEquals(listOf("second page"), layer.lines(0).map { it.text })
        assertEquals(listOf("Hello Kern world", "needle here"), layer.lines(1).map { it.text })
        assertEquals(1, layer.search(1, "needle").single().page)
        // The links moved with their page, and still lead to the page they led to.
        assertEquals(listOf(0, 0), layer.links(1).filterIsInstance<PdfLink.GoTo>().map { it.page })
    }

    /** The PDF described in the class comment, with its cross-reference table computed. */
    private fun linkedPdf(): ByteArray {
        val bodies = listOf(
            "<< /Type /Catalog /Pages 2 0 R /Dests 8 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents 5 0 R " +
                "/Resources << /Font << /F1 6 0 R >> >> /Annots [7 0 R 9 0 R 10 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents 11 0 R " +
                "/Resources << /Font << /F1 6 0 R >> >> >>",
            stream("BT /F1 24 Tf 20 350 Td (Hello Kern world) Tj ET BT /F1 24 Tf 20 300 Td (needle here) Tj ET"),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Type /Annot /Subtype /Link /Rect [20 200 120 230] /Border [0 0 0] /Dest [4 0 R /Fit] >>",
            "<< /chapter [4 0 R /XYZ 0 400 0] >>",
            "<< /Type /Annot /Subtype /Link /Rect [20 150 120 180] /Border [0 0 0] /Dest /chapter >>",
            "<< /Type /Annot /Subtype /Link /Rect [20 100 120 130] /Border [0 0 0] " +
                "/A << /S /URI /URI (https://example.org) >> >>",
            stream("BT /F1 24 Tf 20 350 Td (second page) Tj ET"),
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

    private fun stream(content: String) = "<< /Length ${content.length} >>\nstream\n$content\nendstream"
}
