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
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import android.content.ClipData
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

internal data class GateState(
    val checking: Boolean = true,
    val systemHookReady: Boolean = false,
    val rootReady: Boolean = false,
    val rootMessage: String = "等待系统框架检测完成",
)

internal class EnvironmentViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(GateState())
    val state = mutableState.asStateFlow()
    private var check: Job? = null
    private var started = false

    fun ensureChecked() { if (!started) refresh() }

    fun refresh() {
        if (check?.isActive == true) return
        started = true
        mutableState.value = GateState(checking = true, rootMessage = "正在验证 Root 授权")
        check = viewModelScope.launch {
            var rootGranted = false
            try {
                mutableState.value = withContext(Dispatchers.IO) {
                    environmentCheckMutex.withLock {
                        ensureActive()
                        val root = RootAccess.check()
                        rootGranted = root.granted
                        ensureActive()
                        val hook = if (root.granted) waitForSystemHook(getApplication()) else SystemRuleStore.HookStatus.unavailable()
                        GateState(false, hook.loadedForCurrentBoot, root.granted, root.message)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ModuleDiagnostics.fault(if (rootGranted) ModuleDiagnostics.Code.PROBE_FAILED else ModuleDiagnostics.Code.ROOT_FAILED)
                mutableState.value = GateState(false, false, rootGranted,
                    if (rootGranted) "系统环境检测失败，请重新检查" else "无法完成 Root 验证，请重新检查")
            }
        }
    }
}

@Composable
private fun ModuleApp(activity: MainActivity) {
    val environment = remember(activity) { ViewModelProvider(activity)[EnvironmentViewModel::class.java] }
    val gateState by environment.state.collectAsState()
    var showAbout by rememberSaveable { mutableStateOf(false) }
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

    if (gateState.checking || !gateState.systemHookReady || !gateState.rootReady) {
        EnvironmentGate(gateState, environment::refresh)
    } else {
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
            }
            else RuleScreen(activity) { showAbout = true }
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

private suspend fun waitForSystemHook(activity: Context): SystemRuleStore.HookStatus {
    val request = SystemRuleStore.beginProbe(activity) ?: return SystemRuleStore.HookStatus.unavailable()
    val deadline = android.os.SystemClock.elapsedRealtime() + 2_000L
    try {
        do {
            currentCoroutineContext().ensureActive()
            activity.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    .putExtra(SystemRuleStore.QUERY_ORIGINAL_EXTRA, true),
                PackageManager.MATCH_ALL,
            )
            delay(100)
            val status = SystemRuleStore.readProbeResponse(activity, request)
            if (status.loadedForCurrentBoot) {
                ModuleDiagnostics.recover(ModuleDiagnostics.Code.PROBE_FAILED)
                return status
            }
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        return SystemRuleStore.readProbeResponse(activity, request)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        ModuleDiagnostics.fault(ModuleDiagnostics.Code.PROBE_FAILED)
        return SystemRuleStore.readProbeResponse(activity, request)
    } finally {
        SystemRuleStore.cancelProbe(request)
    }
}

@Composable
private fun EnvironmentGate(state: GateState, onRefresh: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
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
                color = if (state.checking) MiuixTheme.colorScheme.onBackgroundVariant else MiuixTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (state.checking) "正在确认系统框架与 Root 授权状态。" else "完成以下检查后，即可管理文本选择菜单中的扩展项。",
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
                GateRow(
                    title = "系统框架作用域",
                    passed = state.systemHookReady,
                    summary = when {
                        state.systemHookReady -> "已在本次启动中加载"
                        !state.rootReady -> "完成 Root 授权后，即可检查系统框架。"
                        else -> "请在 LSPosed 中勾选系统框架，然后热重启设备。"
                    },
                )
                AboutInfoDivider()
                GateRow(
                    title = "Root 授权",
                    passed = state.rootReady,
                    summary = state.rootMessage,
                )
            }
        }
        item {
            TextButton(
                text = if (state.checking) "正在检查" else "重新检查",
                onClick = onRefresh,
                enabled = !state.checking,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun GateRow(title: String, passed: Boolean, summary: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(9.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (passed) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error),
        )
        Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
            Text(
                summary,
                modifier = Modifier.padding(top = 4.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                lineHeight = 20.sp,
            )
        }
        Text(
            if (passed) "已就绪" else "未通过",
            modifier = Modifier.padding(start = 12.dp, top = 1.dp),
            style = MiuixTheme.textStyles.body2,
            color = if (passed) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
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
    var showDailyMenu by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var diagnosticText by remember { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(showDiagnostics) {
        if (showDiagnostics) diagnosticText = ModuleDiagnostics.snapshot()
    }
    BackHandler(enabled = showDailyMenu || showDiagnostics) {
        showDailyMenu = false
        showDiagnostics = false
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
                    Image(
                        painter = painterResource(R.drawable.ic_module),
                        contentDescription = "文本菜单控制图标",
                        modifier = Modifier.size(56.dp).combinedClickable(
                            onClick = {},
                            onLongClickLabel = "打开今日菜单",
                            onLongClick = { showDailyMenu = true },
                        ),
                    )
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
                AboutActionRow("开源许可证", "GNU General Public License v3.0", "GPL-3.0", null)
            }

            Text("更新", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 2.dp))
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SwitchPreference(
                    checked = autoCheckEnabled,
                    onCheckedChange = onAutoCheckChange,
                    title = "自动检测更新",
                    summary = "打开模块时检测新版本",
                )
                AboutInfoDivider()
                AboutActionRow(
                    if (checkingUpdate) "正在检查更新" else "检查更新",
                    "查看是否有新版本",
                    if (checkingUpdate) "检查中" else "检查",
                    if (checkingUpdate) null else onManualUpdate,
                )
            }
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                AboutActionRow("应用诊断", "查看或复制本次运行的诊断信息", "查看") {
                    showDiagnostics = true
                }
            }
        }
    }
    if (showDailyMenu) {
        WindowDialog(
            show = true,
            onDismissRequest = { showDailyMenu = false },
            content = {
                Column(modifier = Modifier.dialogContent(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("今日菜单", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("复制一点快乐\n粘贴一点好运\n把烦恼留在菜单外", fontSize = 16.sp, lineHeight = 28.sp)
                    TextButton(
                        text = "收下好运",
                        onClick = { showDailyMenu = false },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
    if (showDiagnostics) {
        WindowDialog(show = true, onDismissRequest = { showDiagnostics = false }, content = {
            Column(modifier = Modifier.dialogContent(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("应用诊断", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    diagnosticText,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                DialogActions("关闭", { showDiagnostics = false }, "复制", {
                    scope.launch {
                        try {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("应用诊断", diagnosticText)))
                            Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            Toast.makeText(activity, "复制失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, confirmEnabled = diagnosticText.isNotEmpty())
            }
        })
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
