package com.example.stockmonitor.analysis

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** 单个报告期的累计财务数据（金额单位：元）。报告期数据是年初至今累计，不是单季。 */
data class FinancialPeriod(
    val date: String,
    val revenue: Double,
    val operateCost: Double,
    val netProfit: Double,
    val deductNetProfit: Double,
    val operatingCashFlow: Double,
    val salesCash: Double,
    val capex: Double,
)

/** 资产负债表关键项（金额单位：元）。 */
data class BalanceSheetSnapshot(
    val date: String,
    val monetaryFunds: Double,
    val accountsReceivable: Double,
    val otherReceivable: Double,
    val inventory: Double,
    val goodwill: Double,
    val shortLoan: Double,
    val longLoan: Double,
    val bondPayable: Double,
    val leaseLiability: Double,
    val totalAssets: Double,
    val totalLiabilities: Double,
    val parentEquity: Double,
    val minorityEquity: Double,
) {
    /** 有息负债：短期借款 + 长期借款 + 应付债券 + 租赁负债。 */
    val interestBearingDebt: Double get() = shortLoan + longLoan + bondPayable + leaseLiability

    /** 净现金，负数表示净负债。 */
    val netCash: Double get() = monetaryFunds - interestBearingDebt
}

/** 单个交易日的估值快照。 */
data class ValuationPoint(val date: String, val peTtm: Double?, val pbMrq: Double?)

/**
 * 主营构成的一条（来自年报/中报的「主营业务分产品/分地区/分行业」披露）。
 *
 * [type] 1 = 按行业，2 = 按产品，3 = 按地区（实测口径：茅台酒/系列酒是 2，国内/国外是 3）。同一期里这三类是各算各的，
 * 各自加起来都是 100%，不能混在一张表里求和。
 */
data class MainBusinessItem(
    val date: String,
    val type: Int,
    val name: String,
    val income: Double,
    /** 占主营业务收入的比例，小数（0.64 = 64%）。 */
    val ratio: Double,
    /** 该业务的毛利率，小数；亏损业务会是负数。 */
    val grossMargin: Double?,
)

/**
 * 同板块对比。用同一天的板块估值序列一次拉全，`marketCapRank` 就是这只票在板块里的市值排名。
 * 板块里既有盈利也有亏损的公司，中位数只统计正 PE。
 */
data class PeerContext(
    val boardName: String,
    val total: Int,
    val sampleSize: Int,
    val marketCapRank: Int?,
    val peMedian: Double?,
    val pbMedian: Double?,
    val marketCap: Double?,
)

/** 由累计值差分出来的单季数据（金额单位：元，比率为百分数）。 */
data class QuarterPoint(
    val label: String,
    val revenue: Double,
    val netProfit: Double,
    val grossMargin: Double?,
    val revenueYoy: Double?,
    val netProfitYoy: Double?,
)

enum class RiskLevel { GOOD, WATCH, ALERT }

data class RiskItem(val level: RiskLevel, val text: String, val evidenceDate: String? = null, val announcement: Boolean = false)

data class InstitutionalPosition(
    val name: String,
    val shares: Double,
)

data class ShareholderDisclosure(
    val reportDate: String,
    val holderCount: Long?,
    val institutions: List<InstitutionalPosition>,
)

data class InstitutionalChange(
    val name: String,
    val sharesDelta: Double,
    val estimatedFunds: Double?,
)

data class ShareholderAnalysis(
    val latestReportDate: String?,
    val retailHolderCount: Long?,
    val previousReportDate: String?,
    val previousRetailHolderCount: Long?,
    val institutionalNetShares: Double?,
    val institutionalNetFunds: Double?,
    val inflows: List<InstitutionalChange>,
    val outflows: List<InstitutionalChange>,
) {
    val retailHolderChange: Long?
        get() = if (retailHolderCount != null && previousRetailHolderCount != null) {
            retailHolderCount - previousRetailHolderCount
        } else {
            null
        }

    val retailHolderChangePct: Double?
        get() {
            val change = retailHolderChange
            return if (change != null && previousRetailHolderCount != null && previousRetailHolderCount != 0L) {
                change.toDouble() / previousRetailHolderCount * 100.0
            } else {
                null
            }
        }

    val conclusion: String
        get() {
            val retailDown = retailHolderChangePct?.let { it <= -5.0 } == true
            val retailUp = retailHolderChangePct?.let { it >= 5.0 } == true
            val institutionIn = institutionalNetFunds?.let { it > 0.0 } == true
            val institutionOut = institutionalNetFunds?.let { it < 0.0 } == true
            return when {
                retailDown && institutionIn -> "散户/中小股东户数下降，公开披露机构持仓变动估算为净增，筹码结构改善，偏利好。"
                retailUp && institutionOut -> "散户/中小股东户数增加，公开披露机构持仓变动估算为净减，筹码趋于分散，偏风险。"
                institutionIn -> "公开披露机构持仓变动估算为净增，机构关注度边际增强，偏利好。"
                institutionOut -> "公开披露机构持仓变动估算为净减，机构关注度边际减弱，需警惕筹码撤出风险。"
                retailDown -> "散户/中小股东户数下降，筹码集中度有所改善，但机构流向不明。"
                retailUp -> "散户/中小股东户数增加，筹码趋于分散，但机构流向不明。"
                else -> "股东结构暂无明确改善或恶化信号。"
            }
        }

    companion object {
        fun from(
            latest: ShareholderDisclosure?,
            previous: ShareholderDisclosure?,
            currentPrice: Double,
        ): ShareholderAnalysis? {
            if (latest == null && previous == null) return null
            val latestPositions = latest?.institutions.orEmpty().associateBy { it.name.trim() }
            val previousPositions = previous?.institutions.orEmpty().associateBy { it.name.trim() }
            val names = (latestPositions.keys + previousPositions.keys).filter { it.isNotBlank() }.toSet()
            val changes = names.mapNotNull { name ->
                val delta = (latestPositions[name]?.shares ?: 0.0) - (previousPositions[name]?.shares ?: 0.0)
                if (kotlin.math.abs(delta) < 0.0001) {
                    null
                } else {
                    InstitutionalChange(
                        name = name,
                        sharesDelta = delta,
                        estimatedFunds = currentPrice.takeIf { it > 0.0 }?.let { delta * it },
                    )
                }
            }
            val netShares = changes.takeIf { latest != null && previous != null }?.sumOf { it.sharesDelta }
            val netFunds = changes.takeIf { latest != null && previous != null && currentPrice > 0.0 }
                ?.sumOf { it.estimatedFunds ?: 0.0 }
            return ShareholderAnalysis(
                latestReportDate = latest?.reportDate,
                retailHolderCount = latest?.holderCount,
                previousReportDate = previous?.reportDate,
                previousRetailHolderCount = previous?.holderCount,
                institutionalNetShares = netShares,
                institutionalNetFunds = netFunds,
                inflows = changes.filter { it.sharesDelta > 0.0 }.sortedByDescending { it.sharesDelta },
                outflows = changes.filter { it.sharesDelta < 0.0 }.sortedBy { it.sharesDelta },
            )
        }
    }
}

/** 估值锚的来源：个股自己的历史估值，还是同板块的倍数。 */
enum class AnchorKind { SELF_HISTORY, PEER }

/** 一个估值锚：给出它认为的合理价区间与依据。 */
data class ValuationAnchor(
    val name: String,
    val low: Double,
    val high: Double,
    val note: String,
    val kind: AnchorKind,
)

data class FundamentalResult(
    /** 最近若干单季（默认 8 个），升序。 */
    val quarters: List<QuarterPoint>,
    /** 扣非归母 ÷ 归母（TTM，小数）。 */
    val deductRatio: Double?,
    val grossMargin: Double?,
    val netMargin: Double?,
    /** 经营现金流 ÷ 归母净利（TTM）。 */
    val ocfToProfit: Double?,
    /** 销售收到的现金 ÷ 营业收入（TTM）；含税口径下 1.0~1.13 才算正常。 */
    val cashToRevenue: Double?,
    /** 净现金 ÷ 归母净资产（小数，可为负）。 */
    val netCashRatio: Double?,
    /** ROE：TTM 归母净利 ÷ 期末归母净资产（小数）。严格算法要用平均净资产，这里用期末近似。 */
    val roe: Double?,
    /** TTM 归母净利 ÷ 总股本，单位元。 */
    val eps: Double?,
    /** TTM 归母净利 / TTM 扣非净利，单位元。 */
    val ttmNetProfit: Double?,
    val ttmDeductNetProfit: Double?,
    /** 归母净资产 ÷ 总股本，单位元。 */
    val bps: Double?,
    /** 最近一个交易日的 PE-TTM / PB-MRQ，与分位同源。 */
    val currentPe: Double?,
    val currentPb: Double?,
    /** 最新一份报告期的累计值（原始口径，未差分），用于展示「最近报告期」这一行。 */
    val latest: FinancialPeriod?,
    val balance: BalanceSheetSnapshot?,
    /** 主营业务分产品构成（按收入降序，已过滤到最近一期）。 */
    val mainBusiness: List<MainBusinessItem>,
    /** 近半年公告里需要重点解释的条目（质押、处罚、问询、重组等）。 */
    val governance: List<GovernanceItem>,
    /** 最新两期股东户数与公开披露机构持仓变化。 */
    val shareholder: ShareholderAnalysis?,
    /** 实际扫描过的公告条数，用来交代覆盖率。 */
    val announcementsScanned: Int,
    /** 扫描到的公告覆盖的最早/最晚日期；窗口没取满时如实说明，别让人以为看满了 180 天。 */
    val announcementFrom: String?,
    val announcementTo: String?,
    val peers: PeerContext?,
    val score: Int,
    val verdict: String,
    val risks: List<RiskItem>,
    val anchors: List<ValuationAnchor>,
    val fairLow: Double?,
    val fairHigh: Double?,
    /** 当前 PE 在可用历史中的分位（0~100）。 */
    val pePercentile: Double?,
    val pbPercentile: Double?,
    /** 口径提醒：亏损、历史不足、各方法分歧大、周期高点等。 */
    val notes: List<String>,
)

/**
 * 个股基本面分析：把三张表 + 估值序列折算成「能不能信、贵不贵」两个结论。
 *
 * 三条硬规则，违反任何一条结论都会反：
 *
 * 1. **报告期是累计值，必须差分出单季**。2026 中报营收是上半年累计，直接和去年中报比没问题，
 *    但直接当成「Q2」去和 Q1 比就会得出「环比腰斩」这种假结论。
 * 2. **比率的分子分母要用同一个区间**。毛利率、净利率、现金流/利润一律用 TTM（最近 4 个单季合计），
 *    不混用某个报告期的累计值，否则 H1 的数据和 Q1 的数据放在一起看会失真。
 * 3. **估值锚要各自标明适用条件**。亏损股没有 PE，净资产为负没有 PB，
 *    周期股在盈利高点的低 PE 是陷阱而不是便宜——这些情况宁可不出数字，也不出一个会被误读的数字。
 *
 * 「内控风险」这一块要说明白：接口里拿不到审计意见、股权质押、违规处罚这些直接证据，
 * 这里用的是财报上可观测的**财务质量代理指标**（商誉占比、其他应收款占比、存贷双高、
 * 扣非比例、现金流含量等），能提示风险但不能替代审计结论。
 */
object Fundamentals {

    /** 默认展示与计算的单季数量：4 个同比点需要 8 个单季。 */
    const val QUARTERS = 8

    /** 估值分位至少要有这么多天的历史才给结论（约 3 个月）。 */
    private const val MIN_VALUATION_POINTS = 20

    /** 单季同比至少要有 4 个点才拿去做趋势打分。 */
    private const val MIN_YOY_POINTS = 4

    /**
     * 指标档位：**卡片着色与风险清单共用同一套阈值**。
     *
     * 同一个数字在两处给出不同结论（卡片刻度按 0.9 分档、风险清单按 0.85 分档）比阈值定得高一点低一点更糟，
     * 所以档位只在这里定义一次。返回 null 表示「中性」：既不值得表扬，也不值得提示。
     */
    object Bands {

        fun deductRatio(value: Double?): RiskLevel? = when {
            value == null -> null
            value >= 0.95 -> RiskLevel.GOOD
            value >= 0.85 -> null
            value >= 0.70 -> RiskLevel.WATCH
            else -> RiskLevel.ALERT
        }

        fun ocfToProfit(value: Double?): RiskLevel? = when {
            value == null -> null
            value >= 1.0 -> RiskLevel.GOOD
            value >= 0.8 -> null
            value >= 0.5 -> RiskLevel.WATCH
            else -> RiskLevel.ALERT
        }

        /** 票据结算多的制造业收现比天然低于 1，0.85 以下才算「收入没收到钱」。 */
        fun cashToRevenue(value: Double?): RiskLevel? = when {
            value == null -> null
            value >= 1.0 -> null
            value >= 0.85 -> RiskLevel.WATCH
            else -> RiskLevel.ALERT
        }

        /** 净现金为正就算健康；有息负债超过净资产降级为告警。 */
        fun netCash(netCashRatio: Double?, balance: BalanceSheetSnapshot?): RiskLevel? = when {
            netCashRatio == null -> null
            netCashRatio >= 0.0 -> RiskLevel.GOOD
            balance != null && balance.parentEquity > 0 &&
                balance.interestBearingDebt / balance.parentEquity >= 1.0 -> RiskLevel.ALERT
            else -> RiskLevel.WATCH
        }

        fun goodwill(goodwillRatio: Double?): RiskLevel? = when {
            goodwillRatio == null -> null
            goodwillRatio >= 0.30 -> RiskLevel.ALERT
            goodwillRatio >= 0.10 -> RiskLevel.WATCH
            else -> null
        }

        fun otherReceivable(ratio: Double?): RiskLevel? = when {
            ratio == null -> null
            ratio >= 0.10 -> RiskLevel.ALERT
            ratio >= 0.05 -> RiskLevel.WATCH
            else -> null
        }

        /** ROE：15% 以上算优秀，8% 以下说明股东回报偏弱。 */
        fun roe(value: Double?): RiskLevel? = when {
            value == null -> null
            value >= 0.15 -> RiskLevel.GOOD
            value >= 0.08 -> null
            value >= 0.0 -> RiskLevel.WATCH
            else -> RiskLevel.ALERT
        }
    }

    data class Input(
        val periods: List<FinancialPeriod>,
        val balance: BalanceSheetSnapshot?,
        val valuations: List<ValuationPoint>,
        val totalShares: Double,
        val price: Double,
        val peers: PeerContext? = null,
        val mainBusiness: List<MainBusinessItem> = emptyList(),
        val announcements: List<Announcement> = emptyList(),
        val shareholder: ShareholderAnalysis? = null,
        val today: String = "",
    )

    fun analyze(input: Input): FundamentalResult {
        val notes = mutableListOf<String>()
        val quarters = quarters(input.periods, QUARTERS)
        val ttm = ttmOf(input.periods)
        val balance = input.balance
        val shares = input.totalShares.takeIf { it > 0 }

        val deductRatio = ratio(ttm?.deductNetProfit, ttm?.netProfit)
        val grossMargin = ttm?.let { ratio(it.revenue - it.operateCost, it.revenue)?.times(100) }
        val netMargin = ttm?.let { ratio(it.netProfit, it.revenue)?.times(100) }
        val ocfToProfit = ttm?.let { ratio(it.operatingCashFlow, it.netProfit) }
        val cashToRevenue = ttm?.let { ratio(it.salesCash, it.revenue) }
        val netCashRatio = if (balance != null && balance.parentEquity > 0) {
            balance.netCash / balance.parentEquity
        } else {
            null
        }
        val roe = if (balance != null && balance.parentEquity > 0 && ttm != null) {
            ttm.netProfit / balance.parentEquity
        } else {
            null
        }

        val governance = Governance.scan(
            items = input.announcements,
            referenceDate = input.today.ifBlank { input.valuations.lastOrNull()?.date.orEmpty() },
        )
        val financialDate = listOfNotNull(input.periods.maxByOrNull { it.date }?.date, balance?.date).minOrNull()
        val risks = riskScan(input, deductRatio, ocfToProfit, cashToRevenue, netCashRatio, roe)
            .map { it.copy(evidenceDate = financialDate) }.toMutableList()
        // 公告里的重大风险（立案、处罚、冻结）直接进风险清单；提示类只留在公告一节，避免刷屏。
        governance.filter { it.level == RiskLevel.ALERT }.forEach {
            risks.add(RiskItem(RiskLevel.ALERT, "公告提示：${it.title}（${it.date}）", it.date, announcement = true))
        }
        input.shareholder?.let { shareholder ->
            val institutionOutflow = shareholder.institutionalNetFunds?.let { it < 0.0 } == true
            val holderDispersion = shareholder.retailHolderChangePct?.let { it >= 5.0 } == true
            if (institutionOutflow || holderDispersion) {
                risks.add(RiskItem(RiskLevel.WATCH, "股东结构风险：${shareholder.conclusion}"))
            }
        }
        val score = scoreOf(quarters, deductRatio, ocfToProfit, cashToRevenue, netCashRatio, balance)

        if (quarters.size < QUARTERS) {
            notes.add("可用单季只有 ${quarters.size} 个，同比点不足，成长性仅供参考")
        }

        val eps = if (shares != null && ttm != null) ttm.netProfit / shares else null
        val bps = if (shares != null && balance != null) balance.parentEquity / shares else null
        val peHistory = input.valuations.mapNotNull { it.peTtm }
        val pbHistory = input.valuations.mapNotNull { it.pbMrq }
        val currentPe = input.valuations.lastOrNull { it.peTtm != null }?.peTtm
        val currentPb = input.valuations.lastOrNull { it.pbMrq != null }?.pbMrq
        val pePercentile = currentPercentile(peHistory, currentPe)
        val pbPercentile = currentPercentile(pbHistory, currentPb)

        val anchors = valuationAnchors(input, eps, bps, peHistory, pbHistory, notes)
        val (fairLow, fairHigh) = combine(anchors, notes)
        addTimingNotes(input, notes, pePercentile, quarters, ttm)
        addGrowthNotes(quarters, notes)
        addDeductNote(input, notes, ttm, eps, shares)
        // TTM 只用到利润表和现金流量表都有的报告期，资产负债表可能比它新一期，ROE 这类跨表比率要说清楚。
        if (balance != null && ttm != null && balance.date != ttm.date) {
            notes.add(
                "资产负债表报告期（${balance.date}）与利润表 TTM 截止期（${ttm.date}）不同期，" +
                    "ROE、净现金比例是跨期口径"
            )
        }

        return FundamentalResult(
            quarters = quarters,
            deductRatio = deductRatio,
            grossMargin = grossMargin,
            netMargin = netMargin,
            ocfToProfit = ocfToProfit,
            cashToRevenue = cashToRevenue,
            netCashRatio = netCashRatio,
            roe = roe,
            eps = eps,
            ttmNetProfit = ttm?.netProfit,
            ttmDeductNetProfit = ttm?.deductNetProfit,
            bps = bps,
            currentPe = currentPe,
            currentPb = currentPb,
            latest = input.periods.maxByOrNull { it.date },
            balance = balance,
            mainBusiness = mainBusinessOf(input.mainBusiness),
            governance = governance,
            shareholder = input.shareholder,
            announcementsScanned = input.announcements.size,
            announcementFrom = input.announcements.minOfOrNull { it.date },
            announcementTo = input.announcements.maxOfOrNull { it.date },
            peers = input.peers,
            score = score,
            verdict = verdictOf(score),
            risks = risks,
            anchors = anchors,
            fairLow = fairLow,
            fairHigh = fairHigh,
            pePercentile = pePercentile,
            pbPercentile = pbPercentile,
            notes = notes,
        )
    }

    /** 最近一期的分产品构成，按收入降序；同一期缺产品分类时依次退回行业、地区分类。 */
    fun mainBusinessOf(items: List<MainBusinessItem>): List<MainBusinessItem> {
        val latest = items.maxOfOrNull { it.date } ?: return emptyList()
        val current = items.filter { it.date == latest }
        val picked = current.filter { it.type == MAINOP_PRODUCT }.ifEmpty {
            current.filter { it.type == MAINOP_INDUSTRY }.ifEmpty { current.filter { it.type == MAINOP_REGION } }
        }
        return picked.sortedByDescending { it.income }
    }

    const val MAINOP_INDUSTRY = 1
    const val MAINOP_PRODUCT = 2
    const val MAINOP_REGION = 3

    // ── 单季差分 ──────────────────────────────────────────────────────────────

    /**
     * 累计值 → 单季值。
     *
     * 一季报（03-31）本身就是单季；二季报减一季报，三季报减二季报，年报减三季报。
     *
     * 只有「上一个报告期正好是上一季度」才做差分：跨年相减（Q1 减上年年报）等于把全年收入和
     * 一季度相加，是错的；中间漏了一期（比如缺中报）再相减，会把两个季度并成一个，也是错的。
     */
    fun quarters(periods: List<FinancialPeriod>, limit: Int = QUARTERS): List<QuarterPoint> {
        val sorted = periods.sortedBy { it.date }
        if (sorted.isEmpty()) return emptyList()
        data class Single(val date: String, val revenue: Double, val netProfit: Double, val cost: Double)

        val singles = sorted.mapIndexed { index, period ->
            val prev = sorted.getOrNull(index - 1)
            val chained = consecutiveQuarter(prev, period)
            Single(
                date = period.date,
                revenue = if (chained) period.revenue - prev!!.revenue else period.revenue,
                netProfit = if (chained) period.netProfit - prev!!.netProfit else period.netProfit,
                cost = if (chained) period.operateCost - prev!!.operateCost else period.operateCost,
            )
        }
        val bySlot = singles.associateBy { it.date.take(4) + it.date.substring(5, 7) }
        return singles.takeLast(limit).map { single ->
            val month = single.date.substring(5, 7)
            val lastYear = bySlot[(single.date.take(4).toInt() - 1).toString() + month]
            QuarterPoint(
                label = single.date.substring(2, 4) + "Q" + quarterOf(month),
                revenue = single.revenue,
                netProfit = single.netProfit,
                grossMargin = ratio(single.revenue - single.cost, single.revenue)?.times(100),
                revenueYoy = if (lastYear != null && lastYear.revenue > 0) {
                    (single.revenue / lastYear.revenue - 1) * 100
                } else {
                    null
                },
                // 去年亏、今年盈（或反过来）时同比没有意义，不给数字。
                netProfitYoy = if (lastYear != null && lastYear.netProfit > 0 && single.netProfit > 0) {
                    (single.netProfit / lastYear.netProfit - 1) * 100
                } else {
                    null
                },
            )
        }
    }

    private fun quarterOf(month: String): Int = when (month) {
        "03" -> 1
        "06" -> 2
        "09" -> 3
        else -> 4
    }

    /** [prev] 是不是 [current] 的上一个季度（同一自然年、月份正好差 3）。 */
    private fun consecutiveQuarter(prev: FinancialPeriod?, current: FinancialPeriod): Boolean {
        if (prev == null) return false
        val year = current.date.take(4).toIntOrNull() ?: return false
        val month = current.date.substring(5, 7).toIntOrNull() ?: return false
        val prevYear = prev.date.take(4).toIntOrNull() ?: return false
        val prevMonth = prev.date.substring(5, 7).toIntOrNull() ?: return false
        return year == prevYear && prevMonth + 3 == month
    }

    /**
     * 最近 4 个单季合计（TTM），用于所有比率。
     *
     * 必须先对**全部**报告期做差分，再取最后 4 个单季求和。只截最后 4 期再差分的话，
     * 首个报告期没有同年上一期可比，会被当成单季累加进来（等于把三季报当一季报），
     * TTM 直接虚高一大截。
     */
    internal fun ttmOf(periods: List<FinancialPeriod>): FinancialPeriod? {
        if (periods.isEmpty()) return null
        val sorted = periods.sortedBy { it.date }
        val count = minOf(4, sorted.size)

        fun ttm(pick: (FinancialPeriod) -> Double): Double =
            sorted.mapIndexed { index, period ->
                val prev = sorted.getOrNull(index - 1)
                if (consecutiveQuarter(prev, period)) pick(period) - pick(prev!!) else pick(period)
            }.takeLast(count).sum()

        return FinancialPeriod(
            date = sorted.last().date,
            revenue = ttm { it.revenue },
            operateCost = ttm { it.operateCost },
            netProfit = ttm { it.netProfit },
            deductNetProfit = ttm { it.deductNetProfit },
            operatingCashFlow = ttm { it.operatingCashFlow },
            salesCash = ttm { it.salesCash },
            capex = ttm { it.capex },
        )
    }

    // ── 风险扫描（财务质量代理指标） ────────────────────────────────────────────

    private fun riskScan(
        input: Input,
        deductRatio: Double?,
        ocfToProfit: Double?,
        cashToRevenue: Double?,
        netCashRatio: Double?,
        roe: Double?,
    ): List<RiskItem> {
        val items = mutableListOf<RiskItem>()
        val balance = input.balance

        if (balance != null) {
            val equity = balance.parentEquity
            if (equity > 0) {
                val goodwillRatio = balance.goodwill / equity
                when (Bands.goodwill(goodwillRatio)) {
                    RiskLevel.ALERT -> items.add(RiskItem(RiskLevel.ALERT, "商誉占净资产 ${pctOf(goodwillRatio)}，减值风险高"))
                    RiskLevel.WATCH -> items.add(RiskItem(RiskLevel.WATCH, "商誉占净资产 ${pctOf(goodwillRatio)}，留意减值"))
                    else -> Unit
                }
            }
            if (balance.totalAssets > 0) {
                val otherReceRatio = balance.otherReceivable / balance.totalAssets
                when (Bands.otherReceivable(otherReceRatio)) {
                    RiskLevel.ALERT -> items.add(RiskItem(RiskLevel.ALERT, "其他应收款占总资产 ${pctOf(otherReceRatio)}，存在资金占用嫌疑"))
                    RiskLevel.WATCH -> items.add(RiskItem(RiskLevel.WATCH, "其他应收款占总资产 ${pctOf(otherReceRatio)}，偏高"))
                    else -> Unit
                }
                val cashRatio = balance.monetaryFunds / balance.totalAssets
                val debtRatio = balance.interestBearingDebt / balance.totalAssets
                if (cashRatio >= 0.20 && debtRatio >= 0.20) {
                    items.add(RiskItem(RiskLevel.ALERT, "货币资金与有息负债同时很高（存贷双高），需要核实资金真实性"))
                }
                val receivables = balance.accountsReceivable + balance.inventory
                if (receivables / balance.totalAssets >= 0.50) {
                    items.add(RiskItem(RiskLevel.WATCH, "应收+存货占总资产 ${pctOf(receivables / balance.totalAssets)}，资产偏重"))
                }
            }
            when (Bands.netCash(netCashRatio, balance)) {
                RiskLevel.GOOD -> items.add(
                    RiskItem(
                        RiskLevel.GOOD,
                        if (netCashRatio != null && netCashRatio >= 0.20) {
                            "净现金占净资产 ${pctOf(netCashRatio)}，账面很安全"
                        } else {
                            "净现金为正，有息负债低于账面货币资金"
                        },
                    )
                )
                RiskLevel.ALERT -> items.add(RiskItem(RiskLevel.ALERT, "有息负债超过净资产，偿债压力大"))
                RiskLevel.WATCH -> items.add(RiskItem(RiskLevel.WATCH, "净负债 ${pctOf(-(netCashRatio ?: 0.0))} 净资产"))
                else -> Unit
            }
        }

        when (Bands.deductRatio(deductRatio)) {
            RiskLevel.GOOD -> items.add(RiskItem(RiskLevel.GOOD, "扣非占归母 ${pctOf(deductRatio ?: 0.0)}，利润几乎全部来自主营"))
            RiskLevel.WATCH -> items.add(RiskItem(RiskLevel.WATCH, "扣非只占归母 ${pctOf(deductRatio ?: 0.0)}，非经常性损益贡献不小"))
            RiskLevel.ALERT -> items.add(RiskItem(RiskLevel.ALERT, "扣非仅占归母 ${pctOf(deductRatio ?: 0.0)}，利润主要靠非经常性损益"))
            else -> Unit
        }

        when (Bands.ocfToProfit(ocfToProfit)) {
            RiskLevel.GOOD -> items.add(RiskItem(RiskLevel.GOOD, "经营现金流是净利润的 ${num(ocfToProfit ?: 0.0)} 倍，利润落袋"))
            RiskLevel.WATCH -> items.add(RiskItem(RiskLevel.WATCH, "经营现金流只有净利润的 ${num(ocfToProfit ?: 0.0)} 倍，回款偏慢"))
            RiskLevel.ALERT -> items.add(RiskItem(RiskLevel.ALERT, "经营现金流远低于净利润（${num(ocfToProfit ?: 0.0)} 倍），利润没变成钱"))
            else -> Unit
        }

        when (Bands.cashToRevenue(cashToRevenue)) {
            RiskLevel.WATCH -> items.add(
                RiskItem(RiskLevel.WATCH, "销售收现比 ${num(cashToRevenue ?: 0.0)}，低于含税口径的 1.0~1.13，留意票据与应收是否变多")
            )
            RiskLevel.ALERT -> items.add(RiskItem(RiskLevel.ALERT, "销售收现比只有 ${num(cashToRevenue ?: 0.0)}，收入没收到钱"))
            else -> Unit
        }

        when (Bands.roe(roe)) {
            RiskLevel.WATCH -> items.add(RiskItem(RiskLevel.WATCH, "ROE 只有 ${pctOf(roe ?: 0.0)}，股东回报偏弱"))
            RiskLevel.ALERT -> items.add(RiskItem(RiskLevel.ALERT, "ROE 为负（${pctOf(roe ?: 0.0)}），净资产在亏钱"))
            else -> Unit
        }

        // 最新一期成本增速明显快于收入增速 = 毛利被压缩，是竞争格局变差的早期信号。
        if (input.periods.size >= 5) {
            val revenueGrowth = growthOf(input.periods) { it.revenue }
            val costGrowth = growthOf(input.periods) { it.operateCost }
            if (revenueGrowth != null && costGrowth != null && costGrowth - revenueGrowth >= 25) {
                items.add(
                    RiskItem(
                        RiskLevel.WATCH,
                        "营业成本增速比营收快 ${num(costGrowth - revenueGrowth)} 个百分点，毛利在被压缩",
                    )
                )
            }
        }

        // 集中度只看产品口径：地区口径的「国内占 98.9%」不是业务集中，是销售地集中，不能当成风险。
        val products = mainBusinessOf(input.mainBusiness).filter { it.type == MAINOP_PRODUCT }
        if (products.size >= 2) {
            val top = products.maxByOrNull { it.ratio }
            // 90% 以下不算集中风险；接近 100% 说明只披露了一块业务，也不是集中度问题。
            if (top != null && top.ratio >= 0.90 && top.ratio < 0.995) {
                items.add(RiskItem(RiskLevel.WATCH, "「${top.name}」一项就占主营收入 ${pctOf(top.ratio)}，单一业务依赖度高"))
            }
            val bleeding = products.filter { (it.grossMargin ?: 0.0) < 0 && it.ratio >= 0.10 }
            if (bleeding.isNotEmpty()) {
                val names = bleeding.joinToString("、") { it.name }
                items.add(RiskItem(RiskLevel.WATCH, "$names 毛利率为负，在拖累整体利润"))
            }
        }
        return items
    }

    /** 最近报告期相对去年同期的增速（百分数）。 */
    private fun growthOf(periods: List<FinancialPeriod>, pick: (FinancialPeriod) -> Double): Double? {
        val sorted = periods.sortedBy { it.date }
        val last = sorted.lastOrNull() ?: return null
        val previous = sorted.lastOrNull {
            it.date.take(4) == (last.date.take(4).toInt() - 1).toString() &&
                it.date.substring(5, 7) == last.date.substring(5, 7)
        } ?: return null
        val base = pick(previous)
        if (base <= 0) return null
        return (pick(last) / base - 1) * 100
    }

    // ── 打分 ─────────────────────────────────────────────────────────────────

    /** 五个维度各 20 分：利润含金量、现金质量、资产安全、成长性、资产干净程度。 */
    private fun scoreOf(
        quarters: List<QuarterPoint>,
        deductRatio: Double?,
        ocfToProfit: Double?,
        cashToRevenue: Double?,
        netCashRatio: Double?,
        balance: BalanceSheetSnapshot?,
    ): Int {
        var score = 0

        score += when {
            deductRatio == null -> 10
            deductRatio >= 0.95 -> 20
            deductRatio >= 0.85 -> 14
            deductRatio >= 0.70 -> 8
            else -> 0
        }

        val ocf = ocfToProfit
        val cash = cashToRevenue
        // 拿不到数据给中性分：缺表不等于报表难看，给 0 分会把「没数据」说成「现金质量差」。
        score += if (ocf == null || cash == null) 8 else when {
            ocf >= 1.0 && cash >= 1.0 -> 20
            ocf >= 0.8 -> 14
            ocf >= 0.5 -> 8
            else -> 0
        }

        score += when {
            netCashRatio == null -> 10
            netCashRatio >= 0.20 -> 20
            netCashRatio >= 0.0 -> 14
            netCashRatio >= -0.50 -> 8
            else -> 0
        }

        val revenueYoy = quarters.mapNotNull { it.revenueYoy }.takeLast(MIN_YOY_POINTS)
        val profitYoy = quarters.mapNotNull { it.netProfitYoy }.takeLast(MIN_YOY_POINTS)
        score += when {
            revenueYoy.isEmpty() || profitYoy.isEmpty() -> 10
            else -> {
                val revenueMedian = median(revenueYoy)
                val profitMedian = median(profitYoy)
                when {
                    revenueMedian >= 15 && profitMedian >= 15 -> 20
                    revenueMedian >= 5 && profitMedian >= 5 -> 14
                    revenueMedian >= 0 && profitMedian >= 0 -> 8
                    else -> 0
                }
            }
        }

        score += when {
            balance == null || balance.parentEquity <= 0 -> 10
            else -> {
                val goodwillRatio = balance.goodwill / balance.parentEquity
                val otherReceRatio = if (balance.totalAssets > 0) balance.otherReceivable / balance.totalAssets else 0.0
                when {
                    goodwillRatio < 0.05 && otherReceRatio < 0.05 -> 20
                    goodwillRatio < 0.20 && otherReceRatio < 0.05 -> 14
                    goodwillRatio < 0.30 -> 8
                    else -> 0
                }
            }
        }
        return score
    }

    private fun verdictOf(score: Int): String = when {
        score >= 85 -> "优秀"
        score >= 70 -> "良好"
        score >= 55 -> "中性"
        score >= 40 -> "偏弱"
        else -> "较差"
    }

    // ── 估值 ─────────────────────────────────────────────────────────────────

    private fun valuationAnchors(
        input: Input,
        eps: Double?,
        bps: Double?,
        peHistory: List<Double>,
        pbHistory: List<Double>,
        notes: MutableList<String>,
    ): List<ValuationAnchor> {
        val anchors = mutableListOf<ValuationAnchor>()

        if (eps != null && eps <= 0) {
            notes.add("TTM 归母净利为负，PE 类估值不适用，只保留 PB 与同业参考")
        } else if (eps == null) {
            notes.add("拿不到总股本或 TTM 净利，算不出每股指标，PE/PB 类估值不适用")
        } else if (peHistory.size < MIN_VALUATION_POINTS) {
            notes.add("PE 历史不足 $MIN_VALUATION_POINTS 个交易日，无法给估值分位")
        }

        if (eps != null && eps > 0 && peHistory.size >= MIN_VALUATION_POINTS) {
            val low = percentile(peHistory, 0.30)
            val high = percentile(peHistory, 0.60)
            if (low != null && high != null && high > 0) {
                anchors.add(
                    ValuationAnchor(
                        "自身 PE 分位（${peHistory.size} 个交易日）",
                        low * eps,
                        high * eps,
                        "历史 PE 的 30%~60% 分位对应 EPS-TTM ${price(eps)}",
                        AnchorKind.SELF_HISTORY,
                    )
                )
            }
        }

        if (bps != null && bps > 0 && pbHistory.size >= MIN_VALUATION_POINTS) {
            val low = percentile(pbHistory, 0.30)
            val high = percentile(pbHistory, 0.60)
            if (low != null && high != null && high > 0) {
                anchors.add(
                    ValuationAnchor(
                        "自身 PB 分位（${pbHistory.size} 个交易日）",
                        low * bps,
                        high * bps,
                        "历史 PB 的 30%~60% 分位对应 BPS ${price(bps)}",
                        AnchorKind.SELF_HISTORY,
                    )
                )
            }
        } else if (bps != null && bps <= 0) {
            notes.add("净资产为负，PB 估值不适用")
        }

        val peers = input.peers
        val peerPe = peers?.peMedian
        if (eps != null && eps > 0 && peerPe != null && peerPe > 0) {
            anchors.add(
                ValuationAnchor(
                    "同业 PE 中位（${peers.sampleSize} 只）",
                    peerPe * eps * 0.85,
                    peerPe * eps * 1.15,
                    "${peers.boardName}板块 PE 中位 ${num(peerPe)} 倍，上下留 15% 区间",
                    AnchorKind.PEER,
                )
            )
        }
        val peerPb = peers?.pbMedian
        if (bps != null && bps > 0 && peerPb != null && peerPb > 0) {
            anchors.add(
                ValuationAnchor(
                    "同业 PB 中位（${peers.sampleSize} 只）",
                    peerPb * bps * 0.85,
                    peerPb * bps * 1.15,
                    "${peers.boardName}板块 PB 中位 ${num(peerPb)} 倍，上下留 15% 区间",
                    AnchorKind.PEER,
                )
            )
        }
        return anchors
    }

    /**
     * 合并估值锚。
     *
     * 优先只用**个股自己的历史估值分位**：同一家公司不同时期的估值口径是可比的，
     * 而同板块倍数受板块成分影响极大——半导体板块 2026 年的 PE 中位数是 101 倍，
     * 把一个扣非 PE 已经 128 倍的封测公司按板块中位数去衡量，只会得出「很便宜」这种反向结论。
     * 所以同业口径只作交叉验证，分歧大时明确提示以自身历史为准。
     */
    private fun combine(anchors: List<ValuationAnchor>, notes: MutableList<String>): Pair<Double?, Double?> {
        if (anchors.isEmpty()) return null to null
        val self = anchors.filter { it.kind == AnchorKind.SELF_HISTORY }
        val used = self.ifEmpty { anchors }
        val low = median(used.map { it.low })
        val high = median(used.map { it.high })

        val center = (low + high) / 2
        val peerCenters = anchors.filter { it.kind == AnchorKind.PEER }.map { (it.low + it.high) / 2 }
        val peerCenter = if (peerCenters.isEmpty()) null else median(peerCenters)
        if (self.isNotEmpty() && peerCenter != null && center > 0) {
            val gap = (peerCenter - center) / center
            if (abs(gap) >= 0.40) {
                notes.add(
                    "同业口径的中枢是 ${price(peerCenter)} 元，与自身历史口径的 ${price(center)} 元差 ${pctOf(abs(gap))}；" +
                        "板块估值与个股自身历史脱节时，合理区间以自身历史为准，同业只作参考"
                )
            }
        }
        if (used.size > 1) {
            val centers = used.map { (it.low + it.high) / 2 }
            val centerMedian = median(centers)
            if (centerMedian > 0) {
                val spread = (centers.max() - centers.min()) / centerMedian
                if (spread >= 0.40) {
                    notes.add("同口径各方法分歧 ${pctOf(spread)}，说明盈利波动大或可比性差，合理区间参考价值下降")
                }
            }
        }
        return low to high
    }

    /**
     * 盈利在下滑时，历史估值分位算出来的区间要打折看。
     *
     * 分位的分母是「当前 EPS」，盈利掉了分位自然显得低，看着像「历史上从来没这么便宜」——
     * 这恰恰是典型的估值陷阱，必须点名。
     */
    private fun addGrowthNotes(quarters: List<QuarterPoint>, notes: MutableList<String>) {
        val yoy = quarters.mapNotNull { it.netProfitYoy }.takeLast(2)
        val latest = yoy.lastOrNull() ?: return
        val bothFalling = yoy.size == 2 && yoy.all { it < 0 }
        when {
            bothFalling -> notes.add(
                "最近两季归母净利同比都是负的（${signedPct(yoy[0])}、${signedPct(yoy[1])}），" +
                    "盈利在往下走，历史估值分位看着便宜可能是陷阱"
            )
            latest < 0 -> notes.add("最近一季归母净利同比 ${signedPct(latest)}，盈利出现下滑，历史估值分位的参考价值要打折")
        }
    }

    /** 盈利高增长 + 低 PE 分位，是周期高点最典型的伪装，必须点名。 */
    private fun addTimingNotes(
        input: Input,
        notes: MutableList<String>,
        pePercentile: Double?,
        quarters: List<QuarterPoint>,
        ttm: FinancialPeriod?,
    ) {
        if (pePercentile == null || ttm == null) return
        val profitYoy = quarters.mapNotNull { it.netProfitYoy }.takeLast(4)
        if (profitYoy.size < MIN_YOY_POINTS) return
        val medianYoy = median(profitYoy)
        if (pePercentile <= 30 && medianYoy >= 50) {
            notes.add("当前 PE 处于历史低位，但净利同比 +${num(medianYoy)}%：低 PE 可能是盈利高点撑出来的，先确认是不是周期股")
        }
        if (pePercentile >= 80) {
            notes.add("当前 PE 处于历史 ${num(pePercentile)}% 分位，估值本身不便宜")
        }
    }

    /**
     * 归母和扣非要分开说清楚。
     *
     * 归母净利里可以塞政府补助、卖房卖股权、公允价值变动这些一次性收入，扣非把这些剔掉，
     * 剩下的才是主营到底赚了多少。扣非比例低的时候，用归母算出来的 PE 会显著低估真实估值。
     */
    private fun addDeductNote(
        input: Input,
        notes: MutableList<String>,
        ttm: FinancialPeriod?,
        eps: Double?,
        shares: Double?,
    ) {
        if (ttm == null || eps == null || shares == null || eps <= 0) return
        val deductRatio = ratio(ttm.deductNetProfit, ttm.netProfit) ?: return
        if (deductRatio >= 0.80) return
        val deductEps = ttm.deductNetProfit / shares
        if (deductEps <= 0) {
            notes.add("扣非后 TTM 是亏损的（扣非净利 ${yi(ttm.deductNetProfit)} 亿），现在的利润靠非经常性损益撑着")
            return
        }
        val deductPe = input.price / deductEps
        val reportedPe = input.price / eps
        notes.add(
            "按扣非算 EPS-TTM 只有 ${price(deductEps)} 元（归母口径 ${price(eps)} 元）：" +
                "PE 从 ${num(reportedPe)} 倍变成 ${num(deductPe)} 倍，扣非口径下更贵",
        )
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private fun currentPercentile(history: List<Double>, current: Double?): Double? {
        if (current == null || current <= 0) return null
        val values = history.filter { it > 0 }
        if (values.size < MIN_VALUATION_POINTS) return null
        return values.count { it <= current }.toDouble() / values.size * 100
    }

    /** 线性插值分位；只统计正值（亏损股的负 PE 不能参与分位）。 */
    fun percentile(values: List<Double>, p: Double): Double? {
        val sorted = values.filter { it > 0 }.sorted()
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted[0]
        val index = (sorted.size - 1) * p
        val lower = floor(index).toInt()
        val upper = ceil(index).toInt()
        if (lower == upper) return sorted[lower]
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (index - lower)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return 0.0
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private fun ratio(numerator: Double?, denominator: Double?): Double? {
        if (numerator == null || denominator == null || denominator == 0.0) return null
        return numerator / denominator
    }

    private fun num(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    private fun price(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    private fun pctOf(value: Double): String = String.format(Locale.CHINA, "%.1f%%", value * 100)

    private fun signedPct(value: Double): String = String.format(Locale.CHINA, "%+.1f%%", value)

    /** 金额按「亿」显示；A 股个股的绝对金额几乎都是亿级。 */
    private fun yi(value: Double): String = String.format(Locale.CHINA, "%.2f", value / 100_000_000)
}
