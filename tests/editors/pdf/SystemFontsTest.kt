package dev.kern.editors.pdf

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemFontsTest {

    @Test
    fun a_failure_to_hand_the_fonts_over_is_given_back_for_the_caller_to_tell_not_thrown() {
        // The engine is not loaded in a unit test, so the hand-over fails.
        assertNotNull(SystemFonts.register(emptyList()))
    }

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun only_font_files_are_found_in_a_fixed_order() {
        val a = temp.newFolder("a")
        val b = temp.newFolder("b")
        File(a, "Roboto.ttf").writeText("x")
        File(a, "readme.txt").writeText("x")
        File(a, "Noto.TTC").writeText("x")
        File(b, "Serif.otf").writeText("x")
        File(b, "sub").mkdir()
        File(File(b, "sub"), "Deep.ttf").writeText("x")
        val found = SystemFonts.filesIn(listOf(a, b, File(temp.root, "missing")))
        assertEquals(
            listOf(File(a, "Noto.TTC"), File(a, "Roboto.ttf"), File(b, "Serif.otf")).map { it.path },
            found,
        )
    }
}
