# Portfolio Focus Allocation Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将持仓波段追加从“只有一只首选”改为“最多三只重点改善持仓”，同时按真实剩余现金、单股仓位上限和风险额度顺序分配追加数量。

**Architecture:** 保留 `PortfolioSwing` 作为纯策略入口，在候选排序后依次为最多三只通过条件的持仓分配预算。每次分配都从剩余现金中扣除预计交易金额，候补只保留结构化原因和 0 股结果；`SwingPlan` 的报告和 UI 文案根据实际获得资金的 1～3 只重点持仓生成。

**Tech Stack:** Kotlin, JUnit 4, 现有 `PortfolioSwing`、`SwingDecision`、`SwingPlan`、`Portfolio` 和组合策略测试。

**Spec:** `/Users/stephen/Desktop/temp.txt` 中 2026-11-16 `portfolio_focus.py` 与持仓追加连续性迁移说明。

## Global Constraints

- 单次分析最多给 3 只持仓分配新增资金。
- 分配总额不得超过可用现金、配置总资金、单股最大仓位和单笔风险额度。
- 排序必须稳定：风险和数据有效性先于评分，评分相同按持仓市值和代码稳定排序。
- 不因深套、基本面红灯、数据过期、风险未确认或价格离开窗口而追加资金。
- 追加数量必须继续遵守 A 股整手/北交所数量规则。
- 保留现有 `SwingPlan.validatedFor` 的持仓快照保护，持仓或资金变化后清空全部追加数量。
- 不重置、清理或覆盖工作树中已有的未提交修改；不修改无关失败测试。

---

### Task 1: Add failing tests for three-focus allocation

**Files:**
- Modify: `app/src/test/java/com/example/stockmonitor/PortfolioSwingTest.kt`

- [x] **Step 1: Write the failing tests.**

```kotlin
@Test
fun allocatesAtMostThreeQualifiedHoldings() {
    val config = portfolio.copy(
        availableCapital = 90_000.0,
        positions = mutableListOf(stock, second, third, fourth),
    )
    val result = PortfolioSwing.evaluate(config, evidenceForAllFour, market, date, now)

    assertEquals(3, result.rows.count { it.addQuantity > 0.0 })
    assertTrue(result.rows.count { it.addQuantity > 0.0 } <= 3)
}

@Test
fun laterFocusUsesOnlyRemainingCash() {
    val result = PortfolioSwing.evaluate(portfolioWithFourQualifiedHoldings, evidenceForAllFour, market, date, now)

    assertTrue(result.rows.filter { it.addQuantity > 0.0 }.sumOf { it.estimatedCash } <= result.allocationCapital!! + 0.01)
}

@Test
fun fourthQualifiedHoldingRemainsAZeroQuantityCandidate() {
    val result = PortfolioSwing.evaluate(portfolioWithFourQualifiedHoldings, evidenceForAllFour, market, date, now)
    val fourthRow = result.rows.first { it.code == fourth.code }

    assertEquals(0.0, fourthRow.addQuantity, 0.0)
    assertTrue(fourthRow.reason.contains("3"))
}
```

- [x] **Step 2: Run the focused tests and confirm the current one-focus behavior fails the new expectations.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.PortfolioSwingTest`

Expected: the new tests fail because the current implementation sets `allocated = true` after the first funded row.

### Task 2: Implement bounded multi-focus allocation

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/PortfolioSwing.kt`
- Modify: `app/src/test/java/com/example/stockmonitor/PortfolioSwingTest.kt`

**Interfaces:**
- Add `const val MAX_FOCUS_POSITIONS = 3` to `PortfolioSwing`.
- Change the internal allocation helper to accept an explicit `cashBudget: Double`, so each next row sees the remaining budget instead of the original portfolio cash.
- Keep `SwingPlan.preferredCode` as the first funded row for compatibility; add `focusedRows` as the rows with `addQuantity > 0`.

- [x] **Step 1: Implement sequential budget allocation.**

Build assessed candidates first, sort them deterministically, then iterate with `remainingCash` and `focusCount`. For each eligible row, call `allocate(row, position, allocationPortfolio, remainingCash)`. Subtract `estimatedCash` only after a positive quantity is produced. Once three rows are funded, return later qualified rows with zero quantities and reason `技术条件通过，本轮最多聚焦3只，保留候补`.

- [x] **Step 2: Update report labels for 1–3 funded rows.**

`SwingPlan.headline()` should mention all funded names and `reportLines()` should label them `重点1` through `重点3`; rows with zero quantity continue to say `追加0股`. Keep the existing “不建议新增买入” text when no row is funded.

- [x] **Step 3: Run the focused tests and existing swing regression tests.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.PortfolioSwingTest`

Expected: all three-focus tests and the existing cash, risk, T+1 and snapshot validation tests pass.

### Task 3: Expose the focus count in the portfolio UI and index

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/ui/PortfolioBriefCard.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/PortfolioFragment.kt`
- Modify: `docs/PROJECT_INDEX.md`

- [x] **Step 1: Add a focused summary test or formatter assertion.**

```kotlin
@Test
fun headlineListsAllFundedFocusHoldings() {
    assertTrue(plan.headline().contains("重点"))
    assertTrue(plan.reportLines().any { it.contains("重点1") })
}
```

- [x] **Step 2: Display the actual focus count and candidate reasons.**

Use `SwingPlan.focusedRows.size` in the brief card and keep each row’s `action()` tied to its own allocated quantity. Do not infer the count from the number of holdings or from the number of technically qualified rows.

- [x] **Step 3: Run the focused UI-adjacent tests and hygiene checks.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.PortfolioSwingTest --tests com.example.stockmonitor.PortfolioAnalysisTest && git diff --check`

Expected: tests pass and no whitespace errors are reported.
