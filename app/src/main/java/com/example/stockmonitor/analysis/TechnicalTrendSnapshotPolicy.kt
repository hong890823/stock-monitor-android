package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar

/** 自选股与持仓股共用的技术趋势快照；综合技术分按同一套四维技术指标计算。 */
data class TechnicalTrendSnapshot(
    val code: String,
    val name: String,
    val asOf: String,
    val technicalScore: Int,
    val trendPoints: Int,
    val structurePoints: Int,
    val momentumPoints: Int,
    val riskPoints: Int,
    val weeklyTrend: SwingTrend,
    val monthlyTrend: SwingTrend,
    val trendWeak: Boolean,
    val allowsAddPosition: Boolean,
    val primaryAdvice: String,
    val adviceReason: String,
    /**
     * 行情与关键价位：持仓页等待盘中分析时也能展示支撑/压力、量比与均线结构，
     * 不用等盘中槽位。旧缓存没有这些字段，缺省 0/null 按暂缺处理。
     */
    val price: Double = 0.0,
    val support: Double = 0.0,
    val resistance: Double = 0.0,
    val ma5: Double = 0.0,
    val ma10: Double = 0.0,
    val ma20: Double = 0.0,
    val ma60: Double = 0.0,
    val volumeRatio: Double? = null,
    val atrPct: Double? = null,
    /** 筹码摘要行（[ChipDistributionPolicy] 获利盘/压力位/近20日浮盈）；旧缓存缺省 null。 */
    val chipLine: String? = null,
    /** 上方套牢密度副行；旧缓存缺省 null。 */
    val chipTrappedLine: String? = null,
    /** 增强动量证据行（红绿柱形态/Squeeze/CMF/Aroon，[EnhancedMomentumPolicy]）；旧缓存缺省 null。 */
    val momentumLine: String? = null,
) {
    val summary: String
        get() = "技术 $technicalScore/100｜周K${weeklyTrend.label}｜月K${monthlyTrend.label}"

    val evidenceSummary: String
        get() = "趋势 $trendPoints/35｜均线结构 $structurePoints/25｜量价动量 $momentumPoints/20｜风险控制 $riskPoints/20"

    val dailyKSummary: String
        get() = "日K看均线排列、价格位置、量价动量与入场结构，不以是否出现买点单独计分"

    val trendScaleSummary: String
        get() = "共5档：向上 > 趋势修复 > 下跌企稳 > 偏弱 > 数据不足（数据不足不代表偏弱）"
}

object TechnicalTrendSnapshotPolicy {
    /**
     * entrySetup / trendAssessment 允许传 null：选股快扫等调用方没有独立评估过的
     * 入场结构或趋势结论时，直接复用 [StockAnalysis.analyze] 自带的默认口径，
     * 避免为拿同一个「技术 X/100」分重复计算一遍。
     */
    fun evaluate(
        history: List<DayBar>,
        code: String,
        name: String,
        asOf: String,
        entrySetup: EntrySetup? = null,
        trendAssessment: TrendAssessment? = null,
        marketRegime: MarketRegime? = null,
        /** 调用方已算好的多周期摘要直接复用，避免与 TrendAssessmentPolicy 重复计算。 */
        cycles: MultiTimeframeSummary? = null,
    ): TechnicalTrendSnapshot {
        val effectiveCycles = cycles ?: MultiTimeframeTrend.evaluate(history, asOf)
        // analyze 只跑一次；调用方传入的 entrySetup/trendAssessment 非空时覆盖默认值，
        // 为空则沿用 analyze 自带的口径，两套来源进同一套四维评分。
        val analyzed = StockAnalysis.analyze(
            history = history,
            code = code,
            name = name,
        )
        val effectiveSetup = entrySetup ?: analyzed.entrySetup
        val effectiveAssessment = trendAssessment ?: analyzed.trendAssessment
        val technical = analyzed.copy(
            entrySetup = effectiveSetup,
            trendAssessment = effectiveAssessment,
        )
        val assessment = TechnicalAssessmentPolicy.evaluate(
            technical = technical,
            weeklyTrend = effectiveCycles.weekTrend,
            monthlyTrend = effectiveCycles.monthTrend,
            marketRegime = marketRegime,
        )
        val allowsAddPosition = effectiveAssessment?.allowsAddPosition == true && !effectiveSetup.trendWeak &&
            // 塌陷期不给加仓资格：与 primaryAdvice 的环境压制同一口径。
            marketRegime != MarketRegime.COLLAPSE
        return TechnicalTrendSnapshot(
            code = code,
            name = name,
            asOf = asOf,
            technicalScore = assessment.score,
            trendPoints = assessment.trendPoints,
            structurePoints = assessment.structurePoints,
            momentumPoints = assessment.momentumPoints,
            riskPoints = assessment.riskPoints,
            weeklyTrend = effectiveCycles.weekTrend,
            monthlyTrend = effectiveCycles.monthTrend,
            trendWeak = effectiveSetup.trendWeak,
            allowsAddPosition = allowsAddPosition,
            primaryAdvice = assessment.primaryAdvice,
            adviceReason = assessment.reason,
            price = analyzed.price,
            support = analyzed.support,
            resistance = analyzed.resistance,
            ma5 = analyzed.ma5,
            ma10 = analyzed.ma10,
            ma20 = analyzed.ma20,
            ma60 = analyzed.ma60,
            volumeRatio = analyzed.volumeRatio,
            atrPct = analyzed.volatility.atrPct,
            chipLine = analyzed.chipDistribution?.summaryLine,
            chipTrappedLine = analyzed.chipDistribution?.trappedLine,
            momentumLine = analyzed.momentumDetail?.summaryLine,
        )
    }
}
