package dev.kern.editors.pdf

import java.io.File

/**
 * The folders the edit sessions of this process hold open, and the clearing of every other folder
 * beside them.
 *
 * A session deletes its folder when it closes. One that never closes (the process was killed, or
 * crashed) leaves its folder, with a copy of the user's document in it, in the cache. Only one
 * process of the app runs at a time, so a folder no session of this process holds belongs to a
 * process that has ended and is safe to delete, whatever its age.
 */
internal object SessionFolders {

    private val live = mutableSetOf<File>()

    /**
     * Marks [dir] as held, then deletes everything beside it that no session of this process holds.
     * Gives the names of what could not be deleted, empty when everything was.
     */
    fun open(dir: File): List<String> = synchronized(live) {
        val held = dir.absoluteFile
        live += held
        held.parentFile?.listFiles().orEmpty()
            .filter { it.absoluteFile !in live }
            .filterNot { it.deleteRecursively() }
            .map { it.name }
    }

    /** Lets [dir] go. Its owner deletes it first, so a sweep never runs into a folder still being deleted. */
    fun close(dir: File) {
        synchronized(live) { live -= dir.absoluteFile }
    }

    /** What to tell the user when [left] working copies of earlier sessions could not be deleted. */
    fun leftoverMessage(left: List<String>): String =
        "Kern could not delete ${left.size} working ${if (left.size == 1) "copy" else "copies"} left by an " +
            "earlier session. Clearing Kern's cache in the system settings removes them."
}
