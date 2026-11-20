# 选股 / 自选 / 持仓 / 分析报告：业务逻辑综合 Review

日期：2026-11-19（Asia/Shanghai）。范围：`app/src/main/java/com/example/stockmonitor` 现有实现。只做只读分析与建议，未改动交易规则。

## 四条主链路现状

| 业务 | 入口 | 盘中自动程度 | 出口 |
| --- | --- | --- | --- |
| 选股 | 指标快扫（前 200）/ 全市场深扫 | 快扫手动；深扫仅夜间或手动（`MonitorService.kt:626`、`IndicatorScreener.kt:26`） | 选股结果 → 自动写入观察池 |
| 涨停候补 | 涨跌停池每 20 tick（约 5 分钟） | 自动 | 持续观察 / 龙头候选 → 观察池 → 买点确认或首板观察 |
| 自选股 | `WatchlistBuyCandidatePool` 每 tick 同步 | 自动评估 | 仅“买点确认”类可推送非持仓股 |
| 持仓 | 30 分钟槽位 `PortfolioAnalysis` + 逐 tick 做 T/破位/封板/量价 | 自动 | 持仓类提醒（多数只对持仓推送） |
| 报告 | 16:00 复盘、16/18/21 盘面总结、手动个股报告 | 依赖服务存活 | 落盘缓存 + AI 工具读取 |

## 发现的问题

### P0：直接影响“更早发现、不漏报”

**1. 盘中没有“首板前”的自动发现通道（已落地，2026-11-19）**
- 现状：盘中唯一自动的全市场通道是涨跌停池（收盘价涨停后才入池）；指标快扫要手动点，深扫明确不在盘中跑（`MonitorService.kt:2794`）。
- 影响：海立股份 11-17 首板前的启动日、华东重机 11-12 都无法被自动发现；观察池天然只能覆盖“已经涨停过”的票。
- 建议：复用 `activeStocks(200)` + 自选/持仓的日线缓存，做 5–10 分钟一次的低频“20 日收盘新高 + 量比 + 乖离 ≤8%”扫描，命中只进观察池，不发买卖结论。原型已在 `LaunchDiscoveryPrototype` 验证过触发时点。
- 落地方案：`LaunchDiscoveryTracker`（站上 20 日收盘平台 + 连续 15 分钟确认 + 当日 ≥3% + 量比 ≥1.5 + MA5 乖离 ≤8%，封板不计时，跌破平台重置，每日一次）接入 `MonitorService.checkLaunchDiscovery()`，每 20 tick 扫 `activeStocks(200)`，命中以 `PRE_BREAKOUT` 入观察池并推送“启动突破观察”，只入池不产生买入结论，受每日 10 条预算限制。回放：600410 提前到 11-12 09:50 @12.92，002685 到 11-12 14:10 @5.11，600619 到 11-17 09:50。

**2. 涨停池降级时策略分类与文案错误（已落地，2026-11-19）**
- 现状：`LimitCandidatePolicy.eligible` 要求数据源必须是东方财富涨停池（`LimitCandidatePolicy.kt:12`）；东财失败退回新浪近似后，所有票都进 `addLeaderCandidates`，reason 固定写“今日接近涨停，等待持续性和承接确认”（`MonitorService.kt:3076`）。
- 影响：收盘已封板的票被标成“接近涨停”，`limitStreak`/封单质量可能缺失，首板观察链虽然还能匹配策略，但语义与数据都不可信。
- 建议：降级时单独标记 `source`，文案改为“涨停池降级，仅按近似数据观察”，并对 `limitStreak == null` 的票跳过首板观察。
- 落地方案：`addLeaderCandidates(..., degraded = source != EASTMONEY_LIMIT_POOL)`；降级票 source 写“涨停近似”，reason 明确“封单质量与连板数缺失，不参与首板承接判断”，`limitStreak / sealQuality*` 置 null，首板链自然跳过。

**3. 提醒预算先扣后发，预算不足当日丢提醒（已落地，2026-11-19）**
- 现状：`FirstBoardReclaimTracker.update` 先置 `emitted=true` 再返回（`FirstBoardReclaimTracker.kt:64`），服务随后才 `consumePushBudget`，失败直接 `return`（`MonitorService.kt:1726`）。
- 影响：同一交易日后续行情再满足也不会重发；文档已承认这一点。
- 建议：先预留预算再调用 `update`，或给跟踪器增加 `rearm(code)`，预算失败时回滚 `emitted`。
- 落地方案：`FirstBoardReclaimTracker` / `LaunchDiscoveryTracker` 均提供 `rearm(code)`；服务在 `consumePushBudget` 失败时回滚“已提醒”，下一个 tick 满足条件可重试，不再当日永久静默。

**4. 盘中状态只在内存，进程重启会重复或漏报（已落地主链路，2026-11-19）**
- 现状：`FirstBoardReclaimTracker.states`、封板/炸板、下探回升、反弹跟踪、`observationHistory` 都是内存态，重启即清零。
- 影响：上午提醒过的首板形态，重启后可能再提醒一次；5 分钟低点计时也会从重启时刻重算。
- 建议：至少持久化 `(交易日, code, low, lowAt, emitted)` 与当日已推送事件键，重启后恢复；或在 `Notifier` 层做“当日同类型同代码只发一次”的兜底。
- 落地方案：`Store.reserveAlertEventKey / releaseAlertEventKey` 提供“交易日:类型:代码”持久化账本，`sendCheckedAlert` 在投递前占用、失败回滚；首板承接、启动突破、下探回升、反弹跟踪、封板冲刺/状态均接入，重启后不会重复推送。计时器（low/lowAt）仍需重启后重新累计 5 分钟，属于已知的轻微延迟。

### P1：业务一致性与体验

**5. 自选股盘中几乎不推送（已落地，2026-11-19）**
- 现状：`StockAlertScope.shouldNotify` 只有“买点确认”对非持仓放行，封板冲刺/封板/炸板只推持仓（`StockAlertScope.kt:26-34`）。
- 影响：自选股“看得见、不提醒”，用户以为没信号；海立这类票若不是持仓或观察池，盘中完全静默。
- 建议：按提醒类型给自选股一个可配置开关（至少首板/关键价位类），并沿用统一预算。
- 落地方案：`Store.watchlistIntradayAlerts`（设置页“自选股 / 观察池盘中提醒”，默认关闭）；`StockAlertScope.shouldNotify` 增加 `watchlistEnabled`，封板冲刺、封板/炸板、反弹跟踪对非持仓放行，普通通知不震动、占统一预算并走当日去重。

**6. 量价异动/点火只覆盖持仓（已落地，2026-11-19）**
- 现状：`checkVolumePriceAnomalies` 只遍历 `store.portfolio.positions`（`MonitorService.kt:1942`）。
- 影响：同样的盘中放量突破，持仓会响、自选/观察池不响，形成策略不对称。
- 建议：把检测范围扩到自选 + 观察池，推送仍可按持仓/非持仓分级。
- 落地方案：`checkVolumePriceAnomalies` 范围改为持仓 + 自选 + 观察池；持仓沿用点火/异动分级，非持仓只在开启自选股提醒时推送“强”级别（普通通知），占用统一预算。

**7. 报告里的“买入观察/加仓”与实时买入确认标准不一致（已落地，2026-11-19）**
- 现状：`PortfolioAnalysis` 对持仓的“买入观察”走 `technical.entrySetup`（`PortfolioAnalysis.kt:298`），没有 MA5 乖离上限；非持仓买入确认有 8% 上限（`ObservationEntryPolicy.kt:46`）。
- 影响：同一只票可能“加仓可以、新买不行”，用户无法理解；也容易在高位加仓。
- 建议：把“追高边界”抽成共享策略，加仓与新买共用同一乖离/涨幅上限，差异只体现在仓位。
- 落地方案：`StrategyThresholds.MAX_CHASE_MA5_BIAS_PCT = 8` 作为唯一来源；`PortfolioAnalysis` 的“买入观察”增加 MA5 乖离上限，超限改为“等回踩 MA5”，与 `ObservationEntryPolicy` 新买口径一致。

**8. 盘面总结的整点窗口不补跑（已落地，2026-11-19）**
- 现状：`autoGenerateSummary` 只在 hour 恰好为 16/18/21 时执行（`MonitorService.kt:4910`）；复盘有 `hour >= 16` 的补偿。
- 影响：服务 17:xx、19:xx 才启动时，对应总结当天永久缺失。
- 建议：改成“当前时刻 ≥ 槽位且当日未生成”，与复盘口径一致。
- 落地方案：`autoGenerateSummary` 改为按 21→18→16 优先级检查“已到槽位且当日未生成”，服务晚启动时逐个补跑。

**9. 盘中支撑位没有新鲜度上限（已落地，2026-11-19）**
- 现状：做 T/破位判断用 `Repo.portfolioResults` 的 support/ATR，该结果按 30 分钟槽位刷新；板块、龙头数据都有 `*_MAX_AGE_MS` 检查，这里没有。
- 影响：新加仓或刚变盘的票，可能用半小时前的支撑位判断破位。
- 建议：给 level 加 `asOf` 与最大年龄；超龄时用实时价 + 缓存结构降级，或在提醒文案标注“支撑位来自 HH:mm”。
- 落地方案：`StrategyThresholds.LEVEL_MAX_AGE_MS = 35 分钟`；超龄时盘中补仓建议直接跳过，持仓/组合提醒正文追加“支撑/压力来自 HH:mm，已超过 35 分钟”。

**10. “破位提示”永远不会推送（已落地，2026-11-19）**
- 现状：`portfolioSignalLabels` 只含“买入确认/买入观察/减仓风险”（`StockAlertScope.kt:19`），而 `PortfolioAnalysis.kt:234` 会产出“破位提示”。
- 影响：一档缓释型破位信号在 UI 可见但从不通知，与“减仓风险”只差一档却体验断裂。
- 建议：确认是否漏配；若有意静默，在文档与 UI 明确。
- 落地方案：`BREAK_NOTICE`（`GuardedActionLevel.RISK_FACT`）不再被 ACTIONABLE 过滤；按“风险事实”发普通通知，不占机会类预算，每票每日一次。

**11. 观察时钟双轨，口径要写清（已落地，2026-11-19）**
- 现状：观察池淘汰仍按 `observedDays >= 10`（`ObservationPool.kt:16,328`）；首板承接窗口已改为按首板交易日 1–3 天。
- 影响：展示“观察第 N 日”和提醒窗口不是同一把尺子，复盘时容易误判。
- 建议：UI/报告统一展示两种口径，或把 `firstBoardDate` 正式落库，逐步替换 `observedDays`。
- 落地方案：观察池列表同时展示“已观察 N 天”和“首板后第 N 交易日”（`limitStreak == 1` 时），复盘时两种口径都在视野内。

### P2：工程与可维护性

**12. Store 每次读取都解析 JSON，解析失败静默为空（已落地，2026-11-19）**
- 现状：`watchlist`/`portfolio`/`tradeBook`/`observationPool` 的 getter 每次都从 SharedPreferences 解码（`Store.kt:26-33,171-180,296`）；15 秒 tick 内被多次访问。
- 风险：CPU/GC 浪费；解析异常返回空集合，上层“读空 → 改 → 写回”可能丢数据。
- 建议：Store 内加内存缓存 + 失效标记；解析失败记日志并拒绝写回。
- 落地方案：`Store` 增加解码缓存 + `OnSharedPreferenceChangeListener` 失效；watchlist / portfolio / 观察池 / 自选买点 / tradeBook 走缓存，写回时同步更新；解析失败记 `Log.w`（不静默）。

**13. 通知 ID 用 `code.hashCode()`（已落地，2026-11-19）**
- 现状：多处 `ALERT_ID_* + code.hashCode()`（如 `MonitorService.kt:1731`）。
- 风险：不同代码哈希碰撞会互相覆盖通知。
- 建议：改用“类型前缀 + 代码稳定序号”或 CRC32 截断。
- 落地方案：`NotificationIds.of(prefix, key) = prefix × 100000 + CRC32(key) % 100000`，MonitorService 内所有个股通知 ID 已替换。

**14. 阈值分散、语义相近（已落地，2026-11-19）**
- 8% 同时出现在买入确认（`ObservationEntryPolicy`）与 `BreakoutRiskPolicy.HIGH_BIAS_PCT`，后者是均线止盈线选择，语义不同；0.5% 在首板下探、箱体突破、风险线多处使用。
- 建议：集中到 `StrategyThresholds`，按语义命名并注释来源，避免改一处漏一处。
- 落地方案：新增 `analysis/StrategyThresholds.kt`；追高 8%、首板下探 0.5%、结构线容忍 0.5%、支撑位年龄在此统一，原常量改为别名引用，判断逻辑与数值不变。

**15. 编排层缺测试（已落地基础版，2026-11-19）**
- `MonitorService.kt` 5197 行，只有 2 个测试文件引用；tick 顺序、预算、去重、时段门控都没有自动化覆盖。
- 建议：把 tick 内各 `checkXxx` 的顺序与开关抽成可测的 `AlertOrchestrator`，至少覆盖“预算不足/行情过期/开关关闭”三条路径。
- 落地方案：新增 `AlertOrchestrator`（开关 → 行情新鲜度 → 当日去重 → 预算的纯函数决策），`sendCheckedAlert` 统一调用；`AlertOrchestratorTest` 覆盖全部五条路径。tick 整体顺序仍留在 MonitorService，后续可继续拆。

**16. Notifier 返回值被忽略（已落地，2026-11-19）**
- `Notifier.alert/signal` 返回 Boolean，调用点多未检查；通知被系统拦截或开关关闭时预算已扣。
- 建议：失败时 `refundPushBudget`，并在 `ObsPoolTrace` 记一条 `deliveryFailed`。
- 落地方案：`sendCheckedAlert` 在 `Notifier` 返回 false 时释放当日事件键、`refundPushBudget` 并记 `ObsPoolTrace.deliveryFailed`；`publishDailyAdvice` 原有回滚逻辑保持不变。

## 做得好的地方

- 策略与编排分离，绝大多数判断是可单测的纯函数。
- 观察池、选股结果、交易计划都有落盘与旧数据兼容处理。
- 风险门控层次清晰：硬风险、软风险、环境收紧、追高上限分开。
- 文档与代码同步意识强，今天的回放文档把“限制”写得很诚实。

## 建议落地顺序

1. 首板前低频发现（P0-1）：✅ 已落地（`LaunchDiscoveryTracker` + `checkLaunchDiscovery`），只入池发观察，不产生买入结论。
2. 提醒可靠性（P0-2、P0-3、P0-4）：✅ 已落地（降级语义、`rearm` 回滚、当日事件账本）。
3. 自选股提醒策略（P1-5、P1-6）：✅ 已落地（`watchlistIntradayAlerts` 开关 + 量价异动扩围）。
4. 报告一致性（P1-7、P1-8）：✅ 已落地（共享追高边界、总结窗口补偿式补跑）。
5. 工程债（P2-12～P2-16）：✅ 已落地（Store 缓存、CRC32 通知 ID、`StrategyThresholds`、`AlertOrchestrator` + 测试、投递失败回滚）。

剩余可继续深化的点：观察池 `observedDays` 仍是淘汰主时钟（P1-11 只做了双口径展示）；
首板承接的 low/lowAt 计时器仍为内存态，重启后需要重新累计 5 分钟（P0-4 的事件去重已覆盖重复推送）。
