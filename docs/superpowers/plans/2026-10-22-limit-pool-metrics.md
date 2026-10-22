# 涨跌停池指标 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add verified limit-pool strength and timing metrics, then split the market card’s full-list actions by limit direction.

**Architecture:** Parse East Money limit pools into the existing `LimitData` model, retain the Sina approximate scan as a fallback, and format all new metrics through `LimitReport`. `MarketFragment` remains a read-only renderer and uses separate report actions for up and down lists.

**Tech Stack:** Kotlin, Android programmatic UI, JUnit 4, East Money JSONP, existing `Http` and `Repo` patterns.

**Spec:** `docs/superpowers/specs/2026-10-22-limit-pool-metrics-design.md`

## Global Constraints

- Kotlin/JVM target 17; Android minSdk 26.
- Preserve the current public `LimitReport.format(data)` behavior for compatibility.
- Missing source fields must render as `—`; never infer historical opening counts or seal times.
- Do not create Git commits; the user will review and test the working tree.

---

### Task 1: Limit-pool data model and parser

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/Models.kt`
- Create: `app/src/main/java/com/example/stockmonitor/core/EastMoneyLimitClient.kt`
- Test: `app/src/test/java/com/example/stockmonitor/core/LimitPoolParserTest.kt`

**Interfaces:**
- Consumes: existing `LimitData`, `LimitStock`, and `Http.getText`.
- Produces: `EastMoneyLimitClient.pools(date: String): LimitData`, extended nullable metric fields on `LimitStock`, and `LimitDataSource`.

- [x] **Step 1: Write failing reflection-based parser tests**
- [x] **Step 2: Run tests to verify only the missing parser contract fails**
- [x] **Step 3: Add metric fields and implement East Money JSONP parsing**
- [x] **Step 4: Run parser tests and the full unit test suite**

### Task 2: Metrics and split reports

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/LimitReport.kt`
- Test: `app/src/test/java/com/example/stockmonitor/LimitReportTest.kt`

**Interfaces:**
- Consumes: extended `LimitStock` fields from Task 1.
- Produces: `LimitReport.formatUp(data)`, `LimitReport.formatDown(data)`, `LimitReport.stockLine(stock)`, and percentage accessors on `LimitStock`.

- [x] **Step 1: Add failing tests for ratios and separate report formats**
- [x] **Step 2: Run tests to verify the new report behavior fails**
- [x] **Step 3: Implement ratio calculations and report formatting**
- [x] **Step 4: Run targeted and full unit tests**

### Task 3: Data integration and market-card UI

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/MarketFragment.kt`
- Test: `app/src/test/java/com/example/stockmonitor/MarketLimitCardTest.kt`

**Interfaces:**
- Consumes: `EastMoneyLimitClient.pools`, `LimitReport.stockLine`, `formatUp`, and `formatDown`.
- Produces: East Money-first limit data in `Repo.limits`, 8-row previews, and two independently labelled full-list buttons.

- [x] **Step 1: Add failing structural tests for 8-row previews and split actions**
- [x] **Step 2: Run tests to verify the current combined card fails**
- [x] **Step 3: Integrate East Money-first refresh with Sina fallback**
- [x] **Step 4: Update the card and report actions**
- [x] **Step 5: Run targeted tests, full unit tests, and `assembleDebug`**
