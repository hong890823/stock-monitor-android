package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.BoardTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BoardMembershipPolicyTest {
    @Test
    fun ordinaryBoardMemberGetsTagsEvenWhenItIsNotConceptLeader() {
        val result = BoardMembershipPolicy.resolve(
            stocks = listOf(BoardMembershipPolicy.Stock("sh603278", "大业股份")),
            leaderBoardsByName = mapOf("其他领涨股" to listOf("机器人")),
            cachedEntries = emptyMap(),
            nowMs = 1_000L,
            maxFetches = 2,
        ) {
            listOf(BoardTag("机器人", "BK0864", 1))
        }

        assertEquals(listOf("机器人"), result.boardsByCode["sh603278"])
    }

    @Test
    fun freshCacheAvoidsRefetchAndFetchCountIsBounded() {
        val fresh = BoardTagCache.Entry(listOf(BoardTag("工业母机", null, 1)), 500L)
        var fetchCount = 0
        val result = BoardMembershipPolicy.resolve(
            stocks = listOf(
                BoardMembershipPolicy.Stock("sh000001", "缓存股票"),
                BoardMembershipPolicy.Stock("sz000002", "待拉一"),
                BoardMembershipPolicy.Stock("sz000003", "待拉二"),
                BoardMembershipPolicy.Stock("sz000004", "待拉三"),
            ),
            leaderBoardsByName = emptyMap(),
            cachedEntries = mapOf("sh000001" to fresh),
            nowMs = 1_000L,
            maxFetches = 2,
        ) {
            fetchCount++
            listOf(BoardTag("通用设备", null, 1))
        }

        assertEquals(2, fetchCount)
        assertEquals(2, result.refreshedCodes.size)
        assertFalse(result.refreshedCodes.contains("sh000001"))
    }

    @Test
    fun conceptLeaderAndF10TagsAreMergedWithoutDuplicates() {
        val result = BoardMembershipPolicy.resolve(
            stocks = listOf(BoardMembershipPolicy.Stock("sh603278", "大业股份")),
            leaderBoardsByName = mapOf("大业股份" to listOf("机器人", "通用设备")),
            cachedEntries = mapOf(
                "sh603278" to BoardTagCache.Entry(
                    tags = listOf(BoardTag("机器人", "BK0864", 1), BoardTag("专用设备", null, 2)),
                    fetchedAtMs = 500L,
                ),
            ),
            nowMs = 1_000L,
            maxFetches = 2,
        ) { emptyList() }

        assertEquals(
            listOf("机器人", "通用设备", "专用设备"),
            result.boardsByCode["sh603278"],
        )
    }
}
