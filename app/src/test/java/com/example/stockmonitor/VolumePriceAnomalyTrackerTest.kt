package com.example.stockmonitor

import com.example.stockmonitor.analysis.VolumePriceAnomalyTracker
import com.example.stockmonitor.analysis.VolumePriceAnomalyTracker.Level
import com.example.stockmonitor.analysis.IntradayFundFlowContext
import com.example.stockmonitor.analysis.IntradayFundFlowTracker
import com.example.stockmonitor.analysis.IntradayFundFlowTone
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumePriceAnomalyTrackerTest {
    private val day = "2026-10-22"
    private val open = LocalDateTime.of(LocalDate.parse(day), LocalTime.of(9, 30))

    private fun at(minutes: Long): LocalDateTime = open.plusMinutes(minutes)

    private fun sample(
        price: Double,
        cumulativeVolume: Double,
        cumulativeTurnover: Double,
        timestamp: LocalDateTime,
        high: Double = price,
        code: String = "sh600000",
        name: String = "测试股票",
    ) = VolumePriceAnomalyTracker.Sample(
        code = code,
        name = name,
        price = price,
        cumulativeVolume = cumulativeVolume,
        cumulativeTurnover = cumulativeTurnover,
        sessionHigh = high,
        timestamp = timestamp,
    )

    private fun quietBaseline(): List<VolumePriceAnomalyTracker.Sample> {
        val price = 14.00
        return (0L..35L step 5L).map { minute ->
            val cumulativeVolume = minute * 40_000.0
            sample(
                price = price,
                cumulativeVolume = cumulativeVolume,
                cumulativeTurnover = cumulativeVolume * price,
                timestamp = at(minute),
                high = price,
            )
        }
    }

    private fun surge(
        multiplier: Double,
        price: Double = 14.05,
        high: Double = price,
        timestamp: LocalDateTime = at(40),
        code: String = "sh600000",
        name: String = "测试股票",
    ): VolumePriceAnomalyTracker.Sample {
        val cumulativeVolume = 1_400_000.0 + 200_000.0 * multiplier
        return sample(
            price = price,
            cumulativeVolume = cumulativeVolume,
            cumulativeTurnover = 19_600_000.0 + 200_000.0 * multiplier * price,
            timestamp = timestamp,
            high = high,
            code = code,
            name = name,
        )
    }

    private fun supportiveFlow() = IntradayFundFlowContext(
        dayMainNetYuan = 20_000_000.0,
        dayMainNetPct = 6.0,
        recent5MainNetYuan = 8_000_000.0,
        recent5MainNetPct = 8.0,
        recent15MainNetYuan = 12_000_000.0,
        recent15MainNetPct = 5.0,
    )

    private fun divergentFlow() = supportiveFlow().copy(
        dayMainNetYuan = -10_000_000.0,
        dayMainNetPct = -5.0,
        recent5MainNetYuan = -6_000_000.0,
        recent5MainNetPct = -7.0,
        recent15MainNetYuan = -9_000_000.0,
        recent15MainNetPct = -4.0,
    )

    @Test
    fun threeTimesVolumeBreakoutEmitsNeutralAnomaly() {
        val tracker = VolumePriceAnomalyTracker()
        quietBaseline().forEach { assertNull(tracker.update(it, day)) }

        val alert = tracker.update(surge(multiplier = 3.0), day)

        assertNotNull(alert)
        assertEquals(Level.ANOMALY, alert?.level)
        assertEquals(3.0, alert?.volumeRatio ?: 0.0, 0.001)
        assertTrue(alert?.title?.contains("量价异动") == true)
        assertFalse(alert?.strong == true)
        assertTrue(alert?.body?.contains("不构成买卖建议") == true)
    }

    @Test
    fun strongerThresholdsUpgradeToIgnition() {
        val tracker = VolumePriceAnomalyTracker()
        quietBaseline().forEach { assertNull(tracker.update(it, day)) }

        val alert = tracker.update(
            surge(multiplier = 4.0, price = 14.07),
            day,
            supportiveFlow(),
        )

        assertNotNull(alert)
        assertEquals(Level.IGNITION, alert?.level)
        assertTrue(alert?.strong == true)
        assertTrue(alert?.title?.contains("盘中点火") == true)
        assertTrue(alert?.body?.contains("不代表趋势启动") == true)
        assertFalse(alert?.body?.contains("建议买入") == true)
        assertTrue(alert?.body?.contains("资金配合") == true)
    }

    @Test
    fun volumePriceIgnitionNeedsRecentFundFlowConfirmation() {
        val tracker = VolumePriceAnomalyTracker()
        quietBaseline().forEach { assertNull(tracker.update(it, day)) }

        val divergent = tracker.update(
            surge(multiplier = 4.0, price = 14.07),
            day,
            divergentFlow(),
        )
        val missing = VolumePriceAnomalyTracker().let { fresh ->
            quietBaseline().forEach { assertNull(fresh.update(it, day)) }
            fresh.update(surge(multiplier = 4.0, price = 14.07, timestamp = at(41)), day)
        }

        assertEquals(Level.ANOMALY, divergent?.level)
        assertTrue(divergent?.body?.contains("资金背离") == true)
        assertEquals(Level.ANOMALY, missing?.level)
        assertTrue(missing?.body?.contains("资金数据不足") == true)
    }

    @Test
    fun recentFundFlowCanTurnStrongEvenWhenWholeDayWasOutflow() {
        val tracker = VolumePriceAnomalyTracker()
        quietBaseline().forEach { assertNull(tracker.update(it, day)) }
        val turningStrong = supportiveFlow().copy(
            dayMainNetYuan = -12_000_000.0,
            dayMainNetPct = -6.0,
            recent5MainNetYuan = 9_000_000.0,
            recent5MainNetPct = 9.0,
            recent15MainNetYuan = 4_000_000.0,
            recent15MainNetPct = 2.0,
        )

        val alert = tracker.update(
            surge(multiplier = 4.0, price = 14.07),
            day,
            turningStrong,
        )

        assertEquals(Level.IGNITION, alert?.level)
        assertTrue(alert?.body?.contains("资金转强") == true)
    }

    @Test
    fun volumePriceAndBreakoutGatesMustAllPass() {
        fun evaluate(multiplier: Double, price: Double, turnoverScale: Double = 1.0): VolumePriceAnomalyTracker.Alert? {
            val tracker = VolumePriceAnomalyTracker()
            quietBaseline().forEach { assertNull(tracker.update(it, day)) }
            val cumulativeVolume = 1_400_000.0 + 200_000.0 * multiplier
            return tracker.update(
                sample(
                    price = price,
                    cumulativeVolume = cumulativeVolume,
                    cumulativeTurnover = 19_600_000.0 + 200_000.0 * multiplier * price * turnoverScale,
                    timestamp = at(40),
                ),
                day,
            )
        }

        assertNull(evaluate(multiplier = 2.9, price = 14.05))
        assertNull(evaluate(multiplier = 3.0, price = 14.01))
        assertNull(evaluate(multiplier = 3.0, price = 14.05, turnoverScale = 0.1))
        assertNotNull(evaluate(multiplier = 3.0, price = 14.05))
    }

    @Test
    fun oneTickBreakoutNeverQualifiesEvenDuringHeavyVolume() {
        val tracker = VolumePriceAnomalyTracker()
        quietBaseline().forEach { assertNull(tracker.update(it, day)) }

        assertNull(tracker.update(surge(multiplier = 20.0, price = 14.01), day))
    }

    @Test
    fun staleOutOfOrderAndRegressingCumulativeSamplesAreIgnored() {
        val tracker = VolumePriceAnomalyTracker()
        quietBaseline().forEach { assertNull(tracker.update(it, day)) }
        assertNull(tracker.update(surge(multiplier = 1.0), day))

        assertNull(
            tracker.update(
                surge(multiplier = 4.0, timestamp = at(39)),
                day,
            ),
        )
        assertNull(
            tracker.update(
                sample(
                    price = 14.05,
                    cumulativeVolume = 1_300_000.0,
                    cumulativeTurnover = 18_200_000.0,
                    timestamp = at(41),
                ),
                day,
            ),
        )
        assertNull(
            tracker.update(
                sample(
                    price = 14.05,
                    cumulativeVolume = 1_600_000.0,
                    cumulativeTurnover = 22_400_000.0,
                    timestamp = at(41),
                ),
                day,
            ),
        )
    }

    @Test
    fun detectionStopsAtClosingAuctionAndUsesContinuousTradingClock() {
        val tail = LocalDateTime.of(LocalDate.parse(day), LocalTime.of(14, 56, 59))
        val auction = LocalDateTime.of(LocalDate.parse(day), LocalTime.of(14, 57))
        val afternoon = LocalDateTime.of(LocalDate.parse(day), LocalTime.of(13, 0))

        assertTrue(VolumePriceAnomalyTracker.isDetectionTime(tail))
        assertFalse(VolumePriceAnomalyTracker.isDetectionTime(auction))
        assertEquals(
            VolumePriceAnomalyTracker.sessionElapsedSeconds(LocalDateTime.of(LocalDate.parse(day), LocalTime.of(11, 30))),
            VolumePriceAnomalyTracker.sessionElapsedSeconds(afternoon),
        )
    }

    @Test
    fun lateSessionAlertUsesTailWording() {
        val tracker = VolumePriceAnomalyTracker()
        val lateOpen = LocalDateTime.of(LocalDate.parse(day), LocalTime.of(14, 15))
        val baseline = (0L..35L step 5L).map { minute ->
            val cumulativeVolume = minute * 40_000.0
            sample(
                price = 14.00,
                cumulativeVolume = cumulativeVolume,
                cumulativeTurnover = cumulativeVolume * 14.00,
                timestamp = lateOpen.plusMinutes(minute),
            )
        }
        baseline.forEach { assertNull(tracker.update(it, day)) }

        val alert = tracker.update(
            surge(multiplier = 3.0, timestamp = lateOpen.plusMinutes(40)),
            day,
        )

        assertNotNull(alert)
        assertTrue(alert?.tail == true)
        assertTrue(alert?.title?.contains("尾盘") == true)
    }

    @Test
    fun oncePerStockPerDayAndResetOnNewDay() {
        val tracker = VolumePriceAnomalyTracker()
        quietBaseline().forEach { assertNull(tracker.update(it, day)) }
        assertNotNull(tracker.update(surge(multiplier = 3.0), day))

        assertNull(tracker.update(surge(multiplier = 20.0, timestamp = at(45)), day))
        assertNull(tracker.update(surge(multiplier = 20.0, timestamp = at(50)), day))

        val nextDay = "2026-10-23"
        val nextOpen = LocalDateTime.of(LocalDate.parse(nextDay), LocalTime.of(9, 30))
        quietBaseline().map {
            it.copy(
                timestamp = nextOpen.plusMinutes(DurationMinutes.fromOpen(it.timestamp, open)),
            )
        }.forEach { assertNull(tracker.update(it, nextDay)) }
        assertNotNull(
            tracker.update(
                surge(multiplier = 3.0, timestamp = nextOpen.plusMinutes(40)),
                nextDay,
            ),
        )
    }

    @Test
    fun stocksAreTrackedIndependently() {
        val tracker = VolumePriceAnomalyTracker()
        quietBaseline().forEach { assertNull(tracker.update(it, day)) }
        val alert = tracker.update(surge(multiplier = 3.0), day)
        assertNotNull(alert)

        assertNull(
            tracker.update(
                surge(multiplier = 20.0, code = "sz000001", name = "平安银行", timestamp = at(41)),
                day,
            ),
        )
    }

    @Test
    fun fundFlowTrackerBuildsFiveAndFifteenMinuteWindows() {
        val tracker = IntradayFundFlowTracker()
        val openMs = open.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val contexts = listOf(0L, 5L, 10L, 15L).map { minute ->
            tracker.update(
                code = "sh600000",
                day = day,
                timestampMs = openMs + minute * 60_000L,
                cumulativeMainNetYuan = minute * 1_000_000.0,
                cumulativeAmountYuan = 20_000_000.0 + minute * 10_000_000.0,
                dayMainNetPct = 5.0,
            )
        }

        assertEquals(5_000_000.0, contexts.last()?.recent5MainNetYuan ?: 0.0, 0.001)
        assertEquals(15_000_000.0, contexts.last()?.recent15MainNetYuan ?: 0.0, 0.001)
        assertEquals(IntradayFundFlowTone.SUPPORTIVE, contexts.last()?.let {
            com.example.stockmonitor.analysis.IntradayFundFlowPolicy.assess(it)
        })
    }

    @Test
    fun fundFlowTrackerBuildsFifteenMinuteWindowWithJitteredTimestamps() {
        // 生产时间戳来自东财 asOfMs（毫秒抖动、约 60s 刷新），不会恰好落在整分钟边界。
        // 旧实现先按 cutoff 修剪再取「cutoff 之前（含）」的基线，交集为空 → recent15 恒为 null。
        val tracker = IntradayFundFlowTracker()
        val openMs = open.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val jitteredMinutes = listOf(0L, 5L * 60_000L + 13_000L, 10L * 60_000L + 31_000L, 15L * 60_000L + 47_000L)
        val contexts = jitteredMinutes.map { offsetMs ->
            tracker.update(
                code = "sh600000",
                day = day,
                timestampMs = openMs + offsetMs,
                cumulativeMainNetYuan = (offsetMs / 60_000L) * 1_000_000.0,
                cumulativeAmountYuan = 20_000_000.0 + (offsetMs / 60_000L) * 10_000_000.0,
                dayMainNetPct = 5.0,
            )
        }
        val last = contexts.last()
        assertNotNull("recent15 窗口不能因时间戳抖动失效", last?.recent15MainNetYuan)
        assertEquals(15_000_000.0, last?.recent15MainNetYuan ?: 0.0, 0.001)
    }

    private object DurationMinutes {
        fun fromOpen(value: LocalDateTime, origin: LocalDateTime): Long =
            java.time.Duration.between(origin, value).toMinutes()
    }
}
