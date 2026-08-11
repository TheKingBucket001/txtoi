# 贡献指南

提交前请保持变更聚焦，并运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon
```

涉及 system_server Hook 的变更必须说明目标系统版本、真实方法签名、失败回退和设备验证结果。禁止扩大静态作用域，禁止记录用户选中文字或在异常分支替换非 `PROCESS_TEXT` 查询结果。
