package dev.kern.pdfbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the engine's JSON answers become [KernkraftPdf.Result]. Robolectric supplies a working org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KernkraftPdfTest {

    @Test
    fun success_lists_the_paths_in_order() {
        val result = KernkraftPdf.parseResult("""{"ok":true,"paths":["/cache/a.pdf","/cache/b.pdf"]}""")
        assertEquals(KernkraftPdf.Result.Success(listOf("/cache/a.pdf", "/cache/b.pdf")), result)
    }

    @Test
    fun success_without_paths_is_an_empty_list() {
        assertEquals(KernkraftPdf.Result.Success(emptyList()), KernkraftPdf.parseResult("""{"ok":true}"""))
    }

    @Test
    fun failure_carries_the_message_and_the_kind() {
        val result = KernkraftPdf.parseResult(
            """{"ok":false,"kind":"WrongPassword","message":"the password is wrong","path":"/cache/x.pdf"}""",
        )
        assertEquals(KernkraftPdf.Result.Failure("the password is wrong", "WrongPassword"), result)
    }

    @Test
    fun failure_without_a_message_falls_back_to_the_kind() {
        val result = KernkraftPdf.parseResult("""{"ok":false,"kind":"Exists"}""")
        assertEquals(KernkraftPdf.Result.Failure("Exists", "Exists"), result)
    }

    @Test
    fun an_answer_with_neither_ok_nor_error_fields_is_a_failure() {
        val result = KernkraftPdf.parseResult("{}")
        assertTrue(result is KernkraftPdf.Result.Failure)
        assertEquals("Unknown error", (result as KernkraftPdf.Result.Failure).message)
    }

    @Test(expected = org.json.JSONException::class)
    fun text_that_is_not_json_is_rejected_for_the_caller_to_report() {
        KernkraftPdf.parseResult("not json")
    }
}
