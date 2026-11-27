package dev.kern.editors.pdf

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.shared.settings.KernSettings
import dev.kern.shared.settings.LocalKernSettings
import dev.kern.shared.theme.KernTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The text blocks layer and the bar's Text mode on their own, over a page of 300 x 400 dp. */
@RunWith(AndroidJUnit4::class)
class TextEditLayersTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private lateinit var dir: File
    private lateinit var vm: PdfEditorViewModel

    @Before
    fun open() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        app = instrumentation.targetContext.applicationContext as Application
        dir = File(app.cacheDir, "text-layers-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        val pdf = TestPdfs.withEditableText(File(dir, "one.pdf"), 1)
        instrumentation.runOnMainSync {
            vm = PdfEditorViewModel(app)
            vm.start("file://${pdf.absolutePath}")
        }
        val deadline = System.currentTimeMillis() + 20_000
        while (vm.loading || vm.unavailableReason != null) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the PDF to open" }
            Thread.sleep(25)
        }
        instrumentation.runOnMainSync { vm.startTextEdit() }
    }

    @After
    fun close() {
        dir.deleteRecursively()
    }

    private fun show(content: @Composable () -> Unit) {
        val settings = KernSettings.create(app)
        compose.setContent { CompositionLocalProvider(LocalKernSettings provides settings) { KernTheme { content() } } }
        compose.waitForIdle()
    }

    @Test
    fun the_bar_asks_for_a_tap_on_text_and_offers_done() {
        show { AnnotateBar(vm) }
        compose.onNodeWithText(TextEditModel.TAP_HINT).assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun a_tap_on_a_block_opens_its_field_and_what_is_typed_is_the_draft() {
        show {
            Column {
                Box(Modifier.size(300.dp, 400.dp).testTag("page")) { TextBlocksLayer(vm, 0, Modifier.matchParentSize()) }
                AnnotateBar(vm)
            }
        }
        val box = runBlocking { vm.blocksOf(0) }!!.blocks.single().box
        compose.onNodeWithTag("page").performTouchInput {
            click(androidx.compose.ui.geometry.Offset((box.left + box.right) / 2 * width, (box.top + box.bottom) / 2 * height))
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("page 1").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("page 9")
        assertEquals("page 9", vm.textDraft)
        compose.onNodeWithText("Apply").assertIsDisplayed()
    }
}
