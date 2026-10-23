package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.TradeActionEligibility
import com.example.stockmonitor.core.TradeActionGuard
import com.example.stockmonitor.core.TradeActionPermissions
import com.example.stockmonitor.core.GuardedTradeAction
import com.example.stockmonitor.core.GuardedActionLevel
import com.example.stockmonitor.core.IntradayVolumeContext
import com.example.stockmonitor.core.TimeframeEvidence
import java.util.Locale
import kotlin.math.floor

object TradeNotification {
    fun sellQuantity(quantity: Double, exit: Boolean): Double {
        if (!quantity.isFinite() || quantity <= 0) return 0.0
        return if (exit) floor(quantity / 100.0) * 100.0 else floor(quantity / 3.0 / 100.0) * 100.0
    }

    fun sellQuantity(totalQuantity: Double, sellableQuantity: Double, exit: Boolean): Double {
        if (!totalQuantity.isFinite() || totalQuantity <= 0 || !sellableQuantity.isFinite() || sellableQuantity <= 0) {
            return 0.0
        }
        val capped = minOf(totalQuantity, sellableQuantity)
        return if (exit) floor(capped / 100.0) * 100.0 else floor(capped / 3.0 / 100.0) * 100.0
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
        if (shares <= 0) return when {
            !quantity.isFinite() || quantity <= 0 -> "持仓数量无效，先核对持仓。"
            exit -> "可卖不足100股，不生成零碎卖出；请核对持仓后处理。"
            else -> "按1/3估算不足100股，不自动凑整卖出；请核对仓位后处理。"
        }
        if (lockedToday > 0.0) {
            val action = if (exit) "退出目标约${shares.toLong()}股" else "先卖约${shares.toLong()}股"
            val sizing = if (exit) "（按100股整手向下取整）" else "（可卖仓位1/3且按100股向下取整）"
            return "原有可卖${qty(sellableQuantity)}股，$action$sizing；" +
                lockTail(lockedToday, untrackedQuantity)
        }
        val action = if (exit) {
            "退出目标约${shares.toLong()}股，可分笔（按100股整手向下取整）"
        } else {
            "先卖约${shares.toLong()}股（可卖仓位1/3且按100股向下取整）"
        }
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
            // 短信口径不带基本面长文（见 TradeNotificationTest）：三项缓释的完整理由在持仓报告里。
            // NOTICE 只报告风险事实，不生成确定性卖量。
            HoldingExitStage.NOTICE ->
                "破位风险提示：价格处低位、仓位较轻，去留自定。"
        }
        return "$action 现价${price(price)}，支撑${price(support)}。"
    }

    fun portfolio(
        result: PositionResult,
        sellableQuantity: Double = result.quantity,
        lockedToday: Double = 0.0,
        untrackedQuantity: Double = 0.0,
        ledgerConsistent: Boolean,
        tradeDate: String,
        evidence: TimeframeEvidence? = null,
        volume: IntradayVolumeContext? = null,
    ): GuardedTradeAction? = result.notificationExitDecision?.let {
        val guarded = TradeActionGuard.evaluate(
            intent = when (it.stage) {
                HoldingExitStage.EXIT -> "exit_position"
                HoldingExitStage.HIGH_REDUCE -> "high_reduce"
                HoldingExitStage.REDUCE,
                -> "risk_reduce"
                else -> "hold"
            },
            evidence = evidence,
            volume = volume,
            eligibility = TradeActionEligibility(
                totalQuantity = result.quantity,
                actionableSellableQuantity = sellableQuantity,
                todayLockedQuantity = lockedToday,
                untrackedQuantity = untrackedQuantity,
                ledgerConsistent = ledgerConsistent,
            ),
            tradeDate = tradeDate,
        )
        guarded
    }

    fun intraday(
        input: IntradayTactics.Input,
        signal: IntradaySignal,
        timeframeReason: String? = null,
    ): String = intradayGuarded(input, signal, timeframeReason).text

    fun intradayGuarded(
        input: IntradayTactics.Input,
        signal: IntradaySignal,
        timeframeReason: String? = null,
    ): GuardedTradeAction {
        if (signal.type.isPureObservation) {
            val reason = timeframeReason?.trim()?.takeIf { it.isNotBlank() } ?: "15/30分钟条件待确认"
            return GuardedTradeAction(
                GuardedActionLevel.OBSERVATION,
                com.example.stockmonitor.core.GuardedAction.HOLD,
                "仅观察：$reason；当前不执行买入、做T或普通止盈/减仓。",
            )
        }
        if (signal.holdingExitStage in setOf(HoldingExitStage.WATCH, HoldingExitStage.DEEP_T)) {
            return GuardedTradeAction(
                GuardedActionLevel.OBSERVATION,
                com.example.stockmonitor.core.GuardedAction.HOLD,
                "仅观察：${signal.body}",
            )
        }
        if (signal.type == IntradaySignalType.BREAK_NOTICE ||
            signal.holdingExitStage == HoldingExitStage.NOTICE
        ) {
            return GuardedTradeAction(
                GuardedActionLevel.RISK_FACT,
                com.example.stockmonitor.core.GuardedAction.HOLD,
                "风险观察：破位风险提示只报告风险，不生成确定性卖量。",
            )
        }
        val intent = when (signal.type) {
            IntradaySignalType.REDUCE_POSITION,
            IntradaySignalType.BREAK_SUPPORT,
            IntradaySignalType.BREAKOUT_REVERSAL_RISK -> "risk_reduce"
            IntradaySignalType.HIGH_REDUCE_POSITION -> "high_reduce"
            IntradaySignalType.EXIT_POSITION -> "exit_position"
            IntradaySignalType.EXIT_WINDOW -> "normal_exit"
            IntradaySignalType.T_SELL -> "t_sell"
            IntradaySignalType.RECLAIM_SUPPORT, IntradaySignalType.T_BUY -> "t_buy"
            IntradaySignalType.BREAKOUT,
            IntradaySignalType.PULLBACK,
            IntradaySignalType.BOTTOM_REVERSAL,
            IntradaySignalType.SECOND_WAVE -> "buy"
            else -> "hold"
        }
        val reentry = intent == "t_buy"
        val capacity = if (reentry) input.reentryCapacityQuantity else IntradayTactics.buyCapacityQuantity(input)
        val blocked = mutableListOf<String>()
        if (input.weakEnv) blocked += "大盘或板块环境转弱"
        if (input.fundamentalHealth?.level == HealthLevel.ALERT) blocked += "基本面亮红灯"
        if (input.highPosition?.allowBuy == false) blocked += "高位结构不允许新增买入"
        if (input.trendAssessment?.major == MajorTrend.DOWN) blocked += "大趋势向下"
        if (reentry && capacity <= 0.0) blocked += "缺少真实卖出后未接回数量"
        val guarded = TradeActionGuard.evaluate(
            intent = intent,
            evidence = input.timeframeEvidence,
            volume = input.volumeContext,
            eligibility = TradeActionEligibility(
                totalQuantity = input.quantity,
                actionableSellableQuantity = input.sellableQuantity ?: 0.0,
                todayLockedQuantity = input.lockedQuantity,
                untrackedQuantity = input.untrackedQuantity,
                buyCapacityQuantity = capacity,
                ledgerConsistent = input.ledgerConsistent,
            ),
            tradeDate = input.tradeDate,
            invalidationPrice = input.entrySetup?.stopPrice
                ?: input.breakObservation?.exitPrice
                ?: input.support.takeIf { it.isFinite() && it > 0.0 }
                ?: 0.0,
            permissions = TradeActionPermissions(
                allowBuy = blocked.isEmpty(),
                allowAdd = blocked.isEmpty(),
                allowReentry = blocked.isEmpty(),
                allowT = blocked.isEmpty(),
                blockers = blocked,
            ),
        )
        return guarded
    }

    private fun price(value: Double): String = if (value.isFinite() && value > 0) {
        String.format(Locale.CHINA, "%.2f", value)
    } else "待核实"

    private fun qty(value: Double): String = String.format(Locale.CHINA, "%.0f", value)
}
