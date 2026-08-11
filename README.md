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

当前 Hook 签名针对 ColorOS 16 / Android 16 的 `services.jar` 实测。系统升级后若该签名变化，模块不会执行模糊匹配，菜单将保持系统默认行为。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE)，SPDX 标识为 `GPL-3.0-only`。
