# Intraday Addition Window Android Implementation Plan

> 将 macOS 的严格价格窗口盘中补仓链路迁移到 Android，保持基础加仓评估和统一交易门控不变。

## 已完成

- [x] 增加基础快照模型：评估时间、支撑、压力、止损、原建议数量和持仓/评估指纹。
- [x] 按 `max(支撑×0.995, 止损+0.01)` 与 `min(支撑×1.015, (压力+2×止损)/3)` 计算价格窗口，并分别向上/向下按分取整。
- [x] 增加 35 分钟过期、止损跌破当日失效、止损/支撑/压力关系和 10% 止损距离校验。
- [x] 增加至少 3 个样本、跨度至少 120 秒、间隔不超过 90 秒、个股/指数同日和量能/周期确认。
- [x] 最高接受价只能维持或收紧，持仓或评估指纹变化时重新累积样本。
- [x] 按最高接受价对应的保守数量取原候选数量和统一交易门控结果的较小值，低于 100 股不通知。
- [x] 盘中接入 `MonitorService`，通知复用 `TradeNotification`、`publishDailyAdvice` 和同日买入互斥。
- [x] 持久化最高接受价、指纹和失效状态；状态损坏时暂停机会链路。

## 边界

- 该链路不会把基础加仓报告直接包装成即时买入。
- 价格窗口只管理盘中补仓机会，风险退出仍由原持仓风险链路负责。
- 当前样本在进程重启后重新累积，最高接受价和失效状态保留。

## 验证

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.example.stockmonitor.analysis.IntradayAdditionPolicyTest \
  --tests com.example.stockmonitor.core.IntradayAdditionJsonTest \
  --tests com.example.stockmonitor.analysis.IntradayTacticsTest \
  --tests com.example.stockmonitor.core.TradeActionGuardTest
```
