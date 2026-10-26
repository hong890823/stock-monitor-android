package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DualTimeframeDecision
import com.example.stockmonitor.core.DailyRiskContext
import com.example.stockmonitor.core.IntradayVolumeContext
import com.example.stockmonitor.core.TimeframeEvidence
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** 盘中买卖点 / 做 T 提醒的类型。 */
enum class IntradaySignalType(val label: String) {
    BREAK_SUPPORT("跌破支撑"),
    REDUCE_POSITION("确认破位·分批减仓"),
    HIGH_REDUCE_POSITION("高位转弱·优先减仓"),
    EXIT_POSITION("退出优先"),
    RECLAIM_WATCH("站回待确认"),
    RECLAIM_SUPPORT("站回支撑"),
    EXIT_WINDOW("反弹至压力"),
    BREAKOUT("放量突破"),
    T_SELL("冲高做T"),
    T_SELL_DEFERRED("冲高回落·暂缓做T"),
    T_HOLD("强势延续观察"),
    T_HOLD_CAUTION("高成交额谨慎观察"),
    T_TREND_HOLD("趋势延续观察"),
    T_BUY("接回观察"),
    PULLBACK("回踩支撑"),
    BOTTOM_REVERSAL("底部反转观察"),
    HIGH_CONSOLIDATION("高位整理蓄势"),
    SECOND_WAVE_CANDIDATE("二波候选·等待突破"),
    SECOND_WAVE("二波突破确认"),
    BREAKOUT_REVERSAL_RISK("突破延续下杀风险"),
    BREAK_NOTICE("破位风险提示"),
    TREND_WATCH("趋势条件待确认"),
    TIMEFRAME_WATCH("周期条件待确认");

    val isRisk: Boolean
        get() = this in setOf(
            BREAK_SUPPORT,
            REDUCE_POSITION,
            HIGH_REDUCE_POSITION,
            EXIT_POSITION,
            BREAKOUT_REVERSAL_RISK,
            BREAK_NOTICE,
        )

    val isPureObservation: Boolean
        get() = this in setOf(
            TREND_WATCH,
            TIMEFRAME_WATCH,
            RECLAIM_WATCH,
            T_SELL_DEFERRED,
            T_HOLD,
            T_HOLD_CAUTION,
            T_TREND_HOLD,
            HIGH_CONSOLIDATION,
            SECOND_WAVE_CANDIDATE,
        )

    /** 只有会直接形成买入、普通止盈/减仓或做T动作的信号需要15/30分钟确认。 */
    val requiresDualTimeframe: Boolean
        get() = this in setOf(
            BREAKOUT,
            RECLAIM_SUPPORT,
            EXIT_WINDOW,
            T_SELL,
            T_BUY,
            PULLBACK,
            BOTTOM_REVERSAL,
            SECOND_WAVE,
        )
}

/** 一条盘中提醒：标题进通知栏，正文带数量建议或大体操作。 */
data class IntradaySignal(
    val type: IntradaySignalType,
    val title: String,
    val body: String,
    val holdingExitStage: HoldingExitStage? = null,
)

/**
 * 盘中买卖点 / 做 T 的判定。
 *
 * 每 15 秒跑一次，只判断「有没有刚刚触发」，不重算任何指标：支撑 / 压力 / 评分来自最近一次
 * 持仓分析（30 分钟一次或手动触发），实时价、量比、位置来自行情刷新，所以不额外打接口。
 *
 * 触发看的是**跃迁**而不是状态：只有从「没触发」变成「触发」才算一次，调用方另外做冷却与
 * 每日上限。否则价格贴在阈值上来回抖，会每 15 秒震一次。
 *
 * 阈值按板块涨跌停幅度缩放（主板 3%、创业板/科创板 6%、北交所 9%），
 * 固定 3% 对 20% 涨跌幅的创业板几乎等于没有波动。
 */
object IntradayTactics {

    /** 做 T 的涨跌幅阈值 = 板块涨跌停幅度 × 该系数。 */
    const val T_RATIO = 0.3

    /** 做 T 建议减出的仓位比例。 */
    const val T_PART = 1.0 / 3.0

    /** 建议数量按手取整。 */
    const val LOT = 100.0

    /** 单次做T减出金额不超过当日成交额的这个比例，避免把流动性不足误当成可执行信号。 */
    const val MAX_T_ORDER_TURNOVER_SHARE = 0.02

    /** 跌破支撑的容差：要低于支撑这么多才算破，避免贴着支撑线来回摩擦。 */
    const val BREAK_TOLERANCE = HoldingExitPolicy.BREAK_TOLERANCE

    /**
     * 同板块龙头仍强（封板，或炸板守住高位线）时，做 T 需要这么深的回落才恢复给减仓股数。
     *
     * 龙头强往往意味着板块还有空间，冲高后的第一次回落就下车容易卖飞——
     * 所以浅回落只提示「暂缓」。跟风小票强势不参与降级。但**不静音**：
     * 回落真加深到这个倍数，照样给做 T 股数。
     */
    const val DEEP_RETREAT_FACTOR = 2.0

    /** 提醒正文里最多列出几只强势龙头，供人工核对。 */
    const val MAX_STRONG_LEADER_NAMES = 3

    /** 回踩的容差：现价在支撑上方这么多以内算「回踩到位」。 */
    const val PULLBACK_TOLERANCE = 0.02

    /** 反弹到压力位附近的容差：距压力这么多以内算「反弹到位」（深套红灯的离场窗口）。 */
    const val EXIT_NEAR_PCT = 0.02

    /** 风险信号立即提醒，可执行信号连续两轮（约 30 秒）确认；纯观察状态不进入通知。 */
    fun confirmationRounds(type: IntradaySignalType): Int = when {
        type.isPureObservation -> 0
        type.isRisk -> 1
        else -> 2
    }

    /** 双周期不满足时只保留状态，不把原动作和股数继续带进通知。 */
    fun watchOnly(
        signal: IntradaySignal,
        decision: DualTimeframeDecision,
    ): IntradaySignal {
        require(!signal.type.isRisk && signal.type.requiresDualTimeframe) { "该信号不适用周期降级" }
        require(!decision.allowed) { "已满足周期条件，不能降级为观察" }
        return IntradaySignal(
            type = IntradaySignalType.TIMEFRAME_WATCH,
            title = "${signal.title}｜仅观察",
            body = "原触发：${signal.title}。${decision.reason}。" +
                "当前仅观察，不执行买入、做T或普通止盈/减仓；风险卖出仍按最新已完成15分钟证据评估。",
        )
    }

    /** 判定所需的输入；没有分析结果时把 support / resistance / score 传 0。 */
    data class Input(
        val code: String,
        val name: String,
        val price: Double,
        val changePct: Double,
        val volumeRatio: Double?,
        /** 板块涨跌停幅度，见 SinaClient.limitPctFor。 */
        val limitPct: Double,
        val support: Double,
        val resistance: Double,
        val score: Int,
        val quantity: Double,
        val cost: Double,
        val totalCapital: Double,
        val availableCapital: Double,
        val initialEntryPct: Double,
        val maxPositionPct: Double,
        /** 当日可卖数量；为空时兼容旧调用，按总持仓处理。 */
        val sellableQuantity: Double? = null,
        /** 当日不可卖数量（当日买入 + 未记录买入日期的残股，见 [untrackedQuantity]）。 */
        val lockedQuantity: Double = 0.0,
        /**
         * [lockedQuantity] 里没有买入日期的部分（手工加仓没记批次）。
         * 不会随交易日解冻，要补记买入日期；提醒文案与「今天买入」分开说。
         */
        val untrackedQuantity: Double = 0.0,
        /** 最近一次技术分析生成的趋势入场上下文；为空时保留兼容性的旧规则。 */
        val entrySetup: EntrySetup? = null,
        /**
         * 反弹质量跟踪已判「真反弹企稳」（见 [ReboundTracker]）。
         * 接回 / 站回支撑拿它当确认源之一：企稳已经过站稳时间与承接量能验证，
         * 不必要求价格正好回踩到支撑边才敢确认。
         *
         * **只影响时机确认，不影响趋势判定**：它永远不能顶开趋势闸门——
         * [trendAssessment] 不许 T 买或 riskOnly 时，确认了也不给接回；趋势仍由日线口径说了算。
         */
        val reboundConfirmed: Boolean = false,
        val riskPerTradePct: Double = TrendEntryStrategy.RISK_PER_TRADE_PCT,
        val ma20: Double = 0.0,
        val ma5: Double = 0.0,
        /** 最近一次基本面健康判断（按需补拉的缓存）；为空时破位文案不带基本面倾向。 */
        val fundamentalHealth: FundamentalHealth? = null,
        /** ATR%（波动率），用于把深套阈值按个股波动缩放；为空退回固定阈值。 */
        val atrPct: Double? = null,
        /** 今日是否曾跌破支撑（由调用方按行情逐轮维护），决定「站回 / 反抽失败」的判定。 */
        val brokenSupport: Boolean = false,
        /** 跌破之后是否曾重新站回支撑，用于区分「首次破位」和「反抽失败再度破位」。 */
        val reclaimedSupport: Boolean = false,
        /**
         * 大盘状态。塌陷期**买入侧信号一律压制**——「回踩接回」「站回补仓」「试仓」在
         * 系统性下跌里都是接飞刀；减仓侧不受影响，反而更该提醒。
         */
        val marketRegime: MarketRegime = MarketRegime.NEUTRAL,
        /** 板块状态；无归属时为 null。塌陷与大盘塌陷同等对待。 */
        val sectorRegime: MarketRegime? = null,
        /** 当前板块涨跌；仅用于盘中确认文案，不单独生成信号。 */
        val sectorChangePct: Double? = null,
        /** 当前板块成交额增量相对近期同周期中位数的倍数。 */
        val sectorTurnoverRatio: Double? = null,
        /** 当前板块主力资金净流入，单位元；仅作辅助确认。 */
        val sectorMainNetInflow: Double? = null,
        /** 当前板块主力资金净占比，单位百分比；仅作辅助确认。 */
        val sectorMainNetRatio: Double? = null,
        /**
         * 同板块**龙头**里此刻仍强的家数（封板，或炸板守住高位线）；null 表示龙头名单暂缺，
         * 不做降级判定，退回原做 T 口径。
         *
         * 只认龙头，**跟风小票不算**——龙头强才说明板块还有空间，小票涨停多是情绪跟风。
         * 强弱用实时行情逐轮判，所以龙头一走弱这里立刻变 0，做 T 提醒跟着恢复。
         */
        val strongLeaderCount: Int? = null,
        /** 此刻仍强的龙头名，进提醒正文供人工核对（如「亨通光电」）。 */
        val strongLeaderNames: List<String> = emptyList(),
        /** 当日累计成交额，单位亿元；只用于做T股数的流动性过滤。 */
        val todayTurnoverYi: Double? = null,
        /** 两市全天预估成交额，单位亿元；缺失时不启用强势过滤。 */
        val predictedMarketTurnoverYi: Double? = null,
        /** 沪指当日涨跌幅；缺失时不启用强势过滤。 */
        val marketChangePct: Double? = null,
        /** 个股当日最高价；缺失时不启用强势过滤。 */
        val dailyHighPrice: Double? = null,
        val marketIndexPrice: Double? = null,
        val marketIndexHighPrice: Double? = null,
        val industryBreadth: Double? = null,
        val conceptBreadth: Double? = null,
        val recentMarketTurnoversYi: List<Double> = emptyList(),
        val marketTurnoverRatio: Double? = null,
        val impulse: ImpulseContext? = null,
        val holdingTContext: HoldingTContext? = null,
        val holdingTObservation: HoldingTObservation? = null,
        val breakObservation: HoldingBreakObservation? = null,
        val openPrice: Double? = null,
        val previousClose: Double? = null,
        val priceContext: PriceContext? = null,
        val trendAssessment: TrendAssessment? = null,
        val boxRange: BoxRangeAssessment? = null,
        val highPosition: HighPositionDecision? = null,
        /** 行情数据源交易日；缺失时统一动作守卫只允许观察。 */
        val tradeDate: String? = null,
        val dailyRiskContext: DailyRiskContext? = null,
        val timeframeEvidence: TimeframeEvidence? = null,
        val volumeContext: IntradayVolumeContext? = null,
        val ledgerConsistent: Boolean = true,
        /** 真实卖出后尚未接回的数量，用于接回/做T买入资格。 */
        val reentryCapacityQuantity: Double = 0.0,
    ) {
        /** 大盘或板块任一塌陷。 */
        val collapse: Boolean
            get() = marketRegime == MarketRegime.COLLAPSE || sectorRegime == MarketRegime.COLLAPSE

        /** 大盘或板块任一转弱（含塌陷）。 */
        val weakEnv: Boolean
            get() = collapse ||
                marketRegime == MarketRegime.WEAK ||
                sectorRegime == MarketRegime.WEAK
    }

    fun buyCapacityQuantity(input: Input): Double {
        if (input.totalCapital <= 0 || input.price <= 0 || input.initialEntryPct <= 0) return 0.0
        input.entrySetup?.let { setup ->
            if (setup.canBuy && setup.stopPrice != null) {
                return TrendEntryStrategy.suggestedQuantity(
                    price = input.price,
                    stopPrice = setup.stopPrice,
                    totalCapital = input.totalCapital,
                    initialEntryPct = input.initialEntryPct,
                    maxPositionPct = input.maxPositionPct,
                    riskPerTradePct = input.riskPerTradePct,
                )
            }
        }
        return floor(input.totalCapital * input.initialEntryPct / 100.0 / input.price / LOT) * LOT
    }

    fun evaluate(input: Input): IntradaySignal? {
        if (!input.price.isFinite() || input.price <= 0) return null
        val holding = input.quantity > 0
        val deep = DeepLossPolicy.isDeepLoss(input.cost, input.price, input.atrPct)
        val support = input.breakObservation?.support ?: input.support
        val exitDecision = HoldingExitPolicy.evaluate(HoldingExitPolicy.Input(
            price = input.price, support = support, quantity = input.quantity, cost = input.cost,
            atrPct = input.atrPct, volumeRatio = input.volumeRatio, trendWeak = input.entrySetup?.trendWeak == true,
            confirmed = input.breakObservation?.confirmed == true, exitPrice = input.breakObservation?.exitPrice,
            priceContext = input.priceContext,
            changePct = input.changePct, holdingTContext = input.holdingTContext,
            holdingTObservation = input.holdingTObservation,
            fundamentalAlert = input.fundamentalHealth?.level == HealthLevel.ALERT,
            synchronizedWeakness = input.marketRegime in setOf(MarketRegime.WEAK, MarketRegime.COLLAPSE) &&
                input.sectorRegime in setOf(MarketRegime.WEAK, MarketRegime.COLLAPSE),
            gapDown = input.openPrice?.let { it.isFinite() && it > 0 && it < support * (1 - BREAK_TOLERANCE) } == true &&
                input.previousClose?.let { it.isFinite() && it >= support } == true,
            positionPct = if (input.totalCapital.isFinite() && input.totalCapital > 0) {
                input.quantity * input.price / input.totalCapital * 100
            } else {
                null
            },
        ))
        if (exitDecision != null) return breakSupport(input, support, exitDecision)
        val normalDivergence = NormalDivergencePolicy.evaluateIntraday(
            NormalDivergenceIntradayInput(
                price = input.price,
                ma5 = input.ma5,
                ma20 = input.ma20,
                priceLocation = input.priceContext?.location,
                biasFromMa5Pct = input.ma5.takeIf { it > 0.0 }?.let { (input.price / it - 1.0) * 100.0 },
                atrPct = input.atrPct,
                volumeRatio = input.volumeRatio,
                priceChangePct = input.changePct,
                lastBarRejected = input.timeframeEvidence?.lastBarRejected == true,
                riskLineThresholdPct = input.timeframeEvidence?.riskLineThresholdPct ?: 0.0,
                riskLineBelow = input.timeframeEvidence?.riskLineBelow == true,
                riskLineConfirmations = input.timeframeEvidence?.riskLineConfirmations ?: 0,
                riskLineM30Below = input.timeframeEvidence?.riskLineM30Below == true,
                hasCompleted15m = input.timeframeEvidence?.complete == true,
                hasCompleted30m = input.timeframeEvidence?.complete == true,
                backgroundTrendWeak = input.trendAssessment?.major == MajorTrend.DOWN ||
                    input.trendAssessment?.shortTerm == ShortTermTrend.DOWN,
            ),
        )
        val divergenceAllowsNewAction = normalDivergence.level == NormalDivergenceLevel.NORMAL ||
            normalDivergence.level == NormalDivergenceLevel.MISSING
        val breakoutRisk = BreakoutRiskPolicy.evaluate(
            BreakoutRiskEvidence(
                price = input.price,
                dailyHighPrice = input.dailyHighPrice,
                breakoutReference = input.entrySetup?.priorHigh?.takeIf { it.isFinite() && it > 0.0 }
                    ?: input.resistance.takeIf { it.isFinite() && it > 0.0 },
                changePct = input.changePct,
                volumeRatio = input.volumeRatio,
                atrPct = input.atrPct,
                continuation = BreakoutRiskPolicy.continuation(
                    entryType = input.entrySetup?.type,
                    highPositionPhase = input.highPosition?.phase,
                ),
                priceLocation = input.priceContext?.location,
                ma5 = input.ma5.takeIf { it > 0.0 },
                ma20 = input.ma20.takeIf { it > 0.0 },
                biasFromMa5Pct = input.ma5.takeIf { it > 0.0 }?.let { (input.price / it - 1.0) * 100.0 },
            ),
        )
        if (breakoutRisk.risk) return breakoutReversalRisk(input, breakoutRisk)
        if (input.highPosition?.riskPriority == true && holding) {
            return highDistributionRisk(input, input.highPosition)
        }
        input.trendAssessment?.let { trend ->
            if (trend.major == MajorTrend.MISSING) return trendWatch(input, trend)
            if (trend.riskOnly) return null
        }
        val highPosition = input.highPosition
        if (highPosition?.phase == HighPositionPhase.SECOND_WAVE_CONFIRMED &&
            highPosition.allowSecondWaveEntry
        ) {
            return secondWave(input, highPosition)
        }
        if (highPosition?.phase == HighPositionPhase.SECOND_WAVE_CANDIDATE) {
            return secondWaveCandidate(input, highPosition)
        }
        if (highPosition?.phase == HighPositionPhase.CONSOLIDATION && input.score >= 2) {
            return highConsolidation(input, highPosition)
        }
        if (input.trendAssessment?.major == MajorTrend.BOTTOMING) {
            val nearSupport = input.support > 0 &&
                input.price >= input.support &&
                input.price <= input.support * (1 + PULLBACK_TOLERANCE)
            return if (input.trendAssessment.allowsBottomReversal &&
                input.score >= 2 &&
                !input.weakEnv &&
                nearSupport
            ) {
                bottomReversal(input, input.trendAssessment)
            } else {
                null
            }
        }
        val boxAllowsBuy = input.boxRange == null ||
            input.boxRange.state in setOf(BoxRangeState.NOT_BOX, BoxRangeState.BREAKOUT_CONFIRMED)
        val ordinaryAllowed = input.trendAssessment?.allowsOrdinaryBuy != false &&
            boxAllowsBuy && divergenceAllowsNewAction
        val tBuyAllowed = input.trendAssessment?.allowsTBuy != false &&
            boxAllowsBuy && divergenceAllowsNewAction
        val tSellAllowed = input.trendAssessment?.allowsTSell != false
        val highAllowsNewBuy = highPosition == null ||
            highPosition.phase == HighPositionPhase.NONE ||
            highPosition.allowBuy
        if (input.volumeRatio?.isFinite() == false) return null
        val expanded = input.volumeRatio?.let { it >= VolumeRatio.EXPAND_THRESHOLD } == true
        val setup = input.entrySetup
        val breakout = when {
            setup != null -> setup.type == EntrySetupType.BREAKOUT &&
                setup.confirmed &&
                (holding || setup.canBuy) &&
                setup.priorHigh != null &&
                input.price >= setup.priorHigh * (1 + TrendEntryStrategy.BREAKOUT_BUFFER_PCT / 100.0) &&
                expanded
            else -> holding && input.resistance > 0 && input.price > input.resistance && expanded
        }
        val pullback = when {
            setup != null -> setup.type == EntrySetupType.M20_PULLBACK &&
                setup.confirmed &&
                setup.canBuy &&
                input.ma20 > 0 &&
                input.price >= input.ma20 * (1 - TrendEntryStrategy.MA20_BREAK_TOLERANCE_PCT / 100.0) &&
                input.price <= input.ma20 * (1 + TrendEntryStrategy.PULLBACK_ABOVE_MA20_PCT / 100.0) &&
                input.volumeRatio?.let { it <= TrendEntryStrategy.PULLBACK_VOLUME_RATIO } == true
            else -> false
        }
        val red = input.fundamentalHealth?.level == HealthLevel.ALERT
        val context = input.holdingTContext
        val observation = input.holdingTObservation
        val rhythm = adaptiveRhythm(input)
        val tReady = context != null && observation != null &&
            input.changePct.isFinite() && input.limitPct.isFinite() && input.limitPct > 0 &&
            abs(input.changePct) < input.limitPct - 0.2
        // 不用 context!!：tReady 为假时 context 可能是 null，这里在短路外求值。
        val baseRetreatNeed = context?.atrPct?.let { maxOf(0.5, it * 0.25) } ?: 0.5
        // 强节奏下浅回落先进入暂缓观察；弱节奏仍按基础阈值及时给做 T 提醒。
        val rolloverNeed = baseRetreatNeed * if (rhythm.retreatFactor > 1.0) 0.5 else 1.0
        val rollover = tReady && observation!!.falling && observation.retreatPct >= rolloverNeed
        val sectorHot = leadersStrong(input)
        // 市场节奏动态决定浅回落容忍度；板块龙头仍强时继续保留原来的额外缓冲。
        val deepRetreatFactor = maxOf(rhythm.retreatFactor, if (sectorHot) DEEP_RETREAT_FACTOR else 1.0)
        val deepRollover = tReady && observation!!.falling &&
            observation.retreatPct >= baseRetreatNeed * deepRetreatFactor
        val rally = input.changePct >= tThreshold(input) ||
            (context?.trend == HoldingTTrend.EXTENDED && input.changePct > 0 && atResistance(input))
        return when {
            // 跌破后重新站回支撑：深套的企稳确认窗口；基本面红灯时优先离场。
            holding && input.brokenSupport && input.support > 0 &&
                input.price >= input.support &&
                input.price <= input.support * (1 + PULLBACK_TOLERANCE) ->
                reclaimSupport(input, deep, red)
            // 深套 + 基本面红灯的反弹至压力：分批离场窗口。
            holding && deep && red && input.resistance > 0 &&
                input.price >= input.resistance * (1 - EXIT_NEAR_PCT) &&
                input.price <= input.resistance * (1 + EXIT_NEAR_PCT) ->
                exitWindow(input)
            ordinaryAllowed && breakout && !rollover ->
                breakout(input, holding)
            tSellAllowed && holding && tReady && rally && !rollover && !input.weakEnv && !red &&
                context!!.strongDirection && stockNearHigh(input) -> tTrendHold(input)
            tSellAllowed && holding && tReady && rally && !rollover && !input.weakEnv && !red &&
                (expanded || atResistance(input)) && strongContinuation(input, expanded) ->
                tHold(input)
            tSellAllowed && holding && tReady && rally && !rollover && !input.weakEnv && !red &&
                (expanded || atResistance(input)) && cautiousContinuation(input, expanded) ->
                tHoldCaution(input)
            // 板块龙头仍强：冲高后的首次回落先不给减仓股数，龙头强势途中下车容易卖飞。
            tSellAllowed && holding && rollover && !deepRollover && sectorHot && rally &&
                (expanded || atResistance(input)) -> tSellDeferred(input, expanded)
            tSellAllowed && holding && rollover && !deepRollover && rhythm.retreatFactor > 1.0 && rally &&
                (expanded || atResistance(input)) -> tSellDeferred(input, expanded)
            // 回落真加深了（或板块无涨停）照样给做T股数，不静音。
            tSellAllowed && holding && deepRollover && rally &&
                (expanded || atResistance(input)) -> tSell(input, expanded)
            // 买入侧：转弱就别接回，塌陷直接不提——系统性下跌里接回是接飞刀。
            tBuyAllowed && highAllowsNewBuy && holding && input.changePct <= -tThreshold(input) &&
                input.support > 0 && input.price >= input.support &&
                input.price <= input.support * (1 + PULLBACK_TOLERANCE) && confirmedTRebound(input) ->
                tBuy(input)
            ordinaryAllowed && highAllowsNewBuy && !holding && pullback &&
                input.score >= 2 && !input.weakEnv ->
                pullback(input)
            ordinaryAllowed && highAllowsNewBuy && setup == null && !holding &&
                input.support > 0 && input.score >= 2 &&
                input.price >= input.support &&
                input.price <= input.support * (1 + PULLBACK_TOLERANCE) && !input.weakEnv -> pullback(input)
            else -> null
        }
    }

    private fun trendWatch(input: Input, trend: TrendAssessment): IntradaySignal = IntradaySignal(
        IntradaySignalType.TREND_WATCH,
        "👀 ${input.name}·趋势条件待确认",
        "${trend.summary}；${trend.reason}。当前不执行买入、接回或做T，风险卖出仍按15分钟风险信号即时提醒。",
    )

    private fun highDistributionRisk(
        input: Input,
        decision: HighPositionDecision,
    ): IntradaySignal = IntradaySignal(
        IntradaySignalType.HIGH_REDUCE_POSITION,
        "⚠️ ${input.name}·高位派发风险",
        "现价 ${price(input.price)}；${decision.reason}。停止买入和接回，持仓优先减仓或退出；" +
            "若跌破 ${price(input.support)}，按破位风险继续升级。",
    )

    private fun breakoutReversalRisk(
        input: Input,
        decision: BreakoutRiskDecision,
    ): IntradaySignal = IntradaySignal(
        IntradaySignalType.BREAKOUT_REVERSAL_RISK,
        "⚠️ ${input.name}·突破延续下杀风险",
        "${decision.reason}；持仓优先减仓或退出，未持仓禁止追买，暂停接回和做T。",
    )

    private fun bottomReversal(input: Input, trend: TrendAssessment): IntradaySignal {
        val fullEntry = entryQuantity(input)
        val trialEntry = floor(fullEntry * 0.25 / LOT) * LOT
        val quantityText = if (trialEntry > 0) "可观察试错约 ${qty(trialEntry)} 股" else "数量需按25%初始仓位重新核算"
        return IntradaySignal(
            IntradaySignalType.BOTTOM_REVERSAL,
            "🌱 ${input.name}·底部反转观察",
            "现价 ${price(input.price)}；${trend.summary}。$quantityText，最多按初始仓位25%轻仓试错，" +
                "禁止正常加仓和做T；重新跌破 ${price(input.support)}则筑底窗口失效。${sectorBuyNote(input)}",
        )
    }

    private fun highConsolidation(
        input: Input,
        decision: HighPositionDecision,
    ): IntradaySignal = IntradaySignal(
        IntradaySignalType.HIGH_CONSOLIDATION,
        "📊 ${input.name}·高位整理蓄势",
        "现价 ${price(input.price)}；${decision.reason}。只保留底仓或继续观察，未突破并确认前不买入、不接回。",
    )

    private fun secondWaveCandidate(
        input: Input,
        decision: HighPositionDecision,
    ): IntradaySignal = IntradaySignal(
        IntradaySignalType.SECOND_WAVE_CANDIDATE,
        "📈 ${input.name}·二波候选",
        "现价 ${price(input.price)}；${decision.reason}。等待突破确认：突破 ${price(input.resistance)} 并获得量能确认，" +
            "当前不执行买入、接回或做T。",
    )

    private fun secondWave(input: Input, decision: HighPositionDecision): IntradaySignal {
        val stop = input.entrySetup?.stopPrice ?: input.support.takeIf { it > 0 }
        val stopText = stop?.let { "止损参考 ${price(it)}" } ?: "止损参考暂缺"
        val action = if (input.quantity > 0) {
            "持仓可继续持有底仓，不追高加仓；跌破 ${price(stop ?: input.support)}则二波结构失效。"
        } else {
            val entry = entryQuantity(input)
            if (entry > 0) {
                "可按风险试仓约 ${qty(entry)} 股，不追高加仓。"
            } else {
                "按初始仓位重新核算，不追高加仓。"
            }
        }
        return IntradaySignal(
            IntradaySignalType.SECOND_WAVE,
            "🚀 ${input.name}·二波突破确认",
            "现价 ${price(input.price)}；${decision.reason}。$action$stopText。",
        )
    }

    /** 做 T 的当日涨跌幅阈值（百分数）。 */
    fun tThreshold(input: Input): Double = input.limitPct * T_RATIO

    private fun confirmedTRebound(input: Input): Boolean {
        val context = input.holdingTContext ?: return false
        val observed = input.holdingTObservation ?: return false
        if (input.weakEnv || input.fundamentalHealth?.level == HealthLevel.ALERT) return false
        if (!input.changePct.isFinite() || !input.limitPct.isFinite() || input.limitPct <= 0 ||
            abs(input.changePct) >= input.limitPct - 0.2
        ) {
            return false
        }
        // 反弹跟踪已判「真反弹企稳」：站稳时间与承接都验证过，接回/站回只需确认
        // 当前不在回落、量能没爆表——比等 3 分钟观察窗的即时判定更及时。
        if (input.reboundConfirmed) {
            return !observed.falling && input.volumeRatio?.let { it > 0 && it <= 1.2 } == true
        }
        val required = maxOf(0.3, context.atrPct * 0.15) * if (context.cautiousBuy) 2 else 1
        return input.volumeRatio?.let { it > 0 && it <= 1.0 } == true &&
            observed.rising && observed.reboundPct >= required &&
            observed.low >= input.support * (1 - BREAK_TOLERANCE)
    }

    internal fun marketEnvironment(input: Input): MarketEnvironmentQuality {
        val fallbackTurnover = MarketTurnoverFallbackPolicy.evaluate(
            predictedTurnoverYi = input.predictedMarketTurnoverYi,
            recentTurnoversYi = input.recentMarketTurnoversYi,
        )
        val source = when {
            input.marketTurnoverRatio?.let { it.isFinite() && it > 0.0 } == true ->
                MarketTurnoverSource.SAME_TIME
            fallbackTurnover != null -> fallbackTurnover.source
            else -> null
        }
        return MarketEnvironmentQuality.evaluateAdaptive(
        marketTurnoverRatio = input.marketTurnoverRatio ?: fallbackTurnover?.ratio,
        marketTurnoverSource = source,
        marketChangePct = input.marketChangePct,
        marketPrice = input.marketIndexPrice,
        marketHighPrice = input.marketIndexHighPrice,
        industryBreadth = input.industryBreadth,
        conceptBreadth = input.conceptBreadth,
        )
    }

    private fun adaptiveRhythm(input: Input): AdaptiveRhythmDecision {
        val fallbackTurnover = MarketTurnoverFallbackPolicy.evaluate(
            predictedTurnoverYi = input.predictedMarketTurnoverYi,
            recentTurnoversYi = input.recentMarketTurnoversYi,
        )
        val turnoverSource = when {
            input.marketTurnoverRatio?.let { it.isFinite() && it > 0.0 } == true ->
                MarketTurnoverSource.SAME_TIME
            fallbackTurnover != null -> fallbackTurnover.source
            else -> null
        }
        val relativeTurnover = input.marketTurnoverRatio ?: fallbackTurnover?.ratio
        return AdaptiveRhythmPolicy.evaluate(
            AdaptiveRhythmEvidence(
                marketTurnoverRatio = relativeTurnover,
                marketTurnoverSource = turnoverSource,
                marketChangePct = input.marketChangePct,
                industryBreadth = input.industryBreadth,
                conceptBreadth = input.conceptBreadth,
                marketRegime = input.marketRegime,
                shortTermTrend = input.trendAssessment?.shortTerm,
                majorTrend = input.trendAssessment?.major,
                impulse = input.impulse,
                atrPct = input.atrPct,
                volumeRatio = input.volumeRatio,
                changePct = input.changePct,
                biasFromMa5Pct = input.ma5.takeIf { it.isFinite() && it > 0.0 }
                    ?.let { (input.price / it - 1.0) * 100.0 },
            ),
        )
    }

    private fun strongContinuation(input: Input, expanded: Boolean): Boolean =
        expanded && input.sectorChangePct?.let { it.isFinite() && it >= 0.0 } == true &&
            stockNearHigh(input) && marketEnvironment(input).strong

    private fun cautiousContinuation(input: Input, expanded: Boolean): Boolean =
        expanded && input.sectorChangePct?.let { it.isFinite() && it >= 0.0 } == true &&
            stockNearHigh(input) && marketEnvironment(input).cautious

    private fun stockNearHigh(input: Input): Boolean = input.dailyHighPrice?.let {
        it.isFinite() && it > 0.0 && (it - input.price) / it < 0.01
    } == true

    /**
     * 同板块龙头此刻是否仍强（封板或炸板守住高位线）。龙头名单暂缺时按「没有」处理，退回原来的做 T 口径——
     * 宁可照常提醒，也不能因为拿不到板块成分就把减仓提示吞掉。
     */
    private fun leadersStrong(input: Input): Boolean = (input.strongLeaderCount ?: 0) > 0

    private fun atResistance(input: Input): Boolean =
        input.resistance > 0 && input.price >= input.resistance * 0.99

    private fun breakSupport(input: Input, support: Double, decision: HoldingExitDecision): IntradaySignal {
        val cost = pnlText(input)
        val type = when (decision.stage) {
            HoldingExitStage.REDUCE -> IntradaySignalType.REDUCE_POSITION
            HoldingExitStage.HIGH_REDUCE -> IntradaySignalType.HIGH_REDUCE_POSITION
            HoldingExitStage.EXIT -> IntradaySignalType.EXIT_POSITION
            HoldingExitStage.NOTICE -> IntradaySignalType.BREAK_NOTICE
            else -> IntradaySignalType.BREAK_SUPPORT
        }
        return IntradaySignal(
            type,
            "📉 ${input.name}·${decision.stage.label}",
            "现价 ${price(input.price)}（${pct(input.changePct)}$cost）" +
                (if (input.price < support * (1 - BREAK_TOLERANCE)) "跌破支撑 ${price(support)}。" else "，支撑参考 ${price(support)}。") + decision.action +
                (input.fundamentalHealth?.let { "基本面${it.level.label}：${it.reasons.take(2).joinToString("；")}。" }
                    ?: "基本面暂无数据，待核实；技术风险不因此忽略。"),
            holdingExitStage = decision.stage,
        )
    }

    /**
     * 跌破后重新站回支撑：深套的企稳确认窗口；基本面红灯时不补。
     * **塌陷期不把站回支撑当成企稳**——系统性下跌里支撑还可能再次失守。
     */
    private fun reclaimSupport(input: Input, deep: Boolean, red: Boolean): IntradaySignal {
        val cost = pnlText(input)
        val confirmed = confirmedTRebound(input)
        val action = when {
            deep && input.collapse ->
                "大盘/板块塌陷：站回也不补仓（不追加仓位）——支撑在系统性下跌里还会再破；保留底仓，机动仓滚动做T降低成本，" +
                    "反弹冲高减、回落未企稳不接回，等大盘重新站上 MA20 再评估。"
            deep && red ->
                "基本面亮红灯：站回不补仓（不追加仓位），站稳后等反弹到 " +
                    (if (input.resistance > 0) "压力 ${price(input.resistance)}" else "压力位") +
                    "附近分批离场；若保留仓位，只用机动仓做T。"
            !confirmed ->
                "仅重新站回支撑，尚未通过近期走势与盘中企稳确认；先观察，不追加仓位，不据此接回。"
            deep ->
                "站回支撑后的企稳确认窗口：先用机动仓滚动做T降低成本，反弹冲高减、回踩支撑企稳再接回；" +
                    "若重新跌破 ${price(input.support)}，接回窗口失效并停止执行；需有真实减出记录，不推算接回股数，不追加仓位。"
            else ->
                "站回后已观察到回升，仍需防止再次跌破；仅在有真实减出记录时评估接回，不追加仓位。"
        }
        return IntradaySignal(
            if (confirmed) IntradaySignalType.RECLAIM_SUPPORT else IntradaySignalType.RECLAIM_WATCH,
            "📗 ${input.name}·${if (confirmed) "站回支撑" else "站回待确认"}",
            "现价 ${price(input.price)}（${pct(input.changePct)}$cost）重新站回支撑 ${price(input.support)}。$action",
        )
    }

    /** 深套 + 基本面红灯的反弹至压力：分批离场窗口，而不是破位恐慌位的割肉点。 */
    private fun exitWindow(input: Input): IntradaySignal {
        val cost = pnlText(input)
        val tText = tQuantity(input, expanded = false).quantity
        val action = if (tText > 0) {
            "基本面风险偏高，当前优先分批离场（不必割在破位恐慌位）；" +
                "若你判断逻辑未变，可按「冲高做T」先减约 ${qty(tText)} 股。"
        } else {
            "基本面风险偏高，当前优先分批离场；若你判断逻辑未变，可按「冲高做T」减一部分。"
        }
        return IntradaySignal(
            IntradaySignalType.EXIT_WINDOW,
            "🔴 ${input.name}·反弹至压力",
            "现价 ${price(input.price)}（${pct(input.changePct)}$cost）反弹至压力 ${price(input.resistance)} 附近。$action",
        )
    }

    private fun breakout(input: Input, holding: Boolean): IntradaySignal {
        val level = input.entrySetup?.priorHigh ?: input.resistance
        val head = "现价 ${price(input.price)} 放量突破前高 ${price(level)}" +
            "（量比 ${ratio(input.volumeRatio)}${sectorConfirmation(input)})"
        val action = if (holding) {
            val stopText = input.entrySetup?.stopPrice?.let { "，止损参考 ${price(it)}" } ?: ""
            "持仓 ${qty(input.quantity)} 股可继续持有，不追高；回踩 ${price(level)} 不破再看$stopText。"
        } else if (input.weakEnv) {
            // 转弱/塌陷期不给试仓：突破在系统性下跌里多半是反抽，追进去正好接盘。
            "无持仓：大盘/板块转弱，突破也不追——等回踩缩量止跌、或大盘重新站上 MA20 再谈试仓。"
        } else {
            val entry = entryQuantity(input)
            if (entry > 0) {
                val riskWord = if (input.entrySetup != null) "按风险" else ""
                val stopText = input.entrySetup?.stopPrice?.let { "，止损参考 ${price(it)}" } ?: ""
                "无持仓：可考虑${riskWord}试仓约 ${qty(entry)} 股（单股初始仓位上限 ${pct(input.initialEntryPct)}，" +
                    "单股上限 ${pct(input.maxPositionPct)}$stopText）。"
            } else {
                val stopText = input.entrySetup?.stopPrice?.let { "，止损参考 ${price(it)}" } ?: ""
                "无持仓：满足趋势放量突破条件，可考虑单股初始仓位 ${pct(input.initialEntryPct)}$stopText（未填总资金，不给具体股数）。"
            }
        }
        return IntradaySignal(IntradaySignalType.BREAKOUT, "🚀 ${input.name}·放量突破", "$head。$action")
    }

    /**
     * 龙头仍强（封板，或炸板守住高位线）时的浅回落：只提示观察，不给减仓股数。
     * 正文里写清哪几只龙头仍强和「回落加深到哪」，方便在行情软件里逐点核对。
     */
    private fun tSellDeferred(input: Input, expanded: Boolean): IntradaySignal {
        val need = input.holdingTContext?.atrPct?.let { maxOf(0.5, it * 0.25) } ?: 0.5
        val deepPct = need * DEEP_RETREAT_FACTOR
        val deepPrice = input.dailyHighPrice
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { it * (1 - deepPct / 100.0) }
        val reason = if (expanded) "放量冲高后出现连续回落" else "接近压力 ${price(input.resistance)} 后出现连续回落"
        val names = input.strongLeaderNames.filter { it.isNotBlank() }.take(MAX_STRONG_LEADER_NAMES)
            .joinToString("、")
        val leaderContext = leadersStrong(input)
        val limitText = if (leaderContext) {
            "板块状态参考，非买卖指令：同板块龙头 ${names.ifBlank { "${input.strongLeaderCount} 只" }} " +
                "仍强（封板或炸板守住高位线），龙头强势途中做T容易被震下车"
        } else {
            "自适应市场节奏：${adaptiveRhythm(input).reason}，浅回落先观察，不把上涨第几天直接当卖点"
        }
        val action = "暂缓减仓，保留底仓；" +
            (deepPrice?.let { "回落加深到 ${price(it)} 附近" } ?: "回落继续加深") +
            "，或龙头转弱后再评估机动仓，不因到价直接卖出。"
        return IntradaySignal(
            IntradaySignalType.T_SELL_DEFERRED,
            "⏸ ${input.name}·板块状态参考｜冲高回落暂缓做T",
            "现价 ${price(input.price)}（${pct(input.changePct)}，$reason）。$limitText。$action",
        )
    }

    private fun tSell(input: Input, expanded: Boolean): IntradaySignal {
        val sizing = tQuantity(input, expanded)
        val reason = (if (expanded) "放量冲高后出现连续回落" else "接近压力 ${price(input.resistance)} 后出现连续回落") +
            (if (leadersStrong(input)) "；同板块龙头仍强，但回落已加深到 ${DEEP_RETREAT_FACTOR.toInt()} 倍阈值，按真回落处理" else "")
        val action = if (sizing.quantity > 0) {
            "保留底仓，机动仓可先减约 ${qty(sizing.quantity)} 股做 T（${sizing.note}，且须为可卖股份）；" +
                "回踩企稳后再评估接回，不因到价直接买回，需核对手续费、滑点和实际成交。"
        } else if (sizing.liquidityBlocked) {
            "流动性不足，暂不给具体股数；先观察成交情况，不能为做T凑整卖出底仓。"
        } else {
            "按1/3测算的机动仓不足1手，不为做T凑整卖出底仓。"
        }
        return IntradaySignal(
            IntradaySignalType.T_SELL,
            "🔔 ${input.name}·冲高做T",
            "现价 ${price(input.price)}（${pct(input.changePct)}，$reason）。" +
                "${input.holdingTContext?.summary()}。$action",
        )
    }

    private fun tTrendHold(input: Input): IntradaySignal = IntradaySignal(
        IntradaySignalType.T_TREND_HOLD,
        "${input.name}·趋势延续观察",
        "${input.holdingTContext?.summary()}；自适应市场节奏：${adaptiveRhythm(input).reason}；" +
            "MA20仍上行，回踩未破坏结构，保留观察仓等待二波；" +
            "已有浮盈不是卖出理由。现价${price(input.price)}仍接近日内高位，暂缓冲高做T，不追高加仓；" +
            "只有趋势或关键结构失效才按风险减仓或退出。",
    )

    private fun tHold(input: Input): IntradaySignal {
        val environment = marketEnvironment(input)
        return IntradaySignal(
            IntradaySignalType.T_HOLD,
            "📈 ${input.name}·强势环境延续观察",
            "现价 ${price(input.price)}（${pct(input.changePct)}，量比 ${ratio(input.volumeRatio)}）；" +
                "${environment.summary}；所属板块${input.sectorChangePct?.let { pct(it) } ?: "涨跌暂缺"}，个股接近日内高位；" +
                "暂缓做T，优先保留底仓观察延续，若指数或个股转弱再重新评估。",
        )
    }

    private fun tHoldCaution(input: Input): IntradaySignal {
        val environment = marketEnvironment(input)
        return IntradaySignal(
            IntradaySignalType.T_HOLD_CAUTION,
            "📊 ${input.name}·高成交额谨慎观察",
            "现价 ${price(input.price)}（${pct(input.changePct)}，量比 ${ratio(input.volumeRatio)}）；" +
                "${environment.summary}；个股接近日内高位，先不急于做T，但持续性尚未确认，不视为趋势确认。",
        )
    }

    private fun tBuy(input: Input): IntradaySignal {
        val action = "仅作接回观察：需有真实减出记录，数量不超过尚未接回的已卖股份，且受可用资金约束；" +
            "系统尚未核实成交，不推算接回股数；深套不盲目补仓。" +
            "须扣除手续费与滑点；若重新跌破 ${price(input.support)}，接回窗口失效并停止执行。"
        val sectorNote = sectorBuyNote(input)
        return IntradaySignal(
            IntradaySignalType.T_BUY,
            "🔔 ${input.name}·接回观察",
            "现价 ${price(input.price)}（${pct(input.changePct)}）回踩支撑 ${price(input.support)} 后出现连续回升。" +
                "${input.holdingTContext?.summary()}。$action$sectorNote",
        )
    }

    private fun pullback(input: Input): IntradaySignal {
        val entry = entryQuantity(input)
        val level = if (input.entrySetup != null && input.ma20 > 0) input.ma20 else input.support
        val stopText = input.entrySetup?.stopPrice?.let { "止损参考 ${price(it)}" }
        val action = if (entry > 0) {
            val riskWord = if (input.entrySetup != null) "按风险" else ""
            "无持仓：等止跌确认后可考虑${riskWord}试仓约 ${qty(entry)} 股（${stopText ?: "止损参考暂缺"}）。"
        } else {
            "无持仓：回踩 M20 且缩量，等止跌确认后可考虑初始仓位 ${pct(input.initialEntryPct)}" +
                "（${stopText ?: "止损参考暂缺"}）。"
        }
        return IntradaySignal(
            IntradaySignalType.PULLBACK,
            "👀 ${input.name}·回踩支撑",
            "现价 ${price(input.price)} 回踩${if (input.entrySetup != null) "M20" else "支撑"} ${price(level)}" +
                "（评分 ${signed(input.score)}，量比 ${ratio(input.volumeRatio)}）。$action${sectorBuyNote(input)}",
        )
    }

    private fun sectorConfirmation(input: Input): String {
        val flowNote = sectorFlowNote(input)
        return when {
            input.sectorChangePct != null && input.sectorChangePct > 0 &&
                input.sectorTurnoverRatio != null && input.sectorTurnoverRatio >= 1.2 ->
                "；板块同步走强且放量$flowNote"
            flowNote.isNotBlank() -> flowNote
            input.sectorChangePct != null && input.sectorChangePct < 0 ||
                input.sectorTurnoverRatio != null && input.sectorTurnoverRatio <= 0.8 ->
                "；板块未同步，突破持续性需打折"
            else -> ""
        }
    }

    private fun sectorBuyNote(input: Input): String {
        return when {
            input.sectorChangePct != null && input.sectorChangePct < 0 ->
                "板块走弱，接回或试仓要更保守。"
            input.sectorTurnoverRatio != null && input.sectorTurnoverRatio <= 0.8 ->
                "板块成交额未同步放大，接回或试仓要更保守。"
            sectorFlowNegative(input) ->
                "板块主力资金偏流出，接回或试仓要更保守。"
            else -> ""
        }
    }

    private fun sectorFlowNote(input: Input): String {
        return when {
            sectorFlowPositive(input) ->
                "；板块主力净流入${amountYi(input.sectorMainNetInflow)}（净占比 ${ratioPct(input.sectorMainNetRatio)}）"
            sectorFlowNegative(input) ->
                "；板块主力净流出${amountYi(input.sectorMainNetInflow)}（净占比 ${ratioPct(input.sectorMainNetRatio)}），持续性需打折"
            else -> ""
        }
    }

    private fun sectorFlowPositive(input: Input): Boolean =
        input.sectorMainNetRatio?.takeIf { it.isFinite() }?.let { it > 0.0 }
            ?: input.sectorMainNetInflow?.takeIf { it.isFinite() }?.let { it > 0.0 }
            ?: false

    private fun sectorFlowNegative(input: Input): Boolean =
        input.sectorMainNetRatio?.takeIf { it.isFinite() }?.let { it < 0.0 }
            ?: input.sectorMainNetInflow?.takeIf { it.isFinite() }?.let { it < 0.0 }
            ?: false

    private fun ratioPct(value: Double?): String =
        value?.let { String.format(Locale.CHINA, "%+.2f%%", it) } ?: "净流入"

    private fun amountYi(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let {
            String.format(Locale.CHINA, " %.2f亿", abs(it) / 100_000_000.0)
        } ?: ""

    /** 持仓的 1/3，按手向下取整；不足一手返回 0。 */
    data class TQuantityAdvice(val quantity: Double, val note: String, val liquidityBlocked: Boolean)

    fun suggestedTSellQuantity(input: Input): TQuantityAdvice {
        val expanded = input.volumeRatio?.let { it >= VolumeRatio.EXPAND_THRESHOLD } == true
        return tQuantity(input, expanded)
    }

    private fun tQuantity(input: Input, expanded: Boolean): TQuantityAdvice {
        val sellable = input.sellableQuantity ?: input.quantity
        if (sellable <= 0 || !sellable.isFinite()) {
            return TQuantityAdvice(0.0, if (input.lockedQuantity > 0) "今日可卖仓位为0" else "持仓不足一手", false)
        }
        var part = T_PART
        val notes = mutableListOf<String>()
        val atrPct = input.atrPct ?: input.holdingTContext?.atrPct
        if (atrPct?.isFinite() == true && atrPct >= 5.0) {
            part = minOf(part, 0.2)
            notes.add("波动偏大按1/5")
        }
        if (input.weakEnv) {
            part = minOf(part, 0.25)
            notes.add("大盘或板块偏弱按1/4")
        }
        if (marketEnvironment(input).status == MarketEnvironmentStatus.DIVERGENCE) {
            part = minOf(part, 0.25)
            notes.add("大盘放量分歧按1/4")
        }
        if (input.sectorTurnoverRatio?.let { it.isFinite() && it < 0.8 } == true) {
            part = minOf(part, 0.25)
            notes.add("板块成交额收缩按1/4")
        }
        if (sectorFlowNegative(input) || input.sectorChangePct?.let { it.isFinite() && it < 0.0 } == true) {
            part = minOf(part, 0.25)
            notes.add("板块承接偏弱按1/4")
        }
        if (DeepLossPolicy.isDeepLoss(input.cost, input.price, atrPct)) {
            part = minOf(part, 0.25)
            notes.add("深套按1/4")
        }
        val positionPct = if (input.totalCapital > 0 && input.price > 0) {
            input.quantity * input.price / input.totalCapital * 100.0
        } else null
        if (positionPct != null && input.maxPositionPct > 0 && positionPct > input.maxPositionPct) {
            part = minOf(part, 0.25)
            notes.add("单股仓位偏高按1/4")
        }
        if (!expanded) {
            part = minOf(part, 0.25)
            notes.add("未放量按1/4")
        }
        val rawQuantity = sellable * part
        val turnover = input.todayTurnoverYi
        val orderValueYi = rawQuantity * input.price / 100_000_000.0
        if (turnover != null && (!turnover.isFinite() || turnover <= 0.0 ||
                orderValueYi > turnover * MAX_T_ORDER_TURNOVER_SHARE)) {
            return TQuantityAdvice(0.0, "成交额不足以支持这笔机动仓", true)
        }
        val quantity = floor(rawQuantity / LOT) * LOT
        return TQuantityAdvice(quantity, notes.ifEmpty { listOf("持仓1/3向下取整") }.joinToString("，"), false)
    }

    /** 空仓试仓数量：总资金 × 初始仓位 ÷ 现价，按手向下取整。 */
    private fun entryQuantity(input: Input): Double {
        if (input.totalCapital <= 0 || input.price <= 0 || input.initialEntryPct <= 0) return 0.0
        input.entrySetup?.let { setup ->
            if (setup.canBuy && setup.stopPrice != null) {
                return TrendEntryStrategy.suggestedQuantity(
                    price = input.price,
                    stopPrice = setup.stopPrice,
                    totalCapital = input.totalCapital,
                    initialEntryPct = input.initialEntryPct,
                    maxPositionPct = input.maxPositionPct,
                    riskPerTradePct = input.riskPerTradePct,
                )
            }
        }
        val shares = input.totalCapital * input.initialEntryPct / 100.0 / input.price
        return floor(shares / LOT) * LOT
    }

    private fun pnlText(input: Input): String =
        if (input.cost > 0) "，成本 ${price(input.cost)} ${pct((input.price / input.cost - 1) * 100)}" else ""

    private fun price(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    private fun pct(value: Double): String = String.format(Locale.CHINA, "%+.1f%%", value)

    private fun ratio(value: Double?): String =
        if (value == null) "—" else String.format(Locale.CHINA, "%.2f", value)

    private fun qty(value: Double): String = String.format(Locale.CHINA, "%.0f", value)

    private fun signed(value: Int): String = String.format(Locale.CHINA, "%+d", value)
}
