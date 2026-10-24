# 0.8.1

- 兼容 Firefox（GeckoView）等通过 `PackageManager.queryIntentActivityOptions` 查询 `ACTION_PROCESS_TEXT` 菜单项的应用，无需额外勾选宿主应用作用域即可生效隐藏、排序与改名规则。
- 保留 `queryIntentActivityOptions` 调用方显式指定的 `specifics` 前置项，仅对通用 `ACTION_PROCESS_TEXT` 扩展项应用规则。
