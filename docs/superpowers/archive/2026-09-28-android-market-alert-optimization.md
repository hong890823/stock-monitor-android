# Android 大盘预警优化归档

**归档日期：** 2026-09-28  
**项目：** `stock-monitor-android`  
**参考方案：** `/Users/stephen/Desktop/Android 预警.txt`

## 已落地内容

- 新增纯 Kotlin 大盘预警引擎，监控上证、深证、创业板三个指数。
- 支持转弱、风险、严重风险三级预警；转弱连续两轮确认，风险和严重风险可立即触发。
- 增加 5 分钟急跌速度检测，并将速度原因合并到同一条通知。
- 支持等级升级提醒、同等级去重，以及指数回到 `-0.6%` 以上后的重新武装。
- 增加三指数同步走弱识别，减少单一指数波动造成的误判。
- 增加同一时刻成交额基线：至少 10 个历史交易日后，按近 20 日同期中位数计算倍数。
- 在 `1.5x`、`2x`、`3x` 成交额倍数处分别标记放量、明显放量、异常放量下跌。
- 工具页补充监控指数、阈值、确认轮次、速度预警、成交额确认和重新武装说明。
- 严重风险和急跌风险继续使用高优先级通知及应用直接震动；普通转弱提醒不强制震动。

## 关键文件

- `app/src/main/java/com/example/stockmonitor/analysis/MarketAlertEngine.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/MarketVolumeBaseline.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/TurnoverTracker.kt`
- `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- `app/src/main/java/com/example/stockmonitor/ui/ToolsFragment.kt`
- `app/src/test/java/com/example/stockmonitor/MarketAlertEngineTest.kt`
- `app/src/test/java/com/example/stockmonitor/MarketVolumeBaselineTest.kt`

## 验证记录

- `testDebugUnitTest`：通过。
- `assembleDebug`：通过。
- Debug APK 已安装并启动到当前连接设备。
- APK：`app/build/outputs/apk/debug/app-debug.apk`

## 说明

- 成交额确认依赖本地 15 分钟槽位采样；历史不足 10 个交易日时不会参与放量预警。
- 本归档记录的是 Android 大盘预警优化；A/macOS 项目迁移任务仍保持暂停。
