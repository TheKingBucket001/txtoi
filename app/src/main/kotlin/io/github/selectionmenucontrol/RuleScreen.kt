package io.github.selectionmenucontrol

import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

internal class RuleMovementIndex private constructor(
    val source: List<Processor>,
    val hidden: Set<String>,
    val components: List<String>,
    val positions: Map<String, Int>,
    val displayedPositions: Map<String, Int>,
) {
    val keys: Set<String> get() = positions.keys

    companion object {
        fun create(processors: List<Processor>, hidden: Set<String>): RuleMovementIndex {
            val moving = processors.filter { it.classificationKnown && !it.fixed && it.component !in hidden }
                .map { it.component }
            return RuleMovementIndex(processors, hidden, moving,
                moving.withIndex().associate { it.value to it.index },
                processors.withIndex().associate { it.value.component to it.index })
        }
    }
}

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
    val startPointerY: Float,
    val pickupPaintedTop: Float,
    val pickupSlotTop: Float,
    val moved: Boolean = false,
    val startOrder: List<String>,
    val startRules: SystemRuleStore.Snapshot,
)

private data class RuleReleasedDrag(
    val token: Int,
    val component: String,
    val height: Float,
    val top: Animatable<Float, AnimationVector1D>,
    val order: List<String>,
    val hidden: Set<String>,
)

private class RuleVisualPositions {
    var viewport: LayoutCoordinates? = null
    val rows = HashMap<String, LayoutCoordinates>()

    fun top(component: String): Float? {
        val parent = viewport?.takeIf { it.isAttached } ?: return null
        val row = rows[component]?.takeIf { it.isAttached } ?: return null
        return parent.localPositionOf(row, Offset.Zero).y.takeIf { it.isFinite() }
    }
}

private const val RuleReorderStiffness = 800f

private data class RuleDragCallbacks(
    val start: (Offset) -> Boolean,
    val move: (Float) -> Unit,
    val finish: (Boolean) -> Unit,
)

private enum class RestoreAction { HIDDEN, ORDER }

@Composable
internal fun RuleScreen(
    activity: MainActivity,
    awaitEnvironmentCheck: suspend () -> Unit,
    modifier: Modifier = Modifier,
    onAbout: () -> Unit,
) {
    val editor = remember(activity) { ViewModelProvider(activity)[RuleEditorViewModel::class.java] }
    val saveState by editor.state.collectAsState()
    val recoveryChecking by editor.checking.collectAsState()
    val emptySnapshot = remember { SystemRuleStore.Snapshot.empty() }
    val snapshot = saveState?.value ?: emptySnapshot
    var originalProcessors by remember { mutableStateOf(emptyList<Processor>()) }
    var displayedProcessors by remember { mutableStateOf(emptyList<Processor>()) }
    var initializing by remember { mutableStateOf(true) }
    var initializationError by remember { mutableStateOf<String?>(null) }
    var loadSignal by remember { mutableIntStateOf(0) }
    var dragSession by remember { mutableStateOf<RuleDragSession?>(null) }
    var releasedDrag by remember { mutableStateOf<RuleReleasedDrag?>(null) }
    var dragToken by remember { mutableIntStateOf(0) }
    var optionSize by remember { mutableStateOf(IntSize.Zero) }
    var coordinateState by remember { mutableStateOf<Pair<IntSize, Boolean>?>(null) }
    var restoreAction by rememberSaveable { mutableStateOf<RestoreAction?>(null) }
    var discardPrompt by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(saveState?.verificationPending) {
        if (saveState?.verificationPending != true) discardPrompt = false
    }
    val movement = remember(displayedProcessors, snapshot.hiddenComponents) {
        RuleMovementIndex.create(displayedProcessors, snapshot.hiddenComponents)
    }
    val listState = rememberLazyListState()
    val visualPositions = remember { RuleVisualPositions() }
    val density = LocalDensity.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val canChange = !initializing && !recoveryChecking && initializationError == null && snapshot.valid && saveState?.verificationPending != true
    val canUseActions = canChange && dragSession == null
    val gripStart = with(density) { 16.dp.toPx() }
    val gripEnd = with(density) { 64.dp.toPx() }
    val rowGap = with(density) { 3.dp.toPx() }
    val edgeSize = with(density) { 52.dp.toPx() }
    val maxScrollSpeed = with(density) { 460.dp.toPx() }

    fun movable(processor: Processor, hidden: Set<String> = snapshot.hiddenComponents) =
        processor.classificationKnown && !processor.fixed && processor.component !in hidden

    fun movementFor(hidden: Set<String>): RuleMovementIndex =
        if (movement.source === displayedProcessors && movement.hidden == hidden) movement
        else RuleMovementIndex.create(displayedProcessors, hidden)

    fun reorderPresentation(next: List<Processor>) {
        val firstIndex = listState.firstVisibleItemIndex
        val firstKey = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == firstIndex }?.key as? String
        if (firstKey != null && displayedProcessors.getOrNull(firstIndex)?.component == firstKey) {
            val nextIndex = next.indexOfFirst { it.component == firstKey }
            if (nextIndex >= 0 && nextIndex != firstIndex) {
                // Internal reorders keep the viewport at its slot; package refreshes still follow keys.
                listState.requestScrollToItem(firstIndex, listState.firstVisibleItemScrollOffset)
            }
        }
        displayedProcessors = next
    }

    fun cancelDrag() {
        releasedDrag = null
        val current = dragSession ?: return
        dragSession = null
        val byComponent = displayedProcessors.associateBy { it.component }
        reorderPresentation(current.startOrder.mapNotNull(byComponent::get))
    }

    fun synchronizePresentation() {
        if (dragSession != null) cancelDrag()
        val latest = editor.state.value?.value?.takeIf { it.valid }
        if (latest == null) { releasedDrag = null; return }
        val next = applyOrder(originalProcessors, latest.orderedComponents)
        // Our own UP save can replay this same order while its visual handoff is running.
        val released = releasedDrag
        if (released != null && (released.order != next.map { it.component }
                || released.hidden != latest.hiddenComponents)) releasedDrag = null
        displayedProcessors = next
    }

    fun dragRulesUnchanged(start: SystemRuleStore.Snapshot): Boolean {
        val latest = editor.state.value ?: return false
        return !editor.checking.value && !latest.verificationPending && latest.value.valid
            && latest.value.hiddenComponents == start.hiddenComponents
            && latest.value.orderedComponents == start.orderedComponents
    }

    val refreshPackages by rememberUpdatedState { loadSignal++ }
    val cancelCurrentDrag by rememberUpdatedState(::cancelDrag)
    DisposableEffect(activity) {
        var skipInitialResume = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (skipInitialResume) skipInitialResume = false else refreshPackages()
                }
                Lifecycle.Event.ON_STOP -> cancelCurrentDrag()
                else -> Unit
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshPackages()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            activity.registerReceiver(receiver, filter)
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            activity.unregisterReceiver(receiver)
        }
    }

    LaunchedEffect(loadSignal) {
        cancelDrag()
        if (loadSignal != 0) delay(150) // Coalesce replace/remove/add broadcasts into one read.
        initializing = true
        initializationError = null
        // Scope readiness can precede probe cleanup. Its Root transaction must release
        // the shared lock before migration or durable-draft recovery starts.
        awaitEnvironmentCheck()
        val beforeLoad = editor.state.value
        val result = withContext(Dispatchers.IO) {
            try {
                val draft = beforeLoad?.takeIf { it.pending && !it.verificationPending }?.value?.takeIf { it.valid }
                val requiresVerification = beforeLoad?.verificationPending == true
                val current = draft ?: if (requiresVerification) SystemRuleStore.readGlobal(activity) else SystemRuleStore.readApp(activity)
                when {
                    !current.valid -> RuleScreenData(current, emptyList(), "无法读取设置，请重新读取")
                    draft == null && !requiresVerification && !SystemRuleStore.migrateToGlobal(activity, current) ->
                        RuleScreenData(current, emptyList(), "无法载入设置，请确认 Root 授权后重试")
                    else -> try {
                        val migrated = draft ?: if (requiresVerification) SystemRuleStore.readGlobal(activity) else SystemRuleStore.readApp(activity)
                        if (!migrated.valid) {
                            RuleScreenData(migrated, emptyList(), "无法读取设置，请重新读取")
                        } else {
                            RuleScreenData(migrated, loadProcessors(activity, migrated.hiddenComponents))
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        ModuleDiagnostics.fault(ModuleDiagnostics.Code.QUERY_FAILED)
                        RuleScreenData(current, emptyList(), "读取选项失败，请重试")
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ModuleDiagnostics.fault(ModuleDiagnostics.Code.RULES_READ_FAILED)
                RuleScreenData(beforeLoad?.value ?: emptySnapshot, emptyList(), "读取设置失败，请重新读取")
            }
        }
        if (result.error == null && result.snapshot.valid) editor.initialize(activity, result.snapshot, beforeLoad)
        if (result.error == null) ModuleDiagnostics.recover(ModuleDiagnostics.Code.QUERY_FAILED)
        originalProcessors = result.processors
        displayedProcessors = applyOrder(result.processors, editor.state.value?.value?.orderedComponents ?: result.snapshot.orderedComponents)
        initializationError = result.error
        initializing = false
    }

    // Saved-state replay and external refresh can update the editor after the page has loaded.
    // Preview moves change displayedProcessors only, so they never trigger this effect.
    LaunchedEffect(originalProcessors, saveState?.value?.orderedComponents,
        saveState?.value?.hiddenComponents, initializing, initializationError) {
        if (!initializing && initializationError == null) synchronizePresentation()
    }

    LaunchedEffect(saveState?.failureVersion, loadSignal) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if (editor.consumeFailure()) {
            dragSession = null
            releasedDrag = null
            val result = editor.state.value ?: return@LaunchedEffect
            val actual = result.value
            displayedProcessors = applyOrder(originalProcessors, actual.orderedComponents)
            if (!actual.valid) initializationError = "无法读取设置，请重新核对"
            Toast.makeText(activity,
                when {
                    result.verificationPending -> "保存结果待确认，请重新核对"
                    !actual.valid -> "保存未完成，暂时无法读取设置"
                    else -> "保存未完成，已核对当前设置"
                }, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(saveState?.successVersion, saveState?.pending, saveState?.verificationPending, loadSignal) {
        if (saveState?.pending != false) return@LaunchedEffect
        delay(250)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            && editor.state.value?.pending == false && editor.state.value?.verificationPending == false
            && editor.consumeSuccess()) {
            Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
        }
    }

    fun createAndSave(hidden: Set<String>, order: List<String>): Boolean {
        releasedDrag = null
        if (initializing || editor.checking.value || initializationError != null || editor.state.value?.value?.valid != true
            || editor.state.value?.verificationPending == true || dragSession != null) {
            synchronizePresentation()
            return false
        }
        return try {
            val next = SystemRuleStore.Snapshot(hidden, order)
            next.encode() // Validate the encoded size before changing presentation or submitting IO.
            displayedProcessors = applyOrder(originalProcessors, next.orderedComponents)
            editor.save(next)
            true
        } catch (_: IllegalArgumentException) {
            synchronizePresentation()
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.RULES_LIMIT_EXCEEDED)
            Toast.makeText(activity, "设置过多或内容过长，本次更改未保存", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun saveOrder(): Boolean {
        val current = editor.state.value?.value ?: return false
        val fixed = originalProcessors.filter { it.fixed }.mapTo(HashSet()) { it.component }
        val ordered = mergeOrder(current.orderedComponents, displayedProcessors.map { it.component }, fixed)
        return createAndSave(current.hiddenComponents, ordered)
    }

    fun changeHidden(component: String) {
        if (initializing || editor.checking.value || dragSession != null || initializationError != null || editor.state.value?.verificationPending == true) return
        val current = editor.state.value?.value ?: return
        if (!current.valid) return
        val hidden = current.hiddenComponents.toMutableSet()
        if (!hidden.add(component)) hidden.remove(component)
        createAndSave(hidden, current.orderedComponents)
    }

    fun moveOne(component: String, direction: Int): Boolean {
        if (initializing || editor.checking.value || dragSession != null || initializationError != null || editor.state.value?.verificationPending == true) return false
        val current = editor.state.value?.value?.takeIf { it.valid } ?: return false
        displayedProcessors = applyOrder(originalProcessors, current.orderedComponents)
        val currentMovement = movementFor(current.hiddenComponents)
        val moving = currentMovement.components
        val from = currentMovement.positions[component] ?: return false
        val to = from + direction
        if (from < 0 || to !in moving.indices) return false
        reorderPresentation(moveVisibleProcessors(displayedProcessors, current.hiddenComponents, component, moving[to]))
        return saveOrder()
    }

    fun finishDrag(commit: Boolean) {
        val current = dragSession ?: return
        if (!commit) { cancelDrag(); return }
        if (!dragRulesUnchanged(current.startRules)) { synchronizePresentation(); return }
        val top = RuleDragGeometry.top(current.pointerY, current.grabOffset, current.height,
            optionBounds(listState, displayedProcessors.size))
        dragSession = null
        if (current.startOrder != displayedProcessors.map { it.component } && !saveOrder()) return
        if (top != null) {
            releasedDrag = RuleReleasedDrag(current.token, current.component, current.height, Animatable(top),
                displayedProcessors.map { it.component }, current.startRules.hiddenComponents)
        }
    }

    val dragCallbacks by rememberUpdatedState(RuleDragCallbacks(
        start = start@{ position ->
            if (!canChange || restoreAction != null || dragSession != null || position.x !in gripStart..gripEnd) return@start false
            val layout = listState.layoutInfo
            val released = releasedDrag
            val releasedCell = released?.let {
                RuleDragCell(it.component, displayedProcessors.indexOfFirst { processor -> processor.component == it.component },
                    it.top.value, it.height)
            }
            val painted = layout.visibleItemsInfo.filter { it.key != released?.component }.map {
                val component = it.key as String
                RuleDragCell(component, it.index, visualPositions.top(component) ?: it.offset.toFloat(), it.size.toFloat())
            }
            val item = releasedCell?.let { RuleDragGeometry.hit(position.y, rowGap, listOf(it)) }
                ?: RuleDragGeometry.hit(position.y, rowGap, painted) ?: return@start false
            val processor = displayedProcessors.getOrNull(item.index)?.takeIf { it.component == item.key } ?: return@start false
            if (editor.state.value?.verificationPending == true) return@start false
            val current = editor.state.value?.value?.takeIf { it.valid } ?: return@start false
            val actualPresentation = applyOrder(originalProcessors, current.orderedComponents)
            if (actualPresentation != displayedProcessors) {
                releasedDrag = null
                displayedProcessors = actualPresentation
                return@start true // Absorb a stale grip gesture; its layout belongs to the previous order.
            }
            if (!movable(processor, current.hiddenComponents)) return@start false
            val bounds = optionBounds(listState, displayedProcessors.size)
            val grabOffset = position.y - item.top
            val initialTop = RuleDragGeometry.top(position.y, grabOffset, item.height, bounds) ?: return@start false
            val logical = layout.visibleItemsInfo.firstOrNull { it.key == item.key && it.index == item.index } ?: return@start false
            releasedDrag = null
            dragSession = RuleDragSession(token = ++dragToken, component = processor.component, height = item.height,
                grabOffset = grabOffset, pointerY = position.y, startPointerY = position.y,
                pickupPaintedTop = initialTop, pickupSlotTop = logical.offset.toFloat(),
                startOrder = displayedProcessors.map { it.component }, startRules = current)
            true
        },
        move = { pointerY -> dragSession = dragSession?.let {
            it.copy(pointerY = pointerY, moved = it.moved || pointerY != it.startPointerY)
        } },
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
            if (top == null || !canChange || !dragRulesUnchanged(active.startRules)) {
                synchronizePresentation()
                0f
            } else {
                val hidden = editor.state.value?.value?.hiddenComponents ?: snapshot.hiddenComponents
                val frameMovement = movementFor(hidden)
                val sourceIndex = frameMovement.displayedPositions[active.component] ?: -1
                val movingKeys = frameMovement.keys
                // A layout predating a reorder must not become another insertion decision.
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                // Foundation 1.11.2 itemInfo.offset is the logical slot; placementDelta is paint only.
                val cells = visibleItems.map { RuleDragCell(it.key as String, it.index, it.offset.toFloat(), it.size.toFloat()) }
                val layoutMatches = RuleDragGeometry.matchesOrder(displayed.map { it.component }, cells)
                // Pickup coordinates stay in the viewport: scrolling moves target slots past them.
                val insertionCenter = RuleDragGeometry.insertionCenter(active.pointerY - active.grabOffset, active.height,
                    active.pickupPaintedTop, active.pickupSlotTop, bounds)
                val target = if (layoutMatches && active.moved && insertionCenter != null) RuleDragGeometry.target(
                    sourceIndex, insertionCenter,
                    cells,
                    movingKeys,
                ) else null
                if (target != null) {
                    reorderPresentation(moveVisibleProcessors(displayedProcessors, hidden, active.component, target))
                }
                val viewportTop = listState.layoutInfo.viewportStartOffset.toFloat()
                val viewportBottom = listState.layoutInfo.viewportEndOffset.toFloat()
                when {
                    !active.moved -> 0f
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

    LaunchedEffect(releasedDrag?.token) {
        val released = releasedDrag ?: return@LaunchedEffect
        try {
            // UP may have changed the order before LazyColumn has measured its final slot.
            val cells = withTimeoutOrNull(200) {
                snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.map {
                        RuleDragCell(it.key as String, it.index, it.offset.toFloat(), it.size.toFloat())
                    }
                }.first { RuleDragGeometry.matchesOrder(released.order, it) }
            } ?: return@LaunchedEffect
            val target = RuleDragGeometry.releaseTop(released.component, released.height, released.order, cells,
                optionBounds(listState, displayedProcessors.size)) ?: return@LaunchedEffect
            released.top.animateTo(target, spring(dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = RuleReorderStiffness, visibilityThreshold = 0.5f))
        } finally {
            if (releasedDrag?.token == released.token) releasedDrag = null
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) releasedDrag = null
    }

    LaunchedEffect(optionSize, windowFocused, canChange) {
        val currentCoordinates = optionSize to windowFocused
        val previousCoordinates = coordinateState
        coordinateState = currentCoordinates
        // Initial composition and a recovered gate do not invalidate a new pointer session.
        if (!canChange || !windowFocused || (previousCoordinates != null && previousCoordinates != currentCoordinates)) cancelDrag()
    }
    DisposableEffect(Unit) { onDispose { dragSession = null; releasedDrag = null } }
    BackHandler(enabled = initializing || dragSession != null) { cancelDrag() }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(MiuixTheme.colorScheme.background),
    ) {
        val sectionLimit = maxHeight * 0.3f
        val stackActions = maxWidth < 360.dp || density.fontScale > 1.3f
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = sectionLimit)
                .verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("文本选择菜单", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onAbout, enabled = !initializing && dragSession == null) {
                        Image(painterResource(R.drawable.ic_info), "关于", modifier = Modifier.size(24.dp),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground))
                    }
                }
                Text("勾选隐藏 · 拖动排序 · 自动保存", modifier = Modifier.padding(top = 4.dp),
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                if (saveState?.verificationPending == true) {
                    TextButton(text = "保存结果待核对 · 重新核对", onClick = { loadSignal++ }, enabled = !initializing && !recoveryChecking,
                        modifier = Modifier.fillMaxWidth())
                    TextButton(text = "放弃未保存更改", onClick = { discardPrompt = true }, enabled = !initializing && !recoveryChecking,
                        modifier = Modifier.fillMaxWidth())
                }
                if (originalProcessors.isNotEmpty()) {
                    Text(
                        if (originalProcessors.any { !it.classificationKnown }) "部分位置无法识别，仍可隐藏。"
                        else "锁定项位置由系统决定；隐藏项需先取消勾选再移动。",
                        modifier = Modifier.padding(top = 5.dp), fontSize = 11.sp, lineHeight = 16.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .onGloballyPositioned { visualPositions.viewport = it }
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
                if ((initializing && originalProcessors.isEmpty()) || initializationError != null || originalProcessors.isEmpty()) {
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
                            val index = movement.positions[processor.component] ?: -1
                            val actions = if (canUseActions && movable(processor)) buildList {
                                if (index > 0) add(CustomAccessibilityAction("上移") { moveOne(processor.component, -1) })
                                if (index >= 0 && index < movement.components.lastIndex) add(CustomAccessibilityAction("下移") { moveOne(processor.component, 1) })
                            } else emptyList()
                            val isPlaceholder = dragSession?.component == processor.component
                            val released = releasedDrag?.takeIf { it.component == processor.component }
                            val placement = if (isPlaceholder) Modifier.alpha(0f).clearAndSetSemantics { }
                            else if (released != null) Modifier.zIndex(1f).offset {
                                val slotTop = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == released.component }?.offset ?: released.top.value.roundToInt()
                                IntOffset(0, overlayPixelTop(released.top.value, released.height,
                                    optionBounds(listState, displayedProcessors.size)) - slotTop)
                            }
                            else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                                placementSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = RuleReorderStiffness))
                            ProcessorRuleRow(
                                processor = processor, hidden = hidden, enabled = canChange,
                                handleEnabled = canChange && lockedReason == null, lockedReason = lockedReason,
                                accessibilityActions = actions, interactive = !isPlaceholder,
                                viewportHeight = optionSize.height,
                                onMoveUp = if (canUseActions && index > 0) ({ if (moveOne(processor.component, -1)) Unit else Unit }) else null,
                                onMoveDown = if (canUseActions && index >= 0 && index < movement.components.lastIndex)
                                    ({ if (moveOne(processor.component, 1)) Unit else Unit }) else null,
                                modifier = placement.onGloballyPositioned { visualPositions.rows[processor.component] = it },
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

            Box(modifier = Modifier.fillMaxWidth().heightIn(max = sectionLimit)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
                RestoreButtons(stackActions,
                    canUseActions && snapshot.orderedComponents.isNotEmpty(),
                    canUseActions && snapshot.hiddenComponents.isNotEmpty(),
                    { restoreAction = RestoreAction.ORDER }, { restoreAction = RestoreAction.HIDDEN })
            }
        }
    }

    restoreAction?.let { action ->
        val restoringOrder = action == RestoreAction.ORDER
        WindowDialog(show = true, onDismissRequest = { restoreAction = null }, content = {
            Column(modifier = Modifier.dialogContent(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (restoringOrder) "恢复默认排序" else "恢复全部显示", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (restoringOrder) "将清除扩展项的自定义排序，隐藏设置保持不变。"
                    else "将清除隐藏规则，当前排序保持不变。",
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
                DialogActions("取消", { restoreAction = null }, "确认恢复", {
                    restoreAction = null
                    editor.state.value?.value?.let { current ->
                        if (restoringOrder) createAndSave(current.hiddenComponents, emptyList())
                        else createAndSave(emptySet(), current.orderedComponents)
                    }
                }, confirmEnabled = canUseActions)
            }
        })
    }
    if (discardPrompt && saveState?.verificationPending == true) {
        WindowDialog(show = true, onDismissRequest = { discardPrompt = false }, content = {
            Column(modifier = Modifier.dialogContent(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("放弃未保存更改", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("放弃尚未保存的更改，并读取手机当前设置。已生效的设置会保留。",
                    fontSize = 14.sp, lineHeight = 20.sp)
                DialogActions("取消", { discardPrompt = false }, "放弃更改", {
                    discardPrompt = false
                    editor.discardUnsaved()
                }, confirmEnabled = !initializing && !recoveryChecking && saveState?.pending != true)
            }
        })
    }
}

@Composable
private fun RestoreButtons(
    stacked: Boolean, orderEnabled: Boolean, hiddenEnabled: Boolean,
    onOrder: () -> Unit, onHidden: () -> Unit,
) {
    @Composable fun Order(modifier: Modifier) {
        TextButton(text = "恢复默认排序", onClick = onOrder, enabled = orderEnabled,
            modifier = modifier, colors = ButtonDefaults.textButtonColorsPrimary())
    }
    @Composable fun Hidden(modifier: Modifier) {
        TextButton(text = "恢复全部显示", onClick = onHidden, enabled = hiddenEnabled,
            modifier = modifier, colors = ButtonDefaults.textButtonColors(
                textColor = MiuixTheme.colorScheme.error,
                disabledTextColor = MiuixTheme.colorScheme.error.copy(alpha = 0.4f)))
    }
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Order(Modifier.fillMaxWidth())
        Hidden(Modifier.fillMaxWidth())
    } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Order(Modifier.weight(1f))
        Hidden(Modifier.weight(1f))
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
    viewportHeight: Int = 0,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val interactions = remember(processor.component) { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val active = isDragging || pressed || focused
    var bodyHeight by remember(processor.component) { mutableIntStateOf(0) }
    val outerSpacing = with(LocalDensity.current) { 6.dp.toPx() }
    val needsMoveButtons = interactive && lockedReason == null && viewportHeight > 0
        && bodyHeight + outerSpacing > viewportHeight && (onMoveUp != null || onMoveDown != null)
    val interactionModifier = if (interactive) Modifier
        .toggleable(value = hidden, enabled = enabled, role = Role.Checkbox,
            interactionSource = interactions, indication = null, onValueChange = onCheckedChange)
        .semantics(mergeDescendants = true) {
            stateDescription = (if (hidden) "已隐藏" else "显示中") + (lockedReason?.let { "，$it" } ?: "")
            customActions = accessibilityActions
            onClick(label = if (hidden) "显示此选项" else "隐藏此选项", action = null)
        } else Modifier
    Card(modifier = modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp)
                .onSizeChanged { bodyHeight = it.height }
                .background(if (active) MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.09f) else Color.Transparent)
                .then(interactionModifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val handleColor = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = if (!enabled) 0.3f else if (handleEnabled) 0.58f else 0.4f)
            Box(
                modifier = Modifier.size(48.dp).clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                if (lockedReason == null) DragGrip(handleColor) else LockGrip(handleColor)
            }
            Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(processor.label, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = if (enabled) 1f else 0.45f))
                Text(processor.summary, modifier = Modifier.padding(top = 2.dp), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = if (enabled) 1f else 0.45f))
            }
            Box(modifier = Modifier.padding(start = 10.dp, end = 16.dp)) { RuleCheck(hidden, enabled) }
        }
        if (needsMoveButtons) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "上移", onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null,
                    modifier = Modifier.weight(1f))
                TextButton(text = "下移", onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RuleCheck(checked: Boolean, enabled: Boolean) {
    val foreground = MiuixTheme.colorScheme.onPrimary.copy(alpha = if (enabled) 1f else 0.6f)
    val selected = MiuixTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f)
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
