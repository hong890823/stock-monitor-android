package com.example.stockmonitor

import com.example.stockmonitor.analysis.ReboundTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反弹质量跟踪：深砸回升后区分「有效反弹」与两类风险——诱多、抛压过大。
 *
 * 锁住五件事：站稳 + 量价配合才给确认（并带失效线）；缩量回升只给观察、别追；
 * 放量滞涨（量强价弱 = 抛压/换手出货）判风险、拦截确认并在途也报；
 * 回落走弱 / 吐回果实给诱多警示，且确认之后失效或抛压都要撤回「已企稳」状态；
 * 布防两路——恐慌坑或 -3% 日内坑，高波票浅坑深 V 不漏、坑不够深仍静默。
 */
class ReboundTrackerTest {

    private val day = "2026-10-22"
    private val t0 = 1_000_000_000L

    private fun at(minute: Double): Long = t0 + (minute * 60_000).toLong()

    private fun sample(
        changePct: Double,
        atMs: Long,
        cum: Double? = null,
        atrPct: Double? = null,
        held: Boolean = true,
    ) = ReboundTracker.Sample(
        code = "sz000001",
        name = "测试股",
        price = 10.0 * (1 + changePct / 100),
        changePct = changePct,
        volumeRatio = null,
        cumulativeTurnover = cum,
        timestamp = atMs,
        atrPct = atrPct,
        resistance = 0.0,
        held = held,
    )

    private fun run(tracker: ReboundTracker, samples: List<ReboundTracker.Sample>): List<ReboundTracker.Event> =
        samples.flatMap { tracker.update(listOf(it), day) }

    @Test
    fun volumeReboundThatHoldsConfirmsWithInvalidationLine() {
        val tracker = ReboundTracker()
        // 跌段 30 分钟砸 3.5%，回升段量能放大（累计成交额快速抬升 = 承接有力）。
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6),
                sample(-3.5, at(0.0), cum = 100e6),
                sample(-3.0, at(2.0), cum = 110e6),
                sample(-2.5, at(4.0), cum = 122e6),
                sample(-2.0, at(6.0), cum = 136e6),
                sample(-1.7, at(8.0), cum = 150e6),
                sample(-1.5, at(10.0), cum = 164e6),
                sample(-1.4, at(12.0), cum = 178e6),
            ),
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(ReboundTracker.Kind.CONFIRMED, event.kind)
        assertTrue(event.vibrate)
        assertTrue(event.body.contains("承接"))
        assertTrue(event.body.contains("失效线"))
        assertTrue(event.body.contains("9.65"))
        // 分层声明：盘中窗口的结论不能被读成趋势反转。
        assertTrue(event.body.contains("不改趋势判断"))
        assertTrue(tracker.isConfirmed("sz000001"))
    }

    @Test
    fun shrunkReboundOnlyCautionsAndSaysDoNotChase() {
        val tracker = ReboundTracker()
        // 同样的回升形态，但累计成交额几乎不涨 = 缩量回升，最像诱多。
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6),
                sample(-3.5, at(0.0), cum = 100e6),
                sample(-3.0, at(2.0), cum = 101e6),
                sample(-2.5, at(4.0), cum = 102e6),
                sample(-2.0, at(6.0), cum = 103e6),
                sample(-1.7, at(8.0), cum = 104e6),
                sample(-1.5, at(10.0), cum = 105e6),
                sample(-1.4, at(12.0), cum = 106e6),
            ),
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(ReboundTracker.Kind.CAUTIOUS, event.kind)
        assertFalse(event.vibrate)
        assertTrue(event.body.contains("先别追"))
        assertFalse(tracker.isConfirmed("sz000001"))
    }

    @Test
    fun reboundRolloverWarnsTrapBeforeItEverSettles() {
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6),
                sample(-3.5, at(0.0), cum = 100e6),
                sample(-2.2, at(3.0), cum = 120e6),
                sample(-1.9, at(5.0), cum = 130e6),
                sample(-1.7, at(7.0), cum = 140e6),
                // 回升高点后连续走低：3 分钟内回撤加深，企稳（10 分钟）没等到就变脸。
                sample(-2.1, at(8.0), cum = 142e6),
                sample(-2.5, at(8.5), cum = 143e6),
            ),
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(ReboundTracker.Kind.TRAP, event.kind)
        assertTrue(event.title.contains("疑似诱多"))
        assertTrue(event.body.contains("别追"))
        assertFalse(tracker.isConfirmed("sz000001"))
    }

    @Test
    fun failureAfterConfirmWarnsAndClearsConfirmedState() {
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6),
                sample(-3.5, at(0.0), cum = 100e6),
                sample(-2.8, at(5.0), cum = 125e6),
                sample(-2.2, at(8.0), cum = 145e6),
                sample(-1.6, at(11.0), cum = 170e6),
                sample(-2.0, at(12.0), cum = 172e6),
                // 吐回大半回升果实：先前的「企稳确认」要作废。
                sample(-2.6, at(12.5), cum = 173e6),
            ),
        )

        assertEquals(2, events.size)
        assertEquals(ReboundTracker.Kind.CONFIRMED, events[0].kind)
        assertEquals(ReboundTracker.Kind.TRAP, events[1].kind)
        assertTrue(events[1].title.contains("反弹失效"))
        assertFalse(tracker.isConfirmed("sz000001"))
    }

    @Test
    fun newDeeperLowRearmsAndAllowsSecondConfirm() {
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6),
                sample(-3.5, at(0.0), cum = 100e6),
                sample(-3.0, at(2.0), cum = 110e6),
                sample(-2.5, at(4.0), cum = 122e6),
                sample(-2.0, at(6.0), cum = 136e6),
                sample(-1.5, at(10.0), cum = 164e6),
                sample(-1.4, at(12.0), cum = 178e6),
                // 又一波恐慌、砸出更深的坑：旧腿作废重新布防。
                sample(-3.7, at(15.0), cum = 185e6),
                sample(-3.0, at(20.0), cum = 210e6),
                sample(-2.4, at(25.0), cum = 235e6),
                sample(-1.7, at(28.0), cum = 255e6),
            ),
        )

        assertEquals(2, events.size)
        assertTrue(events.all { it.kind == ReboundTracker.Kind.CONFIRMED })
        assertTrue(tracker.isConfirmed("sz000001"))
    }

    @Test
    fun trapEventsStopAtDailyCap() {
        val tracker = ReboundTracker()
        val samples = mutableListOf<ReboundTracker.Sample>()
        // 四条腿各自回升后回落走弱；前三条给警示，第四条吃满日限。
        for (i in 0 until 4) {
            val low = -3.5 - i * 0.1
            val need = maxOf(1.2, -low * 0.5)
            val base = 100e6 + i * 50e6
            val start = i * 20.0
            samples += sample(-0.5, at(start - 30.0), cum = base - 50e6)
            samples += sample(low, at(start), cum = base)
            samples += sample(low + need + 0.25, at(start + 2.0), cum = base + 25e6)
            samples += sample(low + need - 0.05, at(start + 3.0), cum = base + 28e6)
            samples += sample(low + need - 0.7, at(start + 3.5), cum = base + 29e6)
        }

        val events = run(tracker, samples)

        assertEquals(3, events.size)
        assertTrue(events.all { it.kind == ReboundTracker.Kind.TRAP })
    }

    @Test
    fun shallowDipStaysSilent() {
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.3, at(-10.0), cum = 50e6),
                sample(-1.2, at(0.0), cum = 60e6),
                sample(-0.8, at(5.0), cum = 70e6),
                sample(-0.5, at(12.0), cum = 80e6),
            ),
        )

        assertTrue(events.isEmpty())
        assertFalse(tracker.isConfirmed("sz000001"))
    }

    @Test
    fun trapFiresOnlyOncePerLeg() {
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6),
                sample(-3.5, at(0.0), cum = 100e6),
                sample(-2.2, at(3.0), cum = 120e6),
                sample(-1.9, at(5.0), cum = 130e6),
                sample(-1.7, at(7.0), cum = 140e6),
                sample(-2.1, at(8.0), cum = 142e6),
                sample(-2.5, at(8.5), cum = 143e6),
                // 同一条腿又抽一次再回落：不给第二次警示（创更深低点才会重新武装）。
                sample(-1.6, at(12.0), cum = 150e6),
                sample(-2.2, at(13.0), cum = 151e6),
                sample(-2.6, at(13.5), cum = 152e6),
            ),
        )

        assertEquals(1, events.size)
    }

    @Test
    fun nonHoldingCopySaysSmallTrialInsteadOfTactics() {
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6, held = false),
                sample(-3.5, at(0.0), cum = 100e6, held = false),
                sample(-3.0, at(2.0), cum = 110e6, held = false),
                sample(-2.5, at(4.0), cum = 122e6, held = false),
                sample(-2.0, at(6.0), cum = 136e6, held = false),
                sample(-1.5, at(10.0), cum = 164e6, held = false),
            ),
        )

        val event = events.single()
        assertEquals(ReboundTracker.Kind.CONFIRMED, event.kind)
        assertTrue(event.body.contains("试错"))
        assertFalse(event.body.contains("做T"))
        assertFalse(event.held)
    }

    @Test
    fun heavyVolumeWithWeakPricePaceIsPressureNotConfirmation() {
        // 回升段成交节奏很凶，但价格推进效率只有下跌段的 0.4 倍——放量滞涨是抛压，不是承接。
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6),
                sample(-3.5, at(0.0), cum = 100e6),
                sample(-3.0, at(15.0), cum = 180e6),
                sample(-2.4, at(30.0), cum = 320e6),
                sample(-1.7, at(45.0), cum = 480e6),
            ),
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(ReboundTracker.Kind.PRESSURE, event.kind)
        assertTrue(event.title.contains("抛压"))
        assertTrue(event.body.contains("别按有效反弹追"))
        assertFalse(tracker.isConfirmed("sz000001"))
    }

    @Test
    fun stallWithRacingTurnoverWarnsPressureBeforeSettle() {
        // 两分钟冲上去后高点停更、成交还在放：不等 10 分钟定性，中途就报抛压。
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6),
                sample(-3.5, at(0.0), cum = 100e6),
                sample(-1.7, at(2.0), cum = 300e6),
                sample(-1.72, at(5.0), cum = 380e6),
                sample(-1.74, at(7.5), cum = 460e6),
            ),
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(ReboundTracker.Kind.PRESSURE, event.kind)
        assertTrue(event.title.contains("滞涨"))
        assertFalse(tracker.isConfirmed("sz000001"))
    }

    @Test
    fun pressureAfterConfirmClearsConfirmedState() {
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6),
                sample(-3.5, at(0.0), cum = 100e6),
                sample(-2.8, at(5.0), cum = 125e6),
                sample(-2.2, at(8.0), cum = 145e6),
                sample(-1.6, at(11.0), cum = 170e6),
                // 确认之后价格停涨、成交继续放：抛压接管，「已企稳」要作废。
                sample(-1.65, at(14.0), cum = 260e6),
                sample(-1.68, at(17.0), cum = 350e6),
            ),
        )

        assertEquals(2, events.size)
        assertEquals(ReboundTracker.Kind.CONFIRMED, events[0].kind)
        assertEquals(ReboundTracker.Kind.PRESSURE, events[1].kind)
        assertFalse(tracker.isConfirmed("sz000001"))
    }

    @Test
    fun sluggishPricePaceWithMiddlingVolumeIsCautiousNotConfirm() {
        // 量能不缩但价格推进效率只有 0.46：介于承接与放量滞涨之间，降级为观察、不给确认。
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6),
                sample(-3.5, at(0.0), cum = 100e6),
                sample(-2.9, at(20.0), cum = 130e6),
                sample(-1.65, at(40.0), cum = 170e6),
            ),
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(ReboundTracker.Kind.CAUTIOUS, event.kind)
        assertTrue(event.title.contains("乏力"))
        assertTrue(event.body.contains("推不动"))
        assertFalse(tracker.isConfirmed("sz000001"))
    }

    @Test
    fun shallowPitOnHighVolNameStillTracksRebound() {
        // 2026-10-22 中天科技：ATR% ≈ 5.3 把恐慌坑布防线钳到 -5%，-3.4% 的坑够不着；
        // 走「日内坑 ≤ -3%」这条路布防，收复 1.7pp + 站稳后照常给确认。
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6, atrPct = 5.3),
                sample(-3.39, at(0.0), cum = 100e6, atrPct = 5.3),
                sample(-2.9, at(2.0), cum = 110e6, atrPct = 5.3),
                sample(-2.4, at(5.0), cum = 122e6, atrPct = 5.3),
                sample(-1.9, at(10.0), cum = 136e6, atrPct = 5.3),
                sample(-1.6, at(12.0), cum = 150e6, atrPct = 5.3),
            ),
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(ReboundTracker.Kind.CONFIRMED, event.kind)
        assertTrue(event.vibrate)
        assertTrue(event.body.contains("失效线"))
        assertTrue(event.body.contains("9.66"))
        assertTrue(tracker.isConfirmed("sz000001"))
    }

    @Test
    fun dipAboveArmLineOnHighVolNameStaysSilent() {
        // 噪声底线：高波票 -2.5% 的坑两条布防路都不够（恐慌坑要 -5%，日内坑要 -3%），
        // 回升形态再漂亮也不进跟踪——松的是布防，不是定性。
        val tracker = ReboundTracker()
        val events = run(
            tracker,
            listOf(
                sample(-0.5, at(-30.0), cum = 50e6, atrPct = 5.3),
                sample(-2.5, at(0.0), cum = 100e6, atrPct = 5.3),
                sample(-2.0, at(5.0), cum = 130e6, atrPct = 5.3),
                sample(-1.5, at(10.0), cum = 160e6, atrPct = 5.3),
                sample(-1.1, at(12.0), cum = 178e6, atrPct = 5.3),
            ),
        )

        assertTrue(events.isEmpty())
        assertFalse(tracker.isConfirmed("sz000001"))
    }
}
