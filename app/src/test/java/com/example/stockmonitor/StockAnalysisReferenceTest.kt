package com.example.stockmonitor

import com.example.stockmonitor.analysis.StockAnalysis
import com.example.stockmonitor.analysis.DivergenceState
import com.example.stockmonitor.analysis.FundFlowTone
import com.example.stockmonitor.analysis.ReportMarkup
import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.FundFlow
import com.example.stockmonitor.core.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与 macOS 版 stock_analysis.py 的数值对齐测试。
 *
 * 参考值由 Python 版本在同一份日线数据（sh600089，2026-09-22，240 个交易日）上运行得到，
 * 用于确认 Android 版指标与评分逻辑完全一致。
 */
class StockAnalysisReferenceTest {

    private fun bars(): List<DayBar> =
        javaClass.classLoader!!.getResourceAsStream("kline_sh600089.csv")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(",")
                DayBar(
                    date = parts[0],
                    open = parts[1].toDouble(),
                    high = parts[2].toDouble(),
                    low = parts[3].toDouble(),
                    close = parts[4].toDouble(),
                    volume = parts[5].toDouble(),
                )
            }

    private fun closeTo(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertEquals(expected, actual, tolerance)
    }

    private fun liveQuote(): Quote = Quote(
        code = "sh600089",
        name = "特变电工",
        price = 18.21,
        prevClose = 18.30,
        open = 18.35,
        high = 18.38,
        low = 18.18,
        volume = 39_869_351.0,
        turnover = 728_209_800.0,
    )

    @Test
    fun fundFlowChangesScoreByAtMostOneAndReportsAssessment() {
        val quote = liveQuote().copy(price = 18.60, high = 18.62)
        val base = StockAnalysis.analyze(bars(), "sh600089", "特变电工", quote)
        val supported = StockAnalysis.analyze(
            bars(),
            "sh600089",
            "特变电工",
            quote,
            fundFlow = FundFlow(
                code = "sh600089",
                name = "特变电工",
                asOfMs = 1L,
                amountYuan = 10_000_000.0,
                mainNetPct = 5.0,
            ),
        )

        assertTrue(supported.score - base.score in 0..1)
        assertEquals(1, supported.fundFlowScoreDelta)
        assertEquals(base.score, supported.intradayScore)
        assertEquals(FundFlowTone.SUPPORTIVE, supported.fundFlowAssessment!!.tone)
        assertTrue(supported.fundFlowNote!!.contains("订单规模"))
        assertTrue(StockAnalysis.formatReport(supported).contains("资金流"))
    }

    @Test
    fun reportIncludesSharedTrendAndHighPositionState() {
        val result = StockAnalysis.analyze(bars(), "sh600089", "特变电工", liveQuote())

        assertNotNull(result.trendAssessment)
        assertNotNull(result.highPosition)
        assertNotNull(result.impulse)
        assertEquals(0, result.impulse!!.upDays)
        assertEquals(1, result.impulse!!.downDays)
        assertEquals(0.55229155, result.impulse!!.gain5Pct, 0.001)
        val report = StockAnalysis.formatReport(result)
        assertTrue(report.contains("趋势状态"))
        assertTrue(report.contains("高位结构"))
    }

    @Test
    fun `指标与评分与 Python 版一致（实时行情）`() {
        val result = StockAnalysis.analyze(bars(), "sh600089", "特变电工", liveQuote())

        assertEquals(240, result.rows)
        assertEquals("实时行情", result.priceSource)
        closeTo(18.21, result.price)
        closeTo(-0.4918032786885185, result.priceChange)

        // MACD
        closeTo(-0.5402157246040602, result.macd.dif)
        closeTo(-0.5418586960365377, result.macd.dea)
        closeTo(0.0032859428649549827, result.macd.hist)
        closeTo(-0.029116149, result.macd.previousHist, 1e-8)

        // RSI / KDJ
        closeTo(34.480026788712735, result.rsi!!)
        closeTo(24.24195626324265, result.kdj.k)
        closeTo(22.718756644781674, result.kdj.d)
        closeTo(27.28835550016459, result.kdj.j)

        // 均线与区间
        closeTo(18.133999999999997, result.ma5)
        closeTo(18.426000000000002, result.ma10)
        closeTo(18.7425, result.ma20)
        closeTo(19.918166666666668, result.ma60)
        closeTo(17.83, result.support)
        closeTo(19.43, result.resistance)

        // 量比与 OBV
        closeTo(0.7870651617639736, result.volumeRatio!!)
        closeTo(38_009_973.0, result.obvChange)

        // 波动与趋势强度
        closeTo(0.36848396932095917, result.volatility.atr)
        closeTo(2.0235253669, result.volatility.atrPct!!, 1e-6)
        closeTo(18.7425, result.volatility.bollMid)
        closeTo(19.652881788042404, result.volatility.bollUpper)
        closeTo(17.832118211957596, result.volatility.bollLower)
        closeTo(20.7540283102, result.volatility.bollPosition!!, 1e-6)
        closeTo(31.106303426069214, result.trendStrength.adx!!)
        closeTo(14.917543186801394, result.trendStrength.plusDi!!)
        closeTo(30.06921213643311, result.trendStrength.minusDi!!)
        assertNull(result.relativeStrength)

        // 开盘结构
        closeTo(18.30, result.opening.previousClose)
        closeTo(18.35, result.opening.open)
        closeTo(0.2732240437, result.opening.gapPct, 1e-6)
        closeTo(-0.7629427793, result.opening.openToCurrentPct, 1e-6)
        assertEquals("平开", result.opening.type)
        assertEquals("平开低走（现价较开盘-0.76%）", result.opening.structure)

        // 筹码峰
        assertEquals("较分散", result.chip.concentration)
        closeTo(19.988585, result.chip.chip70Lower!!, 1e-4)
        closeTo(27.711734, result.chip.chip70Upper!!, 1e-4)
        closeTo(32.381959, result.chip.chip70WidthPct!!, 1e-4)
        closeTo(24.087594, result.chip.averageCost!!, 1e-4)
        closeTo(27.7275, result.chip.peaks[0].first, 1e-4)
        closeTo(13.0623, result.chip.peaks[0].second, 1e-4)
        assertEquals("位于70%筹码区下方", result.chip.pricePosition)

        // 评分与建议（底背离 +1：价创阶段新低但 OBV 谷值未再创新低）
        assertEquals(-1, result.score)
        assertEquals("技术面中性：暂以观察为主，等待趋势和量能给出更明确方向。", result.action)
        assertEquals(
            listOf(
                "价格在MA20/MA60下方，趋势偏弱",
                "MACD DIF位于DEA上方",
                "MACD柱体较前一日改善",
                "KDJ中K位于D上方",
                "窄幅缩量，方向尚未确认",
                "ADX 31.1，趋势强度较高但空方占优",
                "量价背离：底背离（股价创新低 OBV 未再创新低）",
                "OBV近20日净变化为正，量能方向改善",
                "开盘后走弱，短线承接不足",
            ),
            result.signals,
        )

        // OBV 量价背离（与 Python 版逐字对拍）
        val divergence = result.divergence
        assertEquals(DivergenceState.BOTTOM, divergence.state)
        assertEquals(1, divergence.scoreDelta)
        closeTo(18.70, divergence.prior!!.price)
        assertEquals("2026-09-02", divergence.prior!!.date)
        closeTo(3_325_204_646.0, divergence.prior!!.obv)
        closeTo(17.83, divergence.current!!.price)
        assertEquals("2026-09-16", divergence.current!!.date)
        closeTo(3_387_797_264.0, divergence.current!!.obv)
    }

    @Test
    fun `无实时行情时使用日线收盘价`() {
        val result = StockAnalysis.analyze(bars(), "sh600089", "特变电工", null)
        assertEquals("历史日线收盘价（非实时）", result.priceSource)
        closeTo(-0.4918032786885185, result.priceChange)
        closeTo(34.480026788712735, result.rsi!!)
        // 底背离 +1
        assertEquals(-1, result.score)
    }

    @Test
    fun `报告包含全部章节且关键数值一致`() {
        val report = StockAnalysis.formatReport(StockAnalysis.analyze(bars(), "sh600089", "特变电工", liveQuote()))
        listOf(
            "一、快速概览", "二、量价关系", "三、开盘结构", "四、均线与趋势",
            "五、波动与趋势强度", "六、MACD", "七、KDJ / RSI", "八、筹码峰结构（估算）",
            "九、综合行动计划", "十、综合结论（红色为关键数据）",
        ).forEach { section -> assertTrue("缺少章节 $section", report.contains(section)) }
        assertTrue(report.contains("MA20 18.74"))
        assertTrue(report.contains("RSI 34.5"))
        assertTrue(report.contains("K 24.2"))
        // 底背离 +1 后的评分
        assertTrue(report.contains("技术评分：-1"))
        // OBV 背离段落：对照行 + 判定行（可人工核对的数字）
        assertTrue(report.contains("段内OBV峰值"))
        assertTrue(report.contains("量价背离判定：底背离"))
        assertTrue(report.contains("不构成投资建议"))
    }

    @Test
    fun `每一节都有自己的结论`() {
        val report = StockAnalysis.formatReport(StockAnalysis.analyze(bars(), "sh600089", "特变电工", liveQuote()))
        // 技术分析固定十节（一、快速概览 ~ 十、综合结论）。
        ReportSectionAssertions.assertEverySectionHasConclusion(report, leastSections = 10)
    }

    @Test
    fun `最后的综合结论把关键数据标红`() {
        val result = StockAnalysis.analyze(bars(), "sh600089", "特变电工", liveQuote())
        val report = StockAnalysis.formatReport(result)

        val conclusion = report.substringAfter("十、综合结论（红色为关键数据）")
        assertTrue("结论里要有标红的关键数据", conclusion.contains(ReportMarkup.OPEN))
        assertTrue("支撑位要标红", conclusion.contains(ReportMarkup.red(StockAnalysis.price(result.support))))
        assertTrue("压力位要标红", conclusion.contains(ReportMarkup.red(StockAnalysis.price(result.resistance))))
        // 复制/分享出去的是去掉标记的纯文本。
        assertFalse(ReportMarkup.plain(conclusion).contains(ReportMarkup.OPEN))
    }
}
