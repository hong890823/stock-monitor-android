package com.example.stockmonitor

import com.example.stockmonitor.analysis.MarketSummary
import com.example.stockmonitor.core.ConceptSummary
import com.example.stockmonitor.core.LimitData
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.SectorData
import com.example.stockmonitor.core.SectorItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盘面总结开头的「今日要点」。
 *
 * 八节的长报告想看「今天什么情况、要不要动」得翻到底，所以开头给五行精要：
 * 大盘 / 量能 / 赚钱效应 / 主线与风险 / 结论。**结论那行与正文第八节同一条口径**——
 * 两处给出两个判断，用户会不知道该信哪个。
 */
class MarketSummaryBriefTest {

    private fun quote(code: String, name: String, changePct: Double) = Quote(
        code = code, name = name, price = 100.0, prevClose = 100.0 / (1 + changePct / 100),
        open = 100.0, high = 100.0, low = 100.0, volume = 1_000.0, turnover = 10_000.0,
    )

    private fun inputs(
        rising: Int = 20,
        falling: Int = 30,
    ) = MarketSummary.Inputs(
        quotes = mapOf(
            "sh000001" to quote("sh000001", "上证指数", -0.35),
            "sz399001" to quote("sz399001", "深证成指", -0.52),
        ),
        sectors = SectorData(
            top = listOf(SectorItem("半导体", 2.1, 100, 1.0, "甲", "+5.0%")),
            bottom = listOf(SectorItem("房地产", -3.2, 100, 1.0, "乙", "-4.0%")),
            all = emptyList(),
        ),
        concept = ConceptSummary(emptyList(), rising, falling),
        limits = LimitData(up = emptyList(), down = emptyList()),
        turnoverLines = listOf("今日 1.2 万亿｜较昨日同一时刻 +8%"),
        klines = emptyList(),
        amounts = null,
    )

    @Test
    fun briefHasTheFiveLines() {
        val brief = MarketSummary.brief(inputs())

        assertEquals(5, brief.size)
        assertTrue(brief[0].startsWith("大盘："))
        assertTrue(brief[0].contains("沪指 -0.35%"))
        assertTrue(brief[1].startsWith("量能："))
        assertTrue(brief[1].contains("较昨日同一时刻 +8%"))
        assertTrue(brief[2].startsWith("赚钱效应："))
        assertTrue(brief[3].startsWith("主线与风险："))
        assertTrue(brief[3].contains("半导体"))
        assertTrue(brief[3].contains("房地产"))
        assertTrue(brief[4].startsWith("结论："))
    }

    @Test
    fun conclusionUsesTheSameBreadthRuleAsTheBody() {
        // 宽度偏弱（20:50 = 29%）→ 防守口径，与正文第八节的「核心结论」同一条。
        val weak = MarketSummary.brief(inputs(rising = 20, falling = 50))
        assertTrue(weak[4].contains("市场宽度偏弱"))

        // 偏暖（70:30 = 70%）→ 防情绪过热。
        val warm = MarketSummary.brief(inputs(rising = 70, falling = 30))
        assertTrue(warm[4].contains("市场偏暖"))

        // 中间地带 → 结构性轮动。
        val mid = MarketSummary.brief(inputs(rising = 50, falling = 50))
        assertTrue(mid[4].contains("结构性轮动"))
    }

    @Test
    fun missingDataSaysSoRatherThanPrintingZero() {
        val brief = MarketSummary.brief(
            MarketSummary.Inputs(
                quotes = emptyMap(),
                sectors = null,
                concept = null,
                limits = null,
                turnoverLines = emptyList(),
                klines = emptyList(),
                amounts = null,
            ),
        )

        assertTrue(brief[0].contains("指数数据暂缺"))
        assertTrue(brief[1].contains("暂缺"))
        assertTrue(brief[2].contains("暂缺"))
        assertTrue(brief[3].contains("暂缺"))
        // 没宽度数据就不硬给判断。
        assertTrue(brief[4].contains("宽度数据暂缺"))
    }

    @Test
    fun freshIndexQuotesRequireAllThreeIndicesToBeFromToday() {
        val today = inputs().quotes.mapValues { (_, quote) -> quote.copy(quoteDate = "2026-10-13") } +
            ("sz399006" to quote("sz399006", "创业板指", -0.7).copy(quoteDate = "2026-10-12"))

        assertFalse(MarketSummary.hasFreshIndexQuotes(today, "2026-10-13"))
        assertTrue(
            MarketSummary.hasFreshIndexQuotes(
                today + ("sz399006" to today.getValue("sz399006").copy(quoteDate = "2026-10-13")),
                "2026-10-13",
            ),
        )
    }

    @Test
    fun strongShanghaiDropUsesPanicDescriptionBeforeBroadWeakness() {
        val input = inputs().copy(
            quotes = mapOf(
                "sh000001" to quote("sh000001", "上证指数", -1.2),
                "sz399001" to quote("sz399001", "深证成指", -0.2),
                "sz399006" to quote("sz399006", "创业板指", -0.4),
            ),
        )

        assertTrue(MarketSummary.generate(input).contains("放量杀跌，恐慌蔓延"))
    }

    @Test
    fun highTurnoverWithWeakBreadthIsReportedAsDivergence() {
        val input = inputs(rising = 25, falling = 75).copy(
            quotes = mapOf(
                "sh000001" to quote("sh000001", "上证指数", 0.1).copy(high = 102.0),
                "sz399001" to quote("sz399001", "深证成指", 0.2),
            ),
            predictedTurnoverYi = 22_000.0,
            recentTurnoversYi = listOf(16_000.0, 17_000.0, 18_000.0),
            sectors = SectorData(
                top = emptyList(),
                bottom = emptyList(),
                all = listOf(
                    SectorItem("行业甲", -1.2, 100, 1.0, "", ""),
                    SectorItem("行业乙", -0.8, 100, 1.0, "", ""),
                ),
            ),
        )

        assertTrue(MarketSummary.brief(input)[4].contains("放量分歧"))
        assertTrue(MarketSummary.generate(input).contains("板块扩散不足"))
        val coreConclusion = MarketSummary.generate(input).lineSequence()
            .first { it.startsWith("📌 核心结论：") }
        assertEquals(MarketSummary.brief(input)[4].removePrefix("结论："), coreConclusion.removePrefix("📌 核心结论："))
    }
}
