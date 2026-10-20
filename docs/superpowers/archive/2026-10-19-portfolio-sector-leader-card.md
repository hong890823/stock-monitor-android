# 持仓页板块龙头卡片与编辑入口整理 · 归档

**归档日期：** 2026-10-19
**项目：** `stock-monitor-android`
**范围：** ①「板块龙头」卡片移到“持仓”Tab「持仓复盘」下方；②「调整龙头」始终可点开编辑；
③ 明确「调整龙头」与「归类管理」的职责边界；④ 修复手动指定候选外个股后刷新丢失的问题。

## 一、问题

- 「板块龙头」原在“盘面”Tab，用户在“持仓”复盘时不方便校验龙头。
- 「调整龙头」依赖已有持仓归类和板块成分数据，数据不完整或归属不准确时入口会被拦截，
  用户无法随时校验和改正。
- 两个编辑入口的职责容易混淆：一个改板块归属，一个改龙头名单。

## 二、产品规则

| 入口 | 负责什么 | 什么时候使用 |
| --- | --- | --- |
| **归类管理** | 哪些股票属于哪个归类；持仓挂靠、自定义归类、成分股增删 | F10 自动归属不准，或需要自定义板块 |
| **调整龙头** | 某个归类里谁算龙头；手动指定、排除、恢复自动 | 自动成交额排名与实盘龙头判断不一致 |

正常情况下两个都不需要操作；先保证归类正确，再校正龙头。保存人工龙头后以人工名单为准，
「恢复自动」清掉该归类的人工修正。

## 三、实现

- `PortfolioFragment`：卡片放在「持仓复盘」和「交易工具」之间；两个操作使用完整宽度按钮，
  不再依赖卡片右上角的小文字入口。
- `BoardLeaderDialogs`：统一承载归类选择和龙头编辑；无归类时仍会弹出引导对话框，
  可直接进入「归类管理」；成分缺失时仍能打开编辑器，并支持按代码/名称添加个股。
- `BoardLeaderEditorPolicy`：归类即使当前无持仓也可进入龙头编辑；候选取
  自动成分 ∪ 当前龙头 ∪ 人工指定的并集，避免数据未刷新时丢掉人工名单。
- `MarketFragment`：移除原「板块龙头」卡片和重复编辑逻辑，避免同一功能在两个 Tab 各自维护。
- `Models.kt` / `MonitorService.kt`：人工指定的个股即使不在自动候选里，也作为龙头保留并进入下轮刷新。

## 四、主要源码位置

- `app/src/main/java/com/example/stockmonitor/ui/PortfolioFragment.kt`
- `app/src/main/java/com/example/stockmonitor/ui/BoardLeaderDialogs.kt`
- `app/src/main/java/com/example/stockmonitor/ui/BoardLeaderEditorPolicy.kt`
- `app/src/main/java/com/example/stockmonitor/ui/MarketFragment.kt`
- `app/src/main/java/com/example/stockmonitor/core/Models.kt`
- `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`

## 五、验证

- 新增 `BoardLeaderEditorPolicyTest`：覆盖无持仓归类仍可编辑、候选数据缺失时回退到已知龙头和人工指定。
- 更新 `SectorLeadersTest`：覆盖手动指定不在自动候选中的个股仍然生效。
- 最终命令：`./gradlew testDebugUnitTest assembleDebug`。
- 结果：`623` 项测试通过，`0` 失败、`0` 错误、`0` 跳过；Debug 构建成功。
- Debug APK：`app/build/intermediates/apk/debug/app-debug.apk`
- SHA-256：`fac45bc631d5f3b186dafe518773f4cfb17bc9a1bc5d392c6adf8ecc8cddc84a`

## 六、边界

- 本次验证覆盖逻辑单测、Kotlin 编译和 Debug 构建，未在真机上做人工点击回归。
- 项目当前不是 Git 仓库，也没有活动的 OpenSpec change；按项目现有约定保存在
  `docs/superpowers/archive/`，未创建 Git commit。
