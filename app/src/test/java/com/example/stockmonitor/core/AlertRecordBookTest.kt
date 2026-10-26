package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRecordBookTest {
    private fun record(
        id: String,
        createdAtMs: Long,
        notificationId: Int = 6001,
    ) = AlertRecord(
        id = id,
        notificationId = notificationId,
        kind = "market_risk",
        level = "DANGER",
        triggerType = "confirmed",
        title = "🚨 大盘风险预警",
        body = "创业板指 过去 5 分钟 -0.56%，成交额约为近 20 日同期中位数的 1.60 倍，放量下跌。",
        createdAtMs = createdAtMs,
    )

    @Test
    fun recordsRoundTripWithStructuredTriggerDetails() {
        val source = listOf(record("alert-1", 1_000L, notificationId = 6001))

        val decoded = AlertRecordBook.decode(AlertRecordBook.encode(source))

        assertEquals(source, decoded)
        assertEquals("market_risk", decoded.single().kind)
        assertEquals("DANGER", decoded.single().level)
        assertEquals("confirmed", decoded.single().triggerType)
    }

    @Test
    fun appendKeepsOnlyThirtyDaysAndMaximumTwoHundredRecords() {
        val nowMs = 30L * 24 * 60 * 60 * 1000
        val old = record("old", createdAtMs = 0L)
        val existing = List(200) { index -> record("existing-$index", createdAtMs = nowMs - index) }

        val result = AlertRecordBook.append(existing + old, record("new", createdAtMs = nowMs), nowMs)

        assertEquals(200, result.size)
        assertTrue(result.none { it.id == "old" })
        assertEquals("new", result.last().id)
    }

    @Test
    fun pruneNormalizesHistoryEveryTimeItIsRead() {
        val nowMs = 30L * 24 * 60 * 60 * 1000
        val old = record("old", createdAtMs = 0L)
        val oversized = List(201) { index -> record("item-$index", createdAtMs = nowMs - index) }

        val result = AlertRecordBook.prune(oversized + old, nowMs)

        assertEquals(200, result.size)
        assertTrue(result.none { it.id == "old" })
        assertEquals("item-199", result.first().id)
        assertEquals("item-0", result.last().id)
    }

    @Test
    fun pruneKeepsLatestRecordsEvenWhenStoredOrderIsOutOfSequence() {
        val nowMs = 10_000L
        val records = listOf(
            record("middle", 2_000L),
            record("newest", 9_000L),
            record("oldest", 1_000L),
        )

        val result = AlertRecordBook.prune(records, nowMs)

        assertEquals(listOf("oldest", "middle", "newest"), result.map { it.id })
    }

    @Test
    fun completeIntentFallbackRepairsPartialPersistedRecord() {
        val fallback = record("alert-1", 1_000L)
        val partial = fallback.copy(body = "")

        assertEquals(fallback, AlertRecordBook.replayRecord(partial, fallback))
        assertEquals(fallback, AlertRecordBook.replayRecord(null, fallback))
    }

    @Test
    fun upsertDurablyRepairsPartialRecordWithSameId() {
        val fallback = record("alert-1", 1_000L)
        val partial = fallback.copy(body = "")

        val result = AlertRecordBook.upsert(listOf(partial), fallback, fallback.createdAtMs)

        assertEquals(listOf(fallback), result)
    }

    @Test
    fun detailTextExposesTimeTypeAndFullBodyForPostClickReview() {
        val detail = AlertRecordBook.detailText(record("alert-1", 1_792_733_489_191L))

        assertTrue(detail.contains("2026-10-23 13:31:29"))
        assertTrue(detail.contains("类型：大盘风险"))
        assertTrue(detail.contains("级别：DANGER"))
        assertTrue(detail.contains("触发：confirmed"))
        assertTrue(detail.contains("创业板指 过去 5 分钟 -0.56%"))
    }

    @Test
    fun findsRecordByIdForNotificationTapReplay() {
        val records = listOf(record("first", 1_000L), record("second", 2_000L))

        assertEquals(records[1], AlertRecordBook.find(records, "second"))
        assertEquals(null, AlertRecordBook.find(records, "missing"))
    }

    @Test
    fun exposesStableTypeLabelAndTimeForHistoryRows() {
        val source = record("alert-1", 1_792_733_489_191L)

        assertEquals("大盘风险", AlertRecordBook.kindLabel(source.kind))
        assertEquals("2026-10-23 13:31:29", AlertRecordBook.formatTime(source.createdAtMs))
    }
}
