# 主力跟随与散户反向：Android 接入说明

日期：2026-11-24  
状态：研究观察层已接入日报、观察池/买点和盘中信号；未实盘验证，不改变评分、仓位规则或风险处置优先级。

## 1. 已有基础

Android 在本次改动前已经有一批“主力跟随 / 散户行为代理”模块，不需要重复实现：

- `analysis/MainForceDirectionPolicy.kt`：用平台资金流推断主力进场、承接、出逃、派发、回流等方向。
- `analysis/VolumePriceSignal.kt`：按价格位置解释量价组合，明确低位放量滞涨与高位放量滞涨含义相反。
- `analysis/NormalDivergencePolicy.kt`：区分正常分歧与破位，缩量回踩和放量下跌不混为一谈。
- `analysis/DipReboundEngine.kt`、`analysis/ReboundTracker.kt`：恐慌下探后的回升、承接和诱多识别。
- `analysis/HighPositionPolicy.kt`、`analysis/HoldingExitPolicy.kt`：高位派发、二波与冲高回落风险。
- `analysis/ObservationPool.kt`、`analysis/ObservationBuyAlert.kt`：候选晋级、买点确认与通知。

这些模块都是量价、资金流和结构的代理，不是真实机构账户或真实散户持仓数据。

## 2. 本次新增

`analysis/AccelerationPolicy.kt` 把 macOS 版已经做过事件研究的“亢奋加速末端”抽成共享口径：

- 现价 ≥ 近 60 日区间高点 ×97%；
- 近 5 日涨幅 ≥10%；
- 量比 ≥1.3；
- 当日涨幅 >1% 记 `ACCELERATING`（放量加速），≤1% 记 `DIVERGING`（高位放量分歧）；
- 缺高位、5 日涨幅、量比或当日涨幅任一字段记 `INSUFFICIENT`，不猜。

三处接入强度不同：

| 场景 | 代码位置 | 强度 |
|---|---|---|
| 持仓日报 | `PortfolioAnalysis.formatReport` → `watchLabel` | 只加一行研究观察标签，不参与评分/动作 |
| 观察池晋级、买点确认 | `ObservationPool.evaluate`、`ObservationSignal.accelerationBlocker` | `entryCanBuy=false`，只暂缓一轮，不淘汰候选；已确认候选回落到 `NEAR_ENTRY` |
| 个股盘中信号 | `IntradayTactics` → `breakoutNote` / `tSellNote` | 只在突破/做T文案中追加证据，不新增信号类型、不改阈值和数量 |

## 3. 豁免与开关

- `LEADER`、`LIMIT_UP_CONTINUATION` 不受晋级暂缓影响。
- 已确认的独立回踩（`M20_PULLBACK` 且 `confirmed`）以及两板以上回踩 MA20 的日线承接路径豁免。
- `AccelerationPolicy.entryBlockEnabled = false` 可关闭晋级/买点暂缓；日报标签和盘中文案不受影响。
- 与 `HighPositionPolicy`、MA5 乖离、资金流门控有重叠，当前按并列 blocker 处理，不做叠加扣分。

## 4. 证据与边界

macOS 版本用 851 只股票、2022-04 至 2026-09 的本地日线做过事件研究：

- 20/20 组“亢奋加速”阈值组合的 5 日超额均值与中位数均为负，中位数 -1.63% 至 -3.07%；
- 2022–2026 每年 100% 的组合中位数为负；
- 恐慌末端均值略正但中位数更差、胜率与基准持平，不能作为买入依据；
- 单票胜率只有 35%–43%，用途是避免在加速末端追高，不是预测顶部。

限制：样本是当前在市股票，存在生存偏差；等权对比上证指数；未扣费用；5 日持有期结论不能外推为盘中分钟级结论。

## 5. 验证

- `AccelerationPolicyTest`：阈值、状态、缺数据、豁免、开关和文案。
- `ObservationPoolTest`：加速末端不进买点池；已确认候选保留在 `NEAR_ENTRY`。
- `IntradayTacticsTest`：做T与突破只追加证据，不改变信号类型和数量。
- `PortfolioAnalysisTest`：日报显示研究观察标签并注明不参与评分与动作。

尚未验证“晋级成功后 5 日收益”和“突破/做T后当日、次日表现”。观察期内不据此改变任何 gate 方向或仓位规则。
