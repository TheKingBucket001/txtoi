package io.github.selectionmenucontrol

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Bundle
import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.MonetSystem, keyColor = Color(0xFF2879FF)) }
            MiuixTheme(controller = controller) {
                ModuleApp(activity = this@MainActivity)
            }
        }
    }
}

internal class EnvironmentViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(GateState())
    val state = mutableState.asStateFlow()
    private var check: Job? = null
    private var started = false

    fun ensureChecked() { if (!started) refresh() }

    suspend fun awaitCheckFinished() { check?.join() }

    fun refresh() {
        if (check?.isActive == true) return
        started = true
        mutableState.value = GateState(checking = true, rootMessage = "正在验证 Root 授权")
        check = viewModelScope.launch {
            mutableState.value = withContext(Dispatchers.IO) {
                environmentCheckMutex.withLock {
                    checkEnvironment(
                        checkRoot = { RootAccess.check() },
                        checkScope = { publish -> waitForSystemHook(getApplication(), publish) },
                        publish = { mutableState.value = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleApp(activity: MainActivity) {
    val environment = remember(activity) { ViewModelProvider(activity)[EnvironmentViewModel::class.java] }
    val gateState by environment.state.collectAsState()
    val saveState by RuleSaveCoordinator.state.collectAsState()
    val recoveryChecking by RuleSaveCoordinator.checking.collectAsState()
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showGateDiscard by rememberSaveable { mutableStateOf(false) }
    val updates = remember(activity) { ViewModelProvider(activity)[UpdateViewModel::class.java] }
    val updateState by updates.state.collectAsState()
    var foregroundSignal by remember { mutableIntStateOf(0) }
    var autoCheckEnabled by remember { mutableStateOf(UpdateSettings.isAutoCheckEnabled(activity)) }

    DisposableEffect(activity, updates, autoCheckEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    foregroundSignal++
                    if (autoCheckEnabled) updates.automaticCheck()
                }
                Lifecycle.Event.ON_STOP -> if (!activity.isChangingConfigurations) updates.cancelCheck()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(updateState.noticeVersion, foregroundSignal) {
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            updates.consumeNotice()?.let { Toast.makeText(activity, it, Toast.LENGTH_SHORT).show() }
        }
    }

    LaunchedEffect(environment) { environment.ensureChecked() }

    // A failed scope check still needs to expose a durable draft from an earlier
    // process. Wait for probe cleanup before initializing the Root-backed recovery
    // coordinator; an ungranted Root never triggers this work.
    LaunchedEffect(gateState.rootReady, gateState.checking, gateState.systemHookReady) {
        if (gateState.rootReady && !gateState.checking && !gateState.systemHookReady) {
            environment.awaitCheckFinished()
            val expected = RuleSaveCoordinator.state.value
            val current = withContext(Dispatchers.IO) { SystemRuleStore.readGlobal(activity) }
            RuleSaveCoordinator.initialize(activity, current, expected)
        }
    }

    BackHandler(enabled = showAbout) {
        showAbout = false
    }
    Crossfade(
        targetState = showAbout,
        animationSpec = tween(durationMillis = 220),
        label = "page-transition",
    ) { aboutVisible ->
        if (aboutVisible) {
                AboutScreen(
                    activity = activity,
                    onBack = { showAbout = false },
                    autoCheckEnabled = autoCheckEnabled,
                    onAutoCheckChange = { enabled ->
                        UpdateSettings.setAutoCheckEnabled(activity, enabled)
                        autoCheckEnabled = enabled
                        if (!enabled) updates.cancelCheck(automaticOnly = true)
                    },
                    checkingUpdate = updateState.checking,
                    onManualUpdate = updates::manualCheck,
                )
        } else {
            if (gateState.checking || !gateState.systemHookReady || !gateState.rootReady) {
                EnvironmentGate(
                    state = gateState,
                    onRefresh = environment::refresh,
                    onAbout = { showAbout = true },
                    hasPendingDraft = saveState?.verificationPending == true,
                    onDiscard = { showGateDiscard = true },
                )
                return@Crossfade
            }
            Box(modifier = Modifier.fillMaxSize()
                .background(MiuixTheme.colorScheme.background).safeDrawingPadding()) {
                RuleScreen(activity, awaitEnvironmentCheck = environment::awaitCheckFinished) { showAbout = true }
            }
        }
    }

    updateState.available?.let { update ->
        UpdateDialog(
            update = update,
            onDismiss = updates::dismissUpdate,
            onOpenRelease = {
                openWebPage(activity, update.releaseUrl, "无法打开更新页面")
            },
        )
    }

    if (showGateDiscard) {
        WindowDialog(show = true, onDismissRequest = { showGateDiscard = false }, content = {
            Column(modifier = Modifier.dialogContent(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("放弃未保存更改", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("放弃尚未确认的更改，并读取手机当前设置。已经生效的设置会保留。",
                    fontSize = 14.sp, lineHeight = 20.sp)
                DialogActions(
                    "取消", { showGateDiscard = false }, "放弃更改", {
                        showGateDiscard = false
                        RuleSaveCoordinator.discardUnsaved()
                    }, confirmEnabled = !recoveryChecking && gateState.rootReady
                        && !gateState.checking && !gateState.cleanupPending,
                )
            }
        })
    }
}

private fun openWebPage(activity: MainActivity, url: String, failureMessage: String) {
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(activity, failureMessage, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(activity, failureMessage, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun UpdateDialog(update: UpdateInfo, onDismiss: () -> Unit, onOpenRelease: () -> Unit) {
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.dialogContent(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("发现新版本", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${update.version} 已发布。",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                DialogActions("稍后", onDismiss, "查看更新", onOpenRelease)
            }
        },
    )
}

private val environmentCheckMutex = Mutex()

private suspend fun waitForSystemHook(
    activity: Context,
    publish: suspend (SystemRuleStore.HookStatus) -> Unit,
): SystemRuleStore.HookStatus =
    runProbeSession(
        prepare = { SystemRuleStore.beginProbe(activity) },
        evaluate = { start ->
            val request = start.request
            if (!start.prepared || request == null) {
                ModuleDiagnostics.fault(ModuleDiagnostics.Code.PROBE_FAILED)
                SystemRuleStore.probeStatus(
                    ProbeProtocol.Status.PREPARATION_FAILED,
                    start.preparationMessage,
                )
            } else {
                ModuleDiagnostics.recover(ModuleDiagnostics.Code.PROBE_FAILED)
                querySystemHook(
                    query = {
                        val queryStarted = android.os.SystemClock.elapsedRealtime()
                        // This is the same public PROCESS_TEXT path used by the system
                        // toolbar. It proves the module is active in system_server and
                        // avoids accepting a client-only probe as a scope assertion.
                        activity.packageManager.queryIntentActivities(
                            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                                .putExtra(SystemRuleStore.QUERY_ORIGINAL_EXTRA, true),
                            PackageManager.MATCH_ALL,
                        )
                        ModuleDiagnostics.metric(ModuleDiagnostics.Metric.PROBE_QUERY_MS,
                            android.os.SystemClock.elapsedRealtime() - queryStarted)
                    },
                    readResponse = { SystemRuleStore.readProbeResponse(activity, request) },
                )
            }
        },
        publish = publish,
        cleanup = { start ->
            // A failed preparation can still have written a request. Cancellation also retires
            // that owned session, within the Root executor's bounded cleanup budget.
            withContext(kotlinx.coroutines.NonCancellable) {
                try { SystemRuleStore.cancelProbe(start.request) }
                catch (_: Exception) { ModuleDiagnostics.fault(ModuleDiagnostics.Code.PROBE_CLEANUP_FAILED) }
            }
        },
    )

@Composable
private fun EnvironmentGate(
    state: GateState,
    onRefresh: () -> Unit,
    onAbout: () -> Unit,
    hasPendingDraft: Boolean,
    onDiscard: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp),
    ) {
        item {
            Text("文本选择菜单", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                if (state.checking) "正在检查运行环境" else "运行环境未就绪",
                modifier = Modifier.padding(top = 6.dp),
                style = MiuixTheme.textStyles.body1,
                color = if (state.checking) MiuixTheme.colorScheme.onBackgroundVariant
                else MiuixTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (state.checking) "正在分别确认 Root 授权与系统框架作用域。"
                else "完成以下检查后，即可管理文本选择菜单中的扩展项。",
                modifier = Modifier.padding(top = 8.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
        item {
            Text(
                "运行环境",
                modifier = Modifier.padding(top = 30.dp, bottom = 8.dp),
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Bold,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                GateRow("Root 授权", state.rootReady, state.checking && !state.rootChecked, state.rootMessage)
                AboutInfoDivider()
                GateRow(
                    "系统框架作用域",
                    state.systemHookReady,
                    state.checking && state.rootReady,
                    when {
                        state.systemHookReady -> state.systemMessage
                        !state.rootChecked -> "完成 Root 检查后，即可检查系统框架。"
                        !state.rootReady -> "Root 未通过，暂不执行作用域检测。"
                        else -> state.systemMessage
                    },
                    waiting = !state.rootReady,
                )
            }
        }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = when {
                        state.checking -> "正在检查"
                        state.cleanupPending -> "正在完成检查"
                        else -> "重新检查"
                    },
                    onClick = onRefresh,
                    enabled = !state.checking && !state.cleanupPending,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = "关于",
                    onClick = onAbout,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (hasPendingDraft) {
                TextButton(
                    text = "放弃未保存更改",
                    onClick = onDiscard,
                    enabled = state.rootReady && !state.checking && !state.cleanupPending,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.error,
                        disabledTextColor = MiuixTheme.colorScheme.error.copy(alpha = 0.4f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun GateRow(title: String, passed: Boolean, checking: Boolean, summary: String, waiting: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.padding(top = 6.dp).size(9.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(when {
                    checking || waiting -> MiuixTheme.colorScheme.onBackgroundVariant
                    passed -> MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.error
                }),
        )
        Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
            Text(summary, modifier = Modifier.padding(top = 4.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant, lineHeight = 20.sp)
        }
        Text(when {
            waiting -> "未检测"
            checking -> "检查中"
            passed -> "已就绪"
            else -> "未通过"
        },
            modifier = Modifier.padding(start = 12.dp, top = 1.dp),
            style = MiuixTheme.textStyles.body2,
            color = when {
                checking || waiting -> MiuixTheme.colorScheme.onBackgroundVariant
                passed -> MiuixTheme.colorScheme.primary
                else -> MiuixTheme.colorScheme.error
            },
            fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AboutScreen(
    activity: MainActivity,
    onBack: () -> Unit,
    autoCheckEnabled: Boolean,
    onAutoCheckChange: (Boolean) -> Unit,
    checkingUpdate: Boolean,
    onManualUpdate: () -> Unit,
) {
    var showCelebration by rememberSaveable { mutableStateOf(false) }
    val iconInteractions = remember { MutableInteractionSource() }
    val iconPressed by iconInteractions.collectIsPressedAsState()
    val iconScale by animateFloatAsState(
        targetValue = if (iconPressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 700f),
        label = "easter-egg-icon-press",
    )
    BackHandler(enabled = showCelebration) {
        showCelebration = false
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Image(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "返回",
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),
                )
            }
            Text(
                "关于文本菜单",
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(48.dp))
        }

        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(24.dp)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .combinedClickable(
                                interactionSource = iconInteractions,
                                indication = null,
                                onClick = {},
                                onLongClickLabel = "打开彩蛋",
                                onLongClick = { showCelebration = true },
                            ),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_module),
                            contentDescription = "文本菜单控制图标",
                            modifier = Modifier.fillMaxSize().scale(iconScale),
                        )
                    }
                    Text("系统文本菜单", color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("文本菜单控制", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = MiuixTheme.colorScheme.onSurfaceContainer)
                    Text("隐藏不需要的选项，调整扩展项顺序。",
                        color = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.7f), fontSize = 14.sp, lineHeight = 20.sp)
                }
            }

            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                AboutInfoRow("当前版本", BuildConfig.VERSION_NAME)
                AboutInfoDivider()
                AboutInfoRow("模块 ID", "txtoi")
                AboutInfoDivider()
                AboutInfoRow("维护者", "Bucket")
            }

            Text("项目", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 2.dp))
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                AboutActionRow("查看源代码", "GitHub · TheKingBucket001/txtoi", "GitHub") {
                    openWebPage(activity, "https://github.com/TheKingBucket001/txtoi", "无法打开源代码页面")
                }
                AboutInfoDivider()
                AboutActionRow("开源许可证", "GNU General Public License v3.0", "GPL-3.0") {
                    openWebPage(
                        activity,
                        "https://github.com/TheKingBucket001/txtoi/blob/main/LICENSE",
                        "无法打开许可证页面",
                    )
                }
            }

            Text("更新", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 2.dp))
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SwitchPreference(
                    checked = autoCheckEnabled,
                    onCheckedChange = onAutoCheckChange,
                    title = "自动检测更新",
                    summary = "打开模块时检测新版本",
                    insideMargin = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                )
                AboutInfoDivider()
                AboutActionRow(
                    if (checkingUpdate) "正在检查更新" else "检查更新",
                    "查看是否有新版本",
                    if (checkingUpdate) "检查中" else "检查",
                    if (checkingUpdate) null else onManualUpdate,
                )
            }
        }
    }
    WindowDialog(
        show = showCelebration,
        onDismissRequest = { showCelebration = false },
        content = {
            Column(modifier = Modifier.dialogContent(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("给今天加点甜", style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant)
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CelebrationBurst(Modifier.matchParentSize())
                    Text("天天开心", fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
                TextButton(
                    text = "收下开心",
                    onClick = { showCelebration = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
private fun CelebrationBurst(modifier: Modifier) {
    val progress = remember { Animatable(0f) }
    val directions = remember {
        List(12) { index ->
            val angle = index * PI / 6.0
            Offset(cos(angle).toFloat(), sin(angle).toFloat())
        }
    }
    val colors = listOf(
        MiuixTheme.colorScheme.primary,
        Color(0xFFE98575),
        Color(0xFFF0BA58),
        Color(0xFF75B990),
    )
    LaunchedEffect(Unit) {
        // Compose applies the system animation scale, including zero duration.
        progress.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val reach = min(size.width * 0.42f, 112.dp.toPx())
        val fade = (1f - t) * (1f - t)
        directions.forEachIndexed { index, direction ->
            val radius = reach * t * (if (index % 2 == 0) 1f else 0.76f)
            val position = center + Offset(
                direction.x * radius,
                direction.y * radius * 0.46f + 10.dp.toPx() * t * t,
            )
            val color = colors[index % colors.size].copy(alpha = fade)
            val particleSize = (if (index % 2 == 0) 3.dp else 4.dp).toPx()
            if (index % 2 == 0) {
                drawCircle(color, radius = particleSize, center = position)
            } else {
                drawLine(color, start = position,
                    end = position + Offset(direction.y, -direction.x) * particleSize * 2f,
                    strokeWidth = 2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onBackgroundVariant, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold,
            fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
    }
}

@Composable
private fun AboutInfoDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f)))
}

@Composable
private fun AboutActionRow(title: String, summary: String, action: String, onClick: (() -> Unit)?) {
    val rowModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(modifier = rowModifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
            Text(summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackgroundVariant)
        }
        Text(action, color = MiuixTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
    }
}
