# 选股 / 自选 / 持仓 / 报告：业务逻辑综合 Review（第二轮）

日期：2026-11-19（Asia/Shanghai）。
范围：`app/src/main/java/com/example/stockmonitor` 当前工作树（HEAD `987fde4` + 未提交改动）。
方法：静态阅读调用链 + 引擎自身约束对照 + `./gradlew :app:testDebugUnitTest`（1266 个用例，21 失败）。
本文件只做只读审查与建议，未修改任何业务代码。

> 第一轮结论见 [2026-11-19-business-logic-review.md](2026-11-19-business-logic-review.md)。
> 本轮定位：**验证第一轮“已落地”项是否真的闭环 + 找新引入的问题**。下文所有条目都核对过源码；低置信度会单独标注。

---

## 落地进度（2026-11-20）

已完成并通过全量单测（1326 个用例，0 失败）：

| 项 | 处理 |
| --- | --- |
| 1.1 测试基线 | 21 个失败逐条定性：24 处测试滞后已同步（做 T/站回支撑的分仓与量价证据、复盘新文案、板块门控移除、技术快照建议、UI 多行声明）；2 处真实口径已改代码（0 仓不进组合建议、塌陷期站回支撑降为待确认）。 |
| 1.2 观察池生命周期 | 新增 `ObservationPoolRetentionPolicy`（淘汰条目保留 5 天后清理）与 `LaunchDiscoveryCoveragePolicy`（淘汰代码不算已覆盖）；`addLaunchCandidate` 对淘汰条目走 `ObservationReentryPolicy.revive`；观察池所有写入统一走 `commitObservationPool`（同一把锁内读-改-写 + 清理），消除复评协程覆盖 tick 线程新增候选的竞态。 |
| 1.3 事件账本粒度 | `sendCheckedAlert` 增加 `eventKey`；下探回升按低点（`AlertEventKeys.dipRebound`）、反弹跟踪按事件类型（`AlertEventKeys.reboundTrack`）去重；`DipReboundEngine.Alert` 增加 `lowPct`。同日“企稳确认”不再吞掉“诱多/反弹失效”。 |
| 1.4 报告 completeness | `DailyReview.Inputs` / `PortfolioAnalysis.formatReport` 增加 `expectedPositionCount` + `analysisFailures`；分析失败时顶部与风险节明确写“未完成分析，不能据此认为没有风险”；全部失败时保留上一份复盘、任务标 failure。 |
| 2.3 启动发现缓存 | 新增独立 `launchHistory`（不参与观察池 `retainAll`）；配额只在成功取到可用历史后计数；缓存命中不再被配额挡住。 |
| 2.6 预算回滚 | `sendCheckedAlert` 的 `SKIP_STALE` 分支补 `refundPushBudget`。 |

新增回归测试：`ObservationPoolRetentionPolicyTest`、`LaunchDiscoveryCoveragePolicyTest`、`AlertEventKeysTest`、`DailyReviewTest.missingAnalysisIsDisclosedAndNeverReadsAsNoRisk`。

### 第二批（2026-11-20）

| 项 | 处理 |
| --- | --- |
| 3.1 账户假亏损 | `todayBrief` 用 `cashPosition().blockedReason` 判定：成本/总资金缺失时输出“暂无法计算（有 N 只持仓未填成本）”，不再把可用现金当 0。新增/编辑持仓也拒绝“数量 > 0 且成本 = 0”。 |
| 3.2 删监控留批次 | 重新加入监控时先 `TradeLotLedger.clearLots` 清掉旧批次（entries/adjustments 保留），避免 `lots.sum > 数量` 导致账本永久不一致、减仓/做T 通知被静默。 |
| 3.3 BOTTOM_TRIAL | 筑底轻仓试错纳入 `allocate`（25% 限额真正生效），而不是永远 0 股；测试改为断言 100 股。 |
| 2.4 轻仓缓释 | `PriorityInput.mitigated` 复用 `HoldingExitPolicy` 的 NOTICE 结论：低位/轻仓/无红灯的破位在组合优先级里降为 WATCH（“去留自定”），不再升级成减仓/退出。 |
| 2.5 分仓引导 | 持仓设置卡对未配置分仓的持仓显示提示：“盘中做 T 只提示观察、不计算股数”。 |
| 2.1/2.2 选股时间口径 | 新增 `ScreeningClock`：只有指数行情日期 == 今天且已过交易分钟才补当日 K 线；非盘中量比按 240 分钟全天口径。周末/盘前/午夜不再重复昨日 K 线、不再 8 倍量比，也不再清空趋势榜。 |
| 2.7a 高位承接 gate | `applyQuoteGate` 对 `POST_LIMIT_HIGH_HOLD` 同日买点免轮次复核，不再每轮 BUY→NEAR 抖动、买点永不推送；新交易日仍照常降档。 |
| 4.2 技术建议环境 | `unifiedAdviceFor` 接收 `marketRegime`，报告/卡片与持仓页/AI 同一口径；塌陷期不再写“可分批参与”。 |
| 4.3 复盘顶部塌陷 | 大盘塌陷且无 EXIT/REDUCE 时，顶部第一条改为“先降整体仓位”，不再说“维持原有仓位”。 |
| 4.4 下一季度方向 | `startsWith` 改为匹配完整句子里的方向，偏支持/偏承压不再恒显示“基本稳定”。 |
| 4.5 金融评分 | 金融口径评分按 68 分制折算后参与 SOUND/WEAK 判定；报告显示“68/68（折合 100 分制约 X）”，不再永远到不了“未见恶化”。 |
| 4.6 AI 持仓报告 | 正确解析“最近分析：”，返回 `generated_at/analysis_date/stale`；未分析时 `status=not_analyzed` 而不是 `no_priority_action`。 |
| 4.7 小项 | 盘面总结缺涨跌停数据时写“暂缺”而非“0 家”；21 点不再重复生成 18 点总结；周末不再每 15 秒失败重试。 |
| 持仓卡片口径 | “今日要点”与完整报告共用组合级风险门控，不再出现“卡片说追加、报告说暂不新增”。 |

新增回归测试：`ScreeningClockTest`、`FundamentalsReportDirectionTest`、`PortfolioPriorityPolicyTest.mitigatedLightLowPositionDoesNotEscalateToReduceOrExit`、`PortfolioSwingTest.bottomingTrialIsCappedAtQuarterInitialPosition`（改为断言 100 股）、`TradeLotsTest.clearingLotsAfterReAddKeepsLedgerConsistent`、`PortfolioAnalysisTest` 的账户口径/组合门控用例、`DailyReviewTest.collapseEnvironmentDoesNotSayMaintainPositions`、`MarketSummaryBriefTest.missingLimitDataIsReportedAsUnknownInsteadOfZero`、`FundamentalHealthPolicyTest` 金融折算用例、`ObservationEligibilityTest.sameDayHighHoldBuyCandidateIsNotBouncedByRoundGate`。

### 第三批（2026-11-20）

| 项 | 处理 |
| --- | --- |
| 同日建议 BLOCKED | `Store.reserveDailyAdvice` 不再把被拒的 BLOCKED 计划写盘；`DailyAdvicePolicy.prepare` 遇到旧版本持久化的 BLOCKED 会复位为 PREPARED/NOT_SENT。另一只买入计划完成后，该股当天可以重新提醒。 |
| 建议计划跨日膨胀 | 新增 `DailyAdvicePolicy.activePlans`，`Store.dailyAdvicePlans` 读取时只保留当天计划；避免一年后数千条计划反复全量解析。 |
| 首板节日窗口 | `FirstBoardReclaimPolicy.tradingDaysAfter/windowEligible` 支持传入指数日线的真实交易日序列，国庆/春节不再把假期工作日算成交易日；缺序列时才退回周末估算。`MonitorService` 在盘中用 `observationBenchmark()` 的日期，并发布到 `Repo.marketTradingDates` 供观察池 UI 使用同一口径。 |
| 节假日误采样 | `checkLaunchDiscovery` 增加“指数行情日期必须是今天”的门控，工作日休市日不再用上一交易日快照发“启动突破”并污染观察池。 |
| 盘面总结重试 | `autoGenerateSummary` 在指数行情不是今天时直接跳过，节假日不再每 15 秒失败重试。 |
| 持仓页时区 | “T+1 可卖”改用 `TimeUtils.todayText()`（Asia/Shanghai），设备在其他时区时不再把可卖数量算反。 |
| 跨日旧分析结果 | 盘中信号要求 `level.analysisDate == today`（新增 `IntradayLevelFreshness`），服务通宵存活时不再用昨天的支撑/MA20 判断今天的破位/加仓。 |
| 盘中机会预算回滚 | `checkIntradaySignals` 在 `publishDailyAdvice` 失败（同日计划互斥/复核/数量不足）时退还 `consumePushBudget`，不再让 8 条/日配额被每 15 秒的无效重试耗光。 |

新增回归测试：`DailyAdvicePolicyTest.blockedPlanIsRevivedWhenMutualExclusionClears`、`onlyTodaysPlansAreActive`；`FirstBoardReclaimPolicyTest` 的国庆窗口/基准缺今天两个用例；`IntradayLevelFreshnessTest`。

### 第四批（2026-11-20）

| 项 | 处理 |
| --- | --- |
| 首板 `firstBoardDate` | `ObservationCandidate` 落库 `firstBoardDate`（JSON 兼容旧数据）；新增 `FirstBoardClockPolicy`：同一策略行第二次首板时开启新周期（重置 `addedDate/observedDays/weakDays/确认轮次/风险信号`），同日多次刷新不重复重置；首板承接门控与观察池 UI 都改用该时钟。 |
| 513 来源时钟 | `addScreenCandidates` 用 `currentRoutes`（本轮真实命中）而不是合并后的 `mergedSources` 判断 513 命中：连续命中时 `sourceObservedDays` 重置为 0，不再每 3 个观察日被淘汰、晚上又复活。 |
| 涨停池截断 | 新增 `LimitCandidateSelection`：首板单独配额（最多 150）优先入选，再用剩余额度放其它涨停（总上限 200），主板首板不再被 20cm/30cm 挤出观察池。 |
| 首板分歧资金过滤 | 新增 `BuyPointFundFlowPolicy`：当日/近 5 分钟主力净流出且近 5/15 分钟无大单承接时，不推送“首板分歧转强观察”（仅保留观察池状态，资金转好后可再报）；现价低于当日成交均价且反弹缩量、无承接时同样不提醒；有承接时在提醒正文附资金证据。 |
| 买点已确认资金过滤 | 同一 `BuyPointFundFlowPolicy` 接入观察池/自选“买点已确认”投递：资金过滤不通过时不占用当日去重键和推送预算，保留待发状态，资金转好后仍可通知；尾盘候选复核同样过滤，并在正文附资金证据；顺带补上尾盘投递失败时去重键/预算的回滚。 |

新增回归测试：`FirstBoardClockPolicyTest`、`ObservationPoolJsonTest`、`LimitCandidateSelectionTest`、`BuyPointFundFlowPolicyTest`。

### 业务逻辑观察（首板链路）

- 原提醒只校验“下探开盘价 0.5% → 收回开盘价 → 低点稳定 5 分钟”，不区分是承接还是出货；新增的资金过滤把“主力净流出 + 无近端承接”挡在提醒之外。
- 资金数据缺失时不硬判出货：只有“价格低于当日均价 + 反弹缩量 + 无承接”才降级，避免因为接口失败漏报。
- 资金流按主动成交订单规模估算，不代表机构身份；提醒正文会带上当日/近 5/15 分钟净流向，便于复盘。

### 第五批（2026-11-20，P2 清理）

| 项 | 处理 |
| --- | --- |
| 分仓配置损坏覆盖 | `PositionAllocationJson.decode` 逐条容错：单条模式/目标无效只跳过该条，其余配置保留，下一次保存不再用单条记录覆盖全部。 |
| 观察池 JSON 单条损坏 | `ObservationPoolJson.decode` 改为逐条 `optJSONObject` + `runCatching`：一条坏数据只跳过一条，其余观察记录保留（整表 JSON 损坏仍无法恢复）。 |
| 基本面红灯加仓 | `canApproveAddition` 显式拒绝 `fundamentalAlert`，不再因为 order=3 > 2 拿到追加资格。 |
| 成本未知时买入 | `buyToPortfolio` 对原成本 ≤0 的持仓不再把 0 参与加权（1000@0 + 100@12 不再摊成 1.09），改用本次成交价作为唯一可核实价格。 |
| 营收下滑 + 存货上升 | 财务质量不再用负数营收增速做“2 倍”比较；营收负增长且存货上升单独告警。 |
| 长报告基本面缺口 | 没有健康快照时明确写“未分析或已过期，不作为无风险证据”，不再静默省略。 |
| 通知 ID 溢出 | `NotificationIds.of` 用 Long 计算并拒绝超出 Int 的前缀，避免未来前缀变大时静默溢出撞 ID。 |
| 盘中手动总结 | 交易时段生成盘面总结时标注“盘中快照，量价未完成”，不按全天口径解读。 |
| 停牌股拖累整组 | `PortfolioSwing.capitalIssue` 只在“完全没有该股证据”时阻断整组；停牌股用最后收盘价估算组合，仅阻断该股自身追加。 |
| 严格补仓现金复核 | `IntradayAdditionPolicy` 按最高接受价 + 可用现金重算数量；投递前再核一次可用现金（含费用预留），防止计划生成后现金被用掉。 |

新增回归测试：`PositionAllocationJsonTest`、`ObservationPoolJsonTest.oneCorruptEntryDoesNotWipeTheWholePool`、`PortfolioPriorityPolicyTest.fundamentalAlertNeverApprovesAddition`、`TradeLotsTest.buyingIntoUnknownCostPositionUsesTradePriceInsteadOfDilutingToZero`、`FinancialQualityPolicyTest.revenueDeclineWithInventoryBuildIsFlaggedWithoutTheMeaninglessDoubleRule`、`PortfolioAnalysisTest.fullReportDisclosesMissingFundamentalEvidence`、`NotificationIdsTest`、`MarketSummaryBriefTest.intradayManualSummaryIsLabeledAsSnapshot`、`PortfolioSwingTest.suspendedHoldingDoesNotBlockAdditionsToOtherHoldings`、`IntradayAdditionPolicyTest.quantityIsRecomputedAtUpperBoundFromAvailableCash` / `insufficientCashAtUpperBoundBlocksInsteadOfSuggestingUnaffordableShares`。

### 第六批（2026-11-20，提醒完整性 / AI）

| 项 | 处理 |
| --- | --- |
| 风险升级重发 | `DailyAdvicePlan` 增加 `riskStage`（JSON 兼容旧数据）；`DailyAdvicePolicy.prepare` 在风险证据从第 2 根升到第 3 根时，保留同日计划与数量反馈，把通知状态复位为 `PREPARED/NOT_SENT` 并附“风险升级”说明；30 分钟链路传 0 不再把已升级档位降回去。第 3 根确认后可以再提醒一次。 |
| 突破下杀风险 | `ObservationBreakoutRiskAlert` 不再是死代码：观察池与自选买点候选在 `breakoutRisk` 从无到有时推送一次“突破延续下杀风险”，走当日事件账本、不占机会预算，同股同日一次。 |
| 启动突破资金过滤 | `checkLaunchDiscovery` 复用 `BuyPointFundFlowPolicy`：主力净流出且近 5/15 分钟无大单承接时不推送、不入池，`rearm` 等资金转好后再评估；提醒正文附资金证据。 |
| AI 行情日期/单位 | `get_realtime_quote` 增加 `quote_date`、`prev_close`、`volume_shares`、`turnover_yuan` 与单位说明；工具描述明确“周末/节假日可能是上一交易日快照”。 |
| AI 报告清洗 | 所有 AI 报告工具返回前统一 `ReportMarkup.plain()` 去掉 `«»` 标记，并限制 8000 字符附截断提示，避免模型抄标记/撑爆上下文。 |

新增回归测试：`DailyAdvicePolicyTest.riskStageEscalationReopensSentPlanWithoutLosingQuantityFeedback`、`DailyAdviceJsonTest` 的 `risk_stage` roundtrip。

未处理（下一步）：科创板/北交所卖出整手（需要把股票代码贯穿 `TradeActionGuard`）、AI 选股结果/观察池只读工具、观察池整池重写性能、盘面总结板块/概念数据新鲜度。

---

## 0. 结论摘要

> 以下是审查时点的原始结论；各项落地状态以顶部“落地进度”为准。

第一轮 16 项大部分确实落到了代码，但**今天的未提交改动**（统一事件账本、启动发现、自选股提醒扩围）引入了 3 个新的可靠性问题；当前工作树的**测试基线是红的**（21 个失败），失败集中在 `987fde4` 触及的区域，而相关源文件不在本轮未提交改动里；其中至少 2 个失败指向真实业务口径，而不是测试滞后。

最该先处理的十件事：

1. **测试基线 21 / 1266 失败**，先逐条定性再谈其他。
2. **个股分析失败/全部缺行情时，复盘写成“没有已确认的持仓风险”，并覆盖上一份好报告**（上市不足 60 日的新股必然触发）。
3. **观察池只增不删**：淘汰记录永远留在池里，导致“启动突破观察”对任何曾经入过池的股票**永久失效**，池子/JSON/UI 也会持续膨胀。
4. **当日事件账本把反弹类多次提醒压成每日一次**：`dipRebound` 引擎允许 3 次/日、`reboundTrack` 允许 CONFIRM/CAUTION/TRAP/PRESSURE 多次，现在第 2 次起被静默丢弃，包括“疑似诱多/反弹失效”这种风险提醒。
5. **启动发现的日线缓存被观察池复评驱逐**：同一批前 6 只票每 5 分钟重拉，60 次/天配额约 50 分钟耗尽，下午不再发现；且预算失败后 `rearm` 实际无效。
6. **非交易时段的选股扫描会用上一交易日快照补成“今日”K 线、并按摩钟折算量比**：周末/盘前/午夜深扫会产出错误选股结果，并覆盖上一份有效结果。
7. **组合优先级 / 复盘顶部建议 / 今日要点没有排除 0 仓，也没接入轻仓缓释**：会对未持仓或轻仓股给出“减仓评估”，与“风险动作比例原则”冲突（有失败测试佐证）。
8. **盘中机会信号预算扣了不退还**：`publishDailyAdvice` 失败时 8 条/日预算可能在 2 分钟内被无效重试耗光。
9. **技术报告的“操作建议”忽略大盘塌陷门控**：同一只票，工具页说“可分批参与”，持仓页/AI 说“暂缓补仓”。
10. **未配置分仓时普通做 T 全部静默**：这是新规则且有测试锁定，但老用户不主动“设置分仓”就会失去做 T 提醒——需确认产品意图并补 UI 引导。

---

## 1. P0

### 1.1 测试基线红（21 / 1266 失败）

```bash
./gradlew :app:testDebugUnitTest
# 1266 tests completed, 21 failed
```

| 测试类 | 失败数 | 代表断言 |
| --- | --- | --- |
| `DailyReviewTest` | 8 | `assertEquals(-1, iWait)` 实际 199：0 仓观察股进入了“持仓整体操作建议” |
| `HoldingTPolicyTest` | 5 | 期望 `T_SELL`，实际 `T_SELL_DEFERRED` |
| `IntradayTacticsTest` | 5 | 期望 `RECLAIM_SUPPORT`，实际 `RECLAIM_WATCH` |
| `PromotionEnvironmentPolicyTest` | 1 | 期望“所属板块同步转弱”，实际 `null` |
| `TechnicalTrendSnapshotPolicyTest` | 1 | `primaryAdvice` 文案断言 |
| `ExpandableQuoteRowUiTest` | 1 | 行展开布局断言 |

逐条读过源码/测试后的定性：

- **多数是测试滞后**：`987fde4` 有意加了“未配置分仓不做普通 T”“站回支撑需要量价证据”“板块标签不再单独阻断晋级”“复盘文案重构”，测试没同步。
- **至少 2 个是真实口径问题**：`DailyReviewTest.watchOnlyStocksAreExcludedFromAdviceAndPnlAttribution`（见 2.4）与 `HoldingTPolicyTest` 系列反映的“未配置分仓 → 做 T 全静默”（见 2.5）。
- 红基线本身会让后续所有“已修复”的说法失去验证依据，不能再留。

### 1.2 观察池只增不删：淘汰记录永久保留，启动发现被永久拉黑

**证据**

`MonitorService.kt:1117-1121`

```kotlin
val knownCodes =
    (store.observationPool.map { it.code } + store.watchlistBuyCandidates.map { it.code }).toSet()
universe.asSequence().filter { it.code !in knownCodes && ... }
```

- `knownCodes` 没有按 `stage` 过滤；
- 观察池所有写入点（`1231-1238`、`3382-3388`、`3441-3448`、`3495-3502`、`3673-3674`）都只是 `existing.values` / `latest.map`，**从不删除 `ELIMINATED` 条目**；
- `addLaunchCandidate` 对已存在 key 直接 `return`（`1205-1230`）。

**失败场景**：某票 11-10 被 513/指标扫入池，11-20 因“10 个交易日未确认”淘汰（记录仍留池）；12-05 放量突破 20 日平台并站稳 15 分钟——`activeStocks` 命中，但 `knownCodes` 含该 code，直接过滤，`launchDiscoveryTracker.update` 永不调用：既不推送“启动突破观察”，也不入池。随着使用时间增长，活跃 200 只里“历史上出现过”的比例越来越高，这条新通道会逐步退化为零覆盖。

**建议**：`knownCodes` 只排除 `stage != ELIMINATED`（保留“淘汰当日不复活、次日可重进”的既有语义），并给观察池加清理策略（淘汰后保留 N 个交易日 / 按 `lastEvaluatedDate` 清理 / 总量上限）。

### 1.3 当日事件账本把合法的多次提醒压成每日一次

`sendCheckedAlert` 的去重键是 `"$type:$code"`：

`MonitorService.kt:2081`

```kotlin
val key = "$type:$code"
val reserved = store.reserveAlertEventKey("$today:$key", today)
```

但两个调用方本身允许同一代码同日多次：

- **下探回升**：`DipReboundEngine` 明确 `MAX_PER_DAY = 3`，创更深新低会重新武装（`emittedLowPct`）。`DipReboundEngine.kt:46,150-154`；调用方只传 `type = "dipRebound"`（`MonitorService.kt:1776`）。
- **反弹跟踪**：`ReboundTracker` 分别允许 `CONFIRM 2 / CAUTION 2 / TRAP 3 / PRESSURE 2`，且“企稳确认”和后面的“诱多/失效”是相反事件。`ReboundTracker.kt:47-50`；调用方只传 `type = "reboundTrack"`（`MonitorService.kt:1843`）。

**失败场景**：10:00 持仓股急跌后 V 型收复，推送“反弹企稳确认”；11:00 再创更深的坑又收复，引擎允许第 2 次，但键 `2026-11-19:dipRebound:sh600000` 已存在 → `SKIP_DUPLICATE`。更严重的是：上午的“企稳确认”会永久吞掉下午的“疑似诱多/反弹失效”，而后者是风险提醒。

对比：改动前这两条链路只走 `consumePushBudget`（每码每天 8 条），没有一次性键——这是今天未提交改动引入的回退。

**建议**：给 `sendCheckedAlert` 增加显式 `eventKey`，或把事件身份并入 `type`：

- 下探回升：`"dipRebound@${lowPct}"`（同一坑只发一次，更深新低是新事件）；
- 反弹跟踪：`"reboundTrack-${event.kind.name}"`，按 kind 保留各自日限。

### 1.4 P0：报告 completeness（详见 4.1）

个股分析失败/全部缺行情时，复盘与持仓长报告写成“没有已确认的持仓风险”，并覆盖上一份好报告。详见 4.1。

---

## 2. P1

### 2.1 选股：非交易时段补“今日”K 线 + 墙钟折算量比 → 错误选股并覆盖旧结果

**证据**

`IndicatorScreener.kt:156-168`

```kotlin
val today = TimeUtils.todayText()
if (history.lastOrNull()?.date == today) return history
if (stock.price <= 0) return history
return history + DayBar(date = today, open = ..., close = stock.price, volume = stock.volume)
```

`withTodayBar` 只比较“日线最后一根是不是今天”，没有校验这份 `ActiveStock` 快照本身是不是今天的行情；`ActiveStock`（`Models.kt:35-44`）根本没有行情日期字段。而 `VolumeRatio.kt:33-35` 的注释已经承认：隔夜到集合竞价结束，实时成交量要么是上一交易日的全天量，要么只是一小部分。

`scan` 同时用墙钟算量比折算：

`IndicatorScreener.kt:69` / `VolumeRatio.kt:33-47`

```kotlin
val elapsedTradingMinutes = TradingCalendar.tradingMinutesElapsed()
```

`tradingMinutesElapsed` 不判星期/节假日（`TradingCalendar.kt:106-115`）。

**失败场景**

- **周六 10:00 手动“重扫指标”**：活跃股榜单返回周五行（`SinaClient.kt:275-300` 只要求 volume>0）。日线最后一根是周五 → 追加一根“周六”K 线，值等于周五；`tradingMinutesElapsed()=30` → 量比 ≈ 周五全天量 ÷ (30/240) ÷ 5 日均量 ≈ 8 倍。严格档被重复日压掉、宽松档 MACD 判据大面积失效。
- **工作日 08:00 手动快扫**：`elapsed=0`，追加的“今日”K 线是昨日重复，`VolumeRatio.of` 返回 null；`mergeScreenResult` 仍把 `screenIndicatorAt` 写成 08:xx 并清掉上一晚的宽松档。
- **00:00–06:00 夜间深扫**（周六凌晨也在此窗口）：同样生成重复 K 线。深扫路径还会进一步清空趋势列表——见 2.2。

**建议**：给 `ActiveStock` 增加行情日期（新浪 hq 字段 30 已有日期），只有“快照属于当前交易日”才补当日 bar；非盘中把量比按全天口径（240）或原始日线口径，不要用墙钟；非交易时段扫描不要写成“今日刷新”。

### 2.2 选股：盘前/午夜深扫 `elapsed=0`，回踩型趋势全灭且仍记成功

**证据**

`TrendEntryStrategy.kt:164,183-184,303-306` + `VolumeRatio.kt:38-39`

```kotlin
val currentVolumeRatio = volumeRatioAt(bars, latest, elapsedTradingMinutes)  // elapsed=0 → null
val contracted = currentVolumeRatio != null && currentVolumeRatio <= PULLBACK_VOLUME_RATIO
val pullback = !trendWeak && !failedBreakout && ma20Rising && nearMa20 && contracted
```

`M20_PULLBACK` 是唯一稳定可买的类型（`TrendEntryStrategy.kt:200-207`，`BREAKOUT` 还受 `BREAKOUT_CAN_BUY` 限制）。盘前/午夜深扫 → 无回踩、无突破确认 → 趋势列表基本为空。但 `mergeScreenResult` 对 `includePatterns=true` 直接以 `current.trend` 覆盖（`MonitorService.kt:3216`），随后 `indicatorScheduler.recordSuccess` 仍记成功（`:3173`）。

**失败场景**：服务在 00:30 启动（夜间窗口内）→ 自动深扫 → 趋势榜被清空、界面显示“本窗口已成功刷新”；当晚 18:00 的有效结果被覆盖。

**建议**：深扫时区分“最后一根是已收盘完整交易日”与“当日盘中”：前者传 240 或 null，只有盘中才传 `tradingMinutesElapsed()`；非盘中不允许执行会覆盖缓存的全量扫描，或至少不记 `recordSuccess`。

### 2.3 启动发现：缓存被驱逐 + 预算/重试失效

**证据**

`MonitorService.kt:1135-1144` 用共享 `observationHistory` 缓存，只有未命中才消耗预算：

```kotlin
val history = observationHistory[stock.code]?.takeIf { it.first == today }?.second
    ?: run {
        fetchedThisScan++
        launchFetchCount++
        runCatching { sina.kline(stock.code, 520) } ...
            ?.also { observationHistory[stock.code] = today to it }
```

`refreshObservationPool` 每分钟清掉非池内代码：

`MonitorService.kt:3560-3564`

```kotlin
observationHistory.keys.retainAll(activeCodes + watchlistCodes)
```

启动发现的候选恰恰是**尚未入池**的代码，永远不在 `activeCodes` 里，缓存最多存活约 1 分钟；下次 5 分钟扫描必然重拉并再次计数。

**后果**：`LAUNCH_MAX_FETCH_PER_SCAN=6`、`LAUNCH_MAX_FETCH_PER_DAY=60`（`:5511-5512`）→ 每 5 分钟重抓同样前 6 只，约 50 分钟配额耗尽；此后 `forEach` 对每只直接 `return@forEach`，当天不再评估任何新股票。原设计“每天补 60 只、覆盖榜单更后面”变成“同一批前 6 只反复抓 10 次”。

**附带**：先 `addLaunchCandidate` 入池、再 `sendCheckedAlert`，失败才 `rearm`（`:1167-1181`）。一旦投递失败（预算耗尽/通知被拦截），该票已进 `knownCodes`，下一轮被过滤，`rearm` 没有机会重放。对比首板承接链没有这层过滤，所以“rearm 可重试”只对首板链成立。

**建议**：独立日级缓存（不参与 `retainAll`），或把 launch 代码加入保留集合；预算按去重代码数计；投递成功后再入池，或维护“已发现未投递”集合重试。

### 2.4 组合优先级 / 复盘 / 今日要点没有排除 0 仓，也没有接入轻仓缓释

**证据**

- `PortfolioPriorityPolicy.rank` 过滤了 `quantity > 0`（`PortfolioPriorityPolicy.kt:78`），但 `evaluate` **没有过滤**（`:41-70`）。
- `DailyReview.reviewOverallAdvice` 直接对 `results` 调 `priorityDecision`，再按状态过滤（`DailyReview.kt:234-237`）。
- `PortfolioFragment` 对“未持仓观察”行也显示 `priorityDecision(result).reason`（`PortfolioFragment.kt:204-207`）。

**失败场景**：用户把一只不再持有的股票留在持仓页观察（`quantity = 0`）。若它跌破支撑，`priorityDecision` 返回 `WATCH`/`REDUCE` → 复盘顶部出现“优先处理：『某股』减仓评估”，持仓卡片写“评估减少风险敞口”。**用户根本没有仓位可减。**

第二层：`HoldingExitPolicy` 已把“低位 + 轻仓 + 基本面未亮红灯”的破位缓释为 `NOTICE`（“去留自定，不强制卖出”），但 `PriorityInput` 没有 `positionPct`，`priorityRiskConfirmed` 只来自 15 分钟风险确认（`MonitorService.kt:4710-4715`），所以顶部建议仍升级为 `REDUCE`。**单股说“去留自定”，顶部说“减仓评估”**，正好违反“低位/轻仓只提示不命令卖出”的比例原则。

**建议**：

1. `PortfolioPriorityPolicy.evaluate` 对 `quantity <= 0` 直接返回 `HOLD`（reason 明确“未持仓，仅观察”）；
2. `PriorityInput` 增加 `positionPct` / `mitigated`，与 `HoldingExitPolicy` 共用同一缓释结论（或由 `PositionResult` 直接带出 `exitStage`，优先级层不再自行推断）；
3. 补一条与失败测试同名的回归测试。

### 2.5 未配置分仓时，普通做 T 提醒整体静默（需确认意图）

`IntradayTactics.kt:998-1007`

```kotlin
val allocation = input.positionAllocation
    ?: return TQuantityAdvice(0.0, "尚未配置底仓与滚动仓，暂缓普通做T", false)
```

生产端传 `store.positionAllocations[position.code]`（`MonitorService.kt:2530`），未配置时为 `null`；系统建议只展示、不落库（`Store.kt:78-90`）。

**后果**：老用户升级后，只要没主动“设置分仓”，所有普通做 T 的可执行提醒都会变成 `T_SELL_DEFERRED`（仅观察）。`HoldingTPolicyTest` 的 5 个失败正是这个行为的反映。这不是边角变化，是核心提醒的整体静默。

**建议**：明确二选一——有意为之则在持仓页/首启强提示“未配置分仓，做 T 不给股数”，并提供一键采用系统建议；否则保留旧行为（未配置按滚动仓估算，算不出才降级观察）。

### 2.6 盘中机会信号：预算先扣，投递失败不退还

`MonitorService.kt:2707-2769`

```kotlin
if (riskSignal || (store.intradaySignalEnabled && consumePushBudget(position.code))) {
    ...
    val posted = publishDailyAdvice(...)
    if (posted) intradaySignalState.commit(...)
}
```

`publishDailyAdvice` 有 7 条 `return false` 路径（同日计划互斥、`quantity < 100`、`REVIEW_REQUIRED`、预约失败、通知失败等，`MonitorService.kt:5065-5105`）；`intradaySignalState.canEmit` 是纯读（`IntradaySignalState.kt:50-62`），失败后不提交，下一 tick 仍可发。

**失败场景**：上午已有 SELL 计划，下午出现反向 BUY → `prepareDailyAdvice` 进入复核态 → 每 15 秒重试并扣预算 → **8 条/日预算在 2 分钟内耗尽**，当天该股所有机会类提醒被预算挡掉。这是第一轮 P0-3“预算先扣后发”在另一条链路上的未闭环。`notifyTailObservationCandidates`（`:3921-3922`）也是“先扣预算再预约去重键”，预约失败同样漏退。

**建议**：`posted == false` 时 `refundPushBudget`；或把扣预算移进成功回调。

### 2.7 自选 / 观察池：几个独立但明确的逻辑缺口

**a) 炸板高位承接买点会被同一轮 gate 立刻打回 NEAR_ENTRY**

`ObservationPool` 对 `independentHighHoldEntry` 不要求确认轮次（`entryConfirmationRequiredRounds=0`），但 `ObservationEligibility.applyQuoteGate` 重新用 `requiredConfirmationRounds(candidate)` 推出 2/3，并因 `entryConfirmationRounds(0) < required` 把 `BUY_CANDIDATE` 打回 `NEAR_ENTRY`（`ObservationEligibility.kt:112-131`）。`LEADER/LIMIT_UP_CONTINUATION` 且 `dailyBaseEntryConfirmed=false`，所以这条路实际不可达；`ObservationBuyAlert` 专门为它写了 `POST_LIMIT_HIGH_HOLD` 分支（`ObservationBuyAlert.kt:25-28`），成了死分支。每轮还会产生 `NEAR→BUY→NEAR` 两条状态变化并落盘。

**建议**：gate 优先使用候选自己记录的 `entryConfirmationRequiredRounds`，或给独立承接加显式标记；补 LEADER + `postLimitHighHoldCandidate` 的 gate 单测。

**b) 首板承接时钟用 `addedDate` 当首板日，但第二次首板不更新 `addedDate`**

`addLeaderCandidates` / `addLimitContinuationCandidates` 对已存在候选只 `previous.copy(limitStreak=...)`（`MonitorService.kt:3427-3439`、`3479-3493`），不更新 `addedDate`；而门控与 UI 都假设“入池日即首板日”（`FirstBoardReclaimPolicy.kt:9-10,36-40`、`ScreenFragment.kt:757-763`）。

**失败场景**：11-10 首板入池；11-18 再次首板（新连板=1），`addedDate` 仍是 11-10。11-19 本应是“首板后第 1 交易日”，实际算出 7 → 1–3 日窗口全部不成立；UI 同时显示“首板后第 7 交易日”。

**建议**：正式落库 `firstBoardDate`；在 `limitStreak` 从“非 1/缺失”变为 1 时重置。这是第一轮 P1-11“双时钟”只做了展示、没解决时钟源的部分。

**c) 513 来源时钟在“再次命中”时从不重置 → 每 3 个观察日淘汰一次、晚上又复活**

`MonitorService.kt:3343-3349`：`currentPattern` 来自 `mergedSources`（包含上一轮来源），所以真实再次命中时 `currentPattern && previousPattern` 仍为真，`patternObservedDays` 沿用旧值（3），紧接着被 `isSourceExpired` 淘汰；晚间深扫 `revive` 后 `sourceObservedDays` 仍是 3，次日再次淘汰。

**建议**：本轮扫描真实命中 513 时把 `sourceObservedDays` 重置为 0；只有“快扫保留了上次 513 来源”才沿用旧年龄。

**d) 观察池“读→整表覆盖写”与 tick 线程入池并发**

`launchObservationPoolRefresh` 在协程里读 `store.observationPool`（`:3612`），拉完 520 根日线后整表写回（`:3673`）；期间 tick 线程的 `refreshLimits()`（每 5 分钟）或手动选股协程可能写入新候选。`observationRefreshBusy` 只互斥复评自身，覆盖不到入池函数，后写者会丢掉先写者新增/更新的条目。

**建议**：统一用 `Mutex`/`synchronized` 保护 observationPool 的读改写，或按 `strategy:code` 做 upsert，并只在有变化时写。

**e) 涨停池按 `pct` 降序截断前 100，强市/降级日会把主板 10cm 首板整体挤掉**

`MonitorService.kt:3403`：`limitStocks.sortedByDescending { it.pct }.take(MAX_LEADER_CANDIDATES)`。`LimitCandidatePolicy.eligible` 之外的所有涨停（弱封单、降级源）都进这里；20cm/30cm 天然排在 10cm 前。涨停数 >100 时，主板首板可能完全进不了池，也就没有后续“首板承接”提醒。

**建议**：不按 pct 截断；首板（`limitStreak==1`）单独配额或优先于连板；降级票另行处理。

### 2.8 节假日：首板窗口算穿 + 启动发现可能用旧快照发真通知

- `FirstBoardReclaimPolicy.tradingDaysAfter` 只跳过周六日（`:20-34`，注释自认“法定节假日暂不建模”）。9-30 首板、10-8 复市 → 中间 5 个工作日被算成交易日 → 窗口直接过期，节后 3 天漏报。
- `TradingCalendar.isMarketOpen` 只判周末（`:83-91`）；`checkLaunchDiscovery` 只判 `inMarketHoursForAlert`，**不校验行情日期**（`MonitorService.kt:1099-1101`），`ActiveStock` 无日期字段。工作日休市日会用上一交易日快照采样，15 分钟后触发“启动突破观察”并写入 `addedDate=节假日`。

**建议**：以“指数当日 `quoteDate == today`”作为“今天是交易日”的唯一事实源（同库 `HoldingAnalyzer.kt:322-328` 已有做法），非交易日直接跳过；首板窗口改用真实的交易日表。

### 2.9 默认自选股在“清空自选”后复活

`Store.parseWatchlist`：`raw.isNullOrBlank()` 或解析后 `list.isEmpty()` 都返回 `defaultWatchlist()`（`Store.kt:635-650,790-797`），默认包含 中天/杉杉 两只个股。

`WatchlistManagement.remove` 允许删到空（`WatchlistManagement.kt:9`）。删掉最后一只后写 `"[]"`，下一次读取（甚至同一进程内 listener 失效后）又返回默认 5 只；其中两只个股会进入 `WatchlistBuyCandidatePool.sync` 被评估，可能推送“买点已确认”。

**建议**：用 `raw == null` 区分“从未初始化”与“显式空”；空数组返回空列表。

---

## 3. 持仓链路

风险卖出侧最终数量都过 `TradeActionGuard`（可卖量封顶、底仓保护、T+1、账本一致性），清仓只走“重大风险待复核”且不生成可卖股数，**未发现能把禁止卖出的股份放行的路径**。下面 3 条会让用户把“系统算不出来”误读成“系统结论”，按 P1 处理。

### 3.1 持仓成本缺失时，账户总盈亏把可用现金按 0 计算，给出巨额假亏损

**证据**

- `PortfolioAnalysis.kt:716-744`：`availableCapital = portfolio.resolvedAvailableCapital()`；`accountLine` 条件只有 `totalCapital > 0 && availableCapital >= 0 && complete`；随后 `totalAssets = marketValue + availableCapital; pnl = totalAssets - totalCapital`。
- `Models.kt:172-200`：任一只 `cost <= 0` 时 `blockedReason = "有 N 只持仓未填成本，算不出可用现金"`，`autoValue = null`，`value` 退化为 0（无手动校正时）。
- `Dialogs.kt:794-806`：新增/编辑持仓只校验 `quantity/cost >= 0`，允许“数量 > 0、成本 = 0”。

**失败场景**：总资金 100000 元，持仓 1000 股、成本填 0，现价 10 元 → 可用现金按 0 计 → “账户总盈亏：-90,000.00 元（-90.00%）”。真实含义是“成本未填、无法计算”。这是持仓页“今日要点”的头号数字（`PortfolioBriefCard.kt:27-37`）。

**建议**：账户行同时要求 `portfolio.cashPosition().blockedReason == null`，缺失时直接输出 `blockedReason`，绝不把 0 当现金；新增持仓时对“数量 > 0 且成本 = 0”给出明确确认或拒绝。

### 3.2 删除监控股票不清批次，重新加入后账本永久不一致，减仓/高位减仓/做 T 通知被静默停掉

**证据**

- `Dialogs.kt:848-873`：`deletePosition` 只改 `portfolio.positions`，完全不碰 `store.tradeBook`。
- `Dialogs.kt:814-818`：重新加入时 `syncPositionEdit(..., oldQuantity = 0.0, ...)` 恒走“追加批次”分支（`TradeLots.kt:153-158`）。
- `TradeLots.kt:235-246`：`ledgerConsistent = abs(position.quantity - lots.sum) <= EPSILON`；残股导致 `lots.sum > position.quantity` 时 `actionableSellableToday = 0`。
- `TradeActionGuard.kt:73-75`：`!ledgerConsistent` 一律 `observe(...)`；`MonitorService.kt:2654`（盘中）、`:5204`（30 分钟组合）对非 `ACTIONABLE` 直接跳过，不发送通知。

**失败场景**：持有 1000 股（批次 1000）→ 用“删除股票”停止监控（批次保留）→ 数周后重新加入 500 股 → `lots.sum = 1500 > 500` → 该股 `ledgerConsistent=false`。此后 REDUCE、HIGH_REDUCE、EXIT_WINDOW、T_SELL 全部只得到 OBSERVATION、不推送；只有 EXIT（重大风险待复核）和 BREAK_NOTICE 仍会送达，用户会误以为“只有一部分风险在监控”。

**建议**：删除监控时删除该代码的 `lots`（保留 `entries` 以保历史），或重新加入时先归零该代码批次再按新数量重建，并在 UI 提供可操作的“重建批次”入口。

### 3.3 “筑底轻仓试错”（BOTTOM_TRIAL）永远分配 0 股

**证据**

- `PortfolioSwing.kt:351`：筑底时 `status = BOTTOM_TRIAL`；`:210` 只有 `status == READY` 才进入 `allocate`；`BOTTOM_TRIAL` 原样返回，`addQuantity = 0.0`。
- `PortfolioSwing.kt:379-381`：`if (decision.status == BOTTOM_TRIAL) limits += ... * 0.25` 是死代码（`allocate` 只可能收到 READY）。
- 展示仍写“最多按初始仓位 25% 轻仓试错”（`:126-128`），并把状态归为“策略阻碍”；测试 `PortfolioSwingTest.kt:43-54` 只断言 `addQuantity <= 125`，0 也通过，等于没测。

**失败场景**：筑底（`major=BOTTOMING`、`allowsBottomReversal=true`）且其余条件全过的持仓，用户期望“按初始仓位 25% 轻仓试错”，实际永远 0 股；报告同时写“策略阻碍”和“25% 轻仓试错”，自相矛盾。

**建议**：把 `BOTTOM_TRIAL` 纳入 `allocate` 入口（或先按 25% 限额算出 `addQuantity` 再置状态），并把测试改成断言具体股数。

### 3.4 持仓链路的 P2

- “今日要点”与持仓报告对波段追加给出相反结论（卡片只看本行 `BUY`，报告要求全组合无风险，`PortfolioAnalysis.kt:778-786` vs `:943-947`）。
- 同日买入互斥的失败方被持久化为 `BLOCKED` 且当日永不复位（`DailyAdvicePolicy.kt:241-243` + `Store.kt:392-400`），B 股当天再不会收到买入通知。
- `dailyAdvicePlans` 跨日不清理、每次读取全量解码，持仓页每行两次（`Store.kt:337-348`、`PortfolioFragment.kt:177/183/221/229`），一年后约 7000 条。
- 持仓页“T+1 可卖”用设备时区 `LocalDate.now()`（`PortfolioFragment.kt:41`），与业务口径 `TimeUtils.todayText()`（Asia/Shanghai）不一致，跨时区会显示相反的可卖数量。
- 分仓配置解析失败时 getter 返回空 Map，下一次保存会用单条记录覆盖其余配置（`Store.kt:78-86` + `Dialogs.kt:1014`）。
- 记录真实买入后的持仓成本不含费用（`TradeLots.kt:190`），与 `TradeCalculator.kt:59-60` 口径不一致；`cost = 0` 参与加权会摊出非真实成本。
- 严格补仓的“按最高接受价重算数量”实际等于原数量（`MonitorService.kt:2826-2827`），最终也不复核可用现金（`IntradayTactics.kt:175` 的 `availableCapital` 未被使用）。
- 单只持仓停牌会让整个组合的波段追加全部归零（`PortfolioSwing.kt:252-256`）。
- 跨日旧 `portfolioResults` 未清空，盘中策略只校验行情日期、不校验 `level.analysisDate`（`MonitorService.kt:2321,2341`），分析失败时可用昨天的支撑/MA20 判断破位。
- 风险证据从第 2 根升级到第 3 根时不会二次提醒（注释写“最多 3 条”，实际被同日计划 `SENT` 状态挡住，`MonitorService.kt:2666-2673` + `DailyAdvicePolicy.kt:284-287`）。
- 做 T / 减仓数量统一按 100 股取整，未覆盖科创板卖出单笔不低于 200 股（`IntradayTactics.kt:108`、`TradeActionGuard.kt:268-269`）。
- `canApproveAddition` 把“基本面红灯”当成可加仓（`PortfolioPriorityPolicy.kt:33,40-43`），当前仅靠 `PortfolioSwing` 二次拦截。
- 组合循环重复网络/重复解析：每只单发行情、再批量拉一次、每只单拉 15 分钟 K 线（已有缓存）、`marketBreadth()` 调两次、`results.firstOrNull` O(n²)（`MonitorService.kt:4673,4703,4805-4806,4877,4915-4935`）。
- 清仓后 UI 只显示“底仓 0 股｜滚动仓 0 股”，看不到“底仓目标 600 仍在”（`PositionAllocationPolicy.kt:40-52`）。

## 4. 报告链路

### 4.1 P0：个股分析失败/全部缺行情时，复盘写成“没有已确认的持仓风险”，并覆盖上一份好报告

**证据**

- `HoldingAnalyzer.kt:377`：`runCatching { results.add(analyzePosition(...)) }.onFailure { Log.e(...) }` —— 单只分析异常被吞掉，results 少一只或为空。
- `StockAnalysis.kt:109`：`require(history.size >= 60)`，上市不足 60 个交易日的新股必然抛异常。
- `DailyReview.kt:236-239`：`if (risks.isEmpty()) return listOf("当前没有已确认的持仓风险；维持原有仓位…")`。
- `DailyReview.kt:789`：`if (risks.isEmpty()) "没有明显风险点"`。
- `HoldingAnalyzer.kt:440-446`：无论 `results.size` 是否等于持仓数，都 `store.lastDailyReviewText = text` 并标 success（状态文案虽写“有股票分析失败”，报告正文没有）。

**失败场景**：持仓里有一只近 60 日上市的新股，或日线接口整体故障 → 该股没有 `PositionResult` → 报告顶部写“当前没有已确认的持仓风险；维持原有仓位”，第二节写“没有明显风险点”，整篇不披露哪只没分析；同时覆盖昨天那份完好的复盘。`MonitorService.kt:4981-4982` 已先落 `lastDailyReviewDate`，当天不再自动重跑。

**建议**：把分析完成度（`analyzedCount/total`、失败代码）传进 `DailyReview.Inputs` 与长报告；`results` 为空或少于持仓时禁止输出“没有已确认风险/维持原有仓位”；复盘只在 `results.isNotEmpty()` 时覆盖 `lastDailyReviewText`，否则保留旧报告并标 failure（复用 `generateSummary` 的“失败不覆盖”策略）。

### 4.2 P1：个股技术报告的“操作建议”绕过大盘塌陷门控，与持仓页/AI 结论相反

**证据**

- `TechnicalAssessmentPolicy.kt:43-46`：`unifiedAdviceFor(result, history)` 没有 `marketRegime` 参数。
- 同文件 `:201-207` 有 `marketRegime == COLLAPSE -> "暂缓补仓：大盘处塌陷期…"` 分支，但对 `unifiedAdviceFor` 永不生效。
- `AnalysisService.kt:272-295` 已算出 `env.market`，却在下一行调用 `unifiedAdviceFor(result, history)`；`StockAiToolExecutor.kt:314-341` 同样忽略它。
- 对照 `TechnicalTrendSnapshotPolicy.kt:79-83` 正确传入了 `marketRegime`（持仓页/AI 趋势快照用的是这条）。

**失败场景**：大盘 `COLLAPSE`，某持仓日/周/月趋势强、`score >= 70`、`allowsAddPosition == true`：工具页技术报告写“可分批参与…首次仓位宜轻”，同一时刻持仓页/AI 写“暂缓补仓：大盘处塌陷期”。同一只票、同一时刻两个相反结论。

**建议**：`unifiedAdviceFor(result, history, marketRegime)`，两个调用点传 `env.market.regime`；卡片与报告共用同一结果。

### 4.3 P1：复盘顶部“维持原有仓位”与第二节“大盘塌陷：先降整体仓位”冲突

**证据**：`DailyReview.kt:204` 顶部先打印 `reviewOverallAdvice(inputs.results)`；`:236-239` 只看个股 `PriorityState`；`:723-725` 同一份报告第二节写“大盘塌陷，系统性风险优先：先降整体仓位”。`PriorityInput` 不包含大盘状态（`PortfolioPriorityPolicy.kt:3-18`）。

**失败场景**：`COLLAPSE` 且所有持仓为“持有观察” → 用户先看到“维持原有仓位”，翻两屏才看到“先降整体仓位”。

**建议**：`reviewOverallAdvice(results, market)`；塌陷且无 EXIT/REDUCE 时，顶部第一条改为系统性风险提示，不输出“维持原有仓位”。

### 4.4 P1：`nextQuarterDirection` 永远返回“利润趋势基本稳定”

**证据**：`FundamentalsReport.kt:895-901` 用 `nextQuarterOutlook(result).startsWith("利润趋势偏支持"/"利润趋势偏承压")` 判断，但 `nextQuarterOutlook` 的返回以“下一财报期趋势预判（…”开头（`:812`），两个 `startsWith` 永远不成立。工具卡片直接展示它（`ToolsFragment.kt:918`）。

**失败场景**：近 4 季净利同比均值 -30%：报告 §十写“利润趋势偏承压”，同一节结论/卡片写“下一财报期利润趋势基本稳定”。

**建议**：改为 `contains("：利润趋势偏承压")`，或让 `nextQuarterOutlook` 返回结构化 `(direction, text)`，禁止用前缀匹配散文。

### 4.5 P1：金融企业评分结构性封顶 68/100，却按统一的 100 分档说“财务质量中性”

**证据**

- `Fundamentals.kt:802`（ocf/cash 均 null → 8 分）、`:810`（netCash null → 10 分）、`:834`（balance null → 10 分）；金融企业另两项最多 20+20。
- 上限 = 20(扣非) + 8(现金) + 10(净现金) + 20(成长) + 10(资产干净) = **68**。
- `FundamentalHealth.kt:46`：`SOUND_MIN_SCORE = 70`；金融企业最高只能到 `NEUTRAL`。
- `FundamentalsReport.kt:69`：显示 `财务质量评分：${score}/100`，`:174-181` 按 55–69 输出“财务质量中性，有短板但不致命”。

**失败场景**：8 个季度数据齐全、扣非 0.98、成长 20% 的银行/券商，得分 68 → 报告说“有短板”；`FundamentalHealth` 永远到不了“未见恶化”，深套时提示“先不补仓”；用户拿 68/100 的银行和 85/100 的工业股对比会得出错误排序。

**建议**：对金融企业做归一化（跳过维度按比例折算到可得维度），或至少在评分行与健康结论写明“金融口径，满分 68/68（折合 100）”并映射档位。

### 4.6 P1：AI `get_portfolio_report` 的 `generated_at` 恒为 null

**证据**：`StockAiToolExecutor.kt:407-411` 解析 `"分析时间："`，但报告头是 `"最近分析：$analyzedAt"`（`PortfolioAnalysis.kt:950`）。`reportPayload` 空串写 `JSONObject.NULL`（`:489-493`）。

**失败场景**：AI 调 `get_portfolio_report` 得到 `report_status:"available"`，但没有生成时间，无法判断是 14:30 的还是三天前的，容易把旧报告当当前持仓回答。

**建议**：解析 `"最近分析："`，并额外返回 `analysis_date` 与 `stale` 标志。

### 4.7 P2（报告链路）

- **盘面总结“今日要点”把缺失的涨跌停数据写成“涨停 0 家、跌停 0 家”**（`MarketSummary.kt:418-427`），正文却写“涨跌停数据暂缺”（`:191-198`）；`limitScanEnabled=false` 或重启后可达。应输出“暂缺”，并给 `LimitData` 加日期。
- **21:00 槽位清空 `lastSummaryDate`，每天重复生成一次 18 点总结**（`MonitorService.kt:5260-5283`）；`autoGenerateSummary` 没有周末短路，周末 16:00 后每 15 秒失败重试。
- **手动重跑复盘丢失 `TurnoverTracker`**（`AnalysisService.kt:172`），用降级的市场风险上下文覆盖共享缓存，15 分钟内 AI 读到的缓存少同期量能维度。
- **报告用未校验日期的行情算“今日盈亏”**（`HoldingAnalyzer.kt:319,357-371`；`DayPnl.kt:81-118` 不校验 `quoteDate`），停牌股可能把上一交易日涨跌算进今日；持仓页 `todayBrief` 有日期过滤（`PortfolioAnalysis.kt:745-747`），两处口径不一致。
- **AI `get_portfolio_today_brief` 把“从未分析”报成 `no_priority_action`**（`StockAiToolExecutor.kt:424-426`）：`analysis_count=0` 时模型会回答“没有需要优先处理的持仓”。应返回 `not_analyzed` + `analysis_as_of`。
- **AI 行情工具缺 `quote_date/prev_close` 与单位**（`StockAiToolExecutor.kt:228-236`），模型可能把周五快照当最新价、把成交额量级写错。
- **513 知识库声称“扫描全部沪深 A 股”，但 AI 没有读取选股结果/观察池的工具**（`AiAnalysis.kt:116-227`），模型只能编造或一律回答“没有符合条件的”。
- **AI 报告标题取“最后一次 `search_security`”的标的**（`StockAiToolExecutor.kt:214-220`），问茅台但中途搜了同行 → 标题变成同行。
- **盘面总结只校验指数日期，不校验板块/概念/涨跌停新鲜度**，页脚却写“数据截至 now”（`MonitorService.kt:5299,5319-5321`；`MarketSummary.kt:380`）。
- **盘中手动“立即生成”盘面总结会用未完成的当日日 K 做量价对比**（`MonitorService.kt:454-456`）。
- **持仓长报告对“未做过基本面分析/健康度已过期”静默省略**（`PortfolioAnalysis.kt:1078-1083`），读者把缺失当“没问题”。
- **财务质量“存货增速达到营收增速 2 倍”在营收负增长时误触发**（`FinancialQualityPolicy.kt:112-118`）：营收 -10%、存货 +5% 也成立，文案数学上不成立。
- **AI 工具把带 `«»` UI 标记的完整长报告原样喂给模型且无截断**（`ReportMarkup` / `StockAiToolExecutor.kt:268,337`），可能把标记抄进回答并撑爆上下文。
- **港美股卡片用交易币种标注财报现金流**（`ToolsFragment.kt:715-717`），报告用财报币种；卡片估值结论与报告分档不一致。

---

## 5. P2（横切 / 选股 / 自选）

1. **盘面总结 21:00 会重复生成 18 点总结**：`autoGenerateSummary` 在 21 点成功后把 `lastSummaryDate = null`（`MonitorService.kt:5286`），下一个 tick 的 `hour >= 18 && lastSummaryDate != today` 立即再次成立。应写 `today`。
2. **部分提醒的重启去重仍只在内存**：个股跌幅分级（`stockDropAlertLevels`）、市场风险引擎、持仓量价异动 tracker 都在进程内；重启后会重新升级/重发。事件账本只接了部分通道。
3. **通知被系统禁用时逐 tick 同步写盘**：`Notifier.alert` 返回 false → `sendCheckedAlert` 先 `reserveAlertEventKey` 再 `releaseAlertEventKey`，两者都是 `commit()`。条件持续时每 15 秒写两次盘；建议缓存 `areNotificationsEnabled()` 做短路。
4. **`sendCheckedAlert` 的 SKIP_STALE 路径漏退预算**：`quoteFresh=false` 时键被释放、预算未退（`MonitorService.kt:2083-2101`）。当前没有调用方传 false，属潜伏问题。
5. **`AlertOrchestrator` 的行情新鲜度门控实际恒为 true**：全仓无调用点传 `quoteFresh`，`SKIP_STALE` 是死路径；而首板/启动发现各自有日期判断，口径重复。
6. **重复/失败返回值不区分导致重启后重试循环**：`sendCheckedAlert` 对 `SKIP_DUPLICATE` 也返回 false；首板承接 `!posted → rearm`（`:1910-1924`）在重启后每 15 秒“命中→重复→rearm”一次直到收盘。建议返回三态。
7. **启动发现日计数是内存态**：`launchFetchCount / launchAlertCount` 重启归零；`knownCodes` 没排除持仓和自选本身，会把用户已关注的票重复送进池。
8. **观察池缓存 25 vs 250 根日线**：启动发现允许 `>=25` 根并写入 `observationHistory`，观察池复评命中缓存后 `if (history.size < 250) return null`，次新股/历史不足的票当天无法复评。建议缓存带来源/最小根数，不足 250 时强制重拉。
9. **`ObservationPoolJson.decode` 单条损坏即整池清空**：用 `array.getJSONObject(index)`（非 `optJSONObject`），任一条坏数据→整池空列表；随后整表写回会覆盖原数据。建议逐条容错 + 解析失败拒绝覆盖写。
10. **阈值别名把不同语义强行绑定**：`BreakoutRiskPolicy.HIGH_BIAS_PCT` 与 `NormalDivergencePolicy.HIGH_BIAS_PCT` 都改为 `StrategyThresholds.MAX_CHASE_MA5_BIAS_PCT`；数值相同但语义是均线止盈/背离，不是追高上限。`LaunchDiscoveryTracker.MAX_MA5_BIAS_PCT=8.0` 又没复用共享常量。
11. **`Store` 缓存返回可变的 `Portfolio` 实例**：`Portfolio.positions` 是 `MutableList`；当前调用都走 `copy()`，但这是新的隐式契约，以后原地 `add` 会“看似保存成功、重启丢失”。建议防御性拷贝或在类注释写死。
12. **`IntradayTactics.Input.sellableQuantity` 注释与实现相反**：注释说空值按总持仓处理（`:178-179`），实现是 `?: 0.0`（`:1004`）。
13. **卖出整手规则只有买入区分板块**：`TradeActionGuard.lotFloor` 对所有卖出按 100 股取整（`:268-269`），科创板买入在 `AddPositionStrategy.kt:574` 区分 200 起。非 100 整数倍持仓的卖出边界可能给出不可下单数量。
14. **自选股买点候选在 UI 完全不可见**：`watchlistBuyCandidates` 只出现在 MonitorService/Store，`ScreenFragment` 只渲染观察池；用户复盘“自选股为什么没提醒”没有入口。
15. **`ObservationBreakoutRiskAlert` 是死代码**：只被测试引用，`StockAlertType.OBSERVATION_BREAKOUT_RISK` 从未传入 `shouldNotify`；要么接进复评，要么删掉。
16. **观察级提醒的开关口径不一致**：首板承接要求 `intradaySignalEnabled`，启动突破只要求 `alertsEnabled`。
17. **`LimitRanking` 的 `lastSealSeconds` 方向与 `firstSealSeconds` 相反**：`firstSeal` 早封得分高（`higherIsBetter=false`），`lastSeal` 晚封得分高（`higherIsBetter=true`）。若按行业口径“越早封越强”，方向反了；现有测试把 15:00 末封当强样本，需要产品确认后一起改。
18. **`IndicatorScreener` 的 `catch (Exception)` 吞掉取消且不记日志**（`:351-353`）；`ACTION_OBSERVE_SCREEN` 在服务主线程同步重建/编码观察池（`:450-452`）。
19. **`technicalScore` 用 40 兜底**（`Models.kt:528,541`、`IndicatorScreener.kt:247-249`）：无法区分“真实 40 分”和“没有快照”，UI 会显示“技术 40/100”并参与排序。
20. **最长 3 天的旧选股命中仍算“今日已有选股信号”**：`ScreenResultJson.STALE_RETENTION_MS=3 天`；`screened` 不带日期（`MonitorService.kt:3567-3571,4317-4323`），周一的复评会把周五命中算作今日信号。
21. **东财涨停池降级时把已有的 `limitStreak` 清成 null**（`MonitorService.kt:3429-3438`）：EM 故障日，昨日已知首板的龙头会被 `limitStreak != 1` 直接拦掉当天所有首板承接观察。
22. **`mergeScreenResult` 保留旧严格档 + 新宽松档时，同一只票可能同时在两档**（`:3200-3217`，宽松档只排除“本次”严格档）。
23. **观察池每分钟整池重写 + UI 每行两次全池扫描**：`store.observationPool / observationSnapshots / paperTrades` 每轮无条件落盘（`:3673-3708`）；`ScreenFragment.observationRow` 每行 `Repo.observationPool.filter{...}` 两次（`:720-727`），且对所有条目（不只当前页）建行。与 1.2 的淘汰条目不清理叠加后，几个月内会明显变慢。
24. **`refreshObservationPool` 与 `refreshWatchlistBuyCandidates` 共用一个 try/catch**（`:3535-3544`）：前者异常会让自选股买点与尾盘复核整轮跳过。
25. **通知 ID 乘法没有护栏**：`prefix * 100_000 + crc % 100_000`（`NotificationIds.kt:15`），当前最大 8600 安全，但以后前缀 >21474 会 Int 溢出。

---

## 6. 做得好的地方

- 第一轮多数修复确实落到了调用链（`rearm`、降级语义、预算回滚、Store 缓存、CRC32 通知 ID、35 分钟支撑位新鲜度），不是只改文档。
- `HoldingExitPolicy` 的“低位 + 轻仓 + 基本面未亮红灯 → NOTICE 去留自定”写得清楚，方向正确；问题只在组合层没有复用。
- 启动发现的原型、回放和限制说明诚实（`docs/2026-11-19-launch-discovery-replay.md`），回放时点有据可查。
- 观察池双时钟展示、`ObsPoolTrace` 结构化日志，为“为什么不提醒”提供了排查路径。
- 选股侧对 ST 涨跌幅、513 形态、指标两档方向、扫描覆盖率（<70% 整体失败）的口径基本自洽，未发现单位/方向性错误。

---

## 7. 建议落地顺序

1. **恢复绿色基线**：逐条定性 21 个失败（1.1）。
2. **报告 completeness**：分析失败不得写成“无风险”，失败不覆盖旧报告（4.1）；技术建议统一传 `marketRegime`（4.2）。
3. **观察池生命周期**：淘汰清理 + `knownCodes` 过滤 + 入池并发保护（1.2、2.7d）。这是“启动发现”能否长期工作的前提。
4. **提醒账本粒度**：下探回升 / 反弹跟踪按事件类型/低点拆键（1.3）。
5. **启动发现缓存与预算**：独立缓存、成功后再入池、预算按代码数（2.3）。
6. **选股时间口径**：非交易时段不补今日 bar、量比按全天/原始口径、非盘中不覆盖缓存（2.1、2.2）。
7. **持仓三条 P1**：成本缺失不得按 0 现金算账户盈亏（3.1）、删除监控清批次（3.2）、BOTTOM_TRIAL 真正分配（3.3）。
8. **0 仓/轻仓口径统一**（2.4）与**预算回滚**（2.6）。
9. **确认分仓产品决策**（2.5），再决定改代码还是加强引导。
10. P2 按触达率排序，优先 5.1 / 5.2 / 5.3 / 5.9 / 5.23。

---

## 附：验证命令

```bash
./gradlew :app:testDebugUnitTest
# 当前：1266 tests, 21 failed
```