package com.example.stockmonitor.analysis

import kotlin.math.floor
import kotlin.math.min

/**
 * 深套持仓（相对成本浮亏较深）的应对口径。
 *
 * 浅亏时跌破支撑减仓控风险是标准动作；但浮亏已经很深时（默认 ≤ -20%），破位那一刻
 * 往往伴随恐慌盘和短期超卖，再喊「先割一部分」容易割在低点、把浮亏变成实亏，剩下的
 * 仓位敞口也没变小。深套场景给的是**决策框架**而不是单一动作：
 * 逻辑 / 基本面已破坏 → 等反弹到压力位分批离场；逻辑还在 → 破位处既不割肉摊成本也不
 * 补仓接飞刀，等缩量止跌或站回支撑后小仓补仓降成本，反弹冲高做 T，再创新低则取消补仓。
 */
object DeepLossPolicy {

    /** 浮亏（相对成本）到这个百分比算深套；没有 ATR 数据时的兜底阈值。 */
    const val DEEP_LOSS_PCT = -20.0

    /** 深套阈值按 ATR 缩放的系数：约 8 个 ATR 日的跌幅才算「伤筋动骨」。 */
    const val DEEP_LOSS_ATR_MULT = 8.0

    /** 缩放后的深套阈值上下限：银行股 -8% 太敏感，题材股 -40% 太迟钝。 */
    const val DEEP_LOSS_MIN_PCT = -30.0
    const val DEEP_LOSS_MAX_PCT = -12.0

    /** 深套补仓只给初始仓位的一半：下跌中加仓比开新仓风险更高，数量要比试仓更小。 */
    const val ADD_ENTRY_RATIO = 0.5

    /** 建议数量按手取整。 */
    const val LOT = 100.0

    /** 相对持仓成本的浮亏幅度（百分比）；成本未填或无效时为 null。 */
    fun floatingPnlPct(cost: Double, price: Double): Double? {
        if (!cost.isFinite() || !price.isFinite() || cost <= 0 || price <= 0) return null
        return (price / cost - 1) * 100
    }

    /**
     * 深套阈值（负百分比）：默认 -20%，有 ATR% 就按波动率缩放——银行股日均波动 1%，
     * 亏 12% 就算伤筋动骨；题材股日均波动 4%，要亏到 30% 才和银行股的 12% 等「疼」。
     */
    fun deepLossThresholdPct(atrPct: Double?): Double {
        if (atrPct == null || !atrPct.isFinite() || atrPct <= 0) return DEEP_LOSS_PCT
        return (-DEEP_LOSS_ATR_MULT * atrPct).coerceIn(DEEP_LOSS_MIN_PCT, DEEP_LOSS_MAX_PCT)
    }

    fun isDeepLoss(cost: Double, price: Double, atrPct: Double? = null): Boolean {
        val pnl = floatingPnlPct(cost, price) ?: return false
        return pnl <= deepLossThresholdPct(atrPct)
    }

    /**
     * 深套补仓的参考股数：空仓试仓口径的一半（[ADD_ENTRY_RATIO]），
     * 再受最大仓位和可用资金约束，按手向下取整。没有总资金、可用资金不足一手
     * 或仓位已顶到上限都返回 0，调用方退回文字建议。
     */
    fun suggestedAddQuantity(
        price: Double,
        holdingQuantity: Double,
        totalCapital: Double,
        availableCapital: Double,
        initialEntryPct: Double,
        maxPositionPct: Double,
    ): Double {
        if (!price.isFinite() || price <= 0) return 0.0
        if (!totalCapital.isFinite() || totalCapital <= 0) return 0.0
        if (!initialEntryPct.isFinite() || !maxPositionPct.isFinite()) return 0.0
        if (initialEntryPct <= 0 || maxPositionPct <= 0) return 0.0
        val initialShares = totalCapital * initialEntryPct * ADD_ENTRY_RATIO / 100.0 / price
        val headroom = totalCapital * maxPositionPct / 100.0 - holdingQuantity * price
        if (headroom <= 0) return 0.0
        val shares = min(initialShares, headroom / price)
        // 可用资金没填（0）视为未知，不拿它卡数量；填了就按实际购买力封顶。
        val affordable = if (availableCapital.isFinite() && availableCapital > 0) {
            availableCapital / price
        } else {
            shares
        }
        return floor(min(shares, affordable) / LOT) * LOT
    }

    /**
     * 补仓股数的说明文案。四个分支的口径只有这一份——
     * [PortfolioAnalysis.deepLossBreakAction] 与 [IntradayTactics] 早先是逐字复制的两份，
     * 改一处漏一处，所以收拢到这里。
     */
    fun addQuantityNote(
        addQty: Double,
        price: Double,
        totalCapital: Double,
        availableCapital: Double,
    ): String = when {
        addQty > 0 -> "约 ${qtyText(addQty)} 股"
        totalCapital <= 0 -> "未填总资金，不给具体股数"
        availableCapital > 0 && availableCapital < price * LOT ->
            "可用资金不足一手，先补资金或减少数量"
        else -> "数量不足一手（补仓口径本就减半），按整体情况小仓操作"
    }

    /** 给持仓报告和盘中提醒使用的单一优先动作。 */
    fun primaryBreakAction(
        support: Double,
        resistance: Double,
        addNote: String,
        health: FundamentalHealth?,
        collapse: Boolean,
    ): String {
        val supportText = if (support > 0) priceText(support) else "支撑位"
        val resistanceText = if (resistance > 0) priceText(resistance) else "压力位"
        val capitalNote = if (addNote.contains("可用资金不足")) {
            "（机动仓提示：$addNote）"
        } else {
            ""
        }
        return when {
            collapse ->
                "当前优先：大盘/板块塌陷，破位处先不补仓、不处理底仓；保留底仓，机动仓只在反弹到压力附近滚动做T降低成本，回落未重新企稳不接回；等大盘重新站上 MA20、个股缩量止跌后再评估。"
            health?.level == HealthLevel.ALERT ->
                "当前优先：基本面亮红灯，破位恐慌位先不处理底仓；反弹到压力 $resistanceText 附近分批离场，若保留仓位只用机动仓做T，先不补仓（不追加仓位）。"
            health?.level == HealthLevel.SOUND ->
                "当前优先：基本面未见明显恶化，深套先不处理底仓、先不补仓；保留底仓，机动仓滚动做T降低成本：反弹到压力 $resistanceText 附近减，回踩支撑 $supportText 企稳再接回；再创新低取消接回。$capitalNote"
            health == null ->
                "当前优先：基本面暂无数据，深套先不处理底仓、先不补仓；保留底仓，机动仓滚动做T降低成本，反弹到压力 $resistanceText 附近再减，回踩未企稳不接回；先补拉一次基本面后再评估。$capitalNote"
            else ->
                "当前优先：基本面倾向不明确，深套先不处理底仓、先不补仓；保留底仓，机动仓滚动做T降低成本，反弹到压力 $resistanceText 附近再减，回踩支撑 $supportText 企稳再接回；重新走弱则取消接回。$capitalNote"
        }
    }

    private fun priceText(value: Double): String = String.format(java.util.Locale.CHINA, "%.2f", value)

    private fun qtyText(value: Double): String = String.format(java.util.Locale.CHINA, "%.0f", value)
}
