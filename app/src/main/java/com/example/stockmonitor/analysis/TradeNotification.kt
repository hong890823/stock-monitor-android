package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.TradeActionEligibility
import com.example.stockmonitor.core.TradeActionGuard
import com.example.stockmonitor.core.TradeActionPermissions
import com.example.stockmonitor.core.GuardedTradeAction
import com.example.stockmonitor.core.GuardedActionLevel
import com.example.stockmonitor.core.GuardedAction
import com.example.stockmonitor.core.IntradayVolumeContext
import com.example.stockmonitor.core.TimeframeEvidence

object TradeNotification {

    data class SellEstimate(
        val referenceCost: Double,
        val quantity: Double,
        val estimatedFee: Double,
        val estimatedRealizedPnl: Double,
        val recoveredFunds: Double,
        val remainingQuantity: Double,
    )

    /** 通知级卖出估算；只用于人工核对，真实成交仍以交易记录为准。 */
    fun estimateSell(
        holdingQuantity: Double,
        averageCost: Double,
        price: Double,
        quantity: Double,
        feeRate: Double = 0.001,
        minimumFee: Double = 5.0,
    ): SellEstimate? {
        val values = listOf(holdingQuantity, averageCost, price, quantity, feeRate, minimumFee)
        if (values.any { !it.isFinite() } || holdingQuantity <= 0.0 || averageCost < 0.0 ||
            price <= 0.0 || quantity <= 0.0 || quantity > holdingQuantity || feeRate < 0.0 || minimumFee < 0.0
        ) return null
        val gross = price * quantity
        val fee = maxOf(minimumFee, gross * feeRate)
        return SellEstimate(
            referenceCost = averageCost,
            quantity = quantity,
            estimatedFee = fee,
            estimatedRealizedPnl = (price - averageCost) * quantity - fee,
            recoveredFunds = (gross - fee).coerceAtLeast(0.0),
            remainingQuantity = (holdingQuantity - quantity).coerceAtLeast(0.0),
        )
    }

    fun sellEstimateText(estimate: SellEstimate): String =
        "参考成本 ${money(estimate.referenceCost)}｜拟卖 ${shares(estimate.quantity)}｜" +
            "估算实现盈亏 ${signedMoney(estimate.estimatedRealizedPnl)}｜" +
            "预计回收 ${money(estimate.recoveredFunds)}（费用约 ${money(estimate.estimatedFee)}）｜" +
            "卖后剩余 ${shares(estimate.remainingQuantity)}"

    private fun money(value: Double): String = String.format(java.util.Locale.CHINA, "%.2f元", value)

    private fun signedMoney(value: Double): String = String.format(java.util.Locale.CHINA, "%+.2f元", value)

    private fun shares(value: Double): String = String.format(java.util.Locale.CHINA, "%.0f股", value)

    fun portfolio(
        result: PositionResult,
        sellableQuantity: Double = result.quantity,
        lockedToday: Double = 0.0,
        untrackedQuantity: Double = 0.0,
        ledgerConsistent: Boolean,
        tradeDate: String,
        evidence: TimeframeEvidence? = null,
        volume: IntradayVolumeContext? = null,
    ): GuardedTradeAction? {
        val decision = PortfolioAnalysis.priorityDecision(result, tradeDate)
        if (!decision.actionConfirmed) return null
        val guarded = TradeActionGuard.evaluate(
            intent = when (decision.state) {
                PriorityState.EXIT -> "exit_position"
                PriorityState.REDUCE -> "risk_reduce"
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
        return if (guarded.requiresExitReview) guarded.copy(
            text = guarded.text + "｜退出候选触发时证据（区别于本次复核采样）：" +
                result.priorityExitAudit.ifBlank { "未留存，不能用当前缓存替代当时依据" },
        ) else guarded
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
        maxActionQuantity: Double = Double.POSITIVE_INFINITY,
    ): GuardedTradeAction {
        val riskContext = if (signal.type.isRisk || signal.holdingExitStage in setOf(
                HoldingExitStage.WATCH,
                HoldingExitStage.DEEP_T,
                HoldingExitStage.NOTICE,
            )
        ) holdingRiskContext(input) else ""
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
                "仅观察：${signal.body}$riskContext",
            )
        }
        if (signal.type == IntradaySignalType.BREAK_NOTICE ||
            signal.holdingExitStage == HoldingExitStage.NOTICE
        ) {
            return GuardedTradeAction(
                GuardedActionLevel.RISK_FACT,
                com.example.stockmonitor.core.GuardedAction.HOLD,
                "风险观察：破位风险提示只报告风险，不生成确定性卖量。$riskContext",
            )
        }
        // 高开低走是“风险事实 + 评估建议”，不是可直接执行的减仓指令：
        // 先把回落事实送达用户，股数与 15/30 分钟证据留给风险/普通卖出通道复核。
        if (signal.type == IntradaySignalType.GAP_FADE) {
            val detail = signal.gapFade?.let {
                GapFadePolicy.holdingBody(it) + "｜" + GapFadePolicy.holdingNotification(it)
            } ?: "高开低走风险事实待复核"
            return GuardedTradeAction(
                GuardedActionLevel.RISK_FACT,
                com.example.stockmonitor.core.GuardedAction.HOLD,
                "$detail｜风险事实，不生成确定性卖量。$riskContext",
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
        val sizing = if (intent in setOf("t_sell", "normal_exit")) IntradayTactics.suggestedTSellQuantity(input) else null
        val allocation = input.positionAllocation
        val actionContext = when (intent) {
            "risk_reduce", "high_reduce", "exit_position" -> riskContext
            "normal_exit" -> "｜${costReferenceText(input)}${allocationText(input)}"
            else -> ""
        }
        if (intent in setOf("risk_reduce", "high_reduce") && allocation == null) {
            return GuardedTradeAction(
                GuardedActionLevel.OBSERVATION,
                GuardedAction.HOLD,
                "仅观察：分仓尚未配置，无法确认底仓保护范围，不生成确定性减仓股数。$riskContext",
            )
        }
        val protectedBaseQuantity = if (intent in setOf("risk_reduce", "high_reduce") &&
            allocation?.mode == com.example.stockmonitor.core.PositionAllocationMode.NORMAL
        ) {
            allocation?.baseQuantityTarget?.coerceIn(0.0, input.quantity.coerceAtLeast(0.0)) ?: 0.0
        } else {
            0.0
        }
        val riskSellCapacity = if (intent in setOf("risk_reduce", "high_reduce")) {
            minOf(input.sellableQuantity ?: input.quantity, (input.quantity - protectedBaseQuantity).coerceAtLeast(0.0))
        } else {
            input.sellableQuantity ?: 0.0
        }
        // 门控条件按维度记录（能力快照口径，2026-11-26 权限解耦）：这些是**买侧**条件，
        // 只拦买入/加仓/接回；做T卖出是减风险动作，不适用「不允许新增买入 / 大趋势向下
        // 不接回」类拦截（否则跌势里的反弹减仓被连带封死，与深套做T框架相反）。
        // 做T卖出的守卫一个不少：15/30分钟卖点证据、净收益门槛、可卖量与同日计划额度。
        val blocked = mutableListOf<String>()
        if (input.weakEnv) blocked += "大盘或板块环境转弱"
        if (input.fundamentalHealth?.level == HealthLevel.ALERT) blocked += "基本面亮红灯"
        if (input.highPosition?.allowBuy == false) blocked += "高位结构不允许新增买入"
        if (input.trendAssessment?.major == MajorTrend.DOWN) blocked += "大趋势向下"
        if (reentry && capacity <= 0.0) blocked += "缺少真实卖出后未接回数量"
        val finalTQuantity = if (maxActionQuantity.isNaN()) 0.0 else kotlin.math.floor(
            minOf(sizing?.quantity ?: 0.0, signal.tSellNetProfitEstimate?.quantity ?: 0.0, maxActionQuantity)
                .coerceAtLeast(0.0) / 100.0,
        ) * 100.0
        val finalTEstimate = if (intent == "t_sell") IntradayTNetProfitPolicy.estimate(
            input.price, input.support, input.atrPct ?: input.holdingTContext?.atrPct, finalTQuantity,
        ) else null
        val guarded = TradeActionGuard.evaluate(
            intent = intent,
            evidence = input.timeframeEvidence,
            volume = input.volumeContext,
            eligibility = TradeActionEligibility(
                totalQuantity = input.quantity,
                actionableSellableQuantity = when (intent) {
                    "normal_exit" -> sizing?.quantity ?: 0.0
                    "risk_reduce", "high_reduce" -> riskSellCapacity
                    else -> input.sellableQuantity ?: 0.0
                },
                todayLockedQuantity = input.lockedQuantity,
                untrackedQuantity = input.untrackedQuantity,
                buyCapacityQuantity = capacity,
                reentryObservationConfirmed = reentry && IntradayTactics.reentryObservationConfirmed(input),
                plannedTSellQuantity = finalTQuantity,
                tSellNetProfitApproved = finalTEstimate?.meetsMinimum == true,
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
                // 做T卖出不设买侧门（见上方门控注释）；它自己的守卫在 TradeActionGuard 的
                // 卖点证据、净收益与数量检查里。
                allowT = true,
                blockers = blocked,
            ),
        )
        val limited = limitQuantity(guarded, maxActionQuantity)
        if (limited.requiresExitReview) return limited.copy(
            text = limited.text + "｜盘中退出候选触发时证据：现价${input.price}、支撑${input.breakObservation?.support ?: input.support}、" +
                "退出参考${input.breakObservation?.exitPrice ?: HoldingExitPolicy.exitReference(input.support, input.atrPct)}、" +
                "趋势转弱${input.entrySetup?.trendWeak ?: false}；候选信号不构成清仓授权。$actionContext",
        )
        if (limited.level != GuardedActionLevel.ACTIONABLE) return if (actionContext.isNotEmpty()) {
            limited.copy(text = limited.text + actionContext)
        } else limited
        return when (limited.action) {
            GuardedAction.T_SELL -> limited.copy(text = limited.text + String.format(
                java.util.Locale.CHINA,
                "｜%s；按预计接回价%.2f元测算，双边费用和滑点后预计净收益%.2f元（门槛%.2f元）；该配对净收益估算独立于历史持仓成本，不保证实际盈利，未成交不生成接回额度。%s%s",
                sizing?.note, finalTEstimate!!.expectedBuybackPrice, finalTEstimate.expectedNetProfit,
                finalTEstimate.requiredNetProfit, costReferenceText(input), allocationText(input),
            ))
            GuardedAction.RISK_REDUCE, GuardedAction.RISK_EXIT -> limited.copy(
                text = limited.text + "｜这是风控处置，不以做T盈利为前提。$actionContext",
            )
            GuardedAction.NORMAL_EXIT -> limited.copy(text = limited.text + actionContext)
            else -> limited
        }
    }

    private fun holdingRiskContext(input: IntradayTactics.Input): String {
        return "｜${costReferenceText(input)}${allocationText(input)}技术风险不等于持有价值判断；提醒不代表已成交。"
    }

    private fun costReferenceText(input: IntradayTactics.Input): String {
        return if (input.cost.isFinite() && input.cost > 0.0 && input.price.isFinite() && input.price > 0.0) {
            val pnlPct = (input.price - input.cost) / input.cost * 100.0
            val recoveryPct = ((input.cost - input.price) / input.price * 100.0).coerceAtLeast(0.0)
            String.format(
                java.util.Locale.CHINA,
                "｜持仓成本参考%.2f元，当前相对成本%s%.1f%%；%s。",
                input.cost,
                if (pnlPct >= 0.0) "浮盈" else "浮亏",
                kotlin.math.abs(pnlPct),
                if (recoveryPct > 0.0) String.format(
                    java.util.Locale.CHINA,
                    "距离回本参考价需上涨%.1f%%（不含费用，非回本保证）",
                    recoveryPct,
                ) else "现价已不低于成本参考价，实际盈亏仍受费用影响",
            )
        } else {
            "｜持仓成本数据暂缺，无法计算成本参考距离。"
        }
    }

    private fun allocationText(input: IntradayTactics.Input): String {
        return when (val config = input.positionAllocation) {
            null -> "分仓尚未配置。"
            else -> if (config.mode == com.example.stockmonitor.core.PositionAllocationMode.NORMAL) {
                "已确认底仓目标${config.baseQuantityTarget.toLong()}股。"
            } else {
                "当前为风险观察分仓，未预设底仓与滚动仓比例。"
            }
        }
    }

    fun riskPriority(action: GuardedTradeAction): Int = if (action.level != GuardedActionLevel.ACTIONABLE) 0 else when (action.action) {
        GuardedAction.RISK_EXIT -> 2
        GuardedAction.RISK_REDUCE -> 1
        else -> 0
    }

    fun limitQuantity(action: GuardedTradeAction, maximum: Double): GuardedTradeAction {
        if (action.level != GuardedActionLevel.ACTIONABLE || maximum == Double.POSITIVE_INFINITY) return action
        val quantity = if (!maximum.isFinite()) 0.0 else kotlin.math.floor(
            minOf(maximum, maxOf(action.maxSellQuantity, action.maxBuyQuantity)).coerceAtLeast(0.0) / 100.0,
        ) * 100.0
        if (quantity < 100.0) return GuardedTradeAction(
            GuardedActionLevel.OBSERVATION, GuardedAction.HOLD, "仅观察：同日计划剩余额度或当前可操作数量不足一手。",
        )
        if (quantity == maxOf(action.maxSellQuantity, action.maxBuyQuantity)) return action
        if (action.action == GuardedAction.T_SELL) return GuardedTradeAction(
            GuardedActionLevel.OBSERVATION, GuardedAction.HOLD, "仅观察：做T股数变化，须按最终股数重新核算双边费用、滑点与净收益。",
        )
        val buying = action.maxBuyQuantity > 0.0
        val purpose = when (action.action) {
            GuardedAction.RISK_EXIT -> "风险退出"
            GuardedAction.RISK_REDUCE -> "风险减仓"
            GuardedAction.NORMAL_EXIT -> "普通卖出"
            else -> "买入"
        }
        return action.copy(
            maxSellQuantity = if (buying) 0.0 else quantity,
            maxBuyQuantity = if (buying) quantity else 0.0,
            text = "${purpose}门控已通过，受同日计划剩余额度限制，本次最多${if (buying) "买入" else "卖出"}${quantity.toLong()}股；" +
                "不追加数量，今日新增仓位受T+1限制。" +
                if (action.invalidationPrice > 0.0) String.format(java.util.Locale.CHINA, "失效价%.2f元。", action.invalidationPrice) else "",
        )
    }
}
