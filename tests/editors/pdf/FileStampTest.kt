package dev.kern.editors.pdf

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The length and hash that tell whether a PDF changed behind Kern's back. */
class FileStampTest {

    private fun stamp(text: String) = FileStamp.of(ByteArrayInputStream(text.toByteArray()))

    @Test
    fun the_hash_is_the_sha256_of_the_content() {
        assertEquals(
            FileStamp(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            stamp("abc"),
        )
    }

    @Test
    fun an_empty_file_has_a_stamp() {
        assertEquals(FileStamp(0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), stamp(""))
    }

    @Test
    fun the_same_length_with_other_content_differs() {
        assertNotEquals(stamp("abc"), stamp("abd"))
    }

    @Test
    fun content_beyond_one_read_buffer_is_stamped_whole() {
        val big = "x".repeat(200_000)
        assertEquals(200_000L, stamp(big).length)
        assertEquals(stamp(big), stamp(big))
        assertNotEquals(stamp(big), stamp(big + "x"))
    }
}
