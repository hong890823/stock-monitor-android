package com.example.stockmonitor.analysis

import kotlin.math.roundToInt

data class LimitQualityMetrics(
    val id: String,
    val sealToTurnoverPct: Double? = null,
    val sealToFloatPct: Double? = null,
    val openCount: Double? = null,
    val firstSealSeconds: Int? = null,
    val lastSealSeconds: Int? = null,
    val limitStreak: Double? = null,
    val mainNetPct: Double? = null,
)

data class LimitQualityScore(
    val id: String,
    val score: Int?,
    val coveragePct: Int,
)

object LimitRanking {
    const val MIN_RELIABLE_COVERAGE_PCT = 50

    private const val SEAL_TO_TURNOVER_WEIGHT = 0.25
    private const val SEAL_TO_FLOAT_WEIGHT = 0.15
    private const val OPEN_COUNT_WEIGHT = 0.20
    private const val FIRST_SEAL_WEIGHT = 0.15
    private const val LAST_SEAL_WEIGHT = 0.05
    private const val LIMIT_STREAK_WEIGHT = 0.05
    private const val MAIN_NET_WEIGHT = 0.15

    fun rank(items: List<LimitQualityMetrics>): List<LimitQualityScore> =
        items
            .map { score(it, items) }
            .sortedWith(
                compareByDescending<LimitQualityScore> {
                    it.coveragePct >= MIN_RELIABLE_COVERAGE_PCT
                }.thenByDescending { it.score ?: -1 }
                    .thenByDescending { it.coveragePct }
            )

    private fun score(item: LimitQualityMetrics, peers: List<LimitQualityMetrics>): LimitQualityScore {
        val components = listOfNotNull(
            component(item.sealToTurnoverPct, peers.map { it.sealToTurnoverPct }, SEAL_TO_TURNOVER_WEIGHT, higherIsBetter = true),
            component(item.sealToFloatPct, peers.map { it.sealToFloatPct }, SEAL_TO_FLOAT_WEIGHT, higherIsBetter = true),
            component(item.openCount, peers.map { it.openCount }, OPEN_COUNT_WEIGHT, higherIsBetter = false),
            component(item.firstSealSeconds?.toDouble(), peers.map { it.firstSealSeconds?.toDouble() }, FIRST_SEAL_WEIGHT, higherIsBetter = false),
            component(item.lastSealSeconds?.toDouble(), peers.map { it.lastSealSeconds?.toDouble() }, LAST_SEAL_WEIGHT, higherIsBetter = true),
            component(item.limitStreak, peers.map { it.limitStreak }, LIMIT_STREAK_WEIGHT, higherIsBetter = true),
            component(item.mainNetPct, peers.map { it.mainNetPct }, MAIN_NET_WEIGHT, higherIsBetter = true),
        )
        val coverage = components.sumOf { it.weight }
        val weightedScore = components.sumOf { it.score * it.weight }
        return LimitQualityScore(
            id = item.id,
            score = if (coverage > 0.0) (weightedScore / coverage).roundToInt() else null,
            coveragePct = (coverage * 100).roundToInt(),
        )
    }

    private fun component(
        value: Double?,
        peers: List<Double?>,
        weight: Double,
        higherIsBetter: Boolean,
    ): ScoreComponent? {
        val validValue = value?.takeIf { it.isFinite() } ?: return null
        val validPeers = peers.mapNotNull { peer -> peer?.takeIf { it.isFinite() } }
        return ScoreComponent(weight, percentile(validValue, validPeers, higherIsBetter))
    }

    private fun percentile(value: Double, peers: List<Double>, higherIsBetter: Boolean): Double {
        if (peers.size < 2) return 50.0
        val worse = peers.count {
            if (higherIsBetter) it < value else it > value
        }
        val equal = peers.count { it == value }
        return 100.0 * (worse + (equal - 1) / 2.0) / (peers.size - 1)
    }

    private data class ScoreComponent(val weight: Double, val score: Double)
}
