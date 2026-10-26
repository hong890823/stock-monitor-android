package com.example.stockmonitor.core

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketNewsClientTest {
    private val nowMs = timestamp("2026-10-22 15:00:00")

    @Test
    fun mergeKeepsPartialItemsAndIndependentSourceFailures() {
        val infoq = NewsSourceProfile("infoq_cn", "InfoQ 中文", NewsTrack.TECHNOLOGY, 82, "https://www.infoq.cn/feed/")
        val techmeme = NewsSourceProfile("techmeme", "Techmeme", NewsTrack.TECHNOLOGY, 88, "https://www.techmeme.com/feed.xml")
        val merged = MarketNewsClient.mergeAttempts(
            listOf(
                NewsSourceAttempt(infoq, Result.success(listOf(item("AI 工程发布", "InfoQ 中文", "infoq_cn", NewsTrack.TECHNOLOGY)))),
                NewsSourceAttempt(techmeme, Result.failure(Http.HttpError("HTTP 503"))),
            ),
            nowMs,
        )

        assertEquals(listOf("AI 工程发布"), merged.items.map { it.title })
        assertEquals(listOf("techmeme"), merged.failures.map { it.sourceId })
        assertFalse(merged.allSourcesFailed)
    }

    @Test
    fun mergeDropsItemsFromOtherShanghaiCalendarDays() {
        val profile = NewsSourceProfile("infoq_cn", "InfoQ 中文", NewsTrack.TECHNOLOGY, 82, "https://www.infoq.cn/feed/")
        val merged = MarketNewsClient.mergeAttempts(
            listOf(
                NewsSourceAttempt(
                    profile,
                    Result.success(
                        listOf(
                            item("今日", "InfoQ 中文", "infoq_cn", NewsTrack.TECHNOLOGY, timestamp("2026-10-22 00:10:00")),
                            item("昨日", "InfoQ 中文", "infoq_cn", NewsTrack.TECHNOLOGY, timestamp("2026-10-21 23:50:00")),
                        ),
                    ),
                ),
            ),
            nowMs,
        )

        assertEquals(listOf("今日"), merged.items.map { it.title })
    }

    @Test
    fun allSourceFailureIsExplicit() {
        val profile = NewsSourceProfile("infoq_cn", "InfoQ 中文", NewsTrack.TECHNOLOGY, 82, "https://www.infoq.cn/feed/")
        val merged = MarketNewsClient.mergeAttempts(
            listOf(NewsSourceAttempt(profile, Result.failure(Http.HttpError("network")))),
            nowMs,
        )

        assertTrue(merged.items.isEmpty())
        assertTrue(merged.allSourcesFailed)
        assertEquals(1, merged.failures.size)
    }

    @Test
    fun mergeSupportsMultiDayWindowForAiNewsQueries() {
        val profile = NewsSourceProfile("infoq_cn", "InfoQ 中文", NewsTrack.TECHNOLOGY, 82, "https://www.infoq.cn/feed/")
        val merged = MarketNewsClient.mergeAttempts(
            listOf(
                NewsSourceAttempt(
                    profile,
                    Result.success(
                        listOf(
                            item("今天", "InfoQ 中文", "infoq_cn", NewsTrack.TECHNOLOGY),
                            item(
                                "两天前",
                                "InfoQ 中文",
                                "infoq_cn",
                                NewsTrack.TECHNOLOGY,
                                timestamp("2026-10-20 15:00:00"),
                            ),
                            item(
                                "四天前",
                                "InfoQ 中文",
                                "infoq_cn",
                                NewsTrack.TECHNOLOGY,
                                timestamp("2026-10-18 15:00:00"),
                            ),
                        ),
                    ),
                ),
            ),
            nowMs,
            maxAgeDays = 3,
        )

        assertEquals(listOf("今天", "两天前"), merged.items.map { it.title })
    }

    @Test
    fun sourceProfilesCoverBothLanes() {
        assertEquals(
            setOf(NewsTrack.INVESTMENT, NewsTrack.TECHNOLOGY),
            MarketNewsClient.sourceProfiles.map { it.track }.toSet(),
        )
        assertTrue(MarketNewsClient.sourceProfiles.any { it.id == "jiqizhixin" })
        assertTrue(MarketNewsClient.sourceProfiles.any { it.id == "kr36" })
    }

    private fun item(
        title: String,
        source: String,
        sourceId: String,
        track: NewsTrack,
        publishedAtMs: Long = nowMs,
    ) = NewsItem(
        title = title,
        publishedAtMs = publishedAtMs,
        source = source,
        sourceId = sourceId,
        track = track,
        trustScore = 80,
    )

    private fun timestamp(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .parse(value)!!.time
}
