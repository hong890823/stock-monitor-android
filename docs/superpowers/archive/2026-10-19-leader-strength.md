# 龙头强度状态机（炸板守住）与板块归类编辑 · 归档

**归档日期：** 2026-10-19
**项目：** `stock-monitor-android`
**范围：** ① 龙头强弱从二值（封板/炸板）改成「守住高位线」状态机；② 板块归类可编辑
（归类内个股增删、持仓挂靠、自定义归类）；③ 板块龙头卡片逐股给涨跌幅与成交额。
**前置：** `docs/superpowers/archive/2026-10-19-sector-limit-up-t-defer.md`（龙头封板暂缓做T）
与 `docs/superpowers/plans/2026-10-19-leader-strength-resume.md`（本轮续做说明，做完即归档）。

## 一、龙头强度状态机：炸板不等于走弱

### 问题

上一轮的判定是二值的（`封板 → 暂缓做T`、`炸板 → 恢复做T`），太粗。用户指出：**炸板后能长时间稳在
水上 8 个点以上，说明砸出来的筹码被买盘吃掉了、承接极强**，这时候提示减机动仓很可能正好卖在回封前一秒。

### 判定规则（`core/LeaderStrength.kt`）

| | 条件 |
| --- | --- |
| **强** | 当前封板（`changePct ≥ limitPct − 0.15`）；**或** 今日曾封板 且 现价 `≥ limitPct − 2.0` |
| **弱** | 今日没封过板；**或** 跌破高位线并**持续 ≥ 3 分钟** |

关键设计（不要改回去）：

1. **时长门槛放在「转弱」那一侧。** 跌破高位线要持续 3 分钟才确认走弱（`HOLD_MS`），刚炸那一瞬的
   抖动不翻转。反过来若放在「转强」侧，炸板后会空出 3 分钟让你去卖——正好卖飞。
   封板转强不设门槛（封板本身就是确定信号）。
2. **「今日曾封板」用 `Quote.high` 反推**，不靠状态记忆：`(high − prevClose) / prevClose × 100 ≥ limitPct − 0.15`。
   服务中途重启也不会把早上封过的板忘掉。转弱计时 `weakSinceMs` 才是状态，跨日按 `quote.quoteDate` 重置。
3. **`STRONG_GAP_PCT = 2.0` 是「离涨停的距离」**，与涨跌停幅度无关：创业板同样是 2 个点（水上 18）。

### API 分工

- 判定侧（含 3 分钟转弱确认）：`LeaderStrengthTracker.strength` / `strongNames`——有状态，MonitorService 持有。
- 展示侧（纯计算、不碰计时）：`LeaderStrengthTracker.sealedNames`（此刻封板）、
  `LeaderStrengthTracker.heldHighNames`（封板或守住高位线）。卡片 `🔴` = sealedNames，
  `⚡` = heldHighNames − sealedNames（炸板守住高位）。3 分钟缓冲是决策侧抖动门槛，
  卡片只回答「现在还在不在高位线」。

### 接线

- `MonitorService`：`leaderStrengthTracker` 字段；换日清理（`holdingTTracker.clear()` 旁）一并 `clear()`；
  `checkIntradaySignals` 里 `sealedNow(...)` 换成 `leaderStrengthTracker.strongNames(...)`。
- `IntradayTactics.Input`：`sealedLeaderCount/Names` → `strongLeaderCount/Names`；
  `leadersSealed` → `leadersStrong`。`T_SELL_DEFERRED` 正文改「同板块龙头 X 仍强（封板或炸板守住高位线）」。
- `SectorLeaders.sealedNow` 与 `SectorLeaders.LIMIT_UP_TOLERANCE` 删除（逻辑归 `LeaderStrengthTracker`）。

## 二、板块归类可编辑（`core/BoardGroups.kt`）

### 问题

F10 的行业粒度经常和实盘的「同板块」对不上（中天科技挂在「通信设备」而不是「光通信」）。
用户要求：**归类内个股和持仓股可编辑，也可新增板块归类**。

### 模型

- `BoardGroup`：`id`（东财板块码 `BK0438`，或 `custom:` + 名称）、`name`、
  `members: Map<code, name>?`（**null = 没动过**，照拉东财成分；非 null = 人工名单生效）、`custom`。
- `BoardGroupBook`（`Store.boardGroups` 落盘）：只存动过的归类 + 持仓挂靠 `holdingAssignments`。
  `groupIdOf(code, f10BoardCode)` 挂靠优先、F10 兜底；`membersOf(id, autoMembers)` 人工名单优先。

### 「市场 → 📌 板块龙头 → 归类管理」（`ui/BoardGroupDialogs.kt`）

| 动作 | 行为 |
| --- | --- |
| 点归类 | 勾选 = 归入本类；勾掉的留在候选列表（自动成分 ∪ 人工名单）随时勾回来；可按代码/名称添加个股 |
| 编辑持仓归属 | 每只持仓选归类（默认「自动（F10·行业名）」），龙头联动跟着新归类走 |
| 新建归类 | 起名后进编辑器加成分股；没有东财板块码，成分就是你加的那几只 |
| 删除归类 / 恢复自动成分 | 自定义归类整组删除（挂靠它的持仓回 F10 自动）；改过成分的自动归类恢复东财名单 |
| 编辑持仓 | 复用持仓页已有的追加 / 编辑股数成本 / 删除对话框 |

保存原则与「调整龙头」一致：**保存后按你勾的来**，不再被东财成分改写；没动过就别写人工名单
（白写一份会把自动成分冻住，以后东财加新成分也进不来）。

### 接线

- `MonitorService.refreshSectorLeaders`：归类 id = 持仓挂靠后的实际归属 ∪ 自定义归类；
  成分 = 人工名单（有则生效）否则东财成分；编辑器候选用全集（自动 ∪ 人工）。
  涨跌幅 / 成交额照样从实时行情取，自定义归类也能排名。
- `checkIntradaySignals` 的龙头联动按 `groupIdOf` 找归类，不再硬绑 F10 板块码。

## 三、板块龙头卡片显示成交额和涨跌幅

`MarketFragment` 板块龙头卡片逐股一行（`UiKit.quoteRow`）：名称 + 强弱标记、现价、涨跌幅、
成交额（亿）。行情跟着每 15 秒的实时行情走，龙头代码本来就在拉取列表里，**不额外打接口**。

## 四、主要源码位置

| 文件 | 内容 |
| --- | --- |
| `core/LeaderStrength.kt` | 强度状态机 + `sealedNames` / `heldHighNames` |
| `core/BoardGroups.kt` | 归类模型与落盘编解码（`BoardGroup` / `BoardGroupBook`） |
| `core/Store.kt` | `boardGroups` 持久化 |
| `monitor/MonitorService.kt` | tracker 挂载、换日清理、归类感知的龙头刷新与做T联动 |
| `analysis/IntradayTactics.kt` | `strongLeader*` 字段、`leadersStrong`、文案 |
| `ui/MarketFragment.kt` | 卡片（成交额 / 涨跌幅 / 🔴⚡） |
| `ui/BoardGroupDialogs.kt` | 归类管理对话框链 |

## 五、验证

```
./gradlew testDebugUnitTest assembleDebug --offline
```

- 单元测试：**621 个，0 失败**（含新建 `core/LeaderStrengthTest.kt` 8 例、
  `core/BoardGroupBookTest.kt` 8 例；`SectorLeadersTest` 改测 `strongNames`；
  `IntradayTacticsTest` 字段改名 + 断言文案）。
- APK SHA-256：`a7e763dd0957591f9b13ecd357ca2011ca3a9d5f3abc59f854d1ede65697a88e`
  （`app/build/outputs/apk/debug/app-debug.apk`，7,280,983 字节）。

## 六、边界

- 强度判定只作用于**做T降级**（暂缓 / 恢复），不改风险侧（破位、退出）——那套有自己的确认链。
- 3 分钟转弱确认用的是判定侧调用节奏（约 15 秒一轮）；停在高位线下方满 3 分钟才翻弱。
- 归类人工名单覆盖东财成分后，东财新加的成分**不会自动进名单**（「恢复自动成分」才回到自动）。
- 自定义归类没有板块行情（涨跌 / 资金流仍按 F10 行业或各自数据源），只有龙头联动按归类走。
- 项目当前不是 Git 仓库，也没有 OpenSpec 变更目录；本次通过本归档文档和源码保存改动，未创建 Git commit。
