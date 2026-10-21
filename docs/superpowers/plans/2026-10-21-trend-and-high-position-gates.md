# Trend And High-Position Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add shared short-term and major-trend decisions plus conservative high-position structure gates for all buy, sell, T-trade, observation, sizing, and notification flows.

**Architecture:** Pure Kotlin policies classify completed daily and larger-timeframe evidence into short-term trend, major trend, and high-position phase. A single `TrendAssessment` is passed to existing analysis and monitor flows; risk alerts bypass trade gates, while normal opportunities require trend and dual-timeframe approval.

**Tech Stack:** Kotlin, Android SharedPreferences, existing `org.json` clients, JUnit 4, and the current Sina/EastMoney data adapters. No new third-party dependency.

**Spec:** `docs/superpowers/specs/2026-10-21-trend-and-high-position-gates-design.md`

## Global Constraints

- Use completed daily bars for major-trend and bottoming decisions; live quotes may only affect intraday confirmation.
- Five-day no-new-low is mandatory for `MajorTrend.BOTTOMING`.
- Major trend `DOWN` blocks normal buy, add-position, T-buy/rebuy, and ordinary T-sell.
- Major trend `BOTTOMING` permits only bottom-reversal observation or a trial capped at 25% of configured initial position.
- `HighPositionPhase.DISTRIBUTION_RISK` blocks buys and T-buys and prioritizes reduce/exit.
- Risk sell and breakdown alerts remain immediate and bypass ordinary opportunity throttles.
- The 30-minute divergence/support-hold pattern is confirmation only and never a standalone buy trigger.
- Chip estimates remain volume-weighted estimates, not real ownership data.
- Unknown or conflicting data yields neutral observation copy, not a directional forecast.
- No new third-party dependency.

## File Structure

- `app/src/main/java/com/example/stockmonitor/analysis/TrendState.kt`: shared enums, `TrendAssessment`, evidence inputs, and state text.
- `app/src/main/java/com/example/stockmonitor/analysis/TrendAssessmentPolicy.kt`: short-term, major-trend, bottoming, and action-gate calculations.
- `app/src/main/java/com/example/stockmonitor/analysis/HighPositionPolicy.kt`: high-range classification and second-wave transitions.
- `app/src/main/java/com/example/stockmonitor/analysis/IntradayTactics.kt`: consume `TrendAssessment`, add bottom-reversal and second-wave signal types, and enforce gates.
- `app/src/main/java/com/example/stockmonitor/analysis/ObservationPool.kt`: require trend eligibility for observation promotion.
- `app/src/main/java/com/example/stockmonitor/analysis/PortfolioSwing.kt`: apply major-trend and high-position gates to new capital and T sizing.
- `app/src/main/java/com/example/stockmonitor/analysis/TradeNotification.kt`: include trend/phase state and conservative action wording.
- `app/src/main/java/com/example/stockmonitor/analysis/StockAnalysis.kt`: render the shared trend assessment in reports.
- `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`: build and pass the shared assessment to intraday and observation flows.
- Tests: `TrendAssessmentPolicyTest.kt`, `HighPositionPolicyTest.kt`, plus updates to `IntradayTacticsTest.kt`, `ObservationPoolTest.kt`, `PortfolioSwingTest.kt`, `TradeNotificationTest.kt`, and `StockAnalysis` report tests.

## Execution Order

1. Build the pure state model and trend policy.
2. Add high-position classification and second-wave confirmation.
3. Add action gates and intraday signal types.
4. Integrate observation and stock-analysis consumers.
5. Integrate portfolio sizing and notification copy.
6. Run targeted tests, full unit tests, and Android assembly.

---

### Task 1: Shared Trend State Model

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/TrendState.kt`
- Test: `app/src/test/java/com/example/stockmonitor/TrendAssessmentPolicyTest.kt`

**Interfaces:**
- Consumes: `HoldingTTrend`, `SwingTrend`, `MultiTimeframeSummary`, `DayBar`, `PricePosition`, `ChipPeaks`.
- Produces: `ShortTermTrend`, `MajorTrend`, `TrendAssessment`, `TrendAssessmentPolicy.evaluate`.

- [ ] **Step 1: Write failing tests for short-term UP/EXTENDED/DOWN and major UP/DOWN/BOTTOMING/REPAIR.**

```kotlin
@Test fun majorTrendBottomingRequiresFiveDaysWithoutNewLow() {
    val result = TrendAssessmentPolicy.evaluate(bottomingBars, cyclesWeakButStabilizing)
    assertEquals(MajorTrend.BOTTOMING, result.major)
    assertTrue(result.buyAllowedOnlyForBottomReversal)
}

@Test fun majorDownBlocksOrdinaryBuy() {
    val result = TrendAssessmentPolicy.evaluate(downBars, weakCycles)
    assertEquals(MajorTrend.DOWN, result.major)
    assertFalse(result.buyAllowed)
    assertFalse(result.tBuyAllowed)
}
```

- [ ] **Step 2: Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.TrendAssessmentPolicyTest` and verify failure.**
- [ ] **Step 3: Implement the shared enums, evidence mapping, five-day no-new-low check, and action-gate fields.**
- [ ] **Step 4: Run the targeted tests and verify pass.**
- [ ] **Step 5: Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.HoldingTContextTest --tests com.example.stockmonitor.MultiTimeframeTrendTest` to verify compatibility.**

### Task 2: High-Position Phase Policy

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/HighPositionPolicy.kt`
- Test: `app/src/test/java/com/example/stockmonitor/HighPositionPolicyTest.kt`

**Interfaces:**
- Consumes: `TrendAssessment`, `PricePosition`, `ChipPeaks`, ATR percent, volume ratio, recent 15/30-minute bars, market/sector regime.
- Produces: `HighPositionPhase`, `HighPositionDecision`, `HighPositionPolicy.evaluate`.

- [ ] **Step 1: Write failing tests for distribution risk, consolidation, second-wave candidate, and confirmed breakout.**

```kotlin
@Test fun elevatedVolumeWithoutPriceProgressIsDistributionRisk() {
    val result = HighPositionPolicy.evaluate(highFlatBars, volumeRatio = 1.8, trend = upward)
    assertEquals(HighPositionPhase.DISTRIBUTION_RISK, result.phase)
    assertFalse(result.allowBuy)
}

@Test fun rangeBreakoutNeedsVolumeAndDualTimeframeApproval() {
    val result = HighPositionPolicy.evaluate(tightRangeBars, volumeRatio = 1.5,
        trend = upward, dualTimeframeAllowed = true)
    assertEquals(HighPositionPhase.SECOND_WAVE_CONFIRMED, result.phase)
    assertTrue(result.allowSecondWaveEntry)
}
```

- [ ] **Step 2: Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.HighPositionPolicyTest` and verify failure.**
- [ ] **Step 3: Implement range detection, volume/price-progress scoring, higher-low checks, and conservative state transitions.**
- [ ] **Step 4: Run the targeted tests and verify pass.**
- [ ] **Step 5: Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.PricePositionTest --tests com.example.stockmonitor.VolumePriceTest`.**

### Task 3: Intraday Trend Gates And New Signals

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/IntradayTactics.kt`
- Test: `app/src/test/java/com/example/stockmonitor/IntradayTacticsTest.kt`

**Interfaces:**
- Consumes: `TrendAssessment`, `HighPositionDecision`, `DualTimeframeDecision`.
- Produces: `IntradaySignalType.BOTTOM_REVERSAL`, `IntradaySignalType.SECOND_WAVE`, and gate-aware `IntradayTactics.evaluate`.

- [ ] **Step 1: Write failing tests proving major-down blocks ordinary T-buy, bottoming only gives light trial, and 30-minute divergence alone cannot buy.**

```kotlin
@Test fun majorDownDoesNotAllowThirtyMinuteDivergenceBuy() {
    val signal = IntradayTactics.evaluate(input.copy(
        trendAssessment = majorDown, highPosition = uncertain))
    assertNull(signal)
}

@Test fun bottomingAllowsOnlyLightBottomReversal() {
    val signal = IntradayTactics.evaluate(input.copy(
        trendAssessment = majorBottoming, highPosition = consolidation))
    assertEquals(IntradaySignalType.BOTTOM_REVERSAL, signal?.type)
    assertTrue(signal!!.body.contains("最多"))
}
```

- [ ] **Step 2: Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.IntradayTacticsTest` and verify failure.**
- [ ] **Step 3: Add the gate fields, signal types, bottom-reversal copy, second-wave copy, and risk-first bypass behavior.**
- [ ] **Step 4: Run the targeted tests and verify pass.**
- [ ] **Step 5: Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.core.IntradayBarsTest --tests com.example.stockmonitor.TradeNotificationTest`.**

### Task 4: Observation And Stock Analysis Integration

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/ObservationPool.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/StockAnalysis.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/ObservationPoolTest.kt`

**Interfaces:**
- Consumes: `TrendAssessment`, `HighPositionDecision`, `ObservationSignal`.
- Produces: trend-aware `ObservationCandidate` eligibility and report text containing trend/phase state.

- [ ] **Step 1: Write failing tests showing major-down cannot promote to BUY_CANDIDATE and bottoming can only produce bottom-reversal observation.**

```kotlin
@Test fun majorDownCannotPromoteObservationCandidate() {
    val result = ObservationPool.evaluate(candidate.copy(trendAssessment = majorDown), bullishSignal)
    assertNotEquals(ObservationStage.BUY_CANDIDATE, result.stage)
}

@Test fun bottomingUsesBottomReversalReason() {
    val result = ObservationPool.evaluate(candidate.copy(trendAssessment = majorBottoming), stabilizedSignal)
    assertEquals(ObservationStage.NEAR_ENTRY, result.stage)
    assertTrue(result.reason.contains("底部反转"))
}
```

- [ ] **Step 2: Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.analysis.ObservationPoolTest` and verify failure.**
- [ ] **Step 3: Add the assessment to observation inputs, enforce eligibility, and render trend/phase text in stock reports.**
- [ ] **Step 4: Run targeted tests and verify pass.**
- [ ] **Step 5: Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.StockAnalysisReferenceTest --tests com.example.stockmonitor.analysis.ObservationBuyAlertTest`.**

### Task 5: Portfolio Sizing And Notification Copy

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/PortfolioSwing.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/TradeNotification.kt`
- Modify: `README.md`
- Test: `app/src/test/java/com/example/stockmonitor/PortfolioSwingTest.kt`

**Interfaces:**
- Consumes: `TrendAssessment`, `HighPositionDecision`, existing position/risk sizing inputs.
- Produces: trend-gated add-position results, 25% bottoming trial cap, and trend-aware notification copy.

- [ ] **Step 1: Write failing tests for no add-position under major-down and 25% trial cap under bottoming.**

```kotlin
@Test fun majorDownAllocatesNoNewCapital() {
    val plan = PortfolioSwing.plan(state.copy(trendAssessment = majorDown))
    assertEquals(0.0, plan.addQuantity)
}

@Test fun bottomingTrialIsCappedAtQuarterInitialPosition() {
    val plan = PortfolioSwing.plan(state.copy(trendAssessment = majorBottoming))
    assertTrue(plan.addQuantity <= initialPositionQuantity * 0.25)
}
```

- [ ] **Step 2: Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.PortfolioSwingTest` and verify failure.**
- [ ] **Step 3: Apply trend and high-position gates to capital allocation and T quantity calculation.**
- [ ] **Step 4: Update notification copy to lead with trend/phase state and never claim a guaranteed future move.**
- [ ] **Step 5: Run targeted tests and verify pass.**

### Task 6: Full Regression And Documentation

**Files:**
- Modify: `README.md`
- Test: full unit suite and Android assembly

**Interfaces:**
- Consumes: all new policy and integration APIs.
- Produces: documented behavior and a green build.

- [ ] **Step 1: Add README rules for short-term/major trend, bottoming, high-position phases, and action gates.**
- [ ] **Step 2: Run `./gradlew testDebugUnitTest` and require zero failures/errors/skips.**
- [ ] **Step 3: Run `./gradlew assembleDebug --offline` and require a successful build.**
- [ ] **Step 4: Review notification and report text for guaranteed-forecast language and replace it with evidence-based wording.**
- [ ] **Step 5: Record any data-source limitation, especially chip estimates and incomplete minute bars, in the README.**

