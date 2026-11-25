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
}
