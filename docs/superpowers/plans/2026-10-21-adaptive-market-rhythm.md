# 自适应市场节奏与分歧压力实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用相对量能、市场宽度、趋势状态和个股延伸度动态调整分歧观察窗口，不把 2.5 万亿或固定上涨天数当作硬规则。

**Architecture:** 新增纯函数 `AdaptiveRhythmPolicy`，输出分歧阶段、风险压力和动态做 T 回落系数；`ImpulseContext` 从日线计算上涨/下跌连续天数。技术分析、持仓分析和盘中策略共享同一判断，时间只增加风险权重，真正的风险仍由量价结构确认。

**Tech Stack:** Kotlin、JUnit、Android Debug 构建。

**Spec:** `docs/superpowers/specs/2026-10-21-trend-and-high-position-gates-design.md`

## Global Constraints

- 不使用绝对成交额 `2.5 万亿` 作为单一门槛；优先使用成交额同期相对倍数，缺失时降级为未知。
- 不按固定第 4/5 天或第 7/8 天预测分歧；连续天数只做压力递增。
- 强势市场允许更长的延续容忍度，弱势市场更早提高警惕，但必须有价格/量能结构证据才升级风险。
- 大趋势向下、塌陷或数据缺失时不做上涨周期预测，只做风险/反弹观察。

## Task 1: 自适应节奏策略

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/AdaptiveRhythmPolicy.kt`
- Test: `app/src/test/java/com/example/stockmonitor/AdaptiveRhythmPolicyTest.kt`

**Interfaces:**
- Consumes: `ImpulseContext`、`MarketRegime`、`ShortTermTrend`、成交额同期相对倍数、宽度、ATR%、量比、MA5 乖离、下移高点和放量滞涨证据。
- Produces: `AdaptiveRhythmDecision`，包含 `phase`、`pressure`、`retreatFactor`、`reason`。

- [x] **Step 1:** 添加失败测试：强势环境比弱势环境更晚进入分歧观察；下移高点/放量滞涨无需等待固定天数即可风险；下跌只在缩量止跌后进入反弹观察。
- [x] **Step 2:** 运行 `./gradlew testDebugUnitTest --tests com.example.stockmonitor.AdaptiveRhythmPolicyTest` 并确认因策略缺失而失败。
- [x] **Step 3:** 实现相对量能评分、宽度/指数/趋势评分、个股延伸压力、动态回落系数和保守的数据缺失降级。
- [x] **Step 4:** 运行目标测试并确认通过。

## Task 2: 日线延伸上下文

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/StockAnalysis.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/PortfolioAnalysis.kt`
- Test: `app/src/test/java/com/example/stockmonitor/StockAnalysisReferenceTest.kt`

**Interfaces:**
- Produces: `TechnicalResult.impulse`、`PositionResult.rhythm`。

- [x] **Step 1:** 添加失败测试：连续上涨天数和近 5 日涨幅进入技术结果，持仓报告输出自适应节奏而不是固定天数。
- [x] **Step 2:** 运行目标测试并确认失败。
- [x] **Step 3:** 从日线计算 `ImpulseContext`，在持仓分析中调用 `AdaptiveRhythmPolicy` 并写入报告。
- [x] **Step 4:** 运行 `StockAnalysisReferenceTest`、`PortfolioAnalysisTest` 并确认通过。

## Task 3: 盘中动态做 T 与风险门控

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/IntradayTactics.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Test: `app/src/test/java/com/example/stockmonitor/IntradayTacticsTest.kt`

**Interfaces:**
- Consumes: `ImpulseContext`、成交额同期相对倍数、`AdaptiveRhythmDecision`。
- Produces: 按市场强度动态调整的回落确认倍数；结构证据触发的风险文案。

- [x] **Step 1:** 添加失败测试：强市场比弱市场要求更深回落才给做 T；放量滞涨/下移高点优先输出节奏风险。
- [x] **Step 2:** 运行目标测试并确认失败。
- [x] **Step 3:** 将 `marketTurnoverRatio` 与 `impulse` 接入 `IntradayTactics.Input`，用动态 `retreatFactor` 替代固定强市场倍数，并接入 MonitorService。
- [x] **Step 4:** 运行 `IntradayTacticsTest`、`MarketEnvironmentQualityTest` 并确认通过。

## Task 4: 文档与全量验证

**Files:**
- Modify: `README.md`

- [x] **Step 1:** 记录“相对量能 + 宽度 + 趋势 + 延伸度”的动态口径，明确固定天数只作风险权重、不是预测。
- [x] **Step 2:** 运行 `./gradlew testDebugUnitTest --offline`、`./gradlew assembleDebug --offline`、`git diff --check`。
