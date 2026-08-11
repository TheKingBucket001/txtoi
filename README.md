# 文本选择菜单控制

一个仅作用于 Android 系统框架的 Modern LibXposed 模块，用于全局隐藏指定的 `ACTION_PROCESS_TEXT` 扩展项。

## 工作方式

- 静态作用域固定为 `system`，不向普通应用进程注入代码。
- 仅过滤已验证的 `ComputerEngine.queryIntentActivitiesInternal` 9 参数实现，且只处理 `ACTION_PROCESS_TEXT`。
- 规则保存在模块私有存储中，system_server 通过只读 Provider 获取快照。
- 查询、反射、配置读取或组件类型异常时均保持 PackageManager 原结果。

## 使用前提

1. 安装后在 LSPosed 启用模块并重启设备。
2. 模块启动后会验证本次启动内 system_server 已加载，以及 `/system/bin/su -c id` 已取得 `uid=0`。
3. 两项未通过时设置页保持锁定，不会显示或修改规则。

## 构建

需要 JDK 17、Android SDK Platform 37.0 和网络访问 Maven Central。

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 当前范围

v0.3.2 仅支持全局隐藏和恢复现有 `PROCESS_TEXT` 扩展项。新增动作、排序、禁用后仍显示和自定义菜单图标需要厂商浮动工具栏的单独适配，尚未作为已实现功能发布。

## 发布签名

正式 Release 仅从未跟踪的 `signing.properties` 或 `ANDROID_KEYSTORE_*` 环境变量读取本机密钥，仓库不包含 Signing keys、口令或 Authentication keys。`signing.properties` 必须包含 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`。

## 安全边界

本模块不读取或保存选中文字，不修改系统 APK、framework 文件或外部应用组件状态。它只隐藏 `PROCESS_TEXT` 查询返回的已选 component；关闭全局规则或恢复全部显示后，系统查询恢复原始结果。

## 兼容性

当前 Hook 签名针对 ColorOS 16 / Android 16 的 `services.jar` 实测。系统升级后若该签名变化，模块不会执行模糊匹配，菜单将保持系统默认行为。

## 许可证

Apache-2.0。详见 [LICENSE](LICENSE)。
