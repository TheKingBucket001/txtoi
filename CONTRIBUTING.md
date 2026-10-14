# 贡献指南

感谢你参与项目。提交代码前，请按下面的步骤检查，确保其他人可以复现你的结果。

## 提交前检查

1. 只修改与本项目有关的文件，并在提交说明中写清楚改了什么、为什么改。
2. 在项目根目录执行以下命令，确认能构建、通过代码规范检查：

   ```powershell
   .\gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:verifyModernXposedMetadata --dependency-verification=strict --no-daemon
   python -m unittest discover -s .github/scripts/tests -v
   ```

3. 如果命令失败，请在提交说明中附上完整错误信息和使用的 JDK、Android SDK 版本。

修改 Root 命令协议后，还须使用 mksh 重跑完整应用测试。Android 的 mksh 数值运算与桌面 Bash 不同；Linux 安装 mksh 后执行：

```bash
./gradlew :app:testDebugUnitTest -ProotProtocolTestShell=/bin/mksh --dependency-verification=strict --no-daemon
```

Windows 可将该参数改为本机 `mksh.exe` 的绝对路径，并确保其运行库所在目录在 PATH 中。显式指定的路径不可用时构建直接失败。CI 同时执行默认 Shell 与 mksh 两套测试；桌面模拟仍不能替代真实 Android 的 Root、SELinux 与 Binder 验收。

## 修改系统框架 Hook 时

如果修改了 `system_server` Hook，请同时写明：

- 测试的 Android/ColorOS 版本；
- 实际找到的方法完整签名（参数数量和类型）；
- 找不到方法或运行出错时，程序如何恢复系统原始结果；
- 在真实设备上验证过的现象和仍未验证的情况。

请勿扩大模块的静态作用域，不要记录用户选中的文字，也不要在异常情况下修改不是 `PROCESS_TEXT` 的查询结果。

## 提交内容

不要提交密钥、签名文件、未脱敏的设备日志或本地构建产物。提交前请检查 `git diff`，确保改动和提交说明一致。

## 发布检查

正式版必须通过 GitHub Actions 构建、Release Lint、单元测试及正式 APK 的 Modern Xposed 元数据检查。主仓 `v版本号` 和镜像 `版本代码-版本号` Tag 都必须提前以 SSH `git tag -s` 创建，推送后确认 GitHub `verification.verified=true`。新镜像 Tag 应指向与主仓相同的源代码提交。

CI 会先核对正式 APK 的长期签名证书、包名、版本、非调试状态及仅 `system` 的 Modern Xposed 元数据。APK 必须包含唯一的 `assets/probe-build.json`；校验工具通过 `--source-root` 从同次源码重新计算构建身份，并核对 variant 与 R8 参数，源码或构建参数变化后必须重新构建。Tag 缺失或验证失败时停止发布，不允许由 Release 自动创建未签名 Tag。正式版检查不重复打包 Debug APK，仍执行 Debug 单元测试。

普通验证任务只有仓库读取权限；仅签名版本 Tag 触发的独立发布任务有主仓写入权限。主仓和镜像先创建草稿、上传 APK，核对两边 GitHub 摘要及重新下载的文件后才公开。跨仓公开不是原子操作；失败会列出实际状态，重跑失败的发布任务可复用同次验证产物，继续发布未公开的草稿。已公开的 APK 摘要不符时拒绝覆盖，必须调查原因并以新版本修正。

更新说明维护在 `.github/release-notes.md`，应只写最终用户实际得到的变化及通过的验证。

## 构建来源与依赖校验

Actions 固定到经过官方 API 核实的完整提交 SHA。Gradle Wrapper 分发包和 Wrapper JAR 均按官方 SHA-256 校验；变更版本时必须同时更新并重新核对官方摘要。

依赖校验清单位于 `gradle/verification-metadata.xml`，后续构建默认严格检查。首次清单基于当前依赖解析生成，不能因此宣称所有依赖都通过了独立来源审计。确实修改依赖时，使用 `--write-verification-metadata sha256` 更新清单，逐项检查新增组件、下载来源及校验值，再运行严格模式的完整门禁。不要为消除校验失败而删除旧摘要或盲目信任变化的字节。

Linux 发布与 Windows 本地构建会使用不同的 AAPT2 二进制，校验清单必须同时包含这两个平台的官方摘要。冷缓存以及实际 Release、Lint、单元测试仍需要验证，解析依赖成功不能代替这些门禁。

## R8 正式构建与回退

正式 Release 默认启用 R8，并使用下面的命令构建唯一交付包。需要比较未裁剪行为时，必须显式传入 `-Pr8Candidate=false`；该回退包只用于本地排查，不作为交付包或手机验收基线：

```powershell
.\gradlew.bat :app:assembleRelease :app:lintRelease :app:verifyReleaseModernXposedMetadata --dependency-verification=strict --no-daemon
python .github/scripts/verify_r8_entrypoints.py --apk app/build/outputs/apk/release/app-release.apk --mapping app/build/outputs/mapping/release/mapping.txt --dexdump "$env:ANDROID_HOME/build-tools/36.0.0/dexdump.exe"
# 仅本地回退比较：
.\gradlew.bat :app:assembleRelease -Pr8Candidate=false --dependency-verification=strict --no-daemon
```

入口验证工具读取同次构建的 mapping 和 APK 中全部实际 DEX，逐类核对模块保留字面名称、公共零参构造和两个外部回调的完整签名，以及三个 ViewModel 的公共反射构造。工具还沿实际父类链核对模块可赋给运行时 `XposedModule`、两个接收 `Application` 的 ViewModel 可赋给同次 mapping 对应的 `AndroidViewModel` 和 `ViewModel`、规则页 ViewModel 可赋给 `ViewModel`；缺失、错误或循环父类链均停止验证。请将 `--dexdump` 改为本机 Android SDK Build Tools 中的实际可执行文件；Linux 使用 `dexdump`。JSON 结果包含 APK、mapping 摘要、已核对的方法和父类链。不要把旧 mapping 与新 APK 混用。

记录 APK 体积、DEX、冷启动和内存的改前／改后结果，实际验证 Hook、菜单识别、保存、更新、全部页面及取消操作。静态入口通过不能代替实机行为验证。`app/proguard-rules.pro` 保留外部加载的模块入口；系统反射目标属于设备框架，无需保留整个应用包。R8 默认包仍需通过静态入口、正式签名、来源身份及设备关键链路验证；性能、功耗、异常掉电和完整兼容矩阵需要分别记录。
