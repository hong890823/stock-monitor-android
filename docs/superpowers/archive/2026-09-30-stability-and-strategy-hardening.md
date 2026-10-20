# 稳定性与交易策略加固归档

**归档日期：** 2026-09-30  
**项目：** `stock-monitor-android`  
**依据：** `/Users/stephen/Desktop/temp.txt` 外部扫描结果及本轮静态复核

## 一、已采纳的稳定性修复

- Android 15 `dataSync` 前台服务增加 `onTimeout()` 安全收尾：记录状态、通知界面并及时 `stopSelf(startId)`，避免系统超时后抛出致命异常。
- `Repo.quotes`、`Repo.volumeRatios`、`Repo.priceContexts` 改为 `@Volatile` 不可变 Map 快照，后台整体替换，避免 UI 迭代时遇到 `clear()` / `putAll()` 并发修改。
- `MonitorService.volumeBaselineBusy` 改为 `AtomicBoolean`，避免并发协程看不到复位状态。
- 工具页技术分析、基本面分析、港美股分析、AI 分析均使用 `try/finally` 复位 busy 状态，并仅在 Fragment View 仍存在时重建 UI。
- 开机自动启动失败不再静默吞掉：错误会写入本地诊断状态并记录日志，下次服务启动时展示一次。
- 时间判断、时间展示和报告时间统一使用 `Asia/Shanghai`；工具页不再直接使用设备默认时区。
- 新浪实时行情解析拒绝 `NaN` / `Infinity`，新浪日线非 JSON 返回会转换为明确的数据解析错误。
- AI 响应非 JSON 时转换为明确的 `HttpError`，不让裸 `JSONObject` 异常向上逃逸。

## 二、已采纳的策略修复

- 历史 K 线已经包含当天时，实时行情只更新当天那根 K 线，不再重复追加第二根同日 K 线。
- 趋势选股和趋势入场统一按 `VolumeRatio.of()` 使用已交易分钟折算量比；未知量比保持 `null`。
- 未知量比不再被当成缩量，因此不会凭空触发回踩 M20 观察或试仓提醒。
- 盘中信号状态机调整为“连续确认 → 冷却检查 → 每日上限检查 → 写入 active → 发送提醒”，避免冷却期间真实二次触发被永久吞掉。
- 主板 ST/*ST 股票按 10% 涨跌幅处理，`920xxx` 北交所股票按 30% 处理，创业板/科创板（含风险警示股票）按 20% 处理。
- 盘中行情带有旧交易日期时，仍允许展示旧行情，但跳过盘中预警、量比和成交额重算，避免节假日或行情冻结造成误报。
- AI K 线工具最低长度改为 21 根，避免计算 20 日涨跌幅时访问负索引。
- 交易成本计算拒绝非有限输入；浮盈比例无法计算时不再伪造为 `+0.00%`。
- 513 策略首个历史窗口没有量能基线时直接判定为无效，不再让 `NaN` 穿过量能约束。

## 三、明确保持的不变量

- 突破前高仍然只是“突破观察”，不会直接变成空仓买入信号。
- 回踩 M20 仍必须满足 M20 上行、接近 M20、缩量和止跌确认。
- 趋势走弱时不加仓、不摊平；风险信号优先，普通信号继续连续两轮确认。
- 保持约 15 秒行情刷新，不改变数据源和持仓存储格式。
- 未引入新的网络依赖，也未改变港美股和 A 股报告的数据结构。

## 四、验证记录

- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:compileDebugKotlin --stacktrace`：成功。
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest`：成功，193 条单测通过。
- 未执行 APK 安装、真机 UI 和后台长时间运行验证；Android 15 前台服务超时处理本轮仅完成安全退出缓解，停止后的全天续跑仍需后续用 WorkManager / AlarmManager 或平台允许的服务类型重新设计。
