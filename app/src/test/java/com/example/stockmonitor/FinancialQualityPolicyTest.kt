package com.example.stockmonitor

import com.example.stockmonitor.analysis.Announcement
import com.example.stockmonitor.analysis.BalanceSheetSnapshot
import com.example.stockmonitor.analysis.FinancialPeriod
import com.example.stockmonitor.analysis.FinancialQualityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialQualityPolicyTest {

    private fun period(
        date: String,
        revenue: Double = 100.0,
        netProfit: Double = 20.0,
        operatingCashFlow: Double? = 20.0,
    ) = FinancialPeriod(
        date = date,
        revenue = revenue,
        operateCost = revenue * 0.6,
        netProfit = netProfit,
        deductNetProfit = netProfit,
        operatingCashFlow = operatingCashFlow,
        salesCash = revenue,
    )

    private fun balance(
        date: String,
        totalAssets: Double = 1_000.0,
        totalLiabilities: Double = 500.0,
        accountsReceivable: Double = 100.0,
        inventory: Double = 100.0,
        contractLiabilities: Double? = null,
    ) = BalanceSheetSnapshot(
        date = date,
        monetaryFunds = 100.0,
        accountsReceivable = accountsReceivable,
        otherReceivable = 0.0,
        inventory = inventory,
        goodwill = 0.0,
        shortLoan = 0.0,
        longLoan = 0.0,
        bondPayable = 0.0,
        leaseLiability = 0.0,
        totalAssets = totalAssets,
        totalLiabilities = totalLiabilities,
        parentEquity = 500.0,
        minorityEquity = 0.0,
        contractLiabilities = contractLiabilities,
    )

    @Test
    fun netCashRatioNeedsTwoConsecutiveAnnualReportsBelowHalf() {
        val oneLowYear = FinancialQualityPolicy.assess(
            periods = listOf(
                period("2024-12-31", netProfit = 100.0, operatingCashFlow = 40.0),
                period("2025-12-31", netProfit = 100.0, operatingCashFlow = 60.0),
            ),
            balances = listOf(balance("2024-12-31"), balance("2025-12-31")),
            deductRatio = 0.9,
            ttmOperatingCashFlow = 60.0,
        )
        assertFalse(oneLowYear.warnings.any { it.contains("净现比连续两年") })

        val twoLowYears = FinancialQualityPolicy.assess(
            periods = listOf(
                period("2024-12-31", netProfit = 100.0, operatingCashFlow = 40.0),
                period("2025-12-31", netProfit = 100.0, operatingCashFlow = 30.0),
            ),
            balances = listOf(balance("2024-12-31"), balance("2025-12-31")),
            deductRatio = 0.9,
            ttmOperatingCashFlow = 30.0,
        )
        assertTrue(twoLowYears.warnings.any { it.contains("净现比连续两年") })
    }

    @Test
    fun lowDeductRatioAndHighLeverageWithNegativeOperatingCashAreWarnings() {
        val assessment = FinancialQualityPolicy.assess(
            periods = listOf(period("2025-12-31", operatingCashFlow = -10.0)),
            balances = listOf(balance("2025-12-31", totalLiabilities = 660.0)),
            deductRatio = 0.69,
            ttmOperatingCashFlow = -10.0,
        )

        assertTrue(assessment.warnings.any { it.contains("扣非归母") && it.contains("0.7") })
        assertTrue(assessment.warnings.any { it.contains("资产负债率") && it.contains("经营性净现金流为负") })
    }

    @Test
    fun revenueDeclineWithInventoryBuildIsFlaggedWithoutTheMeaninglessDoubleRule() {
        val assessment = FinancialQualityPolicy.assess(
            periods = listOf(
                period("2025-06-30", revenue = 100.0),
                period("2026-06-30", revenue = 90.0),
            ),
            balances = listOf(
                balance("2025-06-30", inventory = 100.0),
                balance("2026-06-30", inventory = 105.0),
            ),
            deductRatio = 0.95,
            ttmOperatingCashFlow = 20.0,
        )

        assertTrue(assessment.warnings.any { it.contains("营收下滑而存货上升") })
        assertFalse(assessment.warnings.any { it.contains("达到营业收入增速的 2 倍") })
    }

    @Test
    fun highLeverageWithPositiveOperatingCashDoesNotTriggerCombinedWarning() {
        val assessment = FinancialQualityPolicy.assess(
            periods = listOf(period("2025-12-31", operatingCashFlow = 10.0)),
            balances = listOf(balance("2025-12-31", totalLiabilities = 660.0)),
            deductRatio = 0.9,
            ttmOperatingCashFlow = 10.0,
        )

        assertFalse(assessment.warnings.any { it.contains("高杠杆") })
    }

    @Test
    fun inventoryAndReceivableMismatchesNeedTheRequestedConditions() {
        val assessment = FinancialQualityPolicy.assess(
            periods = listOf(
                period("2024-06-30", revenue = 100.0),
                period("2025-06-30", revenue = 120.0),
            ),
            balances = listOf(
                balance("2024-06-30", accountsReceivable = 100.0, inventory = 100.0),
                balance("2025-06-30", accountsReceivable = 180.0, inventory = 300.0),
            ),
            deductRatio = 0.9,
            ttmOperatingCashFlow = 10.0,
        )

        assertTrue(assessment.warnings.any { it.contains("存货增速") })
        assertTrue(assessment.warnings.any { it.contains("应收账款增速") && it.contains("周转天数") })
    }

    @Test
    fun contractLiabilitiesAndContractAnnouncementsBecomePositiveEvidence() {
        val assessment = FinancialQualityPolicy.assess(
            periods = listOf(period("2025-12-31", revenue = 1_000.0)),
            balances = listOf(balance("2025-12-31", contractLiabilities = 250.0)),
            deductRatio = 0.9,
            ttmOperatingCashFlow = 100.0,
            announcements = listOf(Announcement("2025-10-01", "签订重大销售合同并完成交付")),
        )

        assertTrue(assessment.strengths.any { it.contains("预收款") })
        assertTrue(assessment.strengths.any { it.contains("合同/订单") })
    }

    @Test
    fun missingDataAndFinancialCompaniesAreNotJudgedAsBad() {
        val missing = FinancialQualityPolicy.assess(
            periods = listOf(period("2025-12-31", operatingCashFlow = null)),
            balances = emptyList(),
            deductRatio = null,
            ttmOperatingCashFlow = null,
        )
        assertTrue(missing.dataGaps.isNotEmpty())
        assertTrue(missing.warnings.isEmpty())

        val financial = FinancialQualityPolicy.assess(
            periods = listOf(period("2024-12-31", operatingCashFlow = -100.0), period("2025-12-31", operatingCashFlow = -100.0)),
            balances = listOf(balance("2024-12-31", totalLiabilities = 900.0), balance("2025-12-31", totalLiabilities = 900.0)),
            deductRatio = 0.5,
            ttmOperatingCashFlow = -100.0,
            financial = true,
        )
        assertTrue(financial.warnings.isEmpty())
        assertTrue(financial.dataGaps.any { it.contains("金融") })
    }
}
