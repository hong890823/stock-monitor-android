# AI 项目导航索引

更新时间：2026-11-24

这份文档是给 Codex、其他代码 AI 和维护者使用的项目地图。它只负责回答“应该先看哪里”，不复制完整实现。最终行为以当前源码和测试为准。

## 使用规则

处理代码问题时按下面顺序读取：

1. 先读本索引，确定功能所属的入口、编排层、策略层和展示层。
2. 再读当前源码中与问题直接相关的文件，沿调用链向前或向后追踪。
3. 用相邻测试确认行为约束；如果是 Bug，先补一个能复现问题的测试。
4. 最后才查看 `docs/superpowers/plans/`、`docs/superpowers/specs/` 和 `docs/superpowers/archive/` 中的历史背景。

源码优先级高于文档。当前源码和测试高于旧的方案、计划和归档文档。`semantic-review/` 保存的是历史审查记录，不是当前实现规范。

## 30 秒项目地图

这是一个 Android 原生股票监控应用，主要职责分成五层：

```text
外部行情/新闻/基本面接口
        ↓
core：客户端、模型、缓存、序列化、持久化
        ↓
monitor：定时调度、批量分析、全局状态、通知
        ↓
analysis：技术指标、市场环境、风险门控、候选池、持仓策略
        ↓
ui：页面、卡片、详情、报告、设置和交互
```

应用内 AI 是一条独立链路：

```text
ToolsFragment
    ↓
DeepSeekClient / AiAnalysis
    ↓
StockAiToolExecutor
    ↓
Repo 中的缓存、分析报告和只读数据工具
```

## 顶层目录

| 路径 | 作用 | AI 首选入口 |
| --- | --- | --- |
| `app/src/main/java/com/example/stockmonitor/core` | 模型、网络客户端、缓存、持久化、序列化、AI 数据协议 | `Models.kt`、`Store.kt`、`AiAnalysis.kt` |
| `app/src/main/java/com/example/stockmonitor/analysis` | 纯策略和分析规则，尽量不依赖 Android UI | `StockAnalysis.kt`、`PortfolioAnalysis.kt`、各 `*Policy.kt` |
| `app/src/main/java/com/example/stockmonitor/monitor` | 前台服务、调度、批量分析、全局运行状态、通知 | `MonitorService.kt`、`AnalysisService.kt`、`Repo.kt` |
| `app/src/main/java/com/example/stockmonitor/ui` | 页面、卡片、对话框、报告和用户操作 | `MainActivity.kt`、各 Fragment、`ReportActivity.kt` |
| `app/src/main/assets/knowledge` | 应用内 AI 可读取的策略知识库 | `513-strategy.md` |
| `app/src/test/java/com/example/stockmonitor` | 单元测试和策略回归测试 | 按功能查找同名 `*Test.kt` |
| `app/src/test/resources` | K 线、新闻等固定测试数据 | 按测试文件中的资源名查找 |
| `docs` | 当前说明、设计、计划和历史归档 | 先读本索引，再按功能跳转 |

## 主要运行入口

### Android 生命周期和页面

- `app/src/main/java/com/example/stockmonitor/ui/MainActivity.kt`：应用主 Activity、Tab 和全局页面入口。
- `app/src/main/java/com/example/stockmonitor/ui/BaseFragment.kt`：页面公共基类和刷新/状态协作。
- `MarketFragment.kt`：市场、指数、板块、涨跌停和新闻相关展示。
- `ScreenFragment.kt`：自选股、选股结果、观察池和候选展示。
- `PortfolioFragment.kt`：持仓、持仓信号、波段追加、交易入口和持仓详情。
- `ToolsFragment.kt`：AI 综合分析、工具入口和手动分析操作。
- `ReportActivity.kt`：长报告展示。
- `Dialogs.kt`、`BoardGroupDialogs.kt`、`BoardLeaderDialogs.kt`：配置、交易、板块和龙头管理。

### 后台调度和状态

- `monitor/MonitorService.kt`：前台服务主循环。负责行情刷新、市场数据刷新、持仓分析、选股/观察池复评、提醒和通知。
- `monitor/AnalysisService.kt`：耗时分析任务的 Android Service 入口。
- `monitor/Repo.kt`：运行期间的内存状态发布中心；UI 主要从这里读取。
- `core/Store.kt`：SharedPreferences 和落盘配置/缓存的统一入口。
- `monitor/Notifier.kt`：通知生成、去重和发送。
- `monitor/BootReceiver.kt`：设备启动后的服务恢复。

### 同日建议连续性

同一交易日同一股票的建议计划、通知状态和持仓净数量反馈走独立链路，排查“重复提醒、部分执行、重启丢状态、持仓一直没有后续建议”时优先读取：

- `analysis/DailyAdvicePolicy.kt`：纯 Kotlin 状态机。定义买入/卖出方向、建议类型、预占、发送、回滚、送达未知、部分执行和反向变化复核。
- `core/DailyAdviceJson.kt`：同日计划 JSON 编解码；无效存档降级为空列表。
- `core/Store.kt`：`dailyAdvicePlans` 持久化，以及 `prepareDailyAdvice`、`reserveDailyAdvice`、`commitDailyAdvice`、`rollbackDailyAdvice`、`applyDailyAdviceQuantityFeedback` 等原子入口。
- `monitor/MonitorService.kt`：将持仓和盘中可执行动作接入 `prepare → reserve → 通知 → commit/rollback`；不同股票的未完成买入计划在这里被互斥。
- `monitor/Notifier.kt`：普通提醒、风险提醒和盘中信号返回实际发送结果；返回 `false` 时计划回滚。
- `ui/Dialogs.kt`：新增持仓、编辑持仓和真实交易保存成功后反馈当天净数量；成本单改、批次补记和删除监控不反馈成交。
- `ui/PortfolioFragment.kt`：显示当天原计划、已反馈数量、剩余数量、复核和通知阻断状态。

### 持仓重点改善与波段追加

- `analysis/PortfolioSwing.kt`：持仓波段追加的多周期条件、风险门控、价格区间、风险额度、整手数量和最多三只重点改善持仓的顺序资金分配。
- `analysis/PortfolioSwing.kt` 中的 `SwingDiagnosis`：将每只持仓的结果拆成已通过条件、等待确认、策略阻碍、数据缺口、资金问题和仅观察，报告不再只依赖一段原因字符串。
- `analysis/PortfolioAnalysis.kt`：把已通过持仓优先级的追加机会逐只写入“今日要点”。
- `ui/PortfolioBriefCard.kt`：显示本轮实际获得追加资金的重点持仓数量。
- `ui/PortfolioFragment.kt`：每只持仓详情展示自己的波段追加数量、区间和原因。

### 分歧日执行与强度分档回踩锚

- `analysis/PullbackAnchorPolicy.kt`：回踩锚位强度分档（强势浅锚 MA5/突破位、中性 M20 现口径、弱势不给区间）与建议价可达性护栏（2.5×ATR，超口径注记「大概率到不了」）。
- `analysis/MarketDivergenceCheck.kt`：大盘分歧日体检（高开兑现回落 + 放量 + 结构未破坏）与 D+1/D+2 证伪状态机（`DivergenceCycleState`，宁降勿拖；与 `ObvDivergence.DivergenceState` 的 OBV 背离同名不同义）。状态从已完成指数日线推导，不落盘。
- `analysis/DivergenceActionMatrix.kt`：分歧期状态矩阵文案（市场状态 × 仓位状态），提示不命令；追三连阳禁令只补措辞，拦截仍走 MA5 乖离上限。
- `analysis/AddBatchPolicy.kt`：波段加仓 40/30/30 分批口径与作废线（收盘破失效参考取消后续批次），只切分额度不放大。
- `analysis/PortfolioSwing.kt`：回踩区间按档位选锚（`SwingEvidence.ma5/ma10/recentCloses`），`SwingDecision.anchorLabel`，报告挂分批口径。
- `analysis/AddPositionStrategy.kt`：浅回踩锚优先于深位（`preferenceIndex`），深位候选价过护栏出注记。
- `analysis/PortfolioAnalysis.kt`：整体建议只点名要动手/要核实的票（最多两只 + 总数），持有观察报数不点名；分歧期矩阵行进持仓建议。
- `monitor/MonitorService.kt`：波段证据补短均线/近10日收盘；持仓报告透传分歧期状态（剔除今日未收盘 K 线）。

口径、阈值初始值与待定稿清单见 [分歧日执行策略](2026-11-24-divergence-execution-strategy.md)；改动清单与不动口径见 [实现归档](2026-11-24-divergence-execution-implementation.md)。恐慌坑（深跌回升）口径不变，与分歧日状态机不串口。

### 底仓、滚动仓与风险观察仓

- `core/PositionAllocation.kt`：分仓模式、固定底仓目标和独立 JSON 编解码；配置损坏时由 `Store` 保留原始值，不静默覆盖。
- `analysis/PositionAllocationPolicy.kt`：按当前实时持仓数量派生底仓、滚动仓和风险观察仓；清仓只让显示数量归零，不删除底仓目标。
- `analysis/PositionAllocationRecommendationPolicy.kt`：结合基本面健康等级、技术评分/趋势、单股集中度和数据缺口生成可解释的默认分仓建议；建议不写入配置。
- `core/Store.kt`：`positionAllocations` 独立于 `Portfolio.positions` 持久化，避免把易过期的实时分仓数量重复存储。
- `ui/Dialogs.kt`：设置普通模式或风险观察模式，以及固定底仓目标；未配置时预填系统建议并显示建议依据，用户确认后才保存。
- `ui/PortfolioFragment.kt`：持仓设置、单股摘要和详情展示分仓结果；未配置股票同时显示系统建议。
- `analysis/PortfolioAnalysis.kt`、`monitor/MonitorService.kt`：把同一分仓口径写入持仓长报告，供报告页和 AI 工具读取。

分仓已接入 Android 盘中普通做T的最终数量校验：滚动容量按底仓目标与今日可卖量共同封顶；风险处置仍可独立涉及底仓。T+1 仍由 `TradeLotLedger` 决定。

实现范围与延期项见 [盘中提醒分仓约束与 Android/mac 一致性](2026-11-17-intraday-allocation-and-cross-platform-backlog.md)：记录北方稀土案例的证据边界、分仓容量、风险确认、板块联动和通知连续性；文档不替代当前策略。

### 严格价格窗口的盘中补仓

- `analysis/IntradayAdditionPolicy.kt`：严格计算允许买入区间、35 分钟有效期、止损失效、三次独立样本、样本跨度/间隔、最高接受价收紧和整手数量。
- `core/IntradayAdditionJson.kt`、`core/Store.kt`：持久化当天最高接受价、持仓/评估指纹和失效状态；状态损坏时机会链路暂停，不静默清空。
- `monitor/MonitorService.kt::checkIntradayAdditionOpportunity`：从已有 `PortfolioSwing` 候选生成基础快照，采集盘中样本，调用严格窗口策略，再复用 `TradeNotification` 和 `publishDailyAdvice`。
- `core/TimeframeEvidence.kt`、`core/IntradayVolumeContext.kt`：盘中确认依赖已完成 15/30 分钟证据和量能证据；缺证据只等待，不把基础加仓结果直接变成即时买入。

当前 Android 已完成严格窗口的纯策略、状态持久化和后台通知接入。基础候选仍由 `PortfolioSwing` 产生，数量按原候选数量和统一交易门控取更保守结果。

### 卖出通知估算

- `analysis/TradeNotification.kt::estimateSell`：按持仓成本、当前报价和统一门控给出的拟卖数量估算费用后实现盈亏、回收资金和卖后剩余数量。
- `monitor/MonitorService.kt`：持仓风险通知和盘中卖出通知都附带同一组估算字段；真实成交后仍以交易记录计算的结果为准。

### 数据访问

- `core/SinaClient.kt`：新浪行情、日线、搜索和部分板块数据。
- `core/EastMoneyClient.kt`：东方财富板块、资金流和部分市场数据。
- `core/EastMoneyLimitClient.kt`：涨跌停池和涨停相关数据。
- `core/MarketNewsClient.kt`、`TechNewsFeedParser.kt`：新闻抓取、解析和来源降级。
- `core/FundamentalsClient.kt`：A 股财务、公告和基本面数据。
- `core/GlobalFundamentalsClient.kt`、`SecEdgarClient.kt`：港美股搜索和基本面数据。
- `core/Http.kt`：HTTP 请求、超时和基础错误处理。

## 关键数据流

### 实时刷新

入口是 `MonitorService` 的 tick 循环。典型链路是：

```text
MonitorService
  → SinaClient / EastMoneyClient / MarketNewsClient
  → 原始数据校验与缓存
  → analysis 中的策略和状态机
  → Repo.publish* / Repo 字段
  → UI 监听 Repo 变化
  → Notifier 处理满足条件的提醒
```

实时行情的刷新频率、各模块的节流间隔和盘中/盘后边界以 `README.md` 的“刷新节奏”以及 `MonitorService.kt` 中的常量和调度条件为准。

### 持仓分析

持仓问题优先沿下面的链路检查：

```text
MonitorService.runPortfolioAnalysis
  → HoldingAnalyzer.analyzePosition
  → PortfolioAnalysis.evaluate
  → HoldingExitPolicy / TechnicalTrendSnapshotPolicy / 相关风险策略
  → PortfolioSwing.evaluate
  → PortfolioPriorityPolicy
  → Repo.portfolioResults / Repo.portfolioSwing
  → PortfolioBriefCard / PortfolioFragment / ReportActivity
```

关键职责：

- `analysis/PortfolioAnalysis.kt`：生成单只持仓的价格、信号、动作、技术和风险结果。
- `analysis/HoldingExitPolicy.kt`：破位、减仓、退出和深套相关的持仓风险决策。
- `analysis/PortfolioSwing.kt`：只比较已有持仓，判断是否满足波段追加条件和追加股数。
- `analysis/PortfolioPriorityPolicy.kt`：组合内的退出、减仓、风险观察、追加候选和持有观察优先级。
- `ui/PortfolioFragment.kt`：持仓卡片和单股详情；不要把展示文案误认为策略判定。
- `ui/PortfolioBriefCard.kt`：持仓页“今日要点”卡片。

修改持仓买入/加仓逻辑时，必须同时检查 `PortfolioAnalysis`、`PortfolioSwing`、`PullbackAnchorPolicy`、`PortfolioPriorityPolicy`、`AddBatchPolicy`、`MonitorService` 和 `PortfolioFragment`，因为它们分别负责判定、回踩锚位、分配、组合门控、分批口径、发布和展示。

### 自选股、选股和观察池

- `analysis/IndicatorScreener.kt`：指标筛选和选股结果。
- `analysis/StockAnalysis.kt`：个股技术分析的主要组合入口。
- `analysis/TechnicalTrendSnapshotPolicy.kt`：供列表、持仓和 AI 复用的技术趋势快照。
- `analysis/ObservationPool.kt`：观察候选状态机和晋级/降级。
- `analysis/ObservationEligibility.kt`：候选资格、去重、过期和风险/涨停延期。
- `analysis/ObservationEntryPolicy.kt`：观察池入场条件。
- `analysis/AccelerationPolicy.kt`：观察池/买点确认的加速末端暂缓；龙头与已确认独立回踩豁免。
- `analysis/ObservationBuyAlert.kt`：买点确认后的通知资格和通知文案。
- `analysis/FirstBoardReclaimTracker.kt`、`FirstBoardReclaimPolicy.kt`：首板承接转强的形态与窗口判定。
- `core/FirstBoardReclaimObservation.kt`：选股页「🔥 涨停晋级观察」卡片的记录落盘、去重和 14 天保留期；命中只记录、不推送通知。
- `analysis/WatchlistBuyCandidatePool.kt`：自选股买入候选池。
- `ui/ScreenFragment.kt`：候选分组、排序和列表展示。

观察池状态、买点提醒和 UI 分组是三层逻辑，修改其中一层时不要默认另外两层会自动同步。

### 技术、市场和板块环境

- `analysis/Indicators.kt`：DIF、RSI、KDJ、ATR、ADX 等基础指标。
- `analysis/TechnicalSummary.kt`、`TechnicalAssessmentPolicy.kt`：技术评分和技术质量解释。
- `analysis/TrendEntryStrategy.kt`：回踩、突破、止损和入场结构。
- `analysis/MarketRegime.kt`：大盘趋势状态。
- `analysis/MarketRiskPolicy.kt`、`MarketRiskContext.kt`：市场风险和组合级风险背景。
- `analysis/GapFadePolicy.kt`：指数/持仓共用的高开低走、冲高回落判定；`MarketAlertEngine` 用于指数预警，`IntradayTactics` 用于持仓“做T/减仓评估”风险事实，不直接生成卖量。
- `analysis/SectorContext.kt`、`SectorStrengthPolicy.kt`、`StrongSectorRankingPolicy.kt`：行业/概念板块环境和强弱。
- `analysis/EnvContext.kt`：把大盘、板块和历史快照传给个股策略的组合上下文。
- `analysis/AdaptiveRhythmPolicy.kt`：市场节奏和追涨/回踩节奏修饰。

“技术信号不对”“明明个股强但建议变弱”时，除了看个股指标，也要检查 `EnvContext`、`MarketRegime`、板块状态和风险上下文是否覆盖了基础判断。

### 资金流、新闻和基本面

- `analysis/FundFlowPolicy.kt`、`FundFlowDisplayPolicy.kt`：资金流计算、评分影响和展示解释。
- `analysis/MainForceDirectionPolicy.kt`：主力意图文案。
- `analysis/AccelerationPolicy.kt`：放量加速末端研究口径；日报只读标签、观察池晋级暂缓、盘中文案证据。
- `analysis/MarketFundFlowPolicy.kt`：盘中资金环境 v1（行业板块资金合计 + 个股资金合成，只读提示；不改买卖信号和做 T 份额）。
- `analysis/FinancialQualityPolicy.kt`：净现比、扣非比例、高杠杆现金流、存货/营收错配、应收周转和合同负债证据的跨期规则。
- `analysis/Fundamentals.kt`、`FundamentalHealth.kt`、`FundamentalsReport.kt`：基本面分析、财务质量报告和健康等级。
- `core/FundamentalsClient.kt`：基本面接口字段、多期资产负债表和合同负债/预收款解析。
- `docs/fundamental-quality.md`：财务质量阈值、适用范围、数据缺口和 AI 修改入口。
- `analysis/AnnouncementSummary.kt`、`NewsDetailPolicy.kt`、`NewsRelatedQueryPolicy.kt`：公告/新闻关联和摘要规则。
- `analysis/RiskWarningPolicy.kt`：ST 或 *ST、立案、处罚、资金占用、违规担保、审计非标等重大风险警示的识别与报告置顶；公告时间窗与 mac 侧 `risk_warning_policy.py` 同口径。
- `core/MarketNewsClient.kt`：新闻来源和降级策略。
- `analysis/DailyReview.kt`：收盘复盘，把盈亏、环境、新闻和持仓建议组合成报告。

基本面、新闻和技术数据的“缺失”不能直接当作“正常”或“利好”。相关策略通常会保留数据缺口并在报告中披露。

基本面财务质量的具体阈值集中在 `docs/fundamental-quality.md`；修改指标时必须同时检查规则层、`FundamentalResult`、报告输出、健康等级和 `FinancialQualityPolicyTest`。

### 交易记录、仓位和 T+1

- `core/Models.kt`：`Position`、`Portfolio`、`Quote`、交易记录等核心模型。
- `core/TradeLots.kt`、`TradeLotJson.kt`：交易批次、可卖数量和 T+1。
- `core/TradeHistoryProjector.kt`：交易记录投影到持仓/批次。
- `analysis/DayPnl.kt`：当日盈亏、账户口径、成交和费用。
- `core/TradeActionGuard.kt`：交易动作和可执行性校验。
- `ui/TradeHistoryActivity.kt`、`Dialogs.kt`：交易记录和持仓编辑入口。
- `core/PositionAllocation.kt`、`analysis/PositionAllocationPolicy.kt`：独立分仓配置和实时派生显示。

凡是涉及“当前数量、成本、可卖数量、当日买入、做 T”的问题，都要同时看 `Position`、`TradeBook`、`TradeLotLedger` 和 `DayPnl`，不能只看页面上显示的持仓数量。

## AI 综合分析链路

应用内 AI 不是代码仓库索引器，它读取结构化工具结果和应用内知识库：

```text
ToolsFragment
  → AiAnalysis / DeepSeekClient
  → AiToolCatalog 定义只读工具
  → StockAiToolExecutor 执行工具
  → Repo / Store / 网络客户端 / knowledge 资产
  → 模型返回结构化 JSON
```

相关文件：

- `core/AiAnalysis.kt`：AI 输入模型、工具定义、系统提示、工具调用循环和 JSON 解析。
- `ui/StockAiToolExecutor.kt`：工具实际执行，包括行情、技术、基本面、持仓、市场、新闻和知识库。
- `ui/ToolsFragment.kt`：AI 输入、运行状态和结果展示。
- `app/src/main/assets/knowledge/513-strategy.md`：当前应用内策略知识库文件。

应用内 AI 的工具选择规则写在 `AiAnalysisPrompt.system()`。代码问题应优先读源码和测试，不能把 `assets/knowledge` 当成 Android 项目的完整实现文档。

## 持久化和缓存定位

修改“重启后数据消失”“旧结果覆盖新结果”“日期错位”时，优先检查：

- `core/Store.kt`：配置、报告、缓存的读写总入口。
- `monitor/Repo.kt`：内存态和 UI 发布态。
- 对应的 `*Json.kt`：JSON 编解码和兼容字段。
- `core/AnalysisReportCache.kt`：分析报告缓存。
- `core/PortfolioSnapshot.kt`、`ScreenResultJson.kt`：持仓/选股结果快照。
- `core/ObservationPoolJson.kt`、`ObservationSnapshotJson.kt`：观察池状态和历史快照。
- `core/DailyAdviceJson.kt`：同日建议计划和通知事务状态。
- `core/QuoteCache.kt`、`FundFlowHistoryJson.kt`、`TechnicalTrendSnapshotJson.kt`：行情、资金流和技术快照缓存。

检查数据问题时先确认“来源时间、生成时间、缓存时间、当前交易日”四个口径，不要只看 JSON 是否存在。

## UI 到数据的对应关系

| 用户看到的区域 | 主要页面 | 主要数据来源 |
| --- | --- | --- |
| 市场摘要、指数、板块、涨跌停、新闻 | `MarketFragment` | `Repo.marketState`、板块状态、新闻和涨跌停缓存 |
| 自选股/选股列表 | `ScreenFragment` | `Repo.screenResults`、观察池和自选股候选 |
| 持仓卡片和今日要点 | `PortfolioBriefCard`、`PortfolioFragment` | `Repo.portfolioResults`、`Repo.portfolioSwing`、`Repo.marketState` |
| 持仓长报告/复盘 | `ReportActivity`、`DailyReview` | `PortfolioAnalysis`、`DailyReview`、报告缓存 |
| 底仓/滚动仓/风险观察仓 | `PortfolioFragment`、`Dialogs` | `PositionAllocationPolicy`、`Store.positionAllocations` |
| AI 综合分析 | `ToolsFragment` | `DeepSeekClient`、`StockAiToolExecutor` |
| 交易记录和持仓编辑 | `TradeHistoryActivity`、`Dialogs` | `Store.portfolio`、`Store.tradeBook` |
| 通知和提醒 | 系统通知 | `Notifier`、各 Alert/Policy 类 |
| 同日建议计划和执行反馈 | `PortfolioFragment` | `DailyAdvicePolicy`、`Store`、`MonitorService`、`Dialogs` |

页面文案不等于策略状态。判断“为什么没有提醒/为什么显示等待”时，先追到结构化结果，再看 UI 映射。

## 常见问题到文件

| 问题关键词 | 首先查看 | 然后查看 |
| --- | --- | --- |
| 持仓没有买入/加仓建议 | `PortfolioSwing.kt` | `PortfolioPriorityPolicy.kt`、`MonitorService.kt`、`PortfolioFragment.kt` |
| 分仓数量或模式显示不对 | `PositionAllocationPolicy.kt` | `PositionAllocation.kt`、`Store.kt`、`PortfolioFragment.kt` |
| 盘中补仓通知过早或价格不对 | `IntradayAdditionPolicy.kt` | `MonitorService.kt`、`TimeframeEvidence.kt`、`TradeNotification.kt` |
| 卖出通知缺少金额和剩余仓位 | `TradeNotification.kt` | `MonitorService.kt`、`TradeCalculator.kt`、`TradeLots.kt` |
| 持仓建议重复/部分执行不连续 | `DailyAdvicePolicy.kt` | `Store.kt`、`Dialogs.kt`、`MonitorService.kt` |
| 通知失败后仍被当成已执行 | `Notifier.kt` | `MonitorService.kt`、`DailyAdvicePolicy.kt` |
| 持仓一直显示持有观察 | `PortfolioAnalysis.kt` | `HoldingExitPolicy.kt`、`PortfolioPriorityPolicy.kt` |
| 买入提醒没有推送 | `ObservationBuyAlert.kt` | `ObservationPool.kt`、`Notifier.kt`、`StockAlertScope.kt` |
| 观察池没有晋级 | `ObservationPool.kt` | `ObservationEligibility.kt`、`ObservationEntryPolicy.kt`、市场环境策略 |
| 选股结果不对 | `IndicatorScreener.kt` | `StockAnalysis.kt`、`ScreenResultJson.kt`、`ScreenFragment.kt` |
| 技术评分或趋势不对 | `TechnicalSummary.kt` | `Indicators.kt`、`TechnicalAssessmentPolicy.kt`、`TechnicalTrendSnapshotPolicy.kt` |
| 个股强但整体建议变弱 | `EnvContext.kt` | `MarketRegime.kt`、`SectorContext.kt`、`MarketRiskContext.kt` |
| 资金流解释不一致 | `FundFlowPolicy.kt` | `FundFlowDisplayPolicy.kt`、`MainForceDirectionPolicy.kt` |
| 资金环境提示太吵/太安静 | `MarketFundFlowPolicy.kt` | `MonitorService.refreshSectorFlows`、`MarketFundFlowTracker`、通知闸门常量 |
| 复盘内容或优先级不对 | `DailyReview.kt` | `PortfolioAnalysis.kt`、新闻/盈亏数据链路 |
| AI 调错工具或回答过长 | `AiAnalysis.kt` | `StockAiToolExecutor.kt`、工具定义和系统提示 |
| 重启后结果丢失/旧结果复现 | `Store.kt`、`Repo.kt` | 对应 `*Json.kt`、缓存和日期校验 |
| 当日盈亏、可卖数量不对 | `DayPnl.kt` | `TradeLots.kt`、`TradeHistoryProjector.kt`、交易记录模型 |
| 页面显示和后台结果不一致 | 对应 Fragment | `Repo` 发布点、`MonitorService` 写入点、结果模型 |

## 测试定位

测试通常按生产类同名：

- 技术指标和参考一致性：`StockAnalysisReferenceTest.kt`、`IndicatorsTest.kt`。
- 持仓信号和报告：`PortfolioAnalysisTest.kt`、`PortfolioPriorityPolicyTest.kt`、`PortfolioSwingTest.kt`。
- 分仓派生、建议策略和配置存档：`analysis/PositionAllocationPolicyTest.kt`、`analysis/PositionAllocationRecommendationPolicyTest.kt`、`core/PositionAllocationJsonTest.kt`。
- 严格盘中补仓窗口和状态存档：`analysis/IntradayAdditionPolicyTest.kt`、`core/IntradayAdditionJsonTest.kt`。
- 卖出通知金额估算：`TradeNotificationTest.kt`。
- 同日建议连续性：`analysis/DailyAdvicePolicyTest.kt`、`core/DailyAdviceJsonTest.kt`。
- 持仓退出、深套和 T+1：`HoldingExitPolicyTest.kt`、`HoldingTContextTest.kt`、`DayPnlTest.kt`。
- 观察池和买点提醒：`ObservationPoolTest.kt`、`ObservationEligibilityTest.kt`、`ObservationBuyAlertTest.kt`。
- 市场、板块和环境：搜索 `Market*Test.kt`、`Sector*Test.kt`、`TrendAssessmentPolicyTest.kt`。
- UI 展示：搜索 `*UiTest.kt`，并检查对应 Fragment 的状态映射。
- AI：`AiAnalysisTest.kt`、与工具执行器或报告解析相关的测试。

常用验证命令：

```bash
# 编译
./gradlew :app:assembleDebug

# 全部单元测试
./gradlew :app:testDebugUnitTest

# 针对修改范围运行测试
./gradlew :app:testDebugUnitTest \
  --tests com.example.stockmonitor.PortfolioAnalysisTest
```

如果构建需要指定 JDK，按当前机器和 README 中的配置设置 `JAVA_HOME`。测试失败时先区分：本次修改引入的失败、当前工作树已有失败、环境/依赖失败。

## 修改前检查清单

### 改分析规则

- 找到实际调用入口，而不是只修改一个 `Policy` 文件。
- 检查同一结果是否还被 `MonitorService`、`Notifier`、报告和 UI 重新解释。
- 检查数据日期、缺失值、停牌和盘中/盘后边界。
- 为新行为先补回归测试。

### 改数据或缓存

- 同时检查模型、`Store`、`Repo`、JSON 编解码和旧数据兼容。
- 明确哪些字段是实时值、日线值、分析生成时间和交易日。
- 重启、跨日和请求失败时验证旧值保留策略。

### 改 UI 文案或状态

- 确认文案使用的是结构化状态还是重新推断出的状态。
- 检查等待态、缺数据态、失败态和缓存态。
- 如果状态有通知、报告或 AI 工具消费，也同步检查它们。

### 改 AI 工具

- 更新 `AiToolCatalog` 的描述和参数约束。
- 更新 `AiAnalysisPrompt` 的工具选择边界。
- 确认工具只返回回答所需的数据，避免把完整报告和无关行情一起塞入上下文。
- 更新解析测试，确保 JSON 字段和空值策略稳定。

## 文档维护规则

- 只在本索引中记录稳定的入口、职责、调用方向和定位规则，不记录每天变化的行情或详细实现。
- 新增核心模块、改变主要调用链或移动入口时，同步更新本文件。
- 新功能设计放入 `docs/superpowers/plans/` 或 `docs/superpowers/specs/`；完成后的历史方案进入 `docs/superpowers/archive/`。
- 归档文档必须明确标注历史状态，避免 AI 把它当成当前实现。
- 每次较大的架构调整后更新“更新时间”和受影响的文件表。
