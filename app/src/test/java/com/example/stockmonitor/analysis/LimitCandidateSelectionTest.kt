package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.LimitStock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitCandidateSelectionTest {

    private fun stock(code: String, pct: Double, limitStreak: Int?) = LimitStock(
        code = code,
        name = code,
        price = 10.0,
        prev = 10.0,
        pct = pct,
        limitStreak = limitStreak,
    )

    @Test
    fun firstBoardsAreNotCrowdedOutByHigherPercentBoards() {
        // 120 只主板首板（10%）+ 20 只 20cm 连板：旧实现按 pct 截断前 100 只剩 80 只首板。
        val firstBoards = (1..120).map { stock("sh60%04d".format(it), 10.0, 1) }
        val others = (1..20).map { stock("sz30%04d".format(it), 20.0, 2) }

        val selected = LimitCandidateSelection.select(firstBoards + others)

        assertEquals(120, selected.count { it.limitStreak == 1 })
        assertTrue("最后一只首板也必须在列表里", selected.any { it.code == firstBoards.last().code })
        assertEquals(20, selected.count { it.limitStreak != 1 })
    }

    @Test
    fun otherCandidatesStayCappedAndTotalStaysBounded() {
        val firstBoards = (1..150).map { stock("sh60%04d".format(it), 10.0, 1) }
        val others = (1..300).map { stock("sz30%04d".format(it), 20.0, 2) }

        val selected = LimitCandidateSelection.select(firstBoards + others)

        assertEquals(150, selected.count { it.limitStreak == 1 })
        assertEquals(50, selected.count { it.limitStreak != 1 })
        assertEquals(LimitCandidateSelection.MAX_TOTAL, selected.size)
    }

    @Test
    fun missingStreakIsTreatedAsNonFirstBoard() {
        val firstBoard = stock("sh600001", 10.0, 1)
        val unknown = stock("sh600002", 11.0, null)

        val selected = LimitCandidateSelection.select(listOf(unknown, firstBoard))

        assertEquals(listOf(firstBoard, unknown), selected)
    }
}