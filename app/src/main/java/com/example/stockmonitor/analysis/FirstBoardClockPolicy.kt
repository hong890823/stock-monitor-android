package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage

/**
 * 首板时钟：一只票可能在同一策略行里经历多次「首板 → 连板 → 断板 → 再首板」。
 *
 * 旧实现把 `addedDate` 当首板日，但重新首板时只覆盖 `limitStreak`，日期不更新，
 * 第二次首板后第 1–3 个交易日的承接窗口会整体错位。
 */
object FirstBoardClockPolicy {
    /** 首板日：优先落库字段，旧数据回退到入池日。 */
    fun effectiveBoardDate(candidate: ObservationCandidate): String =
        candidate.firstBoardDate?.takeIf { it.isNotBlank() } ?: candidate.addedDate

    /**
     * 是否开启一轮新的首板观察周期。
     *
     * - 必须是非降级数据且今天连板数 = 1；
     * - 已记录的首板日不能就是今天（否则同一天多次刷新会反复重置）；
     * - 降级数据缺失连板数，不重置任何时钟。
     */
    fun startsNewCycle(
        limitStreak: Int?,
        previous: ObservationCandidate,
        today: String,
        degraded: Boolean,
    ): Boolean = !degraded &&
        limitStreak == 1 &&
        effectiveBoardDate(previous) != today

    /** 新一轮首板观察：清掉上一轮的观察天数、确认轮次和风险状态。 */
    fun startNewCycle(candidate: ObservationCandidate, today: String): ObservationCandidate =
        candidate.copy(
            addedDate = today,
            lastEvaluatedDate = null,
            stage = ObservationStage.OBSERVING,
            observedDays = 0,
            weakDays = 0,
            postConfirmationWatchDays = 0,
            entryConfirmationRounds = 0,
            entryConfirmationRequiredRounds = 0,
            entryConfirmedAtMs = 0L,
            entryConfirmationVolume = 0.0,
            entryTriggerPrice = 0.0,
            entryTriggerAtMs = 0L,
            firstBoardDate = today,
            riskSignals = emptyList(),
            breakoutRisk = null,
        )
}