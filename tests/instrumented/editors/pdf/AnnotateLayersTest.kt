package dev.kern.editors.pdf

import android.app.Application
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kern.shared.settings.KernSettings
import dev.kern.shared.settings.LocalKernSettings
import dev.kern.shared.theme.KernTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The marking and pen surfaces driven by touch, on a page of 300 x 400 dp with one line of ten glyphs. */
@RunWith(AndroidJUnit4::class)
class AnnotateLayersTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val longPressMs = ViewConfiguration.getLongPressTimeout() + 200L
    private lateinit var dir: File
    private lateinit var vm: PdfEditorViewModel

    /** Glyph [i] spans x 0.1 + 0.05 i to 0.15 + 0.05 i of the width, y 0.20 to 0.25 of the height. */
    private val lines = listOf(
        PdfTextLine(
            "abcdefghij", PageRect(0.1f, 0.20f, 0.6f, 0.25f),
            List(10) { PageRect(0.1f + 0.05f * it, 0.20f, 0.15f + 0.05f * it, 0.25f) },
        ),
    )

    @Before
    fun open() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        dir = File(app.cacheDir, "annotate-layers-test").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "pdf-session").deleteRecursively()
        val pdf = TestPdfs.make(File(dir, "one.pdf"), 1)
        instrumentation.runOnMainSync {
            vm = PdfEditorViewModel(app)
            vm.start("file://${pdf.absolutePath}")
        }
        val deadline = System.currentTimeMillis() + 20_000
        while (vm.loading) {
            check(System.currentTimeMillis() < deadline) { "the PDF did not open" }
            Thread.sleep(25)
        }
    }

    @After
    fun close() {
        dir.deleteRecursively()
    }

    private fun showMarkup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.startMarkup() }
        show { MarkupLayer(vm, 0, lines, Modifier.size(PAGE_W.dp, PAGE_H.dp).testTag("layer")) }
    }

    private fun showInk() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.startInk() }
        show { InkLayer(vm, 0, Modifier.size(PAGE_W.dp, PAGE_H.dp).testTag("layer")) }
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        val settings = KernSettings.create(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application)
        compose.setContent { CompositionLocalProvider(LocalKernSettings provides settings) { KernTheme { content() } } }
        compose.waitForIdle()
    }

    /** The middle of glyph [i] in the layer's pixels. */
    private fun glyphCentre(i: Int, width: Float, height: Float) = Offset((0.125f + 0.05f * i) * width, 0.225f * height)

    @Test
    fun pressing_and_dragging_over_text_selects_from_the_first_glyph_to_the_last() {
        showMarkup()
        compose.onNodeWithTag("layer").performTouchInput {
            down(glyphCentre(2, width.toFloat(), height.toFloat()))
            advanceEventTime(longPressMs)
            moveTo(glyphCentre(6, width.toFloat(), height.toFloat()))
            up()
        }
        compose.waitForIdle()
        assertEquals(MarkupSelection(0, 2, 6), vm.markupSelection)
    }

    @Test
    fun dragging_backwards_selects_the_same_glyphs() {
        showMarkup()
        compose.onNodeWithTag("layer").performTouchInput {
            down(glyphCentre(6, width.toFloat(), height.toFloat()))
            advanceEventTime(longPressMs)
            moveTo(glyphCentre(2, width.toFloat(), height.toFloat()))
            up()
        }
        compose.waitForIdle()
        val selection = vm.markupSelection!!
        assertEquals(2 to 6, selection.first to selection.last)
    }

    @Test
    fun a_quick_swipe_over_text_selects_nothing() {
        showMarkup()
        compose.onNodeWithTag("layer").performTouchInput {
            swipe(glyphCentre(2, width.toFloat(), height.toFloat()), glyphCentre(6, width.toFloat(), height.toFloat()), 100)
        }
        compose.waitForIdle()
        assertNull(vm.markupSelection)
    }

    @Test
    fun a_tap_clears_the_selection() {
        showMarkup()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectMarkup(MarkupSelection(0, 2, 4)) }
        compose.waitForIdle()
        compose.onNodeWithTag("layer").performTouchInput { click(Offset(width * 0.5f, height * 0.8f)) }
        compose.waitForIdle()
        assertNull(vm.markupSelection)
    }

    @Test
    fun the_end_handle_moves_the_end_of_the_selection_and_nothing_else() {
        showMarkup()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectMarkup(MarkupSelection(0, 2, 4)) }
        compose.waitForIdle()
        val density = compose.density.density
        compose.onNodeWithTag("layer").performTouchInput {
            // The end handle hangs below the right edge of glyph 4, at the middle of its line.
            val handle = Offset(0.35f * width, 0.225f * height + 18f * density)
            down(handle)
            moveTo(handle + Offset(0.125f * width, 0f))
            up()
        }
        compose.waitForIdle()
        val selection = vm.markupSelection!!
        assertEquals(2, selection.first)
        assertEquals(7, selection.last)
    }

    @Test
    fun the_start_handle_never_passes_the_end() {
        showMarkup()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectMarkup(MarkupSelection(0, 2, 4)) }
        compose.waitForIdle()
        val density = compose.density.density
        compose.onNodeWithTag("layer").performTouchInput {
            val handle = Offset(0.2f * width, 0.225f * height + 18f * density)
            down(handle)
            moveTo(handle + Offset(0.5f * width, 0f))
            up()
        }
        compose.waitForIdle()
        val selection = vm.markupSelection!!
        assertEquals(4, selection.first)
        assertEquals(4, selection.last)
    }

    @Test
    fun one_finger_draws_a_stroke_in_page_fractions_and_hands_it_over_when_it_lifts() {
        showInk()
        compose.onNodeWithTag("layer").performTouchInput {
            down(Offset(width * 0.2f, height * 0.5f))
            moveTo(Offset(width * 0.5f, height * 0.6f))
            moveTo(Offset(width * 0.8f, height * 0.5f))
            up()
        }
        compose.waitForIdle()
        val stroke = vm.pendingInk.single().strokes.single()
        assertTrue(stroke.size >= 3)
        assertEquals(0.2f, stroke.first().x, 0.01f)
        assertEquals(0.5f, stroke.first().y, 0.01f)
        assertEquals(0.8f, stroke.last().x, 0.01f)
        assertEquals(PAGE_H.toFloat() / PAGE_W, vm.pendingInk.single().pageAspect, 0.01f)
    }

    @Test
    fun a_second_finger_drops_the_stroke_so_two_fingers_can_pan_and_zoom() {
        showInk()
        compose.onNodeWithTag("layer").performTouchInput {
            down(0, Offset(width * 0.2f, height * 0.5f))
            moveTo(0, Offset(width * 0.4f, height * 0.5f))
            down(1, Offset(width * 0.6f, height * 0.5f))
            moveTo(0, Offset(width * 0.3f, height * 0.5f))
            moveTo(1, Offset(width * 0.7f, height * 0.5f))
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertTrue(vm.pendingInk.isEmpty())
        assertNotNull(vm.annotateMode)
    }

    private companion object {
        const val PAGE_W = 300
        const val PAGE_H = 400
    }
}
