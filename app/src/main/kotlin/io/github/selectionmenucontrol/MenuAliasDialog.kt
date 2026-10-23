package io.github.selectionmenucontrol

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayDialog

// Leave room for edits/composition and surrounding spaces without retaining oversized pastes.
private const val MaxAliasDraftLength = 256

@Composable
internal fun MenuAliasDialog(
    component: String,
    show: Boolean,
    originalLabel: String?,
    savedAlias: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onSave: (name: String, expectedAlias: String) -> Boolean,
) {
    val baseline = rememberSaveable(component) { savedAlias }
    var draft by rememberSaveable(component, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(savedAlias, selection = TextRange(savedAlias.length)))
    }
    var oversizedPaste by rememberSaveable(component) { mutableStateOf(false) }
    val normalized = remember(draft.text) {
        try { RuleConfig.normalizeAlias(draft.text) } catch (_: IllegalArgumentException) { null }
    }
    val conflict = show && savedAlias != baseline
    val error = when {
        originalLabel == null -> "该选项已不可用，请关闭后重新读取。"
        conflict -> "名称已发生变化，请关闭后重新编辑。"
        oversizedPaste -> "粘贴内容过长，未替换当前名称。"
        normalized == null -> "请输入不超过 20 个字符的可见单行名称。"
        else -> null
    }
    val canSave = show && enabled && error == null
    val focusRequester = remember(component) { FocusRequester() }
    fun save() {
        // IME may commit text and invoke Done before the next recomposition.
        if (!show || !enabled || originalLabel == null || savedAlias != baseline || oversizedPaste) return
        val name = try { RuleConfig.normalizeAlias(draft.text) } catch (_: IllegalArgumentException) { return }
        if (onSave(name, baseline)) onDismiss()
    }

    // Keep this composition alive with show=false until Miuix completes its IME/dim/slide exit.
    OverlayDialog(show = show, onDismissRequest = onDismiss, onDismissFinished = onDismissFinished, content = {
        Column(modifier = Modifier.dialogContent(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("修改菜单名称", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("原名：${originalLabel ?: "该选项已不可用"}", fontSize = 14.sp, lineHeight = 20.sp)
            TextField(value = draft, onValueChange = {
                if (show) {
                    if (it.text != draft.text) {
                        oversizedPaste = it.text.length > MaxAliasDraftLength
                        if (!oversizedPaste) draft = it
                    } else draft = it
                }
            }, label = "菜单名称", modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true, readOnly = !show,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                trailingIcon = {
                    IconButton(onClick = { draft = TextFieldValue(); oversizedPaste = false },
                        enabled = show && (draft.text.isNotEmpty() || oversizedPaste), modifier = Modifier.size(48.dp)) {
                        Image(painterResource(R.drawable.ic_clear), contentDescription = "恢复原名",
                            modifier = Modifier.size(19.dp), colorFilter = ColorFilter.tint(
                                MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(
                                    alpha = if (draft.text.isNotEmpty() || oversizedPaste) 1f else 0.3f)))
                    }
                })
            LaunchedEffect(component, show) {
                if (show) {
                    // This effect runs after the field's focus node has been applied.
                    focusRequester.requestFocus()
                }
            }
            Text("留空恢复原名，最多 20 个字符。", fontSize = 12.sp, lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
            Text(error ?: "重新打开文字菜单后生效。", fontSize = 12.sp, lineHeight = 18.sp,
                minLines = 2, color = if (error == null) MiuixTheme.colorScheme.onSurfaceContainerVariant
                    else MiuixTheme.colorScheme.error)
            DialogActions("取消", onDismiss, "保存", { save() }, confirmEnabled = canSave)
        }
    })
}
