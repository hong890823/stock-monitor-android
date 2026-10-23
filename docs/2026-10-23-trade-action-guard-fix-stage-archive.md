# 交易动作守卫修复阶段归档

归档日期：2026-10-23  
项目：`stock-monitor-android`

## 一、阶段目标

本轮只处理审查中确认的交易动作逻辑问题，重点统一风险意图、退出意图、高位转弱和整手卖出边界，避免通知把风险事实误升级为清仓指令，也避免高位转弱被破位条件错误拦截。

## 二、确认问题与修复

### 1. 高位转弱被破位条件误拦

- **问题**：高位转弱本身不要求价格已经跌破支撑，但统一风险路径要求破位确认，导致已确认的高位转弱无法按减仓意图处理。
- **修复**：新增 `high_reduce` 独立意图；`HIGH_REDUCE_POSITION` 和 `HoldingExitStage.HIGH_REDUCE` 映射到该意图。
- **确认边界**：只使用最新已完成 15 分钟风险证据；不要求 `riskConfirmed` 破位条件。未完成 K 线不参与确认。
- **卖出边界**：首批按可卖仓位 1/3，出现承接迹象时压到 100 股；仍受 T+1、账本一致性和 100 股整手约束。

### 2. 清仓意图过宽

- **问题**：`risk_exit`、`stop` 等风险别名在严重风险下可能被理解为全量清仓，和“风险只分批减仓”冲突。
- **修复**：只有显式 `exit_position` / `exit_priority` 退出优先意图，且连续 3 根已完成 15 分钟 K 线确认、日级风险为 `CRITICAL`，才进入 `RISK_EXIT` 全量退出路径。
- **风险别名**：`risk_exit`、`stop`、`risk_reduce`、`break_support`、`high_reduce` 和突破反转风险统一返回 `RISK_REDUCE`，最多给出分批卖量。

### 3. 退出意图映射不一致

- **问题**：代码识别了 `exit_priority`，但全量退出判断只接受 `exit_position`，两种显式退出意图行为不一致。
- **修复**：全量退出条件统一接受 `exit_position` 和 `exit_priority`，并增加对应回归测试。

### 4. 零碎股和账本边界

- 所有可执行卖量先按 100 股整手向下取整，包括退出场景。
- 账本数量与持仓数量不一致时，直接返回观察状态，可执行卖量为 0。
- T+1 锁定批次不生成今日可卖量；未记录买入日期残股不按“今日新增”解释。

## 三、代码与测试落地

### 代码

- `app/src/main/java/com/example/stockmonitor/core/TradeActionGuard.kt`
  - 新增并处理 `high_reduce`。
  - 收紧 `RISK_EXIT` 全量退出条件。
  - 统一 `exit_position` / `exit_priority` 的退出意图判断。
  - 保持账本一致性和 100 股整手边界。
- `app/src/main/java/com/example/stockmonitor/analysis/TradeNotification.kt`
  - `HIGH_REDUCE_POSITION` / `HoldingExitStage.HIGH_REDUCE` 映射 `high_reduce`。
  - `EXIT_POSITION` / `HoldingExitStage.EXIT` 映射 `exit_position`。
  - 退出和减仓文案分别按整手退出、1/3 分批减仓生成。

### 回归测试

- `app/src/test/java/com/example/stockmonitor/core/TradeActionGuardTest.kt`
  - `risk_exit` 不升级为全量退出。
  - `exit_priority` 在满足条件时可全量退出。
  - `high_reduce` 不要求支撑破位，但要求最新已完成风险证据。
  - 风险减仓、承接压量、T+1 和整手数量边界继续覆盖。

## 四、关联归档

- `docs/2026-10-23-intraday-risk-volume-context-change-archive.md`
- `docs/2026-10-23-archive-logic-audit.md`
- `docs/superpowers/specs/2026-10-20-intraday-risk-t1.md`

## 五、验证状态

- `git diff --check` 通过。
- 已完成代码、测试引用和归档口径的静态交叉审查。
- 定向 Gradle 测试未能启动：Gradle wrapper 锁文件报 `Operation not permitted`；批准执行请求也因服务端 401 未执行。
- 因此不宣称当前单元测试、Debug 构建或 APK 验证通过。
- 当前改动保留在工作区，未提交 Git、未建分支、未安装 APK。
