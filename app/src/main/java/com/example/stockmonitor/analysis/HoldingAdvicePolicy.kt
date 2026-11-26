package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import java.util.Locale
import kotlin.math.abs

/**
 * 持仓股详情页的合并「操作建议」：技术口径的 [TechnicalTrendSnapshot.primaryAdvice]（自选同款）
 * 与持仓视角（浮盈亏档位 / 当日急跌急涨 / 主力资金异动）合成**一项**输出（[mergedAdvice]），
 * 口径冲突时按统一优先级收口，保证一段话里不自相矛盾：
 *
 * 1. 技术买点（回踩再加 / 分批参与）遇当日急跌、主力大幅流出：买点不作废但先不执行——
 *    等缩量止跌、资金回流再按买点轻仓，急跌中不接刀；
 * 2. 技术买点遇深套：「加」只按做T口径轻仓接回机动仓、不是补仓摊成本（复用
 *    [DeepLossPolicy] 的做T框架：反弹到压力减、回踩企稳接回、再创新低取消接回）；
 * 3. 技术减仓 / 退出遇深套：按分批减机动仓执行，不一次恐慌清仓——破位风险独立成立时
 *    减仓成立，但不把深套本身当割肉理由；
 * 4. 技术减仓 / 退出遇大浮盈：落袋口径，不出现「支撑不破可继续持有」；
 * 5. 其余情形技术面原文照放，持仓上下文只作补充，动词方向天然一致。
 *
 * 只提示不命令：具体买卖动作仍由盘中分析的「应对」（priorityDecision）给出；
 * 深套口径复用 [DeepLossPolicy]（ATR 缩放阈值），与持仓报告同源。
 */
object HoldingAdvicePolicy {

    /** 技术面动作档位：从 [TechnicalTrendSnapshot.primaryAdvice] 的模板话头识别，供持仓上下文改写口径用。 */
    enum class TechnicalAction { EXIT_REDUCE, NO_ADD, ADD_PARTICIPATE, HOLD_WATCH }

    /**
     * 合并后的「操作建议」单项：技术面原文在前，持仓上下文在后；
     * 上下文动词按技术动作档位改写（见类注释的收口规则），两者永不互相打架。
     * 两项都空时返回 null（调用方不渲染该行）。
     */
    fun mergedAdvice(
        position: Position,
        quote: Quote?,
        technicalTrend: TechnicalTrendSnapshot?,
        fundFlow: FundFlow?,
    ): String? {
        val technical = technicalTrend?.primaryAdvice
            ?.trim()
            ?.removeSuffix("。")
            ?.takeIf { it.isNotBlank() }
        val context = contextAdvice(position, quote, technicalTrend, fundFlow, actionOf(technical))
        return when {
            technical == null -> context
            context == null -> technical
            else -> "$technical；$context"
        }
    }

    /**
     * 只有持仓上下文（浮盈亏档位、当日异动、资金异动）的旧口径入口：
     * 没有技术面可合并时的兜底，以及单测覆盖话术用。口径与 [mergedAdvice] 的上下文一致。
     */
    fun evaluate(
        position: Position,
        quote: Quote?,
        technicalTrend: TechnicalTrendSnapshot?,
        fundFlow: FundFlow?,
    ): String? = contextAdvice(position, quote, technicalTrend, fundFlow, actionOf(technicalTrend?.primaryAdvice))

    private fun contextAdvice(
        position: Position,
        quote: Quote?,
        technicalTrend: TechnicalTrendSnapshot?,
        fundFlow: FundFlow?,
        action: TechnicalAction,
    ): String? {
        val parts = mutableListOf<String>()
        val price = quote?.price?.takeIf { it.isFinite() && it > 0 }
        if (position.quantity > 0 && price != null && position.cost > 0) {
            val pnl = DeepLossPolicy.floatingPnlPct(position.cost, price)
            if (pnl != null) {
                pnlAdvice(pnl, position.cost, price, technicalTrend, action)?.let { parts += it }
            }
        }
        quote?.changePct?.takeIf { it.isFinite() }?.let { change ->
            intradayAdvice(change, action)?.let { parts += it }
        }
        fundFlow?.mainNetPct?.takeIf { it.isFinite() }?.let { pct ->
            flowAdvice(pct, action)?.let { parts += it }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("；") + "。"
    }

    /**
     * 从技术建议的模板话头识别动作档位。话头与
     * [TechnicalAssessmentPolicy] 的 buildAdvice 各分支一一对应，改动那边模板时
     * 同步 [HoldingAdvicePolicyTest] 里的分类用例。
     */
    internal fun actionOf(primaryAdvice: String?): TechnicalAction {
        val text = primaryAdvice ?: return TechnicalAction.HOLD_WATCH
        return when {
            text.contains("减仓或退出") -> TechnicalAction.EXIT_REDUCE
            text.contains("暂不补仓") || text.contains("暂缓补仓") -> TechnicalAction.NO_ADD
            text.contains("可分批参与") ||
                text.contains("可小仓观察") ||
                text.contains("可关注分批") -> TechnicalAction.ADD_PARTICIPATE
            else -> TechnicalAction.HOLD_WATCH
        }
    }

    /**
     * 浮盈亏档位：深套给决策框架（不割肉摊成本、也不接飞刀），浅套/贴成本/浮盈只给关注点；
     * ±2%~5% 的中间带不值得每天念叨，返回 null。
     * 深套与大浮盈的措辞按 [action] 收口，不和前一条技术建议唱反调。
     */
    private fun pnlAdvice(
        pnl: Double,
        cost: Double,
        price: Double,
        trend: TechnicalTrendSnapshot?,
        action: TechnicalAction,
    ): String? {
        val pnlText = String.format(Locale.CHINA, "%+.1f%%", pnl)
        val direction = if (pnl < 0) "亏" else "盈"
        val resistance = trend?.resistance?.takeIf { it.isFinite() && it > 0 }
        val support = trend?.support?.takeIf { it.isFinite() && it > 0 }
        return when {
            DeepLossPolicy.isDeepLoss(cost, price, trend?.atrPct) -> {
                val threshold = DeepLossPolicy.deepLossThresholdPct(trend?.atrPct)
                val head = "浮$direction $pnlText 已到深套口径（阈值 ${String.format(Locale.CHINA, "%.0f%%", threshold)}）"
                when (action) {
                    // 破位风险独立成立 → 减仓成立，但按分批减机动仓执行，不演变成恐慌清仓。
                    TechnicalAction.EXIT_REDUCE ->
                        "$head：破位风险独立成立，按分批减机动仓处理" +
                            (resistance?.let { "，反弹到压力 ${priceText(it)} 附近减" } ?: "") +
                            "，不一次恐慌清仓；先别急着补仓摊成本" +
                            (support?.let { "，破位恐慌位 ${priceText(it)} 不动底仓" } ?: "") +
                            "，缩量止跌前不接飞刀"
                    // 技术买点降级为做T口径：接回的是机动仓，不是补仓摊成本。
                    TechnicalAction.ADD_PARTICIPATE ->
                        "$head：技术面的「加」只按做T口径轻仓接回机动仓，不补仓摊成本——" +
                            "保留底仓、机动仓滚动做T降成本" +
                            (resistance?.let { "，反弹到压力 ${priceText(it)} 附近减" } ?: "") +
                            (support?.let { "，回踩支撑 ${priceText(it)} 企稳再接回" } ?: "") +
                            "，再创新低取消接回"
                    else ->
                        "$head：先别急着割肉、也别急着补仓摊成本" +
                            (resistance?.let { "，反弹到压力 ${priceText(it)} 附近再分批减" } ?: "") +
                            (support?.let { "；破位恐慌位不动底仓，缩量止跌前不接飞刀" } ?: "")
                }
            }
            pnl <= -5.0 ->
                "浮$direction $pnlText" +
                    (support?.let { "，留意支撑 ${priceText(it)}，跌破再考虑控仓也不迟" } ?: "，留意支撑位")
            abs(pnl) <= 2.0 ->
                "现价基本贴着成本（$pnlText）" +
                    (support?.let { "，可先想好保本位：跌破支撑 ${priceText(it)} 先出来观察" } ?: "")
            pnl >= 5.0 ->
                if (action == TechnicalAction.EXIT_REDUCE) {
                    // 技术面已经要减仓，就不再说「支撑不破可继续持有」——那是自相矛盾。
                    "浮$direction $pnlText：正好分批落袋、注意回撤保护，别让浮盈坐过山车"
                } else {
                    "浮$direction $pnlText，注意回撤保护" +
                        (support?.let { "，支撑 ${priceText(it)} 不破可继续持有" } ?: "")
                }
            else -> null
        }
    }

    private fun intradayAdvice(change: Double, action: TechnicalAction): String? = when {
        change <= -5.0 ->
            if (action == TechnicalAction.ADD_PARTICIPATE) {
                "今日急跌 ${pctText(change)}：技术面的回踩买点今天先不执行，先别接刀，等缩量止跌或重新站稳再轻仓"
            } else {
                "今日急跌 ${pctText(change)}：先别接刀，等缩量止跌或重新站稳再说"
            }
        change >= 5.0 ->
            if (action == TechnicalAction.ADD_PARTICIPATE) {
                "今日大涨 ${pctText(change)}：技术买点只按回踩低吸口径，今天不追高，留意是否放量过热"
            } else {
                "今日大涨 ${pctText(change)}：留意是否放量过热，不追高"
            }
        else -> null
    }

    private fun flowAdvice(pct: Double, action: TechnicalAction): String? = when {
        pct <= -10.0 ->
            if (action == TechnicalAction.ADD_PARTICIPATE) {
                "主力净流出占成交额 ${pctText(pct)}，抛压明显——技术买点先缓一缓，反弹先当减仓波对待，等资金回流再执行"
            } else {
                "主力净流出占成交额 ${pctText(pct)}，抛压明显，反弹先当减仓波对待"
            }
        pct >= 10.0 -> "主力净流入占成交额 ${pctText(pct)}，留意资金是否在回流"
        else -> null
    }

    private fun priceText(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    private fun pctText(value: Double): String = String.format(Locale.CHINA, "%+.2f%%", value)
}
