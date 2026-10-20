package com.example.stockmonitor

import com.example.stockmonitor.analysis.EvidenceCard
import com.example.stockmonitor.analysis.StrongContinuationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceCardTest {
    @Test
    fun unavailableAndStaleEvidenceRemainExplicit() {
        val lines = EvidenceCard.format(
            listOf(
                EvidenceCard("北向净流向", null, available = false),
                EvidenceCard("板块宽度", "68%", fresh = false),
            ),
        )
        assertEquals("北向净流向：暂缺", lines[0])
        assertTrue(lines[1].contains("板块宽度：68%（已过期）"))
    }

    @Test
    fun eventOutcomeStoresObservedPriceWithoutClaimingStrategySuccess() {
        val event = StrongContinuationEvent(
            date = "2026-10-14", code = "sh600522", name = "中天科技",
            triggeredAt = "2026-10-14 10:00", triggerPrice = 30.0, currentPrice = 31.5,
            predictedTurnoverYi = 22_000.0, marketChangePct = 0.8, sectorChangePct = 1.2,
        ).withObservation(31.5, "2026-10-15 15:00")
        assertEquals(5.0, event.currentChangePct, 0.001)
        assertEquals("观察中", event.outcomeStatus)
        assertTrue(event.reportLine().contains("观察中"))
        assertTrue(!event.reportLine().contains("策略有效"))
    }
}
