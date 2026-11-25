package dev.kern.editors.pdf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Where the untouched copy of a PDF is written before its first edit. */
sealed interface CopyDestination {
    /** The same folder as the PDF, as [name]. */
    data class NextToFile(val directory: File, val name: String) : CopyDestination

    /** A place the user picks, because Kern cannot write next to the PDF. */
    data class Picker(val suggestedName: String) : CopyDestination
}

/** What the first-edit dialog shows: where the copy goes, and what went wrong with the last try. */
data class EditWarning(val destination: CopyDestination, val problem: String? = null)

/**
 * The warning before the first edit of an open PDF (D6): editing may change the file, so the user
 * is offered a copy of the untouched file first. The edit waits until the user decides, and a
 * copy that fails or is cancelled never lets the edit through.
 *
 * [T] is the edit that is waiting.
 */
class FirstEditGate<T : Any>(private val plan: () -> CopyDestination) {

    /** The dialog to show, or null when none is open. */
    var warning by mutableStateOf<EditWarning?>(null)
        private set

    /** Whether edits go straight through: the user chose to continue, with or without a copy. */
    var cleared by mutableStateOf(false)
        private set

    private var pending: T? = null

    /** What the caller does next. */
    sealed interface Action<out T> {
        /** Do this edit now. */
        data class Run<T>(val edit: T) : Action<T>

        /** Write the copy next to the file, then call [FirstEditGate.copyDone] or [FirstEditGate.copyFailed]. */
        data class CopyNextToFile(val destination: CopyDestination.NextToFile) : Action<Nothing>

        /** Open the system save picker for the copy, then call [FirstEditGate.copyDone] or [FirstEditGate.pickerCancelled]. */
        data class OpenPicker(val suggestedName: String) : Action<Nothing>

        /** Nothing to do now: a dialog is open or the edit was dropped. */
        data object Wait : Action<Nothing>
    }

    /** An edit was asked for. */
    fun request(edit: T): Action<T> {
        if (cleared) return Action.Run(edit)
        pending = edit
        warning = EditWarning(plan())
        return Action.Wait
    }

    /** The user chose Save a copy first. */
    fun chooseCopy(): Action<T> = when (val destination = warning?.destination) {
        is CopyDestination.NextToFile -> Action.CopyNextToFile(destination)
        is CopyDestination.Picker -> Action.OpenPicker(destination.suggestedName)
        null -> Action.Wait
    }

    /** The copy is written (or saved through the picker): the edit goes ahead. */
    fun copyDone(): Action<T> = clear()

    /** Writing next to the file failed: ask again, this time through the picker. */
    fun copyFailed(reason: String) {
        val current = warning ?: return
        val name = (current.destination as? CopyDestination.NextToFile)?.name ?: (current.destination as CopyDestination.Picker).suggestedName
        warning = EditWarning(CopyDestination.Picker(name), "Could not save a copy next to the file: $reason")
    }

    /** The picker was closed without a copy: the dialog stays, the edit still waits. */
    fun pickerCancelled() {
        val current = warning ?: return
        warning = current.copy(problem = "No copy was saved.")
    }

    /** The user chose Edit without a copy. */
    fun chooseWithoutCopy(): Action<T> = clear()

    /** The user chose Cancel: the edit is dropped and nothing is cleared. */
    fun cancel() {
        pending = null
        warning = null
    }

    private fun clear(): Action<T> {
        val edit = pending
        pending = null
        warning = null
        cleared = true
        return if (edit == null) Action.Wait else Action.Run(edit)
    }
}

/** Names and writes the copy of a PDF kept before its first edit. */
object OriginalCopy {

    /** `<stem>.pdf` without its extension: the name the copies are built on. */
    fun stem(fileName: String): String = fileName.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "document" }

    /** `<stem> (original).pdf`, then `<stem> (original 2).pdf`, and so on, the first one [taken] does not claim. */
    fun name(stem: String, taken: (String) -> Boolean): String {
        var number = 1
        while (true) {
            val name = if (number == 1) "$stem (original).pdf" else "$stem (original $number).pdf"
            if (!taken(name)) return name
            number++
        }
    }

    /**
     * Where the copy of the PDF named [fileName], which lives at [file] when it is a plain file,
     * is written: next to it when that folder takes new files, else through the picker.
     */
    fun plan(fileName: String, file: File?): CopyDestination {
        val stem = stem(fileName)
        val directory = file?.parentFile
        if (directory != null && directory.isDirectory && directory.canWrite()) {
            return CopyDestination.NextToFile(directory, name(stem) { File(directory, it).exists() })
        }
        return CopyDestination.Picker(name(stem) { false })
    }

    /**
     * Writes [bytes] as a new file in [directory], named from [stem] the way [name] does. A name taken
     * meanwhile is skipped: the file is only ever created, never replaced. Gives the file written.
     */
    fun write(directory: File, stem: String, bytes: () -> java.io.InputStream): File {
        var taken = { _: String -> false }
        while (true) {
            val name = name(stem, taken)
            val file = File(directory, name)
            try {
                Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                    bytes().use { it.copyTo(out) }
                }
                return file
            } catch (e: FileAlreadyExistsException) {
                val tried = name
                val before = taken
                taken = { candidate -> candidate == tried || before(candidate) }
            } catch (e: IOException) {
                file.delete()
                throw e
            }
        }
    }
}
