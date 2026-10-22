# Fund Flow And Limit-Up Promotion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add conservative fund-flow evidence to existing judgments and let qualifying sealed limit-up stocks remain in the observation pool until a fresh open-board opportunity can be confirmed.

**Architecture:** Add a batched East Money fund-flow client and pure single-day/3–5-day assessment policies, then feed their bounded evidence into stock analysis, limit reports, and observation promotion. Intraday reminder tactics remain unchanged. Add a continuous limit-up candidate mode whose sealed state pauses timeout and whose open-board state reuses the existing two-round leader confirmation. Keep A-share sell notifications T+1-aware and describe same-day rolling as “底仓做T”, never “T+0”.

**Tech Stack:** Kotlin, Android programmatic UI, JUnit 4, org.json, East Money JSON/JSONP, existing `Http`, `Repo`, `MonitorService`, `Store`, and JSON persistence patterns.

**Spec:** `docs/superpowers/specs/2026-10-22-fund-flow-evidence-design.md`, `docs/superpowers/specs/2026-10-22-limit-up-observation-promotion-design.md`

## Global Constraints

- Kotlin/JVM target 17; Android minSdk 26.
- Fund flow is auxiliary evidence and must not independently create a buy, sell, or elimination.
- Fund-flow history keeps at most five trading-day snapshots per stock; promotion uses both current-day and recent 3/5-day evidence.
- Fund flow may affect observation-pool promotion and analysis wording only; `IntradayTactics` and time-sensitive intraday buy/sell/T alerts remain unchanged.
- A-share shares bought today are not sellable today; “底仓做T” uses previously held sellable shares and actual sellable quantity.
- Sealed limit-up stocks never enter `NEAR_ENTRY` or `BUY_CANDIDATE`.
- Missing fund-flow fields render as `—`; never infer missing order-size buckets.
- Call the metric “大单/超大单主动买卖净流向” or “主力净流向（按订单规模归类）”; never claim verified institutional identity.
- Do not create Git commits; the user will review and test the working tree.

---

### Task 1: Fund-flow model and East Money parser

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/Models.kt`
- Create: `app/src/main/java/com/example/stockmonitor/core/EastMoneyFundFlowClient.kt`
- Test: `app/src/test/java/com/example/stockmonitor/core/EastMoneyFundFlowParserTest.kt`

**Interfaces:**
- Consumes: `Http.getText`.
- Produces: `data class FundFlow(code, name, asOfMs, amountYuan, mainNetYuan, mainNetPct, superLargeNetYuan, superLargeNetPct, largeNetYuan, largeNetPct, mediumNetYuan, mediumNetPct, smallNetYuan, smallNetPct)`, `LimitStock.fundFlow: FundFlow? = null`, and `EastMoneyFundFlowClient.flows(codes: List<String>): Map<String, FundFlow>`.

- [ ] **Step 1: Write failing parser tests for valid values, `-`/missing values, and batched `secids`.**

```kotlin
@Test fun parsesMainAndOrderSizeBuckets() {
    val parsed = EastMoneyFundFlowClient.parse(
        """{"data":{"diff":[{"f12":"000001","f14":"平安银行","f6":123000000.0,"f62":13039016.0,"f184":55.57,"f66":21424170.0,"f69":31.2,"f72":-8385154.0,"f75":-12.1,"f78":2100000.0,"f81":3.1,"f84":-3200000.0,"f87":-4.6}]}}"""
    )
    assertEquals(13039016.0, parsed.getValue("000001").mainNetYuan)
    assertEquals(55.57, parsed.getValue("000001").mainNetPct)
    assertEquals(-8385154.0, parsed.getValue("000001").largeNetYuan)
}

@Test fun missingBucketStaysNull() {
    val parsed = EastMoneyFundFlowClient.parse(
        """{"data":{"diff":[{"f12":"600293","f14":"南矿集团","f6":"-","f62":"-","f184":"-"}]}}"""
    )
    assertNull(parsed.getValue("600293").mainNetYuan)
}
```

- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.core.EastMoneyFundFlowParserTest` and verify the parser tests fail because the contract is missing.**
- [ ] **Step 3: Add `FundFlow`, market-aware `secid` conversion, 50-code batching, nullable numeric parsing, and JSON/JSONP unwrapping. Request `https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&secids=<batch>&fields=f12,f14,f6,f62,f184,f66,f69,f72,f75,f78,f81,f84,f87`.**
- [ ] **Step 4: Run the targeted parser tests and require all tests to pass.**

### Task 2: Pure fund-flow assessment

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/FundFlowPolicy.kt`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/FundFlowPolicyTest.kt`

**Interfaces:**
- Consumes: `FundFlow`.
- Produces: `enum class FundFlowTone { SUPPORTIVE, DIVERGENT, RISK, ABSORPTION_WATCH, NEUTRAL, UNAVAILABLE }`, `data class FundFlowAssessment(tone, strong, lowConfidence, reason)`, `FundFlowPolicy.assess(changePct: Double?, flow: FundFlow?)`, and `FundFlowPolicy.scoreDelta(assessment): Int`.

- [ ] **Step 1: Write failing table-driven tests for all six tones, the ±3% boundary, strong ±10%, and the 5,000,000-yuan confidence threshold.**

```kotlin
@Test fun risingPriceWithOutflowIsDivergent() {
    val assessment = FundFlowPolicy.assess(2.0, flow(mainNetPct = -4.0))
    assertEquals(FundFlowTone.DIVERGENT, assessment.tone)
    assertEquals(-1, FundFlowPolicy.scoreDelta(assessment))
}

@Test fun lowConfidenceSupportiveDoesNotChangeScore() {
    val assessment = FundFlowPolicy.assess(2.0, flow(mainNetPct = 5.0, amountYuan = 4_999_999.0))
    assertTrue(assessment.lowConfidence)
    assertEquals(0, FundFlowPolicy.scoreDelta(assessment))
}
```

- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.analysis.FundFlowPolicyTest` and verify failure.**
- [ ] **Step 3: Implement the policy exactly from the spec without creating trade signals.**
- [ ] **Step 4: Run targeted tests and the existing analysis policy tests.**

### Task 3: Stock analysis integration

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/StockAnalysis.kt`
- Test: `app/src/test/java/com/example/stockmonitor/StockAnalysisReferenceTest.kt`

**Interfaces:**
- Consumes: `FundFlow`, `FundFlowAssessment`, `FundFlowPolicy`.
- Produces: optional `fundFlow` on `StockAnalysis.analyze`; `TechnicalResult.fundFlow`, `fundFlowAssessment`, and `fundFlowNote`. `IntradayTactics.Input` remains unchanged.

- [ ] **Step 1: Write failing tests proving nullable input preserves old output and supportive evidence adds at most one score point with report evidence.**

```kotlin
@Test fun fundFlowCanChangeScoreByAtMostOne() {
    val base = StockAnalysis.analyze(bars, code, name, quote)
    val supported = StockAnalysis.analyze(bars, code, name, quote, fundFlow = supportive)
    assertTrue(supported.score - base.score in -1..1)
}
```

- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.StockAnalysisReferenceTest` and verify failure.**
- [ ] **Step 3: Add nullable fields, bounded score adjustment, and conditional report evidence without modifying `IntradayTactics`.**
- [ ] **Step 4: Run the new tests plus `TechnicalSummaryTest`, `HoldingTPolicyTest`, and `TradeNotificationTest` to prove intraday behavior is unchanged.**

### Task 4: Observation promotion and continuous sealed state

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/Models.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/ObservationPoolJson.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/ObservationPool.kt`
- Create: `app/src/main/java/com/example/stockmonitor/analysis/LimitCandidatePolicy.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/ObservationEligibility.kt`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/LimitPromotionPolicyTest.kt`

**Interfaces:**
- Consumes: `LimitStock`, `FundFlowAssessment`, existing `ObservationSignal`.
- Produces: `ObservationStrategy.LIMIT_UP_CONTINUATION`, `ObservationCandidate.continuousObservation: Boolean`, `ObservationSignal.fundFlowWindow: FundFlowWindowAssessment?`, `ObservationSection.LIMIT_CONTINUOUS`, `LimitCandidatePolicy.eligible(stock: LimitStock): Boolean`, and continuous sealed-state handling in `ObservationPool.evaluate`.

- [ ] **Step 1: Write failing admission, sealed-timeout, re-seal reset, open-board confirmation, fund-flow gate, and JSON compatibility tests.**

```kotlin
@Test fun sealedContinuousCandidateDoesNotTimeOut() {
    val candidate = sealedCandidate(observedDays = ObservationPool.MAX_OBSERVED_DAYS)
    val result = ObservationPool.evaluate(candidate, sealedSignal)
    assertEquals(ObservationStage.OBSERVING, result.stage)
}

@Test fun sealedCandidateCannotBecomeBuyCandidate() {
    val result = ObservationPool.evaluate(sealedCandidate, sealedSignal.copy(canBuy = true, confirmedEntry = true))
    assertNotEquals(ObservationStage.BUY_CANDIDATE, result.stage)
    assertTrue(result.reason.contains("开板后重新确认"))
}
```

- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.analysis.LimitPromotionPolicyTest` and verify failure.**
- [ ] **Step 3: Add model/JSON compatibility and implement admission plus the continuous sealed state machine.**
- [ ] **Step 4: Run targeted tests and the full observation tests.**

### Task 5: Fetch, cache, reports, and UI integration

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/LimitReport.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/MarketFragment.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/ScreenFragment.kt`
- Test: `app/src/test/java/com/example/stockmonitor/FundFlowAndLimitPoolUiTest.kt`

**Interfaces:**
- Consumes: `EastMoneyFundFlowClient.flows`, `FundFlowPolicy`, `LimitCandidatePolicy`, `ObservationPool`.
- Produces: cached `Repo.fundFlows`, enriched `LimitStock`/limit reports, fund-flow notes in holding/watchlist reports, and a “涨停持续观察” section.

- [ ] **Step 1: Write failing structural tests for main net flow in limit rows, explicit low-confidence copy, and the sealed-limit section before ordinary observations.**

```kotlin
@Test fun limitLineShowsMainFlowAfterSealMetrics() {
    val line = LimitReport.stockLine(limitStock.copy(fundFlow = flow))
    val sealIndex = line.indexOf("封单/流通市值")
    val flowIndex = line.indexOf("主力净流向")
    assertTrue(flowIndex > sealIndex)
}

@Test fun sealedLimitItemsAppearInDedicatedSection() {
    val sections = ObservationEligibility.sections(candidates, quotes)
    assertNotNull(sections[ObservationSection.LIMIT_CONTINUOUS])
}
```

- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.FundFlowAndLimitPoolUiTest` and verify failure.**
- [ ] **Step 3: Add batched refresh with stale-cache retention, report formatting, fund-flow evidence, candidate ingestion, and the dedicated UI section.**
- [ ] **Step 4: Run targeted tests, then `./gradlew :app:testDebugUnitTest`.**
- [ ] **Step 5: Run `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:assembleDebug --offline` and require a successful build.**

### Task 6: Documentation and regression

**Files:**
- Modify: `README.md`
- Test: full unit suite and debug assembly

**Interfaces:**
- Consumes: all new policy, persistence, service, and UI behavior.
- Produces: documented thresholds and strategy boundaries.

- [ ] **Step 1: Document order-size classification caveats, fund-flow thresholds, promotion gating, and sealed-limit continuous observation.**
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest assembleDebug` and require zero failures/errors.**
- [ ] **Step 3: Run `git diff --check` and verify no unrelated files were changed.**
