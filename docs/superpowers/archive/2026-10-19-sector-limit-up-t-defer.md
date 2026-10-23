# 冲高做T：板块龙头封板联动与回落分级归档

**归档日期：** 2026-10-19
**项目：** `stock-monitor-android`
**范围：** 盘中「冲高做T」的触发时机、板块龙头封板对做 T 的降级、龙头炸板后的提醒恢复，
以及「真回落」提醒不被吞掉。
**依据：** 用户反馈「中天科技」被提示冲高做T，做完T后股价继续冲高；当时同板块的「亨通光电」已涨停。
追问程序是不是没考虑这个因素。后续补充两条口径：**只认龙头，小票不算**；**龙头炸板要恢复做T提醒**。

> 后续 `2026-10-19-leader-strength.md` 将“炸板即恢复”进一步收紧为“炸板守住高位仍强，跌破高位持续
> 3 分钟才转弱”。本文保留当时的问题定位和分级设计，当前行为以 `LeaderStrengthTracker` 为准。

## 一、结论：没考虑，而且就算考虑了也拦不住

### 1. 板块涨停根本不在判定输入里

`IntradayTactics.Input` 里跟板块有关的字段只有板块**整体**的涨跌、量能、主力资金
（`sectorChangePct` / `sectorTurnoverRatio` / `sectorMainNetInflow` / `sectorMainNetRatio`），
没有任何「板块内个股涨停 / 龙头是否封板」。亨通光电涨停这件事，判定逻辑不知道。

数据其实早就有：`Repo.limits`（全市场涨跌停扫描）和 F10 板块归属（`SectorContext.industry`）
都在，但前者只出涨跌停榜、后者只进持仓报告，从没接到做 T 判定上。
两者也**join 不上**——涨跌停榜不带行业，F10 归属只覆盖持仓自己。

### 2. 更根本：「强势就别做T」在结构上不可能拦住做T

四个分支的条件里，三个「暂缓做T」分支全要求 `!rollover`，而 `tSell` 要求 `rollover`：

| 分支 | 关键条件 |
| --- | --- |
| `tTrendHold` / `tHold` / `tHoldCaution` | `rally && !rollover && …` |
| `tSell` | `rollover && rally && …` |

`rollover` = 连续回落 且 `retreatPct ≥ max(0.5%, ATR%/4)`。**两者互斥**：股价一旦从日内高点
回落到该阈值，三个 hold 分支全部撤防，`tSell` 成为唯一出口。那些「强势环境·暂缓做T」提示
只覆盖**还没回落**的阶段，正好在要做T的那一刻失效。

### 3. 那道强势门槛也够不到

`tHold` / `tHoldCaution` 的前提是 `MarketEnvironmentQuality.strong / cautious`，两者都要求
`highTurnover`（全天预估 ≥ **2 万亿**）。2026-10-19 全天预估约 1.5 万亿 → `status = NORMAL`，
两个分支当天本来就不可能触发。唯一可能拦一下的 `tTrendHold` 同样要求 `!rollover`。

## 二、本次修正

设计口径（用户拍板）：**只认龙头封板；小票涨停不算；龙头炸板恢复做T；只降优先级，不静音。**

### 结构：浅回落 / 真回落分级

- 新增 `T_SELL_DEFERRED`（「冲高回落·暂缓做T」）：龙头封板 + 浅回落 → 只提示观察、
  **不给减仓股数**，正文写清哪几只龙头在封板、以及「回落加深到哪个价位」或「龙头炸板」再评估。
- `tSell` 的触发从 `rollover` 收紧到 `deepRollover`：回落须达到
  `max(0.5%, ATR%/4) × DEEP_RETREAT_FACTOR(2.0)`。**龙头无封板或数据暂缺时倍数为 1.0，
  与原 `rollover` 完全相同，行为不变。**
- `tSell` 正文补一句「同板块龙头仍在封板，但回落已加深到 2 倍阈值，按真回落处理」，
  让人看得出系统看到了封板、是权衡后才给的减仓建议。

### 只认龙头：`SectorLeaders` + 「板块龙头」功能

- `EastMoneyClient.boardMembers(boardSecid)` 拉东财板块成分（`clist` 的 `fs=b:BK****`）。
  只解析 `f12`/`f14`/`f3`——这几个字段是 `industryBoardChanges` 在生产链路一直用的。
- **默认龙头 = 板块内当日成交额前 5 名**（`SectorLeaders.SIZE`）。跟风小票排不进前 5，
  它们涨停也不参与降级。
- 排序口径选**新浪当日成交额**而不是东财流通市值：成交额与两市成交额同一数据源、已验证可靠；
  写这份代码时东财接口正在拒连（`push2` / `push2his` 均空响应），`f20`/`f21` 市值字段无法在线核实。
  若要改成按市值排龙头，只需换 `SectorLeaders.of` 的排序键。

**接口量：名单跟着板块数据刷新（30 分钟一轮），不做盘中扫描。**
初版把定名单挂在 5 分钟那条线上、每轮扫全部成分股再拉一遍行情来排成交额——龙头盘中根本不会这么变，
纯浪费。现在挂在 `SECTOR_INTERVAL_TICKS`（30 分钟）与 `refreshSectors` 同轮，
只拉持仓实际归属的那些板块。封板/炸板另算（见下），**不额外打接口**。

**手动指定 / 排除（`LeaderOverrides`，落在 `Store.sectorLeaderOverrides`）**
「市场 → 📌 板块龙头」卡片显示 持仓 → 板块 → 龙头名单（含谁在封板），
右上「调整龙头」打开勾选框列表（勾选 = 算龙头）：

- 可把排名外的票**提进来**（指定），也可把自动挑中的票**踢出去**（排除）；
- 保存后**按勾选的来**，不再被自动排名改写；没勾的记进 `excluded`，
  免得它以后成交额上来了又被自动捞回来；
- 「恢复自动」清掉人工修正，重新按成交额前 5 挑。

归属关系（持仓 → 板块）本来就走 F10（`Repo.sectorContexts`）；本轮把「板块 → 龙头」显式存下来
（`Repo.sectorLeaders` / `Repo.sectorMembers`），并让你能看见、能改。

### 龙头炸板 → 恢复做T

- **封板/炸板用实时行情逐轮判**，不看快照：龙头的代码进 `fetchData` 的行情拉取列表，
  每 15 秒就有新报价，`SectorLeaders.sealedNow(quotes)` 现算。
  所以龙头炸板后**下一轮 tick** `sealedLeaderCount` 就归 0，做 T 提醒立刻恢复，不留刷新窗口。
- 信号去重按 `type` 名记跃迁（`signalAlertActive`）：`T_SELL_DEFERRED → T_SELL` 是新跃迁，
  会重新推送，不会被吞。
- 龙头**名单**本身 10 分钟 TTL（`SECTOR_LEADERS_MAX_AGE_MS`）；过期当「暂缺」，
  退回原做T口径，不拿旧名单压掉减仓提示。

### 顺带修掉的口径错位

`SinaClient.limitPctFor` 原来用 `code.substring(2)` 去掉 `sh`/`sz` 前缀，但东财板块成分给的是
**裸 6 位码**：`300750` 会被截成 `0750`，把创业板的 20% 错判成 10%，龙头封板会漏判。
改成 `code.takeLast(6)`，两种写法都判对（单测锁住）。

## 三、主要源码位置

- `app/src/main/java/com/example/stockmonitor/analysis/IntradayTactics.kt`（分级判定 + 新信号）
- `app/src/main/java/com/example/stockmonitor/core/Models.kt`（`BoardMember` / `SectorLeaders` / `LeaderOverrides`）
- `app/src/main/java/com/example/stockmonitor/core/EastMoneyClient.kt`（`boardMembers`）
- `app/src/main/java/com/example/stockmonitor/core/SinaClient.kt`（`limitPctFor` 裸码）
- `app/src/main/java/com/example/stockmonitor/core/Store.kt`（`sectorLeaderOverrides` 落盘）
- `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`（`refreshSectorLeaders`、行情列表）
- `app/src/main/java/com/example/stockmonitor/monitor/Repo.kt`（`sectorLeaders` / `sectorMembers`）
- `app/src/main/java/com/example/stockmonitor/ui/MarketFragment.kt`（「📌 板块龙头」卡片 + 勾选编辑器）
- `app/src/test/java/com/example/stockmonitor/IntradayTacticsTest.kt`
- `app/src/test/java/com/example/stockmonitor/core/SectorLeadersTest.kt`
- `app/src/test/java/com/example/stockmonitor/core/LimitUpThresholdTest.kt`
- `README.md`

## 四、验证

- 新增 9 条测试，锁住三条硬要求：
  - **只认龙头**：`onlyTopTurnoverMembersCountAsLeaders` —— 8 只成分里只有成交额前 5 进龙头集合，
    「太辰光」这类跟风小票出局。
  - **手动指定 / 排除**：`manualOverridesCanPinAnOutsiderAndExcludeAnAutoLeader`
    —— 把排名外的「中际旭创」提进来、把排名内的「新易盛」踢出去，两者都生效。
  - **炸板恢复**：`sealedNowTracksLiveBoardSoBreakingTheBoardRestoresTSell`
    —— 亨通从 9.98% 回到 8.5% 后立刻不算封板；
    `brokenBoardOrMissingLeaderDataRestoresOrdinaryTSell` —— 封板数归 0 或名单暂缺都恢复 `T_SELL`。
  - **不静音**：`deepRetreatStillGivesTSellQuantityWhenLeaderIsSealed` —— 回落真加深照样给股数。
  - 另有 `sealedLeaderDefersShallowTSellWithoutGivingQuantity`、`missingLeaderQuotesAreTreatedAsNotSealed`、
    `quoteCodeDerivesMarketFromCodePrefix`、`limitPctAcceptsPrefixedAndBareCodesAlike`。
- 全量单元测试：`./gradlew testDebugUnitTest assembleDebug --offline`，`603` 项测试通过，
  `0` 失败、`0` 错误、`0` 跳过。
- Debug 构建成功；APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
- Debug APK SHA-256：`921799f6c290c8f35b2a7f5cd1fd5c2b2de4df44872562cb4fe110e1e2890870`。

## 五、边界

- **龙头 = 成交额前 5 是默认口径**，不是行业公认的「龙头」定义。按流通市值排更贴近「小票不算」
  的本意，但东财市值字段本次没能在线核实，先用已验证的成交额。要改只动
  `SectorLeaders.of` 的排序键。`SectorLeaders.SIZE` 也是拍的。
  **人工指定 / 排除就是给这个默认口径留的补丁口**——排名挑错了你自己勾。
- **手动保存后按勾选的来**（`pinned` = 勾选全集，`excluded` = 未勾全集），
  自动排名不再改写该板块。这是刻意的：勾过就该是你说的那样。
  代价是之后新冒出来的票不会自动进名单，要再开一次「调整龙头」；「恢复自动」可一键退回。
- **回落加深到 2 倍阈值（`DEEP_RETREAT_FACTOR`）是拍的系数**，不是回测出来的。
  它只解决「别在龙头封板途中被震下车」，不保证卖点最优。实盘命中率需要你自己积累后再调。
- **降级的反面是可能坐过真正的高点**：龙头封板时的首次回落一律不给股数。龙头炸板后立刻恢复。
- 板块归属拿不到（F10 失败）或龙头名单过期时 `sealedLeaderCount` 为 null，
  **退回原做T口径**——宁可照常提醒减仓，也不因为缺数据把提示吞掉。
- 龙头代码走新浪实时行情，前缀由 6 位码推（沪 6 开头、北交所 8/4/920、其余深市）。
  推错前缀时查不到行情 → 按「未封板」处理，同样 fail-safe 到照常提醒。
- 名单 30 分钟一轮：新调入的持仓要等下一轮才有板块归属和龙头；期间按「暂缺」处理，不压做T。
- 本次验证覆盖代码逻辑与 Debug 构建；**东财板块成分接口在写代码时无法在线核实**（拒连），
  `boardMembers` 的解析沿用 `industryBoardChanges` 已在生产使用的字段约定，未做真实请求回归。
  未安装到手机、未做真实行情验证。
- 项目当前不是 Git 仓库，也没有 OpenSpec 变更目录；本次通过本归档文档和源码保存改动，未创建 Git commit。
