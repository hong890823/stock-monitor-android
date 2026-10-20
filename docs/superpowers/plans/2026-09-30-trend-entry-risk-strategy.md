# Trend Entry and Risk Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将“趋势、量能、市场环境、入场结构、止损和仓位控制”统一接入技术分析、基本面综合结论及盘中买卖点提醒。

**Architecture:** 在技术分析结果中增加可复用的趋势/入场/止损上下文；持仓分析和盘中提醒只消费该上下文，不重复实现规则。基本面原始财务指标保持不变，在报告结论中增加基本面与当前技术入场结构的配合判断。

**Tech Stack:** Kotlin、Android、JUnit、现有 `StockAnalysis`、`PortfolioAnalysis`、`IntradayTactics` 和 `FundamentalsReport`。

**Spec:** 用户确认的“突破前高/回踩 M20 + 量能 + 大盘过滤 + 均线趋势 + 止损 + 风险仓位”策略。

## Global Constraints

- 不把单一形态直接当成无条件买入指令。
- 大盘/板块过滤采用软过滤：弱势环境降低或阻止新开仓提醒，但不改变原始财务数据。
- 止损价必须随报告和提醒展示；实际成交价可能因波动偏离止损价。
- 普通信号继续使用约 30 秒双轮确认，风险信号保留快速提醒机制。
- 不新增网络依赖，不改变既有数据源和自选股/持仓存储格式。

### Task 1: Technical entry context

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/StockAnalysis.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/TechnicalSummary.kt`
- Test: `app/src/test/java/com/example/stockmonitor/StockAnalysisReferenceTest.kt`
- Test: `app/src/test/java/com/example/stockmonitor/TechnicalSummaryTest.kt`

- [x] Add a typed entry context containing prior-high, MA20 slope, volume confirmation, market filter, setup type and stop price.
- [x] Define breakout as effective prior-high break with volume and established upward MA20 trend.
- [x] Define pullback as price near rising MA20 with contracting volume and no effective MA20 break.
- [x] Define invalid/weak states explicitly and expose concise text for reports.
- [x] Add failing tests for breakout, MA20 pullback, weak-market filtering and stop calculation.
- [x] Implement the smallest calculation changes and make existing technical reports consume the context.
- [x] Run targeted technical tests.

### Task 2: Portfolio signal and position sizing

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/PortfolioAnalysis.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/IntradayTactics.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Test: `app/src/test/java/com/example/stockmonitor/IntradayTacticsTest.kt`

- [x] Add market/trend/volume gates to new-entry signals while keeping existing support-break and T signals.
- [x] Add risk-based suggested quantity: allowed risk divided by entry-to-stop distance, capped by initial/max position limits and lot size.
- [x] Include setup type, stop price and position-size rationale in buy/hold/T alert text.
- [x] Keep two-round confirmation, cooldown and daily alert cap unchanged unless a test requires a precise adjustment.
- [x] Add failing tests for blocked buys in weak market, breakout confirmation, MA20 pullback confirmation and risk-based quantity.
- [x] Implement and run targeted alert tests.

### Task 3: Fundamental and combined conclusions

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/FundamentalsReport.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/ToolsFragment.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/GlobalFundamentalsReport.kt` if its technical context supports the same fields
- Test: `app/src/test/java/com/example/stockmonitor/FundamentalsTest.kt`

- [x] Preserve all financial, valuation, shareholder and announcement calculations.
- [x] Add a concise “基本面 × 技术择时” conclusion: supported, mixed, or unsupported.
- [x] Distinguish good fundamentals without a valid entry structure from technical breakout with weak fundamentals.
- [x] Show the combined conclusion in the full fundamentals report and the Tool Tab summary where technical data is available.
- [x] Add tests covering fundamental support, technical-only signal and no-entry structure.

### Task 4: UI and report wording

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/StockAnalysis.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/PortfolioFragment.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/ToolsFragment.kt`
- Test: adjacent report assertions where wording changes

- [x] Add clear labels for “突破型机会 / 回踩 M20 观察 / 暂无有效入场结构”.
- [x] Display market filter, volume condition, MA20 direction, stop price and suggested risk position without overwhelming the summary.
- [x] Keep detailed calculation output in the full report and concise actionable wording in the summary card.
- [x] Update existing assertions for changed report headings only where necessary.

### Task 5: Verification

- [x] Run all targeted tests from Tasks 1–4.
- [x] Run `./gradlew testDebugUnitTest`.
- [x] Run `./gradlew assembleDebug`.
- [x] Review the final integration references for accidental changes to unrelated alert or storage behavior.
