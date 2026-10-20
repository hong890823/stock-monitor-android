package com.example.stockmonitor

import com.example.stockmonitor.analysis.StrongContinuationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrongContinuationEventTest {
    private val sample = StrongContinuationEvent(
        date = "2026-10-13",
        code = "sh600000",
        name = "浦发银行",
        triggeredAt = "2026-10-13 10:30",
        triggerPrice = 10.0,
        currentPrice = 10.5,
        predictedTurnoverYi = 22_000.0,
        marketChangePct = 0.8,
        sectorChangePct = 1.2,
        environmentStatus = "高成交额待确认",
        environmentReasons = listOf("近期高量天数不足", "板块扩散同步"),
    )

    @Test
    fun eventRoundTripsAndRendersCurrentPerformance() {
        val decoded = StrongContinuationEvent.decode(StrongContinuationEvent.encode(listOf(sample)))

        assertEquals(listOf(sample), decoded)
        assertTrue(decoded.single().reportLine().contains("较触发 +5.00%"))
        assertTrue(decoded.single().reportLine().contains("22000 亿"))
        assertTrue(decoded.single().reportLine().contains("高成交额待确认"))
        assertTrue(decoded.single().reportLine().contains("近期高量天数不足"))
    }

    @Test
    fun malformedEventDataFallsBackToEmpty() {
        assertTrue(StrongContinuationEvent.decode("not-json").isEmpty())
    }

    @Test
    fun legacyEventWithoutEnvironmentFieldsKeepsDefaultValues() {
        val legacy = """[{"date":"2026-10-12","code":"sh600000","name":"浦发银行","triggered_at":"2026-10-12 10:30","trigger_price":10.0,"current_price":10.1,"predicted_turnover_yi":18000.0,"market_change_pct":0.2,"sector_change_pct":0.3}]"""

        val decoded = StrongContinuationEvent.decode(legacy)

        assertEquals(1, decoded.size)
        assertEquals("强势延续", decoded.single().environmentStatus)
        assertTrue(decoded.single().environmentReasons.isEmpty())
    }
}
