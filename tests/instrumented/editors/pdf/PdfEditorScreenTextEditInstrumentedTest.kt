package dev.kern.editors.pdf

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.shared.settings.KernSettings
import dev.kern.shared.settings.LocalKernSettings
import dev.kern.shared.theme.KernTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Changing the text of a page on the whole PDF screen: the menu row, the outlined blocks, the field over a block, and its bar. */
@RunWith(AndroidJUnit4::class)
class PdfEditorScreenTextEditInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private lateinit var dir: File
    private lateinit var vm: PdfEditorViewModel

    @Before
    fun open() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        app = instrumentation.targetContext.applicationContext as Application
        dir = File(app.cacheDir, "screen-textedit-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        val pdf = TestPdfs.withEditableText(File(dir, "report.pdf"), 2)
        instrumentation.runOnMainSync {
            vm = PdfEditorViewModel(app)
            vm.start("file://${pdf.absolutePath}")
        }
        await("the PDF to open") { !vm.loading && vm.unavailableReason == null }
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

    private fun texts(page: Int = 0) = runBlocking { vm.pageLines(page) }.map { it.text }

    private fun chooseEditPageText() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Edit page text").performClick()
        compose.waitForIdle()
    }

    /** Touches page 1 at [x], [y] as fractions of the page. */
    private fun tapPage(x: Float, y: Float) {
        compose.onNodeWithContentDescription("Page 1").performTouchInput { click(Offset(x * width, y * height)) }
    }

    private fun tapOnBlock() {
        val box = runBlocking { vm.blocksOf(0) }!!.blocks.single().box
        tapPage((box.left + box.right) / 2, (box.top + box.bottom) / 2)
    }

    @Test
    fun the_menu_row_starts_the_mode_and_the_bar_says_what_to_do() {
        chooseEditPageText()
        compose.onNodeWithText(TextEditModel.TAP_HINT).assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        assertEquals(PdfEditorViewModel.AnnotateMode.Text, vm.annotateMode)
    }

    @Test
    fun a_tap_on_the_text_opens_a_field_over_it_with_its_words_and_the_bar_offers_apply_and_cancel() {
        chooseEditPageText()
        tapOnBlock()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("page 1").assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText(TextEditModel.TAP_HINT).assertDoesNotExist()
        compose.onNodeWithText("Done").assertDoesNotExist()
    }

    @Test
    fun typing_in_the_field_and_applying_changes_the_page() {
        chooseEditPageText()
        tapOnBlock()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("page 7")
        compose.onNodeWithText("Apply").performClick()
        await("the warning") { vm.editWarning != null }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.editWithoutCopy() }
        await("the edit") { vm.dirty && !vm.editBusy }
        assertEquals(listOf("page 7"), texts())
        assertNull(vm.textEdit)
        compose.waitForIdle()
        assertFalse(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun an_emptied_field_offers_to_remove_the_text() {
        chooseEditPageText()
        tapOnBlock()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText("Remove text").assertIsDisplayed()
    }

    @Test
    fun cancel_closes_the_field_and_leaves_the_page() {
        chooseEditPageText()
        tapOnBlock()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("something else")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertFalse(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
        assertFalse(vm.dirty)
        assertEquals(listOf("page 1"), texts())
        compose.onNodeWithText(TextEditModel.TAP_HINT).assertIsDisplayed()
    }

    @Test
    fun a_tap_where_there_is_no_text_opens_a_field_whose_text_is_added_to_the_page() {
        chooseEditPageText()
        tapPage(0.1f, 0.5f)
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Helvetica").assertIsDisplayed()
        compose.onNodeWithText("24 pt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Width of the new text").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("Added here")
        compose.onNodeWithText("Add text").performClick()
        await("the warning") { vm.editWarning != null }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.editWithoutCopy() }
        await("the edit") { vm.dirty && !vm.editBusy }
        assertEquals("the box is as wide as the page's text, so the words wrap there", listOf("page 1", "Added", "here"), texts())
        assertNull(vm.newText)
    }

    @Test
    fun the_size_chip_sets_the_size_of_new_text() {
        chooseEditPageText()
        tapPage(0.1f, 0.5f)
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("24 pt").performClick()
        compose.onNodeWithText("14 pt").performClick()
        compose.waitForIdle()
        assertEquals(14.0, vm.newText!!.effectiveSize, 0.0)
        compose.onNodeWithText("14 pt").assertIsDisplayed()
    }
}
