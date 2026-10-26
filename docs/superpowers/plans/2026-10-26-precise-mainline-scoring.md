# 精准主线与统一评分 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将选股综合评分统一为技术 35 / 主线板块 35 / 核心角色 20 / 数据可信度 10，并只把最近 5 个交易日至少 3 日强势、最新一日仍强势的板块认定为持续主线；已形成主线后允许一次温和分歧，避免正常震荡造成排名和档位立即跳水。

**Architecture:** 新增纯策略层分别计算周/月技术趋势、主线持续性和 PE/PB 独立估值风险；板块每日强度快照持久化 10 个交易日，主线判定只读最近 5 条；选股页继续复用单一综合分排序，并展示权重构成与主线持续天数。

**Tech Stack:** Kotlin、Android SharedPreferences、org.json、JUnit 4。

**Spec:** 当前对话已确认的评分设计：技术 35 / 板块 35 / 核心角色 20 / 数据可信 10；周 K/月 K 进入技术趋势；PE/PB 独立于技术分；主线要求最近 5 日至少 3 日强势且最新一日仍强势。已形成主线后的第一个温和分歧日保留主线观察和核心角色，板块项降为 25/35；连续第二个不强势日或首个严重转弱日立即退出主线。

## Global Constraints

- 不把单日强势板块标记为持续主线，不给其核心角色加分。
- 技术分只描述价格、趋势、量价和入场结构，不吸收 PE/PB。
- PE/PB 只生成独立估值风险，不改变技术分。
- 历史最高状态不参与当前档位和当前综合分。
- 只修改当前工作树，不创建分支、不提交、不回滚已有改动。
- 文件修改使用 `apply_patch`。

---

### Task 1: 主线持续性策略与持久化

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/MainlineStrengthPolicy.kt`
- Create: `app/src/main/java/com/example/stockmonitor/core/SectorStrengthHistoryJson.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/Store.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/Repo.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/MainlineStrengthPolicyTest.kt`
- Test: `app/src/test/java/com/example/stockmonitor/core/SectorStrengthHistoryJsonTest.kt`

**Interfaces:**
- Consumes: 当前行业/概念板块涨跌、成交额排名、主力净占比和交易日期。
- Produces: `SectorStrengthDay`、`SectorStrengthPoint`、`MainlineEvidence`、`SectorStrengthHistoryJson.encode/decode`、`Store.sectorStrengthHistory`、`Repo.sectorStrengthHistory`。

- [ ] **Step 1: Write failing tests**

```kotlin
assertEquals(false, MainlineStrengthPolicy.assess(oneStrongDay).isMainline)
assertEquals(true, MainlineStrengthPolicy.assess(threeStrongOfFiveWithLatestStrong).isMainline)
assertEquals(false, MainlineStrengthPolicy.assess(threeStrongButLatestWeak).isMainline)
assertEquals(history, SectorStrengthHistoryJson.decode(SectorStrengthHistoryJson.encode(history)))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --offline --tests '*MainlineStrengthPolicyTest' --tests '*SectorStrengthHistoryJsonTest'`
Expected: FAIL because production types and codecs are missing.

- [ ] **Step 3: Implement strategy and JSON codec**

`MainlineStrengthPolicy` 保留最近 10 个交易日、评估最近 5 个；强势日要求板块涨幅至少 1%、涨跌排名前 25%，并由成交额前 25% 或主力净流入为正佐证；持续主线要求至少 3/5 且最新一日强势。已形成主线后的第一个温和分歧日暂保留主线；连续第二个不强势日，或板块跌幅达到 -2%、排名落入后 25%、主力净流出达到 3% 等严重转弱情况，立即退出主线。

- [ ] **Step 4: Wire persistence and daily snapshots**

启动恢复历史；行业/概念/资金流刷新后按交易日覆盖同一日快照并落盘。

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --offline --tests '*MainlineStrengthPolicyTest' --tests '*SectorStrengthHistoryJsonTest'`
Expected: PASS.

### Task 2: 周月技术分与独立估值风险

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/TechnicalQualityPolicy.kt`
- Create: `app/src/main/java/com/example/stockmonitor/analysis/ValuationRiskPolicy.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/Fundamentals.kt`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/TechnicalQualityPolicyTest.kt`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/ValuationRiskPolicyTest.kt`

**Interfaces:**
- Consumes: 日线证据、由日线聚合出的完整周 K/月 K、PE-TTM、PB-MRQ、PE/PB 历史分位。
- Produces: `TechnicalQualityPolicy.Result(score 0..100, weeklyTrend, monthlyTrend)`、`ValuationRiskPolicy.Result`。

- [ ] **Step 1: Write failing tests**

```kotlin
assertEquals(100, TechnicalQualityPolicy.score(daily = 1.0, weekly = UP, monthly = UP).score)
assertTrue(weeklyAndMonthlyChangeTechnicalScore)
assertTrue(pePbRiskDoesNotChangeTechnicalScore)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --offline --tests '*TechnicalQualityPolicyTest' --tests '*ValuationRiskPolicyTest'`
Expected: FAIL because policies are missing.

- [ ] **Step 3: Implement technical and valuation policies**

技术分由日线证据 40 分、完整周 K 30 分、完整月 K 30 分组成；数据不足只给已有部分，不用猜测补分。PE/PB 风险独立生成，不返回也不写入技术分。

- [ ] **Step 4: Add valuation warnings to fundamental risk output**

PE/PB 高分位、极端倍数和亏损 PE 作为独立风险项进入基本面报告。

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --offline --tests '*TechnicalQualityPolicyTest' --tests '*ValuationRiskPolicyTest' --tests '*FundamentalsTest'`
Expected: PASS.

### Task 3: 统一综合评分 35/35/20/10

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/StrongSectorRankingPolicy.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/ScreenFragment.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/Models.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/ScreenResultJson.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/ObservationPool.kt`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/StrongSectorRankingPolicyTest.kt`
- Test: `app/src/test/java/com/example/stockmonitor/ScreenListUiTest.kt`
- Test: `app/src/test/java/com/example/stockmonitor/ScreenResultJsonTest.kt`

**Interfaces:**
- Consumes: `TechnicalQualityPolicy.Result`、`MainlineEvidence`、核心角色证据、数据覆盖率。
- Produces: `ScoreBreakdown(technicalPoints 0..35, sectorPoints 0..35, rolePoints 0..20, confidencePoints 0..10, total 0..100)`；主线正常分歧时 `sectorPoints=25`。

- [ ] **Step 1: Write failing tests**

```kotlin
assertEquals(35, breakdown.technicalPoints)
assertEquals(35, breakdown.sectorPoints)
assertEquals(20, breakdown.rolePoints)
assertEquals(10, breakdown.confidencePoints)
assertFalse(singleDayStrongResult.strongWatch)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --offline --tests '*StrongSectorRankingPolicyTest' --tests '*ScreenListUiTest' --tests '*ScreenResultJsonTest'`
Expected: FAIL because current weights are 50/25/15/10 and single-day strong is promoted.

- [ ] **Step 3: Implement unified scoring and persistence**

技术原始分统一为 0..100；板块/核心角色只认可持续主线及其一次温和分歧保留期；选股结果和观察池保存技术分、周趋势、月趋势，旧缓存的技术分最多按日线 40 分处理，不能冒充周月俱佳。

- [ ] **Step 4: Update UI breakdown and mainline labels**

列表继续只显示一个综合质量分；详情显示 `技术 35｜板块 35｜核心角色 20｜数据可信 10` 和 `主线持续：最近5日 x/5`。

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --offline --tests '*StrongSectorRankingPolicyTest' --tests '*ScreenListUiTest' --tests '*ScreenResultJsonTest'`
Expected: PASS.

### Task 4: Regression Verification

**Files:**
- Test: related analysis, core, and UI unit tests.

- [ ] **Step 1: Run focused tests**

Run: `./gradlew testDebugUnitTest --offline --tests '*Mainline*' --tests '*TechnicalQuality*' --tests '*ValuationRisk*' --tests '*StrongSector*' --tests '*ScreenListUiTest' --tests '*ScreenResultJsonTest'`

- [ ] **Step 2: Run broader tests**

Run: `./gradlew testDebugUnitTest --offline`

- [ ] **Step 3: Compile debug app**

Run: `./gradlew :app:assembleDebug --offline`

- [ ] **Step 4: Check diff**

Run: `git diff --check`

