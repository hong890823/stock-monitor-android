# 板块管理改名与个股长按删除 · 归档

**归档日期：** 2026-10-20
**项目：** `stock-monitor-android`
**范围：** ①「归类管理」全面改名「板块管理」；② 管理弹窗分区展示（我的板块 / 操作）；
③ 「板块个股」「调整龙头」列表支持长按删除个股；④ 修复删除后候选池残留；⑤ 修复板块个股编辑器按钮行溢出错乱。

## 一、问题

- 自定义板块的「板块个股」编辑器里「添加个股 + 删除板块」双按钮横向排布且无宽度约束，超出对话框宽度后互相挤压、裁切，UI 错乱（自动板块只有单按钮，问题不显）。
- 「归类管理」入口与弹窗文案用「归类」口径，与界面上的「板块」叫法割裂（如「板块归类管理」标题）。
- 管理弹窗把已有板块和「新建 / 编辑持仓归属 / 删除」等操作平铺混排，操作区和板块列表没有视觉区分。
- 龙头 / 成分个股只能「添加个股」没有删除入口；且删掉的个股因候选池保留旧数据会一直留在编辑器列表里。

## 二、产品规则

| 动作 | 行为 |
| --- | --- |
| 长按个股删除（板块个股） | 移出板块成分，随「保存」落盘，「取消」不生效；可再「添加个股」加回（重新添加优先于删除） |
| 长按个股删除（调整龙头） | 从板块成分和龙头候选一并移除；只想移出龙头用取消勾选 + 保存 |
| 勾选 / 取消勾选（调整龙头） | 勾选 = 算龙头（pinned），取消 = 移出龙头（记排除名单，防自动排名捞回），不动成分 |
| 删除板块 | 自定义板块整组删除，挂靠持仓回到 F10 自动归属 |

命名统一为「板块管理」：弹窗标题、入口按钮、新建板块、删除板块、板块个股、板块名称等全部改口径。
管理弹窗分两区：「我的板块」（点板块进成分股编辑）与「操作」（新建板块、编辑持仓归属、编辑持仓、删除板块 / 恢复自动成分），分区标题灰字不可点。

## 三、实现

- `ui/BoardGroupDialogs.kt`：`openManager` 弃用平铺 `setItems`，改 `SectionedAdapter`（`SectionRow` 带点击动作，标题行 `isEnabled=false`）分区渲染；`openGroupEditor` 列表改可变 `entries` + `renderList()` / `syncChecked()`，长按确认删除，删除集合经 `removedCodes` 穿透「添加个股」递归打开的编辑器；按钮行 `HORIZONTAL` → `VERTICAL` + `MATCH_PARENT` 宽（与 `PortfolioFragment.buttonRow` 同口径）。
- `ui/BoardLeaderDialogs.kt`：`openEditor` 同样支持长按删除；保存时被删个股从人工名单剔除（自动板块由此固化为人工名单，可「恢复自动成分」撤销），`pinned` / `excluded` 同步收敛。
- `monitor/MonitorService.refreshSectorLeaders`：人工名单为空（个股删光）时清掉该板块的 `sectorMembers` 候选池与 `sectorLeaders` 龙头名单；自动归类拉不到东财成分仍保留旧数据，不误清。
- `ui/PortfolioFragment.kt` 及各对话框字符串：「归类管理」→「板块管理」。

## 四、主要源码位置

- `app/src/main/java/com/example/stockmonitor/ui/BoardGroupDialogs.kt`
- `app/src/main/java/com/example/stockmonitor/ui/BoardLeaderDialogs.kt`
- `app/src/main/java/com/example/stockmonitor/ui/PortfolioFragment.kt`
- `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`

## 五、验证

- 最终命令：`./gradlew testDebugUnitTest assembleDebug`。
- 结果：`623` 项测试通过，`0` 失败、`0` 错误、`0` 跳过；Debug 构建成功。
- Debug APK：`app/build/intermediates/apk/debug/app-debug.apk`
- SHA-256：`872b7bc095d374c629582ab0d3d3b6f772763f70b83a49b3afed020673d91410`

## 六、边界

- 本次验证覆盖逻辑单测、Kotlin 编译和 Debug 构建，未在真机上做人工点击回归。
- 自动板块里长按删除个股会把成分固化为人工名单（该板块此后不跟东财成分更新，「恢复自动成分」可撤销）。
- 项目当前不是 Git 仓库；按项目现有约定保存在 `docs/superpowers/archive/`，未创建 Git commit。
