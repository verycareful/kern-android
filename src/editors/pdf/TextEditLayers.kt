package dev.kern.editors.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.kern.pdfbridge.PdfTextBlock
import dev.kern.shared.theme.KernRadius
import dev.kern.shared.theme.KernTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The layer over a page while its text is being changed: every block of text outlined, the ones that can
 * be changed in the accent and the ones that cannot dashed, and a tap on a block opening it as a field
 * over the block. A tap where there is no text opens a field for new text there.
 */
@Composable
internal fun TextBlocksLayer(vm: PdfEditorViewModel, page: Int, modifier: Modifier = Modifier) {
    val colors = KernTheme.colors
    var blocks by remember(page) { mutableStateOf<List<PdfTextBlock>>(emptyList()) }
    val version = vm.pageVersion(page)
    val shown = vm.isShown(page, version)
    LaunchedEffect(page, version, vm.documentEpoch, shown) { if (shown) blocks = vm.blocksOf(page)?.blocks.orEmpty() }
    val editing = vm.textEdit?.takeIf { it.page == page }
    // Where the open field and its handles and chips are, so that a touch on them is not a touch outside.
    var fieldBounds by remember(page) { mutableStateOf<Rect?>(null) }
    val handleReach = with(LocalDensity.current) { FIELD_TOUCH_REACH.dp.toPx() }
    BoxWithConstraints(
        modifier.pointerInput(page, handleReach) {
            detectTapGestures {
                if (vm.textFieldOpen && fieldBounds?.inflate(handleReach)?.contains(it) == true) return@detectTapGestures
                vm.tapTextBlock(page, PagePoint(it.x / size.width, it.y / size.height))
            }
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
        if (editing != null) TextEditField(vm, editing, constraints.maxWidth, constraints.maxHeight) { fieldBounds = it }
        vm.newText?.takeIf { it.page == page }?.let { NewTextField(vm, it, constraints.maxWidth, constraints.maxHeight) { b -> fieldBounds = b } }
    }
}

/**
 * The field for new text, its top left corner where the page was tapped and as wide as the box the text will
 * wrap in on the page. A handle on its right edge drags the width; under it, chips choose the font the text
 * follows and its size.
 */
@Composable
private fun NewTextField(
    vm: PdfEditorViewModel,
    edit: PdfEditorViewModel.NewText,
    pageWidthPx: Int,
    pageHeightPx: Int,
    onBounds: (Rect) -> Unit,
) {
    val colors = KernTheme.colors
    val density = LocalDensity.current
    val left = (edit.at.x * pageWidthPx).roundToInt()
    val top = (edit.at.y * pageHeightPx).roundToInt()
    val widthPx = (edit.width * pageWidthPx).roundToInt()
    val pointPx = edit.pageWidthPoints?.let { pageWidthPx.toFloat() / it }
    val letters = ((pointPx ?: 0f) * edit.effectiveSize.toFloat()).coerceAtLeast(TextEditModel.MIN_LETTER_PX)
    val focus = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    var dragged by remember(edit.page, edit.at) { mutableStateOf(edit.width) }
    LaunchedEffect(edit.page, edit.at) { focus.requestFocus() }
    KeepInViewWithKeyboard(bring, edit.page, edit.at)
    Column(Modifier.offset { IntOffset(left, top) }.bringIntoViewRequester(bring)) {
    Column(
        Modifier.onGloballyPositioned { onBounds(it.boundsInParent()) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .width(with(density) { widthPx.toDp() })
                .heightIn(min = with(density) { (letters * NEW_LINE_HEIGHT).toDp() })
                .background(Color.White)
                .border(2.dp, colors.accent),
        ) {
            BasicTextField(
                value = vm.textDraft,
                onValueChange = vm::changeTextDraft,
                textStyle = TextStyle(fontSize = with(density) { letters.toSp() }, color = Color.Black),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = (WIDTH_HANDLE_WIDTH / 2).dp).focusRequester(focus),
            )
            SideHandle("Move the new text", Alignment.CenterStart, pageWidthPx) { dx -> vm.moveNewTextBy(dx / pageWidthPx) }
            SideHandle("Width of the new text", Alignment.CenterEnd, pageWidthPx) { dx ->
                dragged += dx / pageWidthPx
                vm.resizeNewText(dragged)
            }
        }
        Row(
            Modifier.background(colors.surface, RoundedCornerShape(KernRadius.innerSmall)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FontChip(vm, edit)
            SizeChip(vm, edit)
        }
    }
    // The bar over the foot of the page is not part of the field; scrolling the field into view leaves room for it.
    Spacer(Modifier.height(ANNOTATE_BAR_CLEARANCE))
    }
}

/** The font new text follows, and a menu of the page's fonts; on a page with no text there is no other to choose. */
@Composable
private fun FontChip(vm: PdfEditorViewModel, edit: PdfEditorViewModel.NewText) {
    val colors = KernTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        BarChip(TextEditModel.fontLabel(edit.font), selected = false, enabled = edit.fonts.fonts.size > 1) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface) {
            for (font in edit.fonts.fonts) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "${TextEditModel.fontName(font.font)}, ${TextEditModel.sizeLabel(font.size)}",
                            color = if (font == edit.font) colors.accent else colors.text,
                        )
                    },
                    onClick = { open = false; vm.chooseNewTextFont(font) },
                )
            }
        }
    }
}

/** The size new text is set in, and a menu of sizes to choose from. */
@Composable
private fun SizeChip(vm: PdfEditorViewModel, edit: PdfEditorViewModel.NewText) {
    val colors = KernTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        BarChip(TextEditModel.sizeLabel(edit.effectiveSize), selected = false) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface) {
            for (size in TextEditModel.SIZES) {
                DropdownMenuItem(
                    text = {
                        Text(TextEditModel.sizeLabel(size), color = if (size == edit.effectiveSize) colors.accent else colors.text)
                    },
                    onClick = { open = false; vm.chooseNewTextSize(size) },
                )
            }
        }
    }
}

/**
 * Scrolls the field [request] belongs to into view when it opens and again when the keyboard has finished
 * opening or closing: the page area is smaller by then, and a field that was in view before it may not be now.
 * [keys] say which field it is; a new one is brought into view as well.
 */
@OptIn(FlowPreview::class)
@Composable
private fun KeepInViewWithKeyboard(request: BringIntoViewRequester, vararg keys: Any) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(*keys) {
        snapshotFlow { ime.getBottom(density) }
            .distinctUntilChanged()
            .debounce(KEYBOARD_SETTLE_MS)
            .collect { request.bringIntoView() }
    }
}

/**
 * A handle on a side of a field: dragging it sideways calls [onDrag] with the distance in pixels. The handle
 * stands half outside the field, on the side [align] names.
 */
@Composable
private fun BoxScope.SideHandle(description: String, align: Alignment, pageWidthPx: Int, onDrag: (dx: Float) -> Unit) {
    val colors = KernTheme.colors
    val onStart = align == Alignment.CenterStart
    Box(
        Modifier
            .align(align)
            .offset(x = if (onStart) -WIDTH_HANDLE_WIDTH.dp / 2 else WIDTH_HANDLE_WIDTH.dp / 2)
            .size(WIDTH_HANDLE_WIDTH.dp, WIDTH_HANDLE_HEIGHT.dp)
            .background(colors.accent, RoundedCornerShape(WIDTH_HANDLE_WIDTH.dp / 2))
            .semantics { contentDescription = description }
            .pointerInput(pageWidthPx) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    onDrag(dx)
                }
            },
    )
}

/**
 * The text of the open block as a field laid over it, at the block's place and in letters the size of its
 * lines. A handle on each side drags the box: the left one moves it, the right one sets its width.
 */
@Composable
private fun TextEditField(
    vm: PdfEditorViewModel,
    edit: PdfEditorViewModel.TextEdit,
    pageWidthPx: Int,
    pageHeightPx: Int,
    onBounds: (Rect) -> Unit,
) {
    val colors = KernTheme.colors
    val density = LocalDensity.current
    val box = edit.block.box
    val left = (edit.boxLeft * pageWidthPx).roundToInt()
    val top = (box.top * pageHeightPx).roundToInt()
    val blockHeightPx = (box.bottom - box.top) * pageHeightPx
    val widthPx = (edit.boxWidth * pageWidthPx).roundToInt()
        .coerceAtLeast(with(density) { MIN_FIELD_WIDTH.dp.roundToPx() }).coerceAtMost(pageWidthPx - left)
    val letters = TextEditModel.letterPx(blockHeightPx, edit.block.lineCount)
    val focus = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    // A drag starts from the box's real width, not the least width the field is drawn at.
    var dragged by remember(edit.page, edit.block.id) { mutableStateOf(edit.boxWidth) }
    LaunchedEffect(edit.page, edit.block.id, edit.revision) { focus.requestFocus() }
    KeepInViewWithKeyboard(bring, edit.page, edit.block.id)
    Column(Modifier.offset { IntOffset(left, top) }.width(with(density) { widthPx.toDp() }).bringIntoViewRequester(bring)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = with(density) { blockHeightPx.toDp() })
                .background(Color.White)
                .border(2.dp, colors.accent)
                .onGloballyPositioned { onBounds(it.boundsInParent()) },
        ) {
            BasicTextField(
                value = vm.textDraft,
                onValueChange = vm::changeTextDraft,
                textStyle = TextStyle(fontSize = with(density) { letters.toSp() }, color = Color.Black),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = (WIDTH_HANDLE_WIDTH / 2).dp).focusRequester(focus),
            )
            SideHandle("Move the text box", Alignment.CenterStart, pageWidthPx) { dx -> vm.moveTextEditBy(dx / pageWidthPx) }
            SideHandle("Width of the text box", Alignment.CenterEnd, pageWidthPx) { dx ->
                dragged += dx / pageWidthPx
                vm.resizeTextEdit(dragged)
            }
        }
        // The bar over the foot of the page is not part of the field; scrolling the field into view leaves room for it.
        Spacer(Modifier.height(ANNOTATE_BAR_CLEARANCE))
    }
}

private const val BLOCK_DASH_ON = 4f
private const val BLOCK_DASH_OFF = 3f
private const val EDITABLE_ALPHA = 0.55f
private const val REFUSED_ALPHA = 0.45f

/** The height of a line of the new-text field, as a multiple of its letters' size. */
private const val NEW_LINE_HEIGHT = 1.4f

/** The size, in dp, of the handle that drags the width of new text. */
private const val WIDTH_HANDLE_WIDTH = 14
private const val WIDTH_HANDLE_HEIGHT = 36

/** How long, in milliseconds, the keyboard's height must hold still before the field is scrolled into view over what is left. */
private const val KEYBOARD_SETTLE_MS = 150L

/** How far, in dp, a touch may miss the open field, its handles and chips and still be a touch on them. */
private const val FIELD_TOUCH_REACH = 12

/** The narrowest a field is made, in dp: a block of one short word still gives room to type. */
private const val MIN_FIELD_WIDTH = 96
