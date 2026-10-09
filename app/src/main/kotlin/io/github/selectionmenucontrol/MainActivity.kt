package io.github.selectionmenucontrol

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.MonetSystem, keyColor = Color(0xFF2879FF)) }
            MiuixTheme(controller = controller) {
                ModuleApp(activity = this@MainActivity)
            }
        }
    }
}

private data class GateState(
    val checking: Boolean = true,
    val systemHookReady: Boolean = false,
    val rootReady: Boolean = false,
    val rootMessage: String = "等待系统框架检测完成",
)

private data class UpdateInfo(
    val version: String,
    val releaseUrl: String,
)

private sealed interface UpdateCheckResult {
    data class Available(val update: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

private object UpdateSettings {
    private const val PREFERENCES_NAME = "update_settings"
    private const val AUTO_CHECK_KEY = "auto_check"
    private const val CACHED_VERSION_KEY = "cached_version"
    private const val CACHED_URL_KEY = "cached_url"
    private const val CACHED_AT_KEY = "cached_at"

    fun isAutoCheckEnabled(context: Context): Boolean = preferences(context).getBoolean(AUTO_CHECK_KEY, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(AUTO_CHECK_KEY, enabled).apply()
    }

    fun getFreshCachedRelease(context: Context): UpdateInfo? {
        val preferences = preferences(context)
        val cachedAt = preferences.getLong(CACHED_AT_KEY, 0L)
        val age = System.currentTimeMillis() - cachedAt
        if (cachedAt <= 0L || age !in 0..UPDATE_CACHE_VALIDITY_MS) return null
        val version = preferences.getString(CACHED_VERSION_KEY, "").orEmpty()
        val releaseUrl = preferences.getString(CACHED_URL_KEY, "").orEmpty()
        return UpdateInfo(version, releaseUrl).takeIf { it.version.isNotBlank() && it.releaseUrl.isNotBlank() }
    }

    fun saveCachedRelease(context: Context, update: UpdateInfo) {
        preferences(context).edit()
            .putString(CACHED_VERSION_KEY, update.version)
            .putString(CACHED_URL_KEY, update.releaseUrl)
            .putLong(CACHED_AT_KEY, System.currentTimeMillis())
            .apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

@Composable
private fun ModuleApp(activity: MainActivity) {
    var gateState by remember { mutableStateOf(GateState()) }
    var refreshSignal by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var autoCheckEnabled by remember { mutableStateOf(UpdateSettings.isAutoCheckEnabled(activity)) }
    val autoCheckOnLaunch = remember { autoCheckEnabled }

    // This effect belongs to the Activity's root composition, so page navigation and
    // setting changes cannot start another check during the same app session.
    LaunchedEffect(Unit) {
        if (!autoCheckOnLaunch) return@LaunchedEffect
        when (val result = withContext(Dispatchers.IO) { checkForUpdate(activity) }) {
            is UpdateCheckResult.Available -> availableUpdate = result.update
            UpdateCheckResult.UpToDate -> Toast.makeText(activity, "暂无更新", Toast.LENGTH_SHORT).show()
            UpdateCheckResult.Failed -> Toast.makeText(activity, "检查更新失败", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(refreshSignal) {
        gateState = GateState(checking = true, rootMessage = "正在验证 Root 授权")
        gateState = withContext(Dispatchers.IO) {
            environmentCheckMutex.withLock {
                ensureActive()
                val root = RootAccess.check()
                ensureActive()
                val hook = if (root.granted) waitForSystemHook(activity) else SystemRuleStore.HookStatus.unavailable()
                GateState(
                    checking = false,
                    systemHookReady = hook.loadedForCurrentBoot,
                    rootReady = root.granted,
                    rootMessage = root.message,
                )
            }
        }
    }

    if (gateState.checking || !gateState.systemHookReady || !gateState.rootReady) {
        EnvironmentGate(gateState) { refreshSignal++ }
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
                    },
                )
            }
            else RuleScreen(activity) { showAbout = true }
        }
    }

    availableUpdate?.let { update ->
        UpdateDialog(
            update = update,
            onDismiss = { availableUpdate = null },
            onOpenRelease = {
                openWebPage(activity, update.releaseUrl, "无法打开更新页面")
            },
        )
    }
}

private fun openWebPage(activity: MainActivity, url: String, failureMessage: String) {
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("发现新版本", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${update.version} 已发布。",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "稍后", onClick = onDismiss, modifier = Modifier.weight(1f))
                    TextButton(
                        text = "查看更新",
                        onClick = onOpenRelease,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

private fun checkForUpdate(context: Context): UpdateCheckResult {
    UpdateSettings.getFreshCachedRelease(context)?.let { cached ->
        Log.i(UPDATE_LOG_TAG, "Using cached update result")
        return resultForRelease(cached)
    }
    if (!waitForValidatedNetwork(context)) {
        Log.w(UPDATE_LOG_TAG, "Update check skipped: no validated network")
        return UpdateCheckResult.Failed
    }

    var lastFailure: Throwable? = null
    repeat(UPDATE_CHECK_ATTEMPTS) { attempt ->
        try {
            val release = requestLatestRelease()
            UpdateSettings.saveCachedRelease(context, release)
            Log.i(UPDATE_LOG_TAG, "Update check succeeded on attempt ${attempt + 1}")
            return resultForRelease(release)
        } catch (error: Throwable) {
            lastFailure = error
            Log.w(UPDATE_LOG_TAG, "Update check attempt ${attempt + 1} failed: ${error.javaClass.simpleName}")
            if (attempt + 1 < UPDATE_CHECK_ATTEMPTS) {
                Thread.sleep(UPDATE_RETRY_DELAY_MS)
            }
        }
    }
    Log.w(UPDATE_LOG_TAG, "Update check failed after $UPDATE_CHECK_ATTEMPTS attempts", lastFailure)
    return UpdateCheckResult.Failed
}

private fun requestLatestRelease(): UpdateInfo {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = UPDATE_REQUEST_TIMEOUT_MS
            readTimeout = UPDATE_REQUEST_TIMEOUT_MS
            setRequestProperty("User-Agent", "txtoi-android-update-check")
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("Unexpected HTTP status ${connection.responseCode}")
        }
        val releaseUrl = connection.url.toString()
        val releasePath = Uri.parse(releaseUrl).path.orEmpty()
        require(releasePath.contains("/releases/tag/")) { "Latest release did not redirect to a tag" }
        val version = Uri.parse(releaseUrl).lastPathSegment.orEmpty().trim()
        require(version.isNotBlank()) { "Release tag is missing" }
        return UpdateInfo(version, releaseUrl)
    } finally {
        connection?.disconnect()
    }
}

private fun resultForRelease(release: UpdateInfo): UpdateCheckResult =
    if (isVersionNewer(release.version, BuildConfig.VERSION_NAME)) {
        UpdateCheckResult.Available(release)
    } else {
        UpdateCheckResult.UpToDate
    }

private fun waitForValidatedNetwork(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val deadline = System.currentTimeMillis() + NETWORK_READY_TIMEOUT_MS
    do {
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return true
        }
        Thread.sleep(500)
    } while (System.currentTimeMillis() < deadline)
    return false
}

private fun isVersionNewer(remote: String, local: String): Boolean {
    val remoteParts = parseVersion(remote) ?: return false
    val localParts = parseVersion(local) ?: return false
    val count = maxOf(remoteParts.size, localParts.size)
    for (index in 0 until count) {
        val remotePart = remoteParts.getOrElse(index) { 0 }
        val localPart = localParts.getOrElse(index) { 0 }
        if (remotePart != localPart) return remotePart > localPart
    }
    return false
}

private fun parseVersion(value: String): List<Int>? {
    val numericVersion = value.trim().removePrefix("v").removePrefix("V").substringBefore('-')
    val parts = numericVersion.split('.')
    if (parts.isEmpty() || parts.any { it.isBlank() }) return null
    return parts.map { it.toIntOrNull() ?: return null }
}

private const val LATEST_RELEASE_URL = "https://github.com/TheKingBucket001/txtoi/releases/latest"
private const val NETWORK_READY_TIMEOUT_MS = 12_000L
private const val UPDATE_REQUEST_TIMEOUT_MS = 5_000
private const val UPDATE_CHECK_ATTEMPTS = 2
private const val UPDATE_RETRY_DELAY_MS = 1_500L
private const val UPDATE_CACHE_VALIDITY_MS = 60L * 60L * 1_000L
private const val UPDATE_LOG_TAG = "SelectionMenuControl"

private val environmentCheckMutex = Mutex()

private suspend fun waitForSystemHook(activity: ComponentActivity): SystemRuleStore.HookStatus {
    val request = SystemRuleStore.beginProbe() ?: return SystemRuleStore.HookStatus.unavailable()
    val deadline = android.os.SystemClock.elapsedRealtime() + 2_000L
    try {
        do {
            currentCoroutineContext().ensureActive()
            activity.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"),
                PackageManager.MATCH_ALL,
            )
            delay(100)
            val status = SystemRuleStore.readProbeResponse(activity, request)
            if (status.loadedForCurrentBoot) return status
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        return SystemRuleStore.readProbeResponse(activity, request)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.w("SelectionMenuControl", "System hook probe failed", error)
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
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp),
    ) {
        item {
            Text("文本选择菜单", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                if (state.checking) "正在检查运行环境" else "运行环境未就绪",
                modifier = Modifier.padding(top = 6.dp),
                style = MiuixTheme.textStyles.body1,
                color = if (state.checking) MiuixTheme.colorScheme.onBackgroundVariant else Color(0xFFD95D39),
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
                .background(if (passed) Color(0xFF21A366) else Color(0xFFD95D39)),
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
            color = if (passed) Color(0xFF21A366) else Color(0xFFD95D39),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
@Suppress("UseKtx")
private fun AboutScreen(
    activity: MainActivity,
    onBack: () -> Unit,
    autoCheckEnabled: Boolean,
    onAutoCheckChange: (Boolean) -> Unit,
) {
    var showDailyMenu by remember { mutableStateOf(false) }
    BackHandler(enabled = showDailyMenu) { showDailyMenu = false }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Image(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "返回",
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                "关于文本菜单",
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                maxLines = 1,
                softWrap = false,
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
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F6FF)).padding(16.dp),
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
                    Text("系统文本菜单", color = Color(0xFF1976D2), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("文本菜单控制", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Color(0xFF171717))
                    Text("管理全系统文本选择菜单中的 PROCESS_TEXT 扩展项。", color = Color(0xFF5E6570), fontSize = 14.sp, lineHeight = 20.sp)
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
            }
        }
    }
    if (showDailyMenu) {
        WindowDialog(
            show = true,
            onDismissRequest = { showDailyMenu = false },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onBackgroundVariant, modifier = Modifier.weight(1f))
        Text(value, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, textAlign = TextAlign.End)
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
        Text(action, color = Color(0xFF1976D2), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
    }
}
