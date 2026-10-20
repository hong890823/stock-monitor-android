package com.example.stockmonitor

import com.example.stockmonitor.analysis.BoardTag
import com.example.stockmonitor.analysis.ChipPeaks
import com.example.stockmonitor.analysis.DivergenceResult
import com.example.stockmonitor.analysis.DivergenceState
import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.EnvContext
import com.example.stockmonitor.analysis.KdjValues
import com.example.stockmonitor.analysis.MacdValues
import com.example.stockmonitor.analysis.MarketFilter
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.MarketState
import com.example.stockmonitor.analysis.OpeningStructure
import com.example.stockmonitor.analysis.SectorContext
import com.example.stockmonitor.analysis.StockAnalysis
import com.example.stockmonitor.analysis.TechnicalResult
import com.example.stockmonitor.analysis.TrendStrength
import com.example.stockmonitor.analysis.Volatility
import com.example.stockmonitor.core.RowSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **个股技术分析报告**的环境层口径锁。
 *
 * 重点锁一件事：塌陷期那句「回踩不破均线可以继续持有」是**反话**——
 * 系统性下跌里支撑会一起破。所以它要**就地改写**，不是加一句备注了事
 * （备注会被当成补充说明，正文那句照样会被照做）。
 *
 * 不传 [EnvContext] 时报告必须一字不改：`StockAnalysisReferenceTest` 与 macOS Python
 * 参考逐位对齐，动了默认文案就会打破那条基线。
 */
class StockAnalysisEnvTest {

    private fun result(
        adx: Double? = 28.0,
        plusDi: Double? = 25.0,
        minusDi: Double? = 10.0,
    ) = TechnicalResult(
        code = "sh600089",
        name = "特变电工",
        asOf = "2026-10-12 16:05",
        rows = 240,
        price = 18.5,
        priceSource = "实时",
        priceChange = 1.2,
        opening = OpeningStructure(18.3, 18.4, 0.5, 0.5, "高开", "高开高走"),
        volumeRatio = 1.4,
        volumeRatioIntraday = true,
        priceContext = null,
        volatility = Volatility(0.4, 2.2, 18.0, 19.0, 17.0, 0.5),
        trendStrength = TrendStrength(adx, plusDi, minusDi),
        obvChange = 1_000.0,
        divergence = DivergenceResult(
            state = DivergenceState.NEUTRAL,
            forming = false,
            scoreDelta = 0,
            prior = null,
            current = null,
            obvConfirmedNewHigh = null,
            obvConfirmedNewLow = null,
            reason = "暂无背离",
            meaning = "",
            short = "",
            method = "数据足够",
        ),
        relativeStrength = 3.0,
        ma5 = 18.2,
        ma10 = 18.0,
        ma20 = 17.5,
        ma60 = 16.8,
        support = 17.0,
        resistance = 19.5,
        entrySetup = EntrySetup(
            type = EntrySetupType.NONE, confirmed = false, trendWeak = false,
            priorHigh = null, ma20SlopePct = null, volumeConfirmed = false,
            marketFilter = MarketFilter.NEUTRAL, stopPrice = 17.2, reason = "暂无有效入场结构",
        ),
        macd = MacdValues(0.1, 0.05, 0.05, 0.02),
        rsi = 55.0,
        kdj = KdjValues(60.0, 55.0, 70.0),
        chip = ChipPeaks(18.0, emptyList(), null, null, null, "较分散", "估算"),
        score = 3,
        signals = emptyList(),
        action = "技术面偏强：可继续观察量能配合。",
    )

    private fun market(regime: MarketRegime) = MarketState(
        regime = regime,
        reason = "跌破 MA20 与 MA60，近20日 -12.0%——系统性下跌，支撑位多半会一起破",
        changePct = -2.5,
        ma20Pct = -4.0,
        ma60Pct = -9.0,
        change5d = -6.0,
        change20d = -12.0,
    )

    @Test
    fun reportWithoutEnvIsUnchanged() {
        // 默认路径必须一字不改：指标数值与 macOS Python 参考逐位对齐，动了就破基线。
        val report = StockAnalysis.formatReport(result())

        // 免责句里本来就有「市场环境」四个字，这里只锁新加的那行标题（带冒号）。
        assertFalse(report.contains("市场环境："))
        assertFalse(report.contains("板块："))
        // 原文措辞保持不变。
        assertTrue(report.contains("回踩不破均线可以继续持有"))
        ReportSectionAssertions.assertEverySectionHasConclusion(report, leastSections = 10)
    }

    @Test
    fun collapseRewritesTheHoldAdviceInPlace() {
        val report = StockAnalysis.formatReport(result(), EnvContext(market(MarketRegime.COLLAPSE)))

        assertTrue(report.contains("但大盘塌陷"))
        assertTrue(report.contains("回踩不破均线也别加仓"))
        // 正文那句不能还留着——留着就会被照做。
        assertFalse(report.contains("回踩不破均线可以继续持有"))
    }

    @Test
    fun weakEnvironmentAlsoRewritesTheHoldAdvice() {
        val report = StockAnalysis.formatReport(result(), EnvContext(market(MarketRegime.WEAK)))

        assertTrue(report.contains("但大盘转弱"))
        assertFalse(report.contains("回踩不破均线可以继续持有"))
    }

    @Test
    fun collapseAlsoRewritesThePlanConclusion() {
        val report = StockAnalysis.formatReport(result(), EnvContext(market(MarketRegime.COLLAPSE)))

        // 第九节的行动计划同样不能说「回踩不破 MA20 可以继续持有」。
        assertTrue(report.contains("回踩不破 MA20 也别加仓"))
        assertFalse(report.contains("回踩不破 MA20 可以继续持有"))
    }

    @Test
    fun headerShowsMarketEnvironmentAndSector() {
        val env = EnvContext(
            market = market(MarketRegime.COLLAPSE),
            sector = SectorContext(
                industry = BoardTag("半导体", "BK0438", 1),
                changePct = -3.2,
                source = "东财F10",
            ),
        )
        val report = StockAnalysis.formatReport(result(), env)

        assertTrue(report.contains("市场环境：塌陷"))
        assertTrue(report.contains("板块：半导体"))
        assertTrue(report.contains("板块今日 -3.20%"))
    }

    @Test
    fun missingSectorIsSaidOutLoud() {
        val report = StockAnalysis.formatReport(result(), EnvContext(market(MarketRegime.NEUTRAL)))

        assertTrue(report.contains("板块归属暂缺"))
    }

    @Test
    fun headerCarriesTheRelativeSectorCallNotJustTheBoardMove() {
        // 板块自身涨跌只是背景；真正有信息量的是「个股 vs 板块」——
        // 独立行情 / 掉队 / 一起塌是三种完全不同的结论。
        val env = EnvContext(
            market = market(MarketRegime.NEUTRAL),
            sector = SectorContext(
                industry = BoardTag("半导体", "BK0438", 1),
                changePct = -3.0,
                source = "东财F10",
            ),
        )
        // 个股 +1.0%、板块 -3.0% → 差 4 个百分点，属独立行情。
        val report = StockAnalysis.formatReport(result(), env)

        assertTrue(report.contains("板块：半导体"))
        assertTrue(report.contains("独立行情"))
        assertTrue(report.contains("持续性存疑"))
    }

    @Test
    fun previousChangeNoteDescribesWhatChanged() {
        val prev = RowSnapshot(
            code = "sh600089", signal = "", score = 1,
            support = 16.5, resistance = 19.0, price = 18.0, changePct = 0.5, volumeRatio = 0.9,
        )
        val report = StockAnalysis.formatReport(
            result(),
            EnvContext(market(MarketRegime.NEUTRAL), prev = prev),
        )

        assertTrue(report.contains("较上次分析"))
        assertTrue(report.contains("评分 +1→+3"))
        assertTrue(report.contains("支撑 16.50→17.00"))
    }

    @Test
    fun previousChangeNoteMentionsMeaningfulPriceMovement() {
        val prev = RowSnapshot(
            code = "sh600089", signal = "", score = 3,
            support = 16.5, resistance = 19.0, price = 18.0, changePct = 0.5, volumeRatio = 0.9,
        )
        val report = StockAnalysis.formatReport(
            result().copy(price = 18.4, priceChange = 2.7),
            EnvContext(market(MarketRegime.NEUTRAL), prev = prev),
        )

        assertTrue(report.contains("现价 18.00→18.40"))
    }

    @Test
    fun noPreviousSnapshotSaysSoNotNoChange() {
        val report = StockAnalysis.formatReport(result(), EnvContext(market(MarketRegime.NEUTRAL)))

        // 没数据和没变化是两回事，不能硬编成「无变化」。
        assertTrue(report.contains("无上次快照"))
        assertFalse(report.contains("与上次无变化"))
    }

    @Test
    fun sectionsStillPassStructuralAssertionsWithEnv() {
        // 加了环境行不能把章节结构弄坏——报告头的行不能以中文数字开头，否则会被当成新章节。
        val report = StockAnalysis.formatReport(result(), EnvContext(market(MarketRegime.COLLAPSE)))
        ReportSectionAssertions.assertEverySectionHasConclusion(report, leastSections = 10)
        assertTrue(report.contains("十、综合结论（红色为关键数据）"))
    }

    @Test
    fun noHoldingConclusionGetsEnvCaveatWithoutTouchingTheField() {
        val technical = result()
        val withEnv = StockAnalysis.formatReport(technical, EnvContext(market(MarketRegime.COLLAPSE)))
        val withoutEnv = StockAnalysis.formatReport(technical)

        // 塌陷期「暂以观察为主」会被读成「只是没方向」，实际意思是别进场。
        assertTrue(withEnv.contains("这里的『观察』是别进场"))
        assertFalse(withoutEnv.contains("别进场"))
        // action 字段本身必须一字不动：它与 macOS Python 参考逐位对齐，改了会破基线。
        assertEquals("技术面偏强：可继续观察量能配合。", technical.action)
    }
}
