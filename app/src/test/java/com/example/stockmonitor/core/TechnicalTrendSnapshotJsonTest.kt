package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.SwingTrend
import com.example.stockmonitor.analysis.TechnicalTrendSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class TechnicalTrendSnapshotJsonTest {

    @Test
    fun roundTripKeepsScoresTrendsAndUnifiedAdvice() {
        val snapshots = mapOf(
            "sh600000" to TechnicalTrendSnapshot(
                code = "sh600000",
                name = "测试股票",
                asOf = "2026-10-26",
                technicalScore = 82,
                trendPoints = 30,
                structurePoints = 22,
                momentumPoints = 17,
                riskPoints = 13,
                weeklyTrend = SwingTrend.UP,
                monthlyTrend = SwingTrend.REPAIR,
                trendWeak = false,
                allowsAddPosition = true,
                primaryAdvice = "可关注分批参与/补仓",
                adviceReason = "趋势与量价配合",
            ),
        )

        assertEquals(snapshots, TechnicalTrendSnapshotJson.decode(TechnicalTrendSnapshotJson.encode(snapshots)))
    }
}
