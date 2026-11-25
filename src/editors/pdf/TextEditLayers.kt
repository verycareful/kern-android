package dev.kern.editors.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.kern.pdfbridge.PdfTextBlock
import dev.kern.shared.theme.KernTheme
import kotlin.math.roundToInt

/**
 * The layer over a page while its text is being changed: every block of text outlined, the ones that can
 * be changed in the accent and the ones that cannot dashed, and a tap on a block opening it as a field
 * over the block. A tap where there is no text says so.
 */
@Composable
internal fun TextBlocksLayer(vm: PdfEditorViewModel, page: Int, modifier: Modifier = Modifier) {
    val colors = KernTheme.colors
    var blocks by remember(page) { mutableStateOf<List<PdfTextBlock>>(emptyList()) }
    val version = vm.pageVersion(page)
    LaunchedEffect(page, version, vm.documentEpoch) { blocks = vm.blocksOf(page)?.blocks.orEmpty() }
    val editing = vm.textEdit?.takeIf { it.page == page }
    BoxWithConstraints(
        modifier.pointerInput(page) {
            detectTapGestures { vm.tapTextBlock(page, PagePoint(it.x / size.width, it.y / size.height)) }
        },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val dashed = PathEffect.dashPathEffect(floatArrayOf(BLOCK_DASH_ON.dp.toPx(), BLOCK_DASH_OFF.dp.toPx()))
            for (block in blocks) {
                val box = block.box
                drawRect(
                    color = if (block.editable) colors.accent else colors.textDim,
                    topLeft = Offset(box.left * size.width, box.top * size.height),
                    size = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                    alpha = if (block.editable) EDITABLE_ALPHA else REFUSED_ALPHA,
                    style = Stroke(1.dp.toPx(), pathEffect = if (block.editable) null else dashed),
                )
            }
        }
        if (editing != null) TextEditField(vm, editing, constraints.maxWidth, constraints.maxHeight)
    }
}

/** The text of the open block as a field laid over it, at the block's place and in letters the size of its lines. */
@Composable
private fun TextEditField(vm: PdfEditorViewModel, edit: PdfEditorViewModel.TextEdit, pageWidthPx: Int, pageHeightPx: Int) {
    val colors = KernTheme.colors
    val density = LocalDensity.current
    val box = edit.block.box
    val left = (box.left * pageWidthPx).roundToInt()
    val top = (box.top * pageHeightPx).roundToInt()
    val blockWidthPx = ((box.right - box.left) * pageWidthPx).roundToInt()
    val blockHeightPx = (box.bottom - box.top) * pageHeightPx
    val widthPx = blockWidthPx.coerceAtLeast(with(density) { MIN_FIELD_WIDTH.dp.roundToPx() }).coerceAtMost(pageWidthPx - left)
    val letters = TextEditModel.letterPx(blockHeightPx, edit.block.text.lines().size)
    val focus = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(edit) {
        focus.requestFocus()
        bring.bringIntoView()
    }
    Box(
        Modifier
            .offset { IntOffset(left, top) }
            .width(with(density) { widthPx.toDp() })
            .heightIn(min = with(density) { blockHeightPx.toDp() })
            .background(Color.White)
            .border(2.dp, colors.accent)
            .bringIntoViewRequester(bring),
    ) {
        BasicTextField(
            value = vm.textDraft,
            onValueChange = vm::changeTextDraft,
            textStyle = TextStyle(fontSize = with(density) { letters.toSp() }, color = Color.Black),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.focusRequester(focus),
        )
    }
}

private const val BLOCK_DASH_ON = 4f
private const val BLOCK_DASH_OFF = 3f
private const val EDITABLE_ALPHA = 0.55f
private const val REFUSED_ALPHA = 0.45f

/** The narrowest a field is made, in dp: a block of one short word still gives room to type. */
private const val MIN_FIELD_WIDTH = 96
