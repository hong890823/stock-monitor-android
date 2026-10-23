package com.example.stockmonitor.core

import java.time.LocalDate
import kotlin.math.floor

enum class GuardedActionLevel {
    OBSERVATION,
    RISK_FACT,
    NEXT_DAY,
    ACTIONABLE,
}

enum class GuardedAction {
    HOLD,
    BUY_SMALL,
    RISK_EXIT,
    RISK_REDUCE,
    NORMAL_EXIT,
    T_SELL,
    NEXT_DAY_REVIEW,
}

data class TradeActionEligibility(
    val totalQuantity: Double = 0.0,
    val actionableSellableQuantity: Double = 0.0,
    val todayLockedQuantity: Double = 0.0,
    val untrackedQuantity: Double = 0.0,
    val buyCapacityQuantity: Double = 0.0,
    val ledgerConsistent: Boolean = true,
)

data class TradeActionPermissions(
    val allowBuy: Boolean = true,
    val allowAdd: Boolean = true,
    val allowReentry: Boolean = true,
    val allowT: Boolean = true,
    val blockers: List<String> = emptyList(),
)

data class GuardedTradeAction(
    val level: GuardedActionLevel,
    val action: GuardedAction,
    val text: String,
    val maxSellQuantity: Double = 0.0,
    val maxBuyQuantity: Double = 0.0,
    val invalidationPrice: Double = 0.0,
)

object TradeActionGuard {
    private const val LOT = 100.0

    fun evaluate(
        intent: String,
        evidence: TimeframeEvidence?,
        volume: IntradayVolumeContext? = null,
        eligibility: TradeActionEligibility,
        tradeDate: String?,
        invalidationPrice: Double = 0.0,
        permissions: TradeActionPermissions? = null,
    ): GuardedTradeAction {
        if (!eligibility.ledgerConsistent) {
            return observe("持仓账本数量不一致，暂不生成可卖股数；核对持仓批次后再评估。")
        }
        if (!validDate(tradeDate)) {
            return observe("行情交易日期缺失或无效，当前仅观察，不生成确定性买卖。")
        }
        val normalizedIntent = intent.lowercase().trim()
        val kind = normalizeIntent(normalizedIntent)
        if (kind == GuardedAction.HOLD) return observe("当前维持持有观察，不新增确定性买卖动作。")
        if (evidence == null) return observe("15/30分钟证据缺失，当前仅观察，不执行买入、卖出或做T。")
        if (kind == GuardedAction.BUY_SMALL) {
            val allowed = if (normalizedIntent in setOf("t_buy", "reentry")) {
                permissions?.allowReentry != false
            } else {
                permissions?.allowBuy != false
            }
            if (!allowed) {
                val blocker = permissions?.blockers?.joinToString("；")
                    ?.ifBlank { "趋势或高位结构不允许买入" }
                    ?: "趋势或高位结构不允许买入"
                return observe("$blocker，不执行买入。")
            }
            if (invalidationPrice <= 0.0 || !invalidationPrice.isFinite()) {
                return observe("缺少明确失效价，仅观察，不执行买入。")
            }
            if (!evidence.complete || evidence.m15Confirmations < 2 || !evidence.m30Aligned || !evidence.clearanceConfirmed) {
                return observe("15/30分钟站稳距离不足，仅观察，不执行买入。")
            }
            if (evidence.lastBarRejected) return observe("15分钟K线出现上影拒绝，仅观察，不执行买入。")
            if (volume?.blowoffRisk == true) return observe("上涨放量但出现冲高回落，量价证据冲突，仅观察。")
            if (volume?.stockVolumeConfirming != true) return observe("个股量能证据不足，仅观察，不执行买入。")
            if (volume?.marketExpanding != true) return observe("两市成交量证据不足或未放大，仅观察，不执行买入。")
            if (volume?.barVolumeConfirming != true) return observe("当前15分钟量能未确认，仅观察，不执行买入。")
            val quantity = floor(eligibility.buyCapacityQuantity.coerceAtLeast(0.0) / LOT) * LOT
            if (quantity < LOT) return observe("仓位边界不允许至少一手，仅观察，不执行买入。")
            return GuardedTradeAction(
                GuardedActionLevel.ACTIONABLE,
                GuardedAction.BUY_SMALL,
                "15/30分钟、个股和两市量能均已确认，可小仓买入约${quantity.toLong()}股；失效价${formatPrice(invalidationPrice)}，跌破即停止执行。今日新增仓位受A股T+1限制。",
                maxBuyQuantity = quantity,
                invalidationPrice = invalidationPrice,
            )
        }

        val sellable = lotFloor(eligibility.actionableSellableQuantity)
        if (kind == GuardedAction.RISK_EXIT || kind == GuardedAction.RISK_REDUCE) {
            val highReduce = normalizedIntent == "high_reduce"
            if (highReduce) {
                if (!evidence.riskComplete) {
                    return riskFact("高位转弱尚未由最新已完成15分钟K线确认，继续观察。")
                }
                if (sellable <= 0.0) return nextDay("高位转弱条件已确认", eligibility)
                val absorption = volume?.absorptionConfirmed == true
                val firstBatch = if (absorption) LOT else floor(sellable / 3.0 / LOT) * LOT
                if (firstBatch < LOT) {
                    return observe("高位转弱已确认，但首批按1/3不足100股，不生成零碎卖出。")
                }
                val absorptionText = if (absorption) {
                    "；当前15分钟出现承接迹象，首批压到100股，继续观察是否收复支撑，不代表确定的主力资金入场"
                } else {
                    ""
                }
                return GuardedTradeAction(
                    GuardedActionLevel.ACTIONABLE,
                    GuardedAction.RISK_REDUCE,
                    "高位转弱已由最新已完成15分钟K线确认，首批最多减${firstBatch.toLong()}股$absorptionText。今日新增仓位受T+1限制。",
                    maxSellQuantity = minOf(firstBatch, sellable),
                )
            }
            if (!evidence.riskComplete || !evidence.riskConfirmed) {
                return riskFact("风险条件尚未由已完成15分钟K线确认，继续观察。")
            }
            if (evidence.riskConfirmations < 2) {
                return riskFact("单根破位只报告风险，等待连续两根已完成15分钟K线确认。")
            }
            if (sellable <= 0.0) return nextDay("15分钟风险条件已确认", eligibility)
            val absorption = volume?.absorptionConfirmed == true
            val fullExit = normalizedIntent in setOf("exit_position", "exit_priority") &&
                evidence.riskLevel == DailyRiskLevel.CRITICAL &&
                evidence.riskConfirmations >= 3
            if (fullExit) {
                return GuardedTradeAction(
                    GuardedActionLevel.ACTIONABLE,
                    GuardedAction.RISK_EXIT,
                    "连续3根已完成15分钟K线确认，且日级风险为CRITICAL；使用今日可卖${sellable.toLong()}股分批离场。今日新增仓位受T+1限制。",
                    maxSellQuantity = sellable,
                )
            }
            val firstBatch = if (absorption) {
                LOT
            } else {
                floor(sellable / 3.0 / LOT) * LOT
            }
            if (firstBatch < LOT) {
                return observe("风险已确认，但首批按1/3不足100股，不生成零碎卖出。")
            }
            val absorptionText = if (absorption) {
                "；当前15分钟出现承接迹象，首批压到100股，继续观察是否收复支撑，不代表确定的主力资金入场"
            } else {
                ""
            }
            return GuardedTradeAction(
                GuardedActionLevel.ACTIONABLE,
                GuardedAction.RISK_REDUCE,
                "连续${evidence.riskConfirmations}根已完成15分钟K线确认破位，首批最多减${firstBatch.toLong()}股$absorptionText。今日新增仓位受T+1限制。",
                maxSellQuantity = minOf(firstBatch, sellable),
            )
        }

        if (kind == GuardedAction.T_SELL && permissions != null && !permissions.allowT) {
            return observe(permissions.blockers.joinToString("；").ifBlank { "趋势或高位结构不允许做T" } + "，不做T。")
        }
        if (!evidence.complete || evidence.m15Confirmations < 1 || !evidence.m30Aligned) {
            return observe("15/30分钟卖点尚未共同确认，仅观察，不执行卖出或做T。")
        }
        if (sellable <= 0.0) return nextDay("卖点条件已确认", eligibility)
        return if (kind == GuardedAction.T_SELL) {
            val planned = floor(eligibility.totalQuantity / 3.0 / LOT) * LOT
            if (planned < LOT) {
                return observe("按持仓1/3不足100股，不生成零碎做T卖出。")
            }
            val quantity = minOf(sellable, planned)
            GuardedTradeAction(
                GuardedActionLevel.ACTIONABLE,
                GuardedAction.T_SELL,
                "15/30分钟条件已确认，可用原有可卖仓位做T，最多减${quantity.toLong()}股；今日新增仓位受T+1限制。",
                maxSellQuantity = quantity,
                invalidationPrice = invalidationPrice,
            )
        } else {
            GuardedTradeAction(
                GuardedActionLevel.ACTIONABLE,
                GuardedAction.NORMAL_EXIT,
                "15/30分钟卖点已确认，可使用原有可卖${sellable.toLong()}股分批卖出；今日新增仓位受T+1限制。",
                maxSellQuantity = sellable,
            )
        }
    }

    private fun normalizeIntent(value: String): GuardedAction = when (value.lowercase().trim()) {
        "buy", "add", "reentry", "t_buy" -> GuardedAction.BUY_SMALL
        "exit_position", "exit_priority" -> GuardedAction.RISK_EXIT
        "risk_exit", "stop" -> GuardedAction.RISK_EXIT
        "risk_reduce", "break_support", "high_reduce" -> GuardedAction.RISK_REDUCE
        "normal_exit", "exit", "resistance" -> GuardedAction.NORMAL_EXIT
        "t_sell", "do_t" -> GuardedAction.T_SELL
        else -> GuardedAction.HOLD
    }

    private fun validDate(value: String?): Boolean =
        runCatching { LocalDate.parse(value?.take(10).orEmpty()) }.isSuccess

    private fun observe(text: String) =
        GuardedTradeAction(GuardedActionLevel.OBSERVATION, GuardedAction.HOLD, "仅观察：$text")

    private fun riskFact(text: String) =
        GuardedTradeAction(GuardedActionLevel.RISK_FACT, GuardedAction.HOLD, "风险观察：$text")

    private fun nextDay(reason: String, eligibility: TradeActionEligibility): GuardedTradeAction {
        val untracked = eligibility.untrackedQuantity.coerceAtLeast(0.0)
        val sameDay = (eligibility.todayLockedQuantity - untracked).coerceAtLeast(0.0)
        val lockText = when {
            sameDay > 0.0 && untracked > 0.0 -> "今日新增${sameDay.toLong()}股和未记录买入日期${untracked.toLong()}股"
            sameDay > 0.0 -> "今日新增${sameDay.toLong()}股"
            untracked > 0.0 -> "未记录买入日期${untracked.toLong()}股"
            else -> "当日无可卖批次"
        }
        return GuardedTradeAction(
            GuardedActionLevel.NEXT_DAY,
            GuardedAction.NEXT_DAY_REVIEW,
            "$reason；${lockText}受T+1或账本日期质量限制，下一交易日优先复核处置。",
        )
    }

    private fun formatPrice(value: Double): String = String.format(java.util.Locale.CHINA, "%.2f", value)

    private fun lotFloor(value: Double): Double =
        if (value.isFinite() && value > 0.0) floor(value / LOT) * LOT else 0.0
}
