package com.example.stockmonitor.core

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TechNewsFeedParserTest {
    private val profile = NewsSourceProfile(
        id = "infoq_cn",
        label = "InfoQ 中文",
        track = NewsTrack.TECHNOLOGY,
        trustScore = 82,
        url = "https://www.infoq.cn/feed/",
    )

    @Test
    fun rssParsesTitleLinkPublicationAndProfileAttribution() {
        val items = TechNewsFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item>
                <title>AI 工程平台完成升级</title>
                <link>https://www.infoq.cn/article/ai-platform</link>
                <pubDate>Thu, 22 Oct 2026 10:25:00 +0800</pubDate>
              </item>
            </channel></rss>
            """.trimIndent(),
            profile,
        )

        assertEquals(1, items.size)
        assertEquals("AI 工程平台完成升级", items[0].title)
        assertEquals("https://www.infoq.cn/article/ai-platform", items[0].url)
        assertEquals("InfoQ 中文", items[0].source)
        assertEquals("infoq_cn", items[0].sourceId)
        assertEquals(NewsTrack.TECHNOLOGY, items[0].track)
        assertEquals(timestamp("2026-10-22 10:25:00"), items[0].publishedAtMs)
    }

    @Test
    fun atomParsesEntryAndAlternateLink() {
        val items = TechNewsFeedParser.parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Chip startup announces new accelerator</title>
                <link rel="alternate" href="https://example.com/chip-startup"/>
                <published>2026-10-22T02:25:00Z</published>
              </entry>
            </feed>
            """.trimIndent(),
            profile.copy(id = "techmeme", label = "Techmeme", url = "https://www.techmeme.com/feed.xml"),
        )

        assertEquals("Chip startup announces new accelerator", items.single().title)
        assertEquals("https://example.com/chip-startup", items.single().url)
        assertEquals("Techmeme", items.single().source)
        assertEquals(timestamp("2026-10-22 10:25:00"), items.single().publishedAtMs)
    }

    @Test
    fun malformedEntriesAreIgnored() {
        val items = TechNewsFeedParser.parse(
            """
            <rss><channel>
              <item><title></title><pubDate>Thu, 22 Oct 2026 10:25:00 +0800</pubDate></item>
              <item><title>Valid</title></item>
            </channel></rss>
            """.trimIndent(),
            profile,
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun sourceItemTagOverridesProfileLabelWithoutLosingSourceId() {
        val items = TechNewsFeedParser.parse(
            """
            <rss><channel>
              <item>
                <title>机器人订单进入量产</title>
                <source>机器之心</source>
                <pubDate>Thu, 22 Oct 2026 10:25:00 +0800</pubDate>
              </item>
            </channel></rss>
            """.trimIndent(),
            profile.copy(id = "jiqizhixin", label = "机器之心", url = "https://www.jiqizhixin.com/rss"),
        )

        assertEquals("机器之心", items.single().source)
        assertEquals("jiqizhixin", items.single().sourceId)
    }

    @Test
    fun missingUrlStaysNull() {
        val items = TechNewsFeedParser.parse(
            """
            <rss><channel><item>
              <title>No link</title>
              <pubDate>Thu, 22 Oct 2026 10:25:00 +0800</pubDate>
            </item></channel></rss>
            """.trimIndent(),
            profile,
        )

        assertNull(items.single().url)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidXmlFailsInsteadOfLookingLikeEmptyFeed() {
        TechNewsFeedParser.parse("<rss><channel><item>", profile)
    }

    private fun timestamp(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .parse(value)!!.time
}
