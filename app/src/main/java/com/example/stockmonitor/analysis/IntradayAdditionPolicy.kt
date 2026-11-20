package com.example.stockmonitor.analysis

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class IntradayAdditionStatus {
    READY,
    WAITING_CONFIRMATION,
    OUTSIDE_WINDOW,
    BLOCKED,
    INVALIDATED,
    EXPIRED,
}

data class IntradayAdditionBase(
    val code: String,
    val name: String,
    val tradingDate: String,
    val createdAtMs: Long,
    val support: Double,
    val pressure: Double,
    val stopPrice: Double,
    val baselineAddQuantity: Double,
    /** 在最高接受价重新计算得到的保守数量上限。 */
    val maxQuantityAtHighestPrice: Double,
    val riskRewardApproved: Boolean,
    val estimatedFeeRate: Double = 0.0003,
    val estimatedFeeFlat: Double = 5.0,
    /** 生成建议时的自由现金；按最高接受价重算数量时使用。null 表示不参与（兼容旧调用）。 */
    val availableCapital: Double? = null,
    val portfolioFingerprint: String = "",
    val evaluationFingerprint: String = "",
) {
    val isFinite: Boolean
        get() = listOf(
            support,
            pressure,
            stopPrice,
            baselineAddQuantity,
            maxQuantityAtHighestPrice,
            estimatedFeeRate,
            estimatedFeeFlat,
        ).all { it.isFinite() }
}

data class IntradayAdditionSample(
    val timestampMs: Long,
    val price: Double,
    val quoteDate: String,
    val marketQuoteDate: String,
    val sequence: Int,
    val priceRecovered: Boolean,
    val indexConfirmed: Boolean,
    val fundingConfirmed: Boolean,
    val indexAgeMs: Long,
    val timeframeAgeMs: Long,
)

data class IntradayAdditionState(
    val code: String,
    val date: String,
    val maxAcceptedPrice: Double? = null,
    val invalidated: Boolean = false,
    val portfolioFingerprint: String = "",
    val evaluationFingerprint: String = "",
)

data class IntradayAdditionDecision(
    val status: IntradayAdditionStatus,
    val reason: String,
    val lowerBound: Double? = null,
    val upperBound: Double? = null,
    val quantity: Double = 0.0,
    val acceptedPrice: Double? = null,
    val sampleCount: Int = 0,
    val state: IntradayAdditionState? = null,
) {
    val isActionable: Boolean get() = status == IntradayAdditionStatus.READY && quantity > 0.0
}

/**
 * 盘中补仓的严格二阶段确认策略。
 *
 * 基础加仓结果由周期性持仓分析产生；这里仅复核新鲜行情、价格窗口、独立样本和已完成周期证据，
 * 不放宽原有加仓数量和风险门槛。
 */
object IntradayAdditionPolicy {
    const val WINDOW_VALIDITY_MS = 35 * 60_000L
    const val MIN_SAMPLE_SPAN_MS = 120_000L
    const val MAX_SAMPLE_INTERVAL_MS = 90_000L
    const val MAX_QUOTE_AGE_MS = 90_000L
    const val MIN_SAMPLES = 3
    const val LOT = 100.0

    fun evaluate(
        base: IntradayAdditionBase,
        currentPrice: Double,
        quoteDate: String?,
        marketQuoteDate: String?,
        nowMs: Long,
        samples: List<IntradayAdditionSample>,
        previousState: IntradayAdditionState?,
    ): IntradayAdditionDecision {
        val initialState = IntradayAdditionState(
            code = base.code,
            date = base.tradingDate,
            maxAcceptedPrice = previousState?.maxAcceptedPrice,
            invalidated = previousState?.invalidated == true,
            portfolioFingerprint = base.portfolioFingerprint,
            evaluationFingerprint = base.evaluationFingerprint,
        )
        if (base.tradingDate.isBlank() || base.code.isBlank() || !base.isFinite) {
            return blocked(initialState, "基础加仓评估数据无效，暂停盘中补仓")
        }
        if (previousState != null &&
            (previousState.code != base.code || previousState.date != base.tradingDate ||
                previousState.portfolioFingerprint != base.portfolioFingerprint ||
                previousState.evaluationFingerprint != base.evaluationFingerprint)
        ) {
            return blocked(initialState, "持仓或交易日指纹不一致，暂停盘中补仓")
        }
        if (nowMs - base.createdAtMs > WINDOW_VALIDITY_MS) {
            return IntradayAdditionDecision(
                IntradayAdditionStatus.EXPIRED,
                "基础加仓评估已超过35分钟，需要重新生成",
                state = initialState,
            )
        }
        if (quoteDate != base.tradingDate || marketQuoteDate != base.tradingDate) {
            return blocked(initialState, "个股或指数行情不是当天数据，暂停盘中补仓")
        }
        if (previousState?.invalidated == true || currentPrice < base.stopPrice) {
            return IntradayAdditionDecision(
                IntradayAdditionStatus.INVALIDATED,
                "现价跌破止损，今日盘中补仓窗口失效",
                state = initialState.copy(invalidated = true),
            )
        }
        if (base.stopPrice >= base.support || base.support >= base.pressure) {
            return blocked(initialState, "止损、支撑、压力关系无效，暂停盘中补仓")
        }
        if ((base.support - base.stopPrice) / base.support > 0.10) {
            return blocked(initialState, "止损距离超过10%，暂停盘中补仓")
        }
        if (!base.riskRewardApproved) {
            return blocked(initialState, "费用后潜在空间未覆盖估算风险，暂停盘中补仓")
        }
        val lower = ceilCent(max(base.support * 0.995, base.stopPrice + 0.01))
        val calculatedUpper = floorCent(min(base.support * 1.015, (base.pressure + 2 * base.stopPrice) / 3.0))
        val upper = previousState?.maxAcceptedPrice?.let { floorCent(min(calculatedUpper, it)) } ?: calculatedUpper
        if (lower > upper || upper <= 0.0) {
            return blocked(initialState, "价格窗口无效，暂停盘中补仓", lower, upper)
        }
        val nextState = initialState.copy(maxAcceptedPrice = upper)
        if (currentPrice < lower || currentPrice > upper) {
            return IntradayAdditionDecision(
                IntradayAdditionStatus.OUTSIDE_WINDOW,
                "现价不在允许买入区间 ${price(lower)}～${price(upper)}，连续确认重置",
                lower,
                upper,
                state = nextState,
            )
        }
        val freshSamples = samples.sortedBy { it.timestampMs }
        val sampleReason = sampleFailureReason(base, freshSamples, nowMs)
        if (sampleReason != null) {
            return IntradayAdditionDecision(
                IntradayAdditionStatus.WAITING_CONFIRMATION,
                sampleReason,
                lower,
                upper,
                sampleCount = freshSamples.size,
                state = nextState,
            )
        }
        // 数量必须按“最高接受价 + 当前可用现金”重算：窗口内价格上移时，同样的股数会
// 占用更多现金；旧实现把 maxQuantityAtHighestPrice 直接等于 baseline，等于没重算。
        val cashLimited = base.availableCapital?.let { cash ->
            if (!cash.isFinite() || cash <= 0.0) {
                0.0
            } else {
                ((cash - base.estimatedFeeFlat) / (upper * (1.0 + base.estimatedFeeRate)))
                    .coerceAtLeast(0.0)
            }
        } ?: Double.POSITIVE_INFINITY
        val maxAtUpper = if (base.availableCapital != null) cashLimited else base.maxQuantityAtHighestPrice
        val quantity = lotFloor(min(base.baselineAddQuantity, min(maxAtUpper, cashLimited)))
        if (quantity < LOT) {
            return IntradayAdditionDecision(
                IntradayAdditionStatus.BLOCKED,
                "按最高接受价和可用现金重算后不足100股，不生成可执行补仓",
                lower,
                upper,
                sampleCount = freshSamples.size,
                state = nextState,
            )
        }
        return IntradayAdditionDecision(
            IntradayAdditionStatus.READY,
            "价格、个股/指数时效、15/30分钟证据和三次独立样本均已确认",
            lower,
            upper,
            quantity,
            acceptedPrice = upper,
            sampleCount = freshSamples.size,
            state = nextState,
        )
    }

    private fun sampleFailureReason(
        base: IntradayAdditionBase,
        samples: List<IntradayAdditionSample>,
        nowMs: Long,
    ): String? {
        if (samples.size < MIN_SAMPLES) return "等待至少 3 个独立样本，当前仅${samples.size}个"
        val span = samples.last().timestampMs - samples.first().timestampMs
        if (span < MIN_SAMPLE_SPAN_MS) return "等待独立样本跨度达到120秒"
        if (samples.zipWithNext().any { (left, right) -> right.timestampMs - left.timestampMs > MAX_SAMPLE_INTERVAL_MS }) {
            return "样本间隔超过90秒，连续确认重置"
        }
        if (samples.last().timestampMs > nowMs || nowMs - samples.last().timestampMs > MAX_QUOTE_AGE_MS) {
            return "个股或指数样本已超过90秒，等待新鲜确认"
        }
        if (samples.last().indexAgeMs > MAX_QUOTE_AGE_MS || samples.last().timeframeAgeMs > MAX_QUOTE_AGE_MS) {
            return "指数行情或周期证据已超过90秒，等待新鲜确认"
        }
        if (samples.any { it.quoteDate != base.tradingDate || it.marketQuoteDate != base.tradingDate }) {
            return "个股或指数样本不是当天数据，等待新鲜确认"
        }
        if (samples.any { !it.priceRecovered || !it.indexConfirmed || !it.fundingConfirmed }) {
            return "价格回升、指数和资金证据尚未同时确认"
        }
        return null
    }

    private fun blocked(
        state: IntradayAdditionState,
        reason: String,
        lower: Double? = null,
        upper: Double? = null,
    ) = IntradayAdditionDecision(
        IntradayAdditionStatus.BLOCKED,
        reason,
        lower,
        upper,
        state = state,
    )

    private fun ceilCent(value: Double): Double = ceil(value * 100.0 - 1e-9) / 100.0

    private fun floorCent(value: Double): Double = floor(value * 100.0 + 1e-9) / 100.0

    private fun lotFloor(value: Double): Double =
        if (value.isFinite() && value > 0.0) floor(value / LOT) * LOT else 0.0

    private fun price(value: Double): String = String.format(Locale.CHINA, "%.2f", value)
}
