# 盘中风险、T+1 与双周期确认 · 归档

**归档日期：** 2026-10-20
**项目：** `stock-monitor-android`
**范围：** 交易批次与 T+1 可卖资格、买入与做 T 的双周期稳健性、风险卖出及时性、盘中提醒文案和交易录入 UI。

## 一、结论

本次改动对买入和做 T 是正向收紧，不是扩大操作频率：

- 普通买入、接回、普通止盈/减仓和做 T 必须同时满足 15 分钟执行条件、30 分钟方向不反向、数据完整和交易资格；不满足时只提示“仅观察”，并移除具体操作股数。
- A 股 T+1 被纳入数量计算。当天买入批次当天锁定，所有卖出建议以当天可卖数量封顶；旧仓迁移批次保持可卖，避免升级后误把全部持仓锁死。
- 做 T 数量仍遵守原 1/3 机动仓规则，再与当天可卖数量取小并按交易单位向下取整，不会用当天新增仓位凑数。

风险卖出保持不对称优先级：

- 跌破支撑、确认破位、高位转弱和退出优先使用 15 分钟风险结构，不等待 30 分钟过滤。
- 风险提醒不受 `intradaySignalEnabled`、普通 10 分钟冷却和每日机会预算限制，状态升级立即重新通知；状态未变化仍只发一次。
- 风险信号在分钟 K 线网络请求之前完成判定与发送，避免 15/30 分钟数据刷新延迟风险处置。
- 只有当天新增仓位时，文案改为“停止加仓，下一交易日复核处置”，不给当天卖出股数。

因此，本组改动降低的是模糊买入、追高、超可卖数量做 T 和被 30 分钟反向结构误导的风险；没有降低明确风险卖出的及时性。

## 二、主要实现

- `core/TradeLots.kt`：`TradeRecord`、FIFO `TradeLot`、迁移批次、T+1 可卖数量和组合买卖应用。
- `core/TradeLotJson.kt` 与 `core/Store.kt`：完整持久化 `TradeBook`，兼容旧持仓。
- `ui/Dialogs.kt` 与 `ui/PortfolioFragment.kt`：录入买入/卖出，并展示今日可卖、今日买入锁定和下一交易日可卖。
- `analysis/TradeNotification.kt` 与 `analysis/IntradayTactics.kt`：可卖股数封顶、同日锁定文案、安全的周期降级和失效条件。
- `core/IntradayBars.kt` 与 `core/SinaClient.kt`：15/30 分钟 K 线解析、完整性检查、实时柱覆盖更新和双周期决策。
- `monitor/MonitorService.kt`：缓存分钟窗口、普通动作执行周期门控、风险优先发送。
- `monitor/Notifier.kt`：独立高优先级风险卖出通知和震动。
- `ui/MonitoringCopy.kt` 与 `README.md`：T+1、15/30 分钟规则以及“板块状态参考/非买卖指令”边界。

## 三、验证

- 最终命令：`JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --offline`。
- 结果：`660` 项测试通过，`0` 失败、`0` 错误、`0` 跳过。
- 最终命令：`JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug --offline`。
- 结果：Debug 构建成功。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- SHA-256：`12cc31d140463ed959959500bee0d3fd4f7b917d13012058c6760fe9bed01aab`
- 实施计划 `docs/superpowers/plans/2026-10-20-intraday-risk-t1.md` 已无未完成步骤。

## 四、边界

- 验证覆盖策略单测、Kotlin 编译和 Debug 构建；未使用真实资金或券商成交回报验证，不构成收益或成交保证。
- 尚未进行完整真机盘中回归。上线观察时应重点核对同一时刻的“今日可卖”数量、风险提醒时间戳和 15/30 分钟数据失败时是否正确降级为观察。
- 项目当前不是 Git 仓库，也没有 OpenSpec 变更目录；按项目约定归档到 `docs/superpowers/archive/`，未创建 Git commit。
