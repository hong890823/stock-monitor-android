package com.example.stockmonitor.analysis

import java.util.Locale

/** 封板冲刺预警的阈值。以 2026-10-22 华天科技实测形态校准：封板前 2~3 分钟才有痕迹。 */
object SealSprintPolicy {

    /** 急拉判定的回看窗口（秒）：这类点火 5 分钟内见板，窗口太长会把慢涨当冲刺。 */
    const val WINDOW_SECONDS = 120L

    /** 窗口内涨这么多（百分点）才算急拉。 */
    const val SPRINT_GAIN_PCT = 3.0

    /** 窗口内每分钟成交量达到基线的这个倍数才算「放量突袭」（华天点火首分钟约 20 倍）。 */
    const val SPRINT_VOLUME_RATIO = 5.0

    /** 基线取窗口之前的这么长一段（分钟）；开盘头几分钟攒不出基线，不判。 */
    const val BASELINE_MINUTES = 30.0

    /** 基线至少要覆盖这么久（分钟），防止样本太少把第一笔脉冲当 5 倍量。 */
    const val BASELINE_MIN_SPAN_MINUTES = 5.0

    /** 同一代码触发后的冷却（分钟）：急拉不会每 15 秒一次，回落再拉才算新一枪。 */
    const val COOLDOWN_MINUTES = 10.0

    /** 每个代码每天最多报几次。 */
    const val MAX_PER_DAY = 2

    /** 距涨停在这么多（百分比）以内时文案才带「封板」字样。 */
    const val NEAR_LIMIT_PCT = 5.0
}

/**
 * 封板冲刺预警：识别「点火」形态——短时间内急拉 + 量能数倍于基线 + 创日内新高。
 *
 * 无板块先动、无盘口前兆的突发封板（如 2026-10-22 华天科技 13:55 点火、13:58 封板），
 * 盘口痕迹只早于封板 2~3 分钟，但那 2 分钟正是「拿稳别做T减飞」的关键窗口：
 * 15 秒 tick 的价量速率完全够判。判定纯看跃迁，触发后冷却 + 日限防刷屏；
 * 已封板（触涨停价）的不再报冲刺——那是 [LimitSealTracker] 的事。
 *
 * 分层约束：只做盘中窗口提示，不改趋势判断。
 */
class SealSprintTracker {

    data class Sample(
        val code: String,
        val name: String,
        val price: Double,
        val prevClose: Double,
        /** 当日最高（含现价）；现价贴着它才算「创日内新高」。 */
        val high: Double,
        /** 当日累计成交量（同口径即可，倍数比较不依赖单位）。 */
        val volume: Double,
        /** 涨跌停幅度（%），见 SinaClient.limitPctFor。 */
        val limitPct: Double,
        val timestamp: Long,
        val held: Boolean = false,
    )

    data class Event(
        val code: String,
        val name: String,
        val title: String,
        val body: String,
        val vibrate: Boolean,
        val held: Boolean,
    )

    private data class State(
        val samples: ArrayDeque<Pair<Long, Double>> = ArrayDeque(),
        val volumes: ArrayDeque<Pair<Long, Double>> = ArrayDeque(),
        var emitted: Int = 0,
        var lastEmitTs: Long = 0L,
    )

    private var tradingDay: String? = null
    private val states = mutableMapOf<String, State>()

    fun update(samples: List<Sample>, day: String): List<Event> {
        if (tradingDay != day) {
            states.clear()
            tradingDay = day
        }
        return samples.mapNotNull { evaluate(it) }
    }

    fun reset() {
        tradingDay = null
        states.clear()
    }

    private fun evaluate(sample: Sample): Event? {
        if (!sample.price.isFinite() || sample.price <= 0 || !sample.volume.isFinite() || sample.volume < 0) return null
        if (!sample.prevClose.isFinite() || sample.prevClose <= 0) return null
        val state = states.getOrPut(sample.code) { State() }
        remember(state, sample)
        // 冷却/日限先卡：已经报过就别把同一枪的余波再报一遍。
        if (state.emitted >= SealSprintPolicy.MAX_PER_DAY) return null
        if (sample.timestamp - state.lastEmitTs < SealSprintPolicy.COOLDOWN_MINUTES * 60_000) return null
        // 已封板的交给封板提醒，不报冲刺。
        val limitPrice = limitPrice(sample.prevClose, sample.limitPct)
        if (sample.price >= limitPrice - 0.005) return null

        val base = basePoint(state, sample.timestamp) ?: return null
        val window = windowPoint(state, sample.timestamp) ?: return null
        val windowMinutes = (sample.timestamp - window.first) / 60_000.0
        if (windowMinutes <= 0.2) return null
        val gainPct = (sample.price / window.second - 1) * 100
        if (gainPct < SealSprintPolicy.SPRINT_GAIN_PCT) return null
        // 创日内新高：现价就是日内最高的那一档（high 含现价）。
        if (sample.high <= 0 || sample.price < sample.high * 0.9995) return null

        val windowVolRate = ((sample.volume - window.third).coerceAtLeast(0.0)) / windowMinutes
        val baseMinutes = (window.first - base.first) / 60_000.0
        if (baseMinutes < SealSprintPolicy.BASELINE_MIN_SPAN_MINUTES) return null
        val baseVolRate = ((window.third - base.third).coerceAtLeast(0.0)) / baseMinutes
        if (baseVolRate <= 0.0) return null
        val volumeRatio = windowVolRate / baseVolRate
        if (volumeRatio < SealSprintPolicy.SPRINT_VOLUME_RATIO) return null

        state.emitted += 1
        state.lastEmitTs = sample.timestamp
        return sprintEvent(sample, gainPct, volumeRatio, limitPrice)
    }

    private fun remember(state: State, sample: Sample) {
        state.samples.addLast(sample.timestamp to sample.price)
        state.volumes.addLast(sample.timestamp to sample.volume)
        val cutoff = sample.timestamp - (SealSprintPolicy.WINDOW_SECONDS + SealSprintPolicy.BASELINE_MINUTES * 60_000).toLong()
        while (state.samples.isNotEmpty() && state.samples.first().first < cutoff) state.samples.removeFirst()
        while (state.volumes.isNotEmpty() && state.volumes.first().first < cutoff) state.volumes.removeFirst()
    }

    /** 基线段起点：窗口之前、且距今最远的那一笔（价格, 累计量）。 */
    private fun basePoint(state: State, nowMs: Long): Triple<Long, Double, Double>? {
        val windowStart = nowMs - SealSprintPolicy.WINDOW_SECONDS * 1000
        val baselineStart = windowStart - (SealSprintPolicy.BASELINE_MINUTES * 60_000).toLong()
        val anchor = state.volumes.firstOrNull { it.first in baselineStart..windowStart } ?: return null
        val price = state.samples.firstOrNull { it.first == anchor.first }?.second ?: return null
        return Triple(anchor.first, price, anchor.second)
    }

    /** 窗口段起点：刚好在窗口线上的那一笔。 */
    private fun windowPoint(state: State, nowMs: Long): Triple<Long, Double, Double>? {
        val windowStart = nowMs - SealSprintPolicy.WINDOW_SECONDS * 1000
        val anchor = state.volumes.lastOrNull { it.first <= windowStart } ?: return null
        val price = state.samples.firstOrNull { it.first == anchor.first }?.second ?: return null
        return Triple(anchor.first, price, anchor.second)
    }

    private fun sprintEvent(
        sample: Sample,
        gainPct: Double,
        volumeRatio: Double,
        limitPrice: Double,
    ): Event {
        val changePct = (sample.price / sample.prevClose - 1) * 100
        val toLimitPct = (limitPrice / sample.price - 1) * 100
        val nearLimit = toLimitPct <= SealSprintPolicy.NEAR_LIMIT_PCT
        val head = "现价 ${fmtPrice(sample.price)}（${fmtPct(changePct)}）" +
            String.format(Locale.CHINA, "2 分钟急拉 %.1f%%，量能约 %.0f 倍于基线，创日内新高", gainPct, volumeRatio.coerceAtMost(99.0))
        val distance = if (nearLimit) {
            String.format(Locale.CHINA, "，距涨停仅 %.1f%%", toLimitPct)
        } else {
            String.format(Locale.CHINA, "，距涨停 %.1f%%", toLimitPct)
        }
        val action = if (sample.held) {
            "——封板冲刺形态，持仓先拿稳，别在冲刺里做T减仓；冲高回落再按回落口径处理。"
        } else {
            "——封板冲刺形态，别追高抢板；回封打开、缩量回踩再观察。"
        }
        return Event(
            code = sample.code,
            name = sample.name,
            title = "⚡ ${sample.name}·急拉冲板",
            body = head + distance + action + SCOPE_NOTE,
            vibrate = true,
            held = sample.held,
        )
    }

    private fun limitPrice(prevClose: Double, limitPct: Double): Double =
        Math.round(prevClose * (1 + limitPct / 100.0) * 100) / 100.0

    private fun fmtPrice(value: Double): String =
        if (value >= 100) String.format(Locale.CHINA, "%.0f", value)
        else String.format(Locale.CHINA, "%.2f", value)

    private fun fmtPct(value: Double): String = String.format(Locale.CHINA, "%+.1f%%", value)

    private companion object {
        const val SCOPE_NOTE = "（仅为盘中窗口，不改趋势判断）"
    }
}
