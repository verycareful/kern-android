package dev.kern.editors.pdf

import dev.kern.pdfbridge.KernkraftSession
import dev.kern.pdfbridge.PdfOp
import dev.kern.pdfbridge.PdfStep
import kotlinx.coroutines.CoroutineDispatcher
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * The editable state of one open PDF: a private copy of the document for the engine to read,
 * the engine's session over it, and the file the page renderer draws from.
 *
 * Three files live in [dir]:
 * - `session.pdf` is the copy the engine reads. Nothing writes to it, because the engine reads
 *   from it for as long as the session lives.
 * - `view.pdf` is what the renderer draws: the bytes the document holds on disk ([baseLength]
 *   of them, the same as the target's content when it was last read or saved) followed by the
 *   update that carries the edits made since. Its first [baseLength] bytes are therefore also
 *   the content to put back should a save go wrong.
 * - `append.tmp` holds the update on its way to `view.pdf` or to the target.
 *
 * Saves only ever append (7.5.6): the original bytes are never rewritten, except to put them back
 * after a failed save.
 *
 * Calls come one at a time and in order; the caller keeps them on one thread. [release] is called
 * before `view.pdf` is changed, so the renderer can let go of it first: a renderer reads its file
 * lazily and must not see it change.
 */
class PdfEditSession private constructor(
    private val dir: File,
    private val engine: KernkraftSession,
    private val target: DocumentTarget,
    private var base: FileStamp,
    private val now: () -> String,
) : Closeable {

    /** Set when `view.pdf` may not hold the disk bytes it should; the next rebuild reads them again. */
    private var viewStale = false

    val viewFile: File = File(dir, VIEW_NAME)
    private val updateFile: File = File(dir, UPDATE_NAME)

    /** What the last step of the engine answered. */
    var step: PdfStep = PdfStep(
        revision = 0, pageCount = engine.pageCount, changedPages = emptyList(),
        canUndo = false, canRedo = false, modified = false,
    )
        private set

    fun apply(op: PdfOp, release: () -> Unit): PdfStep = remember(engine.apply(op), release)

    fun undo(release: () -> Unit): PdfStep = remember(engine.undo(), release)

    fun redo(release: () -> Unit): PdfStep = remember(engine.redo(), release)

    private fun remember(answer: PdfStep, release: () -> Unit): PdfStep {
        step = answer
        rebuildView(release)
        return answer
    }

    /** Makes `view.pdf` the document as it stands: the disk bytes, then the update for the edits. */
    private fun rebuildView(release: () -> Unit) {
        val appended = engine.appendSince(base.length, updateFile, now())
        release()
        if (viewStale) repairView()
        truncateView(base.length)
        if (appended.appended > 0) appendTo(viewFile, updateFile)
        updateFile.delete()
    }

    /** The text of the document as it stands, read through the engine; its calls run on [dispatcher]. */
    fun textLayer(dispatcher: CoroutineDispatcher): PdfTextLayer = KernkraftTextLayer(engine, dispatcher)

    /** Writes the document as it stands as a new compacted file in the session folder and gives that file back. */
    fun compact(): File {
        val out = File(dir, COMPACT_NAME)
        out.delete()
        engine.compact(out)
        return out
    }

    /** How a [save] ended. */
    sealed interface SaveOutcome {
        /** The edits are appended to the document, which now has [length] bytes. */
        data class Saved(val length: Long) : SaveOutcome

        data object NothingToSave : SaveOutcome

        /** The document is not the one Kern opened; nothing was written. */
        data object ChangedOutside : SaveOutcome

        /** The save failed and the document holds what it held before. */
        data class Failed(val message: String) : SaveOutcome

        /** The save damaged the document and the content from before could not be put back. */
        data class Damaged(val message: String) : SaveOutcome
    }

    /**
     * Appends the edits to the target. The target is first checked against what Kern last saw of
     * it; the update is written once and the whole target is read back and compared with the
     * bytes it should hold. A target that does not hold them is rewritten with the content it had.
     */
    fun save(release: () -> Unit): SaveOutcome {
        // The engine keeps no record of what a save leaves behind when it is asked again with nothing
        // new, and then writes an update that only moves the modification date; so it is not asked.
        if (!step.modified) return SaveOutcome.NothingToSave
        val current = try {
            FileStamp.of(target.openRead())
        } catch (e: IOException) {
            return SaveOutcome.Failed("Could not read the file to check it before saving: ${e.message}")
        }
        if (current != base) return SaveOutcome.ChangedOutside
        // The restore after a failed write copies these bytes back, so they must be what the file holds.
        val kept = try {
            FileStamp.of(LimitedInput(viewFile.inputStream(), base.length))
        } catch (e: IOException) {
            return SaveOutcome.Failed("Could not check the working copy before saving: ${e.message}")
        }
        if (kept != base) return SaveOutcome.Failed("The working copy no longer matches the file, so nothing was written.")

        val appended = try {
            engine.appendSince(base.length, updateFile, now())
        } catch (e: IOException) {
            updateFile.delete()
            return SaveOutcome.Failed(e.message ?: "The PDF engine could not prepare the update.")
        }
        if (appended.appended == 0L) {
            updateFile.delete()
            return SaveOutcome.NothingToSave
        }
        if (appended.length != base.length + appended.appended) {
            updateFile.delete()
            return SaveOutcome.Failed("The PDF engine gave an update of an unexpected length.")
        }

        val expected = try {
            FileStamp.of(concatenated(viewFile, base.length, updateFile))
        } catch (e: IOException) {
            updateFile.delete()
            return SaveOutcome.Failed(e.message ?: "Could not read the update.")
        }
        val writeError = try {
            target.openAppend().use { out -> updateFile.inputStream().use { it.copyTo(out) } }
            null
        } catch (e: IOException) {
            e
        }
        val written = try {
            FileStamp.of(target.openRead())
        } catch (e: IOException) {
            null
        }

        if (writeError == null && written == expected) {
            val before = base.length
            return try {
                engine.confirmAppended(appended.length)
                base = expected
                step = step.copy(modified = false)
                viewStale = true
                release()
                truncateView(before)
                appendTo(viewFile, updateFile)
                viewStale = false
                SaveOutcome.Saved(expected.length)
            } finally {
                updateFile.delete()
            }
        }
        updateFile.delete()
        val reason = writeError?.message ?: "The file did not hold the saved content when read back."
        if (written == base) return SaveOutcome.Failed("Could not save: $reason")
        return restore(reason)
    }

    /** Puts the content the target had before the save back, and checks that it is back. */
    private fun restore(reason: String): SaveOutcome {
        val restored = try {
            target.openTruncate().use { out ->
                viewFile.inputStream().use { it.copyExactly(out, base.length) }
            }
            FileStamp.of(target.openRead())
        } catch (e: IOException) {
            return SaveOutcome.Damaged("Could not save ($reason), and putting the file back failed: ${e.message}")
        }
        return if (restored == base) {
            SaveOutcome.Failed("Could not save: $reason The file was put back as it was.")
        } else {
            SaveOutcome.Damaged("Could not save ($reason), and the file does not match what it held before.")
        }
    }

    override fun close() {
        engine.close()
        dir.deleteRecursively()
    }

    /** Cuts `view.pdf` back to [length] bytes; a file already shorter is an error, never padded. */
    private fun truncateView(length: Long) {
        RandomAccessFile(viewFile, "rw").use {
            if (it.length() < length) throw IOException("The working copy is shorter than the document.")
            it.setLength(length)
        }
    }

    /** Reads the disk bytes into `view.pdf` again, after a rebuild that did not finish. */
    private fun repairView() {
        target.openRead().use { input -> viewFile.outputStream().use { input.copyTo(it) } }
        val kept = FileStamp.of(viewFile.inputStream())
        if (kept != base) throw IOException("The working copy no longer matches the file.")
        viewStale = false
    }

    companion object {
        /**
         * Copies [target] into a new session in [dir] and opens the engine on the copy.
         * [now] gives the PDF date a save carries.
         */
        fun create(dir: File, target: DocumentTarget, now: () -> String): PdfEditSession {
            dir.mkdirs()
            val sessionFile = File(dir, SESSION_NAME)
            try {
                val stamp = copyStamped(target.openRead(), sessionFile)
                sessionFile.copyTo(File(dir, VIEW_NAME), overwrite = true)
                return PdfEditSession(dir, KernkraftSession.open(sessionFile), target, stamp, now)
            } catch (e: IOException) {
                dir.deleteRecursively()
                throw e
            }
        }

        private fun copyStamped(input: InputStream, to: File): FileStamp {
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            DigestInputStream(input, digest).use { read ->
                to.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = read.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        length += n
                    }
                }
            }
            return FileStamp(length, digest.digest().toHex())
        }

        private fun appendTo(file: File, extra: File) {
            java.io.FileOutputStream(file, true).use { out ->
                extra.inputStream().use { it.copyTo(out) }
            }
        }

        /** The first [prefix] bytes of [head] followed by all of [tail], as one stream. */
        private fun concatenated(head: File, prefix: Long, tail: File): InputStream {
            val first = head.inputStream().let { LimitedInput(it, prefix) }
            return java.io.SequenceInputStream(first, tail.inputStream())
        }

        private fun InputStream.copyExactly(out: OutputStream, count: Long) {
            val buffer = ByteArray(BUFFER_BYTES)
            var left = count
            while (left > 0) {
                val n = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw IOException("The saved copy is shorter than expected.")
                out.write(buffer, 0, n)
                left -= n
            }
        }

        private const val SESSION_NAME = "session.pdf"
        private const val VIEW_NAME = "view.pdf"
        private const val UPDATE_NAME = "append.tmp"
        private const val COMPACT_NAME = "compact.pdf"
        private const val BUFFER_BYTES = 64 * 1024
    }
}

/** Reads at most [limit] bytes of [source] and then reports its end. */
private class LimitedInput(private val source: InputStream, private var limit: Long) : InputStream() {
    override fun read(): Int {
        if (limit <= 0) return -1
        val b = source.read()
        if (b >= 0) limit--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) return -1
        val n = source.read(b, off, minOf(len.toLong(), limit).toInt())
        if (n > 0) limit -= n
        return n
    }

    override fun close() = source.close()
}
