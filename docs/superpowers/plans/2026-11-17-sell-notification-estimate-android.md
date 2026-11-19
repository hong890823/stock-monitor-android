# Sell Notification Estimate Android Implementation Plan

## 已完成

- [x] 在卖出通知中加入参考成本和统一门控给出的拟卖数量。
- [x] 按估算费用计算实现盈亏和预计回收资金。
- [x] 计算卖出后剩余持仓。
- [x] 持仓风险通知和盘中卖出通知复用同一估算函数。
- [x] 无效数量或超过持仓时不生成虚假的估算数字。

估算字段只用于通知前的人工核对，实际成交后的最终盈亏仍由交易记录和批次账本计算。

## 验证

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.example.stockmonitor.TradeNotificationTest
```
