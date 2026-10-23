# 盘中风险与量价上下文改造归档

归档日期：2026-10-23

项目：`stock-monitor-android`

## 一、改造范围

本轮把 macOS 版盘中风险、量价上下文、15/30 分钟完成证据、T+1 账本和统一动作守卫迁移到 Android 监控链路，并收紧提醒范围。

重点目标：

- 自选股和观察池只推送有效买点。
- 趋势待确认、周期待确认、站回待确认、做 T 暂缓、强势延续观察、高位整理和二波候选不单独推送。
- 深套风险观察、破位风险提示只保留风险事实或内部状态，不生成未经确认的确定性卖量。
- 未完成的 15/30 分钟 K 线不得参与确认。
- 所有确定性买卖文案必须经过统一动作守卫。
- 卖出数量按 100 股整手向下取整；账本数量不一致时可执行卖量为 0。

## 二、核心规则

### 1. 日级风险上下文

- 以 `(股票代码, 行情交易日)` 缓存日级慢变量，避免盘中每 15 秒重算。
- 接入价格位置、区间低点、失效价、主趋势、近 5 日是否创新低、连续跌破 MA20、事件风险、组合风险、持仓风险和流动性风险。
- `ELEVATED` 包含低位、趋势向下、连续跌破 MA20 和仓位风险。
- `CRITICAL` 包含高位派发、系统性风险、组合风险、事件风险、流动性风险、跌破失效价或跌破区间低点。
- 日报日期与行情日期不一致时，不复用旧日报风险上下文。

### 2. 完成 K 线证据

- `IntradayBar` 增加 `completed` 状态。
- live preview K 线只作为当前槽位预览，不能直接进入完成 K 线证据。
- `TimeframeEvidence` 只接受当前交易日最新已完成 15/30 分钟槽位，并同时校验 `completed` 标记。
- `IntradayBarWindow.complete` 不再把未完成 K 线视为可确认窗口。
- 双周期决策输入同时过滤完成状态和最新完成槽位时间戳。

### 3. 盘中量价上下文

- 两市成交量采用预计全天成交额相对近 5 个完整交易日成交额中位数的倍数。
- 个股量能使用按交易时间折算的累计量比。
- 当前 15 分钟量能使用最新完成 15 分钟 K 线相对前 20 根中位数的倍数。
- 买入需要个股量能、两市成交量、当前 15 分钟量能和 15/30 分钟结构同时确认。
- 上涨放量冲高回落、上影线拒绝、量能缺失或两市缩量时降级为观察。
- 下杀承接只缩小首批卖出至 100 股，并继续观察是否收复支撑；不表述为确定的主力资金入场。

### 4. 统一动作守卫

- 先生成结构化意图，再由 `TradeActionGuard` 统一决定 `OBSERVATION`、`RISK_FACT`、`NEXT_DAY` 或 `ACTIONABLE`。
- 买入必须有明确失效价，且通过 15/30 分钟、量能和仓位边界检查。
- 单根破位只报告风险；连续两根已完成 15 分钟 K 线才进入首批减仓评估。
- 连续三根且日级风险为 `CRITICAL` 时，只有 `EXIT_POSITION` 可进入清仓退出路径。
- `REDUCE_POSITION`、`HIGH_REDUCE_POSITION`、`BREAK_SUPPORT` 和 `BREAKOUT_REVERSAL_RISK` 统一按分批减仓处理，不升级为全量退出。
- `BREAK_NOTICE`、`WATCH`、`DEEP_T` 不生成确定性卖量。
- 做 T 的 1/3 数量不足一手时不回退为卖出全部可卖仓位。

### 5. T+1 与账本

- `TradeLotLedger.availability` 增加账本一致性和可执行卖量字段。
- 持仓数量与交易批次数量不一致时，`actionableSellableToday` 和下一交易日可执行卖量均为 0。
- 未记录买入日期的残股与今日买入分开说明；前者不会随下一交易日自动解锁。
- 所有卖出数量按 100 股整手向下取整，不生成零碎卖出。

## 三、通知范围

- 非持仓股只允许自选股或观察池的有效买点确认提醒。
- 个股跌幅、急跌、持仓信号、做 T、反弹跟踪、封板、炸板和突破风险不向非持仓股推送。
- 大盘提醒不适用持仓过滤。
- 自选股买点使用独立候选池，不混入观察池列表和纸上交易。
- 待确认买点在守卫暂不满足时保留到后续 tick；候选退出买点或跨日后清除，避免旧买点补推。

## 四、主要改动文件

### 核心策略与数据

- `app/src/main/java/com/example/stockmonitor/core/DailyRiskContext.kt`
- `app/src/main/java/com/example/stockmonitor/core/IntradayVolumeContext.kt`
- `app/src/main/java/com/example/stockmonitor/core/TimeframeEvidence.kt`
- `app/src/main/java/com/example/stockmonitor/core/IntradayBars.kt`
- `app/src/main/java/com/example/stockmonitor/core/TradeActionGuard.kt`
- `app/src/main/java/com/example/stockmonitor/core/TradeLots.kt`
- `app/src/main/java/com/example/stockmonitor/core/Store.kt`

### 分析、通知与监控

- `app/src/main/java/com/example/stockmonitor/analysis/IntradayTactics.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/TradeNotification.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/StockAlertScope.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/ObservationBuyAlert.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/BreakoutRiskPolicy.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/WatchlistBuyCandidatePool.kt`
- `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- `app/src/main/java/com/example/stockmonitor/monitor/HoldingAnalyzer.kt`
- `app/src/main/java/com/example/stockmonitor/ui/PortfolioFragment.kt`

### 回归测试

- `app/src/test/java/com/example/stockmonitor/core/DailyRiskContextTest.kt`
- `app/src/test/java/com/example/stockmonitor/core/IntradayVolumeContextTest.kt`
- `app/src/test/java/com/example/stockmonitor/core/TimeframeEvidenceTest.kt`
- `app/src/test/java/com/example/stockmonitor/core/IntradayBarsTest.kt`
- `app/src/test/java/com/example/stockmonitor/core/TradeActionGuardTest.kt`
- `app/src/test/java/com/example/stockmonitor/TradeNotificationTest.kt`
- `app/src/test/java/com/example/stockmonitor/IntradayTacticsTest.kt`
- `app/src/test/java/com/example/stockmonitor/StockAlertScopeTest.kt`
- `app/src/test/java/com/example/stockmonitor/WatchlistBuyCandidatePoolTest.kt`

## 五、验证记录

- `git diff --check`：通过。
- 定向 Gradle 测试已尝试运行，但在 Gradle wrapper 创建 `.gradle` 锁文件时被环境拒绝：`Operation not permitted`。
- 因此本归档记录代码审查和静态检查结果，不宣称 Gradle 编译或单元测试通过。
- 当前改动未提交、未建分支、未安装 APK。

## 六、已知边界

- 两市成交额只能衡量市场活跃度，不能证明主力净流入。
- 下杀放量既可能是承接，也可能是派发，必须结合下影线、收复中点和后续止跌。
- `liquidityRisk` 已接入上下文，但当前不独立放大卖出数量。
- 历史导入仓位的买入日期可能未知，T+1 可卖数量依赖账本日期质量。
- 开盘初期 15 分钟量能历史基线较短，仍依赖双周期和连续确认降低噪声。

## 七、归档一致性审查

- **清仓条件**：当前代码只有退出优先意图（`IntradaySignalType.EXIT_POSITION`，或持仓退出阶段
  `HoldingExitStage.EXIT`）才可能进入 `RISK_EXIT`；`REDUCE_POSITION`、`HIGH_REDUCE_POSITION`、
  `BREAK_SUPPORT` 和 `BREAKOUT_REVERSAL_RISK` 即使达到 `CRITICAL` 也只进入分批减仓。
- **通知与内部状态**：`OBSERVATION`、`RISK_FACT`、`NEXT_DAY` 可进入策略状态、报告或诊断，
  但监控链路只把 `ACTIONABLE` 结果送到通知；这与“纯观察、待确认不弹窗”一致。
- **T+1 与账本**：旧规格中的“账本与持仓取较小值”已废止；账本数量不一致时今日和下一交易日
  可执行卖量都强制为 0，不能用展示估算量代替执行量。
- **候选确认**：候选晋级使用时间分离的新行情复评（15–45 分钟窗口、三板及以上三轮），
  不把累计成交量自然增长当作确认条件。
- **历史验证**：`docs/superpowers/archive/` 和本轮早期记录中的测试数、构建和 APK 哈希均为各自日期的历史快照，
  不是当前未提交改动的验证结论；当前环境的 Gradle wrapper 锁文件仍受权限限制。
