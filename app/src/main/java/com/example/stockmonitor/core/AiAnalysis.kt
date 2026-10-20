package com.example.stockmonitor.core

import com.example.stockmonitor.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

data class WebResearchItem(
    val title: String,
    val url: String,
    val snippet: String,
)

data class AiAnalysisResult(
    val answer: String,
    val headline: String,
    val trend: String,
    val fundamentals: String,
    val moat: String,
    val valuation: String,
    val catalysts: List<String>,
    val risks: List<String>,
    val scenarios: List<String>,
    val watchItems: List<String>,
    val confidence: String,
    val sources: List<String>,
    val portfolio: String = "数据暂缺",
    val marketContext: String = "数据暂缺",
    val marketRisk: String = "数据暂缺",
    val operationAdvice: String = "数据暂缺",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("headline", headline)
        .put("answer", answer)
        .put("trend", trend)
        .put("fundamentals", fundamentals)
        .put("moat", moat)
        .put("valuation", valuation)
        .put("catalysts", JSONArray(catalysts))
        .put("risks", JSONArray(risks))
        .put("scenarios", JSONArray(scenarios))
        .put("watch_items", JSONArray(watchItems))
        .put("confidence", confidence)
        .put("sources", JSONArray(sources))
        .put("portfolio", portfolio)
        .put("market_context", marketContext)
        .put("market_risk", marketRisk)
        .put("operation_advice", operationAdvice)

    companion object {
        fun fromJson(json: JSONObject): AiAnalysisResult = AiAnalysisResult(
            answer = json.optString("answer").ifBlank { json.optString("headline", "暂未形成明确结论") },
            headline = json.optString("headline", "暂未形成明确结论"),
            trend = json.optString("trend", "数据不足"),
            fundamentals = json.optString("fundamentals", "数据不足"),
            moat = json.optString("moat", "暂缺护城河判断"),
            valuation = json.optString("valuation", "暂缺估值判断"),
            catalysts = json.optStringList("catalysts"),
            risks = json.optStringList("risks"),
            scenarios = json.optStringList("scenarios"),
            watchItems = json.optStringList("watch_items"),
            confidence = json.optString("confidence", "低"),
            sources = json.optStringList("sources"),
            portfolio = json.optString("portfolio", "数据暂缺"),
            marketContext = json.optString("market_context", "数据暂缺"),
            marketRisk = json.optString("market_risk", "数据暂缺"),
            operationAdvice = json.optString("operation_advice", "数据暂缺"),
        )

        private fun JSONObject.optStringList(key: String): List<String> {
            val array = optJSONArray(key) ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }
}

data class AiAnalysisInput(
    val question: String,
    val name: String,
    val code: String,
    val market: String,
    val technicalReport: String?,
    val fundamentalReport: String?,
    val trendSummary: String,
    val research: List<WebResearchItem>,
    val generatedAt: String,
    val knowledgeBase: String = "",
)

/** AI 可调用的只读数据工具。实现层负责访问程序已有数据源，模型只负责决定调用顺序。 */
interface AiToolExecutor {
    fun definitions(): JSONArray

    fun execute(name: String, arguments: JSONObject): String
}

object AiToolCatalog {
    fun definitions(): JSONArray = JSONArray()
        .put(function(
            name = "search_security",
            description = "按用户原话搜索股票或指数，支持名称、代码、拼音和英文名称；返回候选标的及市场。",
            properties = JSONObject()
                .put("query", stringProperty("用户问题中的标的描述或完整原话")),
            required = listOf("query"),
        ))
        .put(function(
            name = "get_realtime_quote",
            description = "获取指定股票或指数的最新行情、现价、涨跌幅和成交量。",
            properties = JSONObject()
                .put("code", stringProperty("search_security 返回的代码")),
            required = listOf("code"),
        ))
        .put(function(
            name = "get_fundamental_analysis",
            description = "读取或刷新程序的完整基本面分析报告，包括利润、现金流、股东结构、公告、估值和风险。",
            properties = JSONObject()
                .put("code", stringProperty("search_security 返回的代码"))
                .put("name", stringProperty("标的名称，可选"))
                .put("refresh", JSONObject().put("type", "boolean").put("description", "是否优先刷新公开数据")),
            required = listOf("code"),
        ))
        .put(function(
            name = "get_technical_analysis",
            description = "获取程序技术分析报告，包括均线、量价、MACD、RSI、支撑压力、筹码和条件式建议。",
            properties = JSONObject()
                .put("code", stringProperty("search_security 返回的代码"))
                .put("name", stringProperty("标的名称，可选")),
            required = listOf("code"),
        ))
        .put(function(
            name = "get_kline_summary",
            description = "获取近期 K 线走势和成交量变化摘要，用于判断短中期趋势。",
            properties = JSONObject()
                .put("code", stringProperty("search_security 返回的代码")),
            required = listOf("code"),
        ))
        .put(function(
            name = "get_portfolio_report",
            description = "读取最近一次持仓报告。报告可能来自内存或本地缓存；只读，不会触发重新分析。没有报告时必须明确说明数据暂缺。",
            properties = JSONObject(),
            required = emptyList(),
        ))
        .put(function(
            name = "get_market_summary_report",
            description = "读取最近一次盘面总结。它可能只覆盖今天，因此必须与 get_recent_market_context 返回的最近几天大盘和板块表现一起使用。只读，不会触发重新生成。",
            properties = JSONObject(),
            required = emptyList(),
        ))
        .put(function(
            name = "get_recent_market_context",
            description = "获取最近几天的大盘指数和目标股票所属主行业板块表现，包含1日、5日、20日涨跌幅；用于补足只覆盖今天的盘面总结。",
            properties = JSONObject()
                .put("code", stringProperty("search_security 返回的代码"))
                .put("name", stringProperty("标的名称，可选")),
            required = listOf("code"),
        ))
        .put(function(
            name = "get_market_risk_context",
            description = "获取共享的市场风险上下文，包括周五、月末、季末、财报季、北向资金、成交额和已知数据缺口。日期因素只作风险提示，不代表必然下跌；出金和节假日数据缺失时必须如实说明。",
            properties = JSONObject(),
            required = emptyList(),
        ))
        .put(function(
            name = "search_web",
            description = "检索与用户问题直接相关的公开信息。优先搜索公司公告、交易所/监管机构、公司官网和主流财经媒体；重大事件、财务数据、增发、处罚等信息尽量交叉验证。返回可追溯的标题、来源链接和摘要；没有可靠来源时明确说明，不要把搜索摘要当成已证实事实。",
            properties = JSONObject()
                .put("query", stringProperty("完整搜索问题"))
                .put("code", stringProperty("标的代码，可选"))
                .put("name", stringProperty("标的名称，可选")),
            required = listOf("query"),
        ))
        .put(function(
            name = "list_knowledge_bases",
            description = "列出程序内可用的策略、研究方法和其它知识库文档；当用户询问策略但名称不明确时先调用。",
            properties = JSONObject(),
            required = emptyList(),
        ))
        .put(function(
            name = "get_knowledge_base",
            description = "读取程序内指定的策略或知识库文档，用于解释规则；知识库不是当前行情事实，当前命中情况必须另行调用行情和技术工具确认。",
            properties = JSONObject()
                .put("query", stringProperty("策略名称、文档名称或用户问题中的策略描述")),
            required = listOf("query"),
        ))

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", JSONArray(required))
                        .put("additionalProperties", false),
                ),
        )

    private fun stringProperty(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)
}

object AiAnalysisPrompt {
    fun system(): String = """
        你是一个严谨的股票和指数研究助手。你只能根据用户提供的程序分析数据和网络资料作判断，不能把缺失数据当成利好，也不能编造事实、数字、公告或来源。
        你可以调用程序提供的只读工具。不要自行猜测或提取股票代码，也不要把完整问题当成股票名称；遇到股票或指数问题时，先调用 search_security，再调用 get_realtime_quote、get_technical_analysis、get_fundamental_analysis、get_portfolio_report、get_market_summary_report 和 get_recent_market_context；之后再按问题需要调用 K线和网络资料工具。
        综合分析必须同时覆盖技术面、基本面、持仓报告和盘面总结四类报告，并调用 get_market_risk_context。盘面总结可能只覆盖今天，必须额外结合 get_recent_market_context 返回的最近几天大盘和板块表现（目标股票优先看所属主行业）；市场风险上下文还要结合北向资金、成交额、周五/月末/季末/财报季等时点因素，但日期标签不能被当成确定性卖出信号。缺失的报告或时间范围要明确写出，不能用今天数据冒充多日趋势。
        基本面涉及下一季或年报时，必须明确区分已披露业绩预告、财报事实和基于历史同比/现金流质量的趋势外推；没有业绩预告就不能写成确定的盈利预测或具体利润数字。市场风险上下文中的数据日期、更新时间和数据缺口优先于摘要文字；过期、冻结、单位未确认或暂缺的数据只能写成不确定性，不能当作中性或利好。
        遇到策略或研究方法问题时，先调用 list_knowledge_bases；确定文档后调用 get_knowledge_base。不要假设程序只有某一个策略。
        工具返回的数据优先于你的记忆；需要最新信息时必须调用对应工具。工具没有返回的数据要明确说明缺口，不得补造。
        网络资料必须优先采用权威来源：公司公告、交易所/监管机构、公司官网和主流财经媒体等可追溯来源；低质量转载、自媒体和无来源观点只能作为线索，不能单独支撑关键结论。
        对增发、业绩、处罚、重大合同、监管事项等重大信息，尽量调用 search_web 做交叉验证；无法交叉验证时明确写出“尚未交叉验证”，并降低结论可信度。网络搜索摘要不是事实本身，必须区分已验证事实、基于数据的推断和不确定信息。
        输出必须是合法 JSON，不要 Markdown，不要代码围栏。字段必须包括：answer、headline、operation_advice、trend、fundamentals、portfolio、market_context、market_risk、moat、valuation、catalysts、risks、scenarios、watch_items、confidence、sources。
        catalysts、risks、scenarios、watch_items、sources 必须是字符串数组。结论要区分事实、推断和不确定性，不给出保证收益的买卖指令。
        operation_advice 必须给出简洁、条件式的操作建议，例如“等待回踩企稳后再观察，不追涨”“已有仓位持有观察，跌破某条件再评估”或“暂不参与，等待基本面/技术面缺口补齐”；不得写成无条件的确定性买卖指令。若数据不足，明确写“数据不足，暂不操作”。
        对于持仓中已经深套的股票，不能仅因为浮亏较大就建议割肉或直接止损。若基本面没有明确恶化，操作建议必须优先采用“保留底仓、机动仓（滚动仓）滚动做T降低成本”：反弹到压力位附近卖出机动仓，回踩支撑并企稳后再接回；跌破或未企稳时不接回，等待反弹。只有明确基本面恶化、退市/重大风险或持续破位且风险已独立成立时，才可建议分批退出，并说明触发原因；不能把“深套”本身当作割肉理由。
        answer 必须先直接回答用户问题，再给出最关键的依据；综合结论控制在 3～5句，避免复述同一事实。catalysts、risks、scenarios、watch_items 每项只保留最重要的 1～3 条，内容要具体、简洁、可核对。sources 只填写实际使用过的来源标题或链接；来源未提供发布日期时，明确写“发布日期未提供”。
    """.trimIndent()

    fun user(input: AiAnalysisInput): String = buildString {
        appendLine("【用户问题】")
        appendLine(input.question)
        appendLine("数据生成时间：${input.generatedAt}")
        appendLine("如果问题涉及具体股票或指数，请先使用 search_security，不要等待客户端提供预解析标的。")
    }

}

private const val MAX_TOOL_ROUNDS = 8

class DeepSeekClient(
    private val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
    private val baseUrl: String = BuildConfig.DEEPSEEK_BASE_URL,
    private val model: String = BuildConfig.DEEPSEEK_MODEL.ifBlank { "deepseek-flash" },
) {
    fun analyze(input: AiAnalysisInput, tools: AiToolExecutor): AiAnalysisResult {
        if (apiKey.isBlank()) throw Http.HttpError("未配置 DEEPSEEK_API_KEY")
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", AiAnalysisPrompt.system()))
            .put(JSONObject().put("role", "user").put("content", AiAnalysisPrompt.user(input)))
        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("max_tokens", 8_000)
                .put("stream", false)
                .put("tools", tools.definitions())
                .put("tool_choice", "auto")
            val response = Http.postJson(
                completionUrl(baseUrl),
                body.toString(),
                timeoutMs = 60_000,
                userAgent = "StockMonitorAndroid/1.0",
                authorization = apiKey,
            )
            val root = runCatching { JSONObject(response) }
                .getOrElse { throw Http.HttpError("DeepSeek 返回不是有效 JSON") }
            val message = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?: throw Http.HttpError("DeepSeek 返回缺少 message")
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                val content = message.optString("content").trim()
                if (content.isBlank()) throw Http.HttpError("DeepSeek 返回内容为空")
                return parseContent(content)
            }
            messages.put(message)
            for (index in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(index) ?: continue
                val function = call.optJSONObject("function")
                    ?: throw Http.HttpError("DeepSeek 工具调用缺少 function")
                val name = function.optString("name")
                val arguments = runCatching { JSONObject(function.optString("arguments", "{}")) }
                    .getOrElse { throw Http.HttpError("DeepSeek 工具参数不是有效 JSON：$name") }
                val result = runCatching { tools.execute(name, arguments) }
                    .getOrElse { JSONObject().put("error", it.message ?: "工具执行失败").toString() }
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.optString("id"))
                        .put("content", result),
                )
            }
        }
        throw Http.HttpError("AI 工具调用超过最大轮次")
    }

    fun analyze(input: AiAnalysisInput): AiAnalysisResult =
        analyze(input, object : AiToolExecutor {
            override fun definitions(): JSONArray = AiToolCatalog.definitions()

            override fun execute(name: String, arguments: JSONObject): String =
                JSONObject().put("error", "未配置程序工具：$name").toString()
        })

    companion object {
        private fun completionUrl(baseUrl: String): String {
            val normalized = baseUrl.trimEnd('/').removeSuffix("/v1")
            return "$normalized/chat/completions"
        }

        fun parseResponse(text: String): AiAnalysisResult {
            val root = runCatching { JSONObject(text) }
                .getOrElse { throw Http.HttpError("DeepSeek 返回不是有效 JSON") }
            val choices = root.optJSONArray("choices")
                ?: throw Http.HttpError("DeepSeek 返回缺少 choices")
            val content = choices.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()
            if (content.isBlank()) throw Http.HttpError("DeepSeek 返回内容为空")
            val normalized = content
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            return parseContent(normalized)
        }

        private fun parseContent(content: String): AiAnalysisResult {
            val normalized = content
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            return runCatching { AiAnalysisResult.fromJson(JSONObject(normalized)) }
                .getOrElse { throw Http.HttpError("DeepSeek 返回不是有效 JSON") }
        }
    }

}

class WebResearchClient {
    fun search(name: String, code: String): List<WebResearchItem> {
        return search("", name, code)
    }

    fun search(userQuery: String, name: String, code: String): List<WebResearchItem> {
        val query = listOf(
            userQuery.trim(),
            name.trim(),
            code.trim(),
            "公告 财报 公司官网 交易所 监管 主营业务 行业地位 技术壁垒 最新消息",
        ).filter { it.isNotBlank() }.distinct().joinToString(" ")
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val html = Http.getText(
            "https://html.duckduckgo.com/html/?q=$encoded",
            Charsets.UTF_8,
            referer = "https://duckduckgo.com/",
            timeoutMs = 12_000,
        )
        return parseHtml(html)
    }

    companion object {
        fun parseHtml(html: String): List<WebResearchItem> {
            val resultPattern = Regex(
                "class=\\\"result__a\\\"[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>(.*?)(?=class=\\\"result__a\\\"|$)",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            val snippetPattern = Regex("class=\\\"result__snippet\\\"[^>]*>(.*?)</", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            return resultPattern.findAll(html).mapNotNull { match ->
                val title = cleanHtml(match.groupValues[2])
                val url = decodeRedirect(match.groupValues[1])
                val snippet = snippetPattern.find(match.groupValues[3])?.groupValues?.get(1)?.let(::cleanHtml).orEmpty()
                if (title.isBlank() || url.isBlank()) null else WebResearchItem(title, url, snippet)
            }.take(6).toList()
        }

        private fun decodeRedirect(value: String): String {
            val normalized = value.replace("&amp;", "&").replace("\\u003d", "=")
            val encodedTarget = normalized.substringAfter("uddg=", "").substringBefore('&')
            return if (encodedTarget.isBlank()) {
                normalized
            } else {
                runCatching { java.net.URLDecoder.decode(encodedTarget, "UTF-8") }
                    .getOrDefault(normalized)
            }
        }

        private fun cleanHtml(value: String): String = value
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
