package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.SwingTrend
import com.example.stockmonitor.analysis.TechnicalTrendSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                price = 12.34,
                support = 11.80,
                resistance = 13.20,
                ma5 = 12.20,
                ma10 = 12.10,
                ma20 = 11.95,
                ma60 = 11.40,
                volumeRatio = 1.52,
                atrPct = 2.3,
            ),
        )

        assertEquals(snapshots, TechnicalTrendSnapshotJson.decode(TechnicalTrendSnapshotJson.encode(snapshots)))
    }

    @Test
    fun oldCacheWithoutPriceFieldsDecodesAsMissing() {
        // 落盘格式是数组；旧版本没有价位字段。
        val legacy = JSONArray()
            .put(
                JSONObject()
                    .put("code", "sh600000")
                    .put("name", "测试股票")
                    .put("as_of", "2026-10-25")
                    .put("technical_score", 45)
                    .put("weekly_trend", "WEAK")
                    .put("monthly_trend", "WEAK")
                    .put("primary_advice", "暂不补仓"),
            )
            .toString()

        val snapshot = TechnicalTrendSnapshotJson.decode(legacy).getValue("sh600000")

        assertEquals(45, snapshot.technicalScore)
        // 旧缓存没有价位字段：0/null 落到「暂缺」语义，等下一次评估覆盖。
        assertEquals(0.0, snapshot.price, 0.0)
        assertEquals(0.0, snapshot.support, 0.0)
        assertNull(snapshot.volumeRatio)
        assertNull(snapshot.atrPct)
        assertTrue(snapshot.primaryAdvice.isNotBlank())
    }
}
