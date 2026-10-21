package com.example.stockmonitor.ui

import android.content.Context
import com.example.stockmonitor.analysis.FundamentalHealthPolicy
import com.example.stockmonitor.analysis.Fundamentals
import com.example.stockmonitor.analysis.FundamentalsReport
import com.example.stockmonitor.analysis.GlobalFundamentalsReport
import com.example.stockmonitor.analysis.EnvContext
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.MarketRiskContext
import com.example.stockmonitor.analysis.MarketTimingContext
import com.example.stockmonitor.analysis.RecentMarketContext
import com.example.stockmonitor.analysis.StockAnalysis
import com.example.stockmonitor.analysis.TimeUtils
import com.example.stockmonitor.analysis.TurnoverContext
import com.example.stockmonitor.analysis.TurnoverTracker
import com.example.stockmonitor.core.AiToolCatalog
import com.example.stockmonitor.core.AiToolExecutor
import com.example.stockmonitor.core.AnalysisReportCache
import com.example.stockmonitor.core.BoardTagCache
import com.example.stockmonitor.core.EastMoneyClient
import com.example.stockmonitor.core.FundamentalsClient
import com.example.stockmonitor.core.GlobalFundamentalsClient
import com.example.stockmonitor.core.GlobalSecurity
import com.example.stockmonitor.core.RowSnapshot
import com.example.stockmonitor.core.SinaClient
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.core.WebResearchClient
import com.example.stockmonitor.monitor.Repo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 综合分析的程序工具桥接层。
 *
 * 这里的工具全部是只读操作：模型负责决定调用顺序，应用负责执行已有数据接口，
 * 再把结构化结果回传给模型。任何工具都不允许下单或修改用户配置。
 */
internal class StockAiToolExecutor(context: Context) : AiToolExecutor {
    private val appContext = context.applicationContext
    private val store = Store(appContext)

    @Volatile
    var resolvedSecurity: ResolvedSecurity? = null
        private set

    data class ResolvedSecurity(
        val name: String,
        val code: String,
        val market: String,
    )

    override fun definitions(): JSONArray = AiToolCatalog.definitions()

    override fun execute(name: String, arguments: JSONObject): String =
        runCatching {
            when (name) {
                "search_security" -> searchSecurity(arguments)
                "get_realtime_quote" -> getRealtimeQuote(arguments)
                "get_fundamental_analysis" -> getFundamentalAnalysis(arguments)
                "get_technical_analysis" -> getTechnicalAnalysis(arguments)
                "get_kline_summary" -> getKlineSummary(arguments)
                "get_portfolio_report" -> getPortfolioReport()
                "get_market_summary_report" -> getMarketSummaryReport()
                "get_recent_market_context" -> getRecentMarketContext(arguments)
                "get_sector_snapshot" -> getSectorSnapshot(arguments)
                "get_market_risk_context" -> getMarketRiskContext()
                "search_web" -> searchWeb(arguments)
                "list_knowledge_bases" -> listKnowledgeBases()
                "get_knowledge_base" -> getKnowledgeBase(arguments)
                else -> error("未知工具：$name")
            }.toString()
        }.getOrElse {
            JSONObject()
                .put("error", it.message ?: "工具执行失败")
                .put("tool", name)
                .toString()
        }

    private fun getSectorSnapshot(arguments: JSONObject): JSONObject {
        val query = arguments.optString("query").trim()
        require(query.isNotBlank()) { "get_sector_snapshot 缺少 query" }
        return SectorSnapshotPayload.build(
            query = query,
            industries = Repo.sectors?.all.orEmpty(),
            concepts = Repo.conceptSummary?.sectors.orEmpty(),
            industryUpdatedAtMs = Repo.sectorsUpdatedAtMs,
            conceptUpdatedAtMs = Repo.conceptSummaryUpdatedAtMs,
            nowMs = System.currentTimeMillis(),
            conceptRisingCount = Repo.conceptSummary?.rising,
            conceptFallingCount = Repo.conceptSummary?.falling,
            limit = arguments.optInt("limit", 5),
            includeRankings = arguments.optBoolean("include_rankings", true),
        )
    }

    private fun searchSecurity(arguments: JSONObject): JSONObject {
        val query = arguments.optString("query").trim()
        require(query.isNotBlank()) { "search_security 缺少 query" }
        val candidates = JSONArray()
        val seen = mutableSetOf<String>()

        runCatching { SinaClient().search(query).take(10) }
            .getOrDefault(emptyList())
            .forEach { (code, name) ->
                if (seen.add(code)) {
                    candidates.put(
                        JSONObject()
                            .put("name", name)
                            .put("code", code)
                            .put("market", "A股"),
                    )
                }
            }

        runCatching { GlobalFundamentalsClient().search(query).take(10) }
            .getOrDefault(emptyList())
            .forEach { security ->
                if (seen.add(security.symbol)) {
                    candidates.put(
                        JSONObject()
                            .put("name", security.name)
                            .put("code", security.symbol)
                            .put("market", security.exchange),
                    )
                }
            }

        if (candidates.length() == 0) {
            return JSONObject()
                .put("query", query)
                .put("candidates", candidates)
                .put("message", "没有找到匹配的股票或指数，请换用名称、代码或英文名称重试")
        }

        val first = candidates.optJSONObject(0)
        resolvedSecurity = ResolvedSecurity(
            name = first?.optString("name").orEmpty(),
            code = first?.optString("code").orEmpty(),
            market = first?.optString("market").orEmpty(),
        )
        return JSONObject().put("query", query).put("candidates", candidates)
    }

    private fun getRealtimeQuote(arguments: JSONObject): JSONObject {
        val code = arguments.optString("code").trim()
        require(code.isNotBlank()) { "get_realtime_quote 缺少 code" }
        if (isA股(code)) {
            val quote = SinaClient().quote(code) ?: error("没有取到 $code 的实时行情")
            return JSONObject()
                .put("name", quote.name)
                .put("code", quote.code)
                .put("market", "A股")
                .put("price", quote.price)
                .put("change_pct", quote.changePct)
                .put("volume", quote.volume)
                .put("turnover", quote.turnover)
        }
        val security = resolveGlobal(code, arguments.optString("name"))
        val data = GlobalFundamentalsClient().fetch(security)
        return JSONObject()
            .put("name", security.name)
            .put("code", security.symbol)
            .put("market", security.exchange)
            .put("currency", data.currency)
            .put("price", data.price ?: JSONObject.NULL)
            .put("market_cap", data.marketCap ?: JSONObject.NULL)
            .put("data_sources", JSONArray(data.dataSources))
    }

    private fun getFundamentalAnalysis(arguments: JSONObject): JSONObject {
        val code = arguments.optString("code").trim()
        require(code.isNotBlank()) { "get_fundamental_analysis 缺少 code" }
        val name = arguments.optString("name").trim()
        val refresh = !arguments.has("refresh") || arguments.optBoolean("refresh", true)
        val cached = AnalysisReportCache.get(store.analysisReports, code)
        if (!refresh && cached?.fundamentalReport != null) {
            return JSONObject()
                .put("name", cached.name)
                .put("code", code)
                .put("market", if (isA股(code)) "A股" else "港美股")
                .put("source", "程序本地缓存")
                .put("report", cached.fundamentalReport)
        }

        return if (isA股(code)) {
            val actualName = name.ifBlank { cached?.name ?: code }
            val report = buildA股FundamentalReport(code, actualName)
            saveFundamentalReport(code, actualName, report)
            JSONObject()
                .put("name", actualName)
                .put("code", code)
                .put("market", "A股")
                .put("source", "东方财富公开财报与公告接口")
                .put("report", report)
        } else {
            val security = resolveGlobal(code, name)
            val data = GlobalFundamentalsClient().fetch(security)
            val report = GlobalFundamentalsReport.format(
                data,
                nowStamp(),
            )
            saveFundamentalReport(security.symbol, security.name, report)
            JSONObject()
                .put("name", security.name)
                .put("code", security.symbol)
                .put("market", security.exchange)
                .put("source", JSONArray(data.dataSources))
                .put("report", report)
        }
    }

    private fun getTechnicalAnalysis(arguments: JSONObject): JSONObject {
        val code = arguments.optString("code").trim()
        require(code.isNotBlank()) { "get_technical_analysis 缺少 code" }
        val requestedName = arguments.optString("name").trim()
        val name = requestedName.ifBlank { AnalysisReportCache.get(store.analysisReports, code)?.name ?: code }
        val history = if (isA股(code)) {
            SinaClient().kline(code, 240)
        } else {
            val security = resolveGlobal(code, name)
            GlobalFundamentalsClient().kline(security, 240)
        }
        val result = StockAnalysis.analyze(history, code, name, live = null)
        // 个股报告要带环境：不带的话，塌陷期那句「回踩不破均线可以继续持有」是反话。
        // 板块归属只对 A 股有数据源，港美股写「归属暂缺」而不是硬套 A 股板块。
        val aShare = isA股(code)
        val env = EnvContext(
            market = MarketRegime.evaluate(if (aShare) SinaClient().kline("sh000001", 240) else emptyList()),
            sector = if (aShare) {
                BoardTagCache.loadContext(
                    store = store,
                    eastMoney = EastMoneyClient(),
                    code = code,
                    boardChangeFallback = { names ->
                        val boards = runCatching { EastMoneyClient().industryBoardChanges() }
                            .getOrDefault(emptyMap())
                        BoardTagCache.matchBoardChange(boards, names) { name ->
                            Repo.sectorChangeByName(name) {
                                runCatching { SinaClient().sectors() }.getOrNull()
                            }
                        }
                    },
                )
            } else {
                null
            },
            prev = AnalysisReportCache.get(store.analysisReports, code)?.snapshot,
            marketRisk = Repo.marketRiskContext,
        )
        val report = StockAnalysis.formatReport(result, env)
        // 存下这次的状态快照，下次取同一份报告才有「较上次分析」的对照。
        store.analysisReports = AnalysisReportCache.merge(
            raw = store.analysisReports,
            code = code,
            name = name,
            technical = report,
            fundamental = null,
            updatedAt = TimeUtils.nowText(),
            snapshot = RowSnapshot(
                code = code,
                signal = result.entrySetup.label,
                score = result.score,
                support = result.support,
                resistance = result.resistance,
                price = result.price,
                changePct = result.priceChange,
                volumeRatio = result.volumeRatio ?: 0.0,
            ),
        )
        return JSONObject()
            .put("name", name)
            .put("code", code)
            .put("market", if (isA股(code)) "A股" else "港美股")
            .put("report", report)
            .put("support", result.support)
            .put("resistance", result.resistance)
            .put("score", result.score)
    }

    private fun getKlineSummary(arguments: JSONObject): JSONObject {
        val code = arguments.optString("code").trim()
        require(code.isNotBlank()) { "get_kline_summary 缺少 code" }
        val cached = AnalysisReportCache.get(store.analysisReports, code)
        val name = cached?.name ?: code
        val history = if (isA股(code)) {
            SinaClient().kline(code, 240)
        } else {
            GlobalFundamentalsClient().kline(resolveGlobal(code, name), 240)
        }
        require(history.size >= 21) { "$code 的 K 线数据不足21根" }
        val latest = history.last()
        fun change(days: Int): Double {
            val previous = history[history.size - 1 - days].close
            return if (previous > 0) (latest.close / previous - 1.0) * 100 else 0.0
        }
        val averageVolume = history.takeLast(20).map { it.volume }.average()
        return JSONObject()
            .put("name", name)
            .put("code", code)
            .put("latest_date", latest.date)
            .put("latest_close", latest.close)
            .put("change_5d_pct", change(5))
            .put("change_20d_pct", change(20))
            .put("change_60d_pct", change(minOf(60, history.size - 1)))
            .put("latest_volume_vs_20d_avg", if (averageVolume > 0) latest.volume / averageVolume else JSONObject.NULL)
    }

    private fun getPortfolioReport(): JSONObject {
        val inMemory = Repo.portfolioReport?.takeIf { it.isNotBlank() }
        val persisted = store.lastPortfolioReport?.takeIf { it.isNotBlank() }
        val report = inMemory ?: persisted
        return reportPayload(
            report = report,
            source = if (inMemory != null) "Repo内存缓存" else if (persisted != null) "本地持久化缓存" else "未生成",
            generatedAt = report?.lineSequence()
                ?.firstOrNull { it.contains("分析时间：") }
                ?.substringAfter("分析时间：")
                ?.trim()
                .orEmpty(),
        )
    }

    private fun getMarketSummaryReport(): JSONObject {
        val inMemory = Repo.summaryText?.takeIf { it.isNotBlank() }
        val persisted = store.lastSummaryText?.takeIf { it.isNotBlank() }
        val report = inMemory ?: persisted
        val date = store.lastSummaryGeneratedAt
            ?: store.lastSummaryNight
            ?: store.lastSummaryDate
            ?: store.lastSummaryAfternoon
        return reportPayload(
            report = report,
            source = if (inMemory != null) "Repo内存缓存" else if (persisted != null) "本地持久化缓存" else "未生成",
            generatedAt = date.orEmpty(),
        )
    }

    private fun getMarketRiskContext(): JSONObject {
        val cached = Repo.marketRiskContext
        if (cached?.isFresh() == true) return cached.toJson()

        val snapshot = Repo.turnoverSnapshot
        val gaps = mutableListOf<String>()
        val northbound = runCatching { EastMoneyClient().northboundDailyFlow() }.getOrNull()
            ?: run { gaps.add("北向资金数据暂缺"); null }
        val sameTimeRatio = snapshot?.let {
            TurnoverTracker(store).sameTimeRatio(TimeUtils.todayText(), it.sampleMinute, it.today)
        }
        val turnover = TurnoverContext(
            today = (snapshot?.today ?: Repo.turnoverToday).takeIf { it > 0 },
            previousSameTime = snapshot?.previousSameTime,
            predictedFullDay = snapshot?.predictedFullDay,
            sameTimeRatio = sameTimeRatio,
        ).takeIf { it.today != null || it.previousSameTime != null || it.predictedFullDay != null }
            ?: run { gaps.add("成交额快照暂缺"); null }
        val context = MarketRiskContext(
            timing = MarketTimingContext.from(),
            northbound = northbound,
            turnover = turnover,
            recentMarketText = Repo.summaryText ?: store.lastSummaryText,
            dataGaps = gaps,
            generatedAtMs = System.currentTimeMillis(),
        )
        Repo.marketRiskContext = context
        return context.toJson()
    }

    private fun reportPayload(report: String?, source: String, generatedAt: String): JSONObject = JSONObject()
        .put("report_status", if (report.isNullOrBlank()) "missing" else "available")
        .put("source", source)
        .put("generated_at", generatedAt.ifBlank { JSONObject.NULL })
        .put("report", report ?: "数据暂缺：尚未生成该报告")

    private fun getRecentMarketContext(arguments: JSONObject): JSONObject {
        val code = arguments.optString("code").trim()
        require(code.isNotBlank()) { "get_recent_market_context 缺少 code" }
        val cached = AnalysisReportCache.get(store.analysisReports, code)
        val name = arguments.optString("name").trim().ifBlank { cached?.name ?: code }
        val result = JSONObject()
            .put("name", name)
            .put("code", code)
            .put("lookback", "最近可取得的交易日：1日、5日、20日")
        val gaps = JSONArray()
        val indices = JSONObject()
        if (isA股(code)) {
            mapOf(
                "沪指" to "sh000001",
                "深成指" to "sz399001",
                "创业板指" to "sz399006",
            ).forEach { (label, indexCode) ->
                val bars = runCatching { SinaClient().kline(indexCode, 60) }.getOrDefault(emptyList())
                if (bars.isEmpty()) {
                    gaps.put("${label}近期数据暂缺")
                } else {
                    indices.put(label, RecentMarketContext.summarize(bars).toJson())
                }
            }
        } else {
            gaps.put("港美股暂未接入对应的大盘和行业板块多日数据")
        }
        result.put("indices", indices)

        val sectors = JSONArray()
        if (isA股(code)) {
            val boardBars = mutableMapOf<String, List<com.example.stockmonitor.core.DayBar>>()
            val context = runCatching {
                BoardTagCache.loadContext(
                    store = store,
                    eastMoney = EastMoneyClient(),
                    code = code,
                    boardBars = boardBars,
                )
            }.getOrNull()
            val industry = context?.industry
            val bars = industry?.boardCode?.let { boardBars[it] }.orEmpty()
            if (industry == null) {
                gaps.put("目标股票所属主行业暂缺")
            } else if (bars.isEmpty()) {
                gaps.put("行业「${industry.name}」近期日线暂缺")
            } else {
                sectors.put(
                    RecentMarketContext.summarize(bars).toJson()
                        .put("name", industry.name)
                        .put("board_code", industry.boardCode ?: JSONObject.NULL),
                )
            }
        }
        result.put("sectors", sectors)
        result.put("data_gaps", gaps)
        return result
    }

    private fun searchWeb(arguments: JSONObject): JSONObject {
        val query = arguments.optString("query").trim()
        require(query.isNotBlank()) { "search_web 缺少 query" }
        val name = arguments.optString("name").trim().ifBlank { query }
        val code = arguments.optString("code").trim()
        val results = WebResearchClient().search(query, name, code)
        val items = JSONArray()
        results.forEach { item ->
            items.put(
                JSONObject()
                    .put("title", item.title)
                    .put("url", item.url)
                    .put("snippet", item.snippet),
            )
        }
        return JSONObject().put("query", query).put("results", items)
    }

    private fun listKnowledgeBases(): JSONObject {
        val files = appContext.assets.list(KNOWLEDGE_DIR).orEmpty()
            .filter { it.endsWith(".md", ignoreCase = true) }
            .sorted()
        val documents = JSONArray()
        files.forEach { file ->
            val content = readKnowledge(file)
            documents.put(
                JSONObject()
                    .put("name", file.removeSuffix(".md"))
                    .put("file", "$KNOWLEDGE_DIR/$file")
                    .put("title", content.lineSequence().firstOrNull { it.trimStart().startsWith("#") }?.trim().orEmpty())
                    .put("summary", content.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }?.trim().orEmpty()),
            )
        }
        return JSONObject().put("documents", documents)
    }

    private fun getKnowledgeBase(arguments: JSONObject): JSONObject {
        val query = arguments.optString("query").trim()
        require(query.isNotBlank()) { "get_knowledge_base 缺少 query" }
        val files = appContext.assets.list(KNOWLEDGE_DIR).orEmpty()
            .filter { it.endsWith(".md", ignoreCase = true) }
        val matches = files.filter { file ->
            val content = readKnowledge(file)
            val normalizedQuery = query.lowercase(Locale.CHINA)
            file.lowercase(Locale.CHINA).contains(normalizedQuery) ||
                content.lowercase(Locale.CHINA).contains(normalizedQuery)
        }
        val selected = (matches.ifEmpty { files }).take(MAX_KNOWLEDGE_DOCUMENTS)
        val documents = JSONArray()
        selected.forEach { file ->
            documents.put(
                JSONObject()
                    .put("name", file.removeSuffix(".md"))
                    .put("file", "$KNOWLEDGE_DIR/$file")
                    .put("content", readKnowledge(file).take(MAX_KNOWLEDGE_CHARS)),
            )
        }
        return JSONObject()
            .put("query", query)
            .put("documents", documents)
            .put("notice", "知识库内容用于解释规则，不是当前行情事实；当前命中情况必须另行调用行情和技术工具确认。")
    }

    private fun readKnowledge(file: String): String =
        appContext.assets.open("$KNOWLEDGE_DIR/$file").bufferedReader().use { it.readText() }

    private fun buildA股FundamentalReport(code: String, name: String): String {
        val data = FundamentalsClient().fetch(code, name)
        val result = Fundamentals.analyze(
            Fundamentals.Input(
                periods = data.periods,
                balance = data.balance,
                valuations = data.valuations,
                totalShares = data.totalShares,
                price = data.price,
                peers = data.peers,
                mainBusiness = data.mainBusiness,
                announcements = data.announcements,
                shareholder = data.shareholder,
                today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date()),
            ),
        )
        return FundamentalsReport.format(name, code, data.price, nowStamp(), result).also {
            // 和工具页手动分析一样：结果进健康度缓存，盘中提醒复用，冷却内不重复拉。
            Repo.publishFundamentalHealth(code, FundamentalHealthPolicy.grade(result, nowStamp()))
        }
    }

    private fun saveFundamentalReport(code: String, name: String, report: String) {
        store.analysisReports = AnalysisReportCache.merge(
            raw = store.analysisReports,
            code = code,
            name = name,
            technical = null,
            fundamental = report,
            updatedAt = nowStamp(),
        )
    }

    private fun resolveGlobal(code: String, name: String): GlobalSecurity {
        val query = code.ifBlank { name }
        return GlobalFundamentalsClient().search(query).firstOrNull()
            ?: GlobalFundamentalsClient().search(name).firstOrNull()
            ?: error("没有找到港美股标的：$query")
    }

    private fun isA股(code: String): Boolean =
        code.startsWith("sh", ignoreCase = true) || code.startsWith("sz", ignoreCase = true)

    private fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())

    private companion object {
        const val KNOWLEDGE_DIR = "knowledge"
        const val MAX_KNOWLEDGE_DOCUMENTS = 3
        const val MAX_KNOWLEDGE_CHARS = 20_000
    }
}
