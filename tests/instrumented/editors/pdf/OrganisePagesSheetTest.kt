package dev.kern.editors.pdf

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.shared.settings.KernSettings
import dev.kern.shared.settings.LocalKernSettings
import dev.kern.shared.theme.KernTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Organise pages sheet driven by touch, on a file whose pages are told apart by their width. */
@RunWith(AndroidJUnit4::class)
class OrganisePagesSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val widths = listOf(200, 210, 220, 230)
    private val LONG_PRESS_MS = ViewConfiguration.getLongPressTimeout() + 200L

    private lateinit var dir: File
    private lateinit var pdf: File
    private lateinit var vm: PdfEditorViewModel

    @Before
    fun open() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        dir = File(app.cacheDir, "organise-sheet-test").apply { deleteRecursively(); mkdirs() }
        pdf = TestPdfs.makeSized(File(dir, "book.pdf"), widths)
        instrumentation.runOnMainSync {
            vm = PdfEditorViewModel(app)
            vm.start("file://${pdf.absolutePath}")
        }
        await("the PDF to open") { !vm.loading }
        val settings = KernSettings.create(app)
        compose.setContent {
            CompositionLocalProvider(LocalKernSettings provides settings) {
                KernTheme { OrganisePagesSheet(vm, onDismiss = {}) }
            }
        }
        compose.waitForIdle()
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

    private fun tile(index: Int) = compose.onNodeWithTag("$TILE_TAG_PREFIX$index")

    /** Lets the first edit go ahead without a copy, waits for it, and saves it to the file. */
    private fun settleAndSave() {
        await("the first-edit warning") { vm.editWarning != null }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.editWithoutCopy() }
        await("the edit") { vm.dirty && !vm.editBusy }
        var result: Pair<Boolean, String?>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.save { ok, message -> result = ok to message } }
        await("the save") { result != null }
        assertEquals(true to null, result)
    }

    private fun centreInGrid(index: Int): Offset {
        val grid = compose.onNodeWithTag("organise-grid").fetchSemanticsNode().boundsInRoot
        return tile(index).fetchSemanticsNode().boundsInRoot.center - grid.topLeft
    }

    @Test
    fun shows_a_tile_for_every_page_and_nothing_is_selected() {
        for (index in widths.indices) tile(index).assertIsNotSelected()
        compose.onNodeWithText("Tap pages to select", substring = true).assertTextContains("press and hold", substring = true, ignoreCase = true)
    }

    @Test
    fun the_actions_wait_for_a_selection() {
        compose.onNodeWithTag(ROTATE_LEFT_LABEL).assertIsNotEnabled()
        compose.onNodeWithTag(ROTATE_RIGHT_LABEL).assertIsNotEnabled()
        compose.onNodeWithTag(DELETE_LABEL).assertIsNotEnabled()
        tile(1).performClick()
        tile(1).assertIsSelected()
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithTag(ROTATE_LEFT_LABEL).assertIsEnabled()
        compose.onNodeWithTag(ROTATE_RIGHT_LABEL).assertIsEnabled()
        compose.onNodeWithTag(DELETE_LABEL).assertIsEnabled()
    }

    @Test
    fun a_second_tap_unselects() {
        tile(2).performClick()
        tile(2).performClick()
        tile(2).assertIsNotSelected()
    }

    @Test
    fun every_page_cannot_be_deleted_and_the_sheet_says_why() {
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("4 selected").assertExists()
        compose.onNodeWithTag(DELETE_LABEL).assertIsNotEnabled()
        compose.onNodeWithText("A PDF keeps at least one page", substring = true).assertExists()
        compose.onNodeWithTag(ROTATE_RIGHT_LABEL).assertIsEnabled()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Select all").assertExists()
    }

    @Test
    fun rotating_the_selected_pages_turns_them_in_the_saved_file() {
        tile(1).performClick()
        tile(3).performClick()
        compose.onNodeWithTag(ROTATE_RIGHT_LABEL).performClick()
        settleAndSave()
        assertEquals(listOf(200 to 400, 400 to 210, 220 to 400, 400 to 230), (0 until 4).map { TestPdfs.pageSize(pdf, it) })
        tile(1).assertIsSelected()
    }

    @Test
    fun deleting_asks_first_and_then_removes_the_selected_pages() {
        tile(0).performClick()
        tile(2).performClick()
        compose.onNodeWithTag(DELETE_LABEL).performClick()
        compose.onNodeWithText("Delete 2 pages?").assertExists()
        compose.onNode(hasText("Cancel")).performClick()
        compose.onNodeWithText("Delete 2 pages?").assertDoesNotExist()
        assertEquals(widths, TestPdfs.pageWidths(pdf))
        compose.onNodeWithTag(DELETE_LABEL).performClick()
        compose.onNodeWithTag(CONFIRM_DELETE_TAG).performClick()
        settleAndSave()
        assertEquals(listOf(210, 230), TestPdfs.pageWidths(pdf))
        compose.onNodeWithText("Tap pages to select", substring = true).assertExists()
    }

    @Test
    fun dragging_a_page_onto_another_moves_it() {
        val from = centreInGrid(0)
        val to = centreInGrid(2)
        val grid = compose.onNodeWithTag("organise-grid")
        grid.performTouchInput { down(from) }
        compose.mainClock.advanceTimeBy(LONG_PRESS_MS)
        grid.performTouchInput { moveTo((from + to) / 2f) }
        grid.performTouchInput { moveTo(to) }
        compose.waitForIdle()
        grid.performTouchInput { up() }
        settleAndSave()
        assertEquals(listOf(210, 220, 200, 230), TestPdfs.pageWidths(pdf))
    }

    @Test
    fun a_press_that_does_not_drag_moves_nothing() {
        val at = centreInGrid(1)
        val grid = compose.onNodeWithTag("organise-grid")
        grid.performTouchInput { down(at) }
        compose.mainClock.advanceTimeBy(LONG_PRESS_MS)
        grid.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(null, vm.editWarning)
        assertEquals(widths, TestPdfs.pageWidths(pdf))
    }
}
