# Promotion Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUBSKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove ineffective limit-up promotion confirmation logic and add conservative market, sector, board-count, and seal-quality gates.

**Architecture:** Keep the existing observation state machine, but replace cumulative-volume comparison with time-separated fresh evaluations. Persist limit-up admission context on candidates, score reliable seal quality, require more confirmations for three-board stocks, and delay promotion when broad-market or associated-board conditions are weak.

**Tech Stack:** Kotlin, Android, JUnit 4, existing `ObservationCandidate`, `ObservationSignal`, `ObservationPoolJson`, `MonitorService`, and `Repo`.

**Spec:** `docs/superpowers/specs/2026-10-22-limit-up-observation-promotion-design.md`

## Global Constraints

- Fund flow remains auxiliary evidence and cannot independently create a promotion.
- Sealed limit-up stocks never enter `NEAR_ENTRY` or `BUY_CANDIDATE`.
- A-share trading remains T+1; promotion means a candidate state, not an order.
- Missing market, board, or seal-quality data must not be guessed or block promotion by itself.
- Do not create Git commits.

### Task 1: Confirmation timing and admission context

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/Models.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/ObservationPoolJson.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/ObservationPool.kt`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/ObservationPoolStrategyTest.kt`

**Interfaces:**
- Consumes: existing `ObservationCandidate` and `ObservationSignal`.
- Produces: `limitStreak`, `sealQualityScore`, `sealQualityCoveragePct`, and `boards` candidate context; 15–45 minute two-round confirmation; three-round confirmation for streak >= 3.

- [ ] **Step 1:** Add failing tests proving cumulative volume alone cannot complete confirmation and three-board stocks require three rounds.
- [ ] **Step 2:** Run the focused observation tests and verify failure.
- [ ] **Step 3:** Implement time-separated confirmation and persist admission context.
- [ ] **Step 4:** Run focused tests and JSON compatibility tests.

### Task 2: Market and sector promotion gate

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/PromotionEnvironmentPolicy.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/Models.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/ObservationPool.kt`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/PromotionEnvironmentPolicyTest.kt`

**Interfaces:**
- Consumes: index changes, limit-up/down breadth, and associated-board changes.
- Produces: `ObservationSignal.environmentGate: String?`, a pure `PromotionEnvironmentPolicy.gate(...)`, and promotion delay wording.

- [ ] **Step 1:** Add failing pure-policy tests for market risk, limit-down breadth, and all-known-boards retreat.
- [ ] **Step 2:** Implement the policy and wire it into observation signals without affecting intraday alerts.
- [ ] **Step 3:** Run focused tests and verify missing data remains neutral.

### Task 3: Seal-quality and board-count scoring

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/ObservationPool.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/LimitCandidatePolicy.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/LimitPromotionPolicyTest.kt`

**Interfaces:**
- Consumes: persisted `sealQualityScore`, `sealQualityCoveragePct`, and `limitStreak`.
- Produces: bounded seal-quality score adjustment and stricter high-board gate.

- [ ] **Step 1:** Add failing tests for reliable high/low seal-quality adjustments and three-board quality gating.
- [ ] **Step 2:** Implement bounded scoring and stricter high-board requirements.
- [ ] **Step 3:** Run promotion policy tests and `git diff --check`.
