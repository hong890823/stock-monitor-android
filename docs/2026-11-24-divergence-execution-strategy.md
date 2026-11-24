# 分歧日体检 × 分档回踩执行策略（可落地口径）

日期：2026-11-24
状态：P0/P1/P2 均已实现（见第 8 节分期表与实现注记）。两份输入：① 2026-11-24 收评的可编码框架（分歧日体检、次日证伪、状态矩阵、分批进场）；② 用户反馈「海立股份操作建议的回踩价格偏低，强势股到不了」。P0 修复回踩档位（问题②），P1/P2 落地分歧日状态机（框架①）。

本文只定口径和落点，不动现有评分、仓位上限、风险处置优先级。阈值均为初始值，定稿前按 `rebound-alert-threshold-preference` 的约定先给噪声天数对比。

## 0. 问题定位：回踩价为什么偏低

用户案例：海立股份（600619，`HailiReclaimReplayTest` 的回放对象）处于强势上涨段，但持仓操作建议给出的回踩加仓价远低于现价，强势股大概率回不到那个位置，加仓建议形同虚设。

三个根源：

| # | 位置 | 现行为 | 后果 |
|---|---|---|---|
| 1 | `PortfolioSwing.assess()`（`PortfolioSwing.kt:352-355`） | 回踩参考区间**单锚 MA20**：`[ma20×(1−0.5%), ma20×(1+2%)]`，现价不在区间内直接判 `WAIT`「不追价」 | 强势股（现价远高于 MA20）得到的参考区间深不可达；且因为现价出界，永远拿不到加仓资金 |
| 2 | `AddPositionStrategy.PREFERENCE`（`AddPositionStrategy.kt:126`） | 建议价位优先「波段回踩区间**下沿**」 | 建议价被进一步压向最深锚 |
| 3 | 全局 | 没有**可达性护栏**：建议价距现价多远都不提示 | 用户只能自己发现「等不到」 |

已有但未用上的资产：

- `StrategyThresholds.MAX_CHASE_MA5_BIAS_PCT = 8.0`：MA5 乖离追高上限——「别追连续大阳线」的程序化已经存在，但它只用于**禁止追高**，没把 MA5 用作强势股的**浅回踩锚**。
- `IntradayAdditionPolicy` 的 `maxAcceptedPrice`（最高接受价）：「价位上限」概念有先例，档位化的「锚位选择」没有。
- `NormalDivergencePolicy`：已区分「缩量回踩（正常分歧）」与「放量下跌（破位）」，是本文回踩确认的判据来源。

## 1. 术语

| 术语 | 定义 |
|---|---|
| 分歧日 | 利好高开后冲高回落、放量换手，但指数结构未破坏的交易日（文章：「利好兑现的是筹码，不是行情」） |
| 体检两条腿 | 政策腿（不可直接观测，置 unknown 不猜）；资金腿（放量平稳消化抛压 = 承接力增强） |
| 证伪节点 | 分歧日**次日**的指数 K 线，一次性决定分歧定性（洗盘 or 走弱） |
| 强度分档 | 个股按均线结构与相对 MA5 位置分 STRONG / NEUTRAL / WEAK，决定回踩锚位深度 |
| 可达性护栏 | 建议回踩价距现价超过波动率口径的合理回撤时，明示「大概率到不了」并改推浅锚 |

## 2. 模块 A：大盘分歧日体检（MarketDivergenceCheck）

输入（均可从现有指数行情取）：指数开盘/最高/收盘涨幅、成交额环比、涨跌家数、主线板块指数（如科创 50）相对沪指强弱。

判定规则（初始阈值，同指数全满足才判分歧日）：

| 条件 | 初始值 | 含义 |
|---|---|---|
| 高开 | 开盘涨幅 ≥ +0.4% | 利好驱动的高开 |
| 冲高回落 | 最高涨幅 ≥ +1.5% 且收盘涨幅 ≤ 高开幅度的 60% | 兑现盘主导 |
| 放量换手 | 两市成交额环比 ≥ +5% | 有真实分歧，不是缩量阴跌 |
| 结构未破坏 | 收盘价 > 前 5 日收盘均值 | 不与恐慌坑/破位混淆 |

输出：

```kotlin
data class DivergenceDayReport(
    val date: String,
    val isDivergenceDay: Boolean,
    val fundLeg: FundLeg,          // ABSORB_STRONG | ABSORB_NORMAL | WEAK（按量价承接分档）
    val policyLegUnknown: Boolean, // 政策腿恒为不可观测，只留位不赋值
    val evidence: List<String>,    // 当日事实，进通知文案
)
```

与现有模块的边界（**硬约束**）：

- 恐慌坑（`DipReboundEngine` / `ReboundTracker` 的深跌回升口径）**保持不变**。分歧日是高开回落，不是深跌坑，两个状态机不得互相串口——这条延续 `rebound-alert-threshold-preference` 的既定取舍。
- `NormalDivergencePolicy` 的个股缩量回踩/放量破位判据被模块 D 复用，不复制公式。

## 3. 模块 B：次日证伪状态机（DivergenceVerdict）

```
DIVERGENCE_WATCH ──次日中阳──▶ REBOUND_HOLD（分歧定性：洗盘）
      │
      ├──次日弱势（阴线/中阴）──▶ FALSIFIED_DOWNGRADE
      └──T+2 仍无结论 ──────────▶ 默认 FALSIFIED_DOWNGRADE（宁降勿拖）
```

中阳判定（两个候选口径，做噪声天数对比后二选一）：

- 口径 α（宽松）：沪指收盘涨幅 ≥ +0.5% 且收盘价 > 分歧日 K 线实体中点；
- 口径 β（严格）：收盘涨幅 ≥ +1% 且收复分歧日开盘价。

状态后果（见模块 C 挂接）：

- `REBOUND_HOLD`：恢复常规加仓/持有规则；
- `FALSIFIED_DOWNGRADE`：**降低仓位预期**——冻结新增资金批次（模块 E 批次 2/3），存量按现有风险规则处置。遵循 `risk-action-proportionality`：低位/轻仓/无红灯只出提示，不出减仓命令。

## 4. 模块 C：状态矩阵执行规则（文章三句话程序化）

文章三句（持仓拿底仓 / 分批进场 / 别追三连阳）转成矩阵。每个格子产出**提示文案**，不是命令——语气与现有 `PortfolioAnalysis` 话术同级。

| 大盘状态 \ 仓位状态 | 持仓（有底仓） | 空仓/轻仓 | 全局禁止项 |
|---|---|---|---|
| `REBOUND_HOLD` | 冲高可减机动仓、**留底仓不全兑现**（对齐现有「已有浮盈不是卖出理由」） | 借分歧窗口分批进场（模块 E） | 追三连阳确认 |
| `DIVERGENCE_WATCH` | 不动；给「分歧洗盘观察中，次日定方向」提示 | 只准备不进场：列出候选与浅锚价，**批次 1 也等证伪后**（宁慢勿追） | 同上 |
| `FALSIFIED_DOWNGRADE` | 重仓+红灯才提示减仓风险；轻仓只提示 | 冻结进场 | 同上 + 禁止摊平 |

「别追三连阳确认」的程序化已存在：`MAX_CHASE_MA5_BIAS_PCT` 的追高上限（`PortfolioAnalysis` chasingHigh 分支）。本模块不新增逻辑，只把该分支文案在分歧窗口内改为引用矩阵措辞。

## 5. 模块 D：强度分档回踩区间（P0，直接修复用户痛点）

### 5.1 强度分档判定（StrengthTier）

以 `MultiTimeframeSummary` / 均线快照为输入（数据已齐，不新增抓取）：

| 档位 | 条件（全满足） | 行为 |
|---|---|---|
| `STRONG` | MA5 > MA10 > MA20（多头排列）；现价 ≥ MA5×(1−2%)；近 5 日收盘跌破 MA5 次数 ≤ 1 | 浅回踩锚，可分批 |
| `NEUTRAL` | 现价在 MA20 ±3%（含正在回踩中的多头股：现价 < MA5×0.98 但未破 MA20） | 现口径 MA20 区间 |
| `WEAK` | 现价 < MA20×(1−0.5%)，或 `trendWeak`、`MajorTrend.DOWN` | 不给加仓区间（现有 BLOCKED 路径不变） |

### 5.2 分档锚位

| 档位 | 锚 anchor | 区间 | 文案要点 |
|---|---|---|---|
| `STRONG` | `max(MA5, 平台下沿/突破位)`（平台/突破位取 `BoxRangeAssessment` / `EntrySetup` 已有字段，取不到就退化为 MA5） | `[anchor×(1−1.0%), anchor×(1+2.0%)]` | 「强势股浅回踩口径：缩量回踩锚位企稳即可第一批，**等不到 MA20 不硬等**」 |
| `NEUTRAL` | MA20（现口径） | `[ma20×(1−0.5%), ma20×(1+2%)]` | 现有文案不变 |
| `WEAK` | — | 不给 | 「不加仓不摊平」（现有） |

`PortfolioSwing.assess()` 的改动点：`low/high` 计算（`PortfolioSwing.kt:352-355`）按档位选锚；`STRONG` 档现价在浅锚区间内即算「回踩条件通过」，`entryLow/entryHigh` 填浅锚区间。止损价口径（`entry.stopPrice`）、盈亏比 ≥2、`allocate()` 的仓位/风险预算约束**全部不动**。

操作建议文案同锚（P0 范围，2026-11-24 补齐）：**20 日低点（`StockAnalysis.support`）是破位口径的结构支撑，不是回踩锚**——现价 15.07 的强势股引「回踩支撑 11.04」等于说趋势已死（海立案例）。`TechnicalResult.pullbackAnchor/pullbackRef`（`PullbackAnchorPolicy.anchorFor`）供一切「回踩」话术用：STRONG「MA5 浅锚 / 突破位浅锚」、NEUTRAL「MA20」、WEAK 不给买点参考；「跌破支撑 / 前低」话术才用 20 日低点。

### 5.3 可达性护栏（ReachabilityGuard）

任何给出的建议回踩价（波段区间、补仓测算、操作建议文案）统一过护栏：

```
reachGapPct = (现价 − 建议价) / 现价
波动口径  = max(2.5 × ATR%, 3 × 近20日日均振幅%)   // 初始值，待噪声对比
若 建议价 < 现价 × (1 − 波动口径)：
    追加注记「该价属深回踩口径，按近期波动强势股大概率到不了；参考浅锚 {浅锚价} 或等分歧日分批」
    且 PREFERENCE 重排：STRONG 档下浅锚优先于「波段回踩区间下沿」
```

P0 实现说明：护栏落地在 `PullbackAnchorPolicy.unreachableNote`，当前只用 2.5×ATR 一腿；「3×日均振幅」并列腿等平均振幅字段接入后再加，与上式取 max。建议价排序用 `AddPositionStrategy.preferenceIndex`：含「浅回踩」的锚位标签永远排最前。

`AddPositionStrategy.PREFERENCE` 由固定序改为**按档位排序**：`STRONG` → `[浅锚, 回踩区间上沿, 支撑位, MA20, 现价]`；`NEUTRAL` 保持现序。摊薄公式本身（买价必须低于目标成本）是数学约束，不动。

### 5.4 海立股份用例（期望行为）

> 海立股份强势段：MA5 > MA10 > MA20，现价贴 MA5 上方，波段区间下沿（MA20）比现价低 8%+。
> 现行为：建议「回踩 MA20 区间下沿加仓」→ 用户等不到。
> 期望：分档 = STRONG → 浅锚 = MA5（约现价 −2%）；给「缩量回踩 MA5 企稳先第一批（计划量 40%）」；护栏对 MA20 价注记「深回踩口径大概率到不了」。回踩成立与否仍按 `NormalDivergencePolicy` 的缩量口径确认，不降门槛。

## 6. 模块 E：分批进场参数（文章「分批进场」程序化）

| 批次 | 占计划加仓量 | 触发 | 前置 |
|---|---|---|---|
| 1 | 40% | 浅锚区间内**缩量企稳**（量比 ≤ `PULLBACK_VOLUME_RATIO`(0.8)，复用 `TrendEntryStrategy` 口径） | 大盘 ≠ `FALSIFIED_DOWNGRADE` |
| 2 | 30% | 次日证伪确认 `REBOUND_HOLD` 后，或回踩浅锚不破且量价修复 | 批次 1 已成交 |
| 3 | 30% | 放量突破确认，或二次回踩锚位不破 | 批次 2 已成交 |

作废线（接飞刀禁令）：任一批次后**收盘跌破失效参考 `stopPrice`** → 取消全部后续批次，转入现有破位规则。总加仓量始终受 `allocate()` 现有约束（`initialEntryPct`、`maxPositionPct`、`RISK_PER_TRADE_PCT`），分批只是把同一额度切开，不放大额度。

「一把梭」禁令的实现形态：`SwingPlan` 的 `action()` 文案对空仓/轻仓分支固定追加「分批执行，不一次打满」；不新增交易功能。

## 7. 边界与不做清单

- **不**改变现有评分、风险处置优先级、深套 `T_ONLY` 口径、ST 涨跌停口径。
- **不**预测个股涨跌；所有「洗盘/证伪」只对大盘指数定性，不外推到个股。
- 盘中资金环境 v1（`intraday-fund-environment-v1`）在 2026-12-07 噪声评估完成前，**不**作为批次触发条件；评估通过后才考虑接进模块 E。
- 阈值定稿流程：每个初始阈值先给 2024–2026 样本的「噪声天数 vs 命中数」对比表再拍板（与反弹提醒阈值同流程）。
- 文章的 ETF 纪律（回踩用宽基接、不追行业 ETF）不进执行层；如需要，追加到 `app/src/main/assets/knowledge/513-strategy.md` 供应用内 AI 引用。

## 8. 落地分期与验证

| 期 | 内容 | 触点 | 测试 |
|---|---|---|---|
| P0 | 模块 D：强度分档 + 可达性护栏 + PREFERENCE 重排 | `PortfolioSwing.assess()`、`AddPositionStrategy`、`PortfolioAnalysis` 操作建议文案 | `HailiReclaimReplayTest` 加强势段用例（浅锚可达、深锚注记）；`PortfolioSwingTest` 分档矩阵；`AddPositionStrategyTest` 档位序 |
| P1 | 模块 A/B：分歧日体检 + 次日证伪状态机 | 新 `MarketDivergenceCheck`（纯函数，放 `analysis/`），指数侧接 `MonitorService` 状态 | `MarketDivergenceCheckTest`：判定表、两口径噪声对比、超时降级 |
| P2 | 模块 C/E：矩阵文案 + 分批参数 | `PortfolioAnalysis` 文案分支、`SwingPlan.action()`、`IntradayAdditionPolicy` 批次衔接 | 矩阵全格文案快照测试；批次作废线测试 |

验证纪律：每期先补能复现问题的测试再改实现（`PROJECT_INDEX` 使用规则）；P0 完成后用海立股份历史回放确认「强势段给浅锚、弱势段不变」。

## 9. P1/P2 实现注记

- 新纯函数模块：`analysis/MarketDivergenceCheck.kt`（体检 + 证伪状态机）、`analysis/DivergenceActionMatrix.kt`（矩阵文案）、`analysis/AddBatchPolicy.kt`（40/30/30 分批与作废线）。
- 状态叫 `DivergenceCycleState`（`DivergenceState` 已被 `ObvDivergence` 的 OBV 背离枚举占用，避免同名冲突）。
- 状态机**从历史推导不落盘**：`phaseOf(已完成指数日线)` 确定性可重算；`MonitorService` 接线时剔除今日未收盘 K 线，只用已完成日线。
- `fromBars` 的放量检查暂用**成交量环比**近似（`DayBar` 无成交额字段）；成交额环比（如 `MarketRiskContext.turnover.sameTimeRatio`）接入后由调用方传 `IndexDayStats.turnoverRatioPct`，判定阈值不变。
- 矩阵挂载点：`PortfolioAnalysis.adviceBody`（持仓整体操作建议），按仓位状态选格、重仓/红灯只影响降级行措辞强度；「追三连阳」禁令只补矩阵措辞，追高拦截仍走既有 MA5 乖离上限。
- 分批口径挂载点：`SwingDecision.action()` 与 `SwingPlan.reportLines()`；「IntradayAdditionPolicy 批次衔接」落地为**作废线口径对齐**（收盘跌破失效参考取消后续批次 ↔ `INVALIDATED`），盘中数量与风险门槛不动。
- 中阳 α/β 两口径都已实现（`MidCandleRule`），默认 α；噪声天数对比定稿前不切换。
