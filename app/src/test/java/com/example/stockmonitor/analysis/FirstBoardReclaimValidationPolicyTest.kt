package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FirstBoardReclaimObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstBoardReclaimValidationPolicyTest {

    @Test
    fun staleRecordsAreFilteredForReviewOnly() {
        // 触发于 3 个交易日前：过时效，只作复核。
        val verdict = evaluate(record = record(tradingDay = "2026-11-20"), today = "2026-11-25")
        assertFalse(verdict.participate)
        assertEquals(FirstBoardReclaimValidationPolicy.Category.TIMELINESS, verdict.category)
    }

    @Test
    fun todayAndNextDayAreParticipatory() {
        assertTrue(evaluate(record = record(tradingDay = "2026-11-25"), today = "2026-11-25").participate)
        assertTrue(evaluate(record = record(tradingDay = "2026-11-24"), today = "2026-11-25").participate)
    }

    @Test
    fun dipDepthOutsideBandIsFiltered() {
        // 下探过深（-8% 以外）形同破位。
        val deep = evaluate(record = record(low = 9.10, open = 10.00), today = "2026-11-25")
        assertFalse(deep.participate)
        assertEquals(FirstBoardReclaimValidationPolicy.Category.WEAKENED, deep.category)
        assertTrue(deep.reason.contains("下探过深"))

        // 下探太浅（-0.5% 以内）洗盘有限。
        val shallow = evaluate(record = record(low = 9.98, open = 10.00), today = "2026-11-25")
        assertFalse(shallow.participate)
        assertTrue(shallow.reason.contains("洗盘有限"))
    }

    @Test
    fun priceBackBelowOpenIsFilteredAsWeakened() {
        val verdict = evaluate(
            record = record(open = 10.00, low = 9.60, price = 10.05),
            currentPrice = 9.80,
            today = "2026-11-25",
        )
        assertFalse(verdict.participate)
        assertEquals(FirstBoardReclaimValidationPolicy.Category.WEAKENED, verdict.category)
        assertTrue(verdict.reason.contains("跌回开盘价"))
    }

    @Test
    fun missingCurrentPriceCannotVerifyParticipation() {
        val verdict = evaluate(record = record(), currentPrice = null, today = "2026-11-25")
        assertFalse(verdict.participate)
        assertTrue(verdict.reason.contains("现价缺失"))
    }

    @Test
    fun persistentOutflowIsFiltered() {
        val verdict = evaluate(
            record = record(),
            today = "2026-11-25",
            latestMainNetPct = -4.5,
        )
        assertFalse(verdict.participate)
        assertEquals(FirstBoardReclaimValidationPolicy.Category.OUTFLOW, verdict.category)
    }

    @Test
    fun missingFundFlowDoesNotFilter() {
        // 触发时已过资金闸，展示层缺资金数据不反向否定。
        val verdict = evaluate(record = record(), today = "2026-11-25", latestMainNetPct = null)
        assertTrue(verdict.participate)
    }

    @Test
    fun happyPathParticipates() {
        val verdict = evaluate(
            record = record(open = 10.00, low = 9.70, price = 10.10),
            currentPrice = 10.20,
            today = "2026-11-25",
            latestMainNetPct = 1.2,
        )
        assertTrue(verdict.participate)
        assertEquals(null, verdict.category)
    }

    private fun evaluate(
        record: FirstBoardReclaimObservation,
        currentPrice: Double? = record.price,
        today: String,
        latestMainNetPct: Double? = 0.5,
    ): FirstBoardReclaimValidationPolicy.Verdict = FirstBoardReclaimValidationPolicy.evaluate(
        record = record,
        currentPrice = currentPrice,
        today = today,
        tradingDates = emptyList(),
        latestMainNetPct = latestMainNetPct,
    )

    private fun record(
        tradingDay: String = "2026-11-25",
        open: Double = 10.00,
        low: Double = 9.70,
        price: Double = 10.10,
    ): FirstBoardReclaimObservation = FirstBoardReclaimObservation(
        code = "sh600000",
        name = "测试股票",
        tradingDay = tradingDay,
        detectedAt = "$tradingDay 10:30",
        firstBoardDate = "2026-11-24",
        previousClose = 9.90,
        open = open,
        low = low,
        price = price,
    )
}
