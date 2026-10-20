# 高位风险接入与持仓退出策略归档

**归档日期：** 2026-10-15  
**项目：** `stock-monitor-android`  
**范围：** 持仓高位识别、连续拉升后的转弱判断、破位分级、持仓报告、盘中提醒与波段建议的一致性。

## 本次确认的实际行为

- 价格位置复用近 60 个交易日区间；至少有 20 根有效日线，现价位于区间上 1/3 且相对区间起点涨幅至少 20% 时，才标记为“阶段高位”。它不是历史最高价、估值高估或顶部预测。
- 高位本身不触发卖出。高位叠加确认破位时，风险阶段升级为“高位转弱·优先减仓”；这会优先分批降低风险，但不会仅凭高位直接清仓。
- 尚未破位时，只有连续拉升背景和连续盘中转弱证据同时存在，才触发“高位转弱·优先减仓”：近期 5 日涨幅至少 `max(6%, ATR%×3)`、近 5 个涨跌区间至少 3 次上涨，并且有至少 3 个成交量递增样本、样本实际跨度至少 60 秒。
- 高位转弱的盘中证据包括放量滞涨（量比至少 1.3、当日涨跌幅绝对值不超过 1%、回撤至少 `max(0.5%, ATR%×0.25)`），或更明显的冲高回落（回撤至少 `max(1%, ATR%×0.5)`）。单次回落、单根日K、位置数据缺失或行情没有新成交样本均不触发。
- 持仓报告、今日要点、盘中风险提醒和波段建议共用 `HoldingExitPolicy`。高位转弱时波段建议暂停新增资金和自动做T接回，避免一处建议减仓、另一处建议加仓。
- 未破位的高位转弱在报告中显示为“高位转弱”，不会被摘要或市场环境说明误写成“破位”；普通破位仍按“待确认 → 分批减仓 → 退出优先”处理。

## 数据一致性修正

- `HoldingTTracker.snapshot` 只复用与当前价格、累计成交量和日期完全匹配的最近行情，不会把等待时间冒充为连续成交确认。
- 持仓报告使用已有的新鲜盘中样本，不用旧行情回退写入，避免清空提醒线程已经收集的转弱证据。
- 阶段高位判断接入持仓监控实际维护的价格区间；没有新鲜区间时不猜测高位。

## 主要源码位置

- `app/src/main/java/com/example/stockmonitor/analysis/HoldingExitPolicy.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/HoldingTContext.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/IntradayTactics.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/PortfolioAnalysis.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/PortfolioSwing.kt`
- `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- `README.md`

## 验证

- 全量单元测试：`./gradlew testDebugUnitTest assembleDebug --offline`，`565` 项测试通过，`0` 失败、`0` 错误、`0` 跳过。
- Debug 构建成功；APK 位于 `app/build/intermediates/apk/debug/app-debug.apk`。
- Debug APK SHA-256：`292859f6cafe8cb510018df108188f736672f2c21ca331fa26575e326388f6ac`。

## 边界

- 本次验证覆盖代码逻辑、策略边界、报告与提醒一致性和 Debug 构建，不代表实盘收益、胜率或最优卖点已经验证。
- 未安装到手机，未做真实行情和真实成交回报验证；系统不自动下单，也不会假定减仓或做T已经成交。
- 项目当前不是 Git 仓库，也没有 OpenSpec 变更目录；本次通过本归档文档和源码保存改动，未创建 Git commit。
