package dev.kern.editors.pdf

import android.app.Application
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.shared.settings.KernSettings
import dev.kern.shared.settings.LocalKernSettings
import dev.kern.shared.theme.KernTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The whole PDF screen with marking open: a press lands on the text under the finger whatever the zoom was when it opened. */
@RunWith(AndroidJUnit4::class)
class PdfEditorScreenMarkupZoomInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val longPressMs = ViewConfiguration.getLongPressTimeout() + 200L
    private lateinit var dir: File
    private lateinit var vm: PdfEditorViewModel

    @Before
    fun open() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        dir = File(app.cacheDir, "screen-markup-zoom-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        val pdf = TestPdfs.make(File(dir, "report.pdf"), 2)
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

    @Test
    fun a_press_after_zooming_out_selects_the_glyph_under_the_finger() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.startMarkup() }
        compose.waitForIdle()
        val glyphs = runBlocking { vm.pageGlyphs(0) }
        val target = 3
        val box = glyphs.boxes[target]

        compose.onNodeWithContentDescription("Page 1").performTouchInput {
            pinch(
                Offset(width * 0.2f, height * 0.5f), Offset(width * 0.4f, height * 0.5f),
                Offset(width * 0.8f, height * 0.5f), Offset(width * 0.6f, height * 0.5f),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Page 1").performTouchInput {
            down(Offset((box.left + box.right) / 2 * width, (box.top + box.bottom) / 2 * height))
            advanceEventTime(longPressMs)
            up()
        }
        compose.waitForIdle()
        val selection = vm.markupSelection
        assertNotNull("a press on text selects it", selection)
        assertEquals(target, selection!!.first)
    }
}
