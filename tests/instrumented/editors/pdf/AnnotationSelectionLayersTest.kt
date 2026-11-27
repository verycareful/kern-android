package dev.kern.editors.pdf

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The selection bar, the note dialog and the carrying of a chosen note, driven by touch on a page of 300 x 400 dp. */
@RunWith(AndroidJUnit4::class)
class AnnotationSelectionLayersTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private lateinit var dir: File
    private lateinit var vm: PdfEditorViewModel

    @Before
    fun open() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        app = instrumentation.targetContext.applicationContext as Application
        dir = File(app.cacheDir, "selection-layers-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        val pdf = TestPdfs.make(File(dir, "one.pdf"), 1)
        instrumentation.runOnMainSync {
            vm = PdfEditorViewModel(app)
            vm.start("file://${pdf.absolutePath}")
        }
        await("the PDF to open") { !vm.loading && vm.unavailableReason == null }
        instrumentation.runOnMainSync {
            vm.startNote()
            vm.placeNote(0, PagePoint(0.3f, 0.5f))
            vm.commitNoteText("Check this")
        }
        await("the warning") { vm.editWarning != null }
        instrumentation.runOnMainSync { vm.editWithoutCopy() }
        await("the note to be chosen") { vm.selectedAnnotation != null && !vm.editBusy }
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

    private fun show(content: @Composable () -> Unit) {
        val settings = KernSettings.create(app)
        compose.setContent { CompositionLocalProvider(LocalKernSettings provides settings) { KernTheme { content() } } }
        compose.waitForIdle()
    }

    private fun noteRect() = runBlocking { vm.annotationsOf(0) }.single { it.kind == PdfAnnotationKind.Note }.rect

    @Test
    fun the_bar_shows_the_note_its_text_and_the_actions_that_work() {
        show { SelectionBar(vm) }
        compose.onNodeWithText("Note").assertIsDisplayed()
        compose.onNodeWithText("Check this").assertIsDisplayed()
        compose.onNodeWithText("Edit text").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithContentDescription("Yellow, chosen").assertIsDisplayed()
    }

    @Test
    fun the_bar_is_empty_when_nothing_is_chosen() {
        show { SelectionBar(vm) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.deselect() }
        compose.waitForIdle()
        compose.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun close_drops_the_choice_and_leaves_the_note() {
        show { SelectionBar(vm) }
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        assertNull(vm.selectedAnnotation)
        assertEquals(1, runBlocking { vm.annotationsOf(0) }.count { it.kind == PdfAnnotationKind.Note })
    }

    @Test
    fun a_colour_swatch_recolours_the_note() {
        show { SelectionBar(vm) }
        compose.onNodeWithContentDescription("Red").performClick()
        await("the colour") { AnnotationSelectionModel.offeredColour(vm.selectedAnnotation?.annotation?.colour) == MarkColour.Red }
    }

    @Test
    fun delete_takes_the_note_off_and_the_bar_goes() {
        show { SelectionBar(vm) }
        compose.onNodeWithText("Delete").performClick()
        await("the deletion") { vm.selectedAnnotation == null && !vm.editBusy }
        compose.waitForIdle()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        assertTrue(runBlocking { vm.annotationsOf(0) }.none { it.kind == PdfAnnotationKind.Note })
    }

    @Test
    fun edit_text_asks_with_the_old_text() {
        show { SelectionBar(vm) }
        compose.onNodeWithText("Edit text").performClick()
        compose.waitForIdle()
        assertEquals("Check this", vm.noteRequest!!.initial)
    }

    @Test
    fun a_finger_dragged_from_the_note_carries_it_by_the_distance_dragged() {
        val before = noteRect()
        show {
            Box(Modifier.size(PAGE_W.dp, PAGE_H.dp).testTag("page").carryAnnotation(vm, 0, REACH_PX)) {
                SelectionOutline(vm, 0, Modifier.size(PAGE_W.dp, PAGE_H.dp))
            }
        }
        compose.onNodeWithTag("page").performTouchInput {
            val from = Offset((before.left + before.right) / 2 * width, (before.top + before.bottom) / 2 * height)
            down(from)
            moveTo(from + Offset(width * 0.2f, height * 0.1f))
            up()
        }
        await("the move") { vm.selectedAnnotation?.annotation?.rect?.left?.let { it > before.left + 0.1f } == true && !vm.editBusy }
        val after = vm.selectedAnnotation!!.annotation.rect
        assertEquals(before.left + 0.2f, after.left, 0.01f)
        assertEquals(before.top + 0.1f, after.top, 0.01f)
    }

    @Test
    fun a_drag_that_starts_away_from_the_note_moves_nothing() {
        val before = noteRect()
        show { Box(Modifier.size(PAGE_W.dp, PAGE_H.dp).testTag("page").carryAnnotation(vm, 0, REACH_PX)) }
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(width * 0.85f, height * 0.9f))
            moveTo(Offset(width * 0.85f, height * 0.6f))
            up()
        }
        compose.waitForIdle()
        Thread.sleep(300)
        assertEquals(before, noteRect())
        assertEquals(PagePoint(0f, 0f), vm.carriedOffset)
        assertTrue(!vm.editBusy)
    }

    @Test
    fun a_touch_that_does_not_move_leaves_the_note_where_it_is() {
        val before = noteRect()
        show { Box(Modifier.size(PAGE_W.dp, PAGE_H.dp).testTag("page").carryAnnotation(vm, 0, REACH_PX)) }
        compose.onNodeWithTag("page").performTouchInput {
            val at = Offset((before.left + before.right) / 2 * width, (before.top + before.bottom) / 2 * height)
            down(at)
            up()
        }
        compose.waitForIdle()
        Thread.sleep(300)
        assertEquals(before, noteRect())
    }

    @Test
    fun the_note_dialog_adds_only_with_some_text_and_hands_it_over() {
        var handed: String? = null
        var request by mutableStateOf<PdfEditorViewModel.NoteRequest?>(PdfEditorViewModel.NoteRequest.New(0, PagePoint(0.1f, 0.1f)))
        show {
            request?.let { NoteDialog(it, onCancel = { request = null }, onConfirm = { text -> handed = text }) }
        }
        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("   ")
        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Look here")
        compose.onNodeWithText("Add").assertIsEnabled().performClick()
        assertEquals("   Look here", handed)
    }

    @Test
    fun cancelling_the_note_dialog_hands_nothing_over() {
        var handed: String? = null
        var request by mutableStateOf<PdfEditorViewModel.NoteRequest?>(PdfEditorViewModel.NoteRequest.New(0, PagePoint(0.1f, 0.1f)))
        show {
            request?.let { NoteDialog(it, onCancel = { request = null }, onConfirm = { text -> handed = text }) }
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertNull(handed)
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    private companion object {
        const val PAGE_W = 300f
        const val PAGE_H = 400f
        const val REACH_PX = 30f
    }
}
