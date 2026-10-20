# AI 自由提问、港美股基本面与 513 知识库归档

**归档日期：** 2026-09-29  
**项目：** `stock-monitor-android`  
**适用范围：** Android 项目

## 一、AI 自由提问式分析

- “工具”Tab 的 AI 分析从固定格式输入改为自然语言自由提问。
- 支持输入股票名称、A 股代码、港美股代码、指数以及带有完整语义的问题。
- 支持的示例包括：
  - `英伟达最近为什么下跌？`
  - `特变电工估值贵不贵？`
  - `NVDA 最近的风险是什么？`
  - `上证指数最近走势如何？`
- `AiQuestionResolver` 负责从问题中识别 A 股代码、海外 ticker、中文股票名和指数。
- AI 请求会联合使用：
  - 本地缓存的技术面报告；
  - 本地缓存的 A 股基本面报告；
  - 港美股基本面报告；
  - A 股 K 线和成交量摘要；
  - 网络搜索结果；
  - 按需加载的策略知识库。
- DeepSeek 返回结构化 JSON，包含直接回答、趋势、基本面、护城河、估值、催化因素、风险、情景、观察项、可信度和来源。
- 缺失数据必须明确标注，不能把缺失字段推断成利好，也不能编造数字、公告或来源。

## 二、港股/美股基本面分析

- 新增港股/美股基本面数据适配和独立报告卡片。
- 美股财务数据优先使用 SEC Company Facts，行情和公司资料使用可用的行情/基本面数据源兜底。
- 报告参考 A 股基本面分析的组织方式，展示盈利能力、现金流、资产负债、业务与竞争优势、估值和风险。
- 报告中明确标注市场、币种、数据来源、最新财务期间和数据完整度。
- 缺失字段保持为空或显示“暂缺”，不会用 A 股数据补齐港美股数据。

## 三、英伟达利润率错误修复

### 根因

SEC Company Facts 中不同公司可能使用不同的收入标签。英伟达最新收入数据实际位于 `Revenues`，而旧标签 `RevenueFromContractWithCustomerExcludingAssessedTax` 仍保留历史数据。旧实现只要第一个候选标签有数据就停止，造成旧收入与最新成本、营业利润和净利润混用，最终可能显示错误的负利润率。

### 已修复

- 合并多个候选财务标签，并跨标签选择最新有效报告期间。
- 营收、成本、营业利润和净利润统一按同一期间计算 TTM。
- 季度/中期数据优先与年度累计数据对齐计算 TTM，避免把半年累计值当单季值。
- 经营现金流和资本开支按同一期间处理。
- 现金、债务、净资产和股本取最新期末值。
- 兼容 SEC 中资本开支正负号差异，避免自由现金流方向错误。
- PE 使用 TTM 净利润和最新股本计算；PB 使用最新净资产和股本计算。

## 四、513 策略知识库

- 新增内置文档：
  - `app/src/main/assets/knowledge/513-strategy.md`
- 文档记录：
  - 5 连阳、倍量阳、3 天缩量确认的完整顺序；
  - 程序实际扫描范围：全部沪深 A 股、近 120 日日线；
  - 有效历史不足 60 日、ST 排除规则；
  - 五连阳、倍量阳、缩量确认的程序化条件；
  - 最近 60 日涨幅和区间位置过滤；
  - 观察中、等待确认、形态已确认三个阶段；
  - 策略失效条件和风险边界；
  - AI 回答 513 问题时的事实约束。
- AI 只有在问题涉及 `513`、五连阳、倍量阳、缩量确认、缩量企稳或逢低建仓时，才读取并注入这份知识库，避免普通问题增加无关上下文。
- AI 必须以程序实际扫描结果为准：知识库用于解释规则，不用于伪造当前候选股。
- 没有命中时应直接说明当前数据下没有符合条件的股票，不得为了给出答案而放宽条件。

## 五、关键文件

- `app/src/main/java/com/example/stockmonitor/core/AiAnalysis.kt`
- `app/src/main/java/com/example/stockmonitor/ui/ToolsFragment.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/AiAnalysisReport.kt`
- `app/src/main/java/com/example/stockmonitor/core/GlobalFundamentalsClient.kt`
- `app/src/main/java/com/example/stockmonitor/core/SecEdgarClient.kt`
- `app/src/main/java/com/example/stockmonitor/analysis/GlobalFundamentalsReport.kt`
- `app/src/main/assets/knowledge/513-strategy.md`
- `app/src/test/java/com/example/stockmonitor/AiAnalysisTest.kt`
- `app/src/test/java/com/example/stockmonitor/AiQuestionResolverTest.kt`
- `app/src/test/java/com/example/stockmonitor/GlobalFundamentalsClientTest.kt`

## 六、配置与安全提醒

- DeepSeek 配置读取自项目根目录的 `local.properties`：
  - `DEEPSEEK_API_KEY`
  - `DEEPSEEK_MODEL`
  - `DEEPSEEK_BASE_URL`
- Twelve Data 配置仍由项目现有 `local.properties` 配置读取。
- API Key 不写入本归档，不应提交到公共仓库。
- 当前 API Key 会通过 `BuildConfig` 进入个人测试 APK，仅适合个人测试环境；正式发布前应迁移到服务端代理。

## 七、验证记录

- 港美股 SEC 财务解析专项测试：通过。
- 港美股基本面相关测试：通过。
- 全量 `:app:testDebugUnitTest`：通过（513 知识库最后一次接入前的完整构建验证）。
- `:app:assembleDebug`：通过（513 知识库最后一次接入前的完整构建验证）。
- SEC 真实 NVIDIA Company Facts 数据已用于根因核对，确认英伟达收入标签兼容问题。
- 513 知识库接入后的专项测试和最终 APK 构建因本轮 Gradle 权限未获批准，尚未重新执行；后续测试时应优先运行：

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## 八、后续开发参考

- 修改港美股财务字段时，必须保持同一报告期间和 TTM 口径，不能直接对不同标签取最新值。
- 新增 AI 知识库时，优先放在 `app/src/main/assets/knowledge/`，并通过问题触发条件按需加载。
- 涉及 513 当前结果的回答必须读取程序扫描结果，不能只根据策略文档推断。
- 港股目前仍比美股缺少稳定、完整的财务与历史行情数据，报告应继续保留数据完整度提醒。
- A/macOS 项目迁移任务仍保持暂停，除非后续明确恢复。

## 九、港美股金额可读性优化

- 港美股基本面报告中的现价、营收、经营现金流、自由现金流、现金、债务和市值不再直接展示原始大整数。
- 统一显示币种和数量级：例如 `5.50万亿美元`、`1960.00亿美元`、`744.21亿美元`。
- 支持美元、港元、人民币、欧元和英镑；其它币种保留币种代码。
- 数量级按金额自动选择 `万亿`、`亿`、`万` 或原始金额。
- “工具”Tab 的港美股摘要卡片同步使用相同的金额格式，避免卡片和完整报告口径不一致。
- 相关实现：
  - `app/src/main/java/com/example/stockmonitor/analysis/GlobalFundamentalsReport.kt`
  - `app/src/main/java/com/example/stockmonitor/ui/ToolsFragment.kt`
  - `app/src/test/java/com/example/stockmonitor/GlobalFundamentalsClientTest.kt`
- 本次改动的专项测试尚未在当前权限下重新执行；后续应运行全量 `:app:testDebugUnitTest :app:assembleDebug`。

## 十、小米集团港股搜索修复

- 修复输入“小米集团”无法在港美股基本面分析中识别的问题。
- 增加名称、英文名和代码别名：`小米`、`小米集团`、`Xiaomi`、`1810`、`01810`、`1810.HK`。
- 统一解析为 `1810.HK`、`HKEX`、`HKD`，网络搜索不可用时也能通过本地兜底识别。
- 同时支持自然语言问题，例如“ 小米集团最近基本面怎么样？”。
- 相关实现：`app/src/main/java/com/example/stockmonitor/core/GlobalFundamentalsClient.kt`
- 回归测试：`app/src/test/java/com/example/stockmonitor/GlobalFundamentalsClientTest.kt`

## 十一、港股代码识别后分析失败的修复

- 问题表现：输入 `1810` 可以识别为小米集团，但 Twelve Data 和 Yahoo 财务接口失败后，程序错误地继续使用不适用于港股的 Nasdaq 接口，最终整次分析失败。
- 修复方式：在 Twelve Data、Yahoo 财务接口之后增加 Yahoo Chart 行情兜底。
- 港股即使暂时取不到完整财务字段，也会继续生成报告，并明确提示“当前仅取得行情数据，财务字段暂缺”。
- 美股仍会在取得行情后继续尝试 SEC Company Facts，以补充完整财务数据。
- 新增解析器测试：Yahoo Chart 能正确读取 `1810.HK`、港元和港交所信息。
- 相关实现：`app/src/main/java/com/example/stockmonitor/core/GlobalFundamentalsClient.kt`
- 相关测试：`app/src/test/java/com/example/stockmonitor/GlobalFundamentalsClientTest.kt`
- 本次修复尚未在当前 Gradle 权限限制下执行完整构建，后续需在本机运行测试验证。
