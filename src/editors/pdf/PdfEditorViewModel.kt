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
import dev.kern.pdfbridge.PdfAnnotation
import dev.kern.pdfbridge.PdfAnnotationId
import dev.kern.pdfbridge.PdfBox
import dev.kern.pdfbridge.PdfColour
import dev.kern.pdfbridge.PdfDate
import dev.kern.pdfbridge.PdfEngineException
import dev.kern.pdfbridge.PdfOp
import dev.kern.pdfbridge.PdfPageBlocks
import dev.kern.pdfbridge.PdfPageFont
import dev.kern.pdfbridge.PdfPageFonts
import dev.kern.pdfbridge.PdfPoint
import dev.kern.pdfbridge.PdfStep
import dev.kern.pdfbridge.PdfTextBlock
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

    // Annotations

    private val annotationCache = HashMap<Int, List<PdfAnnotation>>()

    /** What the engine reads of the annotations of [page], in drawing order; empty where it cannot read them. */
    suspend fun annotationsOf(page: Int): List<PdfAnnotation> {
        val revision = textRevision
        annotationCache[page]?.let { return it }
        val open = session ?: return emptyList()
        val read = try {
            withContext(sessionDispatcher) { open.annotations(page) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        if (textRevision == revision) annotationCache[page] = read
        return read
    }

    /** The annotation the last tap chose, with its outline drawn and what can be done to it offered. */
    var selectedAnnotation by mutableStateOf<SelectedAnnotation?>(null)
        private set

    /** How far, as fractions of the page, a finger has carried the selected annotation since it touched it. */
    var carriedOffset by mutableStateOf(PagePoint(0f, 0f))
        private set

    fun carry(by: PagePoint) { carriedOffset = by }

    private fun select(chosen: SelectedAnnotation?) {
        selectedAnnotation = chosen
        forgetCarry()
    }

    private fun forgetCarry() {
        carriedOffset = PagePoint(0f, 0f)
    }

    fun deselect() = select(null)

    /** After the edit now running or waiting: the annotation to select, read again from the document. */
    private data class Reselect(val page: Int, val id: PdfAnnotationId?)

    private var reselect: Reselect? = null

    /** Asks for [op], and for the annotation of [reselect] to be selected once it is in; nothing is kept when the request is not taken. */
    private fun requestEdit(op: PdfOp, reselect: Reselect?) {
        this.reselect = reselect
        if (!requestEdit(op)) {
            this.reselect = null
            forgetCarry()
        }
    }

    /** Asks for the edit [edit] makes of the selected annotation, when [allowed] says the engine can do it. */
    private fun withSelected(allowed: (AnnotationActions) -> Boolean, edit: (page: Int, id: PdfAnnotationId) -> PdfOp) {
        val chosen = selectedAnnotation ?: return
        val id = chosen.annotation.id ?: return
        if (!allowed(AnnotationSelectionModel.actionsOf(chosen.annotation))) return
        requestEdit(edit(chosen.page, id), Reselect(chosen.page, id))
    }

    fun recolourSelected(colour: MarkColour) =
        withSelected({ it.recolour }) { page, id -> PdfOp.EditAnnotation(page, id, PdfDate.now(), colour = colour.toEngine()) }

    /** Moves the selected annotation by [by], fractions of the page; the move is kept on the page. */
    fun moveSelected(by: PagePoint) {
        val box = selectedAnnotation?.annotation?.rect ?: return
        val clamped = AnnotationSelectionModel.clampMove(box, by.x, by.y)
        if (!AnnotationSelectionModel.isMove(clamped)) { forgetCarry(); return }
        withSelected({ it.move }) { page, id ->
            PdfOp.EditAnnotation(page, id, PdfDate.now(), translate = PdfPoint(clamped.x.toDouble(), clamped.y.toDouble()))
        }
    }

    fun deleteSelected() {
        val chosen = selectedAnnotation ?: return
        val id = chosen.annotation.id ?: return
        if (!AnnotationSelectionModel.actionsOf(chosen.annotation).delete) return
        requestEdit(PdfOp.DeleteAnnotation(chosen.page, id), reselect = null)
    }

    /** The text of a note being written, or of one being changed; the screen shows a dialog while it is set. */
    sealed interface NoteRequest {
        val initial: String

        data class New(val page: Int, val at: PagePoint) : NoteRequest {
            override val initial: String get() = ""
        }

        data class Change(val page: Int, val id: PdfAnnotationId, override val initial: String) : NoteRequest
    }

    var noteRequest by mutableStateOf<NoteRequest?>(null)
        private set

    fun editSelectedNoteText() {
        val chosen = selectedAnnotation ?: return
        val id = chosen.annotation.id ?: return
        if (!AnnotationSelectionModel.actionsOf(chosen.annotation).editText) return
        noteRequest = NoteRequest.Change(chosen.page, id, chosen.annotation.contents.orEmpty())
    }

    fun cancelNoteText() { noteRequest = null }

    /** Puts [text] into the note the dialog is about: a new note at the tapped place, or the chosen note's text. */
    fun commitNoteText(text: String) {
        val request = noteRequest ?: return
        noteRequest = null
        if (text.isBlank()) return
        when (request) {
            is NoteRequest.New -> requestEdit(
                PdfOp.AddNote(
                    page = request.page,
                    at = PdfPoint(request.at.x.toDouble(), request.at.y.toDouble()),
                    text = text,
                    colour = noteColour.toEngine(),
                    modified = PdfDate.now(),
                ),
                Reselect(request.page, null),
            )
            is NoteRequest.Change -> requestEdit(
                PdfOp.EditAnnotation(request.page, request.id, PdfDate.now(), contents = text),
                Reselect(request.page, request.id),
            )
        }
    }

    /** A tap at [point] on [page] while adding a note: asks what the note says. */
    fun placeNote(page: Int, point: PagePoint) {
        if (editBusy) return
        val at = PagePoint(point.x.coerceIn(0f, NOTE_PLACEMENT_LIMIT), point.y.coerceIn(0f, NOTE_PLACEMENT_LIMIT))
        noteRequest = NoteRequest.New(page, at)
    }

    /** The edit that just went in is in the document: select what it was about, read again, or nothing. */
    private fun reselectAfter(step: PdfStep) {
        val wanted = reselect
        reselect = null
        if (wanted == null) { deselect(); return }
        // A note that was just added is shown chosen, with its bar: the mode that placed it is left.
        if (wanted.id == null) annotateMode = AnnotateMode.None
        val id = wanted.id ?: step.annotation
        viewModelScope.launch {
            val found = id?.let { annotationsOf(wanted.page).firstOrNull { a -> a.id == it } }
            select(found?.let { SelectedAnnotation(wanted.page, it) })
        }
    }

    // Text editing

    private val blockCache = HashMap<Int, PdfPageBlocks>()

    /**
     * The text blocks of [page] as the document stands, with the revision they were read at; null when
     * the engine cannot read them. A revision is good only until the next edit, so every edit drops
     * what is kept, whichever page it changed.
     */
    suspend fun blocksOf(page: Int): PdfPageBlocks? {
        val revision = textRevision
        blockCache[page]?.let { return it }
        val open = session ?: return null
        val read = try {
            withContext(sessionDispatcher) { open.textBlocks(page) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (textRevision == revision) blockCache[page] = read
        return read
    }

    /** A block being changed: what it said when it was read, and the revision of the document it was read at. */
    data class TextEdit(val page: Int, val block: PdfTextBlock, val revision: Long)

    /** The block whose text is being changed, shown as a field over the block; null when none is. */
    var textEdit by mutableStateOf<TextEdit?>(null)
        private set

    /** What the field over the block holds now. */
    var textDraft by mutableStateOf("")
        private set

    fun changeTextDraft(text: String) { textDraft = text }

    /**
     * New text being typed on [page] in a box whose top left corner is [at] and which is [width] wide, as
     * fractions of the page. [fonts] are the page's fonts as the engine read them; [font] is the one the text
     * follows (the nearest block's unless the user chose another), null on a page with no text to follow.
     * [size] is the size the user chose, in points, or null to keep the followed font's. [blocks] are the
     * page's blocks, whose widths a dragged box snaps to. [pageWidthPoints] sizes the field's letters.
     */
    data class NewText(
        val page: Int,
        val at: PagePoint,
        val width: Float,
        val fonts: PdfPageFonts,
        val font: PdfPageFont?,
        val size: Double?,
        val blocks: List<PdfTextBlock>,
        val pageWidthPoints: Int?,
    ) {
        /** The size the text will be set in, in points. */
        val effectiveSize: Double get() = size ?: font?.size ?: TextEditModel.EMPTY_PAGE_SIZE
    }

    /** The new text being typed, shown as a field at its place; null when none is. */
    var newText by mutableStateOf<NewText?>(null)
        private set

    /** Whether a field is open over the page, for a block or for new text. */
    val textFieldOpen: Boolean get() = textEdit != null || newText != null

    /**
     * A tap at [point] on [page] while changing text: opens the block there for editing, or says why it cannot
     * be; where there is no text, opens a field for new text there.
     */
    fun tapTextBlock(page: Int, point: PagePoint) {
        if (editBusy || textFieldOpen) return
        viewModelScope.launch {
            val read = blocksOf(page)
            if (read == null) {
                toolError = "Kern could not read the text of this page."
                return@launch
            }
            val block = TextEditModel.blockAt(read.blocks, point)
            when {
                block == null -> openNewText(page, point, read)
                !block.editable -> toolMessage = TextEditModel.refusal(block.reason)
                else -> {
                    textDraft = block.text
                    textEdit = TextEdit(page, block, read.revision)
                }
            }
        }
    }

    private suspend fun openNewText(page: Int, point: PagePoint, read: PdfPageBlocks) {
        val open = session ?: return
        val fonts = try {
            withContext(sessionDispatcher) { open.pageFonts(page, PdfPoint(point.x.toDouble(), point.y.toDouble())) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toolError = "Kern could not read the fonts of this page: ${e.message ?: "the PDF engine failed."}"
            return
        }
        if (editBusy || textFieldOpen) return
        textDraft = ""
        newText = NewText(
            page = page,
            at = point,
            width = TextEditModel.columnWidth(read.blocks, point.x),
            fonts = fonts,
            font = fonts.fonts.firstOrNull { it.nearest },
            size = null,
            blocks = read.blocks,
            pageWidthPoints = pageWidthPoints(page),
        )
    }

    /** Sets the new text's box to [width] (a fraction of the page) as dragged; it snaps to the width of a block it comes near. */
    fun resizeNewText(width: Float) {
        val open = newText ?: return
        newText = open.copy(width = TextEditModel.snapWidth(width, open.at.x, open.blocks))
    }

    /** Makes the new text follow [font], one of the page's fonts, with its size unless the user chose one. */
    fun chooseNewTextFont(font: PdfPageFont) {
        val open = newText ?: return
        newText = open.copy(font = font)
    }

    /** Sets the new text in [size] points. */
    fun chooseNewTextSize(size: Double) {
        val open = newText ?: return
        newText = open.copy(size = size)
    }

    fun cancelTextEdit() {
        textEdit = null
        newText = null
    }

    /**
     * Puts the field's text on the page. For a block, nothing is sent when it is as it was, and an empty field
     * takes the block's text out; for new text, an empty field puts nothing on the page.
     */
    fun commitTextEdit() {
        newText?.let { commitNewText(it); return }
        val open = textEdit ?: return
        if (textDraft == open.block.text) { textEdit = null; return }
        requestEdit(PdfOp.EditText(open.page, open.block.id, open.revision, textDraft))
    }

    private fun commitNewText(open: NewText) {
        if (textDraft.isBlank()) { newText = null; return }
        val follow = open.font?.let { font ->
            open.fonts.nearest?.takeIf { it in font.blocks } ?: font.blocks.first()
        }
        requestEdit(
            PdfOp.AddText(
                page = open.page,
                at = PdfPoint(open.at.x.toDouble(), open.at.y.toDouble()),
                width = open.width.toDouble(),
                text = textDraft,
                likeBlock = follow,
                likeRevision = follow?.let { open.fonts.revision },
                size = if (open.font == null) open.effectiveSize else open.size,
            ),
        )
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
     * Handles a tap at [point] on [page] (whose height over width is [pageAspect]): selects the
     * annotation there, if there is one; otherwise drops the selection and follows a link there, if
     * there is one. [slop] is how far, in page widths, a touch may miss a thin annotation.
     * A link out of the document is only offered, never opened, until the user confirms.
     */
    fun tapPage(page: Int, point: PagePoint, pageAspect: Float, slop: Float) {
        val layer = textLayer
        if (layer == null) return
        val revision = textRevision
        viewModelScope.launch {
            val touched = AnnotationSelectionModel.hit(annotationsOf(page), point, pageAspect, slop)
            if (touched != null) {
                select(SelectedAnnotation(page, touched))
                return@launch
            }
            deselect()
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
                .onFailure {
                    if (it is PdfEngineException && it.needsCompactedCopy) needsRepairedCopy = true
                    textFailure = "Search, selection and links are unavailable: ${it.message ?: "the PDF engine could not open this file."}"
                }
        }
    }

    /** Makes the open session's text the document's text layer, if that has not been done. */
    private fun publishText() {
        val open = session ?: return
        if (textLayer != null) return
        textLayer = open.textLayer(sessionDispatcher)
        textFailure = null
    }

    /**
     * Asks for [op] to be applied. The first edit of an open PDF waits for the warning. Gives whether
     * the request was taken: it is not while another edit runs, while the document is not open, or
     * when there is no engine.
     */
    fun requestEdit(op: PdfOp): Boolean {
        if (editBusy || loading || error != null) return false
        if (!KernkraftPdf.available) {
            toolError = "The PDF engine is not bundled in this build, so this PDF cannot be edited."
            return false
        }
        if (needsRepairedCopy) {
            repairOffer = true
            return false
        }
        handle(gate.request(op))
        return true
    }

    // --- Markup and ink -------------------------------------------------------------

    /** What a touch on a page does: scroll and select as usual, mark text, draw, place a note, or change text. */
    enum class AnnotateMode { None, Markup, Ink, Note, Text }

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
    val activeColour: MarkColour
        get() = when (annotateMode) {
            AnnotateMode.Ink -> inkColour
            AnnotateMode.Note -> noteColour
            else -> markColour
        }

    /** The colour a new note is made in. */
    var noteColour by mutableStateOf(MarkColour.Yellow)
        private set
    var inkWidth by mutableStateOf(InkWidth.Medium)
        private set

    /** The text chosen to be marked, waiting for the user to apply it. */
    var markupSelection by mutableStateOf<MarkupSelection?>(null)
        private set

    fun startMarkup() = enter(AnnotateMode.Markup)

    fun startInk() = enter(AnnotateMode.Ink)

    fun startNote() = enter(AnnotateMode.Note)

    fun startTextEdit() {
        enter(AnnotateMode.Text)
        session?.fontsProblem?.let { toolMessage = TextEditModel.fontsProblemMessage(it) }
    }

    fun finishAnnotating() = enter(AnnotateMode.None)

    private fun enter(mode: AnnotateMode) {
        annotateMode = mode
        markupSelection = null
        textEdit = null
        newText = null
        deselect()
    }

    fun chooseMarkupKind(kind: MarkupKind) { markupKind = kind }

    fun chooseColour(colour: MarkColour) {
        when (annotateMode) {
            AnnotateMode.Ink -> inkColour = colour
            AnnotateMode.Note -> noteColour = colour
            else -> markColour = colour
        }
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
        reselect = null
        forgetCarry()
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
        annotationCache.keys.retainAll { it !in step.changedPages }
        blockCache.clear()
        textEdit = null
        newText = null
        TextEditModel.noteFor(step.notes)?.let { toolMessage = it }
        if (countChanged) annotationCache.clear()
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
        reselectAfter(step)
    }

    /** An edit failed: say why, and put the last good picture back if the failure took it away. */
    private suspend fun editFailed(failure: Throwable) {
        dropPendingInk()
        reselect = null
        deselect()
        if (failure is PdfEngineException && failure.needsCompactedCopy) {
            needsRepairedCopy = true
            repairOffer = true
        } else {
            toolError = when {
                failure is PdfEngineException && TextEditModel.missingLetter(failure.message) != null -> TextEditModel.missingLetter(failure.message)
                else -> failure.message ?: "The edit failed."
            }
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

    // Repaired copy

    /**
     * Whether the engine refused to open this file for editing because its cross-reference had to be rebuilt to
     * read it (or a later revision cannot be added to): only a compacted copy of it can be edited.
     */
    var needsRepairedCopy by mutableStateOf(false)
        private set

    /** Whether the screen should offer to save a repaired copy and edit that instead. */
    var repairOffer by mutableStateOf(false)
        private set

    /** A repaired copy that was saved and should now be opened for editing in place of this file; the screen clears it. */
    var openCopy by mutableStateOf<Uri?>(null)
        private set

    fun dismissRepairOffer() { repairOffer = false }

    fun consumeOpenCopy() { openCopy = null }

    /**
     * Writes a repaired, compacted copy of the file as the new file [target] and asks the screen to open it.
     * The file shown here is only read.
     */
    fun saveRepairedCopy(target: Uri) {
        repairOffer = false
        writeCompacted(target, onSaved = { openCopy = target }, failed = "The repaired copy could not be saved.")
    }

    /** The document as it stands, compacted, as a new file at [target]; the original is not touched. */
    fun saveCompactedTo(target: Uri) =
        writeCompacted(target, onSaved = { toolMessage = "Saved a compacted copy" }, failed = "The compacted copy could not be saved.")

    private fun writeCompacted(target: Uri, onSaved: () -> Unit, failed: String) {
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
                    val compacted = compactedCopy()
                    try {
                        (ctx.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Could not open $target for writing"))
                            .use { out -> compacted.inputStream().use { it.copyTo(out) } }
                    } finally {
                        compacted.delete()
                    }
                }
            }
            publishText()
            result.onSuccess { onSaved() }
                .onFailure { toolError = it.message ?: failed }
            editBusy = false
        }
    }

    /**
     * The document as it stands, compacted, in a new cache file. A file the engine cannot open for editing is
     * compacted from a copy of it as it is on disk. Runs on [sessionDispatcher].
     */
    private fun compactedCopy(): File {
        if (!needsRepairedCopy) {
            try {
                return sessionOrOpen().compact()
            } catch (e: PdfEngineException) {
                if (!e.needsCompactedCopy) throw e
                needsRepairedCopy = true
            }
        }
        val dir = toolsCacheDir()
        val source = copyToCache(uri ?: throw IOException("No file was provided."), dir, uniqueName("repair-source"))
        try {
            val out = File(dir, uniqueName("repaired"))
            return when (val result = KernkraftPdf.compactFile(source.path, out.path)) {
                is KernkraftPdf.Result.Success -> out
                is KernkraftPdf.Result.Failure -> {
                    out.delete()
                    throw IOException(result.message)
                }
            }
        } finally {
            source.delete()
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
        /** A note's icon is placed no nearer the page's right and bottom edges than this, so it stays on the page. */
        private const val NOTE_PLACEMENT_LIMIT = 0.95f

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
