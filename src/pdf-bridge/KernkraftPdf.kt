// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Sricharan Suresh (github.com/verycareful)

package dev.kern.pdfbridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin side of the kernkraft native PDF engine. Each `nativeXxx` declaration is
 * bound by name to the `Java_dev_kern_pdfbridge_KernkraftPdf_nativeXxx` export of
 * `libkernkraft_jni.so`, built from the `kernkraft-jni` crate of
 * verycareful/kernkraft (see README.md in this folder).
 *
 * Every call is one JSON object in and one JSON object out. The answer carries
 * `"ok":true` and the call's fields, or `"ok":false` with `kind` and `message`.
 * These operations work on plain filesystem paths: the caller copies the picked
 * documents into the app cache and passes absolute paths. An output is a new file;
 * a path that already exists is refused rather than replaced.
 *
 * The library loads on first use. [available] reports whether it is bundled, so the
 * UI can say so when a build was made without it.
 */
object KernkraftPdf {

    /** Outcome of a bridge call: the output paths it made, or why it failed. */
    sealed interface Result {
        data class Success(val outputPaths: List<String>) : Result

        /** [kind] is the engine's error name (for example `WrongPassword`), when it gave one. */
        data class Failure(val message: String, val kind: String? = null) : Result
    }

    val available: Boolean by lazy {
        runCatching { System.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Merges [sourcePaths], in order, into the new file [outputPath]. */
    fun merge(sourcePaths: List<String>, outputPath: String): Result {
        val sources = JSONArray()
        sourcePaths.forEach { sources.put(JSONObject().put("path", it)) }
        val request = JSONObject().put("sources", sources).put("out", outputPath)
        return call { nativeMerge(request.toString()) }
    }

    /** Writes the 1-based [pages] of [sourcePath], in that order, as the new file [outputPath]. */
    fun extract(sourcePath: String, pages: List<Int>, outputPath: String): Result {
        val request = JSONObject()
            .put("path", sourcePath)
            .put("pages", JSONArray(pages))
            .put("out", outputPath)
        return call { nativeExtract(request.toString()) }
    }

    private inline fun call(block: () -> String): Result {
        if (!available) return Result.Failure("PDF engine ($LIBRARY_FILE) is not bundled in this build.")
        return runCatching { parseResult(block()) }
            .getOrElse { Result.Failure(it.message ?: "Native call failed") }
    }

    /** Reads the engine's answer to a call that makes files. */
    internal fun parseResult(json: String): Result {
        val answer = JSONObject(json)
        if (!answer.optBoolean("ok", false)) {
            val kind = answer.optString("kind").takeIf { it.isNotEmpty() }
            val message = answer.optString("message").takeIf { it.isNotEmpty() }
            return Result.Failure(message ?: kind ?: "Unknown error", kind)
        }
        val paths = answer.optJSONArray("paths")
        return Result.Success(List(paths?.length() ?: 0) { paths!!.getString(it) })
    }

    /** The session natives, named as in [dispatch]. */
    internal enum class Native { Open, Close, Text, Search, Links, Apply, Undo, Redo, AppendSince, ConfirmAppended, Compact }

    /**
     * Sends [request] to [native] and returns the engine's answer, or throws a
     * [PdfEngineException] when it says `ok:false`, when the library is not bundled, or when the
     * answer is not JSON.
     */
    internal fun callNative(native: Native, request: JSONObject): JSONObject {
        if (!available) throw PdfEngineException(null, "PDF engine ($LIBRARY_FILE) is not bundled in this build.")
        val answer = try {
            JSONObject(dispatch(native, request.toString()))
        } catch (e: org.json.JSONException) {
            throw PdfEngineException(null, e.message ?: "The PDF engine gave an unreadable answer.")
        }
        return answerOrThrow(answer)
    }

    internal fun answerOrThrow(answer: JSONObject): JSONObject {
        if (answer.optBoolean("ok", false)) return answer
        val kind = answer.optString("kind").takeIf { it.isNotEmpty() }
        val message = answer.optString("message").takeIf { it.isNotEmpty() }
        throw PdfEngineException(kind, message ?: kind ?: "Unknown error")
    }

    private fun dispatch(native: Native, request: String): String = when (native) {
        Native.Open -> nativeOpen(request)
        Native.Close -> nativeClose(request)
        Native.Text -> nativeText(request)
        Native.Search -> nativeSearch(request)
        Native.Links -> nativeLinks(request)
        Native.Apply -> nativeApply(request)
        Native.Undo -> nativeUndo(request)
        Native.Redo -> nativeRedo(request)
        Native.AppendSince -> nativeAppendSince(request)
        Native.ConfirmAppended -> nativeConfirmAppended(request)
        Native.Compact -> nativeCompact(request)
    }

    @JvmStatic private external fun nativeMerge(request: String): String

    @JvmStatic private external fun nativeExtract(request: String): String

    @JvmStatic private external fun nativeOpen(request: String): String

    @JvmStatic private external fun nativeClose(request: String): String

    @JvmStatic private external fun nativeText(request: String): String

    @JvmStatic private external fun nativeSearch(request: String): String

    @JvmStatic private external fun nativeLinks(request: String): String

    @JvmStatic private external fun nativeApply(request: String): String

    @JvmStatic private external fun nativeUndo(request: String): String

    @JvmStatic private external fun nativeRedo(request: String): String

    @JvmStatic private external fun nativeAppendSince(request: String): String

    @JvmStatic private external fun nativeConfirmAppended(request: String): String

    @JvmStatic private external fun nativeCompact(request: String): String

    private const val LIBRARY = "kernkraft_jni"
    private const val LIBRARY_FILE = "lib$LIBRARY.so"
}
