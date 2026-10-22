package com.oruke.tagloom.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.BlockId
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.editor_block_actions_description

/** Block 左侧完整命中区；正文右侧预留相同宽度以保持阅读栏居中。 */
internal val BLOCK_GUTTER_WIDTH get() = if (PlatformUsesTouchTargets) 52.dp else 36.dp

/**
 * 统一点击菜单与直接拖动的六点手柄，隐藏时仍保留布局和键盘可达性。
 * @param blockId 该手柄对应的 Block。
 * @param visible 正文是否悬停、聚焦或已打开菜单。
 * @param firstLineCenter 文字首行相对块顶部的实际中心；非文本块为空。
 * @param onOpenActions 点击或键盘激活时打开 Block 菜单。
 * @param menu 锚定到完整按钮区域的操作菜单。
 */
@Composable
internal fun BlockGutter(
    blockId: BlockId,
    visible: Boolean,
    firstLineCenter: Dp?,
    onOpenActions: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val drag = LocalEditorBlockDrag.current
    val interaction = remember(blockId) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    var top by remember(blockId) { mutableFloatStateOf(0f) }
    val lifted = drag?.liftedIds?.contains(blockId) == true
    val opacity by animateFloatAsState(if (visible || hovered || focused || lifted) 1f else 0f, tween(100))
    val description = stringResource(Res.string.editor_block_actions_description)
    val shape = RoundedCornerShape(6.dp)
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val handleSize = if (PlatformUsesTouchTargets) 48.dp else 32.dp
    // 整个按钮与首行对齐，命中、焦点、悬停和拖动坐标随图标一起移动，原行占位保持稳定。
    Box(
        Modifier.width(BLOCK_GUTTER_WIDTH)
            .offset(y = firstLineCenter?.let { it - handleSize / 2 } ?: 0.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            Modifier.size(handleSize)
                .onGloballyPositioned { top = it.positionInRoot().y }
                .testTag("block-handle-${blockId.value}")
                .alpha(opacity).clip(shape)
                .background(
                    when {
                        lifted || pressed -> accent.copy(alpha = 0.12f)
                        hovered || focused -> ink.copy(alpha = 0.09f)
                        else -> Color.Transparent
                    }
                )
                .border(1.dp, if (focused) accent else Color.Transparent, shape)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(blockId, drag) {
                    detectDragGestures(
                        onDragStart = { position -> drag?.start(blockId, top + position.y) },
                        onDrag = { change, delta -> change.consume(); drag?.dragBy(delta.y) },
                        onDragEnd = { drag?.finish() },
                        onDragCancel = { drag?.finish(cancel = true) },
                    )
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onOpenActions
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(14.dp, 18.dp)) {
                for (column in 0..1) for (row in 0..2) {
                    drawCircle(
                        if (lifted) accent else ink, 1.35.dp.toPx(),
                        Offset(
                            size.width / 2 + (column - 0.5f) * 5.dp.toPx(),
                            size.height / 2 + (row - 1) * 5.dp.toPx()
                        )
                    )
                }
            }
        }
        menu()
    }
}
