package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStatusChange

object ObservationReentryPolicy {
    fun revive(
        previous: ObservationCandidate,
        incoming: ObservationCandidate,
    ): ObservationCandidate {
        val highestStage = listOfNotNull(
            previous.highestStage ?: previous.stage,
            incoming.highestStage ?: incoming.stage,
        ).maxByOrNull { it.promotionRank }
        val highestRank = highestStage?.promotionRank
        val milestones = listOf(previous, incoming).filter {
            (it.highestStage ?: it.stage).promotionRank == highestRank
        }
        val reentryChange = ObservationStatusChange(
            date = incoming.lastEvaluatedDate ?: incoming.addedDate,
            atMs = incoming.highestStageAtMs,
            previousStage = ObservationStage.ELIMINATED,
            stage = incoming.stage,
            reason = "新信号触发重新入池",
        )
        return incoming.copy(
            highestStage = highestStage,
            highestStageDate = milestones.mapNotNull { it.highestStageDate }.minOrNull()
                ?: milestones.firstOrNull()?.lastEvaluatedDate
                ?: milestones.firstOrNull()?.addedDate,
            highestStageAtMs = milestones.map { it.highestStageAtMs }
                .filter { it > 0L }.minOrNull() ?: incoming.highestStageAtMs,
            statusChanges = (previous.statusChanges + reentryChange + incoming.statusChanges)
                .distinct()
                .sortedWith(compareBy({ it.date }, { it.atMs })),
        )
    }
}
