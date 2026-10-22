package com.example.stockmonitor.analysis

import java.util.Locale
import kotlin.math.floor

object TradeNotification {
    fun sellQuantity(quantity: Double, exit: Boolean): Double {
        if (!quantity.isFinite() || quantity <= 0) return 0.0
        return if (exit) floor(quantity) else floor(quantity / 3.0 / 100.0) * 100.0
    }

    fun sellQuantity(totalQuantity: Double, sellableQuantity: Double, exit: Boolean): Double {
        if (!totalQuantity.isFinite() || totalQuantity <= 0 || !sellableQuantity.isFinite() || sellableQuantity <= 0) {
            return 0.0
        }
        val capped = minOf(totalQuantity, sellableQuantity)
        return if (exit) floor(capped) else floor(capped / 3.0 / 100.0) * 100.0
    }

    /**
     * 不可卖的两种来源分开说：「今天买入」下一交易日会自动解锁，「未记录买入日期」
     * 不会——那是手工加仓没记批次留下的残股（见 [TradeAvailability.untrackedQuantity]），
     * 要在「记录交易」里补记买入日期后才按实际日期计算，不能混称「今天加的」。
     */
    private fun lockTail(lockedToday: Double, untrackedQuantity: Double): String {
        val (sameDayLocked, untracked) = lockedSplit(lockedToday, untrackedQuantity)
        return when {
            sameDayLocked > 0.0 && untracked > 0.0 ->
                "今日新增${qty(sameDayLocked)}股、未记录买入日期${qty(untracked)}股受T+1锁定。"
            sameDayLocked > 0.0 -> "今日新增${qty(sameDayLocked)}股受T+1锁定。"
            else -> "未记录买入日期的${qty(untracked)}股暂按T+1锁定（在「记录交易」补记买入日期后按实际日期计算）。"
        }
    }

    private fun lockBlocked(lockedToday: Double, untrackedQuantity: Double): String {
        val (sameDayLocked, untracked) = lockedSplit(lockedToday, untrackedQuantity)
        return when {
            sameDayLocked > 0.0 && untracked > 0.0 ->
                "今天买入的${qty(sameDayLocked)}股、未记录买入日期的${qty(untracked)}股均受T+1锁定，停止加仓；" +
                    "未记录部分补记买入日期后按实际日期计算，其余下一交易日复核处置。"
            sameDayLocked > 0.0 -> "今天买入的${qty(sameDayLocked)}股受T+1锁定，停止加仓，下一交易日复核处置。"
            else -> "未记录买入日期的${qty(untracked)}股暂按T+1锁定，停止加仓；" +
                "在「记录交易」补记买入日期后按实际日期计算。"
        }
    }

    /** 把「锁定总量」拆成今天买入 / 未记录日期两部分；未记录的计入 [lockedToday] 但不随交易日解冻。 */
    private fun lockedSplit(lockedToday: Double, untrackedQuantity: Double): Pair<Double, Double> {
        val untracked = minOf(untrackedQuantity.coerceAtLeast(0.0), lockedToday.coerceAtLeast(0.0))
        return (lockedToday - untracked).coerceAtLeast(0.0) to untracked
    }

    private fun sale(
        quantity: Double,
        sellableQuantity: Double = quantity,
        lockedToday: Double = 0.0,
        untrackedQuantity: Double = 0.0,
        exit: Boolean = false,
    ): String {
        if (sellableQuantity <= 0.0 && lockedToday > 0.0) {
            return lockBlocked(lockedToday, untrackedQuantity)
        }
        val shares = sellQuantity(quantity, sellableQuantity, exit)
        if (shares <= 0) return if (quantity.isFinite() && quantity > 0 && !exit) {
            "按1/3估算不足100股，不自动凑整卖出；请核对仓位后处理。"
        } else {
            "持仓数量无效，先核对持仓。"
        }
        if (lockedToday > 0.0) {
            val action = if (exit) "退出目标约${shares.toLong()}股" else "先卖约${shares.toLong()}股"
            return "原有可卖${qty(sellableQuantity)}股，$action（可卖仓位1/3向下取整）；" +
                lockTail(lockedToday, untrackedQuantity)
        }
        val action = if (exit) "退出目标约${shares.toLong()}股，可分笔" else "先卖约${shares.toLong()}股（持仓1/3向下取整）"
        return "$action；按录入持仓估算，以实际可卖数量为限，T+1/跌停可能无法成交。"
    }

    fun exit(
        stage: HoldingExitStage,
        totalQuantity: Double,
        price: Double,
        support: Double,
        sellableQuantity: Double = totalQuantity,
        lockedToday: Double = 0.0,
        untrackedQuantity: Double = 0.0,
    ): String {
        if (sellableQuantity <= 0.0 && lockedToday > 0.0) {
            return lockBlocked(lockedToday, untrackedQuantity)
        }
        val action = when (stage) {
            HoldingExitStage.WATCH -> "先不加仓，观察能否站回${price(support)}。"
            HoldingExitStage.REDUCE -> "破位已确认，${sale(totalQuantity, sellableQuantity, lockedToday, untrackedQuantity)}"
            HoldingExitStage.HIGH_REDUCE -> "高位转弱，${sale(totalQuantity, sellableQuantity, lockedToday, untrackedQuantity)}暂停接回。"
            HoldingExitStage.EXIT -> "退出优先，${sale(totalQuantity, sellableQuantity, lockedToday, untrackedQuantity, exit = true)}"
            HoldingExitStage.DEEP_T -> "先不补仓，等反弹再评估做T；持续走弱需重新评估退出。"
        }
        return "$action 现价${price(price)}，支撑${price(support)}。"
    }

    fun portfolio(
        result: PositionResult,
        sellableQuantity: Double = result.quantity,
        lockedToday: Double = 0.0,
        untrackedQuantity: Double = 0.0,
    ): String? = result.notificationExitDecision?.let {
        exit(it.stage, result.quantity, result.price, result.support, sellableQuantity, lockedToday, untrackedQuantity)
    }

    fun intraday(
        input: IntradayTactics.Input,
        signal: IntradaySignal,
        timeframeReason: String? = null,
    ): String {
        val context = trendContext(input)
        val prefix = if (context.isEmpty()) "" else "$context；"
        if (signal.type == IntradaySignalType.TIMEFRAME_WATCH) {
            val reason = timeframeReason?.trim()?.takeIf { it.isNotBlank() } ?: "15/30分钟条件待确认"
            return "$prefix$reason。当前仅观察，不执行买入、做T或普通止盈/减仓。现价${price(input.price)}。"
        }
        val stage = when (signal.type) {
            IntradaySignalType.REDUCE_POSITION -> HoldingExitStage.REDUCE
            IntradaySignalType.HIGH_REDUCE_POSITION -> HoldingExitStage.HIGH_REDUCE
            IntradaySignalType.EXIT_POSITION -> HoldingExitStage.EXIT
            IntradaySignalType.BREAK_SUPPORT -> if (DeepLossPolicy.isDeepLoss(input.cost, input.price, input.atrPct) &&
                input.fundamentalHealth?.level != HealthLevel.ALERT) HoldingExitStage.DEEP_T else HoldingExitStage.WATCH
            else -> null
        }
        val support = input.breakObservation?.support ?: input.support
        if (stage != null) {
            return prefix + exit(
                stage = stage,
                totalQuantity = input.quantity,
                price = input.price,
                support = support,
                sellableQuantity = input.sellableQuantity ?: input.quantity,
                lockedToday = input.lockedQuantity,
                untrackedQuantity = input.untrackedQuantity,
            )
        }
        val action = when (signal.type) {
            IntradaySignalType.BREAKOUT_REVERSAL_RISK ->
                if (input.quantity > 0) {
                    "突破延续后出现下杀风险，优先减仓或退出，暂停接回和做T。"
                } else {
                    "突破延续后出现下杀风险，禁止追买，等待重新站回并确认。"
                }
            IntradaySignalType.BOTTOM_REVERSAL ->
                "底部反转观察，最多按初始仓位25%轻仓试错；禁止正常加仓和做T，跌破${price(support)}则窗口失效。"
            IntradaySignalType.HIGH_CONSOLIDATION ->
                "高位整理，只保留底仓或观察；突破并确认前不买入、接回或做T。"
            IntradaySignalType.SECOND_WAVE_CANDIDATE ->
                "二波候选只观察；突破${price(input.resistance)}并获量能确认前不买入、接回或做T。"
            IntradaySignalType.SECOND_WAVE ->
                "二波突破确认后才可评估试仓；不追高加仓，跌破${price(support)}则结构失效。"
            IntradaySignalType.T_SELL -> {
                val sizing = IntradayTactics.suggestedTSellQuantity(input)
                val action = when {
                    input.sellableQuantity == 0.0 && input.lockedQuantity > 0.0 ->
                        lockBlocked(input.lockedQuantity, input.untrackedQuantity)
                    sizing.quantity > 0 -> "冲高回落，做T先减约${sizing.quantity.toLong()}股（${sizing.note}），接回等企稳。"
                    sizing.liquidityBlocked -> "冲高回落，但成交额不足以支持机动仓交易，先观察。"
                    else -> "冲高回落，机动仓不足一手，先观察。"
                }
                "$action"
            }
            IntradaySignalType.EXIT_WINDOW -> "反弹至压力${price(input.resistance)}，${sale(
                input.quantity,
                input.sellableQuantity ?: input.quantity,
                input.lockedQuantity,
                input.untrackedQuantity,
            )}暂不接回。"
            IntradaySignalType.T_BUY, IntradaySignalType.RECLAIM_SUPPORT ->
                if (input.trendAssessment?.allowsTBuy == false || input.trendAssessment?.major == MajorTrend.DOWN) {
                    "大趋势不支持普通买入、加仓、接回或做T，当前只保留风险处置。"
                } else {
                    "可评估补仓或接回：须有真实卖出记录，不超过未接回股数及可用资金；计入费用，" +
                        "若重新跌破 ${price(support)}，补仓窗口失效并停止执行。"
                }
            IntradaySignalType.RECLAIM_WATCH -> "先观察，不加仓、不接回；站回${price(support)}后仍待企稳确认。"
            IntradaySignalType.T_TREND_HOLD, IntradaySignalType.T_HOLD -> "先持有，暂缓做T；尚未确认连续回落，不追高。"
            IntradaySignalType.T_HOLD_CAUTION -> "先持有，不追高；上涨持续性待确认，回落后再评估做T。"
            IntradaySignalType.BREAKOUT -> "先持有，不追高；回踩${price(input.entrySetup?.priorHigh ?: input.resistance)}站稳再评估。"
            IntradaySignalType.PULLBACK -> "回踩${price(support)}，先等止跌确认，不直接追买。"
            else -> "先观察，不追加仓位。"
        }
        return "$prefix$action 现价${price(input.price)}。"
    }

    private fun trendContext(input: IntradayTactics.Input): String {
        val trend = input.trendAssessment?.let { "${it.major.label}/${it.shortTerm.label}" }
        val box = input.boxRange?.takeIf { it.state != BoxRangeState.NOT_BOX }?.let { "箱体:${it.state.label}" }
        val high = input.highPosition
            ?.takeIf { it.phase != HighPositionPhase.NONE }
            ?.let { "高位:${it.phase.label}" }
        return listOfNotNull(trend, box, high).joinToString("｜")
    }

    private fun price(value: Double): String = if (value.isFinite() && value > 0) {
        String.format(Locale.CHINA, "%.2f", value)
    } else "待核实"

    private fun qty(value: Double): String = String.format(Locale.CHINA, "%.0f", value)
}
