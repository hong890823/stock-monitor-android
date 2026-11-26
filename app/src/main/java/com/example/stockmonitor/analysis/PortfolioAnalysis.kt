package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Portfolio
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.PositionAllocationConfig
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.RowSnapshot
import com.example.stockmonitor.core.TradeBook
import com.example.stockmonitor.core.cashPosition
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
    /** 支撑有效性（[SupportValidityPolicy]）：支撑类买点推送前的验证档。 */
    val supportValidity: SupportValidityPolicy.SupportValidity? = null,
    val rhythm: AdaptiveRhythmDecision? = null,
    val impulse: ImpulseContext? = null,
    val fundFlowScoreDelta: Int = 0,
    val analysisDate: String = "",
    val priorityEvidenceDate: String = "",
    val priorityRiskConfirmed: Boolean = false,
    val priorityExitTriggered: Boolean = false,
    val priorityExitAudit: String = "",
    val priorityBuyApproved: Boolean = false,
    val fiveDayNoNewLow: Boolean? = null,
    val closesBelowMa20: Int = 0,
) {
    val intradayScore: Int
        get() = score - fundFlowScoreDelta
}

data class TodayPriorityAdvice(
    val code: String,
    val name: String,
    val state: PriorityState,
    val fresh: Boolean,
    val reason: String,
    val action: String? = null,
    val technicalAdvice: String? = null,
) {
    val displayText: String
        get() = buildString {
            append("今日单股建议：$name｜${state.label}｜$reason")
            if (!fresh) {
                append("；行情或技术数据尚未同日确认，先刷新后再判断")
            } else {
                action?.takeIf { it.isNotBlank() }?.let { append("；持仓建议：$it") }
                technicalAdvice?.takeIf { it.isNotBlank() }?.let { append("；趋势参考：$it") }
            }
        }
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
        // 追高边界与新买共用：加仓、报告里的“买入观察”都受同一 MA5 乖离上限约束，
        // 避免同一只票“加仓可以、新买不行”。差异只体现在仓位，不体现在价格条件。
        val biasFromMa5Pct = technical.ma5.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (price / it - 1.0) * 100.0 }
        val chasingHigh = biasFromMa5Pct != null && biasFromMa5Pct > StrategyThresholds.MAX_CHASE_MA5_BIAS_PCT
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
            biasFromMa5Pct = biasFromMa5Pct,
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
                biasFromMa5Pct = biasFromMa5Pct,
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
                    // NOTICE 是三项缓释档（基本面无红灯、价格低位、仓位轻），与 UI 详情的
                    // exitStage.label「破位风险提示」同口径；统一写成「减仓风险」会让同一只
                    // 持仓在信号、详情、报告里出现三种说法，缓释档被读成命令卖出。
                    signal = if (exitDecision.stage == HoldingExitStage.NOTICE) "破位提示" else "减仓风险"
                    val breakAction = exitDecision.action
                    action = if (trendWeak) {
                        "$facts。已有效跌破 M20（$ma20Label）、趋势转弱，不加仓、不摊平。$breakAction"
                    } else {
                        "$facts。$breakAction"
                    } + envNote(envContext, changePct)
                }
                trendWeak -> {
                    signal = "趋势转弱观察"
                    // trendWeak 有两个成因（有效跌破 M20 / 失败结构），按现价位置写实是哪个，
                    // 不写「或」让读者自己猜。
                    val weakCause =
                        if (technical.ma20.isFinite() && technical.ma20 > 0 && price < technical.ma20) {
                            "已有效跌破 M20（$ma20Label，现价低 " +
                                String.format(Locale.CHINA, "%.1f%%", (1.0 - price / technical.ma20) * 100.0) +
                                "）"
                        } else {
                            "出现失败结构，价格仍在 M20（$ma20Label）上方"
                        }
                    action = "$facts。$weakCause，趋势转弱。" +
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
        } else if (pullback && technical.entrySetup.canBuy && score >= 2 && !chasingHigh) {
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
            // 「回踩」买点参照走强度分档锚（PullbackAnchorPolicy）；supportLabel（20 日低点）
            // 只用在破位口径——不然强势股的「回踩支撑」会指到趋势已死的位置。
            val pullbackRef = technical.pullbackRef
            // 同为「等待」，成因不同就该给不同的下一步，不能每只股票印同一句话。
            action = when {
                chasingHigh ->
                    "现价较 MA5 乖离 ${pct(biasFromMa5Pct!!)}，超过 " +
                        "${pct(StrategyThresholds.MAX_CHASE_MA5_BIAS_PCT)} 追高上限：等回踩 MA5 附近缩量止跌再评估，不在拉升末端追入。"
                score >= 2 && volumeSignal == VolumePriceSignal.EXPAND_UP && toResistancePct <= 2 ->
                    "已放量走强、评分尚可，且逼近压力 $resistanceLabel（还差 ${pct(toResistancePct)}）；" +
                        "等有效突破站稳再考虑初始仓位。"
                score >= 2 && volumeSignal == VolumePriceSignal.EXPAND_UP ->
                    "量能已放大、评分尚可，但距压力 $resistanceLabel 还有 ${pct(toResistancePct)}；" +
                        if (pullbackRef != null) "等突破，或回踩 $pullbackRef 不破再考虑。"
                        else "等突破站稳；趋势偏弱，不给回踩买点参考。"
                // 放量但没涨上去（放量滞涨 / 高位放量上涨）：句子不能说成「量能没跟上」，
                // 否则和上面那行的量价标签自相矛盾。
                score >= 2 && volumeRatio >= VolumeRatio.EXPAND_THRESHOLD ->
                    "量能已经放大（${volumeSignal.label}）但价格还没突破：等站上压力 $resistanceLabel 再考虑，" +
                        "跌破支撑 $supportLabel 则先观察。"
                score >= 2 ->
                    "技术评分尚可但量能还没跟上：等放量突破压力 $resistanceLabel，" +
                        (pullbackRef?.let { "或回踩 $it 后止跌再介入。" }
                            ?: "或等缩量企稳后再介入（趋势偏弱，不给回踩买点参考）。")
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
            supportValidity = technical.supportValidity,
            highPosition = technical.highPosition,
            rhythm = rhythm,
            impulse = technical.impulse,
            fundFlowScoreDelta = technical.fundFlowScoreDelta,
            analysisDate = live?.quoteDate?.take(10).orEmpty(),
            priorityEvidenceDate = technical.dataDate.take(10),
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
            parts.add(String.format(Locale.CHINA, "距支撑 %+.1f%%", (price / support - 1) * 100))
        }
        if (resistance > 0 && price > 0) {
            parts.add(String.format(Locale.CHINA, "距压力 %+.1f%%", (resistance / price - 1) * 100))
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
                // 「破位提示」是 NOTICE 缓释档拆出的新 signal 文案，统计口径与「减仓风险」
                // 同属破位信号（isBreakSignal），行为不随改名漂移。
                prev.price < prev.support && support > 0 && !isBreakSignal(signal) -> diffs.add("已站回支撑上方")
                !isBreakSignal(prev.signal) && isBreakSignal(signal) -> diffs.add("跌破支撑")
            }
        }
        return if (diffs.isEmpty()) "与昨日无变化" else "较昨日：" + diffs.joinToString("｜")
    }

    private fun pct(value: Double): String = String.format(Locale.CHINA, "%.1f%%", value)

    /** 破位类 signal：「减仓风险」与 NOTICE 缓释档的「破位提示」同属一族，统计口径不随文案拆分漂移。 */
    private fun isBreakSignal(signal: String): Boolean = signal == "减仓风险" || signal == "破位提示"

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
        /** 分歧期状态（模块 A/B）：非空且非 NONE 时在操作建议里追加矩阵提示。 */
        divergence: DivergenceCycleState? = null,
    ): List<String> {
        val lines = mutableListOf<String>()
        // 算一次复用：头行在 ① 前展示，后三行在 ④ 后展示；overallAdvice 的正文不再内嵌
        // 摘要（否则「当前优先事项/风险核实/买入机会/排序口径」在 brief 里各出现两遍）。
        val priority = prioritySummary(results)
        lines.add(priority.first())

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
        lines.add("持仓整体操作建议：\n" + adviceBody(results, swingPlan, divergence).joinToString("\n"))

        // ④ 风险点
        val risks = briefRisks(results, dayPnl)
        lines.add(if (risks.isEmpty()) "风险点：无明显风险" else "风险点：${risks.joinToString("；")}")
        lines.addAll(priority.drop(1))
        return lines
    }

    /**
     * 持仓 Tab 今日要点的**高亮行**：事件后蓄势（见 [EventCoil]）单独用醒目样式渲染在卡片顶部。
     * 这类线索埋在个股报告里等于没给——高亮位才谈得上「重点关注这只票」。
     */
    fun todayBriefHighlights(results: List<PositionResult>): List<String> =
        results.filter { it.quantity.isFinite() && it.quantity > 0 && it.eventCoil != null }
            .map { result -> "🔥 事件后蓄势｜${result.name}：${result.eventCoil!!.brief}" }

    fun priorityInput(result: PositionResult): PriorityInput = PriorityInput(
        code = result.code, name = result.name, quantity = result.quantity,
        price = result.price, support = result.support, ma20 = result.ma20,
        quoteDate = result.analysisDate, evidenceDate = result.priorityEvidenceDate,
        riskConfirmed = result.priorityRiskConfirmed, exitTriggered = result.priorityExitTriggered,
        highRisk = result.highPosition?.phase == HighPositionPhase.DISTRIBUTION_RISK,
        fundamentalAlert = result.fundamentalHealth?.level == HealthLevel.ALERT,
        fundamentalReason = result.fundamentalHealth?.reasons?.joinToString("；").orEmpty(),
        buyApproved = result.priorityBuyApproved,
        mitigated = result.exitDecision?.stage == HoldingExitStage.NOTICE,
    )

    fun prioritySummary(results: List<PositionResult>, asOf: String = TimeUtils.todayText()): List<String> =
        PortfolioPriorityPolicy.summary(results.map(::priorityInput), asOf)

    fun priorityDecision(result: PositionResult, asOf: String = TimeUtils.todayText()): PortfolioPriorityDecision =
        PortfolioPriorityPolicy.evaluate(priorityInput(result), asOf)

    fun priorityOrdered(results: List<PositionResult>, asOf: String = TimeUtils.todayText()): List<PositionResult> {
        val order = PortfolioPriorityPolicy.rank(results.map(::priorityInput), asOf)
            .mapIndexed { index, decision -> decision.input.code to index }.toMap()
        return results.sortedWith(compareBy<PositionResult> { order[it.code] ?: Int.MAX_VALUE }.thenBy { it.code })
    }

    /**
     * 今日要点和 AI 共用的单股优先建议。
     *
     * 排序只复用 [PortfolioPriorityPolicy]：确认退出/减仓优先于风险观察，
     * 再到买入候选；不让无持仓语境的技术文案覆盖持仓风险优先级。
     */
    fun todayPriorityAdvice(
        results: List<PositionResult>,
        technicalTrends: Map<String, TechnicalTrendSnapshot> = emptyMap(),
        asOf: String = TimeUtils.todayText(),
        /** 传入时只接受与当前持仓代码、数量、成本一致的结果，避免缓存旧仓位进入 AI 建议。 */
        currentPositions: List<Position>? = null,
    ): TodayPriorityAdvice? {
        val eligibleResults = currentPositions?.let { positions ->
            val byCode = results.associateBy { it.code }
            positions.filter { it.quantity.isFinite() && it.quantity > 0 }.mapNotNull { position ->
                byCode[position.code]?.takeIf {
                    it.quantity == position.quantity && it.cost == position.cost
                }
            }
        } ?: results
        val ranked = priorityOrdered(eligibleResults, asOf)
        val selected = ranked.firstNotNullOfOrNull { result ->
            val decision = priorityDecision(result, asOf)
            (result to decision).takeIf { decision.order < PriorityState.HOLD.order }
        } ?: return null
        val (result, decision) = selected
        val fundamentalReview = decision.input.fundamentalAlert && !decision.actionConfirmed
        val reason = if (fundamentalReview) {
            decision.input.fundamentalReason.ifBlank { "基本面风险待核实，先查看最新财报或公告，不急于加仓" }
        } else {
            decision.reason
        }
        val technicalAdvice = technicalTrends[result.code]
            ?.takeIf { decision.fresh && it.asOf.take(10) == asOf }
            ?.primaryAdvice
        return TodayPriorityAdvice(
            code = result.code,
            name = result.name,
            state = decision.state,
            fresh = decision.fresh,
            reason = reason,
            action = result.action.takeIf { decision.fresh },
            technicalAdvice = technicalAdvice,
        )
    }

    /** 只在存在同日有效的明确处置或买入候选时，追加到今日要点的整体建议。 */
    private fun actionableTodayPriorityDetail(
        results: List<PositionResult>,
        technicalTrends: Map<String, TechnicalTrendSnapshot>,
        asOf: String = TimeUtils.todayText(),
    ): String? {
        val advice = todayPriorityAdvice(results, technicalTrends, asOf) ?: return null
        if (!advice.fresh || advice.state !in setOf(PriorityState.EXIT, PriorityState.REDUCE, PriorityState.BUY)) {
            return null
        }
        val detail = advice.technicalAdvice ?: advice.action ?: return null
        return "；今日优先处理『${advice.name}』（${advice.state.label}）：${advice.reason}；趋势/操作条件：$detail"
    }

    fun highestPriorityAction(results: List<PositionResult>): String = prioritySummary(results).first()

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
        tradeBook: TradeBook = TradeBook(),
        todayAcquisitions: Map<String, DayPnl.Acquisition> = emptyMap(),
        technicalTrends: Map<String, TechnicalTrendSnapshot> = emptyMap(),
    ): List<String> = buildList {
        val holdings = portfolio.positions.filter { it.quantity.isFinite() && it.quantity > 0 }
        val byCode = results.filter { it.quantity.isFinite() && it.quantity > 0 }
            .associateBy { it.code }
        val activeResults = holdings.mapNotNull { position ->
            byCode[position.code]?.takeIf { it.quantity == position.quantity }?.let {
                if (it.cost == position.cost) it else it.copy(priorityBuyApproved = false)
            }
        }
        val complete = holdings.all { position ->
            val result = byCode[position.code]
            val quoteValue = quotes[position.code]?.let { quote ->
                quote.price.takeIf { it.isFinite() && it > 0 }?.times(position.quantity)
            }
            (quoteValue ?: result?.marketValue)?.let { it.isFinite() && it >= 0 } == true
        }
        val cash = portfolio.cashPosition()
        val accountLine = when {
            // 成本没填/总资金没填时可用现金“算不出来”，不能当 0 去凑账户盈亏——
            // 否则 100 万本金 + 成本 0 的持仓会显示 -90% 的假亏损。
            cash.blockedReason != null -> "账户总盈亏：暂无法计算（${cash.blockedReason}）"
            !complete -> "账户总盈亏：暂无法计算（持仓行情不完整）"
            else -> {
                val marketValue = holdings.sumOf { position ->
                    quotes[position.code]?.price?.takeIf { it.isFinite() && it > 0 }?.times(position.quantity)
                        ?: byCode.getValue(position.code).marketValue
                }
                val totalAssets = marketValue + cash.value
                val pnl = totalAssets - portfolio.totalCapital
                val ratio = pnl / portfolio.totalCapital * 100
                "账户总盈亏：${DayPnl.money(pnl)}元（${DayPnl.pct(ratio)}）"
            }
        }
        val todayQuotes = quotes.filter { (code, quote) ->
            quote.code == code && quote.quoteDate == TimeUtils.todayText()
        }
        val daily = DayPnl.summarize(holdings, todayQuotes)
        val accountDaily = DayPnl.accountSummary(holdings, todayQuotes, tradeBook, TimeUtils.todayText())
        val marketLine = "大盘走势：" + when (market?.regime) {
            MarketRegime.BULL -> "此前分析显示整体向上"
            MarketRegime.WEAK -> "此前分析显示整体走弱，买入要更谨慎"
            MarketRegime.COLLAPSE -> "此前分析显示下跌风险较大，先别急着买"
            MarketRegime.NEUTRAL -> "此前分析没有明显方向，先观察"
            null -> "还没有分析结果"
        }
        if (results.isEmpty()) {
            // 等待盘中分析：账户盈亏、今日持仓与大盘走势不依赖个股结果，能算照算；
            // 「当前优先事项/风险核实/买入机会/排序口径」在无结果时全是「暂无」占位，
            // 等结果出来再渲染，别让等待态出现整屏「暂无」。
            add(accountLine)
            add(todayPerformance(daily, accountDaily, todayQuotes["sh000001"]))
            add(marketLine)
            if (holdings.isNotEmpty()) {
                add("个股分析尚未完成：优先事项、风险与买入机会等分析后生成（每 30 分钟一个槽位，可点「立即分析」重跑）。")
            }
            return@buildList
        }
        if (activeResults.size < holdings.size) {
            add("数据核实：部分持仓未分析或持仓已变化，请重新分析；不推算缺失持仓的动作")
        }
        val currentResults = holdings.mapNotNull { byCode[it.code] }
        val priorityDetail = actionableTodayPriorityDetail(activeResults, technicalTrends)
        add(accountLine)
        add(todayPerformance(daily, accountDaily, todayQuotes["sh000001"]))
        add(marketLine)
        add("整体建议：" + accountSuggestion(currentResults, portfolio) + priorityDetail.orEmpty())
        // 与完整报告同一口径：任一持仓处于 EXIT/REDUCE/WATCH/基本面复核时，本轮不新增资金。
        // 只在卡片里按本行 BUY 判断会出现“卡片说追加、报告说暂不新增”的矛盾。
        val allowBuy = activeResults.none { it.quantity > 0 && priorityDecision(it).order <= 3 }
        swingPlan?.rows
            ?.filter { row ->
                allowBuy && row.addQuantity > 0 && activeResults.firstOrNull { it.code == row.code }
                    ?.let { priorityDecision(it).state == PriorityState.BUY } == true
            }
            ?.take(PortfolioSwing.MAX_FOCUS_POSITIONS)
            ?.forEachIndexed { index, row ->
                add("追加买入机会${index + 1}：${row.name}｜${row.action()}｜${row.reason}")
            }
        val risks = buildList {
            val broken = currentResults.count { isBreakSignal(it.signal) && it.exitDecision?.stage != HoldingExitStage.HIGH_REDUCE }
            val high = currentResults.count { it.exitDecision?.stage == HoldingExitStage.HIGH_REDUCE }
            val deep = currentResults.count { DeepLossPolicy.isDeepLoss(it.cost, it.price, it.volatility.atrPct) }
            val fundamentalAlerts = currentResults.filter { it.fundamentalHealth?.level == HealthLevel.ALERT }
            if (broken > 0) add("$broken 只跌破关键价位")
            if (high > 0) add("$high 只涨到高处后开始走弱")
            if (deep > 0) add("$deep 只比买入成本跌得较多，别急着补钱")
            if (fundamentalAlerts.isNotEmpty()) {
                add(
                    "基本面风险：" + fundamentalAlerts.joinToString("；") { result ->
                        val reasons = result.fundamentalHealth?.reasons.orEmpty().joinToString("、")
                        "${result.name}：${reasons.ifBlank { "具体原因待核实" }}"
                    },
                )
            }
            if (currentResults.size < holdings.size) add("部分持仓还没分析，不能判断全部风险")
        }
        add(if (risks.isEmpty()) "需要留意：现有分析未提示额外风险，不代表没有风险" else "需要留意：${risks.joinToString("；")}")
    }

    private fun todayPerformance(
        daily: DayPnl.Summary,
        account: DayPnl.AccountSummary,
        index: Quote?,
    ): String {
        val indexPct = index?.takeIf { it.price.isFinite() && it.price > 0 && it.prevClose.isFinite() && it.prevClose > 0 }
            ?.changePct?.takeIf { it.isFinite() }
        val indexText = indexPct?.let { "沪指 ${DayPnl.pct(it)}" } ?: "沪指今日行情暂缺"
        if (daily.rows.isEmpty()) return "今日持仓：没有持仓，不计算涨跌比例；$indexText"
        val holdingPct = daily.totalPct?.takeIf { it.isFinite() }
        if (holdingPct == null || !daily.totalPnl.isFinite()) {
            return "今日持仓：暂时算不出，缺少今日现价或昨收；$indexText"
        }
        val holdingAmount = "${DayPnl.money(daily.totalPnl)}元（${DayPnl.pct(holdingPct)}）"
        if (daily.missing > 0) {
            return "今日持仓：已获取的${daily.counted}只持仓 $holdingAmount；${daily.missing}只缺少今日有效行情，暂不作整体对比；$indexText"
        }
        if (!account.complete) {
            return "今日持仓：$holdingAmount；账户今日盈亏数据不足（${account.reasons.joinToString("；")}）；$indexText"
        }
        val accountTotal = account.totalPnl ?: daily.totalPnl
        val accountPct = account.totalPct ?: holdingPct
        val accountHasTradeImpact = account.holdingsPnl != daily.totalPnl ||
            account.realizedPnl != 0.0 || account.fees != 0.0
        val amount = if (accountHasTradeImpact) {
            "账户今日 ${DayPnl.money(accountTotal)}元（${DayPnl.pct(accountPct)}）" +
                "｜当前持仓 $holdingAmount" +
                "｜已实现 ${DayPnl.money(account.realizedPnl ?: 0.0)}元" +
                "｜费用 ${String.format(Locale.CHINA, "%.2f", account.fees ?: 0.0)}元"
        } else {
            holdingAmount
        }
        val comparison = indexPct?.let {
            val gap = accountPct - it
            if (abs(gap) < 0.005) "，与沪指基本持平"
            else "，${if (gap > 0) "跑赢" else "跑输"}${String.format(Locale.CHINA, "%.2f", abs(gap))}个百分点"
        }.orEmpty()
        return "今日持仓：$amount；$indexText$comparison（账户口径含真实当日成交与费用）"
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
            results.any { priorityDecision(it).state == PriorityState.EXIT } ->
                "先处理已经触发卖出风险条件的股票，别急着再买。"
            results.any { it.fundamentalHealth?.level == HealthLevel.ALERT } ->
                "有股票的财务或公告分析提示风险，先别加仓，查看最新财报或公告；别急着卖出后接回。"
            results.any { priorityDecision(it).state == PriorityState.REDUCE } &&
                (topConcentrated || totalPositionPct != null && totalPositionPct >= 80) ->
                "买入的股票较多，或钱集中在少数股票上；先减少已确认走弱的持仓，别继续买。"
            results.any { priorityDecision(it).state == PriorityState.REDUCE } ->
                "先分批卖出一部分已确认走弱的股票，其他持仓先观察，别追着买。"
            results.any { priorityDecision(it).state == PriorityState.WATCH } ->
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

    fun overallAdvice(
        results: List<PositionResult>,
        swingPlan: SwingPlan? = null,
        divergence: DivergenceCycleState? = null,
    ): List<String> = prioritySummary(results) + adviceBody(results, swingPlan, divergence)

    /**
     * 整体建议正文。要点只回答「今天干什么」：**点名的只有要动手/要核实的票**——
     * 多只确认处置点名补齐（单只时顶部「当前优先事项」已点名不复读），
     * 风险观察、深套最多点前两只 + 总数；持有观察不逐票点评，逐票明细在逐股报告里。
     * 不含统一优先级摘要：brief 头尾各展示一次摘要，这里再内嵌就会重复两遍；
     * formatReport/DailyReview 走 [overallAdvice] 拿完整版。
     */
    private fun adviceBody(
        results: List<PositionResult>,
        swingPlan: SwingPlan? = null,
        divergence: DivergenceCycleState? = null,
    ): List<String> = buildList {
        val ranked = PortfolioPriorityPolicy.rank(results.map(::priorityInput), TimeUtils.todayText())
        val toAct = ranked.filter { it.actionConfirmed }
        if (toAct.size > 1) {
            add("今天要处理：" + toAct.joinToString("、") { "${it.input.name}（${it.state.label}）" } + "；按证据分批处置，不自动卖出")
        }
        val watching = ranked.filter { it.state == PriorityState.WATCH }
        if (watching.isNotEmpty()) {
            add(
                "风险观察：" + nameCapped(
                    watching.map { it.input.name },
                    pinned = watching.filter { it.input.fundamentalAlert }.map { it.input.name },
                ) + "；按证据核实，不自动卖出",
            )
        }
        val holding = ranked.filter { it.state == PriorityState.HOLD && !it.input.fundamentalAlert }
        if (holding.isNotEmpty()) {
            // 「不用动」要说（否则用户以为每只都得处理），但报数就够，不逐票点名。
            add("持有观察 ${holding.size} 只：未触发处置条件，暂不主动调仓")
        }
        val deepLoss = results.filter {
            it.quantity > 0 && DeepLossPolicy.isDeepLoss(it.cost, it.price, it.volatility.atrPct)
        }
        if (deepLoss.isNotEmpty()) {
            add(
                "深套处理：" + nameCapped(
                    deepLoss.map { it.name },
                    pinned = deepLoss.filter { it.fundamentalHealth?.level == HealthLevel.ALERT }.map { it.name },
                ) + "（深套口径），浮亏不自动升级或豁免风险；不因深套延后退出评估，不自动补仓或做T",
            )
        }
        swingPlan?.capitalIssue?.let { add("资金检查：$it") }
        // 分歧期矩阵（模块 C）：按大盘分歧状态 × 仓位状态给提示文案，提示不命令。
        divergence?.takeIf { it.phase != DivergencePhase.NONE }?.let { state ->
            val hasHoldings = results.any { it.quantity.isFinite() && it.quantity > 0 }
            val riskFlag = results.any {
                it.signal.contains("减仓") || it.signal.contains("退出") || it.signal.contains("风险")
            }
            DivergenceActionMatrix.promptsFor(state.phase, hasHoldings, riskFlag).forEach { add(it) }
            if (state.detail.isNotBlank()) add("分歧期依据：${state.detail}")
        }
    }

    /**
     * 点名最多两只，其余报总数：要点只留最重要的，逐票明细在逐股报告。
     * [pinned]（基本面亮红灯：退市风险警示/立案/业绩变脸级）永不折叠进「等N只」——
     * 可能引发跌停/退市的风险预警必须点名，今日要点的精简不能精简掉重大风险。
     */
    private fun nameCapped(names: List<String>, max: Int = 2, pinned: List<String> = emptyList()): String {
        val pinnedSet = pinned.toSet()
        val shown = names.filter { it in pinnedSet } + names.filter { it !in pinnedSet }.take(max)
        return if (shown.size >= names.size) shown.joinToString("、")
        else shown.joinToString("、") + " 等${names.size}只"
    }

    /** 风险点：破位 / 深套 / 集中度 / 缺数据，只列有量的那几条。 */
    private fun briefRisks(results: List<PositionResult>, dayPnl: DayPnl.Summary?): List<String> {
        val risks = mutableListOf<String>()
        val broke = results.count { isBreakSignal(it.signal) && it.exitDecision?.stage != HoldingExitStage.HIGH_REDUCE }
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
    /** 分析缺口披露：缺一只的分析结果，不等于那只没有风险。 */
    private fun analysisGapNote(
        results: List<PositionResult>,
        expectedCount: Int,
        failures: List<String>,
    ): String? {
        val analyzed = results.count { it.quantity.isFinite() && it.quantity > 0 }
        val missing = (expectedCount - analyzed).coerceAtLeast(0)
        if (missing <= 0 && failures.isEmpty()) return null
        val names = failures.distinct().joinToString("、")
        return "有 ${maxOf(missing, failures.size)} 只持仓未完成分析" +
            (if (names.isNotBlank()) "（$names）" else "") +
            "，风险未核实，不能据此认为没有风险；请重跑分析或核对行情。"
    }

    fun formatReport(
        results: List<PositionResult>,
        analyzedAt: String,
        dayPnl: DayPnl.Summary? = null,
        market: MarketState? = null,
        strongContinuationEvents: List<StrongContinuationEvent> = emptyList(),
        marketEnvironment: MarketEnvironmentQuality? = null,
        swingPlan: SwingPlan? = null,
        /** 分歧期状态（模块 A/B）：透传给操作建议的矩阵提示。 */
        divergence: DivergenceCycleState? = null,
        positionAllocations: Map<String, PositionAllocationConfig> = emptyMap(),
        /** 期望参与分析的持仓数（数量 > 0）；results 少于它说明有股票分析失败。 */
        expectedPositionCount: Int = results.count { it.quantity.isFinite() && it.quantity > 0 },
        /** 分析失败的持仓名称（可选），用于报告点名披露缺口。 */
        analysisFailures: List<String> = emptyList(),
    ): String {
        val allowBuy = results.none { it.quantity > 0 && priorityDecision(it).order <= 3 }
        val effectiveSwing = swingPlan?.copy(rows = swingPlan.rows.map { row ->
            if (allowBuy && results.any { it.code == row.code && it.priorityBuyApproved }) row
            else row.copy(addQuantity = 0.0, estimatedCash = 0.0, afterPositionPct = null, reason = "未通过统一风险与数据检查，暂不新增")
        })
        val lines = mutableListOf(
            "📦 持仓信息 / 盘中技术监控",
            "最近分析：$analyzedAt",
        )
        // 开头给「今日要点」：十来段的长报告，想看「今天要干什么」不该翻到底。
        lines.add("")
        lines.add("持仓整体操作建议")
        val gap = analysisGapNote(results, expectedPositionCount, analysisFailures)
        gap?.let { lines.add("· $it") }
        overallAdvice(results, effectiveSwing, divergence)
            // 有分析缺口时不能再说“暂无已确认的买卖动作”：没分析不等于没动作。
            .filterNot { gap != null && it.startsWith("当前优先事项：暂无已确认的买卖动作") }
            .forEach { lines.add("· $it") }
        lines.add("")
        lines.add("📌 今日要点")
        brief(results, dayPnl, market, effectiveSwing, divergence)
            .filterNot { it.startsWith("持仓整体操作建议：") }.forEach { lines.add("· $it") }
        effectiveSwing?.let {
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
        var accelerationTagShown = false
        for (result in priorityOrdered(results)) {
            val decision = priorityDecision(result)
            val swing = effectiveSwing?.rows?.firstOrNull { it.code == result.code }
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
                "${result.name}（${result.code}）｜状态：${decision.state.label}｜评分 ${String.format(Locale.CHINA, "%+d", result.score)}"
            )
            lines.add(
                "当前价 ${String.format(Locale.CHINA, "%.2f", result.price)}" +
                    "（较前一日 ${String.format(Locale.CHINA, "%+.2f%%", result.changePct)}$dayText）｜$positionText$pctText"
            )
            lines.add(
                "分仓：${PositionAllocationPolicy.derive(
                    Position(result.code, result.name, result.quantity, result.cost),
                    positionAllocations[result.code],
                ).displayText}"
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
                lines.add("加仓诊断：${swing.diagnosis.summary()}")
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
            result.highPositionEvidence?.let { evidence ->
                val label = AccelerationPolicy.watchLabel(
                    AccelerationPolicy.assess(
                        price = result.price,
                        run5Pct = evidence.gain5Pct,
                        volumeRatio = result.volumeRatio,
                        high60 = evidence.rangeHigh,
                        todayPct = result.changePct,
                    ),
                )
                label?.let {
                    accelerationTagShown = true
                    lines.add(it)
                }
            }
            result.fundamentalHealth?.let { health ->
                lines.add(
                    "基本面：${health.level.label}｜${health.reasons.joinToString("；")}｜判断于 ${health.analyzedAt}" +
                        "（财务与公告可见面，不含行业景气与订单）"
                )
            } ?: lines.add("基本面：未分析或健康度已过期，不作为“无风险”的证据；需要时在「工具」页补拉")
            lines.add("应对：${decision.reason}")
            lines.add("数据口径：行情${result.analysisDate.ifBlank { "未知" }}｜技术${result.priorityEvidenceDate.ifBlank { "未知" }}｜前复权技术位｜${PortfolioPriorityPolicy.VERSION}")
            lines.add("")
        }
        if (accelerationTagShown) {
            lines.add("研究观察：放量加速/高位分歧来自历史事件研究，未实盘验证，不参与评分与动作。")
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
                val broken = results.count { isBreakSignal(it.signal) && it.exitDecision?.stage != HoldingExitStage.HIGH_REDUCE }
                if (broken > 0) counter += "持仓中有${broken}只出现破位，个股风险优先"
                val highReduce = results.count { it.exitDecision?.stage == HoldingExitStage.HIGH_REDUCE }
                if (highReduce > 0) counter += "持仓中有${highReduce}只高位转弱，优先减仓"
                return DecisionReview(support, counter, missing, invalidators)
            }
        }
    }
}
