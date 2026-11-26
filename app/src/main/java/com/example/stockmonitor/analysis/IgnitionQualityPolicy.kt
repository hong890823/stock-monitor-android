package com.example.stockmonitor.analysis

import java.util.Locale

/**
 * 点火四维诱多评级（2026-11-26，与 mac 端 assess_ignition_quality 同口径）：
 * 环境（大盘/板块偏弱）、位置（贴压力冲高或近 5 日涨幅 ≥[POSITION_EXTENSION_5D_PCT]% 的加速末端）、
 * 脉冲（单笔脉冲主导或冲高后衰竭）、资金（主动买卖背离/分歧）。
 * 命中 0-1 维 = 点火质量高、2 = 谨慎、≥3 = 疑似诱多；
 * 缺数据的维度记中性（不计风险，不误判诱多）。
 *
 * 评级只影响点火提醒的呈现节奏（先观察后确认，见 [VolumePriceAnomalyTracker]），
 * 不构成买卖建议，也不单独证明诱多。
 */
object IgnitionQualityPolicy {

    const val QUALITY_GOOD = "点火质量高"
    const val QUALITY_CAUTION = "谨慎"
    const val QUALITY_TRAP = "疑似诱多"

    /** 现价距压力位该比例以内算「贴压力冲高」。 */
    const val POSITION_PRESSURE_PCT = 1.0

    /** 近 5 日涨幅达到该比例算「加速末端」。 */
    const val POSITION_EXTENSION_5D_PCT = 15.0

    /** 单样本占短窗爆量比例超过该值算「单笔脉冲」。 */
    const val PULSE_CONCENTRATION = 0.5

    /** 现价较高点回落超过该百分比算「脉冲后衰竭」。 */
    const val PULSE_FADE_PCT = 0.5

    /** 资金维度的风险口径（与 [IntradayFundFlowTone] 小写名一致）。 */
    private val FUND_FLOW_RISK_TONES = setOf("divergent", "mixed")

    data class IgnitionQuality(
        val rating: String,
        val riskCount: Int,
        val reasons: List<String>,
    ) {
        val summary: String
            get() = if (reasons.isEmpty()) rating else "$rating（命中${riskCount}维：${reasons.joinToString("；")}）"
    }

    fun assess(
        marketChangePct: Double? = null,
        sectorChangePct: Double? = null,
        resistance: Double? = null,
        price: Double? = null,
        recentChange5dPct: Double? = null,
        burstConcentration: Double? = null,
        fadeFromHighPct: Double? = null,
        fundFlowTone: String? = null,
    ): IgnitionQuality {
        val reasons = mutableListOf<String>()
        // 环境：大盘或板块偏弱
        val envHit = listOf(marketChangePct, sectorChangePct).any { finite(it)?.let { n -> n < 0 } == true }
        if (envHit) reasons += "环境：大盘/板块偏弱"
        // 位置：贴压力冲高，或连续大涨后的加速末端
        val res = finite(resistance)
        val px = finite(price)
        val nearPressure = res != null && px != null && res > 0 && px > 0 &&
            kotlin.math.abs(px - res) / res <= POSITION_PRESSURE_PCT / 100
        val extended = finite(recentChange5dPct)?.let { it >= POSITION_EXTENSION_5D_PCT } == true
        if (nearPressure || extended) reasons += "位置：贴压力/加速末端"
        // 脉冲：单笔脉冲主导，或较脉冲高点已明显回落
        val concentrationHit = finite(burstConcentration)?.let { it > PULSE_CONCENTRATION } == true
        val fadeHit = finite(fadeFromHighPct)?.let { it > PULSE_FADE_PCT } == true
        if (concentrationHit || fadeHit) reasons += "脉冲：急拉缺承接/冲高后衰竭"
        // 资金：主动成交口径背离或分歧
        if (fundFlowTone?.trim()?.lowercase(Locale.ROOT) in FUND_FLOW_RISK_TONES) {
            reasons += "资金：主动买卖背离/分歧"
        }
        val rating = when {
            reasons.size <= 1 -> QUALITY_GOOD
            reasons.size == 2 -> QUALITY_CAUTION
            else -> QUALITY_TRAP
        }
        return IgnitionQuality(rating, reasons.size, reasons)
    }

    private fun finite(value: Double?): Double? = value?.takeIf { it.isFinite() }
}
