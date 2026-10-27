package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.SinaClient
import com.example.stockmonitor.core.withStageResult

enum class ObservationSection(val label: String) {
    BUY(ObservationStage.BUY_CANDIDATE.label),
    NEAR(ObservationStage.NEAR_ENTRY.label),
    LIMIT_CONTINUOUS("涨停持续观察"),
    DEFERRED("暂缓参与 · 封板/缩量加速"),
    OBSERVING("其他观察对象"),
}

data class PrioritizedObservationSections(
    val buy: List<ObservationCandidate>,
    val strong: List<ObservationCandidate>,
    val waiting: List<ObservationCandidate>,
    val other: List<ObservationCandidate>,
)

object ObservationEligibility {
    const val PAGE_SIZE = 8
    // 封板判定容差统一走 SinaClient.LIMIT_TOLERANCE_PCT（与全市场扫描、连板统计同一口径）。
    private const val LIMIT_TOLERANCE_PCT = SinaClient.LIMIT_TOLERANCE_PCT
    const val LIMIT_WAIT_REASON = "涨停封板，暂不介入，开板后重新确认"
    const val CONTINUOUS_LIMIT_WAIT_REASON = "涨停封板，持续观察；开板后重新确认，不执行买入"
    const val SHRINK_WAIT_REASON = "缩量加速风险，已降级观察；等待换手、承接及入场条件重新确认"

    fun isAtLimitUp(code: String, name: String, quote: Quote): Boolean {
        if (!quote.prevClose.isFinite() || quote.prevClose <= 0.0 || !quote.price.isFinite()) {
            return false
        }
        val limit = SinaClient.limitPctFor(code, name)
        val reachedLimit = quote.changePct >= limit - LIMIT_TOLERANCE_PCT
        val atSessionHigh = quote.high <= 0.0 || quote.price >= quote.high * 0.9995
        return reachedLimit && atSessionHigh
    }

    fun isLimitUpShrinkAcceleration(
        limitUpBlocked: Boolean,
        changePct: Double,
        volumeRatio: Double?,
        wasDeferred: Boolean = false,
    ): Boolean {
        // 缩量加速只描述当前仍封板的风险；开板后必须由新行情重新判断，不能沿用旧状态。
        if (volumeRatio == null || !volumeRatio.isFinite() || volumeRatio <= 0.0) {
            return limitUpBlocked && wasDeferred
        }
        return limitUpBlocked && changePct > VolumePrice.FLAT_PCT &&
            volumeRatio <= VolumeRatio.SHRINK_THRESHOLD
    }

    fun isLimitUpDeferred(candidate: ObservationCandidate, quote: Quote?): Boolean {
        if (candidate.stage == ObservationStage.ELIMINATED) return false
        // 有实时行情时只相信当前是否封板；历史缩量/封板原因不能把开板后的候选永久锁住。
        quote?.let { return isAtLimitUp(candidate.code, candidate.name, it) }
        return candidate.limitUpBlocked || candidate.limitUpShrinkAcceleration ||
            candidate.reason.contains("涨停封板") || candidate.reason.contains("涨停缩量加速")
    }

    fun applyQuoteGate(candidate: ObservationCandidate, quote: Quote?): ObservationCandidate {
        if (candidate.stage == ObservationStage.ELIMINATED) return candidate
        val quoteAtLimit = quote?.let { isAtLimitUp(candidate.code, candidate.name, it) }
        val shrinkRisk = quoteAtLimit == true && candidate.limitUpShrinkAcceleration
        if (!isLimitUpDeferred(candidate, quote)) {
            // 涨停持续观察在开板首轮保留上一轮封板标记，供状态机消费一次开板宽限；
            // 但展示分组已按当前实时价判断为非封板，不会继续停在暂缓档。
            val openingContinuous = candidate.continuousObservation && candidate.limitUpBlocked && quoteAtLimit == false
            val normalized = candidate.copy(
                limitUpBlocked = if (openingContinuous) candidate.limitUpBlocked else false,
                limitUpShrinkAcceleration = false,
            )
            val requiredConfirmationRounds = ObservationPool.requiredConfirmationRounds(normalized)
            val newQuoteDay = quote?.quoteDate != null &&
                quote.quoteDate != normalized.lastEvaluatedDate
            val reconfirmationRequired = newQuoteDay ||
                (normalized.strategy != ObservationStrategy.PRE_BREAKOUT &&
                    !normalized.dailyBaseEntryConfirmed &&
                    normalized.entryConfirmationRounds < requiredConfirmationRounds)
            return if (normalized.stage == ObservationStage.BUY_CANDIDATE && reconfirmationRequired) {
                normalized.copy(
                    entryConfirmationRounds = 0,
                    entryConfirmedAtMs = 0L,
                    entryConfirmationVolume = 0.0,
                ).withStageResult(
                    stage = ObservationStage.NEAR_ENTRY,
                    date = quote?.quoteDate ?: normalized.lastEvaluatedDate ?: normalized.addedDate,
                    atMs = 0L,
                    reason = "等待回踩企稳及连续复评确认",
                )
            } else normalized
        }
        val stage = when {
            shrinkRisk && candidate.stage == ObservationStage.BUY_CANDIDATE -> ObservationStage.NEAR_ENTRY
            shrinkRisk -> ObservationStage.OBSERVING
            candidate.stage == ObservationStage.BUY_CANDIDATE -> ObservationStage.NEAR_ENTRY
            else -> candidate.stage
        }
        val reason = when {
            candidate.continuousObservation &&
                candidate.strategy == ObservationStrategy.LIMIT_UP_CONTINUATION ->
                CONTINUOUS_LIMIT_WAIT_REASON
            shrinkRisk -> SHRINK_WAIT_REASON
            else -> LIMIT_WAIT_REASON
        }
        return candidate.copy(
            limitUpBlocked = quoteAtLimit ?: candidate.limitUpBlocked,
            limitUpShrinkAcceleration = shrinkRisk,
            entryConfirmationRounds = 0,
            entryConfirmedAtMs = 0L,
            entryConfirmationVolume = 0.0,
        ).withStageResult(
            stage = stage,
            date = quote?.quoteDate ?: candidate.lastEvaluatedDate ?: candidate.addedDate,
            atMs = 0L,
            reason = reason,
            softRiskRounds = 0,
        )
    }

    fun needsRefresh(candidate: ObservationCandidate, today: String, quote: Quote?): Boolean {
        return candidate.stage != ObservationStage.ELIMINATED && quote != null &&
            quote.quoteDate == today && quote.price.isFinite() && quote.price > 0
    }

    fun sections(candidates: List<ObservationCandidate>, quotes: Map<String, Quote>): Map<ObservationSection, List<ObservationCandidate>> {
        val active = forDisplay(candidates, quotes)
        return ObservationSection.entries.associateWith { section ->
            active.filter { item ->
                val actual = when {
                    item.continuousObservation && isLimitUpDeferred(item, quotes[item.code]) ->
                        ObservationSection.LIMIT_CONTINUOUS
                    isLimitUpDeferred(item, quotes[item.code]) -> ObservationSection.DEFERRED
                    item.stage == ObservationStage.BUY_CANDIDATE -> ObservationSection.BUY
                    item.stage == ObservationStage.NEAR_ENTRY -> ObservationSection.NEAR
                    else -> ObservationSection.OBSERVING
                }
                actual == section
            }
        }
    }

    fun prioritizedSections(
        candidates: List<ObservationCandidate>,
        quotes: Map<String, Quote>,
        isStrong: (ObservationCandidate) -> Boolean,
    ): PrioritizedObservationSections {
        val sections = sections(candidates, quotes)
        val buy = sections.getValue(ObservationSection.BUY)
        val near = sections.getValue(ObservationSection.NEAR)
        // 封板持续与缩量/封板暂缓优先归入第四档，不能因为板块强就伪装成可等待参与的强势观察。
        val deferred = sections.getValue(ObservationSection.LIMIT_CONTINUOUS) +
            sections.getValue(ObservationSection.DEFERRED)
        val observing = sections.getValue(ObservationSection.OBSERVING)
        val riskNear = near.filter(::isRiskOrDeferredObservation)
        val ordinaryNear = near.filterNot(::isRiskOrDeferredObservation)
        val riskObserving = observing.filter(::isRiskOrDeferredObservation)
        val ordinaryObserving = observing.filterNot(::isRiskOrDeferredObservation)
        val (strongNear, neutralNear) = ordinaryNear.partition(isStrong)
        val (strongObserving, neutralObserving) = ordinaryObserving.partition(isStrong)
        return PrioritizedObservationSections(
            buy = buy,
            strong = strongNear + strongObserving,
            waiting = neutralNear,
            other = deferred + riskNear + riskObserving + neutralObserving,
        )
    }

    /** 强势板块只决定正常观察项的排序，不能覆盖已经明确的风险或暂缓状态。 */
    private fun isRiskOrDeferredObservation(candidate: ObservationCandidate): Boolean =
        candidate.breakoutRisk?.risk == true ||
            candidate.limitUpBlocked ||
            candidate.limitUpShrinkAcceleration ||
            listOf("风险", "暂缓", "不晋级", "趋势向下", "数据缺失", "封板", "缩量", "走弱")
                .any { token -> candidate.reason.contains(token) }

    fun page(items: List<ObservationCandidate>, index: Int): List<ObservationCandidate> =
        items.drop(index.coerceAtLeast(0) * PAGE_SIZE).take(PAGE_SIZE)

    fun deduplicateCandidates(
        candidates: List<ObservationCandidate>,
    ): List<ObservationCandidate> = candidates.groupBy { it.code }.map { (_, sameStock) ->
        if (sameStock.size == 1) return@map sameStock.first()
        val latestEvaluationDate = sameStock.maxOf {
            it.lastEvaluatedDate ?: it.addedDate
        }
        val latestCandidates = sameStock.filter {
            (it.lastEvaluatedDate ?: it.addedDate) == latestEvaluationDate
        }
        val representative = latestCandidates.maxWithOrNull(
            compareBy<ObservationCandidate> { it.stage.promotionRank }
                .thenByDescending { it.continuousObservation }
                .thenByDescending { it.limitStreak ?: 0 }
                .thenByDescending { it.score },
        ) ?: return@map sameStock.first()
        val highestRank = sameStock.maxOf { (it.highestStage ?: it.stage).promotionRank }
        val highestMilestones = sameStock.filter {
            (it.highestStage ?: it.stage).promotionRank == highestRank
        }
        representative.copy(
            score = sameStock.maxOf { it.score },
            observedDays = sameStock.maxOf { it.observedDays },
            lastEvaluatedDate = sameStock.mapNotNull { it.lastEvaluatedDate }.maxOrNull(),
            continuousObservation = sameStock.any { it.continuousObservation },
            limitUpBlocked = sameStock.any { it.limitUpBlocked },
            limitUpShrinkAcceleration = sameStock.any { it.limitUpShrinkAcceleration },
            limitStreak = sameStock.mapNotNull { it.limitStreak }.maxOrNull(),
            sealQualityScore = sameStock.mapNotNull { it.sealQualityScore }.maxOrNull(),
            sealQualityCoveragePct = sameStock.mapNotNull { it.sealQualityCoveragePct }.maxOrNull(),
            boards = sameStock.flatMap { it.boards }.distinct(),
            highestStage = sameStock.mapNotNull { it.highestStage ?: it.stage }
                .maxByOrNull { it.promotionRank },
            highestStageDate = highestMilestones.mapNotNull { it.highestStageDate }.minOrNull()
                ?: highestMilestones.firstOrNull()?.lastEvaluatedDate,
            highestStageAtMs = highestMilestones.map { it.highestStageAtMs }
                .filter { it > 0L }.minOrNull() ?: representative.highestStageAtMs,
            statusChanges = sameStock.flatMap { it.statusChanges }
                .distinct()
                .sortedWith(compareBy({ it.date }, { it.atMs })),
        )
    }

    fun entrySections(
        candidates: List<ObservationCandidate>,
        quotes: Map<String, Quote>,
    ): Map<ObservationSection, List<ObservationCandidate>> =
        sections(candidates, quotes).filterKeys {
            it == ObservationSection.BUY || it == ObservationSection.NEAR ||
                it == ObservationSection.LIMIT_CONTINUOUS
        }.filterValues { it.isNotEmpty() }

    /**
     * 展示层保留同一股票的不同策略路线，避免封板路线的暂缓状态覆盖另一条已确认的启动路线。
     * 同一路线若因缓存出现重复，才按现有代表项规则折叠。
     */
    private fun deduplicateByStrategy(candidates: List<ObservationCandidate>): List<ObservationCandidate> =
        candidates.groupBy { it.code to it.strategy }.values.flatMap(::deduplicateCandidates)

    fun forDisplay(candidates: List<ObservationCandidate>, quotes: Map<String, Quote>): List<ObservationCandidate> =
        deduplicateByStrategy(candidates).filter { it.stage != ObservationStage.ELIMINATED }
            .map { applyQuoteGate(it, quotes[it.code]) }
            .sortedWith(
                compareBy<ObservationCandidate> {
                    when {
                        isLimitUpDeferred(it, quotes[it.code]) -> 2
                        it.stage == ObservationStage.BUY_CANDIDATE -> 0
                        it.stage == ObservationStage.NEAR_ENTRY -> 1
                        else -> 3
                    }
                }.thenByDescending { it.score }
                    .thenByDescending { it.lastEvaluatedDate ?: it.addedDate }
                    .thenBy { it.code },
            )
}
