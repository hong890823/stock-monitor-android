package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage

/**
 * 「启动突破观察」的已覆盖代码集合。
 *
 * 只把仍在观察中的条目视为已覆盖：淘汰条目由 [ObservationPoolRetentionPolicy] 短期保留，
 * 之后必须允许代码像新票一样重新被发现，否则任何曾经入过池的股票会被永久拉黑。
 */
object LaunchDiscoveryCoveragePolicy {
    fun coveredCodes(
        observationPool: List<ObservationCandidate>,
        watchlistCandidates: List<ObservationCandidate>,
    ): Set<String> = (observationPool + watchlistCandidates)
        .asSequence()
        .filter { it.stage != ObservationStage.ELIMINATED }
        .map { it.code }
        .toSet()
}