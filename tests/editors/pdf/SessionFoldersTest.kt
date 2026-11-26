package dev.kern.editors.pdf

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/** The clearing of session folders that no session of this process holds. */
class SessionFoldersTest {

    private lateinit var root: File
    private val opened = mutableListOf<File>()

    @Before
    fun makeRoot() {
        root = Files.createTempDirectory("pdf-session").toFile()
    }

    @After
    fun dropRoot() {
        opened.forEach(SessionFolders::close)
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun folder(name: String, withFile: Boolean = true): File = File(root, name).apply {
        mkdirs()
        if (withFile) File(this, "session.pdf").writeText("%PDF-1.7")
    }

    private fun open(dir: File): List<String> = SessionFolders.open(dir).also { opened += dir }

    @Test
    fun a_folder_left_by_an_ended_process_is_deleted() {
        val orphan = folder("orphan")
        assertEquals(emptyList<String>(), open(File(root, "mine")))
        assertFalse(orphan.exists())
    }

    @Test
    fun a_stray_file_beside_the_folders_is_deleted() {
        val stray = File(root, "stray.tmp").apply { writeText("x") }
        open(File(root, "mine"))
        assertFalse(stray.exists())
    }

    @Test
    fun a_folder_another_open_session_holds_is_kept() {
        val other = folder("other")
        open(other)
        open(File(root, "mine"))
        assertTrue(File(other, "session.pdf").exists())
    }

    @Test
    fun the_new_session_folder_is_kept_when_it_already_exists() {
        val mine = folder("mine")
        open(mine)
        assertTrue(File(mine, "session.pdf").exists())
    }

    @Test
    fun a_folder_let_go_is_deleted_by_the_next_session() {
        val first = folder("first")
        open(first)
        SessionFolders.close(first)
        open(File(root, "second"))
        assertFalse(first.exists())
    }

    @Test
    fun a_folder_that_cannot_be_deleted_is_named() {
        assumeFalse("root deletes from a read-only folder", System.getProperty("user.name") == "root")
        val stuck = folder("stuck")
        assertTrue(stuck.setWritable(false))
        assertEquals(listOf("stuck"), open(File(root, "mine")))
        assertTrue(stuck.exists())
    }

    @Test
    fun the_message_counts_the_copies_left() {
        assertEquals(
            "Kern could not delete 1 working copy left by an earlier session. " +
                "Clearing Kern's cache in the system settings removes them.",
            SessionFolders.leftoverMessage(listOf("a")),
        )
        assertTrue(SessionFolders.leftoverMessage(listOf("a", "b")).startsWith("Kern could not delete 2 working copies"))
    }
}
