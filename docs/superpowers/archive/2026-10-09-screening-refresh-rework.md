# 选股链路刷新模型重构与策略复核归档

**归档日期：** 2026-10-09
**项目：** `stock-monitor-android`
**依据：** 本轮多轮需求讨论：全市场扫描每 30 分钟数千根日K请求量过大；三档策略池子不同应拆两套刷新；盘中不需要自动扫选股，需要时手动点，盘后自动一轮并缓存即可；随后对选股策略逻辑做全面复核。

## 一、刷新模型（盘中不自动 + 两个手动按钮 + 盘后自动一次）

- **盘中不自动扫描**：原有的 30 分钟槽位自动触发整个移除，需要时手动刷新。
- **两个手动按钮**，对应两套扫描、两把独立锁（互不阻塞）：
  - 「重扫指标」：成交量前 200，只更新严格 / 宽松两档，秒级；
  - 「深扫全市场」：趋势 + 513（顺带全量刷新指标档），数千根日K、数分钟，带独立进度。
- **盘后 18:00–06:00 自动全市场深扫一次**：成功即停，失败 90 分钟重试、最多 8 次（原夜间
  调度保留）。调度状态只在夜间窗口内更新，白天不再被「日间暂停」覆盖扫描状态。
- 调度边界：手动深扫进行中不消耗夜间尝试次数；**夜间窗口内的手动深扫记为该窗口成功**
  （避免自动再跑一遍全市场），但只在窗口内记——白天记会污染 window 键、导致整晚不扫。

## 二、两套扫描与结果合并

- `IndicatorScreener` 拆两个入口（共用判定核心）：`screenIndicators()` 前 200 只算指标两档、
  `screenPatterns()` 全市场趋势 + 513（指标池直接取全市场列表的成交量前 200，不多拉列表）。
  指标池口径不变：动量信号依赖成交活跃度，前 200 合理；趋势 / 513 是趋势起点与埋伏形态，
  必须全市场（前 200 的票往往已走了一段）。
- 结果合并（`MonitorService.mergeScreenResult`）：
  - 按列表各自保留：某档本次为空、上次非空**且在保质期内**则沿用；
  - **旧命中超 3 天自动清**（`ScreenResultJson.STALE_RETENTION_MS`），不拿几天前的命中冒充
    现状；未知时间戳按不保留处理；
  - 指标组与形态组各有各的「上次刷新」时间，谁完整刷新谁更新；
  - 指标快扫不动趋势 / 513 / 统计；513 统计块保留（复核中修掉合并时统计被清零的问题）。

## 三、结果持久化（`core/ScreenResultJson.kt`）

- 选股结果落盘（含两组刷新时间），服务启动回填——盘中不自动扫之后，重启不再空白；
  超过保质期的组不回填。
- **NaN 落盘崩溃修复**（复核中发现的真 bug）：RSI 在平均跌幅为 0 时是 NaN（宽松档合法
  存在），`JSONObject.put` 对 NaN 直接抛 `JSONException`，发生在扫描成功后的落盘步骤、
  沿协程传播。所有 double 安全写入（NaN 跳过、解码回 NaN），落盘失败只记日志、不回滚内存结果。
  旧格式缓存（单一 `generated_at`）自动退回两组同源。

## 四、策略复核与修正

- 513 形态是短线事件：**倍量阳信号日（含缩量确认）距最新完整交易日超 3 个交易日的
  「已确认」不再列出**，不再永久挂表冒充今天的买点。
- 保留旧结果的时间戳修正：保留内容的时间用**它自己的生成时间**，不是「现在」。
- 回踩 M20 的「有效跌破」统一用 0.5% 破位容差（`MA20_BREAK_TOLERANCE_PCT`）：贴着 M20
  下方 0.1% 震两天不再压掉回踩信号，与模块其它「跌破」口径一致（`TrendEntryStrategyTest`）。
- 复核确认无误：指标两档三条件与说明一致；513 阶段机（观察 → 倍量阳 → 3 天缩量确认）与
  失效规则；趋势入场（突破次日守住 / 回踩不破确认、突破失败转 NONE、回踩缩量 + M20 上行、
  大盘偏弱不确认新开仓）；排序函数；`withTodayBar` 盘中 / 收盘口径。

## 五、选股页 UI 重构

布局跟着两套刷新逻辑走，动作和列表同卡：

- 「📡 扫描状态」：状态 / 进度 / stale 提示（含「超过 3 天自动清」说明）；
- 「📊 指标两档 · 快扫」：池子、耗时、**上次刷新**、策略规则、「重扫指标」按钮、
  严格 / 宽松列表（各上限 100 只）；
- 「🔭 趋势与 513 · 深扫」：池子、耗时、**上次刷新**、「深扫全市场」按钮、趋势列表、
  513 统计与列表（上限 100 只）；
- 「ℹ️ 说明」不变。文案修正：日线口径「近 120 日」（原「90 日」与实际不符）。

## 六、明确保持的不变量

- 严格策略仍要求零轴上方金叉 + RSI < 30 + KDJ 低位金叉；宽松仍为三项取二。
- 513 仍只使用完整交易日判定，不把盘中未完成 K 线判为确认；ST 股排除。
- 突破前高仍只是「突破观察」，不直接进入买入确认；趋势走弱不加仓、不摊平。
- 数据源、日K前复权口径、量比口径不变；未引入新的网络依赖。

## 七、验证记录

- `./gradlew :app:testDebugUnitTest --rerun-tasks`：全绿，258 条单测（强制重跑核实，非混合旧结果）。
- 新增用例：`ScreenResultJsonTest`（四档往返、NaN 往返、旧格式回退、保质期判定、损坏负载）、
  `TrendEntryStrategyTest`（容差内贴线不算趋势转弱、超容差连续收破算）、
  `FiveOneThreeScreenerTest` 补过期用例（3 天内保留 / 超期剔除）。
- 未执行真机验证；手动深扫的耗时与接口限速需实盘观察，若手动深扫频繁可再加日K缓存。

## 八、关键文件

- `app/src/main/java/com/example/stockmonitor/analysis/IndicatorScreener.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/FiveOneThreeScreener.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/TrendEntryStrategy.kt`
- `app/src/main/java/com/example/stockmonitor/core/ScreenResultJson.kt`
- `app/src/main/java/com/example/stockmonitor/core/Store.kt`
- `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`（夜间调度、两套扫描、合并落盘）
- `app/src/main/java/com/example/stockmonitor/monitor/Repo.kt`（两组刷新时间）
- `app/src/main/java/com/example/stockmonitor/ui/ScreenFragment.kt`
