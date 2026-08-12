# 文本选择菜单控制

面向 ColorOS 16 的 Modern LibXposed 模块。它让你在一个列表中选择要隐藏的文字处理扩展项，并让选择结果同时作用于系统和普通应用的文本选择菜单。

> 当前发布版：[`v0.3.4`](https://github.com/TheKingBucket001/txtoi/releases/tag/v0.3.4)
> 已验证设备：ColorOS 16 / Android 16（API 36，PLQ110）

## 能做什么

- 列出设备已安装的 `PROCESS_TEXT` 文字处理扩展项。
- 勾选项目后，全局从文本选择菜单隐藏该项目。
- 随时通过“恢复全部显示”清空规则。
- 覆盖安装或重启后，已隐藏项目仍保留在配置列表中，可直接取消隐藏。

模块不读取选中文字，不修改系统 APK、framework 或外部应用组件状态；它只在系统查询 `ACTION_PROCESS_TEXT` 结果时过滤已选组件。

## 使用前确认

| 条件 | 要求 |
| --- | --- |
| 系统 | 当前仅实机验证 ColorOS 16 / Android 16（API 36，PLQ110） |
| Root | 设备必须已获得 Root |
| 框架 | 已安装支持 Modern LibXposed 静态 `system` 作用域的 LSPosed |
| 重启 | 安装、启用模块或更新系统作用域后都需要重启 |

这不是“所有 Android 通用”的模块。它严格匹配 `system_server` 中 `ComputerEngine.queryIntentActivitiesInternal` 的 9 参数实现。其他 Android 版本、AOSP、MIUI/HyperOS、One UI、OriginOS 等系统，只有方法签名和返回类型完全一致且框架支持静态 `system` scope 时才可能工作；不匹配时模块会跳过 Hook，系统菜单保持默认。

## 安装与使用

1. 从 [Releases](https://github.com/TheKingBucket001/txtoi/releases) 下载 APK 并安装。
2. 在 LSPosed 中启用模块，确认其静态作用域为 `system`。
3. 重启设备，打开“文本选择菜单控制”。
4. 在“文字处理扩展项”列表勾选需要隐藏的项目。
5. 长按任意可编辑文本，验证对应菜单项已消失；需要恢复时点击“恢复全部显示”。

应用会先检查 system_server Hook 与 Root 是否在本次启动中就绪。环境检测页未通过时，不会允许写入规则。

## 当前范围

| 已实现 | 尚未实现 |
| --- | --- |
| 全局隐藏 / 恢复现有扩展项 | 新增自定义动作 |
| 持久化规则与升级后可见性恢复 | 菜单排序 |
| system_server 加载状态与 Root 检测 | 保留项目但置灰 |
| 关于页与开源入口 | 自定义菜单图标 |

## 原理与边界

- 模块仅在静态 `system` scope 中运行，不向普通应用进程注入代码。
- 它只处理 `ACTION_PROCESS_TEXT`，其他 Intent 查询保持原样。
- 规则保存在模块私有存储中，system_server 通过只读 Provider 获取快照并按 1.5 秒缓存刷新。
- 反射、规则读取、Provider 或组件类型出现异常时，模块返回 PackageManager 原始结果。

## 从源码构建

需要 JDK 17、Android SDK Platform 37.0，以及可访问 Maven Central 的网络。

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:verifyModernXposedMetadata --no-daemon
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。正式签名材料仅应保存在仓库外的安全位置，禁止提交 keystore、私钥或 `signing.properties`。

## 贡献与安全

- 新系统适配必须提供对应 `ComputerEngine` 方法签名和实机验证，不能使用模糊 Hook。
- 提交前执行上述构建、Lint 和 Modern 元数据校验；详见 [CONTRIBUTING.md](CONTRIBUTING.md)。
- 安全问题请按 [SECURITY.md](SECURITY.md) 的方式提交。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE)，SPDX 标识为 `GPL-3.0-only`。
