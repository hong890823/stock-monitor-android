package com.example.stockmonitor

import com.example.stockmonitor.analysis.Announcement
import com.example.stockmonitor.analysis.BalanceSheetSnapshot
import com.example.stockmonitor.analysis.FinancialPeriod
import com.example.stockmonitor.analysis.Fundamentals
import com.example.stockmonitor.analysis.FundamentalsReport
import com.example.stockmonitor.analysis.Governance
import com.example.stockmonitor.analysis.MainBusinessItem
import com.example.stockmonitor.analysis.RiskLevel
import com.example.stockmonitor.analysis.ReportMarkup
import com.example.stockmonitor.analysis.TechnicalReference
import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.MarketFilter
import com.example.stockmonitor.analysis.ValuationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基本面计算的三条硬规则，任何一条错都会给出反向结论：
 * 累计值必须差分出单季、比率分子分母必须同区间、没有意义的估值锚宁可不给。
 */
class FundamentalsTest {

    private fun p(
        date: String,
        revenue: Double,
        cost: Double = revenue * 0.6,
        net: Double = revenue * 0.1,
        deduct: Double = net,
        ocf: Double = net,
        sales: Double = revenue,
        capex: Double = 0.0,
    ) = FinancialPeriod(date, revenue, cost, net, deduct, ocf, sales, capex)

    private fun sheet(
        totalAssets: Double = 1000.0,
        monetaryFunds: Double = 100.0,
        shortLoan: Double = 0.0,
        longLoan: Double = 0.0,
        goodwill: Double = 0.0,
        otherReceivable: Double = 0.0,
        parentEquity: Double = 500.0,
    ) = BalanceSheetSnapshot(
        date = "2026-06-30",
        monetaryFunds = monetaryFunds,
        accountsReceivable = 100.0,
        otherReceivable = otherReceivable,
        inventory = 100.0,
        goodwill = goodwill,
        shortLoan = shortLoan,
        longLoan = longLoan,
        bondPayable = 0.0,
        leaseLiability = 0.0,
        totalAssets = totalAssets,
        totalLiabilities = totalAssets - parentEquity,
        parentEquity = parentEquity,
        minorityEquity = 0.0,
    )

    private fun valuations(pe: List<Double>, pb: List<Double> = emptyList()): List<ValuationPoint> =
        pe.mapIndexed { index, value ->
            ValuationPoint(
                date = String.format("2025-%02d-%02d", index / 28 + 1, index % 28 + 1),
                peTtm = value,
                pbMrq = pb.getOrNull(index),
            )
        }

    private fun input(
        periods: List<FinancialPeriod>,
        balance: BalanceSheetSnapshot? = sheet(),
        valuations: List<ValuationPoint> = emptyList(),
        peers: com.example.stockmonitor.analysis.PeerContext? = null,
        mainBusiness: List<MainBusinessItem> = emptyList(),
        announcements: List<Announcement> = emptyList(),
        today: String = "2026-09-24",
        price: Double = 20.0,
        shares: Double = 100.0,
    ) = Fundamentals.Input(
        periods = periods,
        balance = balance,
        valuations = valuations,
        totalShares = shares,
        price = price,
        peers = peers,
        mainBusiness = mainBusiness,
        announcements = announcements,
        today = today,
    )

    // ── 单季差分 ─────────────────────────────────────────────────────────────

    @Test
    fun singleQuarterIsDifferenceOfCumulativeWithinTheSameYear() {
        val quarters = Fundamentals.quarters(
            listOf(
                p("2025-03-31", 100.0),
                p("2025-06-30", 220.0),
                p("2025-09-30", 330.0),
                p("2025-12-31", 460.0),
                p("2026-03-31", 130.0),
            )
        )

        assertEquals(5, quarters.size)
        assertEquals(100.0, quarters[0].revenue, 1e-6)
        assertEquals(120.0, quarters[1].revenue, 1e-6)
        assertEquals(110.0, quarters[2].revenue, 1e-6)
        assertEquals(130.0, quarters[3].revenue, 1e-6)
        assertEquals("跨年不清零：一季报本身就是单季", 130.0, quarters[4].revenue, 1e-6)
        assertEquals("26Q1", quarters[4].label)
        assertEquals(30.0, quarters[4].revenueYoy!!, 1e-4)
    }

    @Test
    fun missingQuarterIsTreatedAsAFreshSegmentInsteadOfSubtracting() {
        val quarters = Fundamentals.quarters(
            listOf(
                p("2026-03-31", 100.0),
                p("2026-09-30", 300.0),
            )
        )

        assertEquals(100.0, quarters[0].revenue, 1e-6)
        assertEquals("中间缺中报时不能拿 Q1 去减，宁可当成一整段", 300.0, quarters[1].revenue, 1e-6)
    }

    @Test
    fun netProfitYoyIsSkippedWhenTheBaseQuarterWasLosingMoney() {
        val quarters = Fundamentals.quarters(
            listOf(
                p("2025-06-30", 100.0, net = -5.0),
                p("2025-12-31", 200.0, net = -10.0),
                p("2026-06-30", 150.0, net = 20.0),
                p("2026-12-31", 260.0, net = 30.0),
            )
        )

        assertNull(quarters[2].netProfitYoy)
        assertEquals(50.0, quarters[2].revenueYoy!!, 1e-4)
    }

    // ── TTM ──────────────────────────────────────────────────────────────────

    @Test
    fun ttmSumsTheLastFourSingleQuartersNotTheLastFourReportedValues() {
        val periods = listOf(
            p("2025-03-31", 100.0),
            p("2025-06-30", 240.0),
            p("2025-09-30", 400.0),
            p("2025-12-31", 560.0),
            p("2026-03-31", 200.0),
        )

        // 单季：100 / 140 / 160 / 160 / 200 → 最近四季合计 660。
        // 只截最后 4 期再差分会把 2025 中报的 240 当成单季，算出 760，虚高 15%。
        val ttm = Fundamentals.ttmOf(periods)!!
        assertEquals(660.0, ttm.revenue, 1e-6)
        assertEquals("2026-03-31", ttm.date)
    }

    @Test
    fun ttmAtYearEndEqualsTheAnnualCumulativeFigure() {
        val periods = listOf(
            p("2025-03-31", 100.0),
            p("2025-06-30", 240.0),
            p("2025-09-30", 400.0),
            p("2025-12-31", 560.0),
        )

        assertEquals(560.0, Fundamentals.ttmOf(periods)!!.revenue, 1e-6)
    }

    // ── 比率 ─────────────────────────────────────────────────────────────────

    @Test
    fun ratiosUseTtmNumbersForBothNumeratorAndDenominator() {
        val periods = listOf(
            p("2025-03-31", 100.0, cost = 60.0, net = 10.0),
            p("2025-06-30", 220.0, cost = 132.0, net = 22.0),
            p("2025-09-30", 330.0, cost = 198.0, net = 33.0),
            p("2025-12-31", 460.0, cost = 276.0, net = 46.0),
            p("2026-03-31", 130.0, cost = 78.0, net = 13.0),
            p("2026-06-30", 280.0, cost = 168.0, net = 28.0),
            p("2026-09-30", 430.0, cost = 258.0, net = 43.0),
            p("2026-12-31", 600.0, cost = 360.0, net = 60.0),
        )

        val result = Fundamentals.analyze(input(periods))

        // 最近四季单季营收 130 / 150 / 150 / 170，合计 600，成本 360。
        assertEquals(600.0, Fundamentals.ttmOf(periods)!!.revenue, 1e-6)
        assertEquals(40.0, result.grossMargin!!, 1e-3)
        assertEquals(10.0, result.netMargin!!, 1e-3)
        assertEquals(1.0, result.ocfToProfit!!, 1e-6)
        assertEquals(1.0, result.cashToRevenue!!, 1e-6)
        assertEquals(1.0, result.deductRatio!!, 1e-6)
    }

    @Test
    fun deductRatioBelowThresholdIsFlaggedAsAnAlert() {
        val periods = listOf(
            p("2026-03-31", 100.0, net = 20.0, deduct = 4.0),
            p("2026-06-30", 200.0, net = 40.0, deduct = 8.0),
        )

        val result = Fundamentals.analyze(input(periods, valuations = emptyList()))

        assertEquals(0.2, result.deductRatio!!, 1e-6)
        assertTrue(result.risks.any { it.level == RiskLevel.ALERT && it.text.contains("非经常性损益") })
        assertTrue("扣非口径要单独提醒 PE 变贵", result.notes.any { it.contains("扣非算 EPS-TTM") })
    }

    // ── 风险扫描 ─────────────────────────────────────────────────────────────

    @Test
    fun goodwillOverThirtyPercentOfEquityIsAnAlert() {
        val result = Fundamentals.analyze(input(listOf(p("2026-06-30", 100.0)), balance = sheet(goodwill = 200.0)))

        assertTrue(result.risks.any { it.level == RiskLevel.ALERT && it.text.contains("商誉占净资产") })
    }

    @Test
    fun highCashAndHighDebtAtTheSameTimeIsAnAlert() {
        val result = Fundamentals.analyze(
            input(
                listOf(p("2026-06-30", 100.0)),
                balance = sheet(monetaryFunds = 300.0, shortLoan = 250.0),
            )
        )

        assertTrue(result.risks.any { it.level == RiskLevel.ALERT && it.text.contains("存贷双高") })
    }

    @Test
    fun singleProductDependencyIsFlagged() {
        val items = listOf(
            MainBusinessItem("2026-06-30", 2, "集成电路", 96.0, 0.96, 0.14),
            MainBusinessItem("2026-06-30", 2, "LED", 4.0, 0.04, 0.05),
        )

        val result = Fundamentals.analyze(input(listOf(p("2026-06-30", 100.0)), mainBusiness = items))

        assertTrue(result.risks.any { it.text.contains("单一业务依赖度高") })
    }

    @Test
    fun lossMakingSegmentIsFlaggedButRegionalSplitIsNotConcentration() {
        val items = listOf(
            MainBusinessItem("2026-06-30", 2, "集成电路", 85.0, 0.85, 0.14),
            MainBusinessItem("2026-06-30", 2, "LED", 15.0, 0.15, -7.2),
            MainBusinessItem("2026-06-30", 3, "国内", 99.0, 0.99, 0.14),
            MainBusinessItem("2026-06-30", 3, "国外", 1.0, 0.01, 0.13),
        )

        val result = Fundamentals.analyze(input(listOf(p("2026-06-30", 100.0)), mainBusiness = items))

        assertTrue(result.risks.any { it.text.contains("毛利率为负") })
        assertTrue(
            "地区口径的「国内占 99%」是销售地分布，不是业务集中",
            result.risks.none { it.text.contains("单一业务依赖度高") },
        )
        assertEquals(listOf("集成电路", "LED"), result.mainBusiness.map { it.name })
    }

    @Test
    fun mainBusinessKeepsOnlyTheLatestPeriodAndSortsByIncome() {
        val items = listOf(
            MainBusinessItem("2025-12-31", 2, "老产品", 100.0, 1.0, 0.1),
            MainBusinessItem("2026-06-30", 2, "小产品", 10.0, 0.1, 0.1),
            MainBusinessItem("2026-06-30", 2, "大产品", 90.0, 0.9, 0.2),
            MainBusinessItem("2026-06-30", 3, "华东", 50.0, 0.5, 0.2),
        )

        val picked = Fundamentals.mainBusinessOf(items)

        assertEquals(listOf("大产品", "小产品"), picked.map { it.name })
    }

    // ── 估值 ─────────────────────────────────────────────────────────────────

    @Test
    fun lossMakingCompanyGetsNoPeAnchor() {
        val periods = listOf(
            p("2025-06-30", 400.0, net = -20.0),
            p("2025-12-31", 800.0, net = -50.0),
            p("2026-06-30", 400.0, net = -30.0),
            p("2026-12-31", 900.0, net = -10.0),
        )
        val history = valuations((1..30).map { 10.0 + it })

        val result = Fundamentals.analyze(input(periods, valuations = history))

        assertTrue(result.eps!! < 0)
        assertTrue(result.anchors.none { it.name.contains("PE") })
        assertTrue(result.notes.any { it.contains("PE 类估值不适用") })
    }

    @Test
    fun lowPePercentileWithSurgingProfitWarnsAboutTheCycleTrap() {
        val periods = listOf(
            p("2025-03-31", 100.0, net = 10.0),
            p("2025-06-30", 220.0, net = 22.0),
            p("2025-09-30", 330.0, net = 33.0),
            p("2025-12-31", 460.0, net = 46.0),
            p("2026-03-31", 160.0, net = 26.0),
            p("2026-06-30", 340.0, net = 52.0),
            p("2026-09-30", 520.0, net = 80.0),
            p("2026-12-31", 700.0, net = 110.0),
        )
        // 历史 PE 从 59 一路降到 30，当前 30 落在最低的一档。
        val history = valuations((1..30).map { 60.0 - it })

        val result = Fundamentals.analyze(input(periods, valuations = history, price = 20.0))

        assertTrue(result.pePercentile!! <= 30)
        assertTrue(result.notes.any { it.contains("低 PE 可能是盈利高点撑出来的") })
    }

    @Test
    fun percentileInterpolatesAndIgnoresNonPositiveValues() {
        val values = listOf(1.0, -5.0, 2.0, 3.0, 4.0, 5.0, 0.0)

        assertEquals(3.0, Fundamentals.percentile(values, 0.5)!!, 1e-9)
        assertEquals(1.5, Fundamentals.percentile(values, 0.125)!!, 1e-9)
        assertEquals(5.0, Fundamentals.percentile(values, 1.0)!!, 1e-9)
        assertNull(Fundamentals.percentile(listOf(-1.0, -2.0), 0.5))
    }

    @Test
    fun fairValueRangeComesFromHistoricalPercentileOfPeTimesEps() {
        val periods = listOf(
            p("2025-03-31", 100.0, net = 10.0),
            p("2025-06-30", 220.0, net = 22.0),
            p("2025-09-30", 330.0, net = 33.0),
            p("2025-12-31", 460.0, net = 46.0),
            p("2026-03-31", 130.0, net = 13.0),
            p("2026-06-30", 280.0, net = 28.0),
            p("2026-09-30", 430.0, net = 43.0),
            p("2026-12-31", 600.0, net = 60.0),
        )
        // 最近四季单季归母 13 / 15 / 15 / 17，合计 60，股本 100 → EPS-TTM = 0.6 元。
        // 30 个交易日，PE 从 11 到 40 → 30% 分位 19.7、60% 分位 28.4。
        val history = valuations((1..30).map { 10.0 + it })

        val result = Fundamentals.analyze(input(periods, valuations = history, price = 20.0, shares = 100.0))

        assertEquals(0.6, result.eps!!, 1e-9)
        assertEquals(19.7 * 0.6, result.fairLow!!, 1e-6)
        assertEquals(28.4 * 0.6, result.fairHigh!!, 1e-6)
    }

    // ── 公告（治理风险的数据源） ────────────────────────────────────────────────

    @Test
    fun governanceScanClassifiesAnnouncementsByTitle() {
        val items = listOf(
            Announcement("2026-09-01", "华天科技:关于股东股份质押的公告"),
            Announcement("2026-08-20", "关于收到中国证监会立案告知书的公告"),
            Announcement("2026-07-10", "关于股东部分股份解除质押的公告"),
            Announcement("2026-06-30", "2026年半年度报告"),
            Announcement("2026-06-20", "第八届董事会第十八次会议决议公告"),
            Announcement("2026-06-10", "非经营性资金占用及其他关联资金往来情况汇总表"),
        )

        val scanned = Governance.scan(items, "2026-09-24")

        assertEquals(3, scanned.size)
        assertEquals(RiskLevel.ALERT, scanned[0].level)
        assertTrue(scanned[0].title.contains("立案"))
        assertTrue(scanned.any { it.level == RiskLevel.GOOD && it.reason == "质押解除" })
        assertTrue(scanned.any { it.level == RiskLevel.WATCH && it.reason == "股东质押" })
    }

    @Test
    fun governanceScanIgnoresAnnouncementsOlderThanTheWindow() {
        val items = listOf(
            Announcement("2024-01-05", "关于收到行政处罚决定书的公告"),
            Announcement("2026-09-01", "关于股东减持股份的公告"),
        )

        val scanned = Governance.scan(items, "2026-09-24")

        assertEquals(1, scanned.size)
        assertEquals(RiskLevel.WATCH, scanned[0].level)
    }

    @Test
    fun governanceScanIncludesPrivatePlacementAnnouncements() {
        val items = listOf(
            Announcement(
                "2026-09-20",
                "关于向特定对象发行股票申请获得上海证券交易所受理的公告",
                content = "<p>本次发行尚需上海证券交易所审核通过及中国证监会注册，募集资金用于项目建设。</p>",
            ),
            Announcement("2026-09-18", "关于2026年度定增预案的公告"),
        )

        val scanned = Governance.scan(items, "2026-09-24")

        assertEquals(1, scanned.size)
        assertEquals(RiskLevel.WATCH, scanned.single().level)
        assertEquals("定增/再融资事项", scanned.single().reason)
        assertTrue(scanned.single().title.contains("向特定对象发行"))
        assertTrue(scanned.single().assessment!!.contains("审核/注册"))
    }

    @Test
    fun governanceAlertIsPromotedIntoTheRiskList() {
        val announcements = listOf(Announcement("2026-08-20", "关于收到中国证监会立案告知书的公告"))

        val result = Fundamentals.analyze(input(listOf(p("2026-06-30", 100.0)), announcements = announcements))

        assertTrue(result.risks.any { it.level == RiskLevel.ALERT && it.text.contains("公告提示") })
        assertTrue(result.announcementsScanned == 1)
    }

    @Test
    fun peerMultiplesDoNotOverrideTheStocksOwnHistory() {
        val periods = listOf(
            p("2025-03-31", 100.0, net = 10.0),
            p("2025-06-30", 220.0, net = 22.0),
            p("2025-09-30", 330.0, net = 33.0),
            p("2025-12-31", 460.0, net = 46.0),
            p("2026-03-31", 130.0, net = 13.0),
            p("2026-06-30", 280.0, net = 28.0),
            p("2026-09-30", 430.0, net = 43.0),
            p("2026-12-31", 600.0, net = 60.0),
        )
        val history = valuations((1..30).map { 10.0 + it })
        // 半导体板块 2026 年的 PE 中位数就是 100 倍这种量级，按它算这只票「很便宜」是反向结论。
        val peers = com.example.stockmonitor.analysis.PeerContext(
            boardName = "半导体",
            total = 186,
            sampleSize = 141,
            marketCapRank = 47,
            peMedian = 101.3,
            pbMedian = 6.31,
            marketCap = 61_100_000_000.0,
        )

        val result = Fundamentals.analyze(
            input(periods, valuations = history, peers = peers, price = 17.72, shares = 100.0)
        )

        // 自身 PE 分位 + 同业 PE + 同业 PB（测试里没给 PB 历史，所以没有自身 PB 分位这一档）。
        assertEquals(3, result.anchors.size)
        assertEquals("合理区间只取自身历史口径", 19.7 * 0.6, result.fairLow!!, 1e-6)
        assertEquals(28.4 * 0.6, result.fairHigh!!, 1e-6)
        assertTrue(result.notes.any { it.contains("同业口径的中枢") })
    }

    @Test
    fun valuationReportSeparatesOwnHistoryFromPeerComparison() {
        val periods = listOf(
            p("2025-03-31", 100.0, net = 10.0),
            p("2025-06-30", 220.0, net = 22.0),
            p("2025-09-30", 330.0, net = 33.0),
            p("2025-12-31", 460.0, net = 46.0),
            p("2026-03-31", 130.0, net = 13.0),
            p("2026-06-30", 280.0, net = 28.0),
        )
        val result = Fundamentals.analyze(
            input(
                periods = periods,
                valuations = valuations((1..30).map { 10.0 + it }),
                peers = com.example.stockmonitor.analysis.PeerContext(
                    boardName = "通信设备",
                    total = 61,
                    sampleSize = 61,
                    marketCapRank = 8,
                    peMedian = 93.97,
                    pbMedian = 4.69,
                    marketCap = 80_000_000_000.0,
                ),
                price = 20.0,
                shares = 100.0,
            ),
        )

        val report = FundamentalsReport.format("测试股", "sh600000", 20.0, "2026-09-28 12:00", result)

        assertTrue(report.contains("相对自身历史"))
        assertTrue(report.contains("相对行业可比"))
        assertTrue(report.contains("以自身历史估值为主"))
        assertTrue(report.contains("行业估值只作对照"))
    }

    @Test
    fun valuationReportShowsCurrentNextQuarterAndForwardLayers() {
        val result = Fundamentals.analyze(
            input(
                periods = listOf(
                    p("2025-03-31", 100.0, net = 10.0),
                    p("2025-06-30", 220.0, net = 22.0),
                    p("2025-09-30", 330.0, net = 33.0),
                    p("2025-12-31", 460.0, net = 46.0),
                    p("2026-03-31", 130.0, net = 15.0),
                    p("2026-06-30", 280.0, net = 34.0),
                ),
                valuations = valuations((1..30).map { 10.0 + it }),
            ),
        )

        val section = FundamentalsReport.format("测试股", "sh600000", 20.0, "2026-09-28 12:00", result)
            .substringAfter("十、合理估值")
            .substringBefore("十一、风险清单")

        assertTrue(section.contains("结论："))
        assertTrue(section.contains("未来中性区间"))
        assertTrue(section.contains("现状估值："))
        assertTrue(section.contains("下一财报期："))
        assertTrue(section.contains("历史同比外推"))
        assertTrue(section.contains("不等同业绩预告"))
        assertTrue(section.contains("未来预期："))
        assertTrue(section.contains("不是目标价"))
        assertTrue(FundamentalsReport.futureValuationSummary(result).contains("未来 6~9 个月"))
    }

    @Test
    fun valuationReportShowsTheOverlapBetweenFundamentalAndTechnicalRanges() {
        val result = Fundamentals.analyze(
            input(
                periods = listOf(
                    p("2025-03-31", 100.0, net = 10.0),
                    p("2025-06-30", 220.0, net = 22.0),
                    p("2025-09-30", 330.0, net = 33.0),
                    p("2025-12-31", 460.0, net = 46.0),
                    p("2026-03-31", 130.0, net = 15.0),
                    p("2026-06-30", 280.0, net = 34.0),
                ),
                valuations = valuations((1..30).map { 10.0 + it }),
            ),
        )
        val report = FundamentalsReport.format(
            "测试股",
            "sh600000",
            20.0,
            "2026-09-29 12:00",
            result,
            TechnicalReference(1.0, 100.0),
        )

        assertTrue(report.contains("技术参考区间：1.00 ~ 100.00 元"))
        assertTrue(report.contains("综合判断："))
        assertTrue(report.contains("交集"))
        assertTrue(report.contains("两者方向较一致"))
        assertTrue(report.substringAfter("十三、综合结论").contains("技术参考"))
    }

    @Test
    fun valuationReportExplainsFundamentalAndTechnicalTimingTogether() {
        val result = Fundamentals.analyze(
            input(
                periods = listOf(
                    p("2025-03-31", 100.0, net = 10.0),
                    p("2025-06-30", 220.0, net = 22.0),
                    p("2025-09-30", 330.0, net = 33.0),
                    p("2025-12-31", 460.0, net = 46.0),
                    p("2026-03-31", 130.0, net = 15.0),
                    p("2026-06-30", 280.0, net = 34.0),
                ),
                valuations = valuations((1..30).map { 10.0 + it }),
            ),
        )
        val setup = EntrySetup(
            type = EntrySetupType.BREAKOUT,
            confirmed = true,
            priorHigh = 18.0,
            ma20SlopePct = 1.0,
            volumeConfirmed = true,
            marketFilter = MarketFilter.UP,
            stopPrice = 16.0,
            reason = "站上前高，放量，M20向上",
        )
        val report = FundamentalsReport.format(
            "测试股",
            "sh600000",
            20.0,
            "2026-09-29 12:00",
            result,
            TechnicalReference(1.0, 100.0, setup),
        )

        assertTrue(report.contains("技术择时："))
        assertTrue(report.contains("综合判断："))
    }

    @Test
    fun valuationReportDoesNotInventACombinedRangeWhenFundamentalAndTechnicalConflict() {
        val result = Fundamentals.analyze(
            input(
                periods = listOf(
                    p("2025-03-31", 100.0, net = 10.0),
                    p("2025-06-30", 220.0, net = 22.0),
                    p("2025-09-30", 330.0, net = 33.0),
                    p("2025-12-31", 460.0, net = 46.0),
                    p("2026-03-31", 130.0, net = 15.0),
                    p("2026-06-30", 280.0, net = 34.0),
                ),
                valuations = valuations((1..30).map { 10.0 + it }),
            ),
        )
        val report = FundamentalsReport.format(
            "测试股",
            "sh600000",
            20.0,
            "2026-09-29 12:00",
            result,
            TechnicalReference(1.0, 5.0),
        )

        assertTrue(report.contains("两者无交集"))
        assertTrue(report.contains("基本面与技术面存在分歧"))
        assertFalse(report.contains("综合区间：1.00 ~ 5.00 元"))
    }

    @Test
    fun valuationReportKeepsFundamentalAnalysisWhenTechnicalRangeIsUnavailable() {
        val result = Fundamentals.analyze(
            input(
                periods = listOf(
                    p("2025-03-31", 100.0, net = 10.0),
                    p("2025-06-30", 220.0, net = 22.0),
                    p("2025-09-30", 330.0, net = 33.0),
                    p("2025-12-31", 460.0, net = 46.0),
                    p("2026-03-31", 130.0, net = 15.0),
                    p("2026-06-30", 280.0, net = 34.0),
                ),
                valuations = valuations((1..30).map { 10.0 + it }),
            ),
        )
        val report = FundamentalsReport.format(
            "测试股",
            "sh600000",
            20.0,
            "2026-09-29 12:00",
            result,
            technical = null,
        )

        assertTrue(report.contains("合理估值："))
        assertTrue(report.contains("技术参考区间暂缺"))
        assertTrue(report.contains("基本面区间仍可单独参考"))
    }

    @Test
    fun stallingProfitIsCalledOutBecauseLowPercentileCanBeATrap() {
        val periods = listOf(
            p("2025-03-31", 100.0, net = 20.0),
            p("2025-06-30", 220.0, net = 44.0),
            p("2025-09-30", 330.0, net = 66.0),
            p("2025-12-31", 460.0, net = 96.0),
            p("2026-03-31", 130.0, net = 18.0),
            p("2026-06-30", 250.0, net = 36.0),
            p("2026-09-30", 380.0, net = 54.0),
            p("2026-12-31", 500.0, net = 72.0),
        )
        val history = valuations((1..30).map { 60.0 - it })

        val result = Fundamentals.analyze(input(periods, valuations = history))

        assertTrue(result.pePercentile!! <= 30)
        assertTrue(result.notes.any { it.contains("盈利在往下走") })
    }

    @Test
    fun shortHistoryStillProducesAReportWithoutFakeNumbers() {
        val periods = listOf(p("2026-06-30", 100.0, net = 10.0, deduct = 9.0))

        val result = Fundamentals.analyze(input(periods, valuations = emptyList()))

        assertEquals(1, result.quarters.size)
        assertNull(result.fairLow)
        assertNull(result.fairHigh)
        assertTrue(result.notes.any { it.contains("可用单季只有 1 个") })
    }

    // ── 档位口径：卡片着色与风险清单必须是同一套阈值 ──────────────────────────

    @Test
    fun cardBandsAndRiskListShareTheSameThresholds() {
        // 0.90 的扣非占比够不上风险清单的下限（0.85），卡片刻度也不能把它标成需要留意。
        assertNull(Fundamentals.Bands.deductRatio(0.90))
        assertNull(Fundamentals.Bands.cashToRevenue(1.05))
        assertNull(Fundamentals.Bands.ocfToProfit(0.90))
        assertEquals(RiskLevel.WATCH, Fundamentals.Bands.cashToRevenue(0.87))
        assertEquals(RiskLevel.WATCH, Fundamentals.Bands.cashToRevenue(0.95))
        assertEquals(RiskLevel.ALERT, Fundamentals.Bands.deductRatio(0.50))

        val periods = listOf(
            p("2026-03-31", 100.0, net = 20.0, deduct = 18.0),
            p("2026-06-30", 200.0, net = 40.0, deduct = 36.0),
        )

        val result = Fundamentals.analyze(input(periods, valuations = emptyList()))

        assertEquals(0.90, result.deductRatio!!, 1e-6)
        assertTrue("扣非占 90% 不该出现在风险清单里", result.risks.none { it.text.contains("扣非") })
    }

    @Test
    fun weakRoeIsFlaggedAndHealthyRoeIsNot() {
        val weak = Fundamentals.analyze(
            input(
                listOf(p("2026-06-30", 1000.0, net = 10.0)),
                balance = sheet(parentEquity = 500.0),
                valuations = emptyList(),
            )
        )
        assertTrue(weak.risks.any { it.level == RiskLevel.WATCH && it.text.contains("ROE") })

        val healthy = Fundamentals.analyze(
            input(
                listOf(p("2026-06-30", 1000.0, net = 100.0)),
                balance = sheet(parentEquity = 500.0),
                valuations = emptyList(),
            )
        )
        assertTrue(healthy.risks.none { it.text.contains("ROE") })
    }

    // ── 公告覆盖：窗口是半年，但接口能取多少要如实交代 ────────────────────────

    @Test
    fun repeatedAnnouncementReasonsCollapseIntoOneEntry() {
        val items = listOf(
            Announcement("2026-09-01", "关于重大资产重组的进展公告"),
            Announcement("2026-08-01", "关于重大资产重组的公告"),
            Announcement("2026-07-01", "关于重大资产重组事项的公告"),
        )

        val hits = Governance.scan(items, referenceDate = "2026-09-24")

        assertEquals(1, hits.size)
        assertEquals(3, hits[0].count)
        assertEquals("2026-09-01", hits[0].date)
    }

    @Test
    fun announcementCoverageIsExposedSoTheWindowIsHonest() {
        val items = listOf(
            Announcement("2026-09-10", "关于股东减持股份的公告"),
            Announcement("2026-07-01", "2026年半年度报告"),
        )

        val result = Fundamentals.analyze(
            input(listOf(p("2026-06-30", 100.0)), valuations = emptyList(), announcements = items)
        )

        assertEquals("2026-07-01", result.announcementFrom)
        assertEquals("2026-09-10", result.announcementTo)
    }

    @Test
    fun peTextExplainsThatLossMakingCompaniesHaveNoPe() {
        assertEquals("亏损（PE 不适用）", FundamentalsReport.peText(-12.3))
        assertEquals("18.37 倍", FundamentalsReport.peText(18.37))
        assertEquals("—", FundamentalsReport.peText(null))
    }

    // ── 报告末节的综合结论 ────────────────────────────────────────────────────

    @Test
    fun reportEndsWithAConclusionThatHighlightsKeyNumbers() {

        val periods = listOf(
            p("2025-03-31", 100.0, net = 10.0, deduct = 9.0),
            p("2025-06-30", 220.0, net = 22.0, deduct = 20.0),
            p("2025-09-30", 330.0, net = 33.0, deduct = 30.0),
            p("2025-12-31", 460.0, net = 46.0, deduct = 42.0),
            p("2026-03-31", 130.0, net = 13.0, deduct = 11.0),
            p("2026-06-30", 280.0, net = 28.0, deduct = 24.0),
        )
        val result = Fundamentals.analyze(
            input(
                periods,
                valuations = valuations((1..30).map { 10.0 + it }),
                price = 20.0,
                shares = 100.0,
            )
        )

        val report = FundamentalsReport.format("测试股", "sh600000", 20.0, "2026-09-24 12:00", result)

        assertTrue("综合结论要放在报告最后", report.indexOf("十三、综合结论") > report.indexOf("十二、口径提醒"))
        val conclusion = report.substringAfter("十三、综合结论")
        assertTrue("结论里要有标红的关键数据", conclusion.contains(ReportMarkup.OPEN))
        assertTrue("评分要标红", conclusion.contains(ReportMarkup.red("${result.score}/100")))
        assertTrue(
            "合理估值区间要标红",
            conclusion.contains(ReportMarkup.red("${FundamentalsReport.money(result.fairLow!!)} ~ ${FundamentalsReport.money(result.fairHigh!!)} 元")),
        )
        assertFalse("复制出去不能留标记", ReportMarkup.plain(conclusion).contains(ReportMarkup.OPEN))
    }

    @Test
    fun reportGivesEverySectionItsOwnConclusion() {
        val periods = listOf(
            p("2025-03-31", 100.0, net = 10.0, deduct = 9.0),
            p("2025-06-30", 220.0, net = 22.0, deduct = 20.0),
            p("2025-09-30", 330.0, net = 33.0, deduct = 30.0),
            p("2025-12-31", 460.0, net = 46.0, deduct = 42.0),
            p("2026-03-31", 130.0, net = 13.0, deduct = 11.0),
            p("2026-06-30", 280.0, net = 28.0, deduct = 24.0),
        )
        val result = Fundamentals.analyze(
            input(
                periods,
                valuations = valuations((1..30).map { 10.0 + it }),
                price = 20.0,
                shares = 100.0,
            )
        )
        val report = FundamentalsReport.format("测试股", "sh600000", 20.0, "2026-09-24 12:00", result)
        // 基本面固定十三节；「十二、口径提醒」只在真的有口径问题时才出现，所以只要求不少于十二节。
        ReportSectionAssertions.assertEverySectionHasConclusion(report, leastSections = 12)
    }

    @Test
    fun governanceSectionShowsJudgementInsteadOfAnnouncementBodySummary() {
        val result = Fundamentals.analyze(
            input(
                listOf(p("2026-06-30", 100.0)),
                announcements = listOf(
                    Announcement(
                        "2026-09-20",
                        "关于向特定对象发行股票申请获得上海证券交易所受理的公告",
                        content = "<p>本次发行尚需上海证券交易所审核通过及中国证监会注册，募集资金用于项目建设。</p>",
                    ),
                ),
            ),
        )

        val section = FundamentalsReport.format("测试股", "sh600000", 20.0, "2026-09-24 12:00", result)
            .substringAfter("六、公告与治理提示")
            .substringBefore("七、")

        assertFalse(section.contains("内容摘要"))
        assertFalse(section.contains("本次发行尚需上海证券交易所审核通过及中国证监会注册"))
        assertTrue(section.contains("中性偏积极"))
        assertTrue(section.contains("审核/注册"))

        val conclusion = FundamentalsReport.format("测试股", "sh600000", 20.0, "2026-09-24 12:00", result)
            .substringAfter("十三、综合结论")
        assertTrue(conclusion.contains("公告影响："))
        assertTrue(conclusion.contains("估值判断："))
    }
}
