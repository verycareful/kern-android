package dev.kern.editors.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kern.pdfbridge.PdfAnnotationKind
import dev.kern.shared.theme.KernRadius
import dev.kern.shared.theme.KernTheme
import dev.kern.shared.theme.KernType
import dev.kern.shared.theme.PlexMonoFamily

/** How far from a thin annotation a touch still means it. */
private val TouchReach = 20.dp

/** The tallest the text of a note grows in the bar before it scrolls. */
private val NoteTextMaxHeight = 120.dp

private const val OUTLINE_DASH_ON = 10f
private const val OUTLINE_DASH_OFF = 6f

/**
 * Draws the outline of the chosen annotation of [page], where a finger carrying it has put it. It
 * takes no touch: [carryAnnotation] does.
 */
@Composable
internal fun SelectionOutline(vm: PdfEditorViewModel, page: Int, modifier: Modifier = Modifier) {
    val colors = KernTheme.colors
    val chosen = vm.selectedAnnotation?.takeIf { it.page == page } ?: return
    val carried = vm.carriedOffset
    Canvas(modifier) {
        val box = AnnotationSelectionModel.moved(chosen.annotation.rect, carried)
        val pad = 4.dp.toPx()
        drawRect(
            color = colors.accent,
            topLeft = Offset(box.left * size.width - pad, box.top * size.height - pad),
            size = Size((box.right - box.left) * size.width + 2 * pad, (box.bottom - box.top) * size.height + 2 * pad),
            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(OUTLINE_DASH_ON, OUTLINE_DASH_OFF))),
        )
    }
}

/**
 * Lets a finger that lands on the chosen annotation of [page], when it can be moved, carry it: the
 * outline follows the finger and the annotation moves when the finger lifts. The touch is taken
 * before anything under it sees it, so the page does not scroll under a finger that carries
 * something. A touch anywhere else is left alone, to scroll and to select, and a second finger
 * drops the move. [reach] is how far, in pixels, a touch may miss the annotation.
 */
internal fun Modifier.carryAnnotation(vm: PdfEditorViewModel, page: Int, reach: Float): Modifier = pointerInput(page, reach) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val current = vm.selectedAnnotation?.takeIf { it.page == page } ?: return@awaitEachGesture
        if (!AnnotationSelectionModel.actionsOf(current.annotation).move) return@awaitEachGesture
        val width = size.width.toFloat().coerceAtLeast(1f)
        val height = size.height.toFloat().coerceAtLeast(1f)
        val start = PagePoint(down.position.x / width, down.position.y / height)
        if (!AnnotationSelectionModel.contains(current.annotation.rect, start, reach / width, height / width)) return@awaitEachGesture
        down.consume()
        var moved = false
        var interrupted = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) { interrupted = true; break }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val away = change.position - down.position
            if (!moved && away.getDistance() >= viewConfiguration.touchSlop) moved = true
            if (moved) vm.carry(AnnotationSelectionModel.clampMove(current.annotation.rect, away.x / width, away.y / height))
            change.consume()
            if (!change.pressed) break
        }
        when {
            interrupted -> {
                vm.carry(PagePoint(0f, 0f))
                // The fingers are left alone to pan and zoom until the last one lifts.
                do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
            }
            moved -> vm.moveSelected(vm.carriedOffset)
        }
    }
}

/** A surface that takes a tap while a note is being placed. */
@Composable
internal fun NoteTapLayer(vm: PdfEditorViewModel, page: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.pointerInput(page) {
            detectTapGestures { vm.placeNote(page, PagePoint(it.x / size.width, it.y / size.height)) }
        },
    )
}

/** How far, in pixels, a touch may miss a thin annotation and still mean it. */
@Composable
internal fun touchReachPx(): Float = with(LocalDensity.current) { TouchReach.toPx() }

/**
 * The bar at the foot of the page while an annotation is chosen: what it is, the text of a note,
 * and only what can be done to it. What cannot be done is said, not offered.
 */
@Composable
internal fun SelectionBar(vm: PdfEditorViewModel, modifier: Modifier = Modifier) {
    val chosen = vm.selectedAnnotation ?: return
    val annotation = chosen.annotation
    val actions = AnnotationSelectionModel.actionsOf(annotation)
    val colors = KernTheme.colors
    Column(
        modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(KernRadius.field))
            .background(colors.surface)
            .border(1.dp, colors.borderSoft, RoundedCornerShape(KernRadius.field))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(AnnotationSelectionModel.nameOf(annotation), style = KernType.meta, color = colors.textMid)
        val text = annotation.contents?.takeIf { it.isNotBlank() }
        if (text != null || annotation.kind == PdfAnnotationKind.Note) {
            Text(
                text ?: "This note has no text.",
                style = KernType.body,
                color = if (text != null) colors.text else colors.textDim,
                modifier = Modifier.heightIn(max = NoteTextMaxHeight).verticalScroll(rememberScrollState()),
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (actions.editText) BarChip("Edit text", selected = false, enabled = !vm.editBusy, onClick = vm::editSelectedNoteText)
            if (actions.delete) BarChip("Delete", selected = false, enabled = !vm.editBusy, onClick = vm::deleteSelected)
            BarChip("Close", selected = false, onClick = vm::deselect)
        }
        if (actions.recolour) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColourDots(AnnotationSelectionModel.offeredColour(annotation.colour)) { if (!vm.editBusy) vm.recolourSelected(it) }
            }
        }
        actions.limit?.let { Text(it, style = KernType.caption, color = colors.textMid) }
    }
}

/** Asks what a note says. An empty text is a cancel: the engine would take the text away, and a note with none says nothing. */
@Composable
internal fun NoteDialog(request: PdfEditorViewModel.NoteRequest, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    val colors = KernTheme.colors
    var text by remember(request) { mutableStateOf(request.initial) }
    val focus = remember { FocusRequester() }
    val adding = request is PdfEditorViewModel.NoteRequest.New
    LaunchedEffect(request) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = colors.surface,
        title = { Text(if (adding) "Add note" else "Edit note") },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(KernRadius.field))
                    .background(colors.sunken)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) Text("Note", style = KernType.meta.copy(fontSize = 13.sp), color = colors.textDim)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(fontFamily = PlexMonoFamily, fontSize = 13.sp, color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(if (adding) "Add" else "Save", color = if (text.isNotBlank()) colors.accent else colors.textDim)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = colors.textMid) } },
    )
}
