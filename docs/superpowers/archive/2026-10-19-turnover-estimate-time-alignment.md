# 两市全天成交额预估：同一时刻对齐归档

**归档日期：** 2026-10-19
**项目：** `stock-monitor-android`
**范围：** 「市场」页两市成交额的全天预估、较昨日同期对比、历史全天成交额与同期量能基线的一致性。
**依据：** 用户核对发现「市场」页全天预估显示 **1.8 万亿以上**，而同花顺预估今日两市总成交额约 **1.5 万亿**；
追问后确认早上 09:45 那次采样显示的是 1.8 万亿，到 10:00 左右又回落到 1.5 万亿。

## 一、数据源先排除

新浪 `hq.sinajs.cn` 与东方财富 `trends2` 两路都核过，金额口径一致，没有重复计算或单位错误：

- 新浪 `sh000001` + `sz399001`（`sz399106` 与 `sz399001` 的成交量/额完全相同，都是深市全市场口径）；
- 东方财富 `trends2` 的 `parts[6]` 是**每分钟**成交额（元），逐分累加后与新浪实时值吻合；
- 昨日（2026-10-16）两市全天合计 **15,554 亿**（沪 7,325 + 深 8,229），就是正常的 1.5 万亿量级。

问题不在数据，在算法。

## 二、根因：分子分母不是同一钟表时刻

全天预估公式是 `今日累计 ÷ 昨日同期 × 昨日全天`，但两项的时刻不一致：

- **今日累计** = 采样**那一刻**的实时累计；
- **昨日同期** = 昨日在 **15 分钟槽位**的值（`TradingCalendar.turnoverSlotMinute` 向下取整）。

成交额曲线早盘极陡且全天单调递增，拿实时累计去比槽位起点会把分母系统性压小。
采样落得越晚，虚高越厉害。用 2026-10-19 真实曲线（昨日 09:45 同期 3,349 亿、昨日全天 15,554 亿）复现：

| 实际捕获时刻 | 今日累计 | 当时 App 预估 | 同时刻对齐后应为 |
| --- | --- | --- | --- |
| 09:45 | 3,053 亿 | 1.42 万亿 | 1.42 万亿 |
| 09:50 | 3,692 亿 | 1.71 万亿 | 1.45 万亿 |
| **09:52** | **3,921 亿** | **1.82 万亿** | **1.46 万亿** |
| 09:55 | 4,213 亿 | 1.96 万亿 | 1.47 万亿 |

用户看到的「1.8 万亿以上」正好落在 09:52 前后那一格；10:00 槽位采样贴着槽位起点，所以又回到 1.51 万亿。

### 为什么采样会落在 09:52

采样只在「槽位变化」时触发一次，而 `lastTurnoverSlot` 是 `MonitorService` 的字段、初值 `-1`。
**服务在槽位中途启动**（打开应用 / 进程被系统回收后 `START_STICKY` 重启）时，第一个 tick 立刻采样，
但标签仍是向下取整后的「09:45 采样」。叠加 tick 循环是 `tick(); delay(15_000)`——延迟在耗时的网络
请求之后，槽位内还会继续漂移。

### 连带影响

- **「较昨日同期」方向会反**：09:52 时显示 `3,921 − 3,349 = +17.1%`，真实同时刻应是 `3,921 vs 4,177 = −6.1%`。
- **同期量能基线（`MarketVolumeBaseline.sameTimeRatio`）同病**：历史侧 `day.optDouble(slotMinute)` 精确匹配
  槽位键，当前侧又是实时累计，比较口径同样错位。
- **`fullDayHistoryYi` 硬编码收盘键 `"900"`**：按真实分钟落盘后，末条采样不一定正好落在 15:00。

## 三、本次修正

核心不变式：**比较双方必须落在同一钟表时刻**。15 分钟只决定「多久记一次」，不再当比较键。

- `TurnoverTracker.Snapshot` 的 `slot` / `slotMinute` 改名为 `sampledAt` / `sampleMinute`，
  取的是 `TimeUtils.minuteOfDay(calendar)` 的**真实采样时刻**；界面标签「09:52 采样」「较昨日同期（09:52）」
  现在标的是真实捕获时间，不再是槽位起点。
- 取昨日同期一律用 `curve.at(sampleMinute)`。东财曲线本来就是 1 分钟粒度，能取到精确值。
- 本地落盘 key 从槽位起点改为真实分钟数（`09:30 = 570`），`Store.turnoverSamples` 结构不变。
- `sameTimeRatio` 历史侧改用 `loadCurve(date)?.at(sampleMinute)`，与当前实时累计对齐。
- `TurnoverCurve` 新增 `isCompleteDay`（末条采样 ≥ 收盘 15:00）；`previousFullDay` 与
  `fullDayHistoryYi` 都只认走完一整个交易日的曲线，半日截断的日子当空档，不进历史、也不当预估基数。
- 快照构造抽成 `TurnoverTracker.buildSnapshot` 纯函数，以便脱离 Android `Store` 单测这条不变式。

## 四、主要源码位置

- `app/src/main/java/com/example/stockmonitor/analysis/TurnoverTracker.kt`
- `app/src/main/java/com/example/stockmonitor/core/Models.kt`（`TurnoverCurve.isCompleteDay` / `CLOSE_MINUTE`）
- `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`（`marketTurnoverRatio`、`refreshMarketRiskContext`）
- `app/src/main/java/com/example/stockmonitor/ui/MarketFragment.kt`
- `app/src/main/java/com/example/stockmonitor/ui/StockAiToolExecutor.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/MarketSummary.kt`
- `app/src/test/java/com/example/stockmonitor/TurnoverHistoryTest.kt`
- `README.md`（「两市成交额的口径」）

## 五、验证

- 回归测试用 2026-10-19 真实曲线形状钉住不变式：
  `predictionComparesTodayWithYesterdayAtTheSameClockMinute` 断言 09:52 采样算出 ≈14,600 亿，
  `slotFloorDenominatorWouldHaveInflatedThePrediction` 断言旧口径 >18,000 亿、新口径 <15,500 亿，
  另覆盖半日截断曲线不被当成全天额、以及真实分钟 key 的全天历史取数。
- 全量单元测试：`./gradlew testDebugUnitTest assembleDebug --offline`，`594` 项测试通过，`0` 失败、`0` 错误、`0` 跳过。
- Debug 构建成功；APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
- Debug APK SHA-256：`f3c6010bc30b251d75fb8604af66a31edaf2e2757470e20d0cdba4439a52e362`。

## 六、边界

- 数字复现用的是 2026-10-19 盘中新浪/东财实测曲线；单元测试用的是同形状的合成曲线，
  不代表实盘预估精度已经和同花顺逐日对齐，只是消除了口径错位这一项系统性偏差。
- 本地兜底路径（东财曲线取不到时）采样仍是约 15 分钟一条的稀疏曲线，
  `at(sampleMinute)` 取「不晚于该时刻的最后一条」，精度低于东财 1 分钟曲线，但方向不再反。
- 本次验证覆盖代码逻辑与 Debug 构建，未安装到手机、未做真实行情长时间连续观察。
- 项目当前不是 Git 仓库，也没有 OpenSpec 变更目录；本次通过本归档文档和源码保存改动，未创建 Git commit。
