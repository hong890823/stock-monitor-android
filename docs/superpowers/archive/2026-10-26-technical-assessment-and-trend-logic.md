# 周期趋势、技术综合评分与操作建议逻辑归档

**归档日期：** 2026-10-26  
**项目：** `stock-monitor-android`  
**范围：** 自选股、持仓股的周 K/月 K 趋势判断、统一技术综合评分、风险封顶、操作建议、技术快照持久化，以及持仓最高优先级操作提取。  
**归档边界：** 本文只记录可复用的业务/算法逻辑，不记录 Fragment、布局、卡片、分页、列表排序等 UI 表现；UI 只是这些纯逻辑结果的消费者。

## 一、可迁移的整体架构

另一个项目可以按以下三层迁移，不要把展示文案或 Android Context 直接带入算法层：

1. **行情输入层**：把对方项目的 K 线和指标结果适配成本文需要的结构化输入，尤其是日 K、周 K、月 K、均线、支撑/压力、趋势状态、入场结构、量价和风险证据。
2. **纯策略层**：迁移 `MultiTimeframeTrend`、`TechnicalAssessmentPolicy`、`TechnicalTrendSnapshotPolicy`、`PortfolioAnalysis.highestPriorityAction()` 等纯函数/纯对象；不依赖页面、ViewModel、网络或持久化实现。
3. **结果消费层**：由另一个项目的报告、提醒、缓存或 UI 决定如何展示 `TechnicalAssessment` / `TechnicalTrendSnapshot`；展示层不得改写评分、档位或操作建议。

核心不变量：

- 趋势、评分、风险封顶和操作建议必须由同一份输入一次性计算，避免评分和建议互相矛盾。
- 技术分是 `0–100` 的技术质量/风险控制综合结果，不是收益预测、买入承诺或必涨概率。
- PE/PB 不进入技术分；估值继续作为独立的估值/风险因子，防止高估值把技术趋势“洗掉”或重复计分。
- `数据不足` 是独立状态，不等同于 `偏弱`；缺数据时不能推断趋势已经转坏。

## 二、周 K / 月 K 五档趋势

### 2.1 输入与数据边界

输入建议保留以下字段：

- `history: List<DayBar>`：日线 `date/open/high/low/close/volume`。
- `asOf: String`：评估基准日，格式 `yyyy-MM-dd`。
- `tradingDates: List<String>?`：可选的交易日表，用于识别缺失日线。

数据校验要求：

- 日期必须可解析；OHLCV 必须是有限数值，价格必须大于 0，成交量不得为负。
- `high >= max(open, close)`、`low <= min(open, close)`；日期不得重复。
- 只把 `asOf` 之前的日线视为已结束日线；当前未完成周期不能改变周 K/月 K 结论。
- 聚合周/月时排除当前未完成周期，并过滤掉历史开头的不完整周期；周 K 以周一为周起点，月 K 以每月 1 日为月起点。
- 长历史质量不足时输出 `issue`，不强行给趋势：需要至少 250 个日线、24 个完整周 K、13 个完整月 K；日线超过 10 天未更新或近期交易日不齐时暂停加仓评估。

### 2.2 五档定义

| 档位 | 含义 | 判定要点 |
|---|---|---|
| `UP` | 向上 | 最近完整周期收盘价站上周期均线，且该均线高于前期同长度均线。 |
| `REPAIR` | 趋势修复 | 均线尚未稳定上行，但价格已经从偏弱状态回到周期均线之上，且未满足完整“下跌企稳”确认。 |
| `STABILIZING` | 下跌企稳 | 前置周期仍偏弱；最近两个完整周期均收复均线；最近三个完整周期不再创新低；均线仍未形成稳定上行。 |
| `WEAK` | 偏弱 | 最近完整周期收盘价仍在周期均线之下。 |
| `MISSING` | 数据不足 | 样本不足，无法可靠判断；不是看空。 |

### 2.3 判定顺序

`classify(bars, period, lag)` 的可迁移顺序如下：

1. 样本不足 `period + lag` → `MISSING`。
2. 计算最近 `period` 根收盘价均线 `average`，以及往前 `lag` 个完整周期后的同期均线 `prior`。
3. 当前完整周期收盘价低于 `average` → `WEAK`。
4. `average > prior` → `UP`。
5. 其余情况检查下跌企稳：
   - 去掉最后两个完整周期后，最后一个周期曾低于其同期均线；
   - 最近两个完整周期分别收复各自历史均线；
   - 最近三个完整周期低点没有后值低于前值，即不再创新低。
6. 同时满足确认条件 → `STABILIZING`，否则 → `REPAIR`。

这里的“收复均线”不是只看一天收盘价；“不再创新低”比较完整周期的最低点。当前未完成周期由输入层剔除后再判断。

## 三、自选股/持仓股统一技术综合评分

`TechnicalAssessmentPolicy.evaluate(technical, weeklyTrend, monthlyTrend)` 输出：

- `score`：`0–100`；
- `trendPoints`：`/35`；
- `structurePoints`：`/25`；
- `momentumPoints`：`/20`；
- `riskPoints`：`/20`；
- `level`、`primaryAdvice`、`reason`。

### 3.1 趋势 `35` 分

日 K 短线趋势最高 `15` 分：

| 日 K 状态 | 分数 |
|---|---:|
| `EXTENDED` | 15 |
| `UP` | 14 |
| `REBOUND` | 10 |
| `RANGE` | 6 |
| `DOWN` / `MISSING` / 缺失 | 0 |

周 K、月 K 各最高 `10` 分：

| 周期档位 | 分数 |
|---|---:|
| `UP` | 10 |
| `REPAIR` | 7 |
| `STABILIZING` | 5 |
| `WEAK` / `MISSING` | 0 |

因此趋势总分上限为 `15 + 10 + 10 = 35`。日 K 缺失时不能用周/月 K 直接补足日 K 的 `15` 分。

### 3.2 均线/价格结构 `25` 分

- 四个均线关系各 `3` 分：`price >= MA5`、`MA5 >= MA10`、`MA10 >= MA20`、`MA20 >= MA60`。
- 价格站上有效支撑 `4` 分。
- 区间位置：
  - 价格高于压力，或位于支撑/压力中点以上 → `3` 分；
  - 有效区间内的其他位置 → `1` 分；
  - 压力不大于支撑或区间无效 → `0` 分。
- 入场结构三项各 `2` 分：存在结构、结构已确认、量能已确认。
- 原始结构分封顶 `25`。

### 3.3 量价动量 `20` 分

- MACD 三项各 `2` 分：`hist > 0`、`DIF > DEA`、`hist > previousHist`。
- RSI：`45–70` 得 `3` 分；`35–78` 得 `1` 分；其他或缺失得 `0` 分。
- KDJ：`K > D` 得 `2` 分；`J` 在 `20–95` 得 `2` 分。
- DMI/ADX：ADX `>= 20` 且 `+DI > -DI` 得 `5` 分；方向为正但 ADX 不足得 `3` 分；否则 `0`。
- OBV：OBV 变化为正得 `1` 分；出现底背离或同步状态再得 `1` 分。
- 原始动量分封顶 `20`。

### 3.4 风险控制 `20` 分

该维度是 **风险控制质量**：分数越高表示技术风险越低，不是“风险越高分越高”。从满分 `20` 扣分：

- 趋势转弱：`-10`；
- 价格跌破支撑：`-20`；
- 高位风险优先：`-7`；
- 高位阶段为 `EXTENDED_WATCH` / `DISTRIBUTION_RISK` / `UNCERTAIN`：`-4`；
- 顶背离：`-4`；
- ATR% `> 8`：`-3`，`> 5`：`-1`；
- RSI `> 78`：`-3`；
- MACD 柱为负且 `DIF < DEA`：`-3`；
- 当日涨幅 `>= 5%`、量比 `>= 1.8` 且接近压力：`-3`。
- 结果限制在 `0–20`。

### 3.5 风险封顶与综合档位

先计算四维原始和，再应用风险封顶：

- **破位/趋势明确转弱**：`entrySetup.trendWeak`、主趋势 `DOWN`、短趋势 `DOWN`，或价格跌破有效支撑 → 总分最多 `45`，档位直接为 `风险优先`。
- **过热/高位风险**：高位风险优先、顶背离、RSI `> 78`，或放量大幅上涨接近压力 → 总分最多 `55`。
- 其他情况下使用原始总分，并限制在 `0–100`。

综合档位：

- `风险优先`：破位或明确转弱；
- `强势可参与`：分数 `>= 80`；
- `较强待确认`：分数 `>= 65`；
- `中性观察`：分数 `>= 45`；
- `偏弱谨慎`：低于 `45`。

这样能保证档位不会仅由单一强信号覆盖破位风险；同时也避免主线/强势背景被技术破位后的高原始分误导。

## 四、操作建议生成规则

操作建议只生成一条 `primaryAdvice`，按下列优先级判断：

1. **跌破支撑** → `减仓或退出：现价…已跌破支撑…先控制仓位；重新站稳后再评估是否接回。`
2. **趋势转弱** → `暂不补仓…`；若周 K 或月 K 已出现“下跌企稳”，文案明确写出企稳迹象，但仍不直接补仓。
3. **高位/过热风险** → `等待回踩确认…`；高位风险区明确写“不追高”，量价偏热则写“回踩不破支撑再评估”。
4. **区间震荡** → `持有观察：价格仍在压力与支撑之间…不追高。`
5. **分数 `>= 70` 且允许加仓**：
   - 入场结构、突破/回踩、量价均确认 → `可分批参与…首次仓位宜轻…`；
   - 有结构但未确认 → `观察入场结构…等待确认后再分批。`；
   - 接近压力 → `等待突破确认…站稳压力后再评估，不直接追入。`；
   - 接近支撑 → `可小仓观察…等回踩企稳和量能配合后再分批。`；
   - 其他 → `可关注分批参与…先以小仓观察为主。`
6. **其余情况** → `持有观察…等待方向进一步确认。`

### 4.1 均线文案含义

- `MA20`：短线站稳、入场和补仓判断的关键均线；对应“短线关键均线看 MA20”。
- `MA60`：中期趋势参考；对应“趋势参考 MA60”。
- 文案必须同时给出数值；缺失时显示“暂缺”，不能猜测。
- 操作建议与补仓参考已合并，只保留 `primaryAdvice` 一条；旧缓存中的 `addPositionGuidance` 不再读取。

### 4.2 允许加仓的独立条件

`TechnicalTrendSnapshot.allowsAddPosition = trendAssessment.allowsAddPosition && !entrySetup.trendWeak`。

这表示“允许加仓”不是技术分的同义词：即使分数不低，只要趋势弱、破位或入场结构转弱，就不允许加仓。

## 五、技术快照与缓存兼容

`TechnicalTrendSnapshot` 保存评估结果，至少包括：

- 代码、名称、基准日；
- 技术总分和四维得分；
- 周 K/月 K 档位；
- `trendWeak`、`allowsAddPosition`；
- `primaryAdvice`、`adviceReason`。

快照摘要使用稳定语义：

- `技术 xx/100｜周K…｜月K…`
- `趋势 xx/35｜均线结构 xx/25｜量价动量 xx/20｜风险控制 xx/20`
- 周期趋势五档明确为：`向上 > 趋势修复 > 下跌企稳 > 偏弱 > 数据不足`。

`TechnicalTrendSnapshotJson` 的兼容规则：

- 按股票代码排序写入，保证缓存结果稳定。
- 核心 JSON 字段：`code`、`name`、`as_of`、`technical_score`、`trend_points`、`structure_points`、`momentum_points`、`risk_points`、`weekly_trend`、`monthly_trend`、`trend_weak`、`allows_add_position`、`primary_advice`、`advice_reason`。
- 未知趋势枚举回退为 `MISSING`；旧缓存缺少建议时显示“暂无操作建议，等待重新计算”，缺少依据时显示“旧缓存未包含评分依据，等待重新计算”。
- 旧缓存的 `addPositionGuidance` 不参与新逻辑；新快照不写该字段。

## 六、持仓最高优先级操作

`PortfolioAnalysis.highestPriorityAction()` 只在当前实际持仓（数量为有限正数）中选一条最高优先级操作，并输出：

`最高优先级操作：<股票名>｜<操作建议/信号>`

排序优先级固定为：

1. `减仓风险`；
2. 退出/减仓/高位减仓阶段；
3. `趋势转弱观察`；
4. `持有/止盈观察`；
5. `买入确认` / `买入观察` 或 `entrySetup.canBuy`；
6. 其他。

同优先级按股票名排序，只返回一条，避免持仓页和复盘报告列出大量同等重要建议。该结果同时进入“今日要点”和持仓复盘报告的开头，属于业务摘要逻辑，不是 UI 排版逻辑。

## 七、迁移建议

### 7.1 推荐迁移顺序

1. 先迁移 `SwingTrend`、`DayBar` 适配和 `MultiTimeframeTrend.evaluate/classify`，用固定样本锁定五档边界。
2. 再迁移 `TechnicalResult` 所需字段适配器；在另一个项目中先用已有指标计算结果填充，不要把 Android UI 模型当输入。
3. 迁移 `TechnicalAssessmentPolicy` 的四维评分、风险封顶和建议生成。
4. 最后迁移 `TechnicalTrendSnapshot` / JSON 编解码，以及 `highestPriorityAction()` 的优先级。
5. 将 `TechnicalTrendSnapshotPolicy` 作为入口封装，保证自选股和持仓股共用同一套评分。

### 7.2 另一个项目必须重新验证

- `MA5/MA10/MA20/MA60`、RSI、KDJ、MACD、ADX/DMI、OBV、ATR 的参数和复权口径；
- 日线、周线、月线的时区、交易日边界、停牌日、缺口、除权除息和缺失数据处理；
- `250` 日、`24` 周、`13` 月等历史长度门槛是否符合目标市场的交易日数量；
- `45/55` 风险封顶、`80/65/45` 档位阈值、`70` 分建议阈值是否符合另一项目的品种波动和风控偏好；
- 封板、涨跌停、盘中未完成 K 线、盘中量比折算等场景；
- 回测/样本外验证：至少比较命中率、回撤、假信号、分批建议表现，并检查评分是否被单一题材或短期行情过度放大；
- 若另一个项目需要 PE/PB、板块主线、资金流或情绪分，应作为独立因子接入，不要偷偷加回技术 `100` 分。

## 八、正常分歧与破位判定

这部分是技术评分之外的交易节奏门控，不能用一次盘中回落直接替代日 K 趋势判断：

- 高位或 MA5 乖离 `>= 8%` 的个股，关键线看 `MA5`；中低位个股关键线看 `MA20`。
- 日 K 只要价格守住关键线，且高位量价仍有配合，就按“正常分歧”观察；高位量能不足或出现顶背离时进入“待确认”，不直接判破位。
- 盘中使用 15 分钟发现、30 分钟确认：15 分钟连续 3 根跌破，或 15 分钟连续 2 根跌破且 30 分钟确认，才进入“确认破位”。未满足确认条件时只标记“待确认”，不直接触发新增买入、做 T 买入或快速掉档。
- 只有最新已完成的 15/30 分钟 K 线才能贡献确认计数；过期分钟数据不能把历史跌破伪装成当前确认。现价已经进入关键线容差下方时，也先拦截新增买入，等待持续性确认。
- 60 分钟/中期趋势只作背景压力（当前实现用中期/短线趋势状态作为背景代理）；背景偏弱时降低新增动作置信度，但不把单根盘中回落单独升级为破位。
- 关键线阈值使用 ATR 动态容差，范围 `0.5%–1.5%`；风险线证据要与普通支撑位证据分开保存，避免把 MA5/MA20 判定和止盈止损位混为一谈。
- 非持仓股的盘中推送只允许“买点已确认”链路；反弹跟踪、封板/炸板、跌幅、做 T、破位等提醒只对持仓股开放。买点确认必须同时满足 `BUY_CANDIDATE` 状态、非涨停延期状态和执行守卫通过。

对应纯策略入口为 `NormalDivergencePolicy.evaluateDaily`、`NormalDivergencePolicy.evaluateIntraday`；
盘中证据由 `TimeframeEvidence.riskLine*` 提供，买入动作由 `TradeActionGuard` 在确认前拦截。

## 九、验证记录与已知边界

- 相关逻辑测试文件：
  - `app/src/test/java/com/example/stockmonitor/MultiTimeframeTrendTest.kt`
  - `app/src/test/java/com/example/stockmonitor/analysis/TechnicalAssessmentPolicyTest.kt`
  - `app/src/test/java/com/example/stockmonitor/analysis/NormalDivergencePolicyTest.kt`
  - `app/src/test/java/com/example/stockmonitor/analysis/TechnicalTrendSnapshotPolicyTest.kt`
  - `app/src/test/java/com/example/stockmonitor/core/TechnicalTrendSnapshotJsonTest.kt`
  - `app/src/test/java/com/example/stockmonitor/core/TimeframeEvidenceTest.kt`
  - `app/src/test/java/com/example/stockmonitor/core/TradeActionGuardTest.kt`
  - `app/src/test/java/com/example/stockmonitor/PortfolioAnalysisTest.kt`
  - `app/src/test/java/com/example/stockmonitor/DailyReviewTest.kt`
- 早前一轮针对性验证曾覆盖趋势五档、评分/风险封顶、建议合并、快照 JSON 兼容、持仓最高优先级和复盘摘要；本轮新增的正常分歧测试已补齐，但尚未重新跑通 Gradle。
- 本轮复核修复了三个边界：历史分钟数据不计入当前确认、现价跌破关键线容差时先拦截新增买入、`lastCompleted15m/30m` 改为实际最后一根已完成 K 线而非理论时间槽。
- 本机 Gradle wrapper 曾因 `.gradle` 锁文件权限问题无法正常启动，因此本归档不宣称完整 Gradle 构建或全量测试已经重新通过；迁移项目应以其自己的 CI 和样本外测试为准。
- 本逻辑不自动下单，不保证收益，也不把“可分批参与”解释成确定性买点。

## 十、主要源码位置

- `app/src/main/java/com/example/stockmonitor/analysis/MultiTimeframeTrend.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/TechnicalAssessmentPolicy.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/NormalDivergencePolicy.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/TechnicalTrendSnapshotPolicy.kt`
- `app/src/main/java/com/example/stockmonitor/core/TimeframeEvidence.kt`
- `app/src/main/java/com/example/stockmonitor/core/TradeActionGuard.kt`
- `app/src/main/java/com/example/stockmonitor/core/TechnicalTrendSnapshotJson.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/PortfolioAnalysis.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/DailyReview.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/StockAnalysis.kt`

