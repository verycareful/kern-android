package dev.kern.editors.pdf

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.pdfbridge.PdfAnnotation
import dev.kern.pdfbridge.PdfAnnotationKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Taps on a page that holds annotations Kern did not make: a link, a form field, a stamp and a locked note. */
@RunWith(AndroidJUnit4::class)
class PdfEditorForeignAnnotationsInstrumentedTest {

    private lateinit var app: Application
    private lateinit var dir: File
    private lateinit var vm: PdfEditorViewModel

    private val aspect = TestPdfs.HEIGHT.toFloat() / TestPdfs.WIDTH
    private val reach = 0.05f

    @Before
    fun open() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        dir = File(app.cacheDir, "vm-foreign-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        val pdf = TestPdfs.withForeignAnnotations(File(dir, "foreign.pdf"))
        onMain {
            vm = PdfEditorViewModel(app)
            vm.start("file://${pdf.absolutePath}")
        }
        await("the PDF to open") { !vm.loading && vm.unavailableReason == null }
        assertNull(vm.error)
    }

    @After
    fun close() {
        dir.deleteRecursively()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun annotations(): List<PdfAnnotation> = runBlocking { vm.annotationsOf(0) }

    private fun tap(x: Float, y: Float) = onMain { vm.tapPage(0, PagePoint(x, y), aspect, reach) }

    /** Gives the engine's answers a moment: a tap that selects nothing has no event to wait for. */
    private fun settle() = Thread.sleep(400)

    @Test
    fun the_engine_reads_the_link_the_form_field_the_stamp_and_the_locked_note_as_annotations() {
        val found = annotations()
        assertEquals(setOf("Link", "Widget", "Stamp"), found.mapNotNull { (it.kind as? PdfAnnotationKind.Other)?.subtype }.toSet())
        assertEquals(1, found.count { it.kind == PdfAnnotationKind.Note })
        assertTrue("every one has a name", found.all { it.id != null })
    }

    @Test
    fun a_tap_on_a_link_follows_it_and_chooses_nothing() {
        tap(0.2f, 0.71f)
        await("the link to be offered") { vm.pendingLink == "https://example.org" }
        assertNull(vm.selectedAnnotation)
    }

    @Test
    fun a_tap_on_a_form_field_chooses_nothing() {
        tap(0.2f, 0.46f)
        settle()
        assertNull(vm.selectedAnnotation)
        assertNull(vm.pendingLink)
    }

    @Test
    fun a_stamp_is_chosen_and_can_only_be_deleted() {
        tap(0.65f, 0.46f)
        await("the stamp to be chosen") { vm.selectedAnnotation != null }
        val actions = AnnotationSelectionModel.actionsOf(vm.selectedAnnotation!!.annotation)
        assertTrue(actions.delete)
        assertFalse(actions.recolour || actions.move || actions.editText)
        assertNotNull(actions.limit)

        onMain { vm.recolourSelected(MarkColour.Red); vm.moveSelected(PagePoint(0.1f, 0.1f)) }
        settle()
        assertFalse("nothing was asked of the engine", vm.dirty)
        assertNull(vm.editWarning)

        onMain { vm.deleteSelected() }
        await("the warning") { vm.editWarning != null }
        onMain { vm.editWithoutCopy() }
        await("the deletion") { vm.dirty && !vm.editBusy }
        assertTrue(annotations().none { (it.kind as? PdfAnnotationKind.Other)?.subtype == "Stamp" })
        assertNull(vm.selectedAnnotation)
    }

    @Test
    fun a_locked_note_is_read_but_nothing_can_be_done_to_it() {
        tap(0.54f, 0.22f)
        await("the note to be chosen") { vm.selectedAnnotation != null }
        assertEquals("Locked", vm.selectedAnnotation!!.annotation.contents)
        val actions = AnnotationSelectionModel.actionsOf(vm.selectedAnnotation!!.annotation)
        assertFalse(actions.recolour || actions.move || actions.delete || actions.editText)

        onMain { vm.recolourSelected(MarkColour.Red); vm.deleteSelected(); vm.editSelectedNoteText() }
        settle()
        assertNull(vm.noteRequest)
        assertFalse(vm.dirty)
        assertNull(vm.editWarning)
    }
}
