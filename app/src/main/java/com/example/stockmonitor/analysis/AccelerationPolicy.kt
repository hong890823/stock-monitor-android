package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import java.util.Locale

/**
 * 放量加速末端：日报、观察池/买点、盘中信号共用的研究观察口径。
 *
 * 来源：macOS 版 docs/strategies/主力跟随与散户反向分层.md 与回测结果。
 * 事件研究里「贴近高位 + 近 5 日 ≥10% + 量比 ≥1.3」的加速组 5 日超额为负，
 * 但**未经实盘验证**。因此这里只提供三档用法：

 * 1. [watchLabel]：报告展示标签，不参与任何判定；
 * 2. [breakoutNote] / [tSellNote]：盘中文案证据，不新增信号；
 * 3. [entryBlocker]：观察池/买点确认时「暂缓一轮」，不淘汰候选，且对龙头与
 *    已确认的独立回踩豁免。
 *
 * 不要把它当预测下跌的模型：单票胜率只有 35%–43%，它的价值是避免在加速末端
 * 追高，而不是提前判断顶部。
 */
enum class AccelerationState(val label: String) {
    ACCELERATING("放量加速"),
    DIVERGING("高位放量分歧"),
    CLEAR("未加速"),
    INSUFFICIENT("数据不足"),
}

data class AccelerationAssessment(
    val state: AccelerationState,
    val run5Pct: Double? = null,
    val volumeRatio: Double? = null,
    val high60: Double? = null,
    val todayPct: Double? = null,
    val basis: String = "",
)

object AccelerationPolicy {
    const val NEAR_HIGH_RATIO = 0.97
    const val RUN5_PCT = 10.0
    const val VOLUME_RATIO = 1.3
    const val CONTINUE_PCT = 1.0

    /** 研究阶段开关：关闭后 [entryBlocker] 一律返回 null，展示标签不受影响。 */
    @Volatile
    var entryBlockEnabled = true

    private val HIGH_PHASES = setOf(
        HighPositionPhase.EXTENDED_WATCH,
        HighPositionPhase.DISTRIBUTION_RISK,
        HighPositionPhase.CONSOLIDATION,
        HighPositionPhase.SECOND_WAVE_CANDIDATE,
        HighPositionPhase.SECOND_WAVE_CONFIRMED,
    )

    /** 由已算好的标量判定；金额单位为元，涨幅/百分比均为百分数。 */
    fun assess(
        price: Double?,
        run5Pct: Double?,
        volumeRatio: Double?,
        high60: Double?,
        todayPct: Double?,
    ): AccelerationAssessment {
        val value = price?.takeIf { it.isFinite() && it > 0.0 }
        val high = high60?.takeIf { it.isFinite() && it > 0.0 }
        val nearHigh = if (value != null && high != null) value >= high * NEAR_HIGH_RATIO else null
        return classify(nearHigh, run5Pct, volumeRatio, todayPct, high, "")
    }

    /** 日线序列（末根为今日）→ 加速判定；量比可传入盘中统一口径。 */
    fun assessFromBars(
        bars: List<DayBar>,
        price: Double? = null,
        volumeRatio: Double? = null,
    ): AccelerationAssessment {
        if (bars.size < 6) {
            return AccelerationAssessment(AccelerationState.INSUFFICIENT, basis = "日线不足6根")
        }
        val lastPrice = price?.takeIf { it.isFinite() && it > 0.0 } ?: bars.last().close
        val base5 = bars[bars.size - 6].close
        val baseToday = bars[bars.size - 2].close
        if (!lastPrice.isFinite() || lastPrice <= 0.0 || base5 <= 0.0 || baseToday <= 0.0) {
            return AccelerationAssessment(AccelerationState.INSUFFICIENT, basis = "收盘价缺失")
        }
        val run5 = (lastPrice / base5 - 1.0) * 100.0
        val todayPct = (lastPrice / baseToday - 1.0) * 100.0
        val high60 = (bars.takeLast(60).mapNotNull { it.high.takeIf { value -> value.isFinite() && value > 0.0 } } + lastPrice)
            .maxOrNull() ?: lastPrice
        val ratio = volumeRatio?.takeIf { it.isFinite() && it > 0.0 } ?: run {
            val prior = bars.dropLast(1).takeLast(20).mapNotNull { it.volume.takeIf { value -> value.isFinite() && value > 0.0 } }
            val lastVolume = bars.last().volume
            if (prior.isNotEmpty() && lastVolume.isFinite() && lastVolume > 0.0) {
                lastVolume / prior.average()
            } else {
                null
            }
        }
        return assess(lastPrice, run5, ratio, high60, todayPct)
    }

    /** 盘中上下文：优先用 60 日高点；缺失时用高位状态代替。 */
    fun assessIntraday(
        price: Double?,
        todayPct: Double?,
        run5Pct: Double?,
        volumeRatio: Double?,
        high60: Double?,
        highPosition: HighPositionDecision?,
    ): AccelerationAssessment {
        val high = high60?.takeIf { it.isFinite() && it > 0.0 }
        val value = price?.takeIf { it.isFinite() && it > 0.0 }
        if (value != null && high != null) {
            return assess(value, run5Pct, volumeRatio, high, todayPct)
        }
        val nearHigh = highPosition?.phase?.let { it in HIGH_PHASES }
        return classify(nearHigh, run5Pct, volumeRatio, todayPct, high, "盘中以高位状态代替60日高点")
    }

    /** 日报展示标签：只解释，不参与评分、优先级、动作或通知。 */
    fun watchLabel(assessment: AccelerationAssessment?): String? = when (assessment?.state) {
        AccelerationState.ACCELERATING -> "放量加速（研究观察，未验证，不追高）"
        AccelerationState.DIVERGING -> "高位放量分歧（研究观察，未验证）"
        else -> null
    }

    /** 突破文案证据；不改变突破信号本身与执行数量。 */
    fun breakoutNote(assessment: AccelerationAssessment?): String? =
        if (assessment?.state == AccelerationState.ACCELERATING) {
            "近5日放量加速，突破仅观察不追"
        } else {
            null
        }

    /** 做T文案证据；不改变做T触发和数量。 */
    fun tSellNote(assessment: AccelerationAssessment?): String? =
        if (assessment?.state == AccelerationState.ACCELERATING) {
            "近5日放量加速且贴近高位，优先了结"
        } else {
            null
        }

    /**
     * 买点确认的暂缓理由。
     *
     * 只暂缓一轮，不淘汰候选；[leader] 与 [confirmedPullback] 不生效。
     */
    fun entryBlocker(
        assessment: AccelerationAssessment?,
        leader: Boolean = false,
        confirmedPullback: Boolean = false,
    ): String? {
        if (!entryBlockEnabled || assessment?.state != AccelerationState.ACCELERATING) return null
        if (leader || confirmedPullback) return null
        val run5 = assessment.run5Pct ?: 0.0
        val ratio = assessment.volumeRatio ?: 0.0
        return String.format(
            Locale.CHINA,
            "近5日涨%.1f%%且量比%.1f，贴近60日高；放量加速末端暂缓晋级，等待回踩确认",
            run5,
            ratio,
        )
    }

    private fun classify(
        nearHigh: Boolean?,
        run5Pct: Double?,
        volumeRatio: Double?,
        todayPct: Double?,
        high60: Double?,
        basis: String,
    ): AccelerationAssessment {
        val run5 = run5Pct?.takeIf { it.isFinite() }
        val ratio = volumeRatio?.takeIf { it.isFinite() && it > 0.0 }
        val today = todayPct?.takeIf { it.isFinite() }
        if (nearHigh == null || run5 == null || ratio == null || today == null) {
            return AccelerationAssessment(
                state = AccelerationState.INSUFFICIENT,
                run5Pct = run5,
                volumeRatio = ratio,
                high60 = high60,
                todayPct = today,
                basis = basis.ifBlank { "缺高位/5日涨幅/量比/当日涨幅" },
            )
        }
        if (!nearHigh || run5 < RUN5_PCT || ratio < VOLUME_RATIO) {
            return AccelerationAssessment(
                state = AccelerationState.CLEAR,
                run5Pct = run5,
                volumeRatio = ratio,
                high60 = high60,
                todayPct = today,
                basis = basis.ifBlank { "未同时满足贴近高位、5日≥10%、量比≥1.3" },
            )
        }
        val state = if (today > CONTINUE_PCT) AccelerationState.ACCELERATING else AccelerationState.DIVERGING
        return AccelerationAssessment(
            state = state,
            run5Pct = run5,
            volumeRatio = ratio,
            high60 = high60,
            todayPct = today,
            basis = basis.ifBlank { "贴近高位且放量延伸" },
        )
    }
}
