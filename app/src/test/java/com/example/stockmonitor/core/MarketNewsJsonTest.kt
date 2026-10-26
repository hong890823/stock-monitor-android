package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.HeadlineScore
import com.example.stockmonitor.analysis.NewsCategory
import com.example.stockmonitor.analysis.SelectedHeadline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketNewsJsonTest {
    @Test
    fun roundTripPreservesEnrichedHeadlineFields() {
        val original = SelectedHeadline(
            item = NewsItem(
                title = "AI 芯片量产订单落地",
                publishedAtMs = 1_761_120_000_000L,
                source = "InfoQ 中文",
                url = "https://www.infoq.cn/article/ai-order",
                rawCategory = "半导体",
                sourceId = "infoq_cn",
                track = NewsTrack.TECHNOLOGY,
                trustScore = 82,
            ),
            category = NewsCategory.SECTOR_INDUSTRY,
            track = NewsTrack.TECHNOLOGY,
            score = HeadlineScore(focus = 120, event = 55, trust = 20, corroboration = 10, freshness = 18),
            supportingSources = listOf("InfoQ 中文", "Techmeme"),
        )

        val decoded = MarketNewsJson.decode(
            MarketNewsJson.encode(listOf(original), updatedAtMs = 123L, lastAttemptAtMs = 456L),
        )!!

        assertEquals(123L, decoded.updatedAtMs)
        assertEquals(456L, decoded.lastAttemptAtMs)
        assertEquals(original, decoded.items.single())
    }

    @Test
    fun legacyCacheUsesInvestmentDefaultsAndNullableUrl() {
        val decoded = MarketNewsJson.decode(
            """
            {"updated_at_ms":10,"last_attempt_at_ms":20,"items":[
              {"title":"旧财经快讯","published_at_ms":30,"source":"东方财富","category":"MACRO_POLICY"}
            ]}
            """.trimIndent(),
        )!!
        val selected = decoded.items.single()

        assertEquals(NewsTrack.INVESTMENT, selected.track)
        assertEquals(NewsTrack.INVESTMENT, selected.item.track)
        assertEquals("", selected.item.sourceId)
        assertEquals(listOf("东方财富"), selected.supportingSources)
        assertEquals(0, selected.score.total)
        assertNull(selected.item.url)
    }

    @Test
    fun invalidJsonReturnsNull() {
        assertNull(MarketNewsJson.decode("{broken"))
    }
}
