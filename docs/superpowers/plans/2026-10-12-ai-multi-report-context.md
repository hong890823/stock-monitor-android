# AI Multi-Report Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make AI analysis combine technical, fundamental, portfolio, and market-summary reports, while adding recent multi-day index and target-sector performance to the market context.

**Architecture:** Keep report generation unchanged and extend the existing read-only `AiToolExecutor` bridge. The model explicitly requests four report tools plus a recent-market-context tool; the executor reads cached portfolio/summary reports and fetches compact recent index/sector K-line summaries without starting background report jobs. The final formatter exposes the new portfolio and market sections while preserving the existing JSON contract through additive fields.

**Tech Stack:** Kotlin, Android SharedPreferences, `org.json`, existing Sina/EastMoney clients, JUnit 4.

**Spec:** Conversation-approved bounded design from 2026-10-12, including the follow-up requirement that current-day market summaries must be supplemented with recent index and sector performance.

## Global Constraints

- Reuse existing `Repo`, `Store`, `AnalysisReportCache`, `SinaClient`, `EastMoneyClient`, and `BoardTagCache` patterns.
- Tools are read-only and must not trigger portfolio or market-summary background jobs.
- Missing or stale data is reported explicitly; no numbers or conclusions are invented.
- Preserve backward compatibility for existing AI JSON responses and cached results.
- Use TDD: add and run a failing test before each production behavior change.

### Task 1: Extend the AI result and prompt contract

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/AiAnalysis.kt:13-195`
- Test: `app/src/test/java/com/example/stockmonitor/AiAnalysisTest.kt`

**Interfaces:**
- Consumes: Existing `AiAnalysisResult` JSON fields.
- Produces: Additive `portfolio` and `marketContext` strings on `AiAnalysisResult`, JSON keys `portfolio` and `market_context`, and prompt instructions requiring all four report sources plus recent market context.

- [ ] **Step 1: Write the failing test**

Add tests asserting that `AiAnalysisResult.fromJson` reads `portfolio` and `market_context`, while omitting either field falls back to `"数据暂缺"`; assert the system prompt names technical, fundamental, portfolio, market-summary, and recent multi-day market/sector context as required inputs.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.AiAnalysisTest`

Expected: FAIL because the result model has no new fields and the prompt does not require the new sources.

- [ ] **Step 3: Write minimal implementation**

Extend the data class with defaulted additive fields so existing constructors remain valid; serialize/parse the new keys; update `AiAnalysisPrompt.system()` with explicit mandatory tool calls and the rule that `market_context` must distinguish today’s summary from recent multi-day index/sector data.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.AiAnalysisTest`

Expected: PASS.

### Task 2: Add cached portfolio and market-summary tools

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/AiAnalysis.kt:80-150`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/StockAiToolExecutor.kt:45-90`
- Test: `app/src/test/java/com/example/stockmonitor/AiAnalysisTest.kt`

**Interfaces:**
- Consumes: `Repo.portfolioReport`, `Store.lastPortfolioReport`, `Repo.summaryText`, `Store.lastSummaryText`.
- Produces: Tool names `get_portfolio_report` and `get_market_summary_report`, returning JSON with `report`, `source`, `generated_at`, and an explicit missing-data message when no cache exists.

- [ ] **Step 1: Write the failing test**

Add a tool-catalog test that asserts both tool definitions exist and describe cached/read-only reports; add executor-facing pure helper tests if needed for cache selection, asserting in-memory `Repo` data wins and persisted `Store` data is the fallback.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.AiAnalysisTest`

Expected: FAIL because the catalog and executor do not recognize either tool.

- [ ] **Step 3: Write minimal implementation**

Add catalog definitions, dispatch cases, and executor methods. Read `Repo` first, then `Store`; do not refresh or regenerate reports. Include report timestamps when available and a stable `report_status` such as `available` or `missing`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.AiAnalysisTest`

Expected: PASS.

### Task 3: Add recent multi-day index and sector context

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/AiAnalysis.kt:80-150`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/StockAiToolExecutor.kt:189-282`
- Test: `app/src/test/java/com/example/stockmonitor/AiAnalysisTest.kt`
- Test: `app/src/test/java/com/example/stockmonitor/SectorContextTest.kt` if a pure summary helper is extracted there

**Interfaces:**
- Consumes: Resolved security code/name, existing `SinaClient.kline`, `EastMoneyClient.stockBoards`, and `EastMoneyClient.boardKline`.
- Produces: Read-only tool `get_recent_market_context(code, name)` returning structured index summaries for 5/20 trading days and the target stock’s main industry board summaries for 5/20 days, plus `source` and `data_gaps`.

- [ ] **Step 1: Write the failing test**

Add pure summary tests using fixed `DayBar` lists: verify percentage changes use the latest close against the close N trading bars earlier, insufficient history produces `data_gaps` instead of zeroes, and the JSON shape includes separate `indices` and `sectors` sections. Add a catalog assertion for `get_recent_market_context`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.AiAnalysisTest`

Expected: FAIL because no recent-context tool or summary helper exists.

- [ ] **Step 3: Write minimal implementation**

Implement a small pure formatter/helper in `StockAiToolExecutor` or a focused analysis utility. Fetch the three existing A-share benchmark K-lines when the target is A-share; for the target’s main industry, load cached/F10 tags via `BoardTagCache`, fetch up to the first two valid industry board K-lines, and summarize 1/5/20-day returns and direction. For non-A-share securities, return index/sector gaps without applying A-share endpoints. Cap output to compact summaries suitable for the model and preserve per-source failures.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.AiAnalysisTest`

Expected: PASS.

### Task 4: Format and display the four-source analysis

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/AiAnalysisReport.kt:5-45`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/ToolsFragment.kt:815-840`
- Test: `app/src/test/java/com/example/stockmonitor/AiAnalysisTest.kt`

**Interfaces:**
- Consumes: `AiAnalysisResult.portfolio` and `AiAnalysisResult.marketContext`.
- Produces: Full report sections labeled `技术面`, `基本面`, `持仓影响`, and `盘面环境（今日总结 + 近期大盘/板块表现）`; compact UI summary lines for the two new fields.

- [ ] **Step 1: Write the failing test**

Add a report-format test asserting both new sections and their values appear, and that the warning remains present when reports are missing.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.AiAnalysisTest`

Expected: FAIL because the formatter currently omits both sections.

- [ ] **Step 3: Write minimal implementation**

Add the two sections to the long report and two concise lines to the card preview. Keep existing trend/fundamentals/valuation/risk rendering and markup behavior unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.AiAnalysisTest`

Expected: PASS.

### Task 5: End-to-end verification

**Files:**
- Modify: `app/src/test/java/com/example/stockmonitor/AiAnalysisTest.kt` only if coverage gaps remain.

- [ ] **Step 1: Run focused tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.AiAnalysisTest --tests com.example.stockmonitor.SectorContextTest`

Expected: PASS with zero failures.

- [ ] **Step 2: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS with zero failures; any unrelated pre-existing failure is reported verbatim rather than changed.

- [ ] **Step 3: Compile the debug app**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Inspect the final diff**

Run: `git diff --check` and `git diff --stat`.

Expected: no whitespace errors; changed files are limited to the AI contract, executor, formatter/UI, plan, and focused tests.
