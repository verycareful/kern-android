package dev.kern.editors.pdf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.kern.pdfbridge.PdfOp
import dev.kern.shared.theme.KernRadius
import dev.kern.shared.theme.KernTheme
import dev.kern.shared.theme.KernType
import dev.kern.shared.ui.KernBottomSheet
import dev.kern.shared.ui.KernIcons
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val TileMinWidth = 104.dp
private val TileRenderWidth = 128.dp
private val GridGap = 10.dp
private const val TILE_ASPECT = 0.75f
private const val SHEET_GRID_SCREEN_FRACTION = 0.6f
private const val EDGE_SCROLL_ZONE = 0.15f
private const val EDGE_SCROLL_STEP_PX = 24f
private const val FRAME_MS = 16L
private const val DRAGGED_ALPHA = 0.4f

internal const val TILE_TAG_PREFIX = "organise-page-"
internal const val ROTATE_LEFT_LABEL = "Rotate left"
internal const val ROTATE_RIGHT_LABEL = "Rotate right"
internal const val DELETE_LABEL = "Delete"
internal const val CONFIRM_DELETE_TAG = "organise-confirm-delete"

/**
 * The Organise pages sheet: every page as a picture. Tap pages to select them, press and hold one
 * and drag it to move it; rotate and delete act on the selection. Each action is one edit of the
 * open document, so it goes through the first-edit warning and can be undone from the top bar.
 */
@Composable
internal fun OrganisePagesSheet(vm: PdfEditorViewModel, onDismiss: () -> Unit) {
    val colors = KernTheme.colors
    val count = vm.pageCount
    val busy = vm.editBusy
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(count) { selected = selected.filterTo(mutableSetOf()) { it < count } }

    fun pageAt(point: Offset): Int? {
        val at = IntOffset(point.x.roundToInt(), point.y.roundToInt())
        return gridState.layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(at) }?.index
    }
    val hover by remember { derivedStateOf { if (dragFrom == null) null else pageAt(dragPos) } }

    // Held at the top or bottom edge of the grid, a drag scrolls it, so a page can travel far.
    LaunchedEffect(dragFrom != null) {
        while (dragFrom != null && isActive) {
            val height = gridState.layoutInfo.viewportSize.height
            val zone = height * EDGE_SCROLL_ZONE
            when {
                dragPos.y < zone -> gridState.scrollBy(-EDGE_SCROLL_STEP_PX)
                dragPos.y > height - zone -> gridState.scrollBy(EDGE_SCROLL_STEP_PX)
            }
            delay(FRAME_MS)
        }
    }

    fun drop() {
        val from = dragFrom
        val to = hover
        dragFrom = null
        if (from != null && to != null && from != to && !busy) {
            selected = emptySet()
            vm.requestEdit(PdfOp.Reorder(PageOrder.move(count, from, to)))
        }
    }

    val allSelected = count > 0 && selected.size == count
    val gridHeight = (LocalConfiguration.current.screenHeightDp * SHEET_GRID_SCREEN_FRACTION).dp

    KernBottomSheet(onDismiss = onDismiss, title = "Organise pages") {
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selected.isEmpty()) "Tap pages to select. Press and hold a page, then drag it to move it."
                else "${selected.size} selected",
                style = KernType.caption,
                color = colors.textMid,
                modifier = Modifier.weight(1f),
            )
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
            TextButton(onClick = { selected = if (selected.isEmpty()) (0 until count).toSet() else emptySet() }) {
                Text(if (selected.isEmpty()) "Select all" else "Clear", color = colors.accent)
            }
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(TileMinWidth),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(GridGap),
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight)
                .testTag("organise-grid")
                .pointerInput(busy, count) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at -> if (!busy) { dragFrom = pageAt(at); dragPos = at } },
                        onDrag = { change, _ -> change.consume(); dragPos = change.position },
                        onDragEnd = ::drop,
                        onDragCancel = { dragFrom = null },
                    )
                },
        ) {
            items(count, key = { it }) { index ->
                PageTile(
                    vm = vm,
                    index = index,
                    selected = index in selected,
                    dragged = dragFrom == index,
                    target = dragFrom != null && hover == index && hover != dragFrom,
                    onToggle = { selected = PageOrder.toggle(selected, index) },
                )
            }
        }

        if (allSelected) {
            Text(
                "A PDF keeps at least one page, so not every page can be deleted.",
                style = KernType.caption,
                color = colors.textDim,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val any = selected.isNotEmpty() && !busy
            ActionButton(KernIcons.RotateLeft, ROTATE_LEFT_LABEL, enabled = any) {
                vm.requestEdit(PageOrder.rotate(selected, PageOrder.ROTATE_LEFT))
            }
            ActionButton(KernIcons.RotateRight, ROTATE_RIGHT_LABEL, enabled = any) {
                vm.requestEdit(PageOrder.rotate(selected, PageOrder.ROTATE_RIGHT))
            }
            ActionButton(KernIcons.Trash, DELETE_LABEL, enabled = !busy && PageOrder.canRemove(count, selected), danger = true) {
                confirmDelete = true
            }
        }
    }

    if (confirmDelete) {
        val pages = selected
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = colors.surface,
            title = { Text(if (pages.size == 1) "Delete 1 page?" else "Delete ${pages.size} pages?") },
            text = { Text("They are removed from this PDF. Undo brings them back.") },
            confirmButton = {
                TextButton(modifier = Modifier.testTag(CONFIRM_DELETE_TAG), onClick = {
                    confirmDelete = false
                    if (PageOrder.canRemove(count, pages)) {
                        selected = emptySet()
                        vm.requestEdit(PageOrder.remove(pages))
                    }
                }) { Text("Delete", color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = colors.accent) } },
        )
    }
}

/** One page: its picture, its number, and whether it is selected, being dragged or where a drag would drop. */
@Composable
private fun PageTile(
    vm: PdfEditorViewModel,
    index: Int,
    selected: Boolean,
    dragged: Boolean,
    target: Boolean,
    onToggle: () -> Unit,
) {
    val colors = KernTheme.colors
    val widthPx = with(LocalDensity.current) { TileRenderWidth.roundToPx() }
    val version = vm.pageVersion(index)
    val epoch = vm.documentEpoch
    // The old picture stays until the new one is ready, so a tile never flashes empty.
    val picture by produceState<ImageBitmap?>(null, index, version, epoch) {
        value = vm.renderPage(index, widthPx)?.asImageBitmap() ?: value
    }
    val border = when {
        target -> BorderStroke(3.dp, colors.accent)
        selected -> BorderStroke(2.dp, colors.accent)
        else -> BorderStroke(1.dp, colors.borderSoft)
    }
    Box(
        Modifier
            .testTag("$TILE_TAG_PREFIX$index")
            .fillMaxWidth()
            .aspectRatio(TILE_ASPECT)
            .alpha(if (dragged) DRAGGED_ALPHA else 1f)
            .clip(RoundedCornerShape(KernRadius.pdfPage))
            .background(colors.sunken)
            .border(border, RoundedCornerShape(KernRadius.pdfPage))
            .semantics { this.selected = selected; contentDescription = "Page ${index + 1}" }
            .clickable(onClickLabel = if (selected) "Deselect" else "Select", onClick = onToggle),
    ) {
        picture?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
        Text(
            "${index + 1}",
            style = KernType.meta,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(KernRadius.pill))
                .background(colors.surface.copy(alpha = 0.85f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(KernIcons.Check, contentDescription = null, tint = colors.accentOn, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val colors = KernTheme.colors
    val tint: Color = when {
        !enabled -> colors.textDim.copy(alpha = 0.45f)
        danger -> colors.danger
        else -> colors.accent
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(KernRadius.iconButton))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .testTag(label),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = KernType.caption, color = tint)
    }
}
