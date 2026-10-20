# 港美股数据链路改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 SEC 官方财务数据和可配置行情源增强港美股基本面分析，并让数据缺失与来源在报告中透明可见。

**Architecture:** 将港美股数据拆成证券识别、财务数据、行情数据三层。美股财务优先走 SEC Company Facts，行情优先走可配置 Twelve Data，现有 Yahoo/Nasdaq 作为兼容兜底；港股暂保留行情能力并明确财务数据缺口，不伪造完整结论。

**Tech Stack:** Kotlin、Android `HttpURLConnection`、`org.json`、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-09-29-global-market-data.md`

## Global Constraints

- 不在 APK 中硬编码 API Key。
- 继续使用现有 HTTP 客户端，不引入网络库。
- 网络失败不得导致整个报告无提示地失败。
- 固定 JSON 单测不得依赖实时网络。
- 不修改 A 股基本面分析逻辑。

### Task 1: SEC 数据模型与解析器

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/core/SecEdgarClient.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/GlobalFundamentalsClient.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/Http.kt`
- Test: `app/src/test/java/com/example/stockmonitor/GlobalFundamentalsClientTest.kt`

**Interfaces:**
- `SecEdgarClient.resolveTicker(ticker: String, text: String): SecCompany?`
- `SecEdgarClient.parseCompanyFacts(text: String, security: GlobalSecurity, price: Double?): GlobalFundamentals`
- SEC 解析器只接收文本和已有证券信息，便于离线测试。

- [x] 写 ticker 映射和 Company Facts 的失败/缺失字段测试。
- [x] 运行 `:app:testDebugUnitTest --tests com.example.stockmonitor.GlobalFundamentalsClientTest`，确认新测试先失败。
- [x] 实现 SEC ticker 解析、最新年度/季度事实选择、利润率、自由现金流和估值派生。
- [x] 为 HTTP 客户端增加可选 User-Agent 参数，SEC 请求使用明确标识。
- [x] 运行相关单测并确认通过。

### Task 2: 美股数据源编排与 Twelve Data 配置

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/core/GlobalDataSource.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/GlobalFundamentalsClient.kt`
- Modify: `app/build.gradle`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/example/stockmonitor/GlobalFundamentalsClientTest.kt`

**Interfaces:**
- 数据源优先级：SEC 财务 → Twelve Data 行情（有 Key）→ Yahoo/Nasdaq 行情兜底。
- `GlobalFundamentals` 增加 `dataSources`、`dataWarnings` 和 `latestPeriod`。

- [x] 写源选择测试：美股优先 SEC，行情失败仍保留财务结果。
- [x] 实现无 Key 时安全跳过 Twelve Data，不发送空 Key 请求。
- [x] 实现 SEC 请求失败时回退现有数据源，并保留失败原因。
- [x] 运行客户端单测和编译。

### Task 3: 报告补充数据来源、完整度与趋势判断

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/GlobalFundamentalsReport.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/ToolsFragment.kt`
- Test: `app/src/test/java/com/example/stockmonitor/GlobalFundamentalsClientTest.kt`

- [x] 写报告断言：来源、最新期间、缺失项和“不能据此下结论”提示。
- [x] 在快速结论和各章节显示数据源/完整度，不把字段缺失显示成中性利好。
- [x] 当 SEC 字段齐全时，补充利润率、现金流、负债覆盖和 PE/PB 判断。
- [x] 港股缺失公告/财务数据时明确提示，避免套用美股或 A 股口径。
- [x] 运行报告单测和全量单测。

### Task 4: 构建与手机验证

**Files:**
- Modify: `README.md`（补充 API Key 配置与数据源说明，如有必要）

- [x] 执行 `:app:testDebugUnitTest :app:assembleDebug`。
- [ ] 安装 Debug APK 到手机。
- [ ] 在工具页验证 `NVDA`：报告生成、字段来源、缺失项提示。
- [ ] 验证 Yahoo 失败时不会直接报错；SEC/行情源失败时报告仍给出明确提示。
