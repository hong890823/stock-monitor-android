package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import java.util.Locale
import kotlin.math.abs

/**
 * 持仓股的「持仓视角」文案：技术口径的操作建议不带持仓上下文，这里补上——
 * 浮盈亏档位（深套 / 浅套 / 贴成本 / 浮盈）、当日急跌急涨、主力资金异动。
 * 只提示不命令：具体买卖动作仍由盘中分析的「应对」（priorityDecision）给出；
 * 深套口径复用 [DeepLossPolicy]（ATR 缩放阈值），与持仓报告同源。
 */
object HoldingAdvicePolicy {

    fun evaluate(
        position: Position,
        quote: Quote?,
        technicalTrend: TechnicalTrendSnapshot?,
        fundFlow: FundFlow?,
    ): String? {
        val parts = mutableListOf<String>()
        val price = quote?.price?.takeIf { it.isFinite() && it > 0 }
        if (position.quantity > 0 && price != null && position.cost > 0) {
            val pnl = DeepLossPolicy.floatingPnlPct(position.cost, price)
            if (pnl != null) {
                pnlAdvice(pnl, position.cost, price, technicalTrend)?.let { parts += it }
            }
        }
        quote?.changePct?.takeIf { it.isFinite() }?.let { change ->
            intradayAdvice(change)?.let { parts += it }
        }
        fundFlow?.mainNetPct?.takeIf { it.isFinite() }?.let { pct ->
            flowAdvice(pct)?.let { parts += it }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("；") + "。"
    }

    /**
     * 浮盈亏档位：深套给决策框架（不割肉摊成本、也不接飞刀），浅套/贴成本/浮盈只给关注点；
     * ±2%~5% 的中间带不值得每天念叨，返回 null。
     */
    private fun pnlAdvice(
        pnl: Double,
        cost: Double,
        price: Double,
        trend: TechnicalTrendSnapshot?,
    ): String? {
        val pnlText = String.format(Locale.CHINA, "%+.1f%%", pnl)
        val direction = if (pnl < 0) "亏" else "盈"
        val resistance = trend?.resistance?.takeIf { it.isFinite() && it > 0 }
        val support = trend?.support?.takeIf { it.isFinite() && it > 0 }
        return when {
            DeepLossPolicy.isDeepLoss(cost, price, trend?.atrPct) -> {
                val threshold = DeepLossPolicy.deepLossThresholdPct(trend?.atrPct)
                "浮$direction $pnlText 已到深套口径（阈值 ${String.format(Locale.CHINA, "%.0f%%", threshold)}）：" +
                    "先别急着割肉、也别急着补仓摊成本" +
                    (resistance?.let { "，反弹到压力 ${priceText(it)} 附近再分批减" } ?: "") +
                    (support?.let { "；破位恐慌位不动底仓，缩量止跌前不接飞刀" } ?: "")
            }
            pnl <= -5.0 ->
                "浮$direction $pnlText" +
                    (support?.let { "，留意支撑 ${priceText(it)}，跌破再考虑控仓也不迟" } ?: "，留意支撑位")
            abs(pnl) <= 2.0 ->
                "现价基本贴着成本（$pnlText）" +
                    (support?.let { "，可先想好保本位：跌破支撑 ${priceText(it)} 先出来观察" } ?: "")
            pnl >= 5.0 ->
                "浮$direction $pnlText，注意回撤保护" +
                    (support?.let { "，支撑 ${priceText(it)} 不破可继续持有" } ?: "")
            else -> null
        }
    }

    private fun intradayAdvice(change: Double): String? = when {
        change <= -5.0 -> "今日急跌 ${pctText(change)}：先别接刀，等缩量止跌或重新站稳再说"
        change >= 5.0 -> "今日大涨 ${pctText(change)}：留意是否放量过热，不追高"
        else -> null
    }

    private fun flowAdvice(pct: Double): String? = when {
        pct <= -10.0 -> "主力净流出占成交额 ${pctText(pct)}，抛压明显，反弹先当减仓波对待"
        pct >= 10.0 -> "主力净流入占成交额 ${pctText(pct)}，留意资金是否在回流"
        else -> null
    }

    private fun priceText(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    private fun pctText(value: Double): String = String.format(Locale.CHINA, "%+.2f%%", value)
}
