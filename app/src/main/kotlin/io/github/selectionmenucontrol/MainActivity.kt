package io.github.selectionmenucontrol

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.util.Collections
import java.util.Comparator
import java.util.HashSet

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

private data class Processor(val component: String, val label: String)

@Composable
private fun ModuleApp(activity: MainActivity) {
    var gateState by remember { mutableStateOf(GateState()) }
    var refreshSignal by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }

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
    } else if (showAbout) {
        AboutScreen(activity) { showAbout = false }
    } else {
        RuleScreen(activity) { showAbout = true }
    }
}

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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("文本选择菜单控制", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.Bold)
            Text("运行环境未就绪", style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.onBackgroundVariant)
            Card(modifier = Modifier.fillMaxWidth()) {
                GateRow("系统框架作用域", state.systemHookReady, if (state.systemHookReady) "已在本次启动中加载" else "请确认 LSPosed 中已固定系统框架作用域并重启")
                GateRow("Root 授权", state.rootReady, state.rootMessage)
            }
            TextButton(
                text = if (state.checking) "正在检测" else "重新检测",
                onClick = onRefresh,
                enabled = !state.checking,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GateRow(title: String, passed: Boolean, summary: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (passed) Color(0xFF21A366) else Color(0xFFD95D39)),
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1)
            Text(summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackgroundVariant)
        }
        Text(if (passed) "已就绪" else "阻止进入", style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun RuleScreen(activity: MainActivity, onAbout: () -> Unit) {
    var snapshot by remember { mutableStateOf(SystemRuleStore.read(activity)) }
    var processors by remember { mutableStateOf<List<Processor>?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        processors = withContext(Dispatchers.IO) { loadProcessors(activity) }
    }

    fun save(nextEnabled: Boolean, nextHidden: Set<String>) {
        saving = true
        Thread {
            val saved = SystemRuleStore.save(activity, nextEnabled, nextHidden)
            activity.runOnUiThread {
                saving = false
                if (saved) {
                    snapshot = SystemRuleStore.Snapshot(nextEnabled, nextHidden)
                    Toast.makeText(activity, "全局规则已保存", Toast.LENGTH_SHORT).show()
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
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("文本选择菜单", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("全系统管理 PROCESS_TEXT 扩展项", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackgroundVariant)
                }
                TextButton(text = "关于", onClick = onAbout, enabled = !saving)
            }
        }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                SwitchPreference(
                    title = "启用全局规则",
                    summary = "关闭后不隐藏任何文本选择扩展项",
                    checked = snapshot.enabled,
                    enabled = !saving,
                    onCheckedChange = { checked -> save(checked, snapshot.hiddenComponents) },
                )
            }
        }
        item { SmallTitle("文字处理扩展项") }
        item {
            Text(
                "勾选的项目会从所有应用的文本选择菜单中隐藏。",
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 2.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
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
                    summary = processor.component,
                    checked = snapshot.hiddenComponents.contains(processor.component),
                    enabled = snapshot.enabled && !saving,
                    checkboxLocation = CheckboxLocation.End,
                    onCheckedChange = { checked ->
                        val next = HashSet(snapshot.hiddenComponents)
                        if (checked) next.add(processor.component) else next.remove(processor.component)
                        save(snapshot.enabled, next)
                    },
                )
            }
        }
        item {
            TextButton(
                text = "恢复全部显示",
                onClick = { save(snapshot.enabled, emptySet()) },
                enabled = snapshot.hiddenComponents.isNotEmpty() && !saving,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            )
        }
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
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(text = "返回", onClick = onBack)
                Text("关于", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
            }
        }
        item {
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("文本选择菜单控制", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.Bold)
                    Text("版本 0.3.2", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackgroundVariant)
                    Text("全局管理系统文本选择菜单中的 PROCESS_TEXT 扩展项。", style = MiuixTheme.textStyles.body1)
                }
            }
        }
        item { SmallTitle("项目") }
        item {
            Card(modifier = Modifier.padding(horizontal = 16.dp)) {
                TextButton(
                    text = "在 GitHub 查看源码",
                    onClick = { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/TheKingBucket001/txtoi"))) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Text(
                "本项目采用 GNU GPL v3.0 开源许可证。",
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
    }
}

private fun loadProcessors(activity: ComponentActivity): List<Processor> {
    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
    val infos: List<ResolveInfo> = activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    val processors = ArrayList<Processor>()
    for (info in infos) {
        val activityInfo: ActivityInfo = info.activityInfo ?: continue
        val component = ComponentName(activityInfo.packageName, activityInfo.name)
        val label = info.loadLabel(activity.packageManager).toString().ifBlank { component.shortClassName }
        processors.add(Processor(component.flattenToString(), label))
    }
    Collections.sort(processors, Comparator.comparing { it.label.lowercase() })
    return processors
}
