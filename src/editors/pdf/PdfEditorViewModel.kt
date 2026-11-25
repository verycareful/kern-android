package dev.kern.editors.pdf

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kern.pdfbridge.KernkraftPdf
import dev.kern.pdfbridge.MarkupKind
import dev.kern.pdfbridge.PdfBox
import dev.kern.pdfbridge.PdfColour
import dev.kern.pdfbridge.PdfDate
import dev.kern.pdfbridge.PdfEngineException
import dev.kern.pdfbridge.PdfOp
import dev.kern.pdfbridge.PdfPoint
import dev.kern.pdfbridge.PdfStep
import dev.kern.shared.find.FindMessage
import dev.kern.shared.find.FindState
import dev.kern.shared.find.FindTarget
import dev.kern.shared.io.DocumentIo
import dev.kern.shared.ui.ExternalLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * PDF viewer state: opens a PDF and pages through it read-only via the framework
 * [PdfDocument]. The page tools (merge, extract) run through the kernkraft native
 * engine, [KernkraftPdf]. Export writes a verbatim copy.
 */
class PdfEditorViewModel(app: Application) : AndroidViewModel(app), FindTarget {

    var fileName by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pageCount by mutableIntStateOf(0)
        private set

    private var uri: Uri? = null
    @Volatile private var document: PdfDocument? = null
    private var started = false

    // Find

    override val find = FindState()

    /** The text of the document: set once the engine session is open, and the same layer through every edit. */
    private var textLayer by mutableStateOf<PdfTextLayer?>(null)

    /** Why there is no text layer, when the session could not be opened; null while it is still opening. */
    private var textFailure by mutableStateOf<String?>(null)

    /** Bumped by every edit, undo and redo: what was found or read from the text is out of date. */
    private var textRevision by mutableIntStateOf(0)

    /** Matches of the current search across every page, in page order. */
    var pdfMatches by mutableStateOf<List<PdfTextMatch>>(emptyList())
        private set

    private val searchQuery: String get() = if (find.isOpen) find.query else ""

    override val matchCount: Int get() = if (searchQuery.isEmpty()) 0 else pdfMatches.size
    override val supportsOptions: Boolean get() = false
    override val unavailableReason: String?
        get() = if (loading || error != null || textLayer != null) null else textFailure ?: PREPARING_TEXT

    val currentMatch: PdfTextMatch? get() = if (searchQuery.isEmpty()) null else pdfMatches.getOrNull(find.currentIn(pdfMatches.size))

    // Text selection

    /** Whether page text can be selected here: the engine has opened the document. */
    val canSelectText: Boolean get() = textLayer != null

    private val lineCache = HashMap<Int, List<PdfTextLine>>()

    /** The text lines of [page] for the selectable overlay; empty where there is no text layer. */
    suspend fun pageLines(page: Int): List<PdfTextLine> {
        val revision = textRevision
        val layer = textLayer ?: return emptyList()
        lineCache[page]?.let { return it }
        val lines = try {
            layer.lines(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        // An edit that came while the engine read may have changed the page: only keep what is still current.
        if (textRevision == revision) lineCache[page] = lines
        return lines
    }

    // Links

    /** A web, mail or phone link the user tapped, waiting for them to confirm opening it. */
    var pendingLink by mutableStateOf<String?>(null)
        private set

    /** A page an internal link asked to show; the screen scrolls there and clears it. */
    var linkTargetPage by mutableStateOf<Int?>(null)
        private set

    private val linkCache = HashMap<Int, List<PdfLink>>()

    /**
     * Handles a tap at [point] on [page]: follows a link there, if there is one.
     * A link out of the document is only offered, never opened, until the user confirms.
     */
    fun tapPage(page: Int, point: PagePoint) {
        val layer = textLayer
        if (layer == null) return
        val revision = textRevision
        viewModelScope.launch {
            val links = linkCache[page] ?: try {
                layer.links(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }.also { if (textRevision == revision) linkCache[page] = it }
            when (val link = links.at(point)) {
                is PdfLink.External -> if (ExternalLinks.isOpenable(link.uri)) pendingLink = link.uri
                is PdfLink.GoTo -> if (link.page in 0 until pageCount) linkTargetPage = link.page
                null -> Unit
            }
        }
    }

    fun dismissLink() { pendingLink = null }

    fun consumeLinkTarget() { linkTargetPage = null }

    init {
        // Searches every page off the main thread, a moment after typing stops; a new
        // query cancels the search still running for the old one.
        viewModelScope.launch {
            snapshotFlow { Triple(searchQuery, textLayer, textRevision) }.collectLatest { (query, layer) ->
                pdfMatches = emptyList()
                if (query.isEmpty() || layer == null) return@collectLatest
                delay(SEARCH_DEBOUNCE_MS)
                find.message = SEARCHING
                var unreadable = 0
                val found = (0 until pageCount).flatMap { page ->
                    ensureActive()
                    try {
                        layer.search(page, query)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        unreadable++
                        emptyList()
                    }
                }
                pdfMatches = found
                if (find.message == SEARCHING) find.message = if (unreadable > 0) unreadPagesMessage(unreadable) else null
                find.reveal()
            }
        }
    }

    // --- Editing -------------------------------------------------------------------
    // The edit session starts at the first edit, after the warning: a PDF that is only read is
    // read from where it is, without a copy.

    /** Whether there are edits not yet saved into the file. */
    var dirty by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** True while an edit, undo, redo or save is running. */
    var editBusy by mutableStateOf(false)
        private set

    /** Bumped when the page count changes: every page picture is out of date. */
    var documentEpoch by mutableIntStateOf(0)
        private set

    private val pageVersions = mutableStateMapOf<Int, Int>()

    /** Changes whenever the picture of [index] does, for the page view to draw again. */
    fun pageVersion(index: Int): Int = pageVersions[index] ?: 0

    private val gate = FirstEditGate<PdfOp> {
        OriginalCopy.plan(fileName, uri?.let(DocumentIo::localFile))
    }

    /** The dialog that asks before the first edit, or null. */
    val editWarning: EditWarning? get() = gate.warning

    /** The name the system save picker suggests for the untouched copy, while the screen should show it. */
    var copyPickerRequest by mutableStateOf<String?>(null)
        private set

    @Volatile private var session: PdfEditSession? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sessionDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val sessionDir: File by lazy {
        File(File(getApplication<Application>().cacheDir, "pdf-session"), UUID.randomUUID().toString())
    }

    /** The edit session, opened if it is not yet. Runs on [sessionDispatcher]. */
    private fun sessionOrOpen(): PdfEditSession = session ?: PdfEditSession.create(
        sessionDir,
        ContentDocumentTarget.forUri(getApplication(), uri ?: throw IOException("No file was provided.")),
        PdfDate::now,
    ).also { session = it }

    /**
     * Opens the engine session in the background once the PDF is showing, so that find, selection and
     * links are there for the whole life of the document and the first edit finds the session open.
     */
    private fun prepareText() {
        if (!KernkraftPdf.available) {
            textFailure = "Search, selection and links are unavailable: the PDF engine is not bundled in this build."
            return
        }
        viewModelScope.launch {
            withContext(sessionDispatcher) { runCatching { sessionOrOpen() } }
                .onSuccess { publishText() }
                .onFailure { textFailure = "Search, selection and links are unavailable: ${it.message ?: "the PDF engine could not open this file."}" }
        }
    }

    /** Makes the open session's text the document's text layer, if that has not been done. */
    private fun publishText() {
        val open = session ?: return
        if (textLayer != null) return
        textLayer = open.textLayer(sessionDispatcher)
        textFailure = null
    }

    /** Asks for [op] to be applied. The first edit of an open PDF waits for the warning. */
    fun requestEdit(op: PdfOp) {
        if (editBusy || loading || error != null) return
        if (!KernkraftPdf.available) {
            toolError = "The PDF engine is not bundled in this build, so this PDF cannot be edited."
            return
        }
        handle(gate.request(op))
    }

    // --- Markup and ink -------------------------------------------------------------

    /** What a touch on a page does: scroll and select as usual, mark text, or draw. */
    enum class AnnotateMode { None, Markup, Ink }

    var annotateMode by mutableStateOf(AnnotateMode.None)
        private set
    var markupKind by mutableStateOf(MarkupKind.Highlight)
        private set
    var markColour by mutableStateOf(MarkColour.Yellow)
        private set

    /** The pen's colour, kept apart from the marker's: black ink and yellow highlight are the usual pair. */
    var inkColour by mutableStateOf(MarkColour.Black)
        private set

    /** The colour the bar shows as chosen: the pen's while drawing, the marker's otherwise. */
    val activeColour: MarkColour get() = if (annotateMode == AnnotateMode.Ink) inkColour else markColour
    var inkWidth by mutableStateOf(InkWidth.Medium)
        private set

    /** The text chosen to be marked, waiting for the user to apply it. */
    var markupSelection by mutableStateOf<MarkupSelection?>(null)
        private set

    fun startMarkup() = enter(AnnotateMode.Markup)

    fun startInk() = enter(AnnotateMode.Ink)

    fun finishAnnotating() = enter(AnnotateMode.None)

    private fun enter(mode: AnnotateMode) {
        annotateMode = mode
        markupSelection = null
    }

    fun chooseMarkupKind(kind: MarkupKind) { markupKind = kind }

    fun chooseColour(colour: MarkColour) {
        if (annotateMode == AnnotateMode.Ink) inkColour = colour else markColour = colour
    }

    fun chooseInkWidth(width: InkWidth) { inkWidth = width }

    fun selectMarkup(selection: MarkupSelection?) { markupSelection = selection }

    /** The glyphs of [page], for choosing text to mark. */
    suspend fun pageGlyphs(page: Int): PageGlyphs = PageGlyphs.of(pageLines(page))

    /** Marks the chosen text with the chosen kind and colour. */
    fun applyMarkup() {
        val selection = markupSelection ?: return
        viewModelScope.launch {
            val quads = selection.quads(pageGlyphs(selection.page))
            if (quads.isEmpty()) return@launch
            requestEdit(
                PdfOp.AddMarkup(
                    kind = markupKind,
                    page = selection.page,
                    quads = quads.map { PdfBox(it.left, it.top, it.right, it.bottom) },
                    colour = markColour.toEngine(),
                    modified = PdfDate.now(),
                ),
            )
        }
    }

    /** Strokes drawn on [page] that are not in the document yet; they stay on screen until they are. */
    data class PendingInk(val page: Int, val strokes: List<List<PagePoint>>, val pageAspect: Float)

    var pendingInk by mutableStateOf<List<PendingInk>>(emptyList())
        private set

    /** The pending ink the running or waiting edit holds. */
    private var inkSent: PendingInk? = null

    /**
     * Adds a [stroke] drawn on [page] (whose height over width is [pageAspect]). It is one ink
     * annotation, one undo step. A stroke drawn while an edit runs, or while the first-edit warning
     * waits, stays on screen and goes in right after that edit as an edit of its own; strokes that
     * wait together on one page go in as one.
     */
    fun addInkStroke(page: Int, stroke: List<PagePoint>, pageAspect: Float) {
        if (stroke.isEmpty()) return
        val at = pendingInk.indexOfFirst { it.page == page }
        pendingInk = if (at < 0) {
            pendingInk + PendingInk(page, listOf(stroke), pageAspect)
        } else {
            pendingInk.toMutableList().also { it[at] = it[at].copy(strokes = it[at].strokes + listOf(stroke)) }
        }
        sendInk()
    }

    private fun sendInk() {
        if (editBusy || inkSent != null || loading || error != null) return
        val entry = pendingInk.firstOrNull() ?: return
        val strokes = entry.strokes.map { InkStroke.smooth(it, entry.pageAspect) }.filter { it.isNotEmpty() }
        inkSent = entry
        requestEdit(
            PdfOp.AddInk(
                page = entry.page,
                strokes = strokes.map { stroke -> stroke.map { PdfPoint(it.x.toDouble(), it.y.toDouble()) } },
                widthPoints = inkWidth.points,
                colour = inkColour.toEngine(),
                modified = PdfDate.now(),
            ),
        )
        // requestEdit has said why when there is no engine; the strokes cannot go anywhere.
        if (!KernkraftPdf.available) dropPendingInk()
    }

    /** The ink of the edit that just went in is in the document: take it off the pending list. */
    private fun inkAdopted() {
        val sent = inkSent ?: return
        inkSent = null
        pendingInk = pendingInk.mapNotNull { entry ->
            if (entry.page != sent.page) entry
            else entry.copy(strokes = entry.strokes.drop(sent.strokes.size)).takeIf { it.strokes.isNotEmpty() }
        }
    }

    private fun dropPendingInk() {
        inkSent = null
        pendingInk = emptyList()
    }

    /** The width of [page] in PDF points, which the width of the pen is measured in; null when it cannot be read. */
    suspend fun pageWidthPoints(page: Int): Int? = withContext(Dispatchers.IO) { document?.let { runCatching { it.pageWidthPoints(page) }.getOrNull() } }

    private fun MarkColour.toEngine() = PdfColour(red, green, blue)

    fun chooseCopyFirst() = handle(gate.chooseCopy())

    fun editWithoutCopy() = handle(gate.chooseWithoutCopy())

    fun cancelEdit() {
        gate.cancel()
        dropPendingInk()
    }

    /** The save picker answered for the untouched copy: [target] is where it goes, or null when it was closed. */
    fun copyPicked(target: Uri?) {
        copyPickerRequest = null
        if (target == null) { gate.pickerCancelled(); return }
        val source = uri ?: return
        val ctx = getApplication<Application>()
        runCopy(
            write = {
                ContentDocumentTarget.forUri(ctx, source).openRead().use { input ->
                    (ctx.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Could not open $target for writing"))
                        .use { input.copyTo(it) }
                }
            },
            onFailure = { gate.pickerCancelled() },
        )
    }

    fun undo() {
        if (canUndo) launchEdit { s -> s.undo(::releaseDocument) }
    }

    fun redo() {
        if (canRedo) launchEdit { s -> s.redo(::releaseDocument) }
    }

    private fun handle(action: FirstEditGate.Action<PdfOp>) {
        when (action) {
            is FirstEditGate.Action.Run -> launchEdit { s -> s.apply(action.edit, ::releaseDocument) }
            is FirstEditGate.Action.CopyNextToFile -> writeCopyNextToFile(action.destination)
            is FirstEditGate.Action.OpenPicker -> copyPickerRequest = action.suggestedName
            FirstEditGate.Action.Wait -> Unit
        }
    }

    private fun writeCopyNextToFile(destination: CopyDestination.NextToFile) {
        val source = uri ?: return
        val ctx = getApplication<Application>()
        runCopy(
            write = {
                OriginalCopy.write(destination.directory, OriginalCopy.stem(fileName)) {
                    ContentDocumentTarget.forUri(ctx, source).openRead()
                }
            },
            onFailure = { gate.copyFailed(it) },
        )
    }

    /** Runs [write] off the main thread; on success the edit that waits goes ahead, on failure it never does. */
    private fun runCopy(write: () -> Unit, onFailure: (reason: String) -> Unit) {
        editBusy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(write) }
            editBusy = false
            result.onSuccess { handle(gate.copyDone()) }
                .onFailure { onFailure(it.message ?: "The copy could not be written.") }
        }
    }

    /**
     * Runs [action] on the edit session, which is started if this is the first edit, and shows the
     * document it leaves. Everything touching the session's files happens on [sessionDispatcher].
     */
    private fun launchEdit(action: (PdfEditSession) -> PdfStep) {
        if (editBusy) return
        if (uri == null) return
        editBusy = true
        viewModelScope.launch {
            val result = withContext(sessionDispatcher) {
                runCatching {
                    val s = sessionOrOpen()
                    val step = action(s)
                    step to PdfDocument.open(s.viewFile)
                }
            }
            result.onSuccess { (step, doc) -> adopt(step, doc) }
                .onFailure { editFailed(it) }
            editBusy = false
            sendInk()
        }
    }

    private fun releaseDocument() {
        document?.close()
        document = null
    }

    /** Shows [doc], the document after [step], and drops what was read from the pages it changed. */
    private fun adopt(step: PdfStep, doc: PdfDocument) {
        val countChanged = step.pageCount != pageCount
        document = doc
        pageCount = step.pageCount
        markupSelection = null
        inkAdopted()
        textRevision++
        publishText()
        dirty = step.modified
        canUndo = step.canUndo
        canRedo = step.canRedo
        if (countChanged) {
            lineCache.clear()
            linkCache.clear()
            pageVersions.clear()
            documentEpoch++
        } else {
            for (page in step.changedPages) {
                lineCache.remove(page)
                linkCache.remove(page)
                pageVersions[page] = pageVersion(page) + 1
            }
        }
    }

    /** An edit failed: say why, and put the last good picture back if the failure took it away. */
    private suspend fun editFailed(failure: Throwable) {
        dropPendingInk()
        toolError = when {
            failure is PdfEngineException && failure.needsCompactedCopy ->
                "This PDF had to be repaired to be read, so Kern cannot edit it in place. ${failure.message}"
            else -> failure.message ?: "The edit failed."
        }
        val open = session
        if (document == null && open != null) {
            runCatching { withContext(sessionDispatcher) { PdfDocument.open(open.viewFile) } }
                .onSuccess { document = it }
        }
        open?.let { dirty = it.step.modified; canUndo = it.step.canUndo; canRedo = it.step.canRedo }
    }

    /** Appends the edits to the file. [onResult] gets whether it worked, and why not when it did not. */
    fun save(onResult: (ok: Boolean, message: String?) -> Unit) {
        val open = session
        if (open == null || !dirty) { onResult(true, null); return }
        if (editBusy) { onResult(false, "Wait for the edit in progress to finish."); return }
        editBusy = true
        viewModelScope.launch {
            val outcome = withContext(sessionDispatcher) {
                runCatching {
                    val saved = open.save(::releaseDocument)
                    val doc = if (document == null) PdfDocument.open(open.viewFile) else null
                    saved to doc
                }
            }
            outcome.onSuccess { (result, doc) ->
                if (doc != null) document = doc
                reportSave(result, onResult)
            }.onFailure {
                runCatching { withContext(sessionDispatcher) { PdfDocument.open(open.viewFile) } }
                    .onSuccess { document = it }
                onResult(false, it.message ?: "Saving failed.")
            }
            editBusy = false
        }
    }

    private fun reportSave(result: PdfEditSession.SaveOutcome, onResult: (Boolean, String?) -> Unit) {
        when (result) {
            is PdfEditSession.SaveOutcome.Saved -> {
                dirty = false
                for (page in 0 until pageCount) pageVersions[page] = pageVersion(page) + 1
                onResult(true, null)
            }
            PdfEditSession.SaveOutcome.NothingToSave -> { dirty = false; onResult(true, null) }
            PdfEditSession.SaveOutcome.ChangedOutside -> onResult(
                false,
                "This PDF changed outside Kern since you opened it, so saving would overwrite those changes. " +
                    "Use Export to keep your version as a separate file.",
            )
            is PdfEditSession.SaveOutcome.Failed -> onResult(
                false,
                "${result.message} Use Export to keep your version as a separate file.",
            )
            is PdfEditSession.SaveOutcome.Damaged -> onResult(
                false,
                "${result.message} Export your version now, then check the original file.",
            )
        }
    }

    /** The document as it stands, compacted, as a new file at [target]; the original is not touched. */
    fun saveCompactedTo(target: Uri) {
        if (uri == null || editBusy) return
        if (!KernkraftPdf.available) {
            toolError = "The PDF engine is not bundled in this build."
            return
        }
        val ctx = getApplication<Application>()
        editBusy = true
        viewModelScope.launch {
            val result = withContext(sessionDispatcher) {
                runCatching {
                    val s = sessionOrOpen()
                    val compacted = s.compact()
                    try {
                        (ctx.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Could not open $target for writing"))
                            .use { out -> compacted.inputStream().use { it.copyTo(out) } }
                    } finally {
                        compacted.delete()
                    }
                }
            }
            publishText()
            result.onSuccess { toolMessage = "Saved a compacted copy" }
                .onFailure { toolError = it.message ?: "The compacted copy could not be saved." }
            editBusy = false
        }
    }

    // --- PDF tools (merge / split) state ---------------------------------------
    /** True while a bridge operation runs. */
    var toolBusy by mutableStateOf(false)
        private set
    /** A produced file in cache, waiting for the user to choose where to save it. */
    var pendingOutput by mutableStateOf<PendingOutput?>(null)
        private set
    /** A transient success message shown briefly as a snackbar. */
    var toolMessage by mutableStateOf<String?>(null)
        private set
    /** An error or instructional message to display in a dialog. */
    var toolError by mutableStateOf<String?>(null)
        private set

    /** A cache file produced by a tool, plus a suggested save-as name. */
    data class PendingOutput(val cachePath: String, val suggestedName: String)

    /** Whether the native PDF engine is bundled in this build. */
    val engineAvailable: Boolean get() = KernkraftPdf.available

    fun start(routeArgument: String?) {
        if (started) return
        started = true
        val ctx = getApplication<Application>()
        val decoded = DocumentIo.fromRouteArgument(routeArgument)
        if (decoded == null) {
            loading = false
            error = "No file was provided."
            return
        }
        uri = decoded
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    DocumentIo.tryPersist(ctx, decoded)
                    val name = DocumentIo.displayName(ctx, decoded)
                    val doc = PdfDocument.open(ctx, decoded)
                    name to doc
                }
            }
            result.onSuccess { (name, doc) ->
                fileName = name
                document = doc
                pageCount = doc.pageCount
                loading = false
                prepareText()
            }.onFailure {
                error = it.message ?: "Could not open the PDF."
                loading = false
            }
        }
    }

    /**
     * Renders [index] at [widthPx] off the main thread. The engine draws every page, so a page looks
     * the same before and after it is marked and every annotation in the file is seen; the picture
     * waits for the engine session to open. Only when the engine cannot open the file at all does the
     * framework renderer draw it (the find bar says the engine is unavailable). Null if the doc is closed.
     */
    suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        snapshotFlow { textLayer != null || textFailure != null }.first { it }
        val open = session
        if (open != null) {
            return@withContext runCatching { withContext(sessionDispatcher) { open.render(index, widthPx) } }.getOrNull()
        }
        renderWithFramework(index, widthPx)
    }

    private suspend fun renderWithFramework(index: Int, widthPx: Int): Bitmap? {
        val deadline = System.currentTimeMillis() + RENDER_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            // While an edit swaps the document there is none for a moment: wait for the next one
            // instead of giving the page a picture of nothing.
            val doc = document
            if (doc == null) { delay(RENDER_RETRY_MS); continue }
            val bitmap = runCatching { doc.renderPage(index, widthPx) }.getOrNull()
            if (bitmap != null) return bitmap
            if (document === doc) return null
        }
        return null
    }

    /** Writes a copy of the document as it stands, edits included, to [target]. */
    fun exportTo(target: Uri, onResult: (ok: Boolean, message: String?) -> Unit) {
        val ctx = getApplication<Application>()
        val source = uri ?: run { onResult(false, "Nothing to export."); return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // The edited state is read where the session's files are only ever touched: on the
                    // session's own thread, so a rebuild of the view cannot be caught half way.
                    val edited = withContext(sessionDispatcher) { session?.viewFile?.readBytes() }
                    DocumentIo.writeBytes(ctx, target, edited ?: DocumentIo.readBytes(ctx, source))
                }
            }
            onResult(result.isSuccess, result.exceptionOrNull()?.message)
        }
    }

    /**
     * Merges the open PDF with [extraUris] (appended in order) into one file.
     * Inputs are copied into the app cache and handed to the native bridge by
     * path; the result is staged in [pendingOutput] for the caller to save out.
     */
    fun merge(extraUris: List<Uri>) {
        val source = uri ?: run { toolError = "No open PDF to merge."; return }
        if (extraUris.isEmpty()) { toolError = "Pick at least one more PDF to merge."; return }
        runTool {
            val dir = toolsCacheDir()
            val inputs = buildList {
                add(copyToCache(source, dir, "merge_00.pdf"))
                extraUris.forEachIndexed { i, u -> add(copyToCache(u, dir, "merge_%02d.pdf".format(i + 1))) }
            }
            val out = File(dir, uniqueName("merged"))
            KernkraftPdf.merge(inputs.map { it.absolutePath }, out.absolutePath) to "merged.pdf"
        }
    }

    /**
     * Extracts a single 1-based page range (e.g. "3-7") from the open PDF into a
     * new file, staged in [pendingOutput].
     *
     * Only one produced file can be staged and saved, so a comma-separated list is
     * refused by [rangeSpecError] instead of being silently truncated to its first
     * range. Ranges are extracted one at a time.
     */
    fun extractPages(rangeSpec: String) {
        val source = uri ?: run { toolError = "No open PDF."; return }
        val spec = rangeSpec.filterNot { it.isWhitespace() }
        rangeSpecError(spec)?.let { toolError = it; return }
        val pages = checkNotNull(pageRange(spec))
        if (pages.last > pageCount) {
            toolError = "This PDF has $pageCount ${if (pageCount == 1) "page" else "pages"}."
            return
        }
        runTool {
            val dir = toolsCacheDir()
            val input = copyToCache(source, dir, "extract_src.pdf")
            val out = File(dir, uniqueName("extract"))
            KernkraftPdf.extract(input.absolutePath, pages.toList(), out.absolutePath) to "extract.pdf"
        }
    }

    companion object {
        /** How long a page waits for the document an edit is swapping in. */
        private const val RENDER_WAIT_MS = 5_000L
        private const val RENDER_RETRY_MS = 20L

        /** How long typing must pause before a PDF search starts. */
        private const val SEARCH_DEBOUNCE_MS = 250L

        private val SEARCHING = FindMessage("Searching", isError = false)
        private const val PREPARING_TEXT = "Getting the text ready"

        /** What find says when [count] pages could not be read, so the matches shown may be incomplete. */
        internal fun unreadPagesMessage(count: Int) = FindMessage(
            if (count == 1) "Could not read the text of 1 page, so it was not searched"
            else "Could not read the text of $count pages, so they were not searched",
            isError = true,
        )

        /** One 1-based page range: "4" or "2-7", after whitespace has been stripped. */
        private val SINGLE_RANGE = Regex("""(\d{1,6})(?:-(\d{1,6}))?""")

        /**
         * Checks [spec] as a single 1-based page range, returning null when it is
         * usable or a message explaining why it is not. Shared with the extract
         * dialog so bad input is refused at the point of entry rather than costing
         * the user a range further down the pipeline.
         */
        fun rangeSpecError(spec: String): String? {
            val compact = spec.filterNot { it.isWhitespace() }
            if (compact.isEmpty()) return "Enter a page range, e.g. 1-3."
            if (compact.contains(',')) {
                return "Extract handles one range at a time. Enter a single range like 1-3, " +
                    "then run Extract again for the next one."
            }
            val range = pageRange(compact)
                ?: return "Use one range like 1-3, or a single page number like 4."
            if (range.first < 1) return "Page numbers start at 1."
            if (range.last < range.first) return "The last page of the range cannot come before the first."
            return null
        }

        /**
         * The pages of [spec], a single range such as "4" or "2-7" without whitespace,
         * or null when it is not shaped like one. A reversed range is empty, so
         * callers check [rangeSpecError] first.
         */
        private fun pageRange(spec: String): IntRange? {
            val match = SINGLE_RANGE.matchEntire(spec) ?: return null
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: start
            return start..end
        }

        /** An output name no earlier output of this process shares: outputs never replace a file. */
        private fun uniqueName(stem: String): String = "${stem}_${System.nanoTime()}.pdf"
    }

    /** Writes the staged [pendingOutput] to [target] (a user-picked SAF location). */
    fun saveOutputTo(target: Uri) {
        val output = pendingOutput ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val written = runCatching {
                    val source = resolveToolFile(output.cachePath)
                        ?: throw java.io.IOException("Output is not in the tool cache")
                    DocumentIo.writeBytes(ctx, target, source.readBytes())
                }
                // Drop the staged copy only once it is safely written. If the save
                // failed there is nowhere else for this data to exist, so keep it
                // (and keep it staged) so the user can pick another destination.
                if (written.isSuccess) deleteToolFile(output.cachePath)
                written
            }
            if (result.isSuccess) {
                pendingOutput = null
                toolMessage = "Saved"
            } else {
                toolError = "Save failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun dismissPendingOutput() {
        val output = pendingOutput ?: return
        pendingOutput = null
        viewModelScope.launch { withContext(Dispatchers.IO) { deleteToolFile(output.cachePath) } }
    }
    fun consumeToolMessage() { toolMessage = null }
    fun consumeToolError() { toolError = null }

    /** Runs [block] off-main, mapping its [KernkraftPdf.Result] into [pendingOutput] / [toolMessage]. */
    private fun runTool(block: suspend () -> Pair<KernkraftPdf.Result, String>) {
        if (toolBusy) return
        toolBusy = true
        val staged = listOfNotNull(pendingOutput?.cachePath)
        viewModelScope.launch {
            val (result, suggestedName) = withContext(Dispatchers.IO) {
                // Anything left over from an earlier operation is dead weight.
                pruneToolsCache(staged)
                val outcome = runCatching { block() }
                    .getOrElse { KernkraftPdf.Result.Failure(it.message ?: "Operation failed") to "" }
                // The copied inputs are only needed for the duration of the native
                // call, and a failed call leaves nothing worth keeping at all.
                pruneToolsCache(staged + (outcome.first as? KernkraftPdf.Result.Success)?.outputPaths.orEmpty())
                outcome
            }
            when (result) {
                is KernkraftPdf.Result.Success -> {
                    val paths = result.outputPaths
                    // Only one produced file can be staged for saving, so keeping just
                    // the first would discard the rest: say so rather than lose it.
                    when {
                        paths.isEmpty() -> toolError = "The operation produced no output."
paths.size > 1 -> toolError =
    "That request produced ${paths.size} files, but only one can be saved at a time."
                        else -> pendingOutput = PendingOutput(paths[0], suggestedName)
                    }
                }
                is KernkraftPdf.Result.Failure -> toolError = result.message
            }
            toolBusy = false
        }
    }

    /**
     * Scratch space for the native bridge. It holds plaintext copies of the user's
     * documents, so it is treated as short-lived: see [pruneToolsCache].
     */
    private val toolsCacheRoot: File
        get() = File(getApplication<Application>().cacheDir, "pdf-tools")

    private fun toolsCacheDir(): File = toolsCacheRoot.apply { mkdirs() }

    /**
     * Deletes everything in the tool cache except [keepPaths]. Call it off the main
     * thread; it never throws, because a failed cleanup must not fail an operation.
     */
    private fun pruneToolsCache(keepPaths: Collection<String> = emptyList()) {
        runCatching {
            // Resolve keep-paths exactly as the listing below resolves candidates, so a
            // path that fails canonicalisation still matches itself and survives.
            val keep = keepPaths.map {
                runCatching { File(it).canonicalPath }.getOrNull() ?: File(it).absolutePath
            }.toSet()
            toolsCacheRoot.listFiles()?.forEach { file ->
                val path = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
                if (path !in keep) file.deleteRecursively()
            }
        }
    }

    /**
     * Resolves [path] to a canonical file inside [toolsCacheRoot], or null if it lands
     * outside that directory, is the directory itself, or cannot be resolved. Tool
     * paths arrive from the native bridge, so every read and delete goes through this
     * to keep the reachable set to the scratch directory.
     */
    private fun resolveToolFile(path: String): File? = runCatching {
        val root = toolsCacheRoot.canonicalFile
        val file = File(path).canonicalFile
        if (file != root && file.startsWith(root)) file else null
    }.getOrNull()

    /** Deletes one staged file. Call it off the main thread; it never throws. */
    private fun deleteToolFile(path: String) {
        runCatching { resolveToolFile(path)?.deleteRecursively() }
    }

    private fun copyToCache(src: Uri, dir: File, name: String): File {
        val ctx = getApplication<Application>()
        val dest = File(dir, name)
        ctx.contentResolver.openInputStream(src)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: throw java.io.IOException("Could not read $src")
        return dest
    }

    override fun onCleared() {
        document?.close()
        document = null
        // The session is closed on its own thread, behind any save or edit still running there: closing
        // it earlier would delete the files such a save may need to put the document back.
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(sessionDispatcher) {
            session?.let { runCatching { it.close() } }
            session = null
        }
        // Backstop: leaving the editor must not leave copies of the user's
        // documents behind. viewModelScope is cancelled by now, so this runs on a
        // plain background thread rather than blocking the main one.
        val root = toolsCacheRoot
        Thread({ runCatching { root.deleteRecursively() } }, "pdf-tools-cleanup").start()
    }
}
