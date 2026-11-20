package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate

/**
 * 候选池仅在展示层使用的排序器。
 *
 * 状态机负责决定能否晋级或降档；这里不改变任何状态、风险门控或通知资格。排序严格按
 * 状态档位、个股结构、入场资格命中数、相对强度、市场风险、真实技术快照、复评进度执行，
 * 板块只用于其他条件相同时的平手裁决。
 *
 * 入场资格（`score`）必须参与排序：界面上会展示“资格 x/3”，如果排序完全忽略它，
 * 用户会看到高资格分的候选排在中后段，无法理解排序依据。
 */
object ObservationDisplayRankingPolicy {
    fun rank(
        candidates: List<ObservationCandidate>,
        tierOf: (ObservationCandidate) -> Int,
        boardTieBreakOf: (ObservationCandidate) -> StrongSectorRankingPolicy.Result? = { null },
    ): List<ObservationCandidate> = candidates.sortedWith(
        comparator(tierOf, boardTieBreakOf),
    )

    fun comparator(
        tierOf: (ObservationCandidate) -> Int,
        boardTieBreakOf: (ObservationCandidate) -> StrongSectorRankingPolicy.Result? = { null },
    ): Comparator<ObservationCandidate> = Comparator { left, right ->
        compareValues(tierOf(left), tierOf(right))
            .takeUnless { it == 0 }
            ?: compareDescending(left.structureQuality.rank, right.structureQuality.rank)
            .takeUnless { it == 0 }
            ?: compareDescending(left.score, right.score)
            .takeUnless { it == 0 }
            ?: compareDescendingNullable(left.relativeStrengthPct, right.relativeStrengthPct)
            .takeUnless { it == 0 }
            ?: compareMarketRisk(left, right)
            .takeUnless { it == 0 }
            ?: compareDescendingNullable(left.technicalScore, right.technicalScore)
            .takeUnless { it == 0 }
            ?: compareDescending(left.entryConfirmationRounds, right.entryConfirmationRounds)
            .takeUnless { it == 0 }
            ?: compareDescending(left.lastEvaluatedDate ?: left.addedDate, right.lastEvaluatedDate ?: right.addedDate)
            .takeUnless { it == 0 }
            ?: compareDescending(left.entryConfirmedAtMs, right.entryConfirmedAtMs)
            .takeUnless { it == 0 }
            ?: compareBoardTieBreak(boardTieBreakOf(left), boardTieBreakOf(right))
            .takeUnless { it == 0 }
            ?: left.code.compareTo(right.code)
    }

    private fun compareMarketRisk(left: ObservationCandidate, right: ObservationCandidate): Int {
        // 展示排序里“未知”不伪造为正常，也不应排在已确认的高风险之后。
        return compareValues(left.marketRisk?.rank ?: 0, right.marketRisk?.rank ?: 0)
    }

    private fun compareBoardTieBreak(
        left: StrongSectorRankingPolicy.Result?,
        right: StrongSectorRankingPolicy.Result?,
    ): Int {
        val role = compareDescending(left?.roleScore ?: 0, right?.roleScore ?: 0)
        if (role != 0) return role
        return compareDescending(left?.sectorScore ?: 0, right?.sectorScore ?: 0)
    }

    private fun compareDescending(left: Int, right: Int): Int = right.compareTo(left)

    private fun compareDescending(left: Long, right: Long): Int = right.compareTo(left)

    private fun compareDescending(left: String, right: String): Int = right.compareTo(left)

    private fun compareDescendingNullable(left: Int?, right: Int?): Int {
        if (left != null && right == null) return -1
        if (left == null && right != null) return 1
        return compareDescending(left ?: Int.MIN_VALUE, right ?: Int.MIN_VALUE)
    }

    private fun compareDescendingNullable(left: Double?, right: Double?): Int {
        val leftValid = left?.isFinite() == true
        val rightValid = right?.isFinite() == true
        if (leftValid && !rightValid) return -1
        if (!leftValid && rightValid) return 1
        if (!leftValid) return 0
        return right!!.compareTo(left!!)
    }
}
