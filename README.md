# 文本选择菜单控制

这是一个仅作用于 Android 系统框架的 Modern LibXposed 模块，用于全局隐藏指定的 `ACTION_PROCESS_TEXT` 扩展项。

## 工作方式

- 静态作用域固定为 `system`，不向普通应用进程注入代码。
- 仅过滤已验证的 `ComputerEngine.queryIntentActivitiesInternal` 9 参数实现，且只处理 `ACTION_PROCESS_TEXT`。
- 规则保存在模块私有存储中，system_server 通过只读 Provider 获取快照。
- 查询、反射、配置读取或组件类型异常时均保持 PackageManager 原结果。

## 构建

需要 JDK 17、Android SDK Platform 37.0 和网络访问 Maven Central。

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 安装

从 [发行版](https://github.com/TheKingBucket001/txtoi/releases) 下载 APK，在已启用 Modern LibXposed 的 LSPosed 中安装并重启设备。

当前版本支持全局隐藏和恢复现有 `PROCESS_TEXT` 扩展项；新增动作、排序、保留并灰显、自定义菜单图标尚未实现。

## 安全边界

本模块不读取或保存选中文字，不修改系统 APK、framework 文件或外部应用组件状态。它只隐藏 `PROCESS_TEXT` 查询返回的已选 component；关闭规则或恢复全部显示后，系统查询恢复原始结果。正式 Release 签名配置在仓库外维护，禁止提交 keystore、私钥或本地签名属性。

## 兼容性

Android 是本模块的目标平台，ColorOS 只是目前唯一完成实机验证的定制系统。当前版本严格匹配 `system_server` 中的 `ComputerEngine.queryIntentActivitiesInternal` 9 参数方法，并要求 Modern LibXposed/LSPosed 提供静态 `system` 作用域。

- **已验证**：ColorOS 16 / Android 16（API 36，PLQ110）。
- **可能兼容但未承诺**：AOSP 或其他厂商系统，只要对应系统的类名、方法参数和返回类型完全一致，并支持 Modern LibXposed 静态 `system` 作用域。
- **未验证/可能不兼容**：Android 其他版本、MIUI/HyperOS、One UI、OriginOS 等系统。若 `services.jar` 的方法签名不同，模块会主动跳过 Hook，系统菜单保持默认，不会使用模糊匹配。

因此，要支持其他 Android 版本或定制系统，需要为各自的 `system_server` 实现增加独立、经过实机验证的 Hook 适配器。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE)，SPDX 标识为 `GPL-3.0-only`。
