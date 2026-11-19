# Daily Advice Continuity Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 macOS 项目的同日建议计划、持仓净数量反馈、通知事务状态和跨股票买入互斥迁移到 Android，使建议在当天重启、部分执行和通知异常后仍保持一致。

**Architecture:** 先用纯 Kotlin 策略层描述建议计划、数量反馈和买入计划互斥，再由 `Store` 负责 JSON 持久化与原子 reserve/rollback。`MonitorService` 只负责生成和发送建议，`Dialogs` 只报告用户保存后的数量变化，所有状态迁移通过策略层计算，避免 UI 或通知代码自行推断成交。

**Tech Stack:** Kotlin, Android `SharedPreferences`, `org.json`, JUnit 4,现有 `Store`、`TradeLotLedger`、`TradeNotification` 和 `Notifier`。

**Spec:** `/Users/stephen/Desktop/temp.txt` 中 2026-11-16 macOS 同日建议连续性、持仓反馈和买入资金互斥迁移说明。

## Global Constraints

- 只处理同一交易日的建议计划；跨交易日不得直接复用旧计划。
- 持仓数量不变、只改成本、移除监控都不能被解释为成交反馈。
- 数量反馈只使用持仓净变化；反向或不连续变化必须进入复核状态。
- 未完成或送达未知的买入计划会锁住同日其它股票的普通买入和持仓加仓计划。
- `reserve` 必须在同一把锁内再次检查互斥，失败或通知发送失败必须可 rollback。
- 送达未知和存储异常时暂停正常操作建议；不能把“通知已调用”当作已成交。
- 不重置、清理或覆盖工作树中已有的用户改动；不修改与本阶段无关的失败测试。
- 不创建提交或分支；所有新代码必须先有针对性失败测试，再写最小实现。

---

### Task 1: Define the pure daily advice model and state transitions

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/DailyAdvicePolicy.kt`
- Create: `app/src/test/java/com/example/stockmonitor/analysis/DailyAdvicePolicyTest.kt`

**Interfaces:**
- Produces `AdviceDirection`, `AdviceKind`, `AdvicePlanState`, `DailyAdvicePlan`, `AdvicePreparation`, `QuantityFeedback` and pure functions `prepare`, `applyQuantityFeedback`, `canReserveBuy`, `rollback`, `markSent`.
- `DailyAdvicePlan` must carry `date`, `code`, `direction`, `kind`, `targetQuantity`, `executedQuantity`, `lastKnownQuantity`, `state`, `notificationState`, `reviewReason` and timestamps needed by persistence.

- [x] **Step 1: Write failing tests for the state model.**

```kotlin
@Test
fun unchangedQuantityDoesNotConfirmExecution() {
    val plan = DailyAdvicePolicy.prepare(
        date = "2026-11-16",
        code = "sz000001",
        direction = AdviceDirection.SELL,
        kind = AdviceKind.REDUCE,
        targetQuantity = 400.0,
        currentQuantity = 1_200.0,
        existing = null,
    ).plan

    val updated = DailyAdvicePolicy.applyQuantityFeedback(
        plan = plan,
        previousQuantity = 1_200.0,
        currentQuantity = 1_200.0,
        feedbackAtMs = 1L,
    )

    assertEquals(AdvicePlanState.PREPARED, updated.state)
    assertEquals(0.0, updated.executedQuantity, 0.001)
}

@Test
fun partialReductionUsesOnlyNetDecreaseAndCompletesAtTarget() {
    val plan = DailyAdvicePolicy.prepare(
        date = "2026-11-16",
        code = "sz000001",
        direction = AdviceDirection.SELL,
        kind = AdviceKind.REDUCE,
        targetQuantity = 400.0,
        currentQuantity = 1_200.0,
        existing = null,
    ).plan

    val partial = DailyAdvicePolicy.applyQuantityFeedback(plan, 1_200.0, 1_000.0, 1L)
    val complete = DailyAdvicePolicy.applyQuantityFeedback(partial, 1_000.0, 800.0, 2L)

    assertEquals(200.0, partial.executedQuantity, 0.001)
    assertEquals(AdvicePlanState.PARTIALLY_EXECUTED, partial.state)
    assertEquals(400.0, complete.executedQuantity, 0.001)
    assertEquals(AdvicePlanState.COMPLETED, complete.state)
}

@Test
fun costOnlyChangeDoesNotCreateExecutionFeedback() {
    val updated = DailyAdvicePolicy.applyQuantityFeedback(plan, 1_200.0, 1_200.0, 2L)
    assertEquals(0.0, updated.executedQuantity, 0.001)
}

@Test
fun reverseQuantityChangeRequiresReview() {
    val updated = DailyAdvicePolicy.applyQuantityFeedback(plan, 1_200.0, 1_300.0, 3L)
    assertEquals(AdvicePlanState.REVIEW_REQUIRED, updated.state)
}

@Test
fun oldDatePlanCannotBeReusedForToday() {
    val preparation = DailyAdvicePolicy.prepare(
        date = "2026-11-16",
        code = "sz000001",
        direction = AdviceDirection.BUY,
        kind = AdviceKind.NORMAL_BUY,
        targetQuantity = 200.0,
        currentQuantity = 0.0,
        existing = oldPlan,
    )
    assertTrue(!preparation.reused)
}
```

- [x] **Step 2: Run the focused test to verify it fails.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.analysis.DailyAdvicePolicyTest`

Expected: FAIL because `DailyAdvicePolicy` and its plan types do not exist.

- [x] **Step 3: Implement the minimal pure state machine.**

Implement direction-aware net feedback: a `SELL` plan consumes only `previousQuantity - currentQuantity` when positive; a `BUY` plan consumes only `currentQuantity - previousQuantity` when positive. Equal quantities return the original plan unchanged. A positive delta in the opposite direction sets `REVIEW_REQUIRED` and records a non-empty reason. Cap `executedQuantity` at `targetQuantity`; transition to `PARTIALLY_EXECUTED` until the target is reached, then `COMPLETED`. Existing plans may be reused only when date, code, direction and kind match; otherwise create a new `PREPARED` plan.

- [x] **Step 4: Run the focused test to verify it passes.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.analysis.DailyAdvicePolicyTest`

Expected: PASS.

### Task 2: Add same-day buy mutual exclusion and notification transaction states

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/DailyAdvicePolicy.kt`
- Modify: `app/src/test/java/com/example/stockmonitor/analysis/DailyAdvicePolicyTest.kt`

**Interfaces:**
- `canReserveBuy(plans: List<DailyAdvicePlan>, date: String, code: String): Boolean` rejects a new buy when another code has an unfinished `BUY` plan in `PREPARED`, `RESERVED`, `SENT`, `PARTIALLY_EXECUTED` or `DELIVERY_UNKNOWN`.
- `reserve(plan, plans, date, code): ReserveResult` rechecks the list under the caller's lock and returns either a reserved plan or a rejection reason.
- `markSent(plan, sentAtMs): DailyAdvicePlan`, `rollback(plan, reason): DailyAdvicePlan`, and `markDeliveryUnknown(plan, reason): DailyAdvicePlan` preserve the plan for retry or review.

- [x] **Step 1: Add failing tests for the mutex and transaction lifecycle.**

```kotlin
@Test
fun anotherStockUnfinishedBuyBlocksNewBuy() {
    assertTrue(!DailyAdvicePolicy.canReserveBuy(listOf(codeAPlan), "2026-11-16", "sh600000"))
}

@Test
fun holdingAddAndNormalBuyShareTheSameLock() {
    assertTrue(!DailyAdvicePolicy.canReserveBuy(listOf(holdingAddPlan), "2026-11-16", "sh600000"))
}

@Test
fun completedBuyDoesNotBlockAnotherStock() {
    assertTrue(DailyAdvicePolicy.canReserveBuy(listOf(completedPlan), "2026-11-16", "sh600000"))
}

@Test
fun failedNotificationCanRollbackReservedPlan() {
    val rolledBack = DailyAdvicePolicy.rollback(reservedPlan, 4L, "通知发送失败")
    assertEquals(AdvicePlanState.PREPARED, rolledBack.state)
}

@Test
fun deliveryUnknownRemainsBlocked() {
    assertTrue(!DailyAdvicePolicy.canReserveBuy(listOf(unknownPlan), "2026-11-16", "sh600000"))
}
```

- [x] **Step 2: Run the focused test and confirm the new cases fail.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.analysis.DailyAdvicePolicyTest`

Expected: FAIL on the missing mutual-exclusion and transaction behaviors.

- [x] **Step 3: Implement the smallest state transitions.**

Treat `HOLDING_ADD` and `NORMAL_BUY` as the same `BUY` direction for locking. Keep the current code eligible to reuse its own pending plan while rejecting a different code. `rollback` returns `PREPARED` with the notification status reset to `NOT_SENT`; `markDeliveryUnknown` preserves the reservation and makes `canReserveBuy` return false.

- [x] **Step 4: Run the focused test and confirm it passes.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.analysis.DailyAdvicePolicyTest`

Expected: PASS.

### Task 3: Persist daily advice plans and make reserve/rollback atomic

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/core/DailyAdviceJson.kt`
- Create: `app/src/test/java/com/example/stockmonitor/core/DailyAdviceJsonTest.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/Store.kt`
- Modify: `app/src/test/java/com/example/stockmonitor/core/StoreDailyAdviceTest.kt`

**Interfaces:**
- `DailyAdviceJson.encode(plans: List<DailyAdvicePlan>): String` and `decode(json: String?): List<DailyAdvicePlan>` round-trip all fields and tolerate missing/invalid stored data by returning an empty list.
- `Store.dailyAdvicePlans: List<DailyAdvicePlan>` reads/writes the encoded list for the current app installation.
- `Store.prepareDailyAdvice(...)`, `Store.reserveDailyAdvice(...)`, `Store.commitDailyAdvice(...)` and `Store.rollbackDailyAdvice(...)` serialize read-check-write operations under one private lock.

- [ ] **Step 1: Write failing JSON and restart tests.**

```kotlin
@Test
fun dailyAdviceRoundTripsAllState() {
    assertEquals(source, DailyAdviceJson.decode(DailyAdviceJson.encode(source)))
}

@Test
fun malformedDailyAdviceStorageFallsBackToEmpty() {
    assertTrue(DailyAdviceJson.decode("not-json").isEmpty())
}

@Test
fun reserveRechecksOtherStockInsideTheSameCriticalSection() {
    assertEquals(1, results.count { it.accepted })
}

@Test
fun rollbackAfterSendFailureMakesPlanRetryable() {
    assertEquals(AdvicePlanState.PREPARED, DailyAdvicePolicy.rollback(reserved, 1L).state)
}
```

- [ ] **Step 2: Run the new core tests and verify failure.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.core.DailyAdviceJsonTest --tests com.example.stockmonitor.core.StoreDailyAdviceTest`

Expected: FAIL because the JSON codec, preference key and Store transaction methods do not exist.

- [x] **Step 3: Implement JSON persistence and the synchronized Store transaction.**

Use a new `SharedPreferences` key alongside the existing `Store` keys. Persist one JSON array containing only same-day plans retained by the policy. Use `synchronized(dailyAdviceLock)` around every read-modify-write reserve/commit/rollback operation; do not use the existing observation alert key as a substitute. Preserve plans across process restarts and return a safe empty list for malformed entries.

- [ ] **Step 4: Run the core tests and verify they pass.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.core.DailyAdviceJsonTest --tests com.example.stockmonitor.core.StoreDailyAdviceTest`

Expected: PASS.

### Task 4: Feed real holding edits into quantity feedback

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/TradeLots.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/Dialogs.kt`
- Create or modify: `app/src/test/java/com/example/stockmonitor/ui/DailyAdviceHoldingFeedbackTest.kt`

**Interfaces:**
- `TradeLotLedger.syncPositionEdit` keeps its existing batch and bookkeeping semantics; it must expose enough before/after quantity information for the UI layer to call `Store.applyDailyAdviceQuantityFeedback`.
- `Store.applyDailyAdviceQuantityFeedback(code, previousQuantity, currentQuantity, feedbackAtMs)` updates only plans for the current advice date.

- [ ] **Step 1: Add failing tests for the UI feedback rules.**

```kotlin
@Test
fun manualQuantityDecreaseFeedsOnlyTheNetDecrease() {
    assertEquals(200.0, DailyAdvicePolicy.applyQuantityFeedback(plan, 1_200.0, 1_000.0, 1L).executedQuantity, 0.001)
}

@Test
fun costEditWithSameQuantityDoesNotFeedPlan() {
    assertEquals(0.0, DailyAdvicePolicy.applyQuantityFeedback(plan, 1_200.0, 1_200.0, 1L).executedQuantity, 0.001)
}

@Test
fun removingMonitoringDoesNotFeedACompletion() {
    assertEquals(AdvicePlanState.SENT, plan.state)
}

@Test
fun reverseEditMarksPlanForReview() {
    assertEquals(AdvicePlanState.REVIEW_REQUIRED, DailyAdvicePolicy.applyQuantityFeedback(plan, 1_200.0, 1_300.0, 1L).state)
}
```

- [ ] **Step 2: Run the focused test to confirm failure.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.ui.DailyAdviceHoldingFeedbackTest`

Expected: FAIL because the successful holding-edit callback does not update advice plans.

- [x] **Step 3: Call the feedback method only after a successful quantity save.**

Capture the old position quantity before `syncPositionInput`/`syncPositionEdit`, save the portfolio and trade book, then call the Store feedback method with the new quantity. Do not call it for cost-only changes or monitoring removal. Keep transaction and real trade records unchanged.

- [ ] **Step 4: Run the focused feedback test and the existing trade-lot tests.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.ui.DailyAdviceHoldingFeedbackTest --tests com.example.stockmonitor.core.TradeLotsTest`

Expected: PASS for the new feedback cases and all existing trade-lot tests.

### Task 5: Connect monitor notification outcomes to advice state

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/Notifier.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/TradeNotification.kt`
- Create or modify: `app/src/test/java/com/example/stockmonitor/monitor/DailyAdviceNotificationFlowTest.kt`

**Interfaces:**
- Advice generation maps a guarded action to `AdviceKind` and calls `Store.prepareDailyAdvice` before attempting a notification.
- Notification success calls `commitDailyAdvice`/`markSent`; notification failure calls `rollbackDailyAdvice`; an unknown delivery result calls `markDeliveryUnknown`.
- Advice generation must skip a new buy when `Store.reserveDailyAdvice` rejects it and retain the reason for the UI/report.

- [ ] **Step 1: Add failing flow tests.**

```kotlin
@Test
fun failedNotificationDoesNotBecomeAnExecutedTrade() {
    assertEquals(0.0, DailyAdvicePolicy.rollback(reserved, 1L).executedQuantity, 0.001)
}

@Test
fun notificationSuccessMarksSentButNotCompleted() {
    assertEquals(AdvicePlanState.SENT, DailyAdvicePolicy.markSent(reserved, 1L).state)
}

@Test
fun unknownDeliverySuppressesNormalBuyInstruction() {
    assertTrue(!DailyAdvicePolicy.canReserveBuy(listOf(unknown), "2026-11-16", "sh600000"))
}

@Test
fun sameDayBuyFromAnotherStockIsSkipped() {
    assertTrue(!DailyAdvicePolicy.canReserveBuy(listOf(otherStockBuy), "2026-11-16", "sh600000"))
}
```

- [ ] **Step 2: Run the flow tests and verify failure.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.monitor.DailyAdviceNotificationFlowTest`

Expected: FAIL because monitor and notifier currently have no daily-plan lifecycle calls.

- [x] **Step 3: Wire the lifecycle without making notification code infer fills.**

Use the existing guarded trade action as the source of direction and intent. Persist a plan before sending, reserve buy plans atomically, and update only the notification state after the send result. Leave `executedQuantity` at zero until a later holding quantity edit reports a net change.

- [ ] **Step 4: Run the focused flow tests and related notification tests.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.monitor.DailyAdviceNotificationFlowTest --tests com.example.stockmonitor.TradeNotificationTest`

Expected: PASS for the new lifecycle tests and all existing trade-notification tests.

### Task 6: Show plan status and feedback in the portfolio surface

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/ui/PortfolioFragment.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/PortfolioBriefCard.kt`
- Create or modify: `app/src/test/java/com/example/stockmonitor/ui/DailyAdvicePortfolioCopyTest.kt`

**Interfaces:**
- Add a pure formatter `DailyAdvicePolicy.displayText(plan): String` for pending, partial, completed, review and delivery-unknown states.
- Portfolio UI reads the persisted plan for each holding and displays the original target, executed quantity, remaining quantity and review/block reason.

- [ ] **Step 1: Write failing formatter/UI-copy tests.**

```kotlin
@Test
fun partialPlanShowsOriginalTargetAndRemainingQuantity() {
    assertTrue(DailyAdvicePolicy.displayText(partial).contains("剩余200"))
}

@Test
fun reviewPlanShowsReasonAndDoesNotShowNewOppositeInstruction() {
    assertTrue(DailyAdvicePolicy.displayText(review).contains("待复核"))
}
```

- [ ] **Step 2: Run the focused test and confirm failure.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.ui.DailyAdvicePortfolioCopyTest`

Expected: FAIL because the portfolio surface has no daily-advice status formatter.

- [x] **Step 3: Implement concise status display and attach it to the existing holding rows/cards.**

Keep the original plan visible when a new observation conflicts with it. Display a review or delivery-unknown state as a blocker, not as an executable buy/sell instruction. Do not change unrelated portfolio ranking or risk copy.

- [ ] **Step 4: Run UI-copy tests and the existing portfolio target tests.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.ui.DailyAdvicePortfolioCopyTest --tests com.example.stockmonitor.PortfolioAnalysisTest --tests com.example.stockmonitor.PortfolioPriorityPolicyTest --tests com.example.stockmonitor.PortfolioSwingTest`

Expected: PASS for the new copy and the existing portfolio target tests.

### Task 7: Full verification and migration note

**Files:**
- Modify: `docs/PROJECT_INDEX.md`
- Modify: `README.md` only if the index needs a new entry point.

- [ ] **Step 1: Run the complete targeted regression set.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.analysis.DailyAdvicePolicyTest --tests com.example.stockmonitor.core.DailyAdviceJsonTest --tests com.example.stockmonitor.core.StoreDailyAdviceTest --tests com.example.stockmonitor.core.TradeLotsTest --tests com.example.stockmonitor.monitor.DailyAdviceNotificationFlowTest --tests com.example.stockmonitor.TradeNotificationTest --tests com.example.stockmonitor.PortfolioAnalysisTest --tests com.example.stockmonitor.PortfolioPriorityPolicyTest --tests com.example.stockmonitor.PortfolioSwingTest`

Expected: all selected tests pass; pre-existing failures outside the touched modules remain separately identified.

- [x] **Step 2: Run repository hygiene checks.**

Run: `git diff --check`

Expected: no whitespace errors.

- [x] **Step 3: Update the AI navigation index with the new implementation map.**

Document the new `DailyAdvicePolicy`, `DailyAdviceJson`, Store lifecycle methods, holding-edit feedback entry point and notification flow. Keep the index concise so future AI sessions can start from the relevant files without re-scanning the entire project.

- [ ] **Step 4: Review the final diff without reverting unrelated work.**

Run: `git diff --stat -- docs/superpowers/plans/2026-11-16-daily-advice-continuity-android.md app/src/main/java/com/example/stockmonitor/analysis/DailyAdvicePolicy.kt app/src/main/java/com/example/stockmonitor/core/DailyAdviceJson.kt app/src/main/java/com/example/stockmonitor/core/Store.kt app/src/main/java/com/example/stockmonitor/core/TradeLots.kt app/src/main/java/com/example/stockmonitor/ui/Dialogs.kt app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt app/src/main/java/com/example/stockmonitor/monitor/Notifier.kt app/src/main/java/com/example/stockmonitor/analysis/TradeNotification.kt app/src/main/java/com/example/stockmonitor/ui/PortfolioFragment.kt app/src/main/java/com/example/stockmonitor/ui/PortfolioBriefCard.kt docs/PROJECT_INDEX.md`

Expected: only the planned files are listed for this phase, alongside the already existing unrelated worktree changes.
