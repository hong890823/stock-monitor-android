# Portfolio Addition Diagnostics Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Android 持仓波段追加结果提供结构化诊断，让 AI 和用户能区分已通过条件、等待确认、策略阻碍、数据缺口、资金问题和仅观察。

**Architecture:** 在 `PortfolioSwing.kt` 中由 `SwingDecision` 派生 `SwingDiagnosis`，不改变原有准入门槛和数量计算。报告、今日要点和持仓详情继续使用原结果，同时增加诊断摘要；旧的 `reason` 文案保留以兼容现有调用。

**Tech Stack:** Kotlin, JUnit 4, existing `PortfolioSwing`, `PortfolioAnalysis`, `PortfolioFragment`.

**Spec:** `/Users/stephen/Desktop/temp.txt` 中“六、持仓加仓评估可见性”。

## Global Constraints

- 结构化诊断只解释结果，不放宽基本面、多周期、趋势、风险或资金门槛。
- 缺失数据必须归入数据缺口，不能写成策略条件不通过。
- 组合风险和资金限制要保留逐股诊断，不隐藏整段持仓结果。
- 不清理或覆盖工作树中已有未提交修改。

---

### Task 1: Add structured diagnosis and expose it in reports

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/PortfolioSwing.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/PortfolioAnalysis.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/PortfolioFragment.kt`
- Modify: `docs/PROJECT_INDEX.md`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/PortfolioSwingDiagnosisTest.kt`

- [x] **Step 1: Write failing diagnosis tests.**
- [x] **Step 2: Run the focused test and verify missing diagnosis APIs fail.**
- [x] **Step 3: Implement `SwingDiagnosticKind`, `SwingDiagnosis` and `SwingDecision.diagnosis`.**
- [x] **Step 4: Add diagnostic summaries to swing reports and holding details.**
- [x] **Step 5: Run diagnosis, swing and portfolio regression tests.**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.analysis.PortfolioSwingDiagnosisTest --tests com.example.stockmonitor.PortfolioSwingTest --tests com.example.stockmonitor.PortfolioAnalysisTest`

Expected: all selected tests pass.
