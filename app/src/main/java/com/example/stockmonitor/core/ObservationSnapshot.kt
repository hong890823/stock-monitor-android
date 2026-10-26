package com.example.stockmonitor.core

/**
 * 观察池候选在一个交易日收盘/复评时的最小证据快照。
 *
 * 快照不替代 [ObservationCandidate] 当前状态；它只用于后续复盘和策略绩效统计。
 */
data class ObservationSnapshot(
    val code: String,
    val name: String,
    val date: String,
    val stage: ObservationStage,
    val strategy: ObservationStrategy,
    val score: Int,
    val price: Double,
    val changePct: Double,
    val weakDays: Int,
    val observedDays: Int,
    val stopPrice: Double?,
    val reason: String,
    val evidence: ObservationEvidence = ObservationEvidence(),
    val highestStage: ObservationStage = stage,
    val highestStageDate: String = date,
    val highestStageAtMs: Long = 0L,
)

object ObservationSnapshotHistory {
    const val MAX_DAYS_PER_STOCK = 30

    /** 同一股票同一交易日只保留最新结果，其他股票的历史不受影响。 */
    fun append(
        history: List<ObservationSnapshot>,
        snapshot: ObservationSnapshot,
    ): List<ObservationSnapshot> {
        val previous = history.lastOrNull { it.code == snapshot.code && it.date == snapshot.date }
        val merged = if (previous == null) {
            snapshot
        } else {
            val highestStage = if (snapshot.highestStage.promotionRank >= previous.highestStage.promotionRank) {
                snapshot.highestStage
            } else {
                previous.highestStage
            }
            val highestStageDate = if (snapshot.highestStage.promotionRank >= previous.highestStage.promotionRank) {
                snapshot.highestStageDate
            } else {
                previous.highestStageDate
            }
            val highestStageAtMs = if (snapshot.highestStage.promotionRank >= previous.highestStage.promotionRank) {
                snapshot.highestStageAtMs
            } else {
                previous.highestStageAtMs
            }
            snapshot.copy(
                highestStage = highestStage,
                highestStageDate = highestStageDate,
                highestStageAtMs = highestStageAtMs,
            )
        }
        val replaced = history.filterNot { it.code == snapshot.code && it.date == snapshot.date } + merged
        val grouped = replaced
            .groupBy { it.code }
            .flatMap { (_, items) -> items.sortedBy { it.date }.takeLast(MAX_DAYS_PER_STOCK) }
        return grouped.sortedWith(compareBy<ObservationSnapshot> { it.date }.thenBy { it.code })
    }
}
