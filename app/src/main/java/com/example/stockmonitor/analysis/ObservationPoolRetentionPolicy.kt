package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 观察池淘汰记录的保留策略。
 *
 * 淘汰条目保留一段短时间（复盘/UI 仍能看到「为什么被淘汰」），之后从池里删除：
 * - 观察池、落盘 JSON 和逐行 UI 的全池扫描不会无限膨胀；
 * - 「启动突破观察」与其它复活路径不会因为代码“曾经入过池”而被永久拉黑。
 *
 * 日期解析失败时保留，宁可不清理也不误删历史。
 */
object ObservationPoolRetentionPolicy {
    /** 淘汰后保留的自然日数；超过后从池中删除。 */
    const val ELIMINATED_RETENTION_DAYS = 5L

    fun prune(candidates: List<ObservationCandidate>, today: String): List<ObservationCandidate> {
        val todayDate = runCatching { LocalDate.parse(today) }.getOrNull() ?: return candidates
        return candidates.filterNot { candidate ->
            candidate.stage == ObservationStage.ELIMINATED && isExpired(candidate, todayDate)
        }
    }

    fun isExpired(candidate: ObservationCandidate, today: LocalDate): Boolean {
        if (candidate.stage != ObservationStage.ELIMINATED) return false
        val dateText = candidate.lastEvaluatedDate?.takeIf { it.isNotBlank() }
            ?: candidate.addedDate
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return false
        return ChronoUnit.DAYS.between(date, today) > ELIMINATED_RETENTION_DAYS
    }
}