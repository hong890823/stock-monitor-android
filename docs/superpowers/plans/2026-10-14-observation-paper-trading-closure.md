# 观察池纸上交易闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有观察池之上增加可持久化的候选快照、纸上交易和策略绩效统计，验证启动前潜力与强势龙头两类策略。

**Architecture:** 复用现有 `Store`/`SharedPreferences` 持久化，不引入 Python、SQLite 或第二套行情源。新增纯 Kotlin 领域模块：候选快照记录观察状态变化；纸上交易引擎执行 A 股 T+1、手续费和印花税规则；绩效统计按策略汇总交易次数、胜率、收益、最大回撤。先接入服务层保存每日观察状态，UI 在后续阶段展示统计。

**Tech Stack:** Kotlin, Android SharedPreferences, org.json, JUnit 4。

**Spec:** 本次用户确认的“选择性炼化外部 A 股纸上交易 skill”方案。

## Global Constraints

- 不复制外部 skill 的 Python CLI、SQLite 或 vision-delegate。
- 不改变现有 `ObservationPool` 的晋级/淘汰规则。
- 所有收益统计扣除手续费、卖出印花税，并遵守 A 股买入 T+1。
- 不把纸上交易结果表述为真实投资建议。
- 新增生产代码必须先有失败测试。

### Task 1: 候选观察快照历史
**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/core/ObservationSnapshot.kt`
- Create: `app/src/main/java/com/example/stockmonitor/core/ObservationSnapshotJson.kt`
- Test: `app/src/test/java/com/example/stockmonitor/ObservationSnapshotTest.kt`

- [ ] 写测试：首次快照、跨交易日追加、同日替换、最多保留 30 天。
- [ ] 运行测试确认缺少类型/函数而失败。
- [ ] 实现不可变快照记录与 JSON 编解码。
- [ ] 运行测试确认通过。

### Task 2: 纸上交易引擎
**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/PaperTrading.kt`
- Test: `app/src/test/java/com/example/stockmonitor/PaperTradingTest.kt`

- [ ] 写测试：买入扣费、买入当日不可卖、次日卖出扣印花税、加权成本与已实现收益。
- [ ] 运行测试确认失败。
- [ ] 实现最小账本和费用口径。
- [ ] 运行测试确认通过。

### Task 3: 绩效统计
**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/StrategyPerformance.kt`
- Test: `app/src/test/java/com/example/stockmonitor/StrategyPerformanceTest.kt`

- [ ] 写测试：按策略统计胜率、平均收益、累计收益、最大回撤，未平仓不计入胜率。
- [ ] 运行测试确认失败。
- [ ] 实现统计函数。
- [ ] 运行测试确认通过。

### Task 4: 接入观察池持久化
**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/Store.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/Repo.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/Models.kt`
- Test: `app/src/test/java/com/example/stockmonitor/ObservationSnapshotTest.kt`

- [ ] 新增快照存储字段与运行时缓存。
- [ ] 在观察池新增/复评成功后追加每日快照。
- [ ] 运行单元测试和 Debug 构建。

### Task 5: 文档与回归验证
**Files:**
- Modify: `README.md`

- [ ] 说明纸上交易只用于策略验证，不能代表真实收益。
- [ ] 运行完整 `testDebugUnitTest` 和 `assembleDebug`。
