package com.example.stockmonitor

import com.example.stockmonitor.analysis.TurnoverTracker
import com.example.stockmonitor.core.TurnoverCurve
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TurnoverHistoryTest {

    /** 2026-10-19 是周一；用来给线性外推兜底分支一个确定的已交易分钟数。 */
    private fun morning(minuteOfDay: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(2026, Calendar.OCTOBER, 19, minuteOfDay / 60, minuteOfDay % 60, 0)
            set(Calendar.MILLISECOND, 0)
        }

    /** 真实曲线形状（两市合计，元）：09:45 累计 3349 亿、09:52 累计 4177 亿、全天 15554 亿。 */
    private fun previousDay() = TurnoverCurve(
        "2026-10-16",
        linkedMapOf(585 to 3349e8, 592 to 4177e8, 900 to 15554e8),
    )

    @Test
    fun predictionComparesTodayWithYesterdayAtTheSameClockMinute() {
        // 今日 09:52 采到累计 3921 亿（槽位向下取整是 09:45）。
        val snapshot = TurnoverTracker.buildSnapshot(
            today = 3921e8,
            sampleMinute = 592,
            curve = previousDay(),
            source = TurnoverTracker.Source.CURVE,
            calendar = morning(592),
        )

        assertEquals("09:52", snapshot.sampledAt)
        assertEquals(4177e8, snapshot.previousSameTime!!, 1.0)
        // 3921 / 4177 * 15554 ≈ 14600 亿；若误用 09:45 那点（3349 亿）当分母会算出 18212 亿。
        assertEquals(14600e8, snapshot.predictedFullDay!!, 1e8)
        assertTrue(snapshot.predictedFullDay!! < 16000e8)
    }

    @Test
    fun slotFloorDenominatorWouldHaveInflatedThePrediction() {
        val curve = previousDay()
        val snapshot = TurnoverTracker.buildSnapshot(3921e8, 592, curve, TurnoverTracker.Source.CURVE, morning(592))

        // 把旧算法的错误结果算出来，确认量级正好是用户看到的「1.8 万亿以上」。
        val buggy = 3921e8 / curve.at(585)!! * curve.fullDay!!
        assertTrue("旧口径应明显虚高", buggy / 1e8 > 18000.0)
        assertTrue("新口径要压回 1.5 万亿以内", snapshot.predictedFullDay!! / 1e8 < 15500.0)
    }

    @Test
    fun truncatedPreviousDayIsNotTreatedAsFullDay() {
        // 末条采样停在 14:45（885），只是半日累计，不能当全天预估的基数。
        val truncated = TurnoverCurve("2026-10-16", linkedMapOf(585 to 3349e8, 885 to 12000e8))

        val snapshot = TurnoverTracker.buildSnapshot(3921e8, 592, truncated, TurnoverTracker.Source.LOCAL, morning(592))

        assertEquals(3349e8, snapshot.previousSameTime!!, 1.0)
        assertNull(snapshot.previousFullDay)
        // 没有全天基数就不走比例外推；拿半日额 12000 亿当全天会算出 14047 亿这个假数。
        val bogus = 3921e8 / 3349e8 * 12000e8
        assertTrue(snapshot.predictedFullDay != bogus)
    }

    @Test
    fun partialDayStaysAGapAndCurrentDateIsExcluded() {
        val samples = JSONObject("""{"2026-10-09":{"900":1600000000000},"2026-10-12":{"885":1700000000000},"2026-10-13":{"900":2200000000000}}""")
        val history = TurnoverTracker.fullDayHistoryYi(samples, "2026-10-13", 5)

        assertEquals(2, history.size)
        assertEquals(16000.0, history[0], 0.001)
        assertTrue(history[1].isNaN())
    }

    @Test
    fun fullDayHistoryAcceptsIntradayMinuteKeysAndStillRejectsTruncatedDays() {
        // 新口径按真实采样时刻落盘：末条落在 15:00 之前的日子仍是空档。
        val samples = JSONObject(
            """{"2026-10-09":{"570":1000000000000,"781":1400000000000,"900":1600000000000},
               "2026-10-12":{"570":1000000000000,"884":1500000000000}}"""
        )
        val history = TurnoverTracker.fullDayHistoryYi(samples, "2026-10-13", 5)

        assertEquals(2, history.size)
        assertEquals(16000.0, history[0], 0.001)
        assertTrue(history[1].isNaN())
    }
}
