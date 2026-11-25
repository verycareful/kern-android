package dev.kern.pdfbridge

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The native engine on a device or emulator: libkernkraft_jni.so loads and merge and extract make real PDFs. */
@RunWith(AndroidJUnit4::class)
class KernkraftPdfInstrumentedTest {

    private lateinit var dir: File

    @Before
    fun makeDir() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cache, "kernkraft-instrumented").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun removeDir() {
        dir.deleteRecursively()
    }

    /** A PDF of [pages] pages made by the framework, each page showing its own number. */
    private fun pdf(name: String, pages: Int): File {
        val file = File(dir, name)
        val doc = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 24f }
        repeat(pages) { index ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
            page.canvas.drawText("page ${index + 1} of $name", 20f, 40f, paint)
            doc.finishPage(page)
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun pageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }

    @Test
    fun the_library_loads() {
        assertTrue("libkernkraft_jni.so did not load", KernkraftPdf.available)
    }

    @Test
    fun merge_joins_the_sources_in_order() {
        val out = File(dir, "merged.pdf")
        val result = KernkraftPdf.merge(
            listOf(pdf("a.pdf", 2).path, pdf("b.pdf", 3).path),
            out.path,
        )
        assertEquals(KernkraftPdf.Result.Success(listOf(out.path)), result)
        assertEquals(5, pageCount(out))
    }

    @Test
    fun a_second_merge_to_the_same_path_is_refused_and_leaves_the_first_alone() {
        val out = File(dir, "merged.pdf")
        val sources = listOf(pdf("a.pdf", 1).path, pdf("b.pdf", 1).path)
        assertTrue(KernkraftPdf.merge(sources, out.path) is KernkraftPdf.Result.Success)
        val before = out.readBytes()
        val second = KernkraftPdf.merge(sources, out.path)
        assertTrue(second is KernkraftPdf.Result.Failure)
        assertTrue(before.contentEquals(out.readBytes()))
    }

    @Test
    fun extract_keeps_the_pages_asked_for() {
        val out = File(dir, "extract.pdf")
        val result = KernkraftPdf.extract(pdf("src.pdf", 6).path, listOf(2, 3, 4), out.path)
        assertEquals(KernkraftPdf.Result.Success(listOf(out.path)), result)
        assertEquals(3, pageCount(out))
    }

    @Test
    fun extract_past_the_last_page_fails_without_leaving_a_file() {
        val out = File(dir, "extract.pdf")
        val result = KernkraftPdf.extract(pdf("src.pdf", 2).path, listOf(1, 9), out.path)
        assertTrue(result is KernkraftPdf.Result.Failure)
        assertTrue(!out.exists())
    }

    @Test
    fun a_missing_source_is_a_failure_not_a_crash() {
        val result = KernkraftPdf.merge(listOf(File(dir, "none.pdf").path), File(dir, "out.pdf").path)
        assertTrue(result is KernkraftPdf.Result.Failure)
    }
}
