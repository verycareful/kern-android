package dev.kern.editors.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.pdfbridge.PdfOp
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The working copy, the engine and the append-only save, on a device. */
@RunWith(AndroidJUnit4::class)
class PdfEditSessionInstrumentedTest {

    private lateinit var dir: File
    private lateinit var original: File
    private lateinit var originalBytes: ByteArray
    private val opened = mutableListOf<PdfEditSession>()

    @Before
    fun makeFiles() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cache, "edit-session-test").apply { deleteRecursively(); mkdirs() }
        original = TestPdfs.make(File(dir, "doc.pdf"), 3)
        originalBytes = original.readBytes()
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun session(target: DocumentTarget = FileDocumentTarget(original)): PdfEditSession =
        PdfEditSession.create(File(dir, "session-${opened.size}"), target, { "D:20261125120000+05'30'" }).also { opened += it }

    private val nothing = {}

    @Test
    fun the_view_starts_as_the_document_itself() {
        val s = session()
        assertArrayEquals(originalBytes, s.viewFile.readBytes())
        assertFalse(s.step.modified)
        assertEquals(3, s.step.pageCount)
    }

    @Test
    fun an_edit_appends_to_the_view_and_leaves_the_document_alone() {
        val s = session()
        val step = s.apply(PdfOp.Rotate(listOf(0), 90), nothing)
        assertTrue(step.modified)
        assertTrue(step.canUndo)
        assertEquals(listOf(0), step.changedPages)
        val view = s.viewFile.readBytes()
        assertTrue(view.size > originalBytes.size)
        assertArrayEquals(originalBytes, view.copyOf(originalBytes.size))
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(TestPdfs.HEIGHT to TestPdfs.WIDTH, TestPdfs.pageSize(s.viewFile, 0))
        assertEquals(TestPdfs.WIDTH to TestPdfs.HEIGHT, TestPdfs.pageSize(s.viewFile, 1))
    }

    @Test
    fun the_view_is_released_before_it_is_changed() {
        val s = session()
        val order = mutableListOf<String>()
        val sizeBefore = s.viewFile.length()
        s.apply(PdfOp.Rotate(listOf(1), 180)) { order += "release:${s.viewFile.length()}" }
        assertEquals(listOf("release:$sizeBefore"), order)
    }

    @Test
    fun save_appends_the_update_and_keeps_every_original_byte() {
        val s = session()
        s.apply(PdfOp.Rotate(listOf(0), 90), nothing)
        val outcome = s.save(nothing)
        assertTrue(outcome is PdfEditSession.SaveOutcome.Saved)
        val saved = original.readBytes()
        assertTrue(saved.size > originalBytes.size)
        assertArrayEquals(originalBytes, saved.copyOf(originalBytes.size))
        assertEquals((outcome as PdfEditSession.SaveOutcome.Saved).length, saved.size.toLong())
        assertArrayEquals(saved, s.viewFile.readBytes())
        assertEquals(TestPdfs.HEIGHT to TestPdfs.WIDTH, TestPdfs.pageSize(original, 0))
    }

    @Test
    fun saving_again_with_nothing_new_writes_nothing() {
        val s = session()
        s.apply(PdfOp.Rotate(listOf(0), 90), nothing)
        s.save(nothing)
        val after = original.readBytes()
        assertEquals(PdfEditSession.SaveOutcome.NothingToSave, s.save(nothing))
        assertArrayEquals(after, original.readBytes())
    }

    @Test
    fun a_second_save_appends_after_the_first_and_keeps_it() {
        val s = session()
        s.apply(PdfOp.Rotate(listOf(0), 90), nothing)
        s.save(nothing)
        val first = original.readBytes()
        s.apply(PdfOp.Rotate(listOf(1), 270), nothing)
        assertTrue(s.save(nothing) is PdfEditSession.SaveOutcome.Saved)
        val second = original.readBytes()
        assertTrue(second.size > first.size)
        assertArrayEquals(first, second.copyOf(first.size))
        assertArrayEquals(second, s.viewFile.readBytes())
        assertEquals(TestPdfs.HEIGHT to TestPdfs.WIDTH, TestPdfs.pageSize(original, 1))
    }

    @Test
    fun undo_back_to_the_start_leaves_nothing_to_save() {
        val s = session()
        s.apply(PdfOp.Rotate(listOf(0), 90), nothing)
        val step = s.undo(nothing)
        assertFalse(step.modified)
        assertTrue(step.canRedo)
        assertArrayEquals(originalBytes, s.viewFile.readBytes())
        assertEquals(PdfEditSession.SaveOutcome.NothingToSave, s.save(nothing))
        assertArrayEquals(originalBytes, original.readBytes())
    }

    @Test
    fun redo_brings_the_edit_back() {
        val s = session()
        s.apply(PdfOp.Rotate(listOf(0), 90), nothing)
        s.undo(nothing)
        val step = s.redo(nothing)
        assertTrue(step.modified)
        assertEquals(TestPdfs.HEIGHT to TestPdfs.WIDTH, TestPdfs.pageSize(s.viewFile, 0))
    }

    @Test
    fun a_document_changed_elsewhere_is_not_written_to() {
        val s = session()
        s.apply(PdfOp.Rotate(listOf(0), 90), nothing)
        original.appendBytes("\n% someone else\n".toByteArray())
        val theirs = original.readBytes()
        assertEquals(PdfEditSession.SaveOutcome.ChangedOutside, s.save(nothing))
        assertArrayEquals(theirs, original.readBytes())
    }

    /** A provider that treats "append" as "write": the content is cleared before the update goes in. */
    private class TruncatingOnAppend(private val file: File) : FileDocumentTarget(file) {
        override fun openAppend(): OutputStream = file.outputStream()
    }

    @Test
    fun a_provider_that_truncates_on_append_is_put_back() {
        val s = session(TruncatingOnAppend(original))
        s.apply(PdfOp.Rotate(listOf(0), 90), nothing)
        val outcome = s.save(nothing)
        assertTrue(outcome.toString(), outcome is PdfEditSession.SaveOutcome.Failed)
        assertTrue((outcome as PdfEditSession.SaveOutcome.Failed).message.contains("put back"))
        assertArrayEquals(originalBytes, original.readBytes())
        assertTrue(s.step.modified)
    }

    /** A provider that clears the document and then refuses every write that would put it back. */
    private class TruncatesThenBreaks(private val file: File) : FileDocumentTarget(file) {
        override fun openAppend(): OutputStream = file.outputStream()
        override fun openTruncate(): OutputStream = throw IOException("provider refuses writes")
    }

    @Test
    fun a_document_that_cannot_be_put_back_is_reported_as_damaged() {
        val s = session(TruncatesThenBreaks(original))
        s.apply(PdfOp.Rotate(listOf(0), 90), nothing)
        val outcome = s.save(nothing)
        assertTrue(outcome.toString(), outcome is PdfEditSession.SaveOutcome.Damaged)
        assertTrue(File(dir, "session-0").resolve("view.pdf").length() >= originalBytes.size)
    }

    /** A provider whose append fails before anything is written. */
    private class RefusesAppend(file: File) : FileDocumentTarget(file) {
        override fun openAppend(): OutputStream = throw IOException("no append mode")
    }

    @Test
    fun a_provider_with_no_append_mode_is_an_error_and_the_document_is_untouched() {
        val s = session(RefusesAppend(original))
        s.apply(PdfOp.Rotate(listOf(0), 90), nothing)
        val outcome = s.save(nothing)
        assertTrue(outcome.toString(), outcome is PdfEditSession.SaveOutcome.Failed)
        assertTrue((outcome as PdfEditSession.SaveOutcome.Failed).message.contains("no append mode"))
        assertArrayEquals(originalBytes, original.readBytes())
    }

    @Test
    fun a_compacted_copy_is_a_whole_document_and_the_original_is_untouched() {
        val s = session()
        s.apply(PdfOp.Rotate(listOf(2), 90), nothing)
        val compacted = s.compact()
        assertEquals(3, TestPdfs.pageCount(compacted))
        assertEquals(TestPdfs.HEIGHT to TestPdfs.WIDTH, TestPdfs.pageSize(compacted, 2))
        assertArrayEquals(originalBytes, original.readBytes())
    }

    /** A PDF whose cross-reference is a stream and whose objects sit in object streams, as most real ones do. */
    private fun xrefStreamPdf(): File {
        val file = File(dir, "xref-stream.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("xref-stream.pdf").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    @Test
    fun a_document_with_a_cross_reference_stream_is_saved_and_still_opens() {
        val file = xrefStreamPdf()
        val before = file.readBytes()
        val pages = TestPdfs.pageCount(file)
        val (width, height) = TestPdfs.pageSize(file, 0)
        assertTrue(String(before, Charsets.ISO_8859_1).contains("/XRef"))
        val s = session(FileDocumentTarget(file))
        s.apply(PdfOp.Rotate(listOf(0), 90), nothing)
        assertTrue(s.save(nothing) is PdfEditSession.SaveOutcome.Saved)
        val saved = file.readBytes()
        assertArrayEquals(before, saved.copyOf(before.size))
        assertTrue(String(saved.copyOfRange(before.size, saved.size), Charsets.ISO_8859_1).contains("/XRef"))
        assertEquals(pages, TestPdfs.pageCount(file))
        assertEquals(height to width, TestPdfs.pageSize(file, 0))
    }

    @Test
    fun closing_removes_the_session_folder() {
        val s = session()
        val folder = s.viewFile.parentFile!!
        s.close()
        assertFalse(folder.exists())
    }
}
