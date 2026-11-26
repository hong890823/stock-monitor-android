package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionCardPriorityTest {

    @Test
    fun poolCardUsesBestTierInCard() {
        // 卡内最好档位定档：有已确认就是已确认档，哪怕下面全是观察。
        val confirmed = SelectionCardPriority.poolCardPriority(
            buyCount = 1, nearCount = 5, priorityReentryCount = 0, observingCount = 20,
        )
        assertEquals(SelectionCardPriority.BuyPointTier.CONFIRMED, confirmed.tier)
        assertEquals(1, confirmed.count)

        val near = SelectionCardPriority.poolCardPriority(
            buyCount = 0, nearCount = 2, priorityReentryCount = 3, observingCount = 20,
        )
        assertEquals(SelectionCardPriority.BuyPointTier.NEAR, near.tier)
        assertEquals(5, near.count)

        val watch = SelectionCardPriority.poolCardPriority(
            buyCount = 0, nearCount = 0, priorityReentryCount = 0, observingCount = 4,
        )
        assertEquals(SelectionCardPriority.BuyPointTier.WATCH, watch.tier)
    }

    @Test
    fun noBuyPointRowsMeansHidden() {
        // 只剩暂缓/风险（不算买点）→ 卡片不展示。
        val none = SelectionCardPriority.poolCardPriority(
            buyCount = 0, nearCount = 0, priorityReentryCount = 0, observingCount = 0,
        )
        assertFalse(none.visible)
        assertFalse(SelectionCardPriority.reclaimCardPriority(0).visible)
        assertFalse(SelectionCardPriority.scanCardPriority(0).visible)
        assertTrue(SelectionCardPriority.scanCardPriority(3).visible)
    }

    @Test
    fun cardsSortByTierThenCount() {
        data class Card(val name: String, val priority: SelectionCardPriority.CardPriority)

        val cards = listOf(
            Card("深扫", SelectionCardPriority.scanCardPriority(30)),
            Card("观察池", SelectionCardPriority.poolCardPriority(2, 0, 0, 9)),
            Card("快扫", SelectionCardPriority.scanCardPriority(12)),
            Card("晋级", SelectionCardPriority.reclaimCardPriority(4)),
            Card("观察池B", SelectionCardPriority.poolCardPriority(5, 0, 0, 0)),
        )
        val sorted = SelectionCardPriority.sortByBuyPoint(cards) { it.priority }.map { it.name }

        // 买点已确认档（观察池 2 只、观察池B 5 只，只数多的在前）> 可参与档（晋级）> 线索档（深扫 30 > 快扫 12）。
        assertEquals(listOf("观察池B", "观察池", "晋级", "深扫", "快扫"), sorted)
    }

    @Test
    fun equalPriorityKeepsOriginalOrder() {
        data class Card(val name: String, val priority: SelectionCardPriority.CardPriority)

        val cards = listOf(
            Card("先", SelectionCardPriority.scanCardPriority(5)),
            Card("后", SelectionCardPriority.scanCardPriority(5)),
        )
        val sorted = SelectionCardPriority.sortByBuyPoint(cards) { it.priority }.map { it.name }
        assertEquals(listOf("先", "后"), sorted)
    }
}
