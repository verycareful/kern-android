package dev.kern.editors.pdf

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** The document a PDF was opened from: where its bytes are read and where saves are written. */
interface DocumentTarget {
    fun openRead(): InputStream

    /** Opens the document so that writes go after its current end. */
    fun openAppend(): OutputStream

    /** Opens the document with its content cleared. */
    fun openTruncate(): OutputStream
}

/** A document that is a plain file. */
open class FileDocumentTarget(private val file: File) : DocumentTarget {
    override fun openRead(): InputStream = FileInputStream(file)
    override fun openAppend(): OutputStream = FileOutputStream(file, true)
    override fun openTruncate(): OutputStream = FileOutputStream(file, false)
}

/** A document behind a content:// address; every access is asked of the provider. */
class ContentDocumentTarget(private val context: Context, private val uri: Uri) : DocumentTarget {
    override fun openRead(): InputStream = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Could not open $uri for reading")

    override fun openAppend(): OutputStream = context.contentResolver.openOutputStream(uri, "wa")
        ?: throw IOException("Could not open $uri for appending")

    override fun openTruncate(): OutputStream = context.contentResolver.openOutputStream(uri, "wt")
        ?: throw IOException("Could not open $uri for writing")

    companion object {
        /** The target for [uri]: a file for file:// addresses, the provider for anything else. */
        fun forUri(context: Context, uri: Uri): DocumentTarget {
            if (uri.scheme == "file") {
                val path = uri.path ?: throw IOException("Invalid file URI $uri")
                return FileDocumentTarget(File(path))
            }
            return ContentDocumentTarget(context, uri)
        }
    }
}

/** The length and SHA-256 of a document, what tells whether it changed behind Kern's back. */
data class FileStamp(val length: Long, val sha256: String) {
    companion object {
        /** Reads [input] to its end and stamps what it held. */
        fun of(input: InputStream): FileStamp = input.use { stream ->
            val digest = MessageDigest.getInstance(SHA_256)
            var length = 0L
            val buffer = ByteArray(BUFFER_BYTES)
            DigestInputStream(stream, digest).use {
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    length += n
                }
            }
            FileStamp(length, digest.digest().toHex())
        }

        private const val SHA_256 = "SHA-256"
        private const val BUFFER_BYTES = 64 * 1024
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
