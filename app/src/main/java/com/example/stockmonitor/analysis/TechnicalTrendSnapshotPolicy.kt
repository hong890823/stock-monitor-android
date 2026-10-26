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
    fun evaluate(
        history: List<DayBar>,
        code: String,
        name: String,
        asOf: String,
        entrySetup: EntrySetup,
        trendAssessment: TrendAssessment,
    ): TechnicalTrendSnapshot {
        val cycles = MultiTimeframeTrend.evaluate(history, asOf)
        val technical = StockAnalysis.analyze(
            history = history,
            code = code,
            name = name,
        ).copy(
            entrySetup = entrySetup,
            trendAssessment = trendAssessment,
        )
        val assessment = TechnicalAssessmentPolicy.evaluate(
            technical = technical,
            weeklyTrend = cycles.weekTrend,
            monthlyTrend = cycles.monthTrend,
        )
        val allowsAddPosition = trendAssessment.allowsAddPosition && !entrySetup.trendWeak
        return TechnicalTrendSnapshot(
            code = code,
            name = name,
            asOf = asOf,
            technicalScore = assessment.score,
            trendPoints = assessment.trendPoints,
            structurePoints = assessment.structurePoints,
            momentumPoints = assessment.momentumPoints,
            riskPoints = assessment.riskPoints,
            weeklyTrend = cycles.weekTrend,
            monthlyTrend = cycles.monthTrend,
            trendWeak = entrySetup.trendWeak,
            allowsAddPosition = allowsAddPosition,
            primaryAdvice = assessment.primaryAdvice,
            adviceReason = assessment.reason,
        )
    }
}
