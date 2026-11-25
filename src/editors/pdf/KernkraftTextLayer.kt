package dev.kern.editors.pdf

import dev.kern.pdfbridge.KernkraftSession
import dev.kern.pdfbridge.PdfBox
import dev.kern.pdfbridge.PdfEngineLink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The text of the document the engine session holds, edits included: after an edit the same
 * layer answers for the edited document. The engine keeps one call at a time per session, so
 * every call goes to [dispatcher], the one the session's other calls run on.
 */
class KernkraftTextLayer(
    private val engine: KernkraftSession,
    private val dispatcher: CoroutineDispatcher,
) : PdfTextLayer {

    override suspend fun search(page: Int, query: String): List<PdfTextMatch> =
        withContext(dispatcher) { engine.search(page, query) }.map { PdfTextMatch(page, it.boxes.map(::pageRect)) }

    override suspend fun lines(page: Int): List<PdfTextLine> =
        withContext(dispatcher) { engine.lines(page) }.map { PdfTextLine(it.text, pageRect(it.box)) }

    override suspend fun links(page: Int): List<PdfLink> =
        withContext(dispatcher) { engine.links(page) }.map {
            when (it) {
                is PdfEngineLink.Uri -> PdfLink.External(it.boxes.map(::pageRect), it.uri)
                is PdfEngineLink.Page -> PdfLink.GoTo(it.boxes.map(::pageRect), it.page)
            }
        }

    private fun pageRect(box: PdfBox) = PageRect(box.left, box.top, box.right, box.bottom)
}
