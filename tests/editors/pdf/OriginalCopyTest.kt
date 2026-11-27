package dev.kern.editors.pdf

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Naming and writing the untouched copy kept before the first edit. */
class OriginalCopyTest {

    @Test
    fun the_stem_drops_the_pdf_extension_in_either_case() {
        assertEquals("report", OriginalCopy.stem("report.pdf"))
        assertEquals("report", OriginalCopy.stem("report.PDF"))
        assertEquals("a.b", OriginalCopy.stem("a.b.pdf"))
        assertEquals("notes", OriginalCopy.stem("notes"))
    }

    @Test
    fun an_empty_stem_gets_a_name() {
        assertEquals("document", OriginalCopy.stem(".pdf"))
    }

    @Test
    fun the_first_copy_is_named_original() {
        assertEquals("a (original).pdf", OriginalCopy.name("a") { false })
    }

    @Test
    fun taken_names_count_up_from_two() {
        val taken = setOf("a (original).pdf", "a (original 2).pdf")
        assertEquals("a (original 3).pdf", OriginalCopy.name("a") { it in taken })
    }

    @Test
    fun a_free_slot_below_a_taken_one_is_used() {
        assertEquals("a (original).pdf", OriginalCopy.name("a") { it == "a (original 2).pdf" })
    }

    @Test
    fun a_writable_folder_gets_the_copy_next_to_the_file() {
        val dir = Files.createTempDirectory("kern-copy").toFile()
        try {
            File(dir, "a (original).pdf").writeText("x")
            val plan = OriginalCopy.plan("a.pdf", File(dir, "a.pdf"))
            assertEquals(CopyDestination.NextToFile(dir, "a (original 2).pdf"), plan)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun a_file_with_no_folder_goes_through_the_picker() {
        assertEquals(CopyDestination.Picker("a (original).pdf"), OriginalCopy.plan("a.pdf", null))
    }

    @Test
    fun a_missing_folder_goes_through_the_picker() {
        assertEquals(CopyDestination.Picker("a (original).pdf"), OriginalCopy.plan("a.pdf", File("/no/such/folder/a.pdf")))
    }

    @Test
    fun writing_skips_a_name_taken_meanwhile_and_never_replaces_a_file() {
        val dir = Files.createTempDirectory("kern-copy").toFile()
        try {
            val existing = File(dir, "a (original).pdf").apply { writeText("keep me") }
            val written = OriginalCopy.write(dir, "a") { ByteArrayInputStream("new".toByteArray()) }
            assertEquals("a (original 2).pdf", written.name)
            assertArrayEquals("new".toByteArray(), written.readBytes())
            assertEquals("keep me", existing.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun a_failed_write_leaves_no_partial_copy() {
        val dir = Files.createTempDirectory("kern-copy").toFile()
        try {
            val failure = runCatching {
                OriginalCopy.write(dir, "a") { throw java.io.IOException("source gone") }
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(0, dir.listFiles()?.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
