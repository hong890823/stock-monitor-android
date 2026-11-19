# Position Allocation Android Implementation Plan

> 将 macOS 的底仓、滚动仓和风险观察仓规则迁移到 Android，保持分仓配置独立、实时数量派生和 T+1 账本边界。

## 规则

- 普通模式：底仓 = `min(当前持仓, 固定底仓目标)`；滚动仓 = `当前持仓 - 底仓`；风险观察仓为 0。
- 风险观察模式：底仓和滚动仓为 0；当前持仓全部列入风险观察仓，不预设买回数量。
- 未配置：显示“分仓未设置（暂列观察）”，不伪造已经完成的风险判断。
- 清仓只让派生数量归零，独立配置继续保留；重新持有时按原底仓目标重新派生。
- 分仓不改变 `TradeLotLedger` 的 T+1 可卖数量，底仓不豁免必要风险退出。

## 实现

- [x] 添加分仓配置模型和严格 JSON 编解码。
- [x] 在 `Store` 中独立持久化分仓配置，损坏配置不静默覆盖原始值。
- [x] 添加纯 Kotlin 派生策略，覆盖清仓、目标超过当前数量、非整手数量和风险观察模式。
- [x] 在持仓设置中增加分仓配置入口。
- [x] 在持仓页摘要、详情和持仓报告显示派生结果。
- [x] 增加策略和 JSON 回归测试。

## 验证

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.example.stockmonitor.analysis.PositionAllocationPolicyTest \
  --tests com.example.stockmonitor.core.PositionAllocationJsonTest \
  --tests com.example.stockmonitor.PortfolioAnalysisTest
```
