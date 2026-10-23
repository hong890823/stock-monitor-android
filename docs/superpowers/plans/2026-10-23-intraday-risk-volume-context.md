# Intraday Risk and Volume Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the macOS intraday risk, completed timeframe evidence, volume context, trade guard, and T+1 ledger rules into Android notifications.

**Architecture:** Pure Kotlin modules compute daily risk, volume context, completed-bar evidence, and guarded actions. `MonitorService` supplies fresh quote/bar/ledger data, while `TradeNotification` renders only guarded output.

**Tech Stack:** Kotlin, Android, JUnit4, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-23-intraday-risk-volume-context-design.md`

## Global Constraints

- No network calls in pure policy modules.
- Missing quote date or evidence is observation/next-day handling, never invented execution.
- Current unfinished 15/30-minute bars never count as confirmation.
- Every sell quantity, including full exit, is lot-rounded down to 100-share lots and capped by actionable T+1 quantity.
- Existing unrelated dirty changes are preserved.

## File Map

- Create `core/DailyRiskContext.kt`: date-keyed daily slow variables and cache.
- Create `core/IntradayVolumeContext.kt`: market/stock/bar volume and rejection/absorption patterns.
- Modify `core/IntradayBars.kt`: completed-slot freshness and `TimeframeEvidence`.
- Create `core/TradeActionGuard.kt`: unified buy/risk/sell/T action gate.
- Modify `core/TradeLots.kt`: ledger consistency and actionable quantity.
- Modify `analysis/TradeNotification.kt`: render only guarded action output.
- Modify `analysis/IntradayTactics.kt` and `monitor/MonitorService.kt`: provide context and use actionable quantity.
- Tests under `app/src/test/java/com/example/stockmonitor/core` and `app/src/test/java/com/example/stockmonitor`.

## Tasks

### Task 1: Daily risk context

Write tests for date-keyed caching, risk-level escalation, and missing-date failure. Implement `DailyRiskContext`, `DailyRiskContextInput`, `DailyRiskContextCache`, and `riskLevel(price)`. Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.core.DailyRiskContextTest`.

### Task 2: Volume context

Write tests for predicted-full-day market ratio, stock ratio, completed-bar ratio, absorption, blowoff rejection, and unfinished-bar exclusion. Implement `IntradayVolumeContext.build` with strict completed-bar volume confirmation. Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.core.IntradayVolumeContextTest`.

### Task 3: Completed timeframe evidence

Write tests that a current unfinished slot is ignored, stale bars fail freshness, the ATR threshold is clamped, and trailing risk confirmation is capped at three. Implement `latestCompletedSlot`, `IntradayBarWindow.completedAt`, and `TimeframeEvidence.build`. Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.core.TimeframeEvidenceTest`.

### Task 4: Ledger and unified action guard

Write tests for inconsistent ledgers, missing buy dates, one/two/three-bar risk staging, absorption quantity cap, buy volume failure, and T+1. Add `ledgerConsistent`, `actionableSellableToday`, and `actionableNextTradingDaySellable` to `TradeAvailability`, then implement `TradeActionGuard`. Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.core.TradeLotsTest --tests com.example.stockmonitor.core.TradeActionGuardTest`.

### Task 5: Notification integration

Extend `IntradayTactics.Input` with quote date, daily context, timeframe evidence, volume context, and ledger consistency. Change `TradeNotification.intraday` to produce guarded output only. Update focused notification tests and run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.TradeNotificationTest --tests com.example.stockmonitor.IntradayTacticsTest`.

### Task 6: Monitor integration

Refresh intraday bars before signal evaluation, exclude unfinished bars from confirmation, build daily/volume/timeframe contexts per quote date, use actionable quantities, and pass guarded copy into notifications. Keep pure observation signals out of push notifications. Run focused tests, then `./gradlew testDebugUnitTest`; report unrelated existing failures separately.
