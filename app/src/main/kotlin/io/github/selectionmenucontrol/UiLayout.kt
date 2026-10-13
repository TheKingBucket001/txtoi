package io.github.selectionmenucontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
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
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        @Composable fun Dismiss(modifier: Modifier) {
            TextButton(text = dismissLabel, onClick = onDismiss, modifier = modifier)
        }
        @Composable fun Confirm(modifier: Modifier) {
            TextButton(text = confirmLabel, onClick = onConfirm, modifier = modifier,
                enabled = confirmEnabled, colors = ButtonDefaults.textButtonColorsPrimary())
        }
        if (maxWidth < 280.dp || fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Confirm(Modifier.fillMaxWidth())
            Dismiss(Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dismiss(Modifier.weight(1f))
            Confirm(Modifier.weight(1f))
        }
    }
}
