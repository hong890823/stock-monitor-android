package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitRankingTest {

    @Test
    fun completeStrongSealRanksAboveCompleteWeakSeal() {
        val strong = metrics(
            id = "strong",
            sealToTurnoverPct = 20.0,
            sealToFloatPct = 2.0,
            openCount = 0.0,
            firstSealSeconds = 9 * 3600 + 31 * 60,
            lastSealSeconds = 15 * 3600,
            limitStreak = 4.0,
            mainNetPct = 8.0,
        )
        val weak = metrics(
            id = "weak",
            sealToTurnoverPct = 2.0,
            sealToFloatPct = 0.2,
            openCount = 3.0,
            firstSealSeconds = 10 * 3600 + 30 * 60,
            lastSealSeconds = 13 * 3600,
            limitStreak = 1.0,
            mainNetPct = -8.0,
        )

        val ranked = LimitRanking.rank(listOf(weak, strong))

        assertEquals("strong", ranked.first().id)
        assertEquals(100, ranked.first().score)
        assertEquals(0, ranked.last().score)
        assertEquals(100, ranked.first().coveragePct)
    }

    @Test
    fun severelyIncompleteDataRanksAfterReliableScores() {
        val reliableWeak = metrics(
            id = "reliable-weak",
            sealToTurnoverPct = 2.0,
            sealToFloatPct = 0.2,
            openCount = 3.0,
            firstSealSeconds = 10 * 3600 + 30 * 60,
            lastSealSeconds = 13 * 3600,
            limitStreak = 1.0,
            mainNetPct = -8.0,
        )
        val sparseStrong = metrics(
            id = "sparse-strong",
            openCount = 0.0,
        )

        val ranked = LimitRanking.rank(listOf(sparseStrong, reliableWeak))

        assertEquals("reliable-weak", ranked.first().id)
        assertEquals("sparse-strong", ranked.last().id)
        assertTrue(ranked.last().score!! > ranked.first().score!!)
        assertTrue(ranked.last().coveragePct < 50)
    }

    private fun metrics(
        id: String,
        sealToTurnoverPct: Double? = null,
        sealToFloatPct: Double? = null,
        openCount: Double? = null,
        firstSealSeconds: Int? = null,
        lastSealSeconds: Int? = null,
        limitStreak: Double? = null,
        mainNetPct: Double? = null,
    ) = LimitQualityMetrics(
        id = id,
        sealToTurnoverPct = sealToTurnoverPct,
        sealToFloatPct = sealToFloatPct,
        openCount = openCount,
        firstSealSeconds = firstSealSeconds,
        lastSealSeconds = lastSealSeconds,
        limitStreak = limitStreak,
        mainNetPct = mainNetPct,
    )
}
