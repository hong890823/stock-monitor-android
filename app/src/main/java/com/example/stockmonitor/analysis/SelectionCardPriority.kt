package com.example.stockmonitor.analysis

/**
 * 选股 Tab 卡片的买点优先级（2026-11-26）：股票卡片按卡内**最好买点档位**从高到低排列，
 * 同档比只数；完全没有买点相关行的卡片不展示。「扫描状态」「说明」不参与（固定头/尾）。
 *
 * 档位从高到低：买点已确认 > 买点待确认/优先复参与 > 可参与/启动前潜力（活跃观察）
 * > 线索（快扫/深扫命中）。暂缓参与、风险观察、已淘汰、被过滤的记录不算买点。
 */
object SelectionCardPriority {

    enum class BuyPointTier(val rank: Int, val label: String) {
        CONFIRMED(4, "买点已确认"),
        NEAR(3, "买点待确认/优先复参与"),
        WATCH(2, "可参与/启动前潜力"),
        CLUE(1, "线索"),
        NONE(0, "无买点"),
    }

    data class CardPriority(val tier: BuyPointTier, val count: Int) {
        /** 无买点相关行的卡片不展示。 */
        val visible: Boolean get() = tier != BuyPointTier.NONE && count > 0
    }

    /** 持续观察池卡：已确认 > 待确认/优先复参与 > 活跃观察（含启动前潜力）。 */
    fun poolCardPriority(
        buyCount: Int,
        nearCount: Int,
        priorityReentryCount: Int,
        observingCount: Int,
    ): CardPriority = when {
        buyCount > 0 -> CardPriority(BuyPointTier.CONFIRMED, buyCount)
        nearCount + priorityReentryCount > 0 ->
            CardPriority(BuyPointTier.NEAR, nearCount + priorityReentryCount)
        observingCount > 0 -> CardPriority(BuyPointTier.WATCH, observingCount)
        else -> CardPriority(BuyPointTier.NONE, 0)
    }

    /** 涨停晋级卡：展示层校验通过的「可参与」记录（承接观察线索档）。 */
    fun reclaimCardPriority(participatingCount: Int): CardPriority =
        if (participatingCount > 0) {
            CardPriority(BuyPointTier.WATCH, participatingCount)
        } else {
            CardPriority(BuyPointTier.NONE, 0)
        }

    /** 快扫/深扫卡：命中即线索级。 */
    fun scanCardPriority(hitCount: Int): CardPriority =
        if (hitCount > 0) {
            CardPriority(BuyPointTier.CLUE, hitCount)
        } else {
            CardPriority(BuyPointTier.NONE, 0)
        }

    /** 买点档位降序 → 只数降序；排序稳定，同优先级保持原有先后。 */
    fun <T> sortByBuyPoint(cards: List<T>, priorityOf: (T) -> CardPriority): List<T> =
        cards.sortedWith(
            compareByDescending<T> { priorityOf(it).tier.rank }
                .thenByDescending { priorityOf(it).count },
        )
}
