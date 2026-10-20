# Stability And Strategy Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复外部扫描确认的稳定性、并发和交易信号口径问题，同时保持当前交易策略的核心边界不变。

**Architecture:** 以共享的交易时间、量比和趋势上下文为单一口径，避免各模块自行近似计算。后台状态使用快照/线程安全容器，提醒使用明确的 pending → cooldown → active 状态流转；前台服务先增加 Android 15 超时的安全收尾，不在没有平台决策前擅自切换服务类型。

**Tech Stack:** Kotlin、Android Service、Coroutines、JUnit、现有 `TrendEntryStrategy`、`VolumeRatio`、`TradingCalendar` 和 `MonitorService`。

**Spec:** `docs/superpowers/archive/2026-09-30-ui-polish-and-final-review.md` 与用户确认的“突破观察、回踩 M20 缩量止跌、市场过滤、止损和风险仓位”策略。

## Global Constraints

- 保持行情刷新约 15 秒一次，不改变数据源和持仓存储格式。
- 突破前高仍只作观察，不直接触发空仓买入；如增加通知，必须明确标记为观察。
- 回踩 M20 必须满足 M20 上行、缩量、接近 M20 和止跌条件。
- 跌破支撑/趋势失效优先于普通买卖点；普通信号继续双轮确认。
- 量比统一使用 `VolumeRatio.of()`，未知量比不得当作缩量或放量。
- 不在本次改动中引入新的网络依赖。

### Task 1: Time and foreground-service safety

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/TradingCalendar.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/BootReceiver.kt`
- Test: `app/src/test/java/com/example/stockmonitor/UiInteractionPolicyTest.kt` or a new calendar test

- [ ] 固定交易判断与展示时间使用 `Asia/Shanghai`，保留可注入 `Calendar` 供测试。
- [ ] 增加交易日判断入口，至少在行情日期没有更新时阻止量比和提醒重算；不硬编码不可维护的长期节假日表。
- [ ] 增加 Android 15 `dataSync` 超时回调的安全停止和日志/状态提示，避免系统超时后进程因未及时停止而崩溃。
- [ ] 开机启动失败时保留可诊断状态，不再完全静默吞掉异常。
- [ ] 为时间边界、周末/无新行情和超时回调增加测试或纯函数测试。

### Task 2: Normalize trend strategy data

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/TrendEntryStrategy.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/IndicatorScreener.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/StockAnalysis.kt` only if shared normalization needs it
- Test: `app/src/test/java/com/example/stockmonitor/TrendEntryStrategyTest.kt`

- [ ] 当历史最后一根已经是当日 K 线时，用实时数据更新该根，不再追加第二根同日 K 线。
- [ ] 当历史不含当日时，才追加合成当日 K 线。
- [ ] 趋势选股量比改用 `VolumeRatio.of()`，传入交易日已过分钟数；未知量比保持 null。
- [ ] 保持突破次日确认、回踩 M20 止跌确认、弱势禁开仓和 ATR 止损行为。
- [ ] 增加重复当日 K 线和盘中量比折算的回归测试。

### Task 3: Make background state and UI tasks cancellation-safe

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/Repo.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/ToolsFragment.kt`
- Test: `app/src/test/java/com/example/stockmonitor/BackgroundTaskStateTest.kt`

- [ ] 将行情、量比和价格位置缓存改为不可变快照替换或线程安全容器，保证 UI 读取和盘面总结读取不会遍历到半更新状态。
- [ ] 四个工具分析 busy 标志使用 `try/finally` 复位，并只在 View 仍存在时触发渲染。
- [ ] `volumeBaselineBusy` 改为原子状态，避免后台协程之间看不到复位。
- [ ] 增加切换页面/协程取消后的状态复位测试。

### Task 4: Correct alert semantics and input boundaries

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/IntradayTactics.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/SinaClient.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/StockAiToolExecutor.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/TradeCalculator.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/FiveOneThreeScreener.kt`
- Test: `app/src/test/java/com/example/stockmonitor/IntradayTacticsTest.kt`
- Test: `app/src/test/java/com/example/stockmonitor/TradeCalculatorTest.kt` if present, otherwise add focused coverage

- [ ] 冷却判断通过后才写入 active，避免冷却期内真实二次触发被永久吞掉。
- [ ] 为零持仓股票增加“突破观察”语义时不打开直接买入；回踩仍只走严格 M20 规则。
- [ ] 未知量比不触发缩量回踩信号。
- [ ] 修正 `920xxx` 和 ST 涨跌停幅度口径。
- [ ] 对外部数字解析统一拒绝 NaN/Infinity；修复 AI K 线最小长度边界。
- [ ] 交易成本计算对非有限输入直接拒绝，513 首个历史窗口量能基线不足时返回 false。

### Task 5: Verification and project record

- [ ] 运行 XML/resource 静态校验。
- [ ] 运行受影响的单元测试，再运行 `testDebugUnitTest` 和 `assembleDebug`（若环境权限允许）。
- [ ] 私下复核策略不变量、提醒状态机和时间单位。
- [ ] 将修复内容和无法执行的环境验证写入 `docs/superpowers/archive/`。

