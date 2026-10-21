package com.example.stockmonitor

import com.example.stockmonitor.core.ConceptItem
import com.example.stockmonitor.core.SectorItem
import com.example.stockmonitor.ui.SectorSnapshotPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectorSnapshotPayloadTest {
    @Test
    fun sectorPayloadReportsBreadthAndSourceFreshness() {
        val now = 1_760_000_000_000L
        val payload = SectorSnapshotPayload.build(
            query = "半导体",
            industries = listOf(
                SectorItem("半导体", 1.2, 2, 100.0, "中芯国际", "3.0%"),
                SectorItem("半导体设备", -0.5, 2, 50.0, "北方华创", "-1.0%"),
                SectorItem("半导体材料", 0.0, 2, 30.0, "沪硅产业", "0.0%"),
            ),
            concepts = listOf(
                ConceptItem("半导体概念", "BK1234", 2.0, 80.0, "寒武纪", "5.0%"),
                ConceptItem("芯片概念", "BK5678", -1.0, 40.0, "兆易创新", "-2.0%"),
            ),
            industryUpdatedAtMs = now - 1_000L,
            conceptUpdatedAtMs = now - 2_000L,
            nowMs = now,
        )

        assertEquals(1, payload.getJSONObject("breadth").getJSONObject("industry").getInt("rising"))
        assertEquals(1, payload.getJSONObject("breadth").getJSONObject("industry").getInt("falling"))
        assertEquals(1, payload.getJSONObject("breadth").getJSONObject("industry").getInt("flat"))
        assertEquals(1.0 / 3.0, payload.getJSONObject("breadth").getJSONObject("industry").getDouble("ratio"), 0.0001)
        assertEquals(1, payload.getJSONObject("breadth").getJSONObject("concept").getInt("rising"))
        assertEquals(1, payload.getJSONObject("breadth").getJSONObject("concept").getInt("falling"))
        assertEquals("程序行业板块缓存", payload.getJSONArray("industry_matches").getJSONObject(0).getString("source"))
        assertEquals("程序概念汇总缓存", payload.getJSONArray("concept_matches").getJSONObject(0).getString("source"))
        assertFalse(payload.getJSONObject("freshness").getBoolean("industry_stale"))
    }

    @Test
    fun staleSectorDataIsCalledOutInsteadOfPresentedAsFreshEvidence() {
        val now = 1_760_000_000_000L
        val payload = SectorSnapshotPayload.build(
            query = "机器人",
            industries = emptyList(),
            concepts = emptyList(),
            industryUpdatedAtMs = 0L,
            conceptUpdatedAtMs = now,
            nowMs = now,
        )

        assertTrue(payload.getJSONObject("freshness").getBoolean("industry_stale"))
        assertTrue(payload.getJSONArray("gaps").toString().contains("行业板块数据暂缺"))
        assertTrue(payload.getJSONArray("gaps").toString().contains("没有找到"))
    }
}
