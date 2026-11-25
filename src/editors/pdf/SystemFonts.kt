package dev.kern.editors.pdf

import dev.kern.pdfbridge.KernkraftPdf
import java.io.File
import java.io.IOException

/**
 * The font files of the system, handed to the engine once for the whole process so that text it sets in a
 * face of its own (an edit of text whose font cannot show the new letters) has faces to choose from.
 * The engine reads family, style and weight from the files; this only finds them.
 */
internal object SystemFonts {

    private val FOLDERS = listOf("/system/fonts", "/system_ext/fonts", "/product/fonts", "/vendor/fonts")
    private val EXTENSIONS = setOf("ttf", "otf", "ttc")

    /** The font files directly inside [folders], sorted so the engine is given them in a fixed order. */
    fun filesIn(folders: List<File>): List<String> =
        folders.flatMap { folder -> folder.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            .map { it.path }
            .sorted()

    private var registered = false

    /**
     * Hands the engine the system's fonts if that has not been done in this process. Gives why it could not, or
     * null when the fonts are in; a failure is tried again at the next call. Only the changing of text needs the
     * fonts, so a failure is for the caller to tell the user about when they change text, not to stop a file opening.
     */
    @Synchronized
    fun register(folders: List<File> = FOLDERS.map(::File)): String? {
        if (registered) return null
        return try {
            KernkraftPdf.setSystemFonts(filesIn(folders))
            registered = true
            null
        } catch (e: IOException) {
            e.message ?: "the engine could not index them"
        }
    }
}
