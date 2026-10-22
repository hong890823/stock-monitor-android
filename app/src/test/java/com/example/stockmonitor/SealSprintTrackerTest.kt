package com.example.stockmonitor

import com.example.stockmonitor.analysis.SealSprintTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封板冲刺预警：急拉 + 量能倍增 + 创日内新高的点火形态要立刻报，
 * 慢涨、无量急拉、已封板的都不报。以 2026-10-22 华天科技实测形态校准。
 */
class SealSprintTrackerTest {

    private val day = "2026-10-22"
    private val t0 = 1_000_000_000L

    private fun at(minute: Double): Long = t0 + (minute * 60_000).toLong()

    private fun sample(
        price: Double,
        volume: Double,
        atMs: Long,
        high: Double = price,
        prevClose: Double = 14.25,
        held: Boolean = true,
    ) = SealSprintTracker.Sample(
        code = "sz002185",
        name = "华天科技",
        price = price,
        prevClose = prevClose,
        high = high,
        volume = volume,
        limitPct = 10.0,
        timestamp = atMs,
        held = held,
    )

    /** 死水基线：30 分钟、每 5 分钟一笔，每分钟约 400 手。 */
    private fun quietBaseline(): List<SealSprintTracker.Sample> {
        val samples = mutableListOf<SealSprintTracker.Sample>()
        for (i in 0..6) {
            samples += sample(price = 14.0, volume = 2000.0 * i, atMs = at(i * 5.0))
        }
        return samples
    }

    @Test
    fun suddenVolumeSurgeWithNewHighSprints() {
        val tracker = SealSprintTracker()
        val events = (quietBaseline() + listOf(
            sample(price = 14.0, volume = 12_000.0, atMs = at(30.0)),
            // 2 分钟 +3.0%，量能 4000/分 = 基线的 10 倍，创日内新高。
            sample(price = 14.42, volume = 20_000.0, atMs = at(32.0)),
        )).flatMap { tracker.update(listOf(it), day) }

        assertEquals(1, events.size)
        val event = events.single()
        assertTrue(event.title.contains("急拉冲板"))
        assertTrue(event.body.contains("创日内新高"))
        assertTrue(event.body.contains("做T"))
        assertTrue(event.vibrate)
    }

    @Test
    fun quietDriftOrLightVolumeNeverSprints() {
        fun run(sprint: SealSprintTracker.Sample): List<SealSprintTracker.Event> {
            val tracker = SealSprintTracker()
            return (quietBaseline() + listOf(sprint)).flatMap { tracker.update(listOf(it), day) }
        }

        // 慢涨 +0.7%：涨幅不够。
        assertTrue(run(sample(price = 14.10, volume = 14_000.0, atMs = at(32.0))).isEmpty())
        // 涨幅够但量只有基线 2 倍：不是放量突袭。
        assertTrue(run(sample(price = 14.42, volume = 13_200.0, atMs = at(32.0))).isEmpty())
        // 急拉放量但没创日内新高（上方还压着 14.6）。
        assertTrue(run(sample(price = 14.42, volume = 20_000.0, atMs = at(32.0), high = 14.6)).isEmpty())
    }

    @Test
    fun sealedStockSaysNothingAndDailyCapHolds() {
        val tracker = SealSprintTracker()
        val events = mutableListOf<SealSprintTracker.Event>()
        // 已封板：冲刺交给封板提醒，这里不报。
        events += tracker.update(listOf(sample(price = 15.68, volume = 20_000.0, atMs = at(32.0), high = 15.68)), day)
        // 两枪独立的冲刺，第三枪吃满日限。
        for (i in 0 until 3) {
            val base = 40.0 + i * 15.0
            val shifted = quietBaseline().map { it.copy(timestamp = it.timestamp + (base * 60_000).toLong()) }
            events += (shifted + listOf(
                sample(price = 14.0, volume = 12_000.0, atMs = at(base + 30.0)),
                sample(price = 14.42, volume = 20_000.0, atMs = at(base + 32.0)),
            )).flatMap { tracker.update(listOf(it), day) }
        }

        assertEquals(2, events.size)
    }
}
