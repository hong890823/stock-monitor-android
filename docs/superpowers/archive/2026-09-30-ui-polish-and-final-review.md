# UI 美化与交易策略最终复核归档

**归档日期：** 2026-09-30  
**项目：** `stock-monitor-android`  
**适用范围：** Android 项目

## 一、整体 UI 优化

- 统一页面背景、卡片圆角、卡片阴影、边框和卡片间距，增强卡片分区层次。
- 统一顶部工具栏标题样式，并增加与内容区的视觉分界。
- 优化底部 Tab 的图标尺寸、内边距、选中态和阴影，不改变 Tab 切换逻辑。
- 统一工具页和对话框输入框样式：圆角背景、聚焦描边、提示文字颜色和内边距。
- AI 综合分析输入框继续支持多行输入，同时减少默认空白和卡片底部留白。
- 统一按钮最小高度、圆角和上下内缩，减少页面中按钮高度不一致的问题。
- 优化完整报告页的正文行距、内边距和底部复制/分享区域。
- 补齐深色主题下输入框的背景、聚焦描边、禁用态、提示文字和选区颜色。

## 二、策略与崩溃风险复核

- 复核突破前高、次日确认、回踩 M20、缩量止跌、大盘过滤、趋势失效和 ATR 止损的共享入口，仍由 `TrendEntryStrategy` 统一计算。
- 确认突破结构仍只作观察，不会因为突破形态直接触发空仓买入。
- 确认回踩结构仍要求 M20 上行、接近 M20、缩量和止跌条件；趋势走弱时不加仓、不摊平。
- 复核盘中提醒的连续两轮确认、风险信号快速提醒、冷却时间、每日上限和信号重新武装逻辑，未发现不一致。
- 检查策略计算中的历史数据长度、前高候选、MA20 子区间、ATR 止损和空值分支，未发现新的明显越界或强制解包崩溃点。
- 未改变行情接口、缓存、持仓存储、后台刷新、通知权限和软键盘/底部 Tab inset 逻辑。

## 三、验证记录

- 使用 `xmllint` 检查本次涉及的 XML 资源，成功。
- 使用 Android `aapt2 compile` 编译 `app/src/main/res`，成功，无资源错误。
- 本轮 Gradle 完整编译未能在当前会话重新执行：Gradle Wrapper 缓存锁文件受工作区权限限制，且重新申请外部执行权限未获批准；编译错误由用户已在本地修复。
- ADB 设备复核未执行成功：ADB daemon 在当前会话因系统权限无法启动，因此没有新增设备崩溃日志可供分析。

## 四、关键文件

- `app/src/main/java/com/example/stockmonitor/ui/UiKit.kt`
- `app/src/main/java/com/example/stockmonitor/ui/ToolsFragment.kt`
- `app/src/main/java/com/example/stockmonitor/ui/Dialogs.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/TrendEntryStrategy.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/IntradayTactics.kt`
- `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values-night/colors.xml`
- `app/src/main/res/values/styles.xml`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/activity_report.xml`
- `app/src/main/res/layout/fragment_scroll.xml`
- `app/src/main/res/drawable/bg_input.xml`

