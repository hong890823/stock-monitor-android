package com.example.stockmonitor.analysis

import java.util.Locale

/** 单次买卖成本计算结果，对应 trade_calculator.calculate_trade。 */
data class TradeResult(
    val side: String,
    val holdingShares: Double,
    val averageCost: Double,
    val tradeShares: Double,
    val tradePrice: Double,
    val fees: Double,
    val grossAmount: Double,
    val afterShares: Double,
    val afterCostTotal: Double,
    val afterAverageCost: Double,
    val realizedPnl: Double,
    val currentPrice: Double?,
    val floatingPnl: Double?,
    val floatingPct: Double?,
    val calculatedAt: String,
)

object TradeCalculator {

    fun calculate(
        holdingShares: Double,
        averageCost: Double,
        side: String,
        tradeShares: Double,
        tradePrice: Double,
        fees: Double = 0.0,
        currentPrice: Double? = null,
    ): TradeResult {
        val inputs = listOf(holdingShares, averageCost, tradeShares, tradePrice, fees)
        if (inputs.any { !it.isFinite() } || currentPrice?.isFinite() == false ||
            currentPrice != null && currentPrice <= 0 ||
            holdingShares < 0 || averageCost < 0 || tradeShares <= 0 || tradePrice <= 0 || fees < 0
        ) {
            throw IllegalArgumentException("股数、价格和费用必须是有效的非负数字，操作股数和价格必须大于0")
        }
        val normalized = side.trim().lowercase()
        if (normalized !in setOf("买入", "卖出", "buy", "sell")) {
            throw IllegalArgumentException("操作类型只能是买入或卖出")
        }
        val isBuy = normalized == "买入" || normalized == "buy"
        if (!isBuy && tradeShares > holdingShares) {
            throw IllegalArgumentException("卖出股数不能大于当前持仓股数")
        }

        val beforeCostTotal = holdingShares * averageCost
        val grossAmount = tradeShares * tradePrice
        var realizedPnl = 0.0
        val afterShares: Double
        val afterCostTotal: Double
        val afterAverageCost: Double
        if (isBuy) {
            afterShares = holdingShares + tradeShares
            afterCostTotal = beforeCostTotal + grossAmount + fees
            afterAverageCost = if (afterShares != 0.0) afterCostTotal / afterShares else 0.0
        } else {
            afterShares = holdingShares - tradeShares
            afterCostTotal = afterShares * averageCost
            afterAverageCost = if (afterShares != 0.0) averageCost else 0.0
            realizedPnl = (tradePrice - averageCost) * tradeShares - fees
        }

        var floatingPnl: Double? = null
        var floatingPct: Double? = null
        if (currentPrice != null && afterShares > 0) {
            floatingPnl = (currentPrice - afterAverageCost) * afterShares
            floatingPct = if (afterAverageCost != 0.0) (currentPrice / afterAverageCost - 1) * 100 else null
        }

        return TradeResult(
            side = if (isBuy) "买入" else "卖出",
            holdingShares = holdingShares,
            averageCost = averageCost,
            tradeShares = tradeShares,
            tradePrice = tradePrice,
            fees = fees,
            grossAmount = grossAmount,
            afterShares = afterShares,
            afterCostTotal = afterCostTotal,
            afterAverageCost = afterAverageCost,
            realizedPnl = realizedPnl,
            currentPrice = currentPrice,
            floatingPnl = floatingPnl,
            floatingPct = floatingPct,
            calculatedAt = TimeUtils.nowText(),
        )
    }

    /** 成本计算报告，等价于 format_trade_report。 */
    fun formatReport(result: TradeResult, stockLabel: String? = null, feeNote: String? = null): String {
        val label = stockLabel ?: "未指定股票"
        val lines = mutableListOf(
            "💰 买卖成本计算",
            "对象：$label",
            "计算时间：${result.calculatedAt}",
            "",
            "一、操作信息",
            "操作：${result.side} ${String.format(Locale.CHINA, "%.0f", result.tradeShares)}股 @ ${String.format(Locale.CHINA, "%.2f", result.tradePrice)}",
            "交易金额：${String.format(Locale.CHINA, "%.2f", result.grossAmount)}元",
            "交易费用：${String.format(Locale.CHINA, "%.2f", result.fees)}元",
            "",
            "二、操作后持仓",
            "剩余/持有股数：${String.format(Locale.CHINA, "%.0f", result.afterShares)}股",
            "操作后平均成本：${String.format(Locale.CHINA, "%.4f", result.afterAverageCost)}元",
            "操作后持仓成本总额：${String.format(Locale.CHINA, "%.2f", result.afterCostTotal)}元",
        )
        if (result.side == "卖出") {
            lines.add("本次已实现盈亏：${String.format(Locale.CHINA, "%+.2f", result.realizedPnl)}元")
        }
        if (result.floatingPnl != null) {
            lines.add("")
            lines.add("三、按当前价估算")
            lines.add("当前价格：${String.format(Locale.CHINA, "%.2f", result.currentPrice ?: 0.0)}元")
            lines.add("剩余持仓浮盈/浮亏：${String.format(Locale.CHINA, "%+.2f", result.floatingPnl)}元")
            lines.add(
                "浮盈/浮亏比例：${result.floatingPct?.let {
                    String.format(Locale.CHINA, "%+.2f", it) + "%"
                } ?: "无法计算（成本为0）"}"
            )
        } else {
            lines.add("")
            lines.add("三、浮盈/浮亏")
            lines.add("未输入股票名称/代码，未获取当前价，本次只计算操作后成本。")
        }
        feeNote?.let { lines.add("费用说明：$it") }
        lines.add("")
        lines.add("说明：本计算不写回持仓配置；未计入的佣金、印花税、过户费等会影响实际结果。")
        lines.add("⚠️ 仅作计算参考，不构成投资建议。")
        return lines.joinToString("\n")
    }
}
