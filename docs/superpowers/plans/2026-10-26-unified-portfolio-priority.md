# Unified Portfolio Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android and macOS portfolio summaries use the same decision categories, priority order, tie-breakers, and wording so every entry point reports the same current priority.

**Architecture:** Each platform keeps its existing technical and fundamental analyzers, but both map their result into the same five decision categories: exit evaluation, reduce evaluation, risk confirmation, fundamental review, and buy candidate; ordinary holdings are not promoted as actions. A single deterministic priority function selects the current priority using category, confirmation/severity, position exposure, and code, while separate sections retain fundamental-review and buy-candidate lists.

**Tech Stack:** Kotlin/JUnit Android unit tests; Python/unittest macOS monitor tests.

**Spec:** The approved chat design from 2026-10-26: separate risk verification from trading action, prioritize confirmed risk before unconfirmed risk, never use raw fundamental alert as an automatic sell signal, use account exposure rather than daily P&L as a tie-breaker, and allow no-action output.

## Global Constraints

- Do not treat a fundamental alert as an automatic sell or reduce instruction.
- Do not use daily P&L absolute value as the primary portfolio-action sort key.
- Do not select a stock solely because it has the smallest market value or appears first in input order.
- Keep existing risk gates, T+1 constraints, and actionable-copy safeguards intact.
- Preserve unrelated uncommitted changes in both repositories.

### Task 1: Define the shared decision contract in Android

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/PortfolioAnalysis.kt`
- Test: `app/src/test/java/com/example/stockmonitor/PortfolioAnalysisTest.kt`

- [ ] Add failing tests covering category precedence, fundamental-review separation, exposure tie-breaking, deterministic code tie-breaking, and no-action output.
- [ ] Run the focused Android unit tests and confirm the new assertions fail for the old behavior.
- [ ] Add a small internal decision model and deterministic comparator used by `brief`, `todayBrief`, and report generation.
- [ ] Replace the old `highestPriorityAction`/`mostActionable` selection paths with the shared decision result and neutral wording such as `当前优先事项`.
- [ ] Update affected legacy assertions to the new contract while keeping existing risk and overall-advice assertions.
- [ ] Run focused Android tests and then the full Android unit test suite.

### Task 2: Apply the same contract to macOS

**Files:**
- Modify: `/Users/stephen/Desktop/stock_monitor_mac/portfolio_analysis.py`
- Modify: `/Users/stephen/Desktop/stock_monitor_mac/portfolio_daily_report.py`
- Test: `/Users/stephen/Desktop/stock_monitor_mac/tests/test_highest_priority_action.py`
- Test: `/Users/stephen/Desktop/stock_monitor_mac/tests/test_portfolio_daily_report.py`

- [ ] Add failing Python tests for the same category precedence, exposure tie-breaker, fundamental-review separation, deterministic tie-breaker, and no-action behavior.
- [ ] Run the focused Python tests and confirm the old name/P&L-based behavior fails the new cases.
- [ ] Implement the equivalent decision mapping and current-priority formatter in the macOS analysis layer.
- [ ] Make the daily report consume that single result for its summary and priority section; retain P&L ranking only in the P&L attribution section.
- [ ] Run focused Python tests and the complete relevant Python test suite.

### Task 3: Cross-platform parity verification and documentation

**Files:**
- Modify: `README.md` or the existing strategy documentation only if the user-facing priority semantics are documented there.
- Test: Android and macOS parity fixtures in their existing test files.

- [ ] Add one shared fixture scenario representing 中天科技 versus 长安汽车 and assert both platforms select the same decision category and stock.
- [ ] Verify all summary entry points use the same current-priority result rather than independently recomputing a winner.
- [ ] Review output wording for clear distinction between `风险待确认`, `基本面核实`, and executable `退出/减仓评估`.
- [ ] Run final Android and macOS verification commands and inspect the diff for unrelated changes.

