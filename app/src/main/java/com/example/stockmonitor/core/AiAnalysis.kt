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
    val trend: String = "",
    val fundamentals: String = "",
    val moat: String = "",
    val valuation: String = "",
    val catalysts: List<String>,
    val risks: List<String>,
    val scenarios: List<String>,
    val watchItems: List<String>,
    val confidence: String,
    val sources: List<String>,
    val portfolio: String = "",
    val sectorContext: String = "",
    val marketContext: String = "",
    val marketRisk: String = "",
    val operationAdvice: String = "",
    val analysisScope: String = "stock",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("headline", headline)
        .put("answer", answer)
        .put("analysis_scope", analysisScope)
        .put("trend", trend)
        .put("fundamentals", fundamentals)
        .put("sector_context", sectorContext)
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
            trend = json.optString("trend"),
            fundamentals = json.optString("fundamentals"),
            moat = json.optString("moat"),
            valuation = json.optString("valuation"),
            catalysts = json.optStringList("catalysts"),
            risks = json.optStringList("risks"),
            scenarios = json.optStringList("scenarios"),
            watchItems = json.optStringList("watch_items"),
            confidence = json.optString("confidence", "低"),
            sources = json.optStringList("sources"),
            portfolio = json.optString("portfolio"),
            sectorContext = json.optString("sector_context"),
            marketContext = json.optString("market_context"),
            marketRisk = json.optString("market_risk"),
            operationAdvice = json.optString("operation_advice"),
            analysisScope = normalizeScope(json.optString("analysis_scope")),
        )

        private fun JSONObject.optStringList(key: String): List<String> {
            val array = optJSONArray(key) ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }

        private fun normalizeScope(value: String): String = when (value.trim().lowercase()) {
            "stock", "个股" -> "stock"
            "sector", "板块" -> "sector"
            "market", "市场" -> "market"
            "strategy", "策略" -> "strategy"
            else -> "general"
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
            description = "按用户原话搜索股票或指数，支持名称、代码、拼音和英文名称；只用于个股或指数问题。只问行业/概念板块时不要调用。",
            properties = JSONObject()
                .put("query", stringProperty("用户问题中的标的描述或完整原话")),
            required = listOf("query"),
        ))
        .put(function(
            name = "get_realtime_quote",
            description = "获取指定个股或指数的最新行情、现价、涨跌幅和成交量；不用于回答纯板块问题。",
            properties = JSONObject()
                .put("code", stringProperty("search_security 返回的代码")),
            required = listOf("code"),
        ))
        .put(function(
            name = "get_fundamental_analysis",
            description = "读取或刷新个股完整基本面分析报告，包括利润、现金流、股东结构、公告、估值和风险；只在问题确实需要个股基本面时调用。",
            properties = JSONObject()
                .put("code", stringProperty("search_security 返回的代码"))
                .put("name", stringProperty("标的名称，可选"))
                .put("refresh", JSONObject().put("type", "boolean").put("description", "是否优先刷新公开数据")),
            required = listOf("code"),
        ))
        .put(function(
            name = "get_technical_analysis",
            description = "获取个股或指数的技术分析报告，包括均线、量价、MACD、RSI、支撑压力、筹码和条件式建议；财务或纯板块问题不要调用。",
            properties = JSONObject()
                .put("code", stringProperty("search_security 返回的代码"))
                .put("name", stringProperty("标的名称，可选")),
            required = listOf("code"),
        ))
        .put(function(
            name = "get_kline_summary",
            description = "获取个股或指数近期 K 线走势和成交量变化摘要，用于判断短中期趋势。",
            properties = JSONObject()
                .put("code", stringProperty("search_security 返回的代码")),
            required = listOf("code"),
        ))
        .put(function(
            name = "get_portfolio_report",
            description = "仅当问题涉及用户持仓、仓位或持仓影响时，读取最近一次持仓报告。报告可能来自内存或本地缓存；只读，不会触发重新分析。",
            properties = JSONObject(),
            required = emptyList(),
        ))
        .put(function(
            name = "get_market_summary_report",
            description = "读取最近一次盘面总结，适合市场或板块环境问题。它可能只覆盖今天；涉及多日趋势时再按需结合最近市场上下文。只读，不会触发重新生成。",
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
            name = "get_sector_snapshot",
            description = "读取行业或概念板块的涨跌、领涨股、市场宽度和数据更新时间；不需要也不接受个股代码。适合只问板块强弱、轮动、排名或宽度的问题。",
            properties = JSONObject()
                .put("query", stringProperty("行业或概念名称，例如半导体、机器人、新能源汽车"))
                .put("limit", JSONObject().put("type", "integer").put("description", "榜单条数，可选，默认5"))
                .put("include_rankings", JSONObject().put("type", "boolean").put("description", "是否附带行业/概念涨跌榜；只看单一板块时可传false以减少噪声")),
            required = listOf("query"),
        ))
        .put(function(
            name = "get_related_news",
            description = "查询与股票名称、代码、行业或概念板块相关的最近公开新闻，返回标题、来源、发布时间、原文链接、相关性和多源印证情况。适合回答催化事件、风险事件、订单、扩产、并购、监管、行业变化；返回为空表示未找到匹配新闻，不代表没有事件。",
            properties = JSONObject()
                .put("query", stringProperty("股票名称、代码、行业或概念板块"))
                .put("max_age_days", JSONObject().put("type", "integer").put("description", "最多回看天数，可选，默认3，范围1到7"))
                .put("max_items", JSONObject().put("type", "integer").put("description", "最多返回条数，可选，默认6，范围1到10")),
            required = listOf("query"),
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
        先判断问题范围，再只调用回答所必需的只读工具；不要因为应用提供了工具就机械地全部调用。工具调用应随问题变化：能用一个准确工具回答就不要扩张到无关数据。
        个股问题：不要自行猜测股票代码或把完整问题当成股票名称，先用 search_security 确认标的。再按具体意图选择数据——问走势就调行情、K线或技术面，问财务就调基本面，问所属板块就调板块和市场环境，问持仓影响才调持仓报告；只有用户明确要求综合分析或结论确实需要时，才组合技术面、基本面、板块、市场和持仓数据。财务问题不要为了“完整”而调用技术面，技术问题也不必强行补基本面。
        板块问题：优先使用 get_sector_snapshot、get_market_summary_report 和必要的网络资料；不要调用个股技术分析、个股基本面、个股K线或个股持仓工具。先分清用户问的是行业、概念、相对强弱、资金还是整体宽度，只取得对应数据。
        大盘问题：按需使用 get_market_summary_report、get_market_risk_context 和必要的指数行情；没有明确个股或板块时，不要搜索并强行绑定一只股票。策略或研究方法问题先用 list_knowledge_bases，再按需读取 get_knowledge_base；一般知识问题不必调用行情工具。
        涉及新闻、公告、催化事件或风险事件时，调用 get_related_news：个股传公司名或代码，行业/概念问题传板块名称；根据事件新鲜度和来源判断可信度，查不到就明确写新闻数据不足，不能把没有命中写成没有事件。
        回答必须直接围绕问题，灵活组织依据，不要套用固定章节，也不要重复已知信息。准确性和可核对性优先：明确区分事实、推断和不确定性，缺失或过期数据必须披露，不能用无关数据填补。
        基本面涉及下一季或年报时，必须明确区分已披露业绩预告、财报事实和基于历史同比/现金流质量的趋势外推；没有业绩预告就不能写成确定的盈利预测或具体利润数字。市场风险上下文中的数据日期、更新时间和数据缺口优先于摘要文字；过期、冻结、单位未确认或暂缺的数据只能写成不确定性，不能当作中性或利好。
        工具返回的数据优先于你的记忆；需要最新信息时必须调用对应工具。工具没有返回的数据要明确说明缺口，不得补造。
        网络资料必须优先采用权威来源：公司公告、交易所/监管机构、公司官网和主流财经媒体等可追溯来源；低质量转载、自媒体和无来源观点只能作为线索，不能单独支撑关键结论。
        对增发、业绩、处罚、重大合同、监管事项等重大信息，尽量调用 search_web 做交叉验证；无法交叉验证时明确写出“尚未交叉验证”，并降低结论可信度。网络搜索摘要不是事实本身，必须区分已验证事实、基于数据的推断和不确定信息。
        输出必须是合法 JSON，不要 Markdown，不要代码围栏。字段必须包括：answer、headline、analysis_scope、operation_advice、trend、fundamentals、portfolio、sector_context、market_context、market_risk、moat、valuation、catalysts、risks、scenarios、watch_items、confidence、sources。analysis_scope 只能是 stock、sector、market、strategy 或 general；与问题无关的分析字段返回空字符串或空数组，不要写“数据不足”来制造冗长报告。
        catalysts、risks、scenarios、watch_items、sources 必须是字符串数组。结论要区分事实、推断和不确定性，不给出保证收益的买卖指令。
        涉及仓位或交易时，operation_advice 必须简洁、条件式，例如“等待回踩企稳后再观察，不追涨”“已有仓位持有观察，跌破某条件再评估”或“暂不参与，等待数据缺口补齐”；不得写成无条件的确定性买卖指令。若数据不足，明确写“数据不足，暂不操作”；非交易问题可返回空字符串。
        对于持仓中已经深套的股票，不能仅因为浮亏较大就建议割肉或直接止损。若基本面没有明确恶化，操作建议必须优先采用“保留底仓、机动仓（滚动仓）滚动做T降低成本”：反弹到压力位附近卖出机动仓，回踩支撑并企稳后再接回；跌破或未企稳时不接回，等待反弹。只有明确基本面恶化、退市/重大风险或持续破位且风险已独立成立时，才可建议分批退出，并说明触发原因；不能把“深套”本身当作割肉理由。
        answer 必须先直接回答用户问题，再给出最关键的依据；通常控制在 2～4句，简单问题更短，复杂问题最多5句，避免复述同一事实。catalysts、risks、scenarios、watch_items 每项只保留最重要的 1～3 条，内容要具体、简洁、可核对。sources 只填写实际使用过的来源标题或链接；来源未提供发布日期时，明确写“发布日期未提供”。
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
