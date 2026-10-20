# AI Event And Flow Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a shared, evidence-based market timing and flow context to AI analysis and portfolio analysis without turning weekday, month-end, or holiday labels into deterministic sell signals.

**Architecture:** Create pure analysis models for timing windows, northbound flow, and turnover evidence. The AI tool bridge fetches and serializes one compact `market_risk_context`; the monitor publishes the same context to `Repo` and passes it through `EnvContext` to portfolio rules. Fundamental reports keep their existing numeric forecast layers but label the next-report outlook as historical trend extrapolation unless an announcement provides explicit guidance.

**Tech Stack:** Kotlin, Android SharedPreferences, existing Sina/EastMoney HTTP clients, `org.json`, JUnit 4.

**Spec:** Conversation-approved design from 2026-10-12: consider report-season timing, withdrawal/settlement windows, holidays, month-end, Fridays, northbound flow, turnover, recent market/sector performance, and keep conclusions concise, evidence-based, and non-deterministic.

## Global Constraints

- Reuse the existing `TradingCalendar`, `TurnoverTracker`, `Repo`, `EnvContext`, and report-cache patterns.
- Do not claim a holiday or broker withdrawal date without a verified data source; return an explicit data gap instead.
- Timing labels are weak context only; portfolio signals remain driven by price, support/resistance, trend, fundamentals, and confirmed market/sector regimes.
- Northbound and turnover facts must include their date/source and use missing-data wording when unavailable.
- Preserve existing AI JSON parsing and portfolio-analysis defaults through additive nullable/defaulted fields.
- Use TDD: write and run a failing test before each production behavior change.

### Task 1: Add pure timing and flow models

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/MarketRiskContext.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/EnvContext.kt`
- Test: `app/src/test/java/com/example/stockmonitor/MarketRiskContextTest.kt`

**Interfaces:**
- Consumes: `Calendar`, optional northbound/turnover facts, and optional verified holiday/withdrawal metadata.
- Produces: `MarketTimingContext`, `NorthboundFlowSummary`, `TurnoverContext`, `MarketRiskContext`, plus concise `aiText()` and conservative `portfolioNote()` methods.

- [ ] **Step 1: Write failing tests**

Cover Friday, last-three-calendar-days-of-month, quarter-end/report-season windows, missing holiday/withdrawal data, northbound 1/5/20-day aggregation, turnover comparison, and the rule that a lone Friday/month-end label does not produce a sell instruction.

- [ ] **Step 2: Run the focused test and verify the expected failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.MarketRiskContextTest`

Expected: FAIL because the new models do not exist.

- [ ] **Step 3: Implement the pure models**

Use explicit evidence labels such as `常见财报窗口` and `日期因素仅作风险提示`; keep holiday and broker withdrawal fields nullable with `数据暂缺` text. Add `marketRisk: MarketRiskContext? = null` to `EnvContext` and preserve `EnvContext.NONE` behavior.

- [ ] **Step 4: Run the focused test and verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.MarketRiskContextTest`

Expected: PASS.

### Task 2: Add northbound data fetching and parsing

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/EastMoneyClient.kt`
- Test: `app/src/test/java/com/example/stockmonitor/EastMoneyClientTest.kt`

**Interfaces:**
- Consumes: EastMoney northbound daily-flow JSON.
- Produces: `fun northboundDailyFlow(days: Int = 20): NorthboundFlowSummary?` and a pure parser that accepts the supported response shapes, returning null on malformed or unavailable data.

- [ ] **Step 1: Write failing parser tests**

Add a fixture with 20 dated daily rows and assert latest net flow, 5-day/20-day cumulative values, direction, and date. Add malformed/missing-data cases that return null rather than zero.

- [ ] **Step 2: Run the focused test and verify the expected failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.EastMoneyClientTest`

Expected: FAIL because the parser and client method do not exist.

- [ ] **Step 3: Implement the client and parser**

Call the existing EastMoney history endpoint style with a bounded 20-row request, parse northbound daily net flow only when the response identifies a date and numeric value, and wrap network/shape failures as null. Never convert unavailable data to zero.

- [ ] **Step 4: Run the focused test and verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.EastMoneyClientTest`

Expected: PASS.

### Task 3: Publish shared timing, flow, and turnover context

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/Repo.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Test: `app/src/test/java/com/example/stockmonitor/MarketRiskContextTest.kt`

**Interfaces:**
- Consumes: current `Repo.turnoverSnapshot`, `Repo.turnoverToday`, benchmark/sector data, `EastMoneyClient.northboundDailyFlow`, and current Shanghai calendar time.
- Produces: `Repo.marketRiskContext`, refreshed once per portfolio-analysis run or when market data is refreshed, and passed as `EnvContext.marketRisk`.

- [ ] **Step 1: Write failing integration-shape tests**

Assert that `EnvContext` accepts the context and that the portfolio-note text is empty for weak timing evidence, while combined negative flow plus contracting turnover produces a concise caution note without changing the signal.

- [ ] **Step 2: Run the focused test and verify the expected failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.MarketRiskContextTest --tests com.example.stockmonitor.PortfolioAnalysisTest`

Expected: FAIL because `Repo.marketRiskContext`, `EnvContext.marketRisk`, and the portfolio note are not wired.

- [ ] **Step 3: Implement the publishing and portfolio wiring**

Build the context from verifiable inputs. Pass it into `PortfolioAnalysis.evaluate` through `EnvContext`; append a note only when at least two independent evidence items agree. Keep `signal` unchanged and never add an automatic sell solely for Friday, month-end, or an unknown holiday/withdrawal date.

- [ ] **Step 4: Run the focused tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.MarketRiskContextTest --tests com.example.stockmonitor.PortfolioAnalysisTest`

Expected: PASS.

### Task 4: Expose one compact market-risk tool to AI

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/AiAnalysis.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/StockAiToolExecutor.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/AiAnalysis.kt` result fields if required
- Test: `app/src/test/java/com/example/stockmonitor/AiAnalysisTest.kt`

**Interfaces:**
- Consumes: current report tools, recent index/sector context, `Repo.marketRiskContext`, and persisted turnover state.
- Produces: `get_market_risk_context` tool returning timing, northbound, turnover, recent-market, data gaps, and evidence labels; AI result field `marketRisk` with a default missing-data value.

- [ ] **Step 1: Write failing tests**

Assert the tool catalog includes the new tool, the prompt requires it for stock/index analysis, legacy JSON without `market_risk` parses safely, and the report/UI expose the new concise section.

- [ ] **Step 2: Run the focused test and verify the expected failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.AiAnalysisTest`

Expected: FAIL because the tool, result field, and formatting do not exist.

- [ ] **Step 3: Implement the tool and prompt contract**

Return structured facts with dates and sources. Update the system prompt to require the market-risk tool and to distinguish evidence from inference. Keep the output schema additive and cap the AI section to a few concise sentences.

- [ ] **Step 4: Run the focused test and verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.AiAnalysisTest`

Expected: PASS.

### Task 5: Make fundamental forecast wording explicit

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/FundamentalsReport.kt`
- Test: `app/src/test/java/com/example/stockmonitor/FundamentalsTest.kt`

**Interfaces:**
- Consumes: existing `FundamentalResult.quarters` and announcement/governance signals.
- Produces: report wording that distinguishes `下一财报期趋势预判（历史同比外推）` from explicit company guidance/forecast evidence, and separately keeps the 6–9 month valuation scenario.

- [ ] **Step 1: Write failing tests**

Assert that a report with only historical quarters says it is a directional historical extrapolation and does not print a precise future profit number; assert that an explicit performance-forecast announcement is labeled as announcement evidence.

- [ ] **Step 2: Run the focused test and verify the expected failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.FundamentalsTest`

Expected: FAIL because the current wording only says `下一财报期` and does not state the forecast basis.

- [ ] **Step 3: Implement the minimal wording change**

Reuse existing announcement risk/governance classification; do not create numeric earnings forecasts from insufficient inputs. Mark unavailable guidance as `未发现可核对的业绩预告，以下仅为历史趋势外推`.

- [ ] **Step 4: Run the focused test and verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.FundamentalsTest`

Expected: PASS.

### Task 6: Full verification

**Files:**
- Modify tests only if a regression is found in the preceding tasks.

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL with zero test failures.

- [ ] **Step 2: Build the debug APK**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Inspect formatting and scope**

Run: `git diff --check` and `git diff --stat` when Git metadata is available; otherwise inspect changed-file paths and report that this workspace has no Git metadata.
