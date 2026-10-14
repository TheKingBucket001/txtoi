package io.github.selectionmenucontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class GateState(
    val checking: Boolean = true,
    val systemHookReady: Boolean = false,
    val rootReady: Boolean = false,
    val rootMessage: String = "正在验证 Root 授权",
    val systemMessage: String = "等待系统框架作用域检测",
    val rootChecked: Boolean = false,
    val cleanupPending: Boolean = false,
)

/** Root identity and loaded system scope are independent facts with ordered prerequisites. */
internal suspend fun checkEnvironment(
    checkRoot: suspend () -> RootAccess.Result,
    checkScope: suspend (suspend (SystemRuleStore.HookStatus) -> Unit) -> SystemRuleStore.HookStatus,
    publish: suspend (GateState) -> Unit,
): GateState {
    var root: RootAccess.Result? = null
    try {
        currentCoroutineContext().ensureActive()
        root = checkRoot()
        val rootState = GateState(
            checking = root.granted,
            rootReady = root.granted,
            rootMessage = root.message,
            systemMessage = if (root.granted) "正在检查系统框架作用域"
                else "Root 未通过，暂不执行作用域检测",
            rootChecked = true,
        )
        publish(rootState)
        if (!root.granted) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.ROOT_FAILED)
            return rootState
        }
        ModuleDiagnostics.recover(ModuleDiagnostics.Code.ROOT_FAILED)
        currentCoroutineContext().ensureActive()
        var result = rootState
        val scope = checkScope { status ->
            result = rootState.copy(
                checking = false,
                cleanupPending = true,
                systemHookReady = status.loadedForCurrentBoot,
                systemMessage = status.message,
            )
            publish(result)
        }
        return result.copy(checking = false, cleanupPending = false, systemHookReady = scope.loadedForCurrentBoot,
            systemMessage = scope.message)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        val rootGranted = root?.granted == true
        ModuleDiagnostics.fault(if (rootGranted) ModuleDiagnostics.Code.PROBE_FAILED
            else ModuleDiagnostics.Code.ROOT_FAILED)
        val failed = GateState(
            checking = false,
            cleanupPending = false,
            rootReady = rootGranted,
            rootMessage = root?.message ?: "无法完成 Root 验证，请重新检查",
            systemMessage = if (rootGranted) "系统框架作用域检测未完成，请重新检查"
                else "Root 未通过，暂不执行作用域检测",
            rootChecked = true,
        )
        publish(failed)
        return failed
    }
}
