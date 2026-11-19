package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.core.WatchItem

object WatchlistBuyCandidatePool {
    const val SOURCE = "自选股买点确认"

    fun sync(
        watchlist: List<WatchItem>,
        previous: List<ObservationCandidate>,
        heldCodes: Set<String>,
        observationCodes: Set<String>,
        today: String,
    ): List<ObservationCandidate> {
        val watchedCodes = watchlist
            .filterNot { it.code.startsWith("sh000") || it.code.startsWith("sz399") }
            .filter { it.code !in heldCodes && it.code !in observationCodes }
            .associateBy { it.code }
        val previousByCode = previous.associateBy { it.code }
        return watchedCodes.values.map { item ->
            val existing = previousByCode[item.code]
            when {
                existing == null -> candidate(item.code, item.name, today)
                // 淘汰当日不复活：sync 每个盘中 tick（约 15 秒）都会跑，立即重置的话
                // 「10日未确认/连续走弱」淘汰最多存活一个 tick、淘汰原因与状态轨迹
                // 全部丢失。保留到收盘，次日才开始新一轮观察（原测试语义）。
                existing.stage == ObservationStage.ELIMINATED &&
                    existing.lastEvaluatedDate != today -> candidate(item.code, item.name, today)
                else -> existing.copy(name = item.name)
            }
        }
    }

    fun candidate(code: String, name: String, today: String): ObservationCandidate =
        ObservationCandidate(
            code = code,
            name = name,
            addedDate = today,
            lastEvaluatedDate = null,
            stage = ObservationStage.OBSERVING,
            score = 0,
            weakDays = 0,
            observedDays = 0,
            lastPrice = 0.0,
            source = SOURCE,
            sources = setOf(SOURCE),
            reason = "自选股持续评估有效买点",
            strategy = ObservationStrategy.PRE_BREAKOUT,
        )
}
