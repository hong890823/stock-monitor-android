package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.FundFlow
import com.example.stockmonitor.core.ObservationMarketRisk
import com.example.stockmonitor.core.ObservationRiskType
import com.example.stockmonitor.core.ObservationStructureQuality
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
    /** 曾确认买点但现处于 NEAR_ENTRY 的优先复参与机会；仍不是可直接参与资格。 */
    val priorityReentry: List<ObservationCandidate>,
    val near: List<ObservationCandidate>,
    val observing: List<ObservationCandidate>,
    /** 当前不能执行但没有结构/环境风险的暂缓项，例如封板和已明确标记的暂缓。 */
    val temporarilyDeferred: List<ObservationCandidate>,
    /** 结构、趋势或暂停型市场风险项。 */
    val risk: List<ObservationCandidate>,
)

object ObservationEligibility {
    const val PAGE_SIZE = 8
    const val PATTERN_SOURCE = "513形态"
    const val TREND_SOURCE = "趋势深扫"
    // 封板判定容差统一走 SinaClient.LIMIT_TOLERANCE_PCT（与全市场扫描、连板统计同一口径）。
    private const val LIMIT_TOLERANCE_PCT = SinaClient.LIMIT_TOLERANCE_PCT
    /** 炸板后仍算守住高位线的最大离涨停幅度（百分点，不是绝对涨幅）。 */
    const val POST_LIMIT_HOLD_GAP_PCT = 3.0
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

    fun touchedLimitUp(code: String, name: String, quote: Quote): Boolean {
        if (!quote.prevClose.isFinite() || quote.prevClose <= 0.0 ||
            !quote.high.isFinite() || quote.high <= 0.0
        ) {
            return false
        }
        val limit = SinaClient.limitPctFor(code, name)
        val highChangePct = (quote.high / quote.prevClose - 1.0) * 100.0
        return highChangePct >= limit - LIMIT_TOLERANCE_PCT
    }

    /**
     * 今日摸过涨停，但当前已经开板，且仍维持在离涨停 [POST_LIMIT_HOLD_GAP_PCT] 个点以内。
     * 10cm 对应约水上 7 个点，20cm/30cm 仍按离涨停距离判断，避免把绝对 +7% 误当高位。
     */
    fun isPostLimitHighHoldCandidate(code: String, name: String, quote: Quote): Boolean {
        if (isAtLimitUp(code, name, quote) || !touchedLimitUp(code, name, quote)) return false
        if (!quote.changePct.isFinite() || !quote.price.isFinite() || quote.price <= 0.0) return false
        val limit = SinaClient.limitPctFor(code, name)
        return quote.changePct >= limit - POST_LIMIT_HOLD_GAP_PCT - 1e-6
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
            // 炸板高位承接是独立日线结构：确认时故意不要求轮次（entryConfirmationRounds=0），
            // gate 不能再按普通 LEADER 的 2/3 轮把它打回 NEAR，否则买点永远推不出去、
            // 状态每分钟 BUY↔NEAR 抖一次。新交易日仍照常降档复评。
            val independentHighHold = normalized.structureQuality ==
                com.example.stockmonitor.core.ObservationStructureQuality.POST_LIMIT_HIGH_HOLD
            val reconfirmationRequired = newQuoteDay ||
                (normalized.strategy != ObservationStrategy.PRE_BREAKOUT &&
                    !independentHighHold &&
                    !normalized.dailyBaseEntryConfirmed &&
                    normalized.entryConfirmationRounds < requiredConfirmationRounds)
            return if (normalized.stage == ObservationStage.BUY_CANDIDATE && reconfirmationRequired) {
                normalized.copy(
                    entryConfirmationRounds = 0,
                    entryConfirmedAtMs = 0L,
                    entryConfirmationVolume = 0.0,
                    entryTriggerPrice = 0.0,
                    entryTriggerAtMs = 0L,
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
            entryTriggerPrice = 0.0,
            entryTriggerAtMs = 0L,
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

    fun isSourceExpired(
        candidate: ObservationCandidate,
        nowMs: Long,
        today: String = TimeUtils.todayText(),
    ): Boolean {
        if (candidate.stage == ObservationStage.ELIMINATED ||
            (candidate.highestStage ?: candidate.stage) == ObservationStage.BUY_CANDIDATE
        ) {
            return false
        }
        val tradingDayExpired = candidate.sourceValidTradingDays > 0 &&
            candidate.sourceObservedDays >= candidate.sourceValidTradingDays &&
            candidate.lastEvaluatedDate != null &&
            candidate.lastEvaluatedDate != today
        val legacyTimestampExpired = candidate.sourceExpiresAtMs > 0L && nowMs >= candidate.sourceExpiresAtMs
        return tradingDayExpired || legacyTimestampExpired
    }

    fun expireStaleSource(
        candidate: ObservationCandidate,
        nowMs: Long,
        today: String = TimeUtils.todayText(),
    ): ObservationCandidate {
        if (!isSourceExpired(candidate, nowMs, today)) return candidate
        val date = candidate.lastEvaluatedDate ?: candidate.addedDate
        val remainingSources = candidate.allSources - PATTERN_SOURCE
        if (remainingSources.isNotEmpty()) {
            return candidate.copy(
                source = remainingSources.first(),
                sources = remainingSources.toCollection(linkedSetOf()),
                sourceExpiresAtMs = 0L,
                sourceValidTradingDays = 0,
                sourceObservedDays = 0,
                reason = "$PATTERN_SOURCE 形态来源已过期，保留${remainingSources.joinToString("＋")}来源继续观察",
            )
        }
        return candidate.withStageResult(
            stage = ObservationStage.ELIMINATED,
            date = date,
            atMs = nowMs,
            reason = "$PATTERN_SOURCE 形态来源已过期，不再沿用旧形态继续观察",
        )
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

    /**
     * 展示档位严格跟随当前状态和暂缓/风险标记。板块信息不参与分组，也不能抬升档位。
     */
    fun displayTier(candidate: ObservationCandidate): Int = when {
        candidate.stage == ObservationStage.ELIMINATED -> 3
        isDeferredOrRisk(candidate) -> 3
        candidate.stage == ObservationStage.BUY_CANDIDATE -> 0
        candidate.stage == ObservationStage.NEAR_ENTRY -> 1
        else -> 2
    }

    /**
     * 历史确认过买点，或当前结构明确且技术评分较强的待确认标的，优先复评。
     * 该标记只影响展示次序，不能绕过当日行情、交易守卫、状态机和通知门控。
     */
    fun isPriorityReentry(candidate: ObservationCandidate): Boolean =
        candidate.stage == ObservationStage.NEAR_ENTRY &&
            candidate.structureQuality != ObservationStructureQuality.NONE &&
            (
                (candidate.highestStage ?: candidate.stage) == ObservationStage.BUY_CANDIDATE ||
                    isHighQualityNearEntry(candidate)
            ) &&
            !isDeferredOrRisk(candidate)

    /** 与“较强待确认”技术档同口径，只用于待确认中的展示优先级。 */
    private fun isHighQualityNearEntry(candidate: ObservationCandidate): Boolean =
        candidate.structureQuality != ObservationStructureQuality.NONE &&
            candidate.riskPolicyVersion >= ObservationPool.CURRENT_RISK_POLICY_VERSION &&
            (candidate.technicalScore ?: 0) >= 65 &&
            candidate.riskSignals.isEmpty() &&
            candidate.marketRisk != ObservationMarketRisk.BLOCKED

    /** 优先复评机会在没有当日行情和当日复评前，只能提示，不能视为可执行机会。 */
    fun priorityReentryAwaitingFreshEvaluation(candidate: ObservationCandidate, quote: Quote?): Boolean =
        isPriorityReentry(candidate) &&
            (candidate.lastEvaluatedDate != TimeUtils.todayText() || quote?.quoteDate != TimeUtils.todayText())

    fun prioritizedSections(
        candidates: List<ObservationCandidate>,
        quotes: Map<String, Quote>,
    ): PrioritizedObservationSections {
        val sections = sections(candidates, quotes)
        val buy = sections.getValue(ObservationSection.BUY)
        val near = sections.getValue(ObservationSection.NEAR)
        val observing = sections.getValue(ObservationSection.OBSERVING)
        val deferred = sections.getValue(ObservationSection.LIMIT_CONTINUOUS) +
            sections.getValue(ObservationSection.DEFERRED)
        val all = buy + near + observing + deferred
        val temporarilyDeferred = all.filter {
            !isRiskObservation(it) && isTemporaryDeferral(it)
        }
        val risk = all.filter(::isRiskObservation)
        val ordinaryBuy = buy.filterNot { isRiskObservation(it) || isTemporaryDeferral(it) }
        val ordinaryNear = near.filterNot { isRiskObservation(it) || isTemporaryDeferral(it) }
        val priorityReentry = ordinaryNear.filter(::isPriorityReentry)
        val regularNear = ordinaryNear.filterNot(::isPriorityReentry)
        val ordinaryObserving = observing.filterNot { isRiskObservation(it) || isTemporaryDeferral(it) }
        return PrioritizedObservationSections(
            buy = ordinaryBuy,
            priorityReentry = priorityReentry,
            near = regularNear,
            observing = ordinaryObserving,
            temporarilyDeferred = temporarilyDeferred,
            risk = risk,
        )
    }

    /** 第四、五档均不能参与：暂缓是执行条件未满足，风险是结构或环境已不安全。 */
    fun isDeferredOrRisk(candidate: ObservationCandidate): Boolean =
        isRiskObservation(candidate) || isTemporaryDeferral(candidate)

    /** 第四档是暂缓，不等同个股风险：封板、数据缺失和市场整体阻断均在此等待恢复。 */
    private fun isTemporaryDeferral(candidate: ObservationCandidate): Boolean {
        val reason = displayReason(candidate)
        return candidate.marketRisk == ObservationMarketRisk.BLOCKED ||
            candidate.limitUpBlocked ||
            reason.contains("封板") ||
            reason.contains("数据缺失") ||
            reason.contains("暂缓")
    }

    /** 风险观察只保留可落到具体原因上的个股风险，市场整体阻断归入“暂缓参与”。 */
    private fun isRiskObservation(candidate: ObservationCandidate): Boolean =
        riskEvidence(candidate).isNotEmpty()

    /**
     * 风险摘要只返回有证据的具体原因。旧缓存没有结构化信号时，也只接受明确的风险关键词；
     * 泛化的状态词、走弱描述或不晋级原因不能单独把股票放进风险组。
     */
    fun riskSummary(
        candidate: ObservationCandidate,
        currentFlow: FundFlow? = null,
        flowHistory: List<FundFlow> = emptyList(),
    ): String? {
        val evidence = riskEvidence(candidate)
        if (evidence.isEmpty()) return null
        return evidence.take(2).joinToString("；") { risk ->
            val detail = if (risk.type == ObservationRiskType.FUND_FLOW_OUTFLOW) {
                enrichFundFlowEvidence(risk.detail, currentFlow, flowHistory)
            } else {
                risk.detail
            }
            if (detail.isBlank()) risk.type.label else "${risk.type.label}：$detail"
        }
    }

    /**
     * 规则升级后，旧缓存仍可能带着上一版趋势结论。展示层不把旧“大趋势向下/周月偏弱”
     * 当成当前事实，等下一次完整复评再生成新的阶段原因。
     */
    fun displayReason(candidate: ObservationCandidate): String {
        if (candidate.riskPolicyVersion >= ObservationPool.CURRENT_RISK_POLICY_VERSION) {
            return candidate.reason
        }
        val hadLegacyRisk = candidate.riskSignals.isNotEmpty() ||
            candidate.breakoutRisk?.risk == true ||
            candidate.limitUpShrinkAcceleration ||
            LEGACY_TREND_REASON_TOKENS.any(candidate.reason::contains)
        return if (hadLegacyRisk) "风险规则已更新，等待当日复评" else candidate.reason
    }

    private data class RiskEvidence(val type: ObservationRiskType, val detail: String)

    /**
     * 结构化风险信号是唯一权威来源。这里保留少量明确的旧文案兼容，但不把状态机泛化原因当风险。
     */
    private fun riskEvidence(candidate: ObservationCandidate): List<RiskEvidence> = buildList {
        if (candidate.riskPolicyVersion < ObservationPool.CURRENT_RISK_POLICY_VERSION) {
            return@buildList
        }
        candidate.riskSignals.forEach { signal ->
            add(RiskEvidence(signal.type, signal.detail.trim()))
        }
        if (none { it.type == ObservationRiskType.BREAKOUT_FAILURE }) {
            candidate.breakoutRisk?.takeIf { it.risk }?.let { risk ->
                add(
                    RiskEvidence(
                        ObservationRiskType.BREAKOUT_FAILURE,
                        risk.reason.ifBlank { "价格跌破突破参考位，突破结构失效" },
                    ),
                )
            }
        }
        if (candidate.limitUpShrinkAcceleration &&
            none { it.type == ObservationRiskType.VOLUME_SHRINK_ACCELERATION }
        ) {
            add(
                RiskEvidence(
                    ObservationRiskType.VOLUME_SHRINK_ACCELERATION,
                    "涨停封板但成交量不足，等待放量换手后重新确认",
                ),
            )
        }
        if (none { it.type == ObservationRiskType.TREND_WEAK }) {
            candidate.evidence.counter.firstOrNull { it.contains("趋势走弱") }
                ?.let { add(RiskEvidence(ObservationRiskType.TREND_WEAK, it)) }
            appendLegacyReasonEvidence(
                candidate.reason,
                ObservationRiskType.TREND_WEAK,
                listOf("趋势向下", "趋势走弱"),
            )
        }
        if (none { it.type == ObservationRiskType.HIGH_POSITION_DISTRIBUTION }) {
            appendLegacyReasonEvidence(
                candidate.reason,
                ObservationRiskType.HIGH_POSITION_DISTRIBUTION,
                listOf("高位派发"),
            )
        }
        if (none { it.type == ObservationRiskType.PRICE_VOLUME_DIVERGENCE }) {
            if (candidate.reason.contains("量价背离")) {
                appendLegacyReasonEvidence(
                    candidate.reason,
                    ObservationRiskType.PRICE_VOLUME_DIVERGENCE,
                    listOf("量价背离"),
                )
            } else {
                candidate.evidence.counter.firstOrNull { it.contains("量价背离") }
                    ?.let { add(RiskEvidence(ObservationRiskType.PRICE_VOLUME_DIVERGENCE, it)) }
            }
        }
        if (none { it.type == ObservationRiskType.FUND_FLOW_OUTFLOW }) {
            appendLegacyReasonEvidence(
                candidate.reason,
                ObservationRiskType.FUND_FLOW_OUTFLOW,
                listOf("持续流出", "资金背离"),
            )
        }
        if (none { it.type == ObservationRiskType.BREAKOUT_FAILURE }) {
            appendLegacyReasonEvidence(
                candidate.reason,
                ObservationRiskType.BREAKOUT_FAILURE,
                listOf("突破下杀"),
            )
        }
        if (none { it.type == ObservationRiskType.STRUCTURE_BREAKDOWN }) {
            appendLegacyReasonEvidence(
                candidate.reason,
                ObservationRiskType.STRUCTURE_BREAKDOWN,
                listOf("结构破位"),
            )
        }
    }.distinctBy { it.type to it.detail }

    private fun MutableList<RiskEvidence>.appendLegacyReasonEvidence(
        reason: String,
        type: ObservationRiskType,
        tokens: List<String>,
    ) {
        val token = tokens.firstOrNull(reason::contains) ?: return
        val fragment = reason
            .split('｜', '；', '，', '。')
            .map(String::trim)
            .firstOrNull { it.contains(token) }
            .orEmpty()
            .ifBlank { token }
        add(RiskEvidence(type, fragment))
    }

    private fun enrichFundFlowEvidence(
        detail: String,
        currentFlow: FundFlow?,
        flowHistory: List<FundFlow>,
    ): String {
        val concrete = FundFlowDisplayPolicy.riskEvidence(currentFlow, flowHistory) ?: return detail
        return when {
            detail.contains("今日主力资金") || detail.contains("近3日主力资金") -> detail
            detail.isBlank() -> concrete
            else -> "$concrete；$detail"
        }
    }

    fun page(items: List<ObservationCandidate>, index: Int): List<ObservationCandidate> =
        items.drop(index.coerceAtLeast(0) * PAGE_SIZE).take(PAGE_SIZE)

    private val LEGACY_TREND_REASON_TOKENS = listOf(
        "大趋势向下",
        "趋势向下",
        "趋势走弱",
        "按已完成周K/月K",
    )

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
            source = representative.source,
            sources = sameStock.flatMap { it.allSources }.toCollection(linkedSetOf()),
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

    /**
     * 快扫只刷新指标两档，必须保留上一轮趋势/513 来源；深扫覆盖全市场，才按最新结果替换形态来源。
     */
    fun mergeScreenSources(
        current: Collection<String>,
        previous: Collection<String>,
        includePatterns: Boolean,
    ): Set<String> {
        val merged = current.map(String::trim).filter(String::isNotBlank).toCollection(linkedSetOf())
        if (!includePatterns) {
            previous.filter { it == TREND_SOURCE || it == PATTERN_SOURCE }.forEach(merged::add)
        }
        return merged
    }

    fun entrySections(
        candidates: List<ObservationCandidate>,
        quotes: Map<String, Quote>,
    ): Map<ObservationSection, List<ObservationCandidate>> =
        sections(candidates, quotes).filterKeys {
            it == ObservationSection.BUY || it == ObservationSection.NEAR ||
                it == ObservationSection.LIMIT_CONTINUOUS
        }.filterValues { it.isNotEmpty() }

    private fun deduplicateByStrategy(candidates: List<ObservationCandidate>): List<ObservationCandidate> =
        candidates.groupBy { it.code to it.strategy }.values.flatMap(::deduplicateCandidates)

    /**
     * 内部状态按“代码 + 策略”独立保存；UI 每只股票只显示一行。
     * 选择可参与的非暂缓路线优先，封板/缩量路线不能覆盖同代码的有效启动路线。
     */
    fun forDisplay(candidates: List<ObservationCandidate>, quotes: Map<String, Quote>): List<ObservationCandidate> {
        val routed = deduplicateByStrategy(candidates).filter { it.stage != ObservationStage.ELIMINATED }
            .map { applyQuoteGate(it, quotes[it.code]) }
        return routed.groupBy { it.code }.mapNotNull { (_, routes) ->
            val selectable = routes.filterNot { isLimitUpDeferred(it, quotes[it.code]) }
                .ifEmpty { routes }
            ObservationDisplayRankingPolicy.rank(selectable, ::displayTier).firstOrNull()
        }.let { displayed ->
            ObservationDisplayRankingPolicy.rank(displayed, ::displayTier)
        }
    }
}
