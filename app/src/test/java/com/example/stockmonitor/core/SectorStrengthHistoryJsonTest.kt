package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.BoardKind
import com.example.stockmonitor.analysis.SectorStrengthDay
import com.example.stockmonitor.analysis.SectorStrengthPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class SectorStrengthHistoryJsonTest {

    @Test
    fun roundTripKeepsDatesBoardKindsAndOptionalFlow() {
        val history = listOf(
            SectorStrengthDay(
                date = "2026-10-26",
                points = listOf(
                    SectorStrengthPoint(
                        kind = BoardKind.INDUSTRY,
                        date = "2026-10-26",
                        name = "机器人",
                        code = "BK0438",
                        changePct = 2.5,
                        changeRankPct = 0.08,
                        turnoverRankPct = 0.12,
                        mainNetRatioPct = 1.5,
                    ),
                    SectorStrengthPoint(
                        kind = BoardKind.CONCEPT,
                        date = "2026-10-26",
                        name = "人工智能",
                        code = null,
                        changePct = 1.1,
                        changeRankPct = 0.20,
                        turnoverRankPct = 0.30,
                        mainNetRatioPct = null,
                    ),
                ),
            ),
        )

        val decoded = SectorStrengthHistoryJson.decode(
            SectorStrengthHistoryJson.encode(history),
        )

        assertEquals(history, decoded)
    }
}
