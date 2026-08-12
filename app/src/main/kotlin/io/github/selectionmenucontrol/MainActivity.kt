package io.github.selectionmenucontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.Collections
import java.util.Comparator
import java.util.HashSet
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

private data class Processor(val component: String, val label: String, val summary: String)

private data class UpdateInfo(
    val version: String,
    val releaseUrl: String,
)

private sealed interface UpdateCheckResult {
    data class Available(val update: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

@Composable
private fun ModuleApp(activity: MainActivity) {
    var gateState by remember { mutableStateOf(GateState()) }
    var refreshSignal by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

    // This effect belongs to the Activity's root composition, so page navigation and
    // environment refreshes cannot start another check during the same app session.
    LaunchedEffect(Unit) {
        when (val result = withContext(Dispatchers.IO) { checkForUpdate(activity) }) {
            is UpdateCheckResult.Available -> availableUpdate = result.update
            UpdateCheckResult.UpToDate -> Toast.makeText(activity, "暂无更新", Toast.LENGTH_SHORT).show()
            UpdateCheckResult.Failed -> Toast.makeText(activity, "检查更新失败", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(refreshSignal) {
        Thread {
            val hook = waitForSystemHook(activity)
            val root = if (hook.loadedForCurrentBoot) RootAccess.check() else null
            activity.runOnUiThread {
                gateState = GateState(
                    checking = false,
                    systemHookReady = hook.loadedForCurrentBoot,
                    rootReady = root?.granted == true,
                    rootMessage = when {
                        root == null -> "等待系统框架就绪后再验证"
                        root.granted -> "已获得 uid=0"
                        else -> root.message
                    },
                )
            }
        }.start()
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
            if (aboutVisible) AboutScreen(activity) { showAbout = false }
            else RuleScreen(activity) { showAbout = true }
        }
    }

    availableUpdate?.let { update ->
        UpdateDialog(
            update = update,
            onDismiss = { availableUpdate = null },
            onOpenRelease = {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                } catch (_: Throwable) {
                    Toast.makeText(activity, "无法打开更新页面", Toast.LENGTH_SHORT).show()
                }
            },
        )
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
    if (!waitForValidatedNetwork(context)) {
        Log.w(UPDATE_LOG_TAG, "Update check skipped: no validated network")
        return UpdateCheckResult.Failed
    }

    var lastFailure: Throwable? = null
    repeat(UPDATE_CHECK_ATTEMPTS) { attempt ->
        try {
            val result = requestLatestRelease()
            Log.i(UPDATE_LOG_TAG, "Update check succeeded on attempt ${attempt + 1}")
            return result
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

private fun requestLatestRelease(): UpdateCheckResult {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = UPDATE_REQUEST_TIMEOUT_MS
            readTimeout = UPDATE_REQUEST_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "txtoi-android-update-check")
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("Unexpected HTTP status ${connection.responseCode}")
        }
        val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        val version = release.optString("tag_name").trim()
        val releaseUrl = release.optString("html_url").trim()
        require(version.isNotBlank() && releaseUrl.isNotBlank()) { "Release response is incomplete" }
        return if (isVersionNewer(version, BuildConfig.VERSION_NAME)) {
            UpdateCheckResult.Available(UpdateInfo(version, releaseUrl))
        } else {
            UpdateCheckResult.UpToDate
        }
    } finally {
        connection?.disconnect()
    }
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

private const val LATEST_RELEASE_URL = "https://api.github.com/repos/TheKingBucket001/txtoi/releases/latest"
private const val NETWORK_READY_TIMEOUT_MS = 12_000L
private const val UPDATE_REQUEST_TIMEOUT_MS = 8_000
private const val UPDATE_CHECK_ATTEMPTS = 3
private const val UPDATE_RETRY_DELAY_MS = 1_500L
private const val UPDATE_LOG_TAG = "SelectionMenuControl"

private fun waitForSystemHook(activity: ComponentActivity): SystemRuleStore.HookStatus {
    val deadline = System.currentTimeMillis() + 2_000L
    try {
        do {
            activity.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"),
                PackageManager.MATCH_ALL,
            )
            Thread.sleep(150)
            val status = SystemRuleStore.readHookStatus(activity)
            if (status.loadedForCurrentBoot) return status
        } while (System.currentTimeMillis() < deadline)
    } catch (_: Throwable) {
        // The final status read keeps the page locked if PackageManager cannot be queried.
    }
    return SystemRuleStore.readHookStatus(activity)
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
                    summary = if (state.systemHookReady) "已在本次启动中加载" else "请在 LSPosed 中固定系统框架作用域，然后重启设备。",
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
private fun RuleScreen(activity: MainActivity, onAbout: () -> Unit) {
    var snapshot by remember { mutableStateOf(SystemRuleStore.read(activity)) }
    var processors by remember { mutableStateOf<List<Processor>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val migrated = withContext(Dispatchers.IO) {
            SystemRuleStore.migrateToGlobal(activity, snapshot.hiddenComponents)
        }
        if (!migrated) {
            Toast.makeText(activity, "规则迁移失败，请确认 Root 授权", Toast.LENGTH_LONG).show()
        }
        processors = withContext(Dispatchers.IO) { loadProcessors(activity, snapshot.hiddenComponents) }
    }

    fun save(nextHidden: Set<String>) {
        saving = true
        Thread {
            val saved = SystemRuleStore.save(activity, nextHidden)
            activity.runOnUiThread {
                saving = false
                if (saved) {
                    snapshot = SystemRuleStore.Snapshot(nextHidden)
                    Toast.makeText(activity, "隐藏规则已保存", Toast.LENGTH_SHORT).show()
                } else {
                    snapshot = SystemRuleStore.read(activity)
                    Toast.makeText(activity, "规则保存失败", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "文本选择菜单",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onAbout, enabled = !saving) {
                    Image(
                        painter = painterResource(R.drawable.ic_info),
                        contentDescription = "关于",
                        modifier = Modifier.size(25.dp),
                    )
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 0.dp)) {
                Text("文字处理扩展项", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                Text(
                    "勾选的项目会从所有应用的文本选择菜单中隐藏。",
                    modifier = Modifier.padding(top = 2.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp)) {
                if (processors == null) {
                    Text("正在读取系统扩展项…", modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2)
                } else if (processors!!.isEmpty()) {
                    Text("当前没有可配置的文字处理扩展项", modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2)
                }
            }
        }
        items(processors ?: emptyList(), key = { it.component }) { processor ->
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) {
                CheckboxPreference(
                    title = processor.label,
                    summary = processor.summary,
                    checked = snapshot.hiddenComponents.contains(processor.component),
                    enabled = !saving,
                    checkboxLocation = CheckboxLocation.End,
                    onCheckedChange = { checked ->
                        val next = HashSet(snapshot.hiddenComponents)
                        if (checked) next.add(processor.component) else next.remove(processor.component)
                        save(next)
                    },
                )
            }
        }
        item {
            TextButton(
                text = "恢复全部显示",
                onClick = { showRestoreConfirm = true },
                enabled = snapshot.hiddenComponents.isNotEmpty() && !saving,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 4.dp),
                colors = ButtonDefaults.textButtonColors(
                    textColor = Color(0xFFD14343),
                    disabledTextColor = Color(0xFFD14343).copy(alpha = 0.4f),
                ),
            )
        }
    }

    if (showRestoreConfirm) {
        WindowDialog(
            show = true,
            onDismissRequest = { showRestoreConfirm = false },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("恢复全部显示", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("将清除已隐藏的扩展项，所有 PROCESS_TEXT 菜单会恢复显示。", fontSize = 14.sp, lineHeight = 20.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = "取消",
                            onClick = { showRestoreConfirm = false },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "确认恢复",
                            onClick = {
                                showRestoreConfirm = false
                                save(emptySet())
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            },
        )
    }
}

@Composable
@Suppress("UseKtx")
private fun AboutScreen(activity: MainActivity, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F6FF)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Image(painter = painterResource(R.drawable.ic_module), contentDescription = null, modifier = Modifier.size(64.dp))
                    Text("系统文本菜单", color = Color(0xFF1976D2), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("文本菜单控制", fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Color(0xFF171717))
                    Text("管理全系统文本选择菜单中的 PROCESS_TEXT 扩展项。", color = Color(0xFF5E6570), fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
        }
        item {
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                AboutInfoRow("当前版本", BuildConfig.VERSION_NAME)
                AboutInfoDivider()
                AboutInfoRow("模块 ID", "txtoi")
                AboutInfoDivider()
                AboutInfoRow("维护者", "Bucket")
            }
        }
        item {
            Text("项目", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp))
        }
        item {
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                AboutActionRow("查看源代码", "GitHub · TheKingBucket001/txtoi", "GitHub") {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/TheKingBucket001/txtoi")))
                }
                AboutInfoDivider()
                AboutActionRow("开源许可证", "GNU General Public License v3.0", "GPL-3.0", null)
            }
        }
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

private fun loadProcessors(activity: ComponentActivity, hiddenComponents: Set<String>): List<Processor> {
    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
    val packageManager = activity.packageManager
    val infos: List<ResolveInfo> = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    val processors = ArrayList<Processor>()
    val knownComponents = HashSet<String>()
    for (info in infos) {
        val activityInfo: ActivityInfo = info.activityInfo ?: continue
        val component = ComponentName(activityInfo.packageName, activityInfo.name)
        val label = info.loadLabel(activity.packageManager).toString().ifBlank { component.shortClassName }
        processors.add(Processor(component.flattenToString(), label, activityInfo.packageName))
        knownComponents.add(component.flattenToString())
    }
    // The system_server hook hides selected entries from queryIntentActivities. Read their
    // metadata directly so an upgrade or restart cannot make persisted rules disappear.
    for (flattened in hiddenComponents) {
        if (knownComponents.contains(flattened)) continue
        val component = ComponentName.unflattenFromString(flattened) ?: continue
        val activityInfo = try {
            packageManager.getActivityInfo(component, PackageManager.MATCH_ALL)
        } catch (_: Throwable) {
            null
        } ?: continue
        val label = activityInfo.loadLabel(packageManager).toString().ifBlank { component.shortClassName }
        processors.add(Processor(component.flattenToString(), label, activityInfo.packageName))
    }
    Collections.sort(processors, Comparator.comparing { it.label.lowercase() })
    return processors
}
