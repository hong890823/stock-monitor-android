package com.example.stockmonitor.analysis

import kotlin.math.max

data class BreakoutRiskEvidence(
    val price: Double,
    val dailyHighPrice: Double?,
    val breakoutReference: Double?,
    val changePct: Double,
    val volumeRatio: Double?,
    val atrPct: Double?,
    val continuation: Boolean,
    val priceLocation: PriceLocation? = null,
    val ma5: Double? = null,
    val ma20: Double? = null,
    val biasFromMa5Pct: Double? = null,
)

data class BreakoutRiskDecision(
    val risk: Boolean,
    val reason: String,
)

object BreakoutRiskPolicy {
    const val REFERENCE_BREAK_PCT = 0.5
    const val DROP_FROM_HIGH_PCT = 2.0
    const val EXPANDED_VOLUME_RATIO = 1.3
    const val RAPID_DROP_ATR_FACTOR = 1.2
    const val HIGH_BIAS_PCT = 8.0
    const val LINE_BREAK_TOLERANCE_PCT = 0.5

    fun evaluate(evidence: BreakoutRiskEvidence): BreakoutRiskDecision {
        if (!evidence.continuation || !evidence.price.isFinite() || evidence.price <= 0.0) {
            return BreakoutRiskDecision(false, "不属于突破延续后的下杀风险")
        }
        val reference = evidence.breakoutReference?.takeIf { it.isFinite() && it > 0.0 }
        val belowReference = reference != null &&
            evidence.price < reference * (1.0 - REFERENCE_BREAK_PCT / 100.0)
        val high = evidence.dailyHighPrice?.takeIf { it.isFinite() && it > 0.0 }
        val dropFromHighPct = high?.let { (it - evidence.price) / it * 100.0 } ?: 0.0
        val expanded = evidence.volumeRatio?.let { it.isFinite() && it >= EXPANDED_VOLUME_RATIO } == true
        val rapidDropThreshold = max(
            DROP_FROM_HIGH_PCT,
            evidence.atrPct?.takeIf { it.isFinite() && it > 0.0 }?.times(RAPID_DROP_ATR_FACTOR) ?: DROP_FROM_HIGH_PCT,
        )
        val rapidDrop = dropFromHighPct >= rapidDropThreshold
        val priceDamage = belowReference && expanded && (evidence.changePct <= -2.0 || rapidDrop)
        val riskLineBroken = riskLineBroken(evidence)
        val risk = riskLineBroken || priceDamage || (rapidDrop && expanded)
        if (!risk) return BreakoutRiskDecision(false, "突破延续状态暂未出现下杀确认")
        val reason = when {
            riskLineBroken && useMa5Stop(evidence) -> "股价位置偏高或乖离较大，跌破5日线，突破延续转为下杀风险"
            riskLineBroken -> "股价位置不高，跌破20日线，突破延续转为下杀风险"
            priceDamage && rapidDrop -> "突破延续后跌破突破参考位且冲高回落，出现下杀风险"
            priceDamage -> "突破延续后跌破突破参考位，出现下杀风险"
            else -> "突破延续后从日内高点快速回落且量能放大，出现下杀风险"
        }
        return BreakoutRiskDecision(true, reason)
    }

    fun continuation(
        entryType: EntrySetupType?,
        highPositionPhase: HighPositionPhase?,
    ): Boolean = entryType in setOf(EntrySetupType.BREAKOUT, EntrySetupType.BREAKOUT_WATCH) ||
        highPositionPhase in setOf(HighPositionPhase.SECOND_WAVE_CANDIDATE, HighPositionPhase.SECOND_WAVE_CONFIRMED)

    fun useMa5Stop(evidence: BreakoutRiskEvidence): Boolean =
        evidence.priceLocation == PriceLocation.HIGH ||
            (evidence.biasFromMa5Pct?.takeIf { it.isFinite() } ?: 0.0) >= HIGH_BIAS_PCT

    fun riskLineBroken(evidence: BreakoutRiskEvidence): Boolean {
        val line = if (useMa5Stop(evidence)) {
            evidence.ma5?.takeIf { it.isFinite() && it > 0.0 }
        } else {
            evidence.ma20?.takeIf { it.isFinite() && it > 0.0 }
        } ?: return false
        return evidence.price < line * (1.0 - LINE_BREAK_TOLERANCE_PCT / 100.0)
    }
}
