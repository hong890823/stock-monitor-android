# 龙头强度状态机（炸板守住）· 续做说明

**建立日期：** 2026-10-19
**项目：** `stock-monitor-android`
**状态：** ✅ **已完成并归档** → `docs/superpowers/archive/2026-10-19-leader-strength.md`
（同轮还做了：板块归类可编辑 `core/BoardGroups.kt` + 归类管理对话框、板块龙头卡片显示成交额/涨跌幅）
**前置：** `docs/superpowers/archive/2026-10-19-sector-limit-up-t-defer.md`（已归档完成的那一轮：龙头封板暂缓做T）

## 一、本轮要解决的问题

用户指出：**炸板不等于走弱**。炸板后能长时间稳在水上 8 个点以上，说明砸出来的筹码被买盘吃掉、
承接极强，这时候提示减机动仓很可能正好卖在回封前一秒。

上一轮的判定是二值的（`封板 → 暂缓做T`、`炸板 → 恢复做T`），太粗。
本轮改成「看是不是还贴在涨停附近的高位」，并加时长门槛滤掉刚炸那一瞬的抖动。

## 二、已完成

**`app/src/main/java/com/example/stockmonitor/core/LeaderStrength.kt`（116 行，已落盘）**

- `enum class LeaderStrength { STRONG, WEAK }`
- `class LeaderStrengthTracker`，API：
  - `clear()` / `retainCodes(codes: Set<String>)`
  - `strength(member: BoardMember, quote: Quote, nowMs: Long): LeaderStrength`
  - `strongNames(leaders: List<BoardMember>, quotes: Map<String, Quote>, nowMs: Long): List<String>`
  - `sealedNames(leaders: List<BoardMember>, quotes: Map<String, Quote>): List<String>`（给界面区分「封板 / 炸板守住」）
- 常量：
  - `LIMIT_UP_TOLERANCE = 0.15`（与全市场涨跌停扫描一致）
  - `STRONG_GAP_PCT = 2.0`（「贴板高位」离涨停的最大百分点距离；**与涨跌停幅度无关**，创业板同样是 2 个点 = 水上 18）
  - `HOLD_MS = 3 * 60 * 1000L`（转弱确认时长）

### 判定规则（不要改回去）

| | 条件 |
| --- | --- |
| **强** | 当前封板（`changePct ≥ limitPct − 0.15`）；**或** 今日曾封板 且 现价 `≥ limitPct − 2.0` |
| **弱** | 今日没封过板；**或** 跌破高位线并**持续 ≥ 3 分钟** |

两条关键设计：

1. **时长门槛放在「转弱」那一侧。** 跌破高位线要持续 3 分钟才确认走弱，刚炸那一瞬的抖动不翻转。
   若反过来放在「转强」侧，炸板后会空出 3 分钟让你去卖——正好卖飞，正是要避免的事。
   封板转强不设门槛（封板本身就是确定信号）。
2. **「今日曾封板」用 `Quote.high` 反推**，不靠状态记忆：
   `(high − prevClose) / prevClose × 100 ≥ limitPct − 0.15`。
   这样服务中途重启也不会把早上封过的板忘掉。转弱计时 `weakSinceMs` 才是状态，跨日按 `quote.quoteDate` 重置。

## 三、未完成的 6 步

### 1. `core/Models.kt`
- 删掉 `SectorLeaders.sealedNow(quotes)` 方法（逻辑已由 `LeaderStrengthTracker.sealedNames` / `strongNames` 承担）
- 删掉 `SectorLeaders.companion` 里的 `LIMIT_UP_TOLERANCE` 常量（已搬到 `LeaderStrengthTracker`）
- `SectorLeaders.SIZE = 5` 与 `SectorLeaders.of(all, turnovers, overrides, asOfMs)` **保持不变**

### 2. `monitor/MonitorService.kt`
- 新增字段：`private val leaderStrengthTracker = LeaderStrengthTracker()`
- 换日清理处（`holdingTTracker.clear()` 附近，约 L820）加 `leaderStrengthTracker.clear()`
- `checkIntradaySignals` 里，把
  `?.sealedNow(currentQuotes)` 换成
  `?.let { leaderStrengthTracker.strongNames(it.members, currentQuotes, now) }`
- 字段改名：`sealedLeaderCount` → `strongLeaderCount`，`sealedLeaderNames` → `strongLeaderNames`
- 界面若要区分「封板 / 炸板守住」，同一处再取一次 `sealedNames(...)`

### 3. `ui/MarketFragment.kt`
- 卡片显示区分：`sealedNames` 成员标 `🔴`，`strongNames − sealedNames` 标 `⚡`（炸板守住高位）
- `openLeaderEditor`（手动指定/排除龙头）**保持不变**
- 文案把「封板」改成「强势（封板或炸板守住高位线）」

### 4. `analysis/IntradayTactics.kt`
- `sealedLeaderCount` / `sealedLeaderNames` → `strongLeaderCount` / `strongLeaderNames`
- `leadersSealed(input)` → `leadersStrong(input)`
- `T_SELL_DEFERRED` 正文：「同板块龙头 X 仍在封板」→「同板块龙头 X 仍强（封板或炸板守住高位线）」
- `tSell` 的补充说明同步改
- `DEEP_RETREAT_FACTOR = 2.0` **不变**

### 5. 测试
- **新建 `core/LeaderStrengthTest.kt`**，至少覆盖：
  - 封板 → 立即强
  - 炸板后守住 `limitPct − 2%`（主板水上 8 点）→ 强
  - 跌破高位线后 3 分钟内 → 仍强（抖动不翻转）
  - 持续 3 分钟后 → 弱
  - 今日没封过板但涨到 8.5% → 弱（只认「炸板守住」，不认「从没封过」）
  - 服务重启后靠 `Quote.high` 仍能认出「今日曾封板」
  - 跨日（`quoteDate` 变化）计时重置
- `core/SectorLeadersTest.kt`：删掉 `sealedNowTracksLiveBoardSoBreakingTheBoardRestoresTSell`，
  改测 `strongNames`；`onlyTopTurnoverMembersCountAsLeaders`、`manualOverridesCanPinAnOutsiderAndExcludeAnAutoLeader` 不变
- `IntradayTacticsTest.kt`：字段改名 + 断言文案

### 6. 文档
- README「板块龙头」一节补「炸板守住也算强」
- 新建归档 `docs/superpowers/archive/2026-10-19-leader-strength.md`（旧的那份本轮更新不了，见下）

## 四、验收

```
./gradlew testDebugUnitTest assembleDebug --offline
```
要求 0 失败；记下测试总数与 APK SHA-256 写进归档。

## 五、中断原因（下次先看这个）

本会话运行在 **Android Studio → `/opt/homebrew/bin/claude` → zsh** 进程链下，
macOS TCC 的「文稿（Documents）」访问被拒：

| | `~/Desktop` | `~/Downloads` | `~/Documents` |
| --- | --- | --- | --- |
| 列目录 | ✅ | ✅ | ❌ EPERM |
| 读已有文件 | ✅ | ✅ | ❌ EPERM |

表现是 `stat`/`ls -l` 正常，但已有文件的**内容**读写一律 `Operation not permitted`；
进程**自己新建**的文件可读写（所以这份文档和 `LeaderStrength.kt` 能写进来）。

**已排除**：iCloud 文稿同步（无 `~/Library/Mobile Documents`，文件 `size=60068 blocks=120` 是完整落地的）、
Android Studio VFS/fsnotifier（不可能只锁文稿放行桌面）、文件锁（会是 `Resource temporarily unavailable`）。

**恢复条件**（三选一）：
1. 系统设置 → 隐私与安全性 → 文件与文件夹 → **Android Studio** → 打开「文稿文件夹」，然后 **Cmd+Q 完全退出并重启 Android Studio**；
2. 或直接给 Android Studio「完全磁盘访问权限」后重启；
3. 或**改从 Terminal.app 启动本项目**（Terminal 通常已有文稿权限），避开 Android Studio 这个负责进程。

> TCC 授权是进程启动时读一次的，改完必须重启进程才生效。macOS 不允许进程给自己申请文稿访问，
> 所以这一步只能在会话外做。

**未做且刻意不做**：凭上下文里的片段去重写 `Models.kt` / `IntradayTactics.kt` 等文件——
只读过部分内容，硬写会冲掉没读过的代码，比中断更严重。因此当前工作区是干净的：
`LeaderStrength.kt` 为唯一新增，其余文件未被本轮改动。
