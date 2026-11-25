package dev.kern.pdfbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The page-number conversion and answer reading of the session wrapper. Robolectric supplies org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KernkraftSessionTest {

    @Test
    fun a_step_gives_changed_pages_from_zero() {
        val step = KernkraftSession.parseStep(
            JSONObject(
                """{"ok":true,"revision":3,"pages":5,"changed_pages":[1,4,5],"can_undo":true,"can_redo":false,"modified":true}""",
            ),
        )
        assertEquals(listOf(0, 3, 4), step.changedPages)
        assertEquals(5, step.pageCount)
        assertEquals(3L, step.revision)
        assertTrue(step.canUndo)
        assertFalse(step.canRedo)
        assertTrue(step.modified)
    }

    @Test
    fun a_step_without_changed_pages_has_none() {
        val step = KernkraftSession.parseStep(
            JSONObject("""{"ok":true,"revision":0,"pages":2,"changed_pages":[],"can_undo":false,"can_redo":false,"modified":false}"""),
        )
        assertEquals(emptyList<Int>(), step.changedPages)
    }

    @Test
    fun a_rotation_is_sent_with_pages_from_one() {
        val json = PdfOp.Rotate(listOf(0, 2), 90).toJson()
        assertEquals("rotate", json.getString("op"))
        assertEquals(90, json.getInt("degrees"))
        assertEquals("[1,3]", json.getJSONArray("pages").toString())
    }

    @Test
    fun rotating_every_page_sends_no_pages() {
        assertEquals("[]", PdfOp.Rotate(emptyList(), 180).toJson().getJSONArray("pages").toString())
    }

    @Test
    fun a_reorder_is_sent_from_one() {
        assertEquals("[3,1,2]", PdfOp.Reorder(listOf(2, 0, 1)).toJson().getJSONArray("order").toString())
    }

    @Test
    fun a_removal_is_sent_from_one() {
        val json = PdfOp.Remove(listOf(4)).toJson()
        assertEquals("remove", json.getString("op"))
        assertEquals("[5]", json.getJSONArray("pages").toString())
    }

    @Test
    fun an_error_answer_becomes_an_exception_with_its_kind() {
        val failure = runCatching {
            KernkraftPdf.answerOrThrow(JSONObject("""{"ok":false,"kind":"RebuiltNotEditable","message":"rebuilt"}"""))
        }.exceptionOrNull() as PdfEngineException
        assertEquals("RebuiltNotEditable", failure.kind)
        assertEquals("rebuilt", failure.message)
        assertTrue(failure.needsCompactedCopy)
    }

    @Test
    fun other_errors_do_not_offer_a_compacted_copy() {
        val failure = runCatching {
            KernkraftPdf.answerOrThrow(JSONObject("""{"ok":false,"kind":"WrongPassword","message":"no"}"""))
        }.exceptionOrNull() as PdfEngineException
        assertFalse(failure.needsCompactedCopy)
    }

    @Test
    fun text_lines_keep_their_boxes_and_drop_blank_lines() {
        val lines = KernkraftSession.parseLines(
            JSONObject(
                """{"ok":true,"revision":0,"tagged":false,"blocks":[],"lines":[
                    {"block":0,"text":"Hello","rect":[0.1,0.2,0.5,0.25],"glyphs":[]},
                    {"block":0,"text":"  ","rect":[0.1,0.3,0.2,0.35],"glyphs":[]},
                    {"block":1,"text":"World","rect":[0.1,0.4,0.6,0.45],"glyphs":[]}]}""",
            ),
        )
        assertEquals(listOf("Hello", "World"), lines.map { it.text })
        assertEquals(PdfBox(0.1f, 0.2f, 0.5f, 0.25f), lines[0].box)
    }

    @Test
    fun a_page_without_text_has_no_lines() {
        val lines = KernkraftSession.parseLines(JSONObject("""{"ok":true,"revision":0,"tagged":false,"blocks":[],"lines":[]}"""))
        assertEquals(emptyList<PdfTextLineBox>(), lines)
    }

    @Test
    fun a_match_spanning_two_lines_has_two_boxes() {
        val found = KernkraftSession.parseMatches(
            JSONObject("""{"ok":true,"matches":[{"rects":[[0.1,0.1,0.9,0.15],[0.1,0.16,0.3,0.21]]},{"rects":[[0.5,0.5,0.6,0.55]]}]}"""),
        )
        assertEquals(listOf(2, 1), found.map { it.boxes.size })
        assertEquals(PdfBox(0.1f, 0.16f, 0.3f, 0.21f), found[0].boxes[1])
    }

    @Test
    fun links_give_an_address_or_a_page_from_zero_and_skip_the_unresolved() {
        val links = KernkraftSession.parseLinks(
            JSONObject(
                """{"ok":true,"links":[
                    {"rects":[[0.1,0.1,0.2,0.2]],"uri":"https://example.org"},
                    {"rects":[[0.3,0.3,0.4,0.4]],"page":3,"x":0.0,"y":0.0,"zoom":null},
                    {"rects":[[0.5,0.5,0.6,0.6]]}]}""",
            ),
        )
        assertEquals(2, links.size)
        assertEquals("https://example.org", (links[0] as PdfEngineLink.Uri).uri)
        assertEquals(2, (links[1] as PdfEngineLink.Page).page)
    }
}
