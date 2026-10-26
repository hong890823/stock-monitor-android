package com.example.stockmonitor.analysis

enum class ValuationRiskLevel {
    MISSING,
    NEUTRAL,
    WATCH,
    ALERT,
}

data class ValuationRiskResult(
    val level: ValuationRiskLevel,
    val summary: String,
    val technicalImpact: Int = 0,
)

/** PE/PB 只做独立估值风险，不折算进技术分。 */
object ValuationRiskPolicy {
    fun assess(
        peTtm: Double?,
        pbMrq: Double?,
        pePercentile: Double?,
        pbPercentile: Double?,
    ): ValuationRiskResult {
        val notes = mutableListOf<String>()
        var alert = false
        if (peTtm != null && peTtm.isFinite()) {
            if (peTtm <= 0.0) {
                notes += "PE-TTM为负或无效，不参与估值比较"
            } else if (peTtm >= 100.0 || finiteAtLeast(pePercentile, 90.0)) {
                notes += "PE-TTM处于高估值区间"
                alert = alert || peTtm >= 150.0 || finiteAtLeast(pePercentile, 95.0)
            }
        }
        if (pbMrq != null && pbMrq.isFinite()) {
            if (pbMrq <= 0.0) {
                notes += "PB-MRQ为负或无效，不参与估值比较"
            } else if (pbMrq >= 10.0 || finiteAtLeast(pbPercentile, 90.0)) {
                notes += "PB-MRQ处于高估值区间"
                alert = alert || pbMrq >= 15.0 || finiteAtLeast(pbPercentile, 95.0)
            }
        }
        val level = when {
            notes.isEmpty() && peTtm == null && pbMrq == null -> ValuationRiskLevel.MISSING
            alert -> ValuationRiskLevel.ALERT
            notes.isNotEmpty() -> ValuationRiskLevel.WATCH
            else -> ValuationRiskLevel.NEUTRAL
        }
        return ValuationRiskResult(
            level = level,
            summary = when {
                notes.isEmpty() && level == ValuationRiskLevel.MISSING -> "PE/PB暂缺，不影响技术分"
                notes.isEmpty() -> "PE/PB未见明显估值风险"
                else -> notes.joinToString("；")
            },
            technicalImpact = 0,
        )
    }

    private fun finiteAtLeast(value: Double?, threshold: Double): Boolean =
        value != null && value.isFinite() && value >= threshold
}
