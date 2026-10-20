package com.example.stockmonitor

import com.example.stockmonitor.analysis.AiAnalysisReport
import com.example.stockmonitor.core.AiAnalysisResult
import com.example.stockmonitor.core.AiAnalysisInput
import com.example.stockmonitor.core.AiAnalysisPrompt
import com.example.stockmonitor.core.DeepSeekClient
import com.example.stockmonitor.core.AiToolCatalog
import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.Http
import com.example.stockmonitor.core.WebResearchClient
import com.example.stockmonitor.analysis.RecentMarketContext
import com.example.stockmonitor.analysis.ReportMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAnalysisTest {

    @Test
    fun aiResultReadsPortfolioAndMarketContextWithMissingDataFallback() {
        val result = AiAnalysisResult.fromJson(
            org.json.JSONObject()
                .put("headline", "综合判断")
                .put("portfolio", "持仓报告显示仓位偏高")
                .put("market_context", "近5日大盘偏弱，行业同步走弱"),
        )

        assertEquals("持仓报告显示仓位偏高", result.portfolio)
        assertEquals("近5日大盘偏弱，行业同步走弱", result.marketContext)
        assertEquals("数据暂缺", result.marketRisk)
        assertEquals("数据暂缺", AiAnalysisResult.fromJson(org.json.JSONObject()).portfolio)
        assertEquals("数据暂缺", AiAnalysisResult.fromJson(org.json.JSONObject()).marketContext)
        assertEquals("数据暂缺", AiAnalysisResult.fromJson(org.json.JSONObject()).operationAdvice)
    }

    @Test
    fun promptRequiresAllReportsAndRecentMarketContext() {
        val prompt = AiAnalysisPrompt.system()

        assertTrue(prompt.contains("技术面"))
        assertTrue(prompt.contains("基本面"))
        assertTrue(prompt.contains("持仓报告"))
        assertTrue(prompt.contains("盘面总结"))
        assertTrue(prompt.contains("最近几天"))
        assertTrue(prompt.contains("大盘和板块"))
        assertTrue(prompt.contains("市场风险上下文"))
        assertTrue(prompt.contains("北向资金"))
        assertTrue(prompt.contains("operation_advice"))
        assertTrue(prompt.contains("操作建议"))
        assertTrue(prompt.contains("滚动做T"))
        assertTrue(prompt.contains("深套"))
    }

    @Test
    fun toolCatalogIncludesPortfolioSummaryAndRecentMarketContextTools() {
        val catalog = AiToolCatalog.definitions().toString()

        assertTrue(catalog.contains("get_portfolio_report"))
        assertTrue(catalog.contains("get_market_summary_report"))
        assertTrue(catalog.contains("get_recent_market_context"))
        assertTrue(catalog.contains("get_market_risk_context"))
    }

    @Test
    fun recentMarketContextCalculatesTradingDayReturnsAndReportsGaps() {
        val bars = (1..6).map { day ->
            DayBar(
                date = "2026-10-${day.toString().padStart(2, '0')}",
                open = day.toDouble(),
                high = day.toDouble(),
                low = day.toDouble(),
                close = day.toDouble(),
                volume = 1.0,
            )
        }

        val summary = RecentMarketContext.summarize(bars)

        assertEquals(6.0, summary.latestClose!!, 0.0)
        assertEquals(20.0, summary.changePct(1)!!, 0.0001)
        assertEquals(500.0, summary.changePct(5)!!, 0.0001)
        assertTrue(summary.changePct(20) == null)
        assertTrue(summary.missingHorizons.contains(20))
    }

    @Test
    fun deepSeekRequestHeadersIncludeBearerAuthorization() {
        val headers = Http.jsonHeaders("StockMonitorAndroid/1.0", "sk-test")

        assertEquals("Bearer sk-test", headers["Authorization"])
    }

    @Test
    fun deepSeekParserReadsStructuredAnalysis() {
        val response = """
            {"choices":[{"message":{"content":"{\"headline\":\"趋势偏强但估值不低\",\"answer\":\"震荡偏强，等待确认\",\"operation_advice\":\"等待回踩企稳，不追涨；已有仓位持有观察\",\"trend\":\"均线多头\",\"fundamentals\":\"盈利和现金流较好\",\"moat\":\"技术壁垒较强\",\"valuation\":\"偏高\",\"catalysts\":[\"订单增长\"],\"risks\":[\"估值回撤\"],\"scenarios\":[\"若业绩兑现则震荡上行\"],\"watch_items\":[\"下一季毛利率\"],\"confidence\":\"中等\"}"}}]}
        """.trimIndent()

        val result = DeepSeekClient.parseResponse(response)

        assertEquals("震荡偏强，等待确认", result.answer)
        assertEquals("等待回踩企稳，不追涨；已有仓位持有观察", result.operationAdvice)
        assertEquals("趋势偏强但估值不低", result.headline)
        assertEquals("均线多头", result.trend)
        assertEquals(listOf("订单增长"), result.catalysts)
        assertEquals(listOf("下一季毛利率"), result.watchItems)
    }

    @Test
    fun deepSeekParserRejectsMissingChoices() {
        val error = runCatching { DeepSeekClient.parseResponse("{}") }.exceptionOrNull()

        assertTrue(error?.message?.contains("choices") == true)
    }

    @Test
    fun webResearchParserExtractsTitleUrlAndSnippet() {
        val html = """
            <a class="result__a" href="https://example.com/a">公司官网</a>
            <a class="result__snippet">主营业务和技术平台介绍</a>
            <a class="result__a" href="https://example.com/b">行业报告</a>
            <a class="result__snippet">行业竞争格局发生变化</a>
        """.trimIndent()

        val items = WebResearchClient.parseHtml(html)

        assertEquals(2, items.size)
        assertEquals("公司官网", items[0].title)
        assertEquals("https://example.com/a", items[0].url)
        assertTrue(items[1].snippet.contains("竞争格局"))
    }

    @Test
    fun aiReportContainsConclusionAndRisks() {
        val result = AiAnalysisResult(
            answer = "趋势偏强但估值不低",
            headline = "趋势偏强但估值不低",
            trend = "均线多头",
            fundamentals = "盈利较好",
            moat = "技术壁垒待验证",
            valuation = "偏高",
            catalysts = listOf("订单增长"),
            risks = listOf("估值回撤"),
            scenarios = listOf("震荡上行"),
            watchItems = listOf("下一季毛利率"),
            confidence = "中等",
            sources = listOf("程序本地缓存"),
            operationAdvice = "等待回踩企稳，不追涨；已有仓位持有观察",
        )

        val report = AiAnalysisReport.format(result, "英伟达", "NVDA", "2026-09-29 16:00")

        assertTrue(report.contains("核心结论"))
        assertTrue(report.contains("操作建议：等待回踩企稳，不追涨；已有仓位持有观察"))
        assertTrue(report.contains("技术面：均线多头"))
        assertTrue(report.contains("均线多头"))
        assertTrue(report.indexOf("趋势：") == -1)
        assertTrue(report.contains("估值回撤"))
        assertTrue(report.contains("下一季毛利率"))
    }

    @Test
    fun aiReportContainsPortfolioAndRecentMarketSections() {
        val result = AiAnalysisResult(
            answer = "暂以观察为主",
            headline = "结构性分化",
            trend = "技术面震荡",
            fundamentals = "基本面尚可",
            moat = "护城河待验证",
            valuation = "估值中性",
            catalysts = emptyList(),
            risks = listOf("市场波动"),
            scenarios = emptyList(),
            watchItems = emptyList(),
            confidence = "中等",
            sources = emptyList(),
            portfolio = "持仓占比偏高，暂不追加",
            marketContext = "今日盘面偏弱，但近20日指数仍处于震荡区间",
            marketRisk = "周五、月末；北向近5日净流出，成交额收缩",
        )

        val report = AiAnalysisReport.format(result, "测试股", "sh600000", "2026-10-12 16:00")

        assertTrue(report.contains("关键依据"))
        assertTrue(report.contains("持仓影响：持仓占比偏高，暂不追加"))
        assertTrue(report.contains("盘面环境：今日盘面偏弱，但近20日指数仍处于震荡区间"))
        assertTrue(report.contains("市场风险：周五、月末；北向近5日净流出，成交额收缩"))
    }

    @Test
    fun aiReportHighlightsImportantValuationNumbers() {
        val result = AiAnalysisResult(
            answer = "等待回踩",
            headline = "估值偏高",
            trend = "震荡",
            fundamentals = "盈利稳定",
            moat = "一般",
            valuation = "合理区间 24.34 ~ 34.21 元，现价 60.64 元，PE 92.9% 分位",
            catalysts = emptyList(),
            risks = emptyList(),
            scenarios = emptyList(),
            watchItems = emptyList(),
            confidence = "中等",
            sources = emptyList(),
        )

        val report = AiAnalysisReport.format(result, "测试股", "sh600000", "2026-09-30 10:00")

        assertTrue(report.contains(ReportMarkup.red("24.34 ~ 34.21 元")))
        assertTrue(report.contains(ReportMarkup.red("60.64 元")))
        assertTrue(report.contains(ReportMarkup.red("92.9%")))
        assertTrue(ReportMarkup.plain(report).contains("合理区间 24.34 ~ 34.21 元"))
    }

    @Test
    fun aiReportDoesNotRepeatAnswerInHeaderAndConclusion() {
        val result = AiAnalysisResult(
            answer = "今天不宜追高",
            headline = "短线偏谨慎",
            trend = "震荡",
            fundamentals = "尚可",
            moat = "待验证",
            valuation = "合理",
            catalysts = emptyList(),
            risks = emptyList(),
            scenarios = emptyList(),
            watchItems = emptyList(),
            confidence = "中等",
            sources = emptyList(),
        )

        val report = AiAnalysisReport.format(result, "万科", "sz000002", "2026-09-30 10:00")

        assertTrue(report.contains("核心结论"))
        assertTrue(!report.contains("问题：今天不宜追高"))
        assertEquals(1, report.windowed("今天不宜追高".length).count { it == "今天不宜追高" })
    }

    @Test
    fun aiReportSeparatesSummaryFromFullAnalysis() {
        val result = AiAnalysisResult(
            answer = "等待回踩确认",
            headline = "短线观望",
            trend = "震荡偏弱",
            fundamentals = "盈利稳定",
            moat = "竞争力一般",
            valuation = "接近合理区间",
            catalysts = listOf("订单改善"),
            risks = listOf("市场波动"),
            scenarios = emptyList(),
            watchItems = emptyList(),
            confidence = "中等",
            sources = emptyList(),
        )

        val report = AiAnalysisReport.format(result, "万科", "sz000002", "2026-09-30 10:00")

        assertTrue(report.contains("一、精要摘要"))
        assertTrue(report.contains("二、关键依据"))
        assertTrue(report.contains("核心结论：等待回踩确认"))
        assertTrue(report.contains("技术面：震荡偏弱"))
        assertTrue(report.contains("基本面：盈利稳定"))
        assertTrue(!report.contains("完整分析包括"))
        assertTrue(!report.contains("6. 护城河与竞争力"))
        assertEquals(1, report.windowed("震荡偏弱".length).count { it == "震荡偏弱" })
    }

    @Test
    fun aiPromptDelegatesKnowledgeLookupToGenericTools() {
        val prompt = AiAnalysisPrompt.user(
            AiAnalysisInput(
                question = "513策略现在有哪些候选股？",
                name = "特变电工",
                code = "002185",
                market = "A股",
                technicalReport = null,
                fundamentalReport = null,
                trendSummary = "暂缺",
                research = emptyList(),
                generatedAt = "2026-09-29 16:00",
                knowledgeBase = "513策略：5连阳 → 倍量阳 → 3天缩量确认",
            )
        )

        assertTrue(prompt.contains("search_security"))
        assertTrue(prompt.contains("不要等待客户端提供预解析标的"))
    }

    @Test
    fun aiPromptRequiresReliableSourcesAndConciseEvidenceBasedConclusion() {
        val system = AiAnalysisPrompt.system()
        val tools = AiToolCatalog.definitions().toString()

        assertTrue(system.contains("权威来源"))
        assertTrue(system.contains("交叉验证"))
        assertTrue(system.contains("事实、推断和不确定性"))
        assertTrue(system.contains("3～5句"))
        assertTrue(tools.contains("公司公告、交易所/监管机构、公司官网"))
        assertTrue(tools.contains("来源链接"))
    }

    @Test
    fun toolCatalogUsesGenericKnowledgeTools() {
        val definitions = AiToolCatalog.definitions().toString()
        assertTrue(definitions.contains("list_knowledge_bases"))
        assertTrue(definitions.contains("get_knowledge_base"))
        assertTrue(!definitions.contains("get_513_strategy"))
    }

}
