package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import kotlin.math.roundToInt
import java.util.Locale

enum class TechnicalAssessmentLevel(val label: String) {
    STRONG("强势可参与"),
    GOOD("较强待确认"),
    NEUTRAL("中性观察"),
    WEAK("偏弱谨慎"),
    RISK("风险优先"),
}

data class TechnicalAssessment(
    val score: Int,
    val trendPoints: Int,
    val structurePoints: Int,
    val momentumPoints: Int,
    val riskPoints: Int,
    val level: TechnicalAssessmentLevel,
    val primaryAdvice: String,
    val reason: String,
    val normalDivergenceLevel: NormalDivergenceLevel = NormalDivergenceLevel.MISSING,
    val normalDivergenceReason: String = "",
) {
    val componentSummary: String
        get() = "趋势 $trendPoints/35｜均线结构 $structurePoints/25｜量价动量 $momentumPoints/20｜风险控制 $riskPoints/20"
}

/**
 * 自选股、持仓股统一使用的 0–100 技术综合评分。
 *
 * 趋势占 35 分，均线与价格结构占 25 分，量价与动量占 20 分，入场结构与技术风险占 20 分。
 * PE/PB 不在这里重复计分，由独立估值/风险因子表达。
 */
object TechnicalAssessmentPolicy {
    /**
     * 无持仓语境的统一技术建议：工具页卡片、完整报告与 AI 工具共用，
     * 与持仓/自选的 [primaryAdvice] 同一口径——替换 [TechnicalResult.action] 那套
     * score 四档的一句话模板（「技术面偏强：不建议追高…」不带任何价位数据）。
     */
    fun unifiedAdviceFor(
        result: TechnicalResult,
        history: List<DayBar>,
        marketRegime: MarketRegime? = null,
    ): String {
        val cycles = MultiTimeframeTrend.evaluate(history, result.dataDate)
        // 与持仓页/趋势快照同一口径：大盘塌陷时不能因为个股强就给“可分批参与”。
        return evaluate(result, cycles.weekTrend, cycles.monthTrend, marketRegime).primaryAdvice
    }

    fun evaluate(
        technical: TechnicalResult,
        weeklyTrend: SwingTrend,
        monthlyTrend: SwingTrend,
        marketRegime: MarketRegime? = null,
    ): TechnicalAssessment {
        val trendPoints = trendPoints(technical, weeklyTrend, monthlyTrend)
        val structurePoints = structurePoints(technical)
        val momentumPoints = momentumPoints(technical)
        val normalDivergence = NormalDivergencePolicy.evaluateDaily(
            NormalDivergenceDailyInput(
                price = technical.price,
                ma5 = technical.ma5,
                ma20 = technical.ma20,
                priceLocation = technical.priceContext?.location,
                atrPct = technical.volatility.atrPct,
                volumeRatio = technical.volumeRatio,
                priceChangePct = technical.priceChange,
                topDivergence = technical.divergence.state == DivergenceState.TOP,
                trendWeak = technical.entrySetup.trendWeak,
                supportBroken = technical.support > 0 && technical.price < technical.support,
                majorTrend = technical.trendAssessment?.major,
                weeklyTrend = weeklyTrend,
                monthlyTrend = monthlyTrend,
            ),
        )
        val riskPoints = riskPoints(technical, normalDivergence)
        val rawScore = trendPoints + structurePoints + momentumPoints + riskPoints
        val broken = technical.entrySetup.trendWeak ||
            technical.trendAssessment?.major == MajorTrend.DOWN ||
            technical.trendAssessment?.shortTerm == ShortTermTrend.DOWN ||
            (technical.support > 0 && technical.price < technical.support) ||
            normalDivergence.level == NormalDivergenceLevel.CONFIRMED_BREAK
        val rapidHeat = technical.priceChange >= 5.0 &&
            technical.volumeRatio?.let { it >= 1.8 } == true
        val hotRisk = technical.highPosition?.riskPriority == true ||
            technical.divergence.state == DivergenceState.TOP ||
            technical.rsi?.let { it > 78 } == true ||
            (rapidHeat && normalDivergence.level != NormalDivergenceLevel.NORMAL)
        val score = when {
            broken -> minOf(rawScore, 45)
            hotRisk -> minOf(rawScore, 55)
            else -> rawScore
        }.coerceIn(0, 100)
        val primaryAdvice = buildAdvice(
            technical = technical,
            score = score,
            broken = broken,
            hotRisk = hotRisk,
            weeklyTrend = weeklyTrend,
            monthlyTrend = monthlyTrend,
            normalDivergence = normalDivergence,
            marketRegime = marketRegime,
        )
        val level = when {
            broken -> TechnicalAssessmentLevel.RISK
            score >= 80 -> TechnicalAssessmentLevel.STRONG
            score >= 65 -> TechnicalAssessmentLevel.GOOD
            score >= 45 -> TechnicalAssessmentLevel.NEUTRAL
            else -> TechnicalAssessmentLevel.WEAK
        }
        return TechnicalAssessment(
            score = score,
            trendPoints = trendPoints,
            structurePoints = structurePoints,
            momentumPoints = momentumPoints,
            riskPoints = riskPoints,
            level = level,
            primaryAdvice = primaryAdvice,
            reason = buildString {
                append("日/周/月趋势")
                if (broken) append("中出现破位或明确转弱，综合分已按风险规则封顶")
                else if (hotRisk) append("尚可但短线过热或高位风险偏高，综合分已按风险规则封顶")
                else append("与均线、量价和技术风险综合判断")
                // 原先 NORMAL 与非 MISSING 两个分支追加的文本完全相同，合并为「非缺失即附注」。
                if (normalDivergence.level != NormalDivergenceLevel.MISSING) {
                    append("；${normalDivergence.reason}")
                }
            },
            normalDivergenceLevel = normalDivergence.level,
            normalDivergenceReason = normalDivergence.reason,
        )
    }

    private fun buildAdvice(
        technical: TechnicalResult,
        score: Int,
        broken: Boolean,
        hotRisk: Boolean,
        weeklyTrend: SwingTrend,
        monthlyTrend: SwingTrend,
        normalDivergence: NormalDivergenceDecision,
        marketRegime: MarketRegime? = null,
    ): String {
        val price = formatPrice(technical.price)
        val support = formatPrice(technical.support)
        val resistance = formatPrice(technical.resistance)
        val ma5 = formatPrice(technical.ma5)
        val ma20 = formatPrice(technical.ma20)
        val ma60 = formatPrice(technical.ma60)
        val nearSupport = technical.support > 0 && technical.price <= technical.support * 1.02
        val nearResistance = technical.resistance > 0 && technical.price >= technical.resistance * 0.98
        // 「回踩」话术的参考锚位：强势浅锚（MA5/突破位）、中性 MA20（见 PullbackAnchorPolicy）。
        // 20 日低点 $support 只用在破位/位置话术——强势股回踩到那里趋势已经结束了。
        val pullbackRef = technical.pullbackRef
        val trendText = "周K${weeklyTrend.label}、月K${monthlyTrend.label}"
        val movingAverageTrend = MovingAverageTrendPolicy.evaluate(
            price = technical.price,
            ma5 = technical.ma5,
            ma20 = technical.ma20,
            ma60 = technical.ma60,
        )
        return when {
            broken && technical.support > 0 && technical.price < technical.support ->
                "减仓或退出：现价 $price 已跌破支撑 $support，先控制仓位；重新站稳后再评估是否接回。"
            technical.entrySetup.trendWeak ||
                technical.trendAssessment?.major == MajorTrend.DOWN ||
                technical.trendAssessment?.shortTerm == ShortTermTrend.DOWN -> {
                // 弱趋势不再只报两个均线数：带上现价距 MA20/支撑的百分比与量比，
                // 让「暂不补仓」能看出是深跌未止还是贴近支撑蓄势。
                val context = weakTrendContext(technical)
                    .ifEmpty { "短线关键均线看 MA20（$ma20），趋势参考 MA60（$ma60）" }
                if (weeklyTrend == SwingTrend.STABILIZING || monthlyTrend == SwingTrend.STABILIZING) {
                    "暂不补仓：${trendText}出现下跌企稳迹象；$context，等待连续站稳后再评估。"
                } else {
                    "暂不补仓：${trendText}；$context，等待重新站稳后再评估。"
                }
            }
            movingAverageTrend == MovingAverageTrend.STRONG_BEARISH ->
                "暂不补仓：${movingAverageTrend.label}，现价 $price 低于 MA5（$ma5）、MA20（$ma20）和 MA60（$ma60）；反弹优先看压力 $resistance，失守支撑 $support 时继续控制仓位。"
            movingAverageTrend == MovingAverageTrend.MID_WEAK_SHORT_WEAK ->
                "短中期均线偏弱：现价 $price 未站上 MA5（$ma5），MA20（$ma20）仍低于或贴近 MA60（$ma60）；不把反弹当加仓依据，先观察能否站回 MA20，支撑参考 $support。"
            movingAverageTrend == MovingAverageTrend.SHORT_BULLISH_MID_WEAK ||
                movingAverageTrend == MovingAverageTrend.SHORT_REBOUND_BELOW_MA20 ->
                if (pullbackRef != null) {
                    "短期反弹观察：${movingAverageTrend.label}，中期尚未转强；接近压力 $resistance 不追入，只有回踩 $pullbackRef 附近企稳并站上 MA20（$ma20）后再评估。"
                } else {
                    "短期反弹观察：${movingAverageTrend.label}，中期尚未转强；接近压力 $resistance 不追入，趋势偏弱不给回踩买点参考，站上 MA20（$ma20）企稳后再评估。"
                }
            movingAverageTrend == MovingAverageTrend.MID_BULLISH_SHORT_PULLBACK ->
                if (nearSupport) {
                    "中期持有、短期防守：MA20（$ma20）仍在 MA60（$ma60）上方，现价靠近支撑 $support；等待重新站上 MA5（$ma5）并确认承接，未确认前不追加仓位。"
                } else {
                    "中期趋势未破、短期正在回踩：MA20（$ma20）仍在 MA60（$ma60）上方；先等价格重新站上 MA5（$ma5），接近压力 $resistance 不追高。"
                }
            normalDivergence.level == NormalDivergenceLevel.NORMAL &&
                normalDivergence.line == NormalDivergenceLine.MA5 &&
                !hotRisk ->
                "正常分歧：股价处于高位但守住 MA5（${normalDivergence.linePrice?.let(::formatPrice) ?: "暂缺"}），量价仍有配合；不视为破位，回踩不破 MA5 再观察。"
            hotRisk -> {
                // 回踩观察的参照是分档锚，不是20日低点：真回踩到20日低点就不是「回踩确认」而是趋势死亡。
                val pullbackHold = pullbackRef?.let { "回踩不破 $it 再评估。" }
                    ?: "偏弱口径不给回踩买点参考；不破前低 $support 也只作观察。"
                if (technical.highPosition?.riskPriority == true) {
                    "等待回踩确认：当前处于高位风险区，${trendText}尚可但不追高；$pullbackHold"
                } else {
                    "等待回踩确认：短线偏热或量价波动放大，${trendText}未完全消化，$pullbackHold"
                }
            }
            technical.trendAssessment?.shortTerm == ShortTermTrend.RANGE ->
                "持有观察：价格仍在区间内，压力 $resistance、支撑 $support 之间没有形成一致方向，不追高。"
            // 大盘塌陷期不因为个股强就给加仓类建议（口径同 PortfolioAnalysis 环境改写）：
            // 塌陷初期一律暂缓补仓；个股自身出现企稳/反弹迹象（筑底或周月企稳）才允许
            // 考虑小仓做T降成本——不是加仓，站稳 MA20 后再评估。
            marketRegime == MarketRegime.COLLAPSE -> when {
                technical.trendAssessment?.major == MajorTrend.BOTTOMING ||
                    weeklyTrend == SwingTrend.STABILIZING || monthlyTrend == SwingTrend.STABILIZING ->
                    "大盘塌陷、个股有企稳迹象：${trendText}；仅可考虑小仓做T降成本，不追加仓位，站稳 MA20（$ma20）后再评估。"
                else ->
                    "暂缓补仓：大盘处塌陷期，先控制仓位不加仓；等大盘企稳（站回 MA20）再评估做T与接回。"
            }
            score >= 70 && technical.trendAssessment?.allowsAddPosition == true &&
                movingAverageTrend == MovingAverageTrend.STRONG_BULLISH ->
                when {
                    technical.entrySetup.type != EntrySetupType.NONE &&
                        technical.entrySetup.confirmed &&
                        technical.entrySetup.canBuy &&
                        technical.entrySetup.volumeConfirmed ->
                        "可分批参与：${trendText}向上，短中期均线多头排列，入场结构和量价已确认；首次仓位宜轻，" +
                            (pullbackRef?.let { "回踩不破 $it 再加。" } ?: "回踩加仓参考暂缺，等站稳后再评估。")
                    technical.entrySetup.type != EntrySetupType.NONE &&
                        technical.entrySetup.confirmed && !technical.entrySetup.canBuy ->
                        "入场结构仅作观察：${technical.entrySetup.label}已确认，但当前规则不允许据此开仓；" +
                            (pullbackRef?.let { "等待回踩 $it 企稳或形成可买结构。" }
                                ?: "等待站上 MA20（$ma20）企稳或形成可买结构。")
                    technical.entrySetup.type != EntrySetupType.NONE ->
                        "观察入场结构：${trendText}偏强，短中期均线多头排列，但突破/回踩尚未完成确认，等待确认后再分批。"
                    nearResistance ->
                        "等待突破确认：${trendText}偏强且接近压力 $resistance，站稳压力后再评估，不直接追入。"
                    nearSupport ->
                        "可小仓观察：${trendText}偏强且靠近支撑 $support，等回踩企稳和量能配合后再分批。"
                    else ->
                        "可关注分批参与：${trendText}和短中期均线均配合，但入场结构尚未确认，先以小仓观察为主。"
                }
            else ->
                "持有观察：${trendText}与量价尚未形成高置信度入场条件，等待方向进一步确认。"
        }
    }

    private fun formatPrice(value: Double): String =
        if (value.isFinite() && value > 0) String.format(Locale.CHINA, "%.2f", value) else "暂缺"

    /** 弱趋势分支的位置上下文：现价距 MA20/MA60、距支撑的百分比与量比；数据缺失的部分自动省略。 */
    private fun weakTrendContext(technical: TechnicalResult): String = buildString {
        if (!technical.price.isFinite() || technical.price <= 0) return@buildString
        val maRefs = buildList {
            if (technical.ma20.isFinite() && technical.ma20 > 0) {
                add("MA20（${formatPrice(technical.ma20)}）${distancePct(technical.price, technical.ma20)}")
            }
            if (technical.ma60.isFinite() && technical.ma60 > 0) {
                add("MA60（${formatPrice(technical.ma60)}）${distancePct(technical.price, technical.ma60)}")
            }
        }
        if (maRefs.isNotEmpty()) append("现价 ${formatPrice(technical.price)} 距 ${maRefs.joinToString("、")}")
        if (technical.support.isFinite() && technical.support > 0 && technical.price >= technical.support) {
            if (isNotEmpty()) append("，")
            append("距支撑 ${formatPrice(technical.support)} ${distancePct(technical.price, technical.support)}")
        }
        technical.volumeRatio?.takeIf { it.isFinite() && it > 0 }?.let {
            if (isNotEmpty()) append("，")
            append(String.format(Locale.CHINA, "量比 %.2f", it))
        }
    }

    private fun distancePct(price: Double, reference: Double): String =
        String.format(Locale.CHINA, "%+.1f%%", (price / reference - 1) * 100)

    private fun trendPoints(
        technical: TechnicalResult,
        weeklyTrend: SwingTrend,
        monthlyTrend: SwingTrend,
    ): Int {
        val daily = when (technical.trendAssessment?.shortTerm) {
            ShortTermTrend.EXTENDED -> 15
            ShortTermTrend.UP -> 14
            ShortTermTrend.REBOUND -> 10
            ShortTermTrend.RANGE -> 6
            ShortTermTrend.DOWN, ShortTermTrend.MISSING, null -> 0
        }
        return daily + cyclePoints(weeklyTrend, 10) + cyclePoints(monthlyTrend, 10)
    }

    private fun cyclePoints(trend: SwingTrend, max: Int): Int = when (trend) {
        SwingTrend.UP -> max
        SwingTrend.REPAIR -> (max * 0.7).roundToInt()
        SwingTrend.STABILIZING -> (max * 0.5).roundToInt()
        SwingTrend.WEAK, SwingTrend.MISSING -> 0
    }

    private fun structurePoints(technical: TechnicalResult): Int {
        val movingAverageSignals = listOf(
            technical.price >= technical.ma5,
            technical.ma5 >= technical.ma10,
            technical.ma10 >= technical.ma20,
            technical.ma20 >= technical.ma60,
        ).count { it } * 3
        val supportPoints = if (technical.support > 0 && technical.price >= technical.support) 4 else 0
        val rangePoints = when {
            technical.resistance <= technical.support -> 0
            technical.price > technical.resistance -> 3
            technical.price >= (technical.support + technical.resistance) / 2.0 -> 3
            else -> 1
        }
        val entryPoints = listOf(
            technical.entrySetup.type != EntrySetupType.NONE,
            technical.entrySetup.confirmed,
            technical.entrySetup.volumeConfirmed,
        ).count { it } * 2
        return (movingAverageSignals + supportPoints + rangePoints + entryPoints).coerceIn(0, 25)
    }

    private fun momentumPoints(technical: TechnicalResult): Int {
        val macdPoints = listOf(
            technical.macd.hist > 0,
            technical.macd.dif > technical.macd.dea,
            technical.macd.hist > technical.macd.previousHist,
        ).count { it } * 2
        val rsiPoints = when (technical.rsi) {
            null -> 0
            in 45.0..70.0 -> 3
            in 35.0..78.0 -> 1
            else -> 0
        }
        val kdjPoints = (if (technical.kdj.k > technical.kdj.d) 2 else 0) +
            (if (technical.kdj.j in 20.0..95.0) 2 else 0)
        val adx = technical.trendStrength.adx
        val plusDi = technical.trendStrength.plusDi
        val minusDi = technical.trendStrength.minusDi
        val positiveDirection = plusDi != null && minusDi != null && plusDi > minusDi
        val diPoints = when {
            adx != null && adx >= 20 && positiveDirection -> 5
            adx != null && positiveDirection -> 3
            else -> 0
        }
        val obvPoints = (if (technical.obvChange > 0) 1 else 0) +
            (if (technical.divergence.state == DivergenceState.BOTTOM ||
                technical.divergence.state == DivergenceState.SYNC
            ) 1 else 0)
        return (macdPoints + rsiPoints + kdjPoints + diPoints + obvPoints).coerceIn(0, 20)
    }

    private fun riskPoints(
        technical: TechnicalResult,
        normalDivergence: NormalDivergenceDecision,
    ): Int {
        var deduction = 0
        if (technical.entrySetup.trendWeak ||
            technical.trendAssessment?.major == MajorTrend.DOWN ||
            technical.trendAssessment?.shortTerm == ShortTermTrend.DOWN
        ) deduction += 10
        if (technical.support > 0 && technical.price < technical.support) deduction += 20
        if (technical.highPosition?.riskPriority == true) deduction += 7
        if (technical.highPosition?.phase in setOf(
                HighPositionPhase.EXTENDED_WATCH,
                HighPositionPhase.DISTRIBUTION_RISK,
                HighPositionPhase.UNCERTAIN,
            )
        ) deduction += 4
        if (technical.divergence.state == DivergenceState.TOP) deduction += 4
        technical.volatility.atrPct?.let {
            deduction += when {
                it > 8.0 -> 3
                it > 5.0 -> 1
                else -> 0
            }
        }
        if (technical.rsi?.let { it > 78 } == true) deduction += 3
        if (technical.macd.hist < 0 && technical.macd.dif < technical.macd.dea) deduction += 3
        if (technical.priceChange >= 5.0 &&
            technical.volumeRatio?.let { it >= 1.8 } == true &&
            technical.resistance > 0 &&
            technical.price >= technical.resistance * 0.99 &&
            normalDivergence.level != NormalDivergenceLevel.NORMAL
        ) deduction += 3
        return (20 - deduction).coerceIn(0, 20)
    }
}
