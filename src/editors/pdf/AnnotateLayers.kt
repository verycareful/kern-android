package dev.kern.editors.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kern.pdfbridge.MarkupKind
import dev.kern.shared.theme.KernRadius
import dev.kern.shared.theme.KernTheme
import dev.kern.shared.theme.KernType

/** The bar at the foot of the page while the user marks text or draws: what to mark with, and how to finish. */
@Composable
internal fun AnnotateBar(vm: PdfEditorViewModel, modifier: Modifier = Modifier) {
    val colors = KernTheme.colors
    val marking = vm.annotateMode == PdfEditorViewModel.AnnotateMode.Markup
    Column(
        modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(KernRadius.field))
            .background(colors.surface)
            .border(1.dp, colors.borderSoft, RoundedCornerShape(KernRadius.field))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (marking) {
                for (kind in MarkupKind.entries) {
                    BarChip(kindLabel(kind), vm.markupKind == kind) { vm.chooseMarkupKind(kind) }
                }
            } else {
                for (width in InkWidth.entries) {
                    BarChip(width.label, vm.inkWidth == width) { vm.chooseInkWidth(width) }
                }
            }
            BarChip("Done", selected = false, onClick = vm::finishAnnotating)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (colour in MarkColour.entries) {
                val chosen = vm.activeColour == colour
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(colour.red.toFloat(), colour.green.toFloat(), colour.blue.toFloat()))
                        .border(if (chosen) 3.dp else 1.dp, if (chosen) colors.accent else colors.borderSoft, CircleShape)
                        .semantics { contentDescription = "${colour.label}${if (chosen) ", chosen" else ""}" }
                        .clickable { vm.chooseColour(colour) },
                )
            }
            if (marking) {
                val selected = vm.markupSelection != null
                BarChip(
                    if (selected) "Mark" else "Press and hold text, then drag",
                    selected = selected,
                    enabled = selected && !vm.editBusy,
                    onClick = vm::applyMarkup,
                )
            }
        }
    }
}

private fun kindLabel(kind: MarkupKind) = when (kind) {
    MarkupKind.Highlight -> "Highlight"
    MarkupKind.Underline -> "Underline"
    MarkupKind.StrikeOut -> "Strikeout"
}

@Composable
private fun BarChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = KernTheme.colors
    val shape = RoundedCornerShape(KernRadius.innerSmall)
    Text(
        text = label,
        style = KernType.meta,
        color = when {
            !enabled -> colors.textDim
            selected -> colors.accent
            else -> colors.text
        },
        modifier = Modifier
            .clip(shape)
            .border(1.dp, if (selected) colors.accent else colors.borderSoft, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private val HandleRadius = 8.dp
private val HandleDrop = 18.dp
private val HandleGrab = 28.dp

/** Which end of the selection a touch took hold of. */
private enum class Handle { Start, End }

/**
 * The page's text for choosing what to mark: press and hold on text, then drag to cover more; the
 * two handles that appear move either end. The chosen text is drawn the way the mark will look.
 */
@Composable
internal fun MarkupLayer(vm: PdfEditorViewModel, page: Int, lines: List<PdfTextLine>, modifier: Modifier = Modifier) {
    val glyphs = remember(lines) { PageGlyphs.of(lines) }
    val selection = vm.markupSelection?.takeIf { it.page == page && it.last < glyphs.boxes.size }
    val colors = KernTheme.colors
    val markColour = vm.markColour
    val kind = vm.markupKind
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val aspect = if (width > 0f) height / width else 1f
        fun point(o: Offset) = PagePoint(o.x / width, o.y / height)

        val handleDrop = with(density) { HandleDrop.toPx() }
        val handleGrab = with(density) { HandleGrab.toPx() }

        /** The point on the text a handle marks: the left edge of the first glyph, the right edge of the last. */
        fun anchorOf(handle: Handle, s: MarkupSelection): Offset {
            val box = glyphs.boxes[if (handle == Handle.Start) s.first else s.last]
            return Offset((if (handle == Handle.Start) box.left else box.right) * width, (box.top + box.bottom) / 2 * height)
        }

        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(page) { detectTapGestures { vm.selectMarkup(null) } }
                .pointerInput(glyphs, page) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start ->
                            glyphs.hit(point(start), aspect)?.let { vm.selectMarkup(MarkupSelection(page, it, it)) }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val current = vm.markupSelection?.takeIf { it.page == page } ?: return@detectDragGesturesAfterLongPress
                            glyphs.hit(point(change.position), aspect)?.let { vm.selectMarkup(current.copy(focus = it)) }
                        },
                    )
                }
                .pointerInput(glyphs, page) {
                    // Last in the chain, so it sees a touch first: one on a handle moves that end and nothing else.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val current = vm.markupSelection?.takeIf { it.page == page && it.last < glyphs.boxes.size }
                            ?: return@awaitEachGesture
                        val grabbed = Handle.entries.firstOrNull { h ->
                            val centre = anchorOf(h, current) + Offset(0f, handleDrop)
                            (down.position - centre).getDistance() <= handleGrab
                        } ?: return@awaitEachGesture
                        down.consume()
                        val grabOffset = anchorOf(grabbed, current) - down.position
                        drag(down.id) { change ->
                            change.consume()
                            val target = change.position + grabOffset
                            val latest = vm.markupSelection?.takeIf { it.page == page } ?: return@drag
                            glyphs.hit(point(target), aspect)?.let {
                                vm.selectMarkup(if (grabbed == Handle.Start) latest.withStart(it) else latest.withEnd(it))
                            }
                        }
                    }
                },
        ) {
            if (selection == null) return@Canvas
            val mark = Color(markColour.red.toFloat(), markColour.green.toFloat(), markColour.blue.toFloat())
            for (quad in selection.quads(glyphs)) drawMark(kind, quad, mark)
            for (handle in Handle.entries) {
                val at = anchorOf(handle, selection)
                val centre = at + Offset(0f, handleDrop)
                drawLine(colors.accent, at, centre, strokeWidth = 2.dp.toPx())
                drawCircle(colors.accent, HandleRadius.toPx(), centre)
            }
        }
    }
}

/** One box of text marked the way the engine draws it: a translucent fill, or a line at the foot or middle. */
private fun DrawScope.drawMark(kind: MarkupKind, quad: PageRect, colour: Color) {
    val left = quad.left * size.width
    val right = quad.right * size.width
    val top = quad.top * size.height
    val bottom = quad.bottom * size.height
    when (kind) {
        MarkupKind.Highlight -> drawRect(colour.copy(alpha = HIGHLIGHT_ALPHA), Offset(left, top), Size(right - left, bottom - top))
        MarkupKind.Underline -> drawLine(colour, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.5.dp.toPx())
        MarkupKind.StrikeOut -> drawLine(colour, Offset(left, (top + bottom) / 2), Offset(right, (top + bottom) / 2), strokeWidth = 1.5.dp.toPx())
    }
}

private const val HIGHLIGHT_ALPHA = 0.4f

/**
 * A surface that takes the pen: one finger draws, two fingers are left to pan and zoom (a stroke
 * that a second finger interrupts is dropped). A stroke is sent when the finger lifts, and stays on
 * screen until it is part of the page.
 */
@Composable
internal fun InkLayer(vm: PdfEditorViewModel, page: Int, modifier: Modifier = Modifier) {
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var widthPoints by remember(page) { mutableStateOf<Int?>(null) }
    LaunchedEffect(page, vm.documentEpoch, vm.pageVersion(page)) { widthPoints = vm.pageWidthPoints(page) }
    val inkColour = vm.inkColour
    val pen = Color(inkColour.red.toFloat(), inkColour.green.toFloat(), inkColour.blue.toFloat())
    val penWidth = vm.inkWidth.points
    val pending = vm.pendingInk.firstOrNull { it.page == page }

    Canvas(
        modifier.pointerInput(page) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val aspect = size.height.toFloat() / size.width.coerceAtLeast(1)
                fun fraction(o: Offset) = PagePoint(o.x / size.width, o.y / size.height)
                val stroke = mutableListOf(fraction(down.position))
                live = listOf(down.position)
                down.consume()
                var interrupted = false
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) { interrupted = true; break }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    stroke += fraction(change.position)
                    live = live + change.position
                    change.consume()
                    if (!change.pressed) break
                }
                if (interrupted) {
                    live = emptyList()
                    // The fingers are left alone to pan and zoom until the last one lifts.
                    do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed })
                } else {
                    vm.addInkStroke(page, stroke, aspect)
                    live = emptyList()
                }
            }
        },
    ) {
        val perPoint = widthPoints?.let { size.width / it } ?: (size.width / FALLBACK_PAGE_POINTS)
        val strokeWidth = (penWidth * perPoint).toFloat()
        pending?.strokes?.forEach { drawStroke(it.map { p -> Offset(p.x * size.width, p.y * size.height) }, pen, strokeWidth) }
        drawStroke(live, pen, strokeWidth)
    }
}

/** A page is about this many points wide (A4 and Letter), for drawing the pen before the page's own width is known. */
private const val FALLBACK_PAGE_POINTS = 595f

private fun DrawScope.drawStroke(points: List<Offset>, colour: Color, width: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(colour, width / 2, points.first())
        return
    }
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (p in points.drop(1)) lineTo(p.x, p.y)
    }
    drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
