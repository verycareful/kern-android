package dev.kern.editors.pdf

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.pdfbridge.PdfAnnotationKind
import dev.kern.shared.settings.KernSettings
import dev.kern.shared.settings.LocalKernSettings
import dev.kern.shared.theme.KernTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The whole PDF screen with a note chosen on it: the bar, and a finger carrying the note through the real page list. */
@RunWith(AndroidJUnit4::class)
class PdfEditorScreenNoteInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private lateinit var dir: File
    private lateinit var pdf: File
    private lateinit var vm: PdfEditorViewModel

    @Before
    fun open() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        app = instrumentation.targetContext.applicationContext as Application
        dir = File(app.cacheDir, "screen-note-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        pdf = TestPdfs.make(File(dir, "report.pdf"), 3)
        instrumentation.runOnMainSync {
            vm = PdfEditorViewModel(app)
            vm.start("file://${pdf.absolutePath}")
        }
        await("the PDF to open") { !vm.loading && vm.unavailableReason == null }
        instrumentation.runOnMainSync {
            vm.startNote()
            vm.placeNote(0, PagePoint(0.3f, 0.5f))
            vm.commitNoteText("Check this note")
        }
        await("the warning") { vm.editWarning != null }
        instrumentation.runOnMainSync { vm.editWithoutCopy() }
        await("the note to be chosen") { vm.selectedAnnotation != null && !vm.editBusy }
        val settings = KernSettings.create(app)
        compose.setContent {
            CompositionLocalProvider(LocalKernSettings provides settings) {
                KernTheme { PdfEditorScreen(filePath = "file://${pdf.absolutePath}", onOpenCopy = {}, vm = vm) }
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun close() {
        dir.deleteRecursively()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(25)
        }
    }

    private fun noteRect() = runBlocking { vm.annotationsOf(0) }.single { it.kind == PdfAnnotationKind.Note }.rect

    @Test
    fun the_bar_shows_the_chosen_note_on_the_real_screen() {
        compose.onNodeWithText("Check this note").assertIsDisplayed()
        compose.onNodeWithText("Edit text").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun a_finger_dragged_from_the_note_moves_it_and_the_page_list_does_not_scroll() {
        val before = noteRect()
        val pageBefore = compose.onNodeWithContentDescription("Page 1").getUnclippedBoundsInRoot()
        compose.onNodeWithContentDescription("Page 1").performTouchInput {
            val from = Offset((before.left + before.right) / 2 * width, (before.top + before.bottom) / 2 * height)
            down(from)
            moveTo(from + Offset(width * 0.1f, -height * 0.15f))
            moveTo(from + Offset(width * 0.2f, -height * 0.3f))
            up()
        }
        await("the move") { vm.selectedAnnotation?.annotation?.rect?.left?.let { it > before.left + 0.1f } == true && !vm.editBusy }
        val after = vm.selectedAnnotation!!.annotation.rect
        assertEquals(before.left + 0.2f, after.left, 0.01f)
        assertEquals(before.top - 0.3f, after.top, 0.01f)
        compose.waitForIdle()
        assertEquals("the page did not scroll", pageBefore.top, compose.onNodeWithContentDescription("Page 1").getUnclippedBoundsInRoot().top)
    }

    @Test
    fun delete_on_the_real_screen_takes_the_note_off_the_page() {
        compose.onNodeWithText("Delete").performClick()
        await("the deletion") { runBlocking { vm.annotationsOf(0) }.none { it.kind == PdfAnnotationKind.Note } && !vm.editBusy }
        assertTrue(vm.selectedAnnotation == null)
    }
}
