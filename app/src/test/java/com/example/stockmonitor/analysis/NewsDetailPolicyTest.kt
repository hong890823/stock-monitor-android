package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.NewsItem
import com.example.stockmonitor.core.NewsTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class NewsDetailPolicyTest {
    @Test
    fun filterSeparatesTracksAndHoldingRelevance() {
        val investment = headline("宏观政策", NewsTrack.INVESTMENT, score = HeadlineScore(focus = 120))
        val technology = headline("AI 芯片订单", NewsTrack.TECHNOLOGY, score = HeadlineScore(focus = 80))
        val generalTech = headline("全球科技产品", NewsTrack.TECHNOLOGY)

        assertEquals(listOf(investment), NewsDetailPolicy.filter(listOf(investment, technology, generalTech), NewsDetailFilter.INVESTMENT))
        assertEquals(listOf(technology, generalTech), NewsDetailPolicy.filter(listOf(investment, technology, generalTech), NewsDetailFilter.TECHNOLOGY))
        assertEquals(listOf(investment, technology), NewsDetailPolicy.filter(listOf(investment, technology, generalTech), NewsDetailFilter.HOLDING_RELATED))
    }

    @Test
    fun sortSupportsCompositeScoreAndFreshness() {
        val olderHighScore = headline(
            "AI 芯片获得量产订单",
            NewsTrack.TECHNOLOGY,
            publishedAtMs = 1_000L,
            score = HeadlineScore(event = 55, trust = 20),
        )
        val newerLowScore = headline(
            "科技公司发布产品",
            NewsTrack.TECHNOLOGY,
            publishedAtMs = 2_000L,
            score = HeadlineScore(event = 25),
        )

        assertEquals(
            listOf(olderHighScore, newerLowScore),
            NewsDetailPolicy.sort(listOf(newerLowScore, olderHighScore), NewsDetailSort.SCORE),
        )
        assertEquals(
            listOf(newerLowScore, olderHighScore),
            NewsDetailPolicy.sort(listOf(olderHighScore, newerLowScore), NewsDetailSort.LATEST),
        )
    }

    @Test
    fun summaryCountsBothTracksAndHoldingRelevance() {
        val items = listOf(
            headline("投资", NewsTrack.INVESTMENT, score = HeadlineScore(focus = 120)),
            headline("科技", NewsTrack.TECHNOLOGY, score = HeadlineScore(focus = 80)),
            headline("科技二", NewsTrack.TECHNOLOGY),
        )

        assertEquals(NewsDetailSummary(total = 3, investment = 1, technology = 2, holdingRelated = 2), NewsDetailPolicy.summary(items))
    }

    private fun headline(
        title: String,
        track: NewsTrack,
        publishedAtMs: Long = 1_000L,
        score: HeadlineScore = HeadlineScore(),
    ) = SelectedHeadline(
        item = NewsItem(
            title = title,
            publishedAtMs = publishedAtMs,
            source = "测试来源",
            track = track,
        ),
        category = NewsCategory.MARKET_GENERAL,
        track = track,
        score = score,
    )
}
