package com.example.stockmonitor.analysis

import java.util.Locale

enum class IntradayFundFlowTone {
    SUPPORTIVE,
    TURNING_STRONG,
    DIVERGENT,
    MIXED,
    UNAVAILABLE,
}

/** 当日累计资金流与最近 5/15 分钟边际资金流；数据源按订单规模估算，不代表机构身份。 */
data class IntradayFundFlowContext(
    val dayMainNetYuan: Double?,
    val dayMainNetPct: Double?,
    val recent5MainNetYuan: Double?,
    val recent5MainNetPct: Double?,
    val recent15MainNetYuan: Double?,
    val recent15MainNetPct: Double?,
)

object IntradayFundFlowPolicy {
    const val DAY_SUPPORT_FLOW_PCT = 3.0
    const val RECENT_SUPPORT_FLOW_PCT = 3.0

    fun assess(context: IntradayFundFlowContext): IntradayFundFlowTone {
        val dayPct = context.dayMainNetPct
        val recent5Pct = context.recent5MainNetPct
        val recent15Pct = context.recent15MainNetPct
        if (dayPct == null || recent5Pct == null || recent15Pct == null ||
            !dayPct.isFinite() || !recent5Pct.isFinite() || !recent15Pct.isFinite()
        ) {
            return IntradayFundFlowTone.UNAVAILABLE
        }

        val dayPositive = dayPct >= DAY_SUPPORT_FLOW_PCT
        val dayNegative = dayPct <= -DAY_SUPPORT_FLOW_PCT
        val recent5Positive = recent5Pct >= RECENT_SUPPORT_FLOW_PCT
        val recent5Negative = recent5Pct <= -RECENT_SUPPORT_FLOW_PCT
        val recent15Positive = recent15Pct > 0.0
        return when {
            recent15Positive && dayPositive && recent5Positive ->
                IntradayFundFlowTone.SUPPORTIVE
            recent15Positive && dayNegative && recent5Positive ->
                IntradayFundFlowTone.TURNING_STRONG
            recent5Negative && (dayPositive || dayNegative) ->
                IntradayFundFlowTone.DIVERGENT
            else -> IntradayFundFlowTone.MIXED
        }
    }

    fun summary(context: IntradayFundFlowContext): String {
        val tone = assess(context)
        val dayText = flowText(context.dayMainNetYuan, context.dayMainNetPct)
        val recent5Text = flowText(context.recent5MainNetYuan, context.recent5MainNetPct)
        val recent15Text = flowText(context.recent15MainNetYuan, context.recent15MainNetPct)
        return when (tone) {
            IntradayFundFlowTone.SUPPORTIVE ->
                "资金配合：当日主力$dayText，近5分钟$recent5Text，近15分钟$recent15Text。"
            IntradayFundFlowTone.TURNING_STRONG ->
                "资金转强：当日主力$dayText，但近5分钟$recent5Text、近15分钟$recent15Text。"
            IntradayFundFlowTone.DIVERGENT ->
                "资金背离：当日主力$dayText，近5分钟$recent5Text，近15分钟$recent15Text。"
            IntradayFundFlowTone.MIXED ->
                "资金方向混合：当日主力$dayText，近5分钟$recent5Text，近15分钟$recent15Text。"
            IntradayFundFlowTone.UNAVAILABLE ->
                "资金数据不足：缺少完整当日及近5/15分钟窗口，不据此升级强提醒。"
        } + " 资金流按主动成交订单规模估算，不代表机构身份。"
    }

    private fun flowText(amountYuan: Double?, pct: Double?): String {
        if (amountYuan == null || pct == null ||
            !amountYuan.isFinite() || !pct.isFinite()
        ) {
            return "净流向暂缺"
        }
        val direction = when {
            amountYuan > 0.0 -> "净流入 "
            amountYuan < 0.0 -> "净流出 "
            else -> "净流向为0 "
        }
        return direction + formatAmount(abs(amountYuan)) +
            String.format(Locale.CHINA, "（%+.2f%%）", pct)
    }

    private fun formatAmount(value: Double): String = when {
        value >= 100_000_000.0 ->
            String.format(Locale.CHINA, "%.2f亿元", value / 100_000_000.0)
        value >= 10_000.0 ->
            String.format(Locale.CHINA, "%.0f万元", value / 10_000.0)
        else -> String.format(Locale.CHINA, "%.0f元", value)
    }

    private fun abs(value: Double): Double = if (value < 0.0) -value else value
}

/** 从当日累计主力净流快照构建最近 5/15 分钟边际窗口。 */
class IntradayFundFlowTracker(
    private val shortWindowMs: Long = 5 * 60_000L,
    private val longWindowMs: Long = 15 * 60_000L,
) {
    data class Observation(
        val timestampMs: Long,
        val cumulativeMainNetYuan: Double,
        val cumulativeAmountYuan: Double,
    )

    private data class State(
        val samples: MutableList<Observation> = mutableListOf(),
        var lastTimestampMs: Long = Long.MIN_VALUE,
        var lastCumulativeAmountYuan: Double = Double.NEGATIVE_INFINITY,
    )

    private val states = mutableMapOf<String, State>()
    private val contexts = mutableMapOf<String, IntradayFundFlowContext>()
    private var tradeDay: String? = null

    fun update(
        code: String,
        day: String,
        timestampMs: Long,
        cumulativeMainNetYuan: Double,
        cumulativeAmountYuan: Double,
        dayMainNetPct: Double? = null,
    ): IntradayFundFlowContext? {
        if (code.isBlank() || day.isBlank() ||
            !cumulativeMainNetYuan.isFinite() ||
            !cumulativeAmountYuan.isFinite() ||
            cumulativeAmountYuan < 0.0
        ) {
            return null
        }
        if (tradeDay != day) {
            states.clear()
            contexts.clear()
            tradeDay = day
        }
        val state = states.getOrPut(code) { State() }
        if (timestampMs <= state.lastTimestampMs ||
            cumulativeAmountYuan < state.lastCumulativeAmountYuan
        ) {
            return null
        }
        state.samples += Observation(
            timestampMs = timestampMs,
            cumulativeMainNetYuan = cumulativeMainNetYuan,
            cumulativeAmountYuan = cumulativeAmountYuan,
        )
        state.lastTimestampMs = timestampMs
        state.lastCumulativeAmountYuan = cumulativeAmountYuan

        // 先取基线再修剪：修剪阈值与 longBase 查找阈值同为一个 cutoff，若先删后查，
        // 「cutoff 之前（含）」的样本已被清掉，交集只剩恰好等于 cutoff 的那一条——
        // 生产时间戳（东财 asOfMs，毫秒抖动）几乎不可能命中，recent15 会恒为 null。
        val shortBase = valueAtOrBefore(state.samples, timestampMs - shortWindowMs)
        val longBase = valueAtOrBefore(state.samples, timestampMs - longWindowMs)
        state.samples.removeAll { it.timestampMs < timestampMs - longWindowMs }
        val recent5Amount = shortBase?.let { cumulativeAmountYuan - it.cumulativeAmountYuan }
        val recent15Amount = longBase?.let { cumulativeAmountYuan - it.cumulativeAmountYuan }
        val recent5Net = shortBase?.let { cumulativeMainNetYuan - it.cumulativeMainNetYuan }
        val recent15Net = longBase?.let { cumulativeMainNetYuan - it.cumulativeMainNetYuan }
        val currentDayPct = dayMainNetPct?.takeIf { it.isFinite() }
            ?: cumulativeAmountYuan.takeIf { it > 0.0 }
                ?.let { cumulativeMainNetYuan / it * 100.0 }
        val context = IntradayFundFlowContext(
            dayMainNetYuan = cumulativeMainNetYuan,
            dayMainNetPct = currentDayPct,
            recent5MainNetYuan = recent5Net,
            recent5MainNetPct = ratioPct(recent5Net, recent5Amount),
            recent15MainNetYuan = recent15Net,
            recent15MainNetPct = ratioPct(recent15Net, recent15Amount),
        )
        contexts[code] = context
        return context
    }

    fun context(code: String): IntradayFundFlowContext? = contexts[code]

    private fun valueAtOrBefore(
        samples: List<Observation>,
        timestampMs: Long,
    ): Observation? = samples.lastOrNull { it.timestampMs <= timestampMs }

    private fun ratioPct(netYuan: Double?, amountYuan: Double?): Double? {
        if (netYuan == null || amountYuan == null || amountYuan <= 0.0) return null
        return netYuan / amountYuan * 100.0
    }
}
