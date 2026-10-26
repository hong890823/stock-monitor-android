package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Portfolio
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.RowSnapshot
import com.example.stockmonitor.core.resolvedAvailableCapital
import java.util.Locale
import kotlin.math.abs

/** 持仓信号，对应 portfolio_analysis.evaluate_position。 */
data class PositionResult(
    val code: String,
    val name: String,
    val quantity: Double,
    val cost: Double,
    val price: Double,
    /** 当日涨跌幅（百分数）；持仓信号与量价标签都依赖它，列表里也要能看到。 */
    val changePct: Double,
    val marketValue: Double,
    val positionPct: Double?,
    val signal: String,
    val action: String,
    val support: Double,
    val resistance: Double,
    val ma20: Double,
    val ma5: Double = 0.0,
    val entrySetup: EntrySetup,
    val suggestedQuantity: Double,
    val score: Int,
    val volumeRatio: Double,
    /** 与自选股 / 选股列表同一套量价标签（含价格位置口径），供报告里直接展示。 */
    val volumeSignal: VolumePriceSignal,
    val opening: OpeningStructure,
    val volatility: Volatility,
    val trendStrength: TrendStrength,
    val relativeStrength: Double?,
    val analyzedAt: String,
    /** 最近一次基本面健康判断（按需补拉的缓存）；为空表示还没拉过。 */
    val fundamentalHealth: FundamentalHealth? = null,
    /**
     * 「较昨日」的变化叙述。没有昨日快照时为 null（报告写「无昨日快照」，
     * 不硬编成「无变化」——没数据和没变化是两回事）。
     */
    val changeNote: String? = null,
    /**
     * 板块上下文一句话（所属行业 + 板块今日涨跌 / 状态）。归属查不到时写「板块归属暂缺」，
     * 不猜——「贵州板块跌了」不代表你这只股的行业在跌。
     */
    val sectorNote: String? = null,
    /** 事件后蓄势线索（见 EventCoil）：利好落地 + 缩量回踩的观察提示，属概率线索。 */
    val eventCoil: EventCoil.Signal? = null,
    val exitDecision: HoldingExitDecision? = null,
    val notificationExitDecision: HoldingExitDecision? = null,
    val trendAssessment: TrendAssessment? = null,
    val boxRange: BoxRangeAssessment? = null,
    val highPositionEvidence: HighPositionEvidence? = null,
    val highPosition: HighPositionDecision? = null,
    val rhythm: AdaptiveRhythmDecision? = null,
    val impulse: ImpulseContext? = null,
    val fundFlowScoreDelta: Int = 0,
    val analysisDate: String = "",
    val fiveDayNoNewLow: Boolean? = null,
    val closesBelowMa20: Int = 0,
) {
    val intradayScore: Int
        get() = score - fundFlowScoreDelta
}

object PortfolioAnalysis {

    fun evaluate(
        position: Position,
        technical: TechnicalResult,
        live: Quote?,
        portfolio: Portfolio,
        fundamentalHealth: FundamentalHealth? = null,
        /**
         * 分析环境（大盘 / 板块 / 昨日快照）。缺省 [EnvContext.NONE] 表示不改写建议——
         * 但那样熊市里会给出相反的结论，生产链路（MonitorService）必须把真实环境传进来。
         */
        envContext: EnvContext = EnvContext.NONE,
        breakObservation: HoldingBreakObservation? = null,
        holdingTContext: HoldingTContext? = null,
        holdingTObservation: HoldingTObservation? = null,
        eventCoil: EventCoil.Signal? = null,
    ): PositionResult {
        val quantity = position.quantity
        val cost = position.cost
        val price = live?.price ?: technical.price
        val totalCapital = portfolio.totalCapital
        val maxPositionPct = portfolio.maxPositionPct
        val initialEntryPct = portfolio.initialEntryPct
        val marketValue = quantity * price
        val positionPct = if (totalCapital > 0) marketValue / totalCapital * 100 else null
        val support = breakObservation?.support ?: technical.support
        val resistance = technical.resistance
        val ma20 = technical.ma20
        val score = technical.score
        val volumeRatio = technical.volumeRatio ?: 0.0
        val changePct = if (live != null) live.changePct else technical.priceChange
        val volumeSignal = VolumePrice.of(technical.volumeRatio, changePct, technical.priceContext)
        val nearSupport = support > 0 && price in (support * 0.97)..(support * 1.03)
        /** 现价距 20 日压力还有多少（%）；无压力数据时为 NaN。 */
        val toResistancePct = if (resistance > 0 && price > 0) (resistance / price - 1) * 100 else Double.NaN
        val supportLabel = priceOrLabel(support, "支撑位")
        val resistanceLabel = priceOrLabel(resistance, "压力位")
        val ma20Label = priceOrLabel(ma20, "MA20")
        val breakout = technical.entrySetup.type == EntrySetupType.BREAKOUT && technical.entrySetup.confirmed
        val pullback = technical.entrySetup.type == EntrySetupType.M20_PULLBACK && technical.entrySetup.confirmed
        val trendWeak = technical.entrySetup.trendWeak
        val structureEvidence = BreakoutRiskEvidence(
            price = price,
            dailyHighPrice = null,
            breakoutReference = null,
            changePct = changePct,
            volumeRatio = technical.volumeRatio,
            atrPct = technical.volatility.atrPct,
            continuation = true,
            priceLocation = technical.priceContext?.location,
            ma5 = technical.ma5.takeIf { it.isFinite() && it > 0.0 },
            ma20 = technical.ma20.takeIf { it.isFinite() && it > 0.0 },
            biasFromMa5Pct = technical.ma5.takeIf { it.isFinite() && it > 0.0 }
                ?.let { (price / it - 1.0) * 100.0 },
        )
        val structureRetained = technical.entrySetup.ma20SlopePct?.let { it.isFinite() && it > 0.0 } == true &&
            !trendWeak &&
            technical.trendAssessment?.riskOnly == false &&
            technical.trendAssessment?.major != MajorTrend.DOWN &&
            technical.highPosition?.riskPriority != true &&
            !BreakoutRiskPolicy.riskLineBroken(structureEvidence)
        val structureRiskLine = if (BreakoutRiskPolicy.useMa5Stop(structureEvidence)) "MA5" else "M20"
        val structureRiskPrice = (if (structureRiskLine == "MA5") technical.ma5 else technical.ma20)
            .takeIf { it.isFinite() && it > 0.0 }
            ?.let { it * (1 - BreakoutRiskPolicy.LINE_BREAK_TOLERANCE_PCT / 100.0) }
        val structureRiskText = structureRiskPrice?.let { "$structureRiskLine ${priceText(it)}" } ?: structureRiskLine
        val recentHighs = technical.highPositionEvidence?.recentHighs.orEmpty()
        val lowerHighs = recentHighs.size >= 3 &&
            recentHighs.zipWithNext().all { (previous, current) -> current <= previous } &&
            recentHighs.last() < recentHighs.first()
        val volumeWithoutProgress = technical.volumeRatio?.let {
            it >= VolumeRatio.EXPAND_THRESHOLD && abs(changePct) <= 1.0 &&
                (technical.highPositionEvidence?.gain5Pct ?: 0.0) <= maxOf(1.0, (technical.volatility.atrPct ?: 0.0) * 0.5)
        } == true
        val rhythm = AdaptiveRhythmPolicy.evaluate(
            AdaptiveRhythmEvidence(
                marketTurnoverRatio = envContext.marketRisk?.turnover?.sameTimeRatio,
                marketChangePct = envContext.market.changePct,
                industryBreadth = envContext.industryBreadth,
                conceptBreadth = envContext.conceptBreadth,
                marketRegime = envContext.market.regime,
                shortTermTrend = technical.trendAssessment?.shortTerm,
                majorTrend = technical.trendAssessment?.major,
                impulse = technical.impulse,
                atrPct = technical.volatility.atrPct,
                volumeRatio = technical.volumeRatio,
                changePct = changePct,
                biasFromMa5Pct = technical.ma5.takeIf { it.isFinite() && it > 0.0 }
                    ?.let { (price / it - 1.0) * 100.0 },
                lowerHighs = lowerHighs,
                volumeWithoutProgress = volumeWithoutProgress,
            ),
        )
        val suggestedQuantity = TrendEntryStrategy.suggestedQuantity(
            price = price,
            stopPrice = technical.entrySetup.stopPrice,
            totalCapital = totalCapital,
            initialEntryPct = initialEntryPct,
            maxPositionPct = maxPositionPct,
        )

        val exitSupport = breakObservation?.support ?: support
        val exitInput = HoldingExitPolicy.Input(
            price, exitSupport, quantity, cost, technical.volatility.atrPct, technical.volumeRatio,
            trendWeak = trendWeak, confirmed = breakObservation?.confirmed == true,
            exitPrice = breakObservation?.exitPrice, fundamentalAlert = fundamentalHealth?.level == HealthLevel.ALERT,
            priceContext = technical.priceContext,
            changePct = changePct, holdingTContext = holdingTContext, holdingTObservation = holdingTObservation,
            synchronizedWeakness = envContext.market.regime in setOf(MarketRegime.WEAK, MarketRegime.COLLAPSE) &&
                envContext.sector?.state?.regime in setOf(MarketRegime.WEAK, MarketRegime.COLLAPSE),
            gapDown = live?.let { it.open.isFinite() && it.open > 0 && it.open < exitSupport * (1 - HoldingExitPolicy.BREAK_TOLERANCE) &&
                it.prevClose.isFinite() && it.prevClose >= exitSupport } == true,
            positionPct = positionPct,
        )
        val exitDecision = HoldingExitPolicy.evaluate(exitInput)
        val notificationExitDecision = HoldingExitPolicy.evaluate(exitInput.copy(
            fundamentalAlert = FundamentalHealthPolicy.forIntraday(fundamentalHealth, TimeUtils.todayText())?.level == HealthLevel.ALERT,
        ))
        val signal: String
        val action: String
        if (quantity > 0) {
            // 当天事实先摆出来：话术嵌的是实际数字，所以天天不一样，
            // 而不是换汤不换药的模板（这正是「永远同一套话术」的根因）。
            val facts = dayFacts(changePct, volumeRatio, volumeSignal, price, support, resistance)
            // 高于支撑多少（正数 = 在支撑上方）；距压力多少（正数 = 压力还在上面）。
            val aboveSupportPct = if (support > 0 && price > 0) (price / support - 1) * 100 else Double.NaN
            val toResistancePct =
                if (resistance > 0 && price > 0) (resistance / price - 1) * 100 else Double.NaN
            when {
                // 破位是风险事件，口径优先于「趋势转弱」的通用观察话术（深套股几乎都跌破 M20，
                // 否则破位信息会被转弱分支盖住；转弱信息作为前缀补上。
                exitDecision != null -> {
                    signal = "减仓风险"
                    val breakAction = exitDecision.action
                    action = if (trendWeak) {
                        "$facts。已有效跌破 M20（$ma20Label）、趋势转弱，不加仓、不摊平。$breakAction"
                    } else {
                        "$facts。$breakAction"
                    } + envNote(envContext, changePct)
                }
                trendWeak -> {
                    signal = "趋势转弱观察"
                    action = "$facts。已有效跌破 M20（$ma20Label）或出现失败结构，趋势转弱。" +
                        "不加仓、不摊平，站回 $ma20Label 并企稳前不动。" + envNote(envContext, changePct)
                }
                breakout && score >= 2 -> {
                    signal = "持有/止盈观察"
                    val take = if (structureRetained) {
                        "MA20仍上行，回踩未破坏结构，保留观察仓等待二波；已有浮盈不是卖出理由。" +
                            "只有跌破 $structureRiskText 或出现高位派发、突破下杀等结构风险，才按风险减仓或退出"
                    } else {
                        "结构支撑不足，按破位和高位风险规则处理，不因已有浮盈直接卖出"
                    }
                    action = "$facts。突破确认，继续持有；$take。" + envNote(envContext, changePct)
                }
                else -> {
                    signal = "持有观察"
                    val place = when {
                        !aboveSupportPct.isNaN() && aboveSupportPct <= 3.0 -> "贴着支撑"
                        !toResistancePct.isNaN() && toResistancePct <= 3.0 -> "贴着压力"
                        else -> "在区间中段"
                    }
                    val verdict = when {
                        structureRetained ->
                            "MA20仍上行，回踩未破坏结构，保留观察仓等待二波"
                        volumeSignal.tone == Tone.CAUTION && place == "贴着支撑" ->
                            "量价偏警示（${volumeSignal.label}），先别急着加，看能不能守住"
                        volumeSignal.tone == Tone.CAUTION ->
                            "量价偏警示（${volumeSignal.label}），不加仓"
                        place == "贴着支撑" -> "贴着支撑，不动——别在支撑位慌着卖"
                        else -> "不动"
                    }
                    val triggers = buildList {
                        if (structureRetained) {
                            add("已有浮盈不是卖出理由")
                            add("跌破 $structureRiskText 或结构失效再谈减仓退出")
                        } else {
                            if (support > 0) add("跌破 ${priceText(support)} 再谈减")
                            if (resistance > 0) add("站稳 ${priceText(resistance)} 再谈加")
                        }
                    }
                    action = "$facts——位置$place，$verdict。" +
                        triggers.joinToString("；") + "。" + envNote(envContext, changePct)
                }
            }
        } else if (pullback && technical.entrySetup.canBuy && score >= 2) {
            signal = "买入观察"
            action = entryAction(
                setup = technical.entrySetup,
                suggestedQuantity = suggestedQuantity,
                initialEntryPct = initialEntryPct,
                maxPositionPct = maxPositionPct,
            ) + envNote(envContext, changePct)
        } else if (breakout) {
            signal = "等待"
            action = "突破结构已确认，但历史回测显示突破后追入表现不稳定，仅作观察；等回踩 M20 缩量止跌后再评估。" +
                envNote(envContext, changePct)
        } else {
            signal = "等待"
            // 同为「等待」，成因不同就该给不同的下一步，不能每只股票印同一句话。
            action = when {
                score >= 2 && volumeSignal == VolumePriceSignal.EXPAND_UP && toResistancePct <= 2 ->
                    "已放量走强、评分尚可，且逼近压力 $resistanceLabel（还差 ${pct(toResistancePct)}）；" +
                        "等有效突破站稳再考虑初始仓位。"
                score >= 2 && volumeSignal == VolumePriceSignal.EXPAND_UP ->
                    "量能已放大、评分尚可，但距压力 $resistanceLabel 还有 ${pct(toResistancePct)}；" +
                        "等突破，或回踩支撑 $supportLabel 不破再考虑。"
                // 放量但没涨上去（放量滞涨 / 高位放量上涨）：句子不能说成「量能没跟上」，
                // 否则和上面那行的量价标签自相矛盾。
                score >= 2 && volumeRatio >= VolumeRatio.EXPAND_THRESHOLD ->
                    "量能已经放大（${volumeSignal.label}）但价格还没突破：等站上压力 $resistanceLabel 再考虑，" +
                        "跌破支撑 $supportLabel 则先观察。"
                score >= 2 ->
                    "技术评分尚可但量能还没跟上：等放量突破压力 $resistanceLabel，" +
                        "或回踩支撑 $supportLabel 后止跌再介入。"
                score < 0 ->
                    "技术面偏弱（评分 ${signed(score)}），暂不介入；等站回 MA20（$ma20Label）并出现放量确认。"
                else ->
                    "技术面中性（评分 ${signed(score)}），还不到可以介入的程度；等站回 MA20（$ma20Label）" +
                        "或放量突破压力 $resistanceLabel 再观察。"
            } + envNote(envContext, changePct)
        }

        return PositionResult(
            code = position.code,
            name = position.name,
            quantity = quantity,
            cost = cost,
            price = price,
            changePct = changePct,
            marketValue = marketValue,
            positionPct = positionPct,
            signal = signal,
            action = action,
            exitDecision = exitDecision,
            notificationExitDecision = notificationExitDecision,
            support = support,
            resistance = resistance,
            ma20 = technical.ma20,
            ma5 = technical.ma5,
            entrySetup = technical.entrySetup,
            suggestedQuantity = suggestedQuantity,
            score = score,
            volumeRatio = volumeRatio,
            volumeSignal = volumeSignal,
            opening = technical.opening,
            volatility = technical.volatility,
            trendStrength = technical.trendStrength,
            relativeStrength = technical.relativeStrength,
            analyzedAt = TimeUtils.nowText(),
            fundamentalHealth = fundamentalHealth,
            changeNote = changeNoteOf(
                prev = envContext.prev,
                signal = signal,
                score = score,
                support = support,
                price = price,
                changePct = changePct,
                volumeRatio = volumeRatio,
                volumeSignal = volumeSignal,
            ),
            sectorNote = sectorNoteOf(envContext.sector),
            eventCoil = eventCoil,
            trendAssessment = technical.trendAssessment,
            boxRange = technical.boxRange,
            highPositionEvidence = technical.highPositionEvidence,
            highPosition = technical.highPosition,
            rhythm = rhythm,
            impulse = technical.impulse,
            fundFlowScoreDelta = technical.fundFlowScoreDelta,
        )
    }

    private fun entryAction(
        setup: EntrySetup,
        suggestedQuantity: Double,
        initialEntryPct: Double,
        maxPositionPct: Double,
    ): String {
        val quantityText = if (suggestedQuantity > 0) {
            "风险仓位约${String.format(Locale.CHINA, "%.0f", suggestedQuantity)}股"
        } else {
            "未给总资金或止损距离无效，暂不计算股数"
        }
        val stopText = setup.stopPrice?.let { "止损参考 ${priceText(it)}" } ?: "止损参考暂缺"
        return "${setup.label}已满足：${setup.reason}；$stopText；$quantityText，" +
            "单股初始仓位上限${String.format(Locale.CHINA, "%.0f", initialEntryPct)}%，" +
            "单股最大仓位${String.format(Locale.CHINA, "%.0f", maxPositionPct)}%。"
    }

    /**
     * 板块上下文一句话。归属查不到时写「板块归属暂缺」——**不猜**，
     * 把「贵州板块」当行业去比涨跌会得出完全错的结论。
     */
    private fun sectorNoteOf(sector: SectorContext?): String {
        val name = sector?.industry?.name ?: return "板块归属暂缺"
        val change = sector.changePct?.let { "，板块今日 ${signedPct(it)}（${sector.changeSource}）" }
            ?: "，板块涨跌暂缺（${sector.changeSource}）"
        val regime = sector.state?.regime?.let { "，板块${it.label}" } ?: ""
        return "板块：$name$change$regime（归属：${sector.source}）"
    }

    /** 注意：evaluate 里有名为 price 的局部变量（现价），所以这里不能叫 price。 */
    private fun priceText(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    private fun priceOrLabel(value: Double, fallback: String): String =
        if (value.isFinite() && value > 0) priceText(value) else fallback

    private fun qtyText(value: Double): String = String.format(Locale.CHINA, "%.0f", value)

    private fun signedPct(value: Double): String =
        if (value.isFinite()) String.format(Locale.CHINA, "%+.2f%%", value) else "—"

    /**
     * 当天的事实句：涨跌幅 + 量价 + 距支撑/压力。
     * 话术嵌的是这些实际数字，所以同一只股票不同日子读起来不一样，
     * 不会退化成「暂不做激进操作，观察支撑与压力」这种天天一样的模板。
     */
    private fun dayFacts(
        changePct: Double,
        volumeRatio: Double,
        volumeSignal: VolumePriceSignal,
        price: Double,
        support: Double,
        resistance: Double,
    ): String {
        val parts = mutableListOf("今天 ${signedPct(changePct)}")
        parts.add(
            if (volumeSignal == VolumePriceSignal.NEUTRAL) {
                String.format(Locale.CHINA, "量比 %.2f（常态）", volumeRatio)
            } else {
                String.format(Locale.CHINA, "量比 %.2f·%s", volumeRatio, volumeSignal.label)
            }
        )
        if (support > 0 && price > 0) {
            parts.add(String.format(Locale.CHINA, "较支撑 %+.1f%%", (price / support - 1) * 100))
        }
        if (resistance > 0 && price > 0) {
            parts.add(String.format(Locale.CHINA, "距压力 %.1f%%", (resistance / price - 1) * 100))
        }
        return parts.joinToString("，")
    }

    /**
     * 环境修饰。**判定要有后果**：塌陷期的「回踩支撑企稳」是接飞刀，
     * 所以这里把「不补仓、不接回、反弹减」直接写进应对，而不是让用户自己联想到。
     */
    private fun envNote(env: EnvContext, stockChangePct: Double? = null): String {
        val notes = mutableListOf<String>()
        when (env.market.regime) {
            MarketRegime.COLLAPSE ->
                notes.add("大盘塌陷（${env.market.reason}）：反弹高度别指望，不补仓、不做 T 接回，冲高优先减")
            MarketRegime.WEAK -> notes.add("大盘转弱（${env.market.reason}）：别加仓，等重新站上 MA20")
            else -> Unit
        }
        // 板块修饰由 SectorContext.interpret 给——个股强/板块弱、个股弱/板块强、板块塌陷是三种不同结论。
        env.sector?.interpret(stockChangePct)?.let { notes.add(it) }
        env.marketRisk?.portfolioNote()?.let { notes.add(it) }
        return if (notes.isEmpty()) "" else "｜" + notes.joinToString("；")
    }

    /**
     * 「较昨日」叙述。没有昨日快照就返回 null（报告写「无昨日快照」）——
     * **没数据和没变化是两回事**，不能把缺数据说成「无变化」。
     */
    private fun changeNoteOf(
        prev: RowSnapshot?,
        signal: String,
        score: Int,
        support: Double,
        price: Double,
        changePct: Double,
        volumeRatio: Double,
        volumeSignal: VolumePriceSignal,
    ): String? {
        if (prev == null) return null
        val diffs = mutableListOf<String>()
        if (prev.signal != signal) diffs.add("信号 ${prev.signal} → $signal")
        if (prev.score != score) diffs.add("评分 ${signed(prev.score)}→${signed(score)}")
        if (prev.support > 0 && support > 0 && prev.support != support) {
            diffs.add("支撑 ${priceText(prev.support)}→${priceText(support)}")
        }
        if (prev.price > 0 && price > 0 && abs(price / prev.price - 1.0) >= 0.01) {
            diffs.add("现价 ${priceText(prev.price)}→${priceText(price)}")
        }
        if (abs(changePct - prev.changePct) >= 0.5) {
            diffs.add("涨跌 ${pct(prev.changePct)}→${pct(changePct)}")
        }
        val volumeMoved = kotlin.math.abs(volumeRatio - prev.volumeRatio) >= 0.3
        if (volumeMoved) {
            diffs.add(
                String.format(
                    Locale.CHINA,
                    "量比 %.2f→%.2f（%s）",
                    prev.volumeRatio,
                    volumeRatio,
                    if (volumeSignal == VolumePriceSignal.NEUTRAL) "常态" else volumeSignal.label,
                )
            )
        }
        if (prev.price > 0 && support > 0 && prev.support > 0) {
            when {
                prev.price < prev.support && support > 0 && signal != "减仓风险" -> diffs.add("已站回支撑上方")
                prev.signal != "减仓风险" && signal == "减仓风险" -> diffs.add("跌破支撑")
            }
        }
        return if (diffs.isEmpty()) "与昨日无变化" else "较昨日：" + diffs.joinToString("｜")
    }

    private fun pct(value: Double): String = String.format(Locale.CHINA, "%.1f%%", value)

    private fun signed(value: Int): String = String.format(Locale.CHINA, "%+d", value)

    /**
     * 「今日要点」：报告开头与持仓页卡片**共用的一份精要**。
     *
     * 为什么不直接把长报告截断：长报告十来段，想看「今天要干什么」得翻到底；
     * 而卡片上的一两行没法承载四块信息。所以抽成独立函数，两边都调它，口径只有一份。
     *
     * 四块：**今日结果**（赚/亏、跑赢跑输）→ **市场环境** → **要做什么** → **风险点**。
     * 每块一行，读完不用点开长报告。
     */
    fun brief(
        results: List<PositionResult>,
        dayPnl: DayPnl.Summary? = null,
        market: MarketState? = null,
        swingPlan: SwingPlan? = null,
    ): List<String> {
        val lines = mutableListOf<String>()
        highestPriorityAction(results)?.let { lines.add(it) }

        // ① 今日结果：钱 + 相对大盘 + 主要来自哪只
        val resultLine = buildString {
            val total = dayPnl?.takeIf { it.totalPct != null }
            if (total != null) {
                append("今日结果：组合 ${DayPnl.pct(total.totalPct!!)}（${DayPnl.money(total.totalPnl)}）")
                val vsIndex = market?.changePct?.takeIf { it.isFinite() }
                if (vsIndex != null) {
                    val gap = total.totalPct - vsIndex
                    if (gap >= 0.5 || gap <= -0.5) {
                        append("，${if (gap > 0) "跑赢" else "跑输"}沪指 ${String.format(Locale.CHINA, "%.1f", abs(gap))} 个百分点")
                    }
                }
                val counted = total.rows.filter { it.pnl != null }
                val worst = counted.minByOrNull { it.pnl!! }
                val best = counted.maxByOrNull { it.pnl!! }
                val driver = when {
                    total.totalPnl < 0 && worst != null -> "；主要拖累『${worst.name}』${DayPnl.money(worst.pnl!!)}"
                    total.totalPnl > 0 && best != null -> "；主要来自『${best.name}』${DayPnl.money(best.pnl!!)}"
                    else -> ""
                }
                append(driver)
            } else {
                append("今日结果：盈亏暂缺（缺行情或昨收无效）")
            }
        }
        lines.add(resultLine)

        // ② 市场环境
        val envLine = buildString {
            append("市场环境：")
            if (market == null) {
                append("暂缺")
            } else {
                append("大盘${market.regime.label}")
                market.changePct?.takeIf { it.isFinite() }?.let { append("（沪指 ${DayPnl.pct(it)}）") }
            }
        }
        lines.add(envLine)

        // ③ 要做什么：按信号分档点名，没动作的也要说「不用动」——否则用户会以为都得处理
        lines.add("持仓整体操作建议：\n" + overallAdvice(results, swingPlan).joinToString("\n"))

        // ④ 风险点
        val risks = briefRisks(results, dayPnl)
        lines.add(if (risks.isEmpty()) "风险点：无明显风险" else "风险点：${risks.joinToString("；")}")
        return lines
    }

    /**
     * 持仓 Tab 今日要点的**高亮行**：事件后蓄势（见 [EventCoil]）单独用醒目样式渲染在卡片顶部。
     * 这类线索埋在个股报告里等于没给——高亮位才谈得上「重点关注这只票」。
     */
    fun todayBriefHighlights(results: List<PositionResult>): List<String> =
        results.filter { it.quantity.isFinite() && it.quantity > 0 && it.eventCoil != null }
            .map { result -> "🔥 事件后蓄势｜${result.name}：${result.eventCoil!!.brief}" }

    /** 持仓页与复盘报告共用的最高优先级操作，只挑一条，避免用户面对一堆同等重要的建议。 */
    fun highestPriorityAction(results: List<PositionResult>): String? {
        val current = results.filter { it.quantity.isFinite() && it.quantity > 0 }
            .sortedWith(compareBy<PositionResult> { operationPriority(it) }.thenBy { it.name })
            .firstOrNull() ?: return null
        val action = current.action.ifBlank { current.signal }
        return "最高优先级操作：${current.name}｜$action"
    }

    private fun operationPriority(result: PositionResult): Int = when {
        result.signal == "减仓风险" -> 0
        result.exitDecision?.stage in setOf(
            HoldingExitStage.EXIT,
            HoldingExitStage.REDUCE,
            HoldingExitStage.HIGH_REDUCE,
        ) -> 1
        result.signal == "趋势转弱观察" -> 2
        result.signal == "持有/止盈观察" -> 3
        result.signal in setOf("买入确认", "买入观察") || result.entrySetup.canBuy -> 4
        else -> 5
    }

    /**
     * 持仓 Tab 的精简今日要点。它和 [brief] 分开，避免把账户快照带进完整持仓报告。
     * 仓位只参与后台排序和整体建议，不在这里逐只展示。
     */
    fun todayBrief(
        results: List<PositionResult>,
        portfolio: Portfolio,
        market: MarketState? = null,
        swingPlan: SwingPlan? = null,
        quotes: Map<String, Quote> = emptyMap(),
        todayAcquisitions: Map<String, DayPnl.Acquisition> = emptyMap(),
    ): List<String> = buildList {
        val availableCapital = portfolio.resolvedAvailableCapital()
        val holdings = portfolio.positions.filter { it.quantity.isFinite() && it.quantity > 0 }
        val byCode = results.filter { it.quantity.isFinite() && it.quantity > 0 }
            .associateBy { it.code }
        highestPriorityAction(results)?.let { add(it) }
        val complete = holdings.all { position ->
            val result = byCode[position.code]
            val quoteValue = quotes[position.code]?.let { quote ->
                quote.price.takeIf { it.isFinite() && it > 0 }?.times(position.quantity)
            }
            (quoteValue ?: result?.marketValue)?.let { it.isFinite() && it >= 0 } == true
        }
        val accountLine = if (portfolio.totalCapital.isFinite() && portfolio.totalCapital > 0 &&
            availableCapital.isFinite() && availableCapital >= 0 && complete) {
            val marketValue = holdings.sumOf { position ->
                quotes[position.code]?.price?.takeIf { it.isFinite() && it > 0 }?.times(position.quantity)
                    ?: byCode.getValue(position.code).marketValue
            }
            val totalAssets = marketValue + availableCapital
            val pnl = totalAssets - portfolio.totalCapital
            val ratio = pnl / portfolio.totalCapital * 100
            "账户总盈亏：${DayPnl.money(pnl)}元（${DayPnl.pct(ratio)}）"
        } else {
            "账户总盈亏：暂无法计算（总资金、剩余资金或持仓行情不完整）"
        }
        add(accountLine)
        val todayQuotes = quotes.filter { (code, quote) ->
            quote.code == code && quote.quoteDate == TimeUtils.todayText()
        }
        val daily = DayPnl.summarize(holdings, todayQuotes, todayAcquisitions)
        add(todayPerformance(daily, todayQuotes["sh000001"]))
        add("大盘走势：" + when (market?.regime) {
            MarketRegime.BULL -> "此前分析显示整体向上"
            MarketRegime.WEAK -> "此前分析显示整体走弱，买入要更谨慎"
            MarketRegime.COLLAPSE -> "此前分析显示下跌风险较大，先别急着买"
            MarketRegime.NEUTRAL -> "此前分析没有明显方向，先观察"
            null -> "还没有分析结果"
        })

        add("最值得操作：" + mostActionable(holdings.mapNotNull { byCode[it.code] }, swingPlan))
        add("整体建议：" + accountSuggestion(holdings.mapNotNull { byCode[it.code] }, portfolio))
        val currentResults = holdings.mapNotNull { byCode[it.code] }
        val risks = buildList {
            val broken = currentResults.count { it.signal == "减仓风险" && it.exitDecision?.stage != HoldingExitStage.HIGH_REDUCE }
            val high = currentResults.count { it.exitDecision?.stage == HoldingExitStage.HIGH_REDUCE }
            val deep = currentResults.count { DeepLossPolicy.isDeepLoss(it.cost, it.price, it.volatility.atrPct) }
            if (broken > 0) add("$broken 只跌破关键价位")
            if (high > 0) add("$high 只涨到高处后开始走弱")
            if (deep > 0) add("$deep 只比买入成本跌得较多，别急着补钱")
            if (currentResults.size < holdings.size) add("部分持仓还没分析，不能判断全部风险")
        }
        add(if (risks.isEmpty()) "需要留意：现有分析未提示额外风险，不代表没有风险" else "需要留意：${risks.joinToString("；")}")
    }

    private fun todayPerformance(daily: DayPnl.Summary, index: Quote?): String {
        val indexPct = index?.takeIf { it.price.isFinite() && it.price > 0 && it.prevClose.isFinite() && it.prevClose > 0 }
            ?.changePct?.takeIf { it.isFinite() }
        val indexText = indexPct?.let { "沪指 ${DayPnl.pct(it)}" } ?: "沪指今日行情暂缺"
        if (daily.rows.isEmpty()) return "今日持仓：没有持仓，不计算涨跌比例；$indexText"
        val pct = daily.totalPct?.takeIf { it.isFinite() }
        if (pct == null || !daily.totalPnl.isFinite()) return "今日持仓：暂时算不出，缺少今日现价或昨收；$indexText"
        val amount = "${DayPnl.money(daily.totalPnl)}元（${DayPnl.pct(pct)}）"
        if (daily.missing > 0) return "今日持仓：已获取的${daily.counted}只持仓 $amount；${daily.missing}只缺少今日有效行情，暂不作整体对比；$indexText"
        val comparison = indexPct?.let {
            val gap = pct - it
            if (abs(gap) < 0.005) "，与沪指基本持平"
            else "，${if (gap > 0) "跑赢" else "跑输"}${String.format(Locale.CHINA, "%.2f", abs(gap))}个百分点"
        }.orEmpty()
        return "今日持仓：$amount；$indexText$comparison（按当前股数较昨收估算）"
    }

    private fun mostActionable(results: List<PositionResult>, swingPlan: SwingPlan?): String {
        val candidate = results.maxWithOrNull(compareBy<PositionResult> { actionPriority(it) }
            .thenByDescending { if (it.marketValue.isFinite()) it.marketValue else 0.0 })
        if (candidate != null && actionPriority(candidate) > 0) {
            val action = when (candidate.exitDecision?.stage) {
                HoldingExitStage.EXIT -> "卖出风险条件已触发，核对分析依据后优先考虑退出"
                HoldingExitStage.HIGH_REDUCE -> "涨到高处后开始走弱，优先分批卖出一部分"
                HoldingExitStage.REDUCE -> "跌破关键价位后未能站回，优先分批卖出一部分"
                HoldingExitStage.WATCH -> "刚跌破关键价位，先别加仓，看能否站回"
                HoldingExitStage.NOTICE -> "跌破关键价位，但基本面未亮红灯、位置低、仓位轻，去留按自己的风险偏好决定"
                HoldingExitStage.DEEP_T -> "亏损较深，别再补钱；等反弹时评估卖出一小部分，再等回落稳住后考虑接回"
                else -> when {
                    candidate.fundamentalHealth?.level == HealthLevel.ALERT -> {
                        val reason = candidate.fundamentalHealth.reasons.firstOrNull { it.isNotBlank() }
                            ?: "财务或公告分析提示风险，但具体原因未提供"
                        "$reason；先别加仓，查看最新财报或公告，不是让你马上卖出"
                    }
                    candidate.signal == "趋势转弱观察" -> "走势变弱，先别继续买，等价格稳住"
                    candidate.signal == "持有/止盈观察" -> "结构未失效时保留观察仓等待二波，不因已有浮盈卖出"
                    else -> "先保持不动"
                }
            }
            return "${candidate.name}：$action"
        }
        val preferred = swingPlan?.preferredCode?.let { code ->
            results.firstOrNull { it.code == code }
        }
        return if (preferred != null) "${preferred.name}：已有买入条件，可按最新分析的价格和数量考虑少量买入，不追高"
        else "目前没有哪只需要优先买卖，不必勉强操作"
    }

    private fun actionPriority(result: PositionResult): Int = when {
        result.exitDecision?.stage == HoldingExitStage.EXIT -> 100
        result.exitDecision?.stage == HoldingExitStage.HIGH_REDUCE -> 90
        result.exitDecision?.stage == HoldingExitStage.REDUCE -> 85
        result.exitDecision?.stage == HoldingExitStage.WATCH -> 80
        result.fundamentalHealth?.level == HealthLevel.ALERT -> 75
        result.signal == "趋势转弱观察" -> 70
        // 破位但三项缓释（基本面没红灯/低位/轻仓）：提一句即可，不该抢「最值得操作」的头条。
        result.exitDecision?.stage == HoldingExitStage.NOTICE -> 65
        result.exitDecision?.stage == HoldingExitStage.DEEP_T -> 60
        result.signal == "持有/止盈观察" -> 40
        else -> 0
    }

    private fun accountSuggestion(results: List<PositionResult>, portfolio: Portfolio): String {
        if (results.isEmpty()) return "持仓还没分析完，先不急着买卖"
        val totalValue = results.sumOf { it.marketValue.takeIf { value -> value.isFinite() && value > 0 } ?: 0.0 }
        val totalPositionPct = if (portfolio.totalCapital > 0) totalValue / portfolio.totalCapital * 100 else null
        val top = results.maxByOrNull { it.marketValue.takeIf { value -> value.isFinite() } ?: 0.0 }
        val topConcentrated = top != null && totalValue > 0 &&
            (top.marketValue / totalValue >= 0.5 ||
                (portfolio.totalCapital > 0 && top.marketValue / portfolio.totalCapital * 100 > portfolio.maxPositionPct))
        return when {
            results.any { it.exitDecision?.stage == HoldingExitStage.EXIT } ->
                "先处理已经触发卖出风险条件的股票，别急着再买。"
            results.any { it.fundamentalHealth?.level == HealthLevel.ALERT } ->
                "有股票的财务或公告分析提示风险，先查看具体原因，别急着加仓或卖出后接回。"
            results.any { it.exitDecision?.stage in setOf(HoldingExitStage.HIGH_REDUCE, HoldingExitStage.REDUCE) } &&
                (topConcentrated || totalPositionPct != null && totalPositionPct >= 80) ->
                "买入的股票较多，或钱集中在少数股票上；先减少已确认走弱的持仓，别继续买。"
            results.any { it.exitDecision?.stage in setOf(HoldingExitStage.HIGH_REDUCE, HoldingExitStage.REDUCE) } ->
                "先分批卖出一部分已确认走弱的股票，其他持仓先观察，别追着买。"
            results.any { it.exitDecision?.stage in setOf(HoldingExitStage.WATCH, HoldingExitStage.NOTICE) } ->
                "先别继续买入，看能否站回关键价位；不能只因刚跌破就急着卖。"
            totalPositionPct != null && totalPositionPct >= 80 ->
                "较多资金已经买了股票，先别继续买，留些现金应对波动。"
            topConcentrated ->
                "钱比较集中在少数股票上，先别继续买入持有最多的那只。"
            results.any { it.signal == "趋势转弱观察" } ->
                "走势变弱的股票先别继续买，等价格稳住后再判断。"
            else -> "先持有观察，不必频繁买卖；有明确买卖条件时再考虑少量操作。"
        }
    }

    fun overallAdvice(results: List<PositionResult>, swingPlan: SwingPlan? = null): List<String> = buildList {
        val holdings = results.filter { it.quantity.isFinite() && it.quantity > 0 }
        val groups = holdings.groupBy { result ->
            when {
                result.exitDecision?.stage == HoldingExitStage.EXIT -> "exit"
                result.exitDecision?.stage == HoldingExitStage.HIGH_REDUCE -> "highReduce"
                result.exitDecision?.stage == HoldingExitStage.REDUCE -> "reduce"
                result.fundamentalHealth?.level == HealthLevel.ALERT -> "fundamental"
                result.exitDecision?.stage == HoldingExitStage.NOTICE -> "notice"
                result.exitDecision?.stage == HoldingExitStage.WATCH -> "watchBreak"
                DeepLossPolicy.isDeepLoss(result.cost, result.price, result.volatility.atrPct) -> "deep"
                result.signal == "减仓风险" -> "broken"
                result.signal == "趋势转弱观察" -> "weak"
                result.signal == "持有/止盈观察" -> "profit"
                result.action.contains("保留观察仓等待二波") -> "secondWaveHold"
                result.code == swingPlan?.preferredCode -> "preferred"
                else -> "hold"
            }
        }
        fun names(rows: List<PositionResult>): String = rows.take(3).joinToString("、") { it.name } +
            if (rows.size > 3) "等${rows.size}只" else ""
        val riskParts = buildList {
            groups["exit"]?.let { add("${names(it)}退出优先，核对依据后以清仓退出为目标，不再等待反弹做T") }
            groups["highReduce"]?.let { add("${names(it)}高位转弱，优先分批减仓，暂停加仓和做T接回；不因高位直接清仓") }
            groups["reduce"]?.let { add("${names(it)}确认破位，优先分批减仓；失守风险参考且趋势转弱时升级退出") }
            groups["notice"]?.let { add("${names(it)}已破位，但基本面未亮红灯、位置低、仓位轻，去留自定；若减仓可分批约1/3") }
            groups["watchBreak"]?.let { add("${names(it)}破位待确认，暂不加仓；持续未收回则减仓") }
            groups["fundamental"]?.let { add("${names(it)}基本面亮红灯，先核实风险，暂停加仓和机械做T接回") }
            groups["broken"]?.let { add("${names(it)}已破位，确认未收回则减仓；持有依据失效时退出") }
            groups["weak"]?.let { add("${names(it)}趋势转弱，暂不加仓，等重新企稳") }
        }
        if (riskParts.isNotEmpty()) add("风险优先：${riskParts.joinToString("；")}")
        groups["deep"]?.let {
            add("深套处理：${names(it)}深套不盲目补仓；机动仓做T需等反弹减出、回踩企稳后再评估接回，不保证降本或解套")
        }
        val positionParts = buildList {
            groups["secondWaveHold"]?.let { add("${names(it)}MA20仍上行且回踩未破坏结构，保留观察仓等待二波，不因已有浮盈卖出") }
            groups["profit"]?.let { add("${names(it)}按逐股结构和风险线处理，不因已有浮盈直接卖出") }
            groups["hold"]?.let { add("${names(it)}以持有观察为主，暂不主动调仓") }
        }
        if (positionParts.isNotEmpty()) add("现有仓位：${positionParts.joinToString("；")}")
        if (holdings.isEmpty()) add("现有仓位：暂无有效持仓分析，不推算调仓动作")
        add("新增资金：" + (swingPlan?.headline() ?: "波段加仓评估尚未完成，暂不新增买入"))
    }

    /** 风险点：破位 / 深套 / 集中度 / 缺数据，只列有量的那几条。 */
    private fun briefRisks(results: List<PositionResult>, dayPnl: DayPnl.Summary?): List<String> {
        val risks = mutableListOf<String>()
        val broke = results.count { it.signal == "减仓风险" && it.exitDecision?.stage != HoldingExitStage.HIGH_REDUCE }
        val highReduce = results.count { it.exitDecision?.stage == HoldingExitStage.HIGH_REDUCE }
        if (broke > 0) risks.add("破位 $broke 只")
        if (highReduce > 0) risks.add("高位转弱 $highReduce 只")
        val deep = results.count {
            it.quantity > 0 && DeepLossPolicy.isDeepLoss(it.cost, it.price, it.volatility.atrPct)
        }
        if (deep > 0) risks.add("深套 $deep 只（较成本）")
        val holding = results.filter { it.quantity > 0 && it.marketValue > 0 }
        val top = holding.maxByOrNull { it.marketValue }
        val totalValue = holding.sumOf { it.marketValue }
        if (top != null && totalValue > 0) {
            val weight = top.marketValue / totalValue * 100
            if (weight >= 30.0) {
                risks.add("单股集中度 ${String.format(Locale.CHINA, "%.0f%%", weight)}（『${top.name}』）")
            }
        }
        if (dayPnl != null && dayPnl.missing > 0) risks.add("${dayPnl.missing} 只缺行情未计入")
        return risks
    }

    /**
     * 持仓监控缓存报告，等价于 format_portfolio_report。
     *
     * [dayPnl] 是较昨收的当日盈亏合计，与报告里逐股的「较成本」建议分开展示；
     * 不传（或一只都算不出）时整段不出现，不印 0 假装没亏没赚。
     * [market] 用来生成开头的「今日要点」，也参与逐股的环境改写。
     */
    fun formatReport(
        results: List<PositionResult>,
        analyzedAt: String,
        dayPnl: DayPnl.Summary? = null,
        market: MarketState? = null,
        strongContinuationEvents: List<StrongContinuationEvent> = emptyList(),
        marketEnvironment: MarketEnvironmentQuality? = null,
        swingPlan: SwingPlan? = null,
    ): String {
        val lines = mutableListOf(
            "📦 持仓信息 / 盘中技术监控",
            "最近分析：$analyzedAt",
        )
        // 开头给「今日要点」：十来段的长报告，想看「今天要干什么」不该翻到底。
        lines.add("")
        lines.add("持仓整体操作建议")
        overallAdvice(results, swingPlan).forEach { lines.add("· $it") }
        lines.add("")
        lines.add("📌 今日要点")
        brief(results, dayPnl, market, swingPlan)
            .filterNot { it.startsWith("持仓整体操作建议：") }.forEach { lines.add("· $it") }
        swingPlan?.let {
            it.capitalIssue?.let { issue -> lines.add("资金检查：$issue") }
            it.allocationCapital?.let { capital ->
                lines.add("额度基数：${priceText(capital)}元（配置总资金与已录入持仓市值加现金取较小值）")
            }
        }
        lines.add("")
        dayPnl?.let { DayPnl.summaryText(it) }?.let { lines.add(it) }
        if (dayPnl != null && dayPnl.missing > 0) {
            lines.add("其中 ${dayPnl.missing} 只无行情（停牌或未拉到），未计入今日盈亏。")
        }
        if (strongContinuationEvents.isNotEmpty()) {
            lines.add("")
            lines.add("📈 强势环境暂缓做T记录")
            lines.add("量能、市场和板块环境同时偏强时，记录为继续观察，不把冲高机械当作卖出信号。")
            strongContinuationEvents.forEach { lines.add("· ${it.reportLine()}") }
        }
        marketEnvironment?.let { environment ->
            val cards = listOf(
                EvidenceCard(
                    "全天预估成交额",
                    environment.predictedTurnoverYi?.let { String.format(Locale.CHINA, "%,.0f亿", it) },
                    source = "TurnoverTracker",
                    quoteDate = TimeUtils.todayText(),
                    scope = "沪深两市",
                ),
                EvidenceCard(
                    "沪指涨跌",
                    environment.marketChangePct?.let { String.format(Locale.CHINA, "%+.2f%%", it) },
                    quoteDate = TimeUtils.todayText(),
                ),
                EvidenceCard(
                    "行业板块上涨占比",
                    environment.industryBreadth?.let { String.format(Locale.CHINA, "%.0f%%", it * 100) },
                    quoteDate = TimeUtils.todayText(),
                    scope = "行业板块",
                ),
                EvidenceCard(
                    "概念板块上涨占比",
                    environment.conceptBreadth?.let { String.format(Locale.CHINA, "%.0f%%", it * 100) },
                    quoteDate = TimeUtils.todayText(),
                    scope = "概念板块",
                ),
            )
            lines.add("")
            lines.add("📎 市场证据")
            EvidenceCard.format(cards).forEach { lines.add("· $it") }
        }
        val review = DecisionReview.from(marketEnvironment, results)
        if (review.hasContent) {
            lines.add("")
            lines.add("🧭 市场判断复核")
            review.supporting.takeIf { it.isNotEmpty() }?.let { lines.add("支持证据：${it.joinToString("；")}") }
            review.counter.takeIf { it.isNotEmpty() }?.let { lines.add("反向证据：${it.joinToString("；")}") }
            review.missing.takeIf { it.isNotEmpty() }?.let { lines.add("尚缺信息：${it.joinToString("、")}") }
            review.invalidators.takeIf { it.isNotEmpty() }?.let { lines.add("判断失效条件：${it.joinToString("；")}") }
        }
        lines.add("")
        if (results.isEmpty()) {
            lines.add("尚未配置监控股票，或当前没有可用分析结果。")
        }
        for (result in results) {
            val swing = swingPlan?.rows?.firstOrNull { it.code == result.code }
            var positionText = "未持仓观察"
            if (result.quantity > 0) {
                positionText = "持仓${String.format(Locale.CHINA, "%.0f", result.quantity)}股"
                if (result.cost > 0) positionText += "，成本${String.format(Locale.CHINA, "%.2f", result.cost)}"
            }
            val pctText = result.positionPct?.let { "，占总资金${String.format(Locale.CHINA, "%.1f", it)}%" } ?: ""
            // 当日金额紧跟当日涨跌幅，两个「今日」口径放一起；较成本的浮盈在下面的应对里，不混进来。
            val dayText = dayPnl?.rows?.firstOrNull { it.code == result.code }?.pnl
                ?.let { "，今日 ${DayPnl.money(it)} 元" } ?: ""
            lines.add(
                "${result.name}（${result.code}）｜状态：${result.exitDecision?.stage?.label ?: result.signal}｜评分 ${String.format(Locale.CHINA, "%+d", result.score)}"
            )
            lines.add(
                "当前价 ${String.format(Locale.CHINA, "%.2f", result.price)}" +
                    "（较前一日 ${String.format(Locale.CHINA, "%+.2f%%", result.changePct)}$dayText）｜$positionText$pctText"
            )
            lines.add(
                "${result.sectorNote ?: "板块归属暂缺"}｜入场：${swing?.status?.label ?: result.entrySetup.conciseText()}"
            )
            result.eventCoil?.let { lines.add(it.note) }
            result.rhythm?.let {
                lines.add("市场节奏：${it.phase.label}｜${it.reason}")
            }
            if (swing != null) {
                lines.add("波段追加：${swing.action()}")
                lines.add("依据：${swing.reason}")
                swing.cycles?.let { cycles ->
                    lines.add("${cycles.summary()}｜前复权历史${cycles.historyStart ?: "暂缺"}至${cycles.asOf}；完整周K${cycles.weeks.size}根、月K${cycles.months.size}根")
                    val year = cycles.years.lastOrNull()
                    lines.add(if (year == null) "年K：暂无完整年度；不参与加仓评分" else
                        "年K：${cycles.years.size}根完整年度，最近${year.date.take(4)}年开${priceText(year.open)}、收${priceText(year.close)}；仅作背景")
                }
                if (swing.addQuantity > 0) lines.add(swing.levels())
            } else result.entrySetup.stopPrice?.let {
                lines.add("止损参考：${String.format(Locale.CHINA, "%.2f", it)}｜建议风险仓位：${String.format(Locale.CHINA, "%.0f", result.suggestedQuantity)}股")
            }
            lines.add(
                "支撑 ${priceOrLabel(result.support, "—")}｜" +
                    "压力 ${priceOrLabel(result.resistance, "—")}｜" +
                    "M20 ${priceOrLabel(result.ma20, "—")}｜" +
                    "量比 ${String.format(Locale.CHINA, "%.2f", result.volumeRatio)}" +
                    if (result.volumeSignal == VolumePriceSignal.NEUTRAL) {
                        ""
                    } else {
                        "｜${VolumePrice.tag(result.volumeSignal)}"
                    }
            )
            result.fundamentalHealth?.let { health ->
                lines.add(
                    "基本面：${health.level.label}｜${health.reasons.joinToString("；")}｜判断于 ${health.analyzedAt}" +
                        "（财务与公告可见面，不含行业景气与订单）"
                )
            }
            lines.add("应对：" + if (swing?.addQuantity?.let { it > 0 } == true) {
                "仅在参考区间内重新确认企稳后按上限分批考虑；价格、资金或仓位变化须重评，不自动下单。"
            } else result.action)
            lines.add("")
        }
        lines.add("说明：本报告只提供条件式技术信号，不自动下单；未持仓股票的建议不考虑个人成本。")
        lines.add("⚠️ 市场有风险，投资需谨慎，不构成投资建议。")
        return lines.joinToString("\n")
    }

    private data class DecisionReview(
        val supporting: List<String>,
        val counter: List<String>,
        val missing: List<String>,
        val invalidators: List<String>,
    ) {
        val hasContent: Boolean get() = supporting.isNotEmpty() || counter.isNotEmpty() ||
            missing.isNotEmpty() || invalidators.isNotEmpty()

        companion object {
            fun from(environment: MarketEnvironmentQuality?, results: List<PositionResult>): DecisionReview {
                if (environment == null) return DecisionReview(emptyList(), emptyList(), emptyList(), emptyList())
                val support = mutableListOf<String>()
                val counter = mutableListOf<String>()
                val missing = mutableListOf<String>()
                val invalidators = mutableListOf<String>()
                environment.predictedTurnoverYi?.let {
                    if (it >= 20_000) support += "全天预估成交额约${it.toInt()}亿，达到高量观察线"
                    else counter += "全天预估成交额约${it.toInt()}亿，尚未达到2万亿观察线"
                } ?: missing.add("全天预估成交额")
                environment.marketChangePct?.let {
                    if (it >= 0) support += "沪指${String.format(Locale.CHINA, "%+.2f%%", it)}，暂未形成明显拖累"
                    else counter += "沪指${String.format(Locale.CHINA, "%+.2f%%", it)}，指数方向不利"
                } ?: missing.add("沪指涨跌")
                environment.industryBreadth?.let {
                    if (it >= 0.6) support += "行业板块上涨占比${String.format(Locale.CHINA, "%.0f%%", it * 100)}，扩散达到参考线"
                    else counter += "行业板块宽度${String.format(Locale.CHINA, "%.0f%%", it * 100)}，扩散不足"
                } ?: missing.add("行业板块宽度")
                environment.conceptBreadth?.let {
                    if (it >= 0.6) support += "概念板块上涨占比${String.format(Locale.CHINA, "%.0f%%", it * 100)}，扩散达到参考线"
                    else counter += "概念板块宽度${String.format(Locale.CHINA, "%.0f%%", it * 100)}，扩散不足"
                } ?: missing.add("概念板块宽度")
                if (environment.status == MarketEnvironmentStatus.STRONG_SUSTAINED) {
                    invalidators += "若指数脱离日内高位、板块宽度明显收窄或量能回落，当前判断需要重评"
                } else if (environment.status == MarketEnvironmentStatus.STRONG_SINGLE_DAY) {
                    invalidators += "若后续量能不能延续，或指数/板块出现滞涨，单日高量不再支持继续等待"
                }
                val broken = results.count { it.signal == "减仓风险" && it.exitDecision?.stage != HoldingExitStage.HIGH_REDUCE }
                if (broken > 0) counter += "持仓中有${broken}只出现破位，个股风险优先"
                val highReduce = results.count { it.exitDecision?.stage == HoldingExitStage.HIGH_REDUCE }
                if (highReduce > 0) counter += "持仓中有${highReduce}只高位转弱，优先减仓"
                return DecisionReview(support, counter, missing, invalidators)
            }
        }
    }
}
