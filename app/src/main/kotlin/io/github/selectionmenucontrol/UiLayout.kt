package io.github.selectionmenucontrol

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun Modifier.dialogContent(): Modifier {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return heightIn(max = with(density) { (window.height * 0.7f).toDp() })
        .verticalScroll(rememberScrollState())
}

@Composable
internal fun DialogActions(
    dismissLabel: String, onDismiss: () -> Unit,
    confirmLabel: String, onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    val fontScale = LocalDensity.current.fontScale
    // The constraints only affect measurement/placement, so no layout-time subcomposition is needed.
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        TextButton(text = dismissLabel, onClick = onDismiss)
        TextButton(text = confirmLabel, onClick = onConfirm,
            enabled = confirmEnabled, colors = ButtonDefaults.textButtonColorsPrimary())
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        if (width < 280.dp.roundToPx() || fontScale > 1.3f) {
            val confirm = measurables[1].measure(
                Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight))
            val gap = 8.dp.roundToPx().coerceAtMost(constraints.maxHeight - confirm.height)
            // offset preserves Infinity for the enclosing vertical scroll container.
            val dismiss = measurables[0].measure(
                Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight)
                    .offset(vertical = -confirm.height - gap))
            val height = constraints.constrainHeight(confirm.height + gap + dismiss.height)
            layout(width, height) {
                confirm.placeRelative(0, 0)
                dismiss.placeRelative(0, confirm.height + gap)
            }
        } else {
            val gap = 12.dp.roundToPx().coerceAtMost(width)
            val buttonSpace = width - gap
            val dismissWidth = (buttonSpace + 1) / 2
            val dismiss = measurables[0].measure(Constraints(minWidth = dismissWidth,
                maxWidth = dismissWidth, maxHeight = constraints.maxHeight))
            val confirmWidth = buttonSpace - dismissWidth
            val confirm = measurables[1].measure(Constraints(minWidth = confirmWidth,
                maxWidth = confirmWidth, maxHeight = constraints.maxHeight))
            val height = constraints.constrainHeight(maxOf(dismiss.height, confirm.height))
            layout(width, height) {
                dismiss.placeRelative(0, 0)
                confirm.placeRelative(dismiss.width + gap, 0)
            }
        }
    }
}
