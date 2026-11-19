package com.example.stockmonitor.analysis

import java.util.Locale

/** 财务质量规则的计算结果；缺失数据与真实风险分开保存。 */
data class FinancialQualityAssessment(
    val warnings: List<String> = emptyList(),
    val strengths: List<String> = emptyList(),
    val dataGaps: List<String> = emptyList(),
    val annualNetCashRatios: List<FinancialQualityPeriodMetric> = emptyList(),
    val debtRatio: Double? = null,
    val inventoryGrowth: Double? = null,
    val revenueGrowth: Double? = null,
    val receivableGrowth: Double? = null,
    val receivableTurnoverDays: Double? = null,
    val previousReceivableTurnoverDays: Double? = null,
    val contractLiabilities: Double? = null,
    val contractLiabilityRatio: Double? = null,
    val contractEvidenceCount: Int = 0,
    val applicable: Boolean = true,
)

data class FinancialQualityPeriodMetric(
    val date: String,
    val value: Double,
)

/**
 * 基本面财务质量的跨期规则。
 *
 * 这里的规则只做财报筛选和证据整理，不把异常直接等同于财务造假；取不到数据时返回缺口。
 */
object FinancialQualityPolicy {

    const val NET_CASH_RATIO_ALERT = 0.5
    const val DEDUCT_RATIO_ALERT = 0.7
    const val DEBT_RATIO_ALERT = 0.65
    const val INVENTORY_GROWTH_MULTIPLE = 2.0
    const val CONTRACT_LIABILITY_RATIO_STRENGTH = 0.20

    fun assess(
        periods: List<FinancialPeriod>,
        balances: List<BalanceSheetSnapshot>,
        deductRatio: Double?,
        ttmOperatingCashFlow: Double?,
        financial: Boolean = false,
        announcements: List<Announcement> = emptyList(),
    ): FinancialQualityAssessment {
        if (financial) {
            return FinancialQualityAssessment(
                dataGaps = listOf("金融行业跳过净现比、资产负债率+经营现金流、存货/应收错配和合同负债工业口径"),
                applicable = false,
            )
        }

        val warnings = mutableListOf<String>()
        val strengths = mutableListOf<String>()
        val dataGaps = mutableListOf<String>()
        val sortedPeriods = periods.sortedBy { it.date }
        val sortedBalances = balances.filter { it.date.length == 10 }.sortedBy { it.date }

        val annualNetCashRatios = sortedPeriods
            .filter { it.date.endsWith("-12-31") }
            .mapNotNull { period ->
                val cash = period.operatingCashFlow
                if (cash == null || period.netProfit <= 0 || !cash.isFinite()) {
                    null
                } else {
                    FinancialQualityPeriodMetric(period.date, cash / period.netProfit)
                }
            }
        val lastTwoAnnual = annualNetCashRatios.takeLast(2)
        if (lastTwoAnnual.size < 2 || !consecutiveYears(lastTwoAnnual[0].date, lastTwoAnnual[1].date)) {
            dataGaps.add("净现比连续两年需要两个相邻年度的经营现金流和归母净利润")
        } else if (lastTwoAnnual.all { it.value < NET_CASH_RATIO_ALERT }) {
            warnings.add(
                "净现比连续两年低于 0.5（${lastTwoAnnual.joinToString("、") { "${it.date.take(4)} 年 ${number(it.value)}" }}），利润现金含量弱",
            )
        }

        if (deductRatio == null || !deductRatio.isFinite()) {
            dataGaps.add("扣非归母净利润/归母净利润缺失")
        } else if (deductRatio < DEDUCT_RATIO_ALERT) {
            warnings.add("扣非归母净利润/归母净利润为 ${number(deductRatio)}，低于 0.7，利润主要依赖非经常性损益")
        }

        val latestBalance = sortedBalances.lastOrNull()
        val debtRatio = latestBalance?.let { balance ->
            if (balance.totalAssets > 0 && balance.totalLiabilities >= 0) {
                balance.totalLiabilities / balance.totalAssets
            } else {
                null
            }
        }
        if (latestBalance == null || debtRatio == null) {
            dataGaps.add("资产负债率需要有效的最新资产总额和负债总额")
        } else if (debtRatio > DEBT_RATIO_ALERT && ttmOperatingCashFlow != null && ttmOperatingCashFlow < 0) {
            warnings.add(
                "资产负债率 ${percent(debtRatio)} 且 TTM 经营性净现金流为负，属于高杠杆现金流风险",
            )
        } else if (debtRatio > DEBT_RATIO_ALERT && ttmOperatingCashFlow == null) {
            dataGaps.add("资产负债率超过 65%，但经营性净现金流缺失，无法判断高杠杆组合风险")
        }

        val latestPeriod = sortedPeriods.lastOrNull()
        val previousPeriod = latestPeriod?.let { previousSamePeriod(sortedPeriods, it.date) }
        val previousBalance = latestBalance?.let { previousSamePeriod(sortedBalances, it.date) }
        val latestRevenue = latestPeriod?.revenue
        val previousRevenue = previousPeriod?.revenue
        val revenueGrowth = growth(latestRevenue, previousRevenue)
        val inventoryGrowth = growth(latestBalance?.inventory, previousBalance?.inventory)
        if (inventoryGrowth == null || revenueGrowth == null) {
            dataGaps.add("存货增速与营业收入增速需要最新期和去年同期的利润表、资产负债表")
        } else if (inventoryGrowth > 0 && inventoryGrowth >= revenueGrowth * INVENTORY_GROWTH_MULTIPLE) {
            warnings.add(
                "存货增速 ${percent(inventoryGrowth)}，营业收入增速 ${percent(revenueGrowth)}，达到营业收入增速的 2 倍以上，需警惕备货未兑现",
            )
        }

        val receivableGrowth = growth(latestBalance?.accountsReceivable, previousBalance?.accountsReceivable)
        val receivableTurnoverDays = turnoverDays(latestBalance?.accountsReceivable, latestRevenue, latestPeriod?.date)
        val previousReceivableTurnoverDays = turnoverDays(previousBalance?.accountsReceivable, previousRevenue, previousPeriod?.date)
        if (receivableGrowth == null || receivableTurnoverDays == null || previousReceivableTurnoverDays == null) {
            dataGaps.add("应收账款增速和周转天数需要最新期与去年同期的应收账款、营业收入")
        } else if (receivableGrowth > (revenueGrowth ?: Double.NEGATIVE_INFINITY) &&
            receivableTurnoverDays > previousReceivableTurnoverDays
        ) {
            warnings.add(
                "应收账款增速 ${percent(receivableGrowth)} 高于营业收入增速 ${percent(revenueGrowth ?: 0.0)}，" +
                    "周转天数由 ${number(previousReceivableTurnoverDays)} 天升至 ${number(receivableTurnoverDays)} 天，需警惕放宽信用后回款变慢",
            )
        }

        val contractLiabilities = latestBalance?.contractLiabilities
        val contractLiabilityRatio = if (contractLiabilities != null && latestRevenue != null && latestRevenue > 0) {
            contractLiabilities / latestRevenue
        } else {
            null
        }
        if (contractLiabilities == null) {
            dataGaps.add("合同负债/预收款字段缺失，无法判断预收款质量")
        } else if (contractLiabilityRatio != null && contractLiabilityRatio >= CONTRACT_LIABILITY_RATIO_STRENGTH) {
            strengths.add(
                "合同负债/预收款 ${amount(contractLiabilities)}，约占最新报告期营业收入 ${percent(contractLiabilityRatio)}，" +
                    "下游先付款，是持续挣钱能力的正面证据",
            )
        }

        val contractEvidenceCount = announcements.count { announcement ->
            listOf("合同", "订单", "中标", "交付", "供货", "项目", "协议").any { keyword ->
                announcement.title.contains(keyword)
            }
        }
        if (contractEvidenceCount > 0) {
            strengths.add(
                "近 180 天发现 $contractEvidenceCount 条合同/订单/中标/交付类公告，可作为业务落地线索；仍需核实金额、交付和回款",
            )
        } else {
            dataGaps.add("未发现可核验的合同/订单/中标/交付公告证据")
        }
        if (contractLiabilityRatio != null && contractLiabilityRatio >= CONTRACT_LIABILITY_RATIO_STRENGTH && contractEvidenceCount > 0) {
            strengths.add("预收款与合同/订单落地线索同时存在，持续收入证据更完整；预收款本身不等于利润已实现")
        }

        return FinancialQualityAssessment(
            warnings = warnings,
            strengths = strengths,
            dataGaps = dataGaps,
            annualNetCashRatios = annualNetCashRatios,
            debtRatio = debtRatio,
            inventoryGrowth = inventoryGrowth,
            revenueGrowth = revenueGrowth,
            receivableGrowth = receivableGrowth,
            receivableTurnoverDays = receivableTurnoverDays,
            previousReceivableTurnoverDays = previousReceivableTurnoverDays,
            contractLiabilities = contractLiabilities,
            contractLiabilityRatio = contractLiabilityRatio,
            contractEvidenceCount = contractEvidenceCount,
        )
    }

    private fun consecutiveYears(first: String, second: String): Boolean =
        first.take(4).toIntOrNull()?.plus(1) == second.take(4).toIntOrNull() &&
            first.substringAfter('-') == second.substringAfter('-')

    private fun <T> previousSamePeriod(sorted: List<T>, latestDate: String, dateOf: (T) -> String = { item ->
        when (item) {
            is FinancialPeriod -> item.date
            is BalanceSheetSnapshot -> item.date
            else -> ""
        }
    }): T? {
        val latestYear = latestDate.take(4).toIntOrNull() ?: return null
        val monthDay = latestDate.substringAfter('-')
        return sorted.lastOrNull { item ->
            val date = dateOf(item)
            date.substringAfter('-') == monthDay && date.take(4).toIntOrNull() == latestYear - 1
        }
    }

    private fun growth(current: Double?, previous: Double?): Double? {
        if (current == null || previous == null || current < 0 || previous <= 0) return null
        return current / previous - 1.0
    }

    private fun turnoverDays(receivable: Double?, revenue: Double?, date: String?): Double? {
        if (receivable == null || revenue == null || date == null || revenue <= 0) return null
        val month = date.substringAfter('-').substringBefore('-').toIntOrNull() ?: return null
        val days = when (month) {
            3 -> 90.0
            6 -> 181.0
            9 -> 273.0
            12 -> 365.0
            else -> 365.0
        }
        return receivable / revenue * days
    }

    private fun number(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    private fun percent(value: Double): String = String.format(Locale.CHINA, "%.1f%%", value * 100)

    private fun amount(value: Double): String = String.format(Locale.CHINA, "%.2f 亿", value / 100_000_000)
}
