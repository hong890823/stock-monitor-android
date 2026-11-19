package com.example.stockmonitor.analysis

import kotlin.math.max

/**
 * 做 T 的双边费用、滑点和净收益估算。
 *
 * 默认费用直接采用 [PaperTradingConfig]：佣金 0.02%（单边最低 5 元）、卖出印花税
 * 0.05%、过户费 0.001%；另以单边 0.05% 滑点保守模拟实际成交。该策略只估算
 * 做 T 是否值得尝试，不构成成交或接回容量记录。
 */
data class IntradayTNetProfitConfig(
    val paperTrading: PaperTradingConfig = PaperTradingConfig(),
    val oneWaySlippageRate: Double = 0.0005,
    val minimumNetProfit: Double = 10.0,
    val minimumNetProfitRate: Double = 0.0015,
) {
    init {
        require(oneWaySlippageRate.isFinite() && oneWaySlippageRate >= 0.0 && oneWaySlippageRate < 1.0) { "单边滑点必须在[0,1)之间" }
        require(minimumNetProfit.isFinite() && minimumNetProfit >= 0.0) { "最低净收益必须非负且有限" }
        require(minimumNetProfitRate.isFinite() && minimumNetProfitRate >= 0.0) { "最低净收益率必须非负且有限" }
    }
}

data class IntradayTNetProfitEstimate(
    val quantity: Double,
    val expectedSellPrice: Double,
    val expectedBuybackPrice: Double,
    val sellNotional: Double,
    val roundTripFees: Double,
    val roundTripSlippageCost: Double,
    val expectedNetProfit: Double,
    val requiredNetProfit: Double,
    val dynamicPullbackPct: Double,
    val supportBandPct: Double,
) {
    val meetsMinimum: Boolean
        get() = expectedNetProfit.isFinite() && requiredNetProfit.isFinite() &&
            expectedNetProfit > 0.0 && requiredNetProfit >= 0.0 && expectedNetProfit >= requiredNetProfit
}

/** 可复用的纯估算策略。价格均为预计成交价，不读取行情、不写入账本。 */
object IntradayTNetProfitPolicy {
    const val MIN_PULLBACK_PCT = 0.5
    const val MAX_PULLBACK_PCT = 2.0
    const val MIN_SUPPORT_BAND_PCT = 0.5
    const val MAX_SUPPORT_BAND_PCT = 2.0

    fun dynamicPullbackPct(atrPct: Double?): Double =
        max(MIN_PULLBACK_PCT, validAtrPct(atrPct) * 0.3).coerceAtMost(MAX_PULLBACK_PCT)

    fun supportBandPct(atrPct: Double?): Double =
        max(MIN_SUPPORT_BAND_PCT, validAtrPct(atrPct) * 0.3).coerceAtMost(MAX_SUPPORT_BAND_PCT)

    /**
     * 预期接回价取 ATR 回踩目标与支撑带上沿中的较高价，再计入买入滑点，避免以过低接回价虚增收益。
     */
    fun estimate(
        sellReferencePrice: Double,
        support: Double,
        atrPct: Double?,
        quantity: Double,
        config: IntradayTNetProfitConfig = IntradayTNetProfitConfig(),
    ): IntradayTNetProfitEstimate? {
        if (!sellReferencePrice.isFinite() || sellReferencePrice <= 0.0 ||
            !quantity.isFinite() || quantity <= 0.0
        ) return null

        val pullbackPct = dynamicPullbackPct(atrPct)
        val bandPct = supportBandPct(atrPct)
        val atrTarget = sellReferencePrice * (1.0 - pullbackPct / 100.0)
        val supportTarget = support.takeIf { it.isFinite() && it > 0.0 }
            ?.let { it * (1.0 + bandPct / 100.0) }
        val conservativeBuyReference = max(atrTarget, supportTarget ?: atrTarget)
        val sellPrice = sellReferencePrice * (1.0 - config.oneWaySlippageRate)
        val buyPrice = conservativeBuyReference * (1.0 + config.oneWaySlippageRate)
        val sellNotional = sellPrice * quantity
        val buyNotional = buyPrice * quantity
        val sellFees = commission(sellNotional, config) +
            sellNotional * config.paperTrading.stampDutyRate +
            sellNotional * config.paperTrading.transferFeeRate
        val buyFees = commission(buyNotional, config) + buyNotional * config.paperTrading.transferFeeRate
        val slippageCost = (sellReferencePrice - sellPrice + buyPrice - conservativeBuyReference) * quantity
        val required = max(config.minimumNetProfit, sellNotional * config.minimumNetProfitRate)
        return IntradayTNetProfitEstimate(
            quantity = quantity,
            expectedSellPrice = sellPrice,
            expectedBuybackPrice = buyPrice,
            sellNotional = sellNotional,
            roundTripFees = sellFees + buyFees,
            roundTripSlippageCost = slippageCost,
            expectedNetProfit = sellNotional - sellFees - buyNotional - buyFees,
            requiredNetProfit = required,
            dynamicPullbackPct = pullbackPct,
            supportBandPct = bandPct,
        )
    }

    private fun commission(notional: Double, config: IntradayTNetProfitConfig): Double =
        max(config.paperTrading.minimumCommission, notional * config.paperTrading.commissionRate)

    private fun validAtrPct(value: Double?): Double =
        value?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
}
