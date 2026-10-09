package io.github.selectionmenucontrol

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

internal data class Processor(
    val component: String,
    val label: String,
    val summary: String,
    val fixed: Boolean,
    val classificationKnown: Boolean,
)

private data class RuleScreenData(
    val snapshot: SystemRuleStore.Snapshot,
    val processors: List<Processor>,
    val error: String? = null,
)

private data class RuleDragSession(
    val token: Int,
    val component: String,
    val height: Float,
    val grabOffset: Float,
    val pointerY: Float,
    val startOrder: List<String>,
)

private data class RuleDragCallbacks(
    val start: (Offset) -> Boolean,
    val move: (Float) -> Unit,
    val finish: (Boolean) -> Unit,
)

private enum class RestoreAction { HIDDEN, ORDER }

@Composable
internal fun RuleScreen(activity: MainActivity, onAbout: () -> Unit) {
    val editor = remember(activity) { ViewModelProvider(activity)[RuleEditorViewModel::class.java] }
    val saveState by editor.state.collectAsState()
    val emptySnapshot = remember { SystemRuleStore.Snapshot.empty() }
    val snapshot = saveState?.value ?: emptySnapshot
    var originalProcessors by remember { mutableStateOf(emptyList<Processor>()) }
    var displayedProcessors by remember { mutableStateOf(emptyList<Processor>()) }
    var initializing by remember { mutableStateOf(true) }
    var initializationError by remember { mutableStateOf<String?>(null) }
    var loadSignal by remember { mutableIntStateOf(0) }
    var dragSession by remember { mutableStateOf<RuleDragSession?>(null) }
    var dragToken by remember { mutableIntStateOf(0) }
    var optionSize by remember { mutableStateOf(IntSize.Zero) }
    var restoreAction by remember { mutableStateOf<RestoreAction?>(null) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val canChange = !initializing && initializationError == null && snapshot.valid
    val canUseActions = canChange && dragSession == null
    val gripStart = with(density) { 16.dp.toPx() }
    val gripEnd = with(density) { 64.dp.toPx() }
    val rowGap = with(density) { 3.dp.toPx() }
    val edgeSize = with(density) { 52.dp.toPx() }
    val maxScrollSpeed = with(density) { 460.dp.toPx() }

    fun movable(processor: Processor, hidden: Set<String> = snapshot.hiddenComponents) =
        processor.classificationKnown && !processor.fixed && processor.component !in hidden

    fun cancelDrag() {
        val current = dragSession ?: return
        dragSession = null
        val byComponent = displayedProcessors.associateBy { it.component }
        displayedProcessors = current.startOrder.mapNotNull(byComponent::get)
    }

    LaunchedEffect(loadSignal) {
        cancelDrag()
        initializing = true
        initializationError = null
        val beforeLoad = editor.state.value
        val result = withContext(Dispatchers.IO) {
            val draft = beforeLoad?.takeIf { it.pending }?.value?.takeIf { it.valid }
            val current = draft ?: SystemRuleStore.readApp(activity)
            when {
                !current.valid -> RuleScreenData(current, emptyList(), "规则数据无法读取，请恢复备份后重试")
                draft == null && !SystemRuleStore.migrateToGlobal(activity, current) ->
                    RuleScreenData(current, emptyList(), "规则迁移失败，请确认 Root 授权后重试")
                else -> try {
                    val migrated = draft ?: SystemRuleStore.readApp(activity)
                    if (!migrated.valid) {
                        RuleScreenData(migrated, emptyList(), "规则数据无法读取，请恢复备份后重试")
                    } else {
                        RuleScreenData(migrated, loadProcessors(activity, migrated.hiddenComponents))
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.e("SelectionMenuControl", "Cannot load PROCESS_TEXT activities", error)
                    RuleScreenData(current, emptyList(), "读取扩展项失败，请重试")
                }
            }
        }
        if (result.error == null && result.snapshot.valid) editor.initialize(activity, result.snapshot, beforeLoad)
        originalProcessors = result.processors
        displayedProcessors = applyOrder(result.processors, editor.state.value?.value?.orderedComponents ?: result.snapshot.orderedComponents)
        initializationError = result.error
        initializing = false
    }

    LaunchedEffect(saveState?.failureVersion) {
        if (editor.consumeFailure()) {
            dragSession = null
            val actual = editor.state.value?.value ?: return@LaunchedEffect
            displayedProcessors = applyOrder(originalProcessors, actual.orderedComponents)
            if (!actual.valid) initializationError = "规则数据无法读取，请恢复备份后重试"
            Toast.makeText(activity, "保存失败，已重新读取当前规则", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(saveState?.successVersion, saveState?.pending) {
        if (saveState?.pending != false) return@LaunchedEffect
        delay(250)
        if (editor.state.value?.pending == false && editor.consumeSuccess()) {
            Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
        }
    }

    fun save(next: SystemRuleStore.Snapshot) {
        if (initializing || initializationError != null || editor.state.value?.value?.valid != true || dragSession != null) return
        displayedProcessors = applyOrder(originalProcessors, next.orderedComponents)
        editor.save(next)
    }

    fun saveOrder() {
        val current = editor.state.value?.value ?: return
        val fixed = originalProcessors.filter { it.fixed }.mapTo(HashSet()) { it.component }
        val ordered = mergeOrder(current.orderedComponents, displayedProcessors.map { it.component }, fixed)
        save(SystemRuleStore.Snapshot(current.hiddenComponents, ordered))
    }

    fun changeHidden(component: String) {
        if (initializing || dragSession != null || initializationError != null) return
        val current = editor.state.value?.value ?: return
        if (!current.valid) return
        val hidden = current.hiddenComponents.toMutableSet()
        if (!hidden.add(component)) hidden.remove(component)
        save(SystemRuleStore.Snapshot(hidden, current.orderedComponents))
    }

    fun moveOne(component: String, direction: Int): Boolean {
        if (initializing || dragSession != null || initializationError != null) return false
        val current = editor.state.value?.value?.takeIf { it.valid } ?: return false
        val moving = displayedProcessors.filter { movable(it, current.hiddenComponents) }
        val from = moving.indexOfFirst { it.component == component }
        val to = from + direction
        if (from < 0 || to !in moving.indices) return false
        displayedProcessors = moveVisibleProcessors(displayedProcessors, current.hiddenComponents, component, moving[to].component)
        saveOrder()
        return true
    }

    fun finishDrag(commit: Boolean) {
        val current = dragSession ?: return
        if (!commit) { cancelDrag(); return }
        dragSession = null
        if (current.startOrder != displayedProcessors.map { it.component }) saveOrder()
    }

    val dragCallbacks by rememberUpdatedState(RuleDragCallbacks(
        start = start@{ position ->
            if (!canChange || restoreAction != null || dragSession != null || position.x !in gripStart..gripEnd) return@start false
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull {
                position.y >= it.offset + rowGap && position.y <= it.offset + it.size - rowGap
            } ?: return@start false
            val processor = displayedProcessors.getOrNull(item.index)?.takeIf { it.component == item.key } ?: return@start false
            val current = editor.state.value?.value?.takeIf { it.valid } ?: return@start false
            if (!movable(processor, current.hiddenComponents)) return@start false
            val bounds = optionBounds(listState, displayedProcessors.size)
            val grabOffset = position.y - item.offset
            if (RuleDragGeometry.top(position.y, grabOffset, item.size.toFloat(), bounds) == null) return@start false
            dragSession = RuleDragSession(++dragToken, processor.component, item.size.toFloat(), grabOffset, position.y,
                displayedProcessors.map { it.component })
            true
        },
        move = { pointerY -> dragSession = dragSession?.copy(pointerY = pointerY) },
        finish = ::finishDrag,
    ))

    // The viewport owns the gesture; virtualising or reordering its source row cannot stop it.
    val dragFrame by rememberUpdatedState<() -> Float> {
        val active = dragSession
        if (active == null) {
            0f
        } else {
            val displayed = displayedProcessors
            val bounds = optionBounds(listState, displayed.size)
            val top = RuleDragGeometry.top(active.pointerY, active.grabOffset, active.height, bounds)
            if (top == null || !canChange) {
                cancelDrag()
                0f
            } else {
                val sourceIndex = displayed.indexOfFirst { it.component == active.component }
                val hidden = editor.state.value?.value?.hiddenComponents ?: snapshot.hiddenComponents
                val movingKeys = displayed.filter { movable(it, hidden) }.mapTo(HashSet()) { it.component }
                // A layout predating a reorder must not become another insertion decision.
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val layoutMatches = visibleItems.all { displayed.getOrNull(it.index)?.component == it.key }
                val target = if (layoutMatches) RuleDragGeometry.target(
                    sourceIndex, top + active.height / 2f,
                    visibleItems.map { RuleDragCell(it.key as String, it.index, it.offset.toFloat(), it.size.toFloat()) },
                    movingKeys,
                ) else null
                if (target != null) {
                    displayedProcessors = moveVisibleProcessors(displayedProcessors, hidden, active.component, target)
                }
                val viewportTop = listState.layoutInfo.viewportStartOffset.toFloat()
                val viewportBottom = listState.layoutInfo.viewportEndOffset.toFloat()
                when {
                    active.pointerY < viewportTop + edgeSize && listState.canScrollBackward ->
                        -maxScrollSpeed * ((viewportTop + edgeSize - active.pointerY) / edgeSize).coerceIn(0f, 1f)
                    active.pointerY > viewportBottom - edgeSize && listState.canScrollForward ->
                        maxScrollSpeed * ((active.pointerY - viewportBottom + edgeSize) / edgeSize).coerceIn(0f, 1f)
                    else -> 0f
                }
            }
        }
    }

    LaunchedEffect(dragSession?.token) {
        val token = dragSession?.token ?: return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (isActive && dragSession?.token == token) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.04f)
            previousFrame = frame
            val speed = dragFrame()
            if (speed != 0f && dragSession?.token == token) listState.scrollBy(speed * seconds)
        }
    }

    LaunchedEffect(optionSize, windowFocused) {
        // Changed coordinates or focus invalidate the current grab.
        if (dragSession != null) cancelDrag()
    }
    DisposableEffect(Unit) { onDispose { dragSession = null } }
    BackHandler(enabled = initializing || dragSession != null) { cancelDrag() }

    Column(
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("文本选择菜单", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onAbout, enabled = canUseActions) {
                    Image(painterResource(R.drawable.ic_info), "关于", modifier = Modifier.size(24.dp))
                }
            }
            Text("勾选隐藏 · 拖动排序 · 自动保存", modifier = Modifier.padding(top = 4.dp),
                fontSize = 13.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
            if (originalProcessors.isNotEmpty()) {
                Text(
                    if (originalProcessors.any { !it.classificationKnown }) "部分选项排序检测不可用；仍可设置隐藏。"
                    else "锁定项由系统安排位置，隐藏项取消勾选后可移动。",
                    modifier = Modifier.padding(top = 5.dp), fontSize = 11.sp, lineHeight = 16.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .onSizeChanged {
                    if (optionSize != it) cancelDrag()
                    optionSize = it
                }
                .pointerInput(listState) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (!dragCallbacks.start(down.position)) return@awaitEachGesture
                        down.consume()
                        var commit = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (event.changes.any { it.id != down.id && it.pressed }) {
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                if (!pointer.pressed) {
                                    // Compose synthesises an already-consumed UP on ACTION_CANCEL.
                                    // Only an unconsumed, real UP commits the preview.
                                    commit = pointer.changedToUp()
                                    if (commit) {
                                        dragCallbacks.move(pointer.position.y)
                                        dragFrame()
                                    }
                                    pointer.consume()
                                    break
                                }
                                dragCallbacks.move(pointer.position.y)
                                pointer.consume()
                            }
                        } finally {
                            dragCallbacks.finish(commit)
                        }
                    }
                },
        ) {
            if (initializing || initializationError != null || originalProcessors.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        when {
                            initializing -> "正在读取系统扩展项…"
                            initializationError != null -> initializationError!!
                            else -> "当前没有可配置的文字处理扩展项"
                        },
                        modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2,
                    )
                    if (!initializing && initializationError != null) {
                        TextButton(text = "重新读取", onClick = { loadSignal++ }, modifier = Modifier.fillMaxWidth())
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(displayedProcessors, key = { it.component }) { processor ->
                        val hidden = processor.component in snapshot.hiddenComponents
                        val lockedReason = when {
                            processor.fixed -> "位置由系统固定，不能移动"
                            !processor.classificationKnown -> "排序检测不可用"
                            hidden -> "已隐藏，取消隐藏后可移动"
                            else -> null
                        }
                        val moving = displayedProcessors.filter { movable(it) }
                        val index = moving.indexOfFirst { it.component == processor.component }
                        val actions = if (canUseActions && movable(processor)) buildList {
                            if (index > 0) add(CustomAccessibilityAction("上移") { moveOne(processor.component, -1) })
                            if (index >= 0 && index < moving.lastIndex) add(CustomAccessibilityAction("下移") { moveOne(processor.component, 1) })
                        } else emptyList()
                        val isPlaceholder = dragSession?.component == processor.component
                        ProcessorRuleRow(
                            processor = processor, hidden = hidden, enabled = canChange,
                            handleEnabled = canChange && lockedReason == null, lockedReason = lockedReason,
                            accessibilityActions = actions, interactive = !isPlaceholder,
                            modifier = if (isPlaceholder) Modifier.alpha(0f).clearAndSetSemantics { } else Modifier,
                            onCheckedChange = { changeHidden(processor.component) },
                        )
                    }
                }
                dragSession?.let { active ->
                    val processor = displayedProcessors.firstOrNull { it.component == active.component }
                    val top = RuleDragGeometry.top(active.pointerY, active.grabOffset, active.height,
                        optionBounds(listState, displayedProcessors.size))
                    if (processor != null && top != null) {
                        ProcessorRuleRow(
                            processor = processor, hidden = processor.component in snapshot.hiddenComponents,
                            enabled = true, handleEnabled = true, interactive = false, isDragging = true,
                            modifier = Modifier.fillMaxWidth()
                                .offset { IntOffset(0, overlayPixelTop(top, active.height,
                                    optionBounds(listState, displayedProcessors.size))) }
                                .height(with(density) { active.height.toDp() }).clearAndSetSemantics { },
                            onCheckedChange = { },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(
                text = "恢复默认排序", onClick = { restoreAction = RestoreAction.ORDER },
                enabled = canUseActions && snapshot.orderedComponents.isNotEmpty(),
                modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            TextButton(
                text = "恢复全部显示", onClick = { restoreAction = RestoreAction.HIDDEN },
                enabled = canUseActions && snapshot.hiddenComponents.isNotEmpty(), modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    textColor = Color(0xFFD14343), disabledTextColor = Color(0xFFD14343).copy(alpha = 0.4f),
                ),
            )
        }
    }

    restoreAction?.let { action ->
        val restoringOrder = action == RestoreAction.ORDER
        WindowDialog(show = true, onDismissRequest = { restoreAction = null }, content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (restoringOrder) "恢复默认排序" else "恢复全部显示", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (restoringOrder) "将清除扩展项的自定义排序，隐藏设置保持不变。"
                    else "将清除隐藏规则，当前排序保持不变。",
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "取消", onClick = { restoreAction = null }, modifier = Modifier.weight(1f))
                    TextButton(
                        text = "确认恢复", modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            restoreAction = null
                            val current = editor.state.value?.value ?: return@TextButton
                            if (restoringOrder) save(SystemRuleStore.Snapshot(current.hiddenComponents, emptyList()))
                            else save(SystemRuleStore.Snapshot(emptySet(), current.orderedComponents))
                        },
                    )
                }
            }
        })
    }
}

private fun optionBounds(state: LazyListState, count: Int): RuleDragBounds? {
    if (count == 0) return null
    val layout = state.layoutInfo
    return RuleDragGeometry.bounds(
        layout.viewportStartOffset.toFloat(), layout.viewportEndOffset.toFloat(),
        layout.visibleItemsInfo.firstOrNull { it.index == 0 }?.offset?.toFloat(),
        layout.visibleItemsInfo.firstOrNull { it.index == count - 1 }?.let { (it.offset + it.size).toFloat() },
    )
}

private fun overlayPixelTop(top: Float, height: Float, bounds: RuleDragBounds?): Int {
    if (bounds == null) return top.roundToInt()
    val first = kotlin.math.ceil(bounds.top).toInt()
    val last = kotlin.math.floor(bounds.bottom - height).toInt()
    return if (last >= first) top.roundToInt().coerceIn(first, last) else first
}

@Composable
private fun ProcessorRuleRow(
    processor: Processor,
    hidden: Boolean,
    enabled: Boolean,
    handleEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    lockedReason: String? = null,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
    interactive: Boolean = true,
    isDragging: Boolean = false,
) {
    var pressed by remember(processor.component) { mutableStateOf(false) }
    val change by rememberUpdatedState(onCheckedChange)
    val currentHidden by rememberUpdatedState(hidden)
    val active = isDragging || pressed
    val interactionModifier = if (interactive) Modifier
        .pointerInput(enabled) {
            if (enabled) detectTapGestures(
                onPress = {
                    pressed = true
                    try { tryAwaitRelease() } finally { pressed = false }
                },
                onTap = { change(!currentHidden) },
            )
        }
        .semantics(mergeDescendants = true) {
            contentDescription = "${processor.label}，${processor.summary}" + (lockedReason?.let { "，$it" } ?: "")
            role = Role.Checkbox
            toggleableState = ToggleableState(hidden)
            customActions = accessibilityActions
            if (!enabled) disabled()
            onClick { if (enabled) { change(!currentHidden); true } else false }
        } else Modifier
    DisposableEffect(processor.component) { onDispose { pressed = false } }
    Card(modifier = modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp)
                .background(if (active) MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.09f) else Color.Transparent)
                .then(interactionModifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val handleColor = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = if (enabled) 0.58f else 0.3f)
            Box(
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = if (handleEnabled) "拖动${processor.label}调整顺序" else "${processor.label}，$lockedReason"
                },
                contentAlignment = Alignment.Center,
            ) {
                if (lockedReason == null) DragGrip(handleColor) else LockGrip(handleColor)
            }
            Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(processor.label, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = if (enabled) 1f else 0.45f))
                Text(processor.summary, modifier = Modifier.padding(top = 2.dp), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = if (enabled) 0.55f else 0.3f))
            }
            Box(modifier = Modifier.padding(start = 10.dp, end = 16.dp)) { RuleCheck(hidden, enabled) }
        }
    }
}

@Composable
private fun RuleCheck(checked: Boolean, enabled: Boolean) {
    val foreground = Color.White.copy(alpha = if (enabled) 1f else 0.6f)
    val selected = Color(0xFF536B9F).copy(alpha = if (enabled) 1f else 0.4f)
    val empty = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = if (enabled) 0.1f else 0.05f)
    Canvas(modifier = Modifier.size(25.dp)) {
        drawCircle(if (checked) selected else empty)
        if (checked) {
            val path = Path().apply {
                moveTo(size.width * 0.27f, size.height * 0.5f)
                lineTo(size.width * 0.44f, size.height * 0.66f)
                lineTo(size.width * 0.73f, size.height * 0.35f)
            }
            drawPath(path, foreground, style = Stroke(width = 2.3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Composable
private fun DragGrip(color: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val radius = 1.6.dp.toPx()
        for (column in 0..1) for (row in 0..2) {
            drawCircle(color, radius, Offset(size.width * (0.3f + column * 0.4f), size.height * (0.2f + row * 0.3f)))
        }
    }
}

@Composable
private fun LockGrip(color: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = Stroke(width = 1.7.dp.toPx())
        drawArc(color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(6.dp.toPx(), 2.dp.toPx()), size = Size(8.dp.toPx(), 12.dp.toPx()), style = stroke)
        drawRoundRect(color, topLeft = Offset(4.dp.toPx(), 8.dp.toPx()), size = Size(12.dp.toPx(), 10.dp.toPx()),
            cornerRadius = CornerRadius(1.8.dp.toPx()), style = stroke)
        drawCircle(color, 1.1.dp.toPx(), Offset(10.dp.toPx(), 12.dp.toPx()))
        drawLine(color, Offset(10.dp.toPx(), 12.dp.toPx()), Offset(10.dp.toPx(), 15.dp.toPx()), 1.7.dp.toPx())
    }
}

internal fun moveVisibleProcessors(original: List<Processor>, hiddenComponents: Set<String>, from: String, to: String) =
    moveRuleSlots(original, { it.component }, { it.classificationKnown && !it.fixed && it.component !in hiddenComponents }, from, to)

internal fun applyOrder(original: List<Processor>, orderedComponents: List<String>): List<Processor> =
    RuleConfig(emptySet(), orderedComponents).apply(original, { it.component }, { it.fixed || !it.classificationKnown })

internal fun mergeOrder(previous: List<String>, visible: List<String>, fixed: Set<String>): List<String> {
    val ordinaryVisible = visible.filterNot { it in fixed }.distinct()
    val ordinaryPrevious = previous.filterNot { it in fixed }.distinct()
    val visibleSet = ordinaryVisible.toSet()
    val remaining = ordinaryVisible.iterator()
    return buildList {
        for (component in ordinaryPrevious) {
            if (component in visibleSet) { if (remaining.hasNext()) add(remaining.next()) }
            else add(component)
        }
        while (remaining.hasNext()) add(remaining.next())
    }
}

@Suppress("UseKtx")
private fun loadProcessors(activity: ComponentActivity, hiddenComponents: Set<String>): List<Processor> {
    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(SystemRuleStore.QUERY_ORIGINAL_EXTRA, true)
    val packageManager = activity.packageManager
    val infos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    val processors = ArrayList<Processor>()
    val knownComponents = HashSet<String>()
    for (info in infos) {
        val activityInfo = info.activityInfo ?: continue
        val component = ComponentName(activityInfo.packageName, activityInfo.name)
        val flattened = component.flattenToString()
        if (!knownComponents.add(flattened)) continue
        val label = info.loadLabel(packageManager).toString().ifBlank { component.shortClassName }
        val classification = activityInfo.metaData?.getInt(SystemRuleStore.MENU_CLASSIFICATION_KEY, 0) ?: 0
        processors.add(Processor(flattened, label, activityInfo.packageName, fixed = classification == 2,
            classificationKnown = classification == 1 || classification == 2))
    }
    // The fallback only recovers hiding controls; it cannot invent a classification result.
    for (flattened in hiddenComponents) {
        if (flattened in knownComponents) continue
        val component = ComponentName.unflattenFromString(flattened) ?: continue
        val info = try { packageManager.getActivityInfo(component, PackageManager.MATCH_ALL) }
        catch (_: PackageManager.NameNotFoundException) { null } ?: continue
        val label = info.loadLabel(packageManager).toString().ifBlank { component.shortClassName }
        processors.add(Processor(component.flattenToString(), label, info.packageName, fixed = false, classificationKnown = false))
    }
    return processors
}
