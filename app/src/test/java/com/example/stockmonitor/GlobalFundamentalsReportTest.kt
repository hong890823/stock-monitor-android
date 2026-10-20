package com.example.stockmonitor

import com.example.stockmonitor.analysis.GlobalFundamentalsReport
import com.example.stockmonitor.core.GlobalFundamentals
import com.example.stockmonitor.core.GlobalSecurity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 港美股基本面报告的结论口径锁：**结论必须带上做出判断所依据的数字**。
 *
 * 只写「当前 PE 不高」「盈利质量较好」是教科书话——PE 3 和 PE 19 读出同一句话，
 * 茅台和一家薄利公司也读出同一句话。写清「PE 12.3 倍」「毛利率 62.0%」，
 * 读者才能自己核对这个判断合不合理。
 */
class GlobalFundamentalsReportTest {

    private fun fundamentals(
        grossMargin: Double? = 0.62,
        operatingMargin: Double? = 0.35,
        profitMargin: Double? = 0.30,
        roe: Double? = 0.28,
        operatingCashFlow: Double? = 80_000_000_000.0,
        freeCashFlow: Double? = 70_000_000_000.0,
        cash: Double? = 56_000_000_000.0,
        debt: Double? = 33_000_000_000.0,
        pe: Double? = 12.3,
        pb: Double? = 4.5,
    ) = GlobalFundamentals(
        security = GlobalSecurity("SH600519", "贵州茅台", "SSE", "CNY"),
        price = 1_500.0,
        currency = "CNY",
        marketCap = 1_800_000_000_000.0,
        revenue = 150_000_000_000.0,
        grossMargin = grossMargin,
        operatingMargin = operatingMargin,
        profitMargin = profitMargin,
        roe = roe,
        operatingCashFlow = operatingCashFlow,
        freeCashFlow = freeCashFlow,
        cash = cash,
        debt = debt,
        pe = pe,
        pb = pb,
        sector = "Consumer Defensive",
        industry = "Beverages—Wineries & Distillers",
        summary = "白酒生产与销售。",
        latestPeriod = "2026-06-30",
        dataSources = listOf("SEC Company Facts"),
    )

    @Test
    fun qualityConclusionCarriesTheNumbersBehindTheCall() {
        val report = GlobalFundamentalsReport.format(fundamentals(), "2026-10-12 16:05")

        // 结论里要能看到做出判断所依据的数字，而不是只给一个定性形容词。
        assertTrue(report.contains("毛利率 62.0%"))
        assertTrue(report.contains("净利率 30.0%"))
        assertTrue(report.contains("ROE 28.0%"))
    }

    @Test
    fun valuationConclusionSaysThePeNotJustHighOrLow() {
        val report = GlobalFundamentalsReport.format(fundamentals(pe = 12.3, pb = 4.5), "2026-10-12 16:05")

        // 「当前 PE 不高」不说数字，PE 3 和 PE 19 会读出同一句话。
        assertTrue(report.contains("PE 12.3 倍"))
        assertTrue(report.contains("PB 4.5 倍"))
        assertFalse(report.contains("当前PE不高"))
    }

    @Test
    fun highPeIsSaidWithTheNumberToo() {
        val report = GlobalFundamentalsReport.format(fundamentals(pe = 88.0, pb = 30.0), "2026-10-12 16:05")

        assertTrue(report.contains("PE 88.0 倍"))
        assertTrue(report.contains("估值偏高"))
    }

    @Test
    fun solvencyConclusionShowsCashVersusDebt() {
        val report = GlobalFundamentalsReport.format(fundamentals(), "2026-10-12 16:05")

        // 现金 vs 债务是这条判断的全部依据，不写出来就无从核对。
        assertTrue(report.contains("现金覆盖债务"))
        assertTrue(report.contains("现金是债务的 1.70 倍"))
    }

    @Test
    fun profitabilityConclusionCarriesMarginNumbers() {
        val report = GlobalFundamentalsReport.format(
            fundamentals(grossMargin = 0.12, profitMargin = 0.03, roe = 0.05),
            "2026-10-12 16:05",
        )

        assertTrue(report.contains("毛利率 12.0%"))
        assertTrue(report.contains("净利率 3.0%"))
    }

    @Test
    fun missingFieldsStillSaySoRatherThanInventingACall() {
        val report = GlobalFundamentalsReport.format(
            fundamentals(pe = null, pb = null, profitMargin = null, cash = null, debt = null),
            "2026-10-12 16:05",
        )

        assertTrue(report.contains("PE/PB 暂缺"))
        // 缺数据时不给结论，而不是给一个看起来笃定的定性句。
        assertFalse(report.contains("质量较好"))
    }

    @Test
    fun pbOnlyValuationUsesPbInsteadOfFallingIntoHighPeBranch() {
        val report = GlobalFundamentalsReport.format(
            fundamentals(pe = null, pb = 2.0),
            "2026-10-12 16:05",
        )

        assertTrue(report.contains("PB 2.0 倍"))
        assertTrue(report.contains("按 PB 看不贵"))
        assertFalse(report.contains("估值偏高"))
    }

    @Test
    fun negativePeIsNotTreatedAsCheapValuation() {
        val report = GlobalFundamentalsReport.format(
            fundamentals(pe = -8.0, pb = null),
            "2026-10-12 16:05",
        )

        assertTrue(report.contains("PE/PB 暂缺"))
        assertFalse(report.contains("按 PE 看不贵"))
    }
}
