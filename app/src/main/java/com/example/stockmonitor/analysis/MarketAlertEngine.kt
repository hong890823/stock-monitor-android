package com.example.stockmonitor.analysis

import java.util.Locale

enum class MarketAlertLevel(val priority: Int) {
    WARNING(1),
    DANGER(2),
    CRASH(3),
}

enum class MarketAlertScope(val priority: Int) {
    INDEX(1),
    MARKET(2),
    SYSTEMIC(3),
}

/**
 * 市场风险引擎。
 *
 * 5 分钟速度证据只接受已完成、不重叠的 K 线；单指数转弱只观察，不返回通知。
 * 指数风险、市场风险和系统性风险分开命名，避免把创业板等单一指数异动叫成市场风险。
 */
/** 同级风险提醒的冷却窗：满 60 分钟后允许同级再报（升级仍即时，见 shouldEmit）。 */
private const val SAME_LEVEL_REARM_MS = 60L * 60_000L
/** 仅用最新完成5分钟柱一致的指数做速度组合；落后缓存不能拉低其它指数的处理时点。 */

class MarketAlertEngine {
    data class IndexSnapshot(
        val code: String,
        val name: String,
        val price: Double,
        val changePct: Double,
        val turnover: Double,
        val timestamp: Long,
        val turnoverRatio: Double? = null,
        /** 近20个已完成交易日的稳健日波动率（百分点）；缺失时策略退回指数最低容差。 */
        val dailyVolatilityPct: Double? = null,
        /** 高开低走判定所需；旧调用方不传时保持 null，不改变原预警行为。 */
        val open: Double? = null,
        val previousClose: Double? = null,
        val high: Double? = null,
        val sessionMinutes: Int? = null,
    )

    data class IndexBar(
        val code: String,
        val name: String,
        val timestamp: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    ) {
        val valid: Boolean
            get() =
                code.isNotBlank() &&
                    timestamp > 0L &&
                    listOf(open, high, low, close, volume).all { it.isFinite() } &&
                    open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0 && volume >= 0.0 &&
                    high >= maxOf(open, low, close) &&
                    low <= minOf(open, high, close)

        val midpoint: Double get() = (high + low) / 2.0
    }

    data class MarketBreadth(
        val industryRatio: Double? = null,
        val conceptRatio: Double? = null,
    ) {
        private val complete =
            industryRatio != null && conceptRatio != null &&
                industryRatio.isFinite() && conceptRatio.isFinite() &&
                industryRatio in 0.0..1.0 && conceptRatio in 0.0..1.0

        val weak: Boolean
            get() = complete && maxOf(industryRatio!!, conceptRatio!!) <= MarketRiskPolicy.WEAK_BREADTH

        val veryWeak: Boolean
            get() = complete && maxOf(industryRatio!!, conceptRatio!!) <= MarketRiskPolicy.VERY_WEAK_BREADTH

        val strong: Boolean
            get() = complete && minOf(industryRatio!!, conceptRatio!!) >= MarketRiskPolicy.STRONG_BREADTH

        fun summary(): String =
            if (complete) {
                String.format(
                    Locale.CHINA,
                    "行业上涨占比 %.0f%%、概念上涨占比 %.0f%%",
                    industryRatio!! * 100,
                    conceptRatio!! * 100,
                )
            } else {
                "市场宽度暂缺"
            }
    }

    data class Alert(
        val level: MarketAlertLevel,
        val scope: MarketAlertScope,
        val type: String,
        val title: String,
        val body: String,
        val vibrate: Boolean,
    )

    private data class Candidate(
        val level: MarketAlertLevel,
        val scope: MarketAlertScope,
        val type: String,
        val reason: String,
        val indexName: String? = null,
        val code: String? = null,
    )

    private var tradingDay: String? = null
    /** 已评估的分钟组合批次，包含时间与参与指数集合；同窗迟到指数补齐时允许重新判断组合。 */
    private var lastSpeedBatchKey: String? = null
    private var emittedLevel: MarketAlertLevel? = null
    private var emittedScope: MarketAlertScope? = null
    private var lastEmitAtMs: Long = 0L
    /** 每只指数当日只报一次高开低走，避免同一走势反复提醒。 */
    private val gapFadeEmitted = mutableSetOf<String>()

    fun update(
        snapshots: List<IndexSnapshot>,
        bars: Map<String, List<IndexBar>>,
        day: String,
        breadth: MarketBreadth = MarketBreadth(),
    ): List<Alert> {
        if (tradingDay != day) {
            reset()
            tradingDay = day
        }
        val current = snapshots.filter { it.price > 0.0 }.distinctBy { it.code }
        val preparedBars =
            bars.mapValues { (_, values) ->
                values.filter { it.valid }.distinctBy { it.timestamp }.sortedBy { it.timestamp }
            }.filterValues { it.isNotEmpty() }
        // 某一指数分钟线短暂滞后时，不让它冻结其它至少两条指数的新速度证据。
        val latestKnownTimestamp = preparedBars.values.mapNotNull { it.lastOrNull()?.timestamp }.maxOrNull()
        val marketSpeedBars = latestKnownTimestamp?.let { latest ->
            preparedBars.filterValues { values -> values.lastOrNull()?.timestamp == latest }
        }.orEmpty()
        val newestBarTimestamp = latestKnownTimestamp?.takeIf { marketSpeedBars.size >= 2 }
        val speedBatchKey = newestBarTimestamp?.let { timestamp ->
            "$timestamp:${marketSpeedBars.keys.sorted().joinToString(",")}"
        }
        val newBarBatch = speedBatchKey != null && speedBatchKey != lastSpeedBatchKey
        if (speedBatchKey != null) lastSpeedBatchKey = speedBatchKey

        val daily = dailyCandidate(current, breadth)
        val gapFade = gapFadeCandidate(current)
        val speed = newestBarTimestamp
            ?.takeIf { newBarBatch }
            ?.let { speedCandidate(marketSpeedBars, breadth, it) }
        val candidate = listOfNotNull(daily, gapFade, speed).maxWithOrNull(candidateComparator())
        if (candidate == null) {
            if (isRearmed(current)) resetState()
            return emptyList()
        }
        // 日线候选只依赖实时快照，冷却也必须使用同一批行情的时间；不能因5分钟K线暂缺而永不重报。
        val notificationTimestamp = current.maxOfOrNull { it.timestamp }
        if (!shouldEmit(candidate, notificationTimestamp)) return emptyList()
        candidate.code?.takeIf { candidate.type.startsWith("index_gap_fade") }?.let { gapFadeEmitted.add(it) }
        emittedLevel = candidate.level
        emittedScope = candidate.scope
        notificationTimestamp?.let { lastEmitAtMs = it }
        return listOf(buildAlert(current, breadth, candidate))
    }

    fun reset() {
        tradingDay = null
        lastSpeedBatchKey = null
        gapFadeEmitted.clear()
        resetState()
    }

    private fun resetState() {
        emittedLevel = null
        emittedScope = null
        lastEmitAtMs = 0L
    }

    /**
     * 指数级高开低走：高开 → 跌破开盘价 → 从日高明显回落。
     *
     * 与绝对跌幅/5分钟急跌相互独立：高开低走常发生在指数仍红盘时，
     * 只盯跌幅阈值会整段静默。每只指数当日只报一次。
     */
    private fun gapFadeCandidate(snapshots: List<IndexSnapshot>): Candidate? {
        for (snapshot in snapshots) {
            if (snapshot.code in gapFadeEmitted) continue
            val finding = GapFadePolicy.evaluate(
                openPrice = snapshot.open,
                previousClose = snapshot.previousClose,
                highPrice = snapshot.high,
                price = snapshot.price,
                sessionMinutes = snapshot.sessionMinutes,
                holding = false,
            ) ?: continue
            return Candidate(
                level = if (finding.severity == "danger") MarketAlertLevel.DANGER else MarketAlertLevel.WARNING,
                scope = MarketAlertScope.INDEX,
                type = "index_gap_fade:${snapshot.code}",
                reason = GapFadePolicy.indexReason(finding),
                indexName = snapshot.name,
                code = snapshot.code,
            )
        }
        return null
    }

    private fun dailyCandidate(
        snapshots: List<IndexSnapshot>,
        breadth: MarketBreadth,
    ): Candidate? {
        val risks = snapshots.filter { snapshot ->
            snapshot.changePct <= -MarketRiskPolicy.dailyRiskThresholdPct(
                snapshot.code,
                snapshot.dailyVolatilityPct,
            )
        }
        val severe = snapshots.filter { snapshot ->
            snapshot.changePct <= -MarketRiskPolicy.dailySevereThresholdPct(
                snapshot.code,
                snapshot.dailyVolatilityPct,
            )
        }
        val coreCodes = setOf(MarketRiskPolicy.SHANGHAI_CODE, MarketRiskPolicy.SHENZHEN_CODE)
        val growthCodes = setOf(MarketRiskPolicy.CHINEXT_CODE, MarketRiskPolicy.SCI_TECH_50_CODE)
        val coreRisk = risks.filter { it.code in coreCodes }
        val growthRisk = risks.filter { it.code in growthCodes }
        val individual = risks.maxByOrNull { snapshot ->
            -snapshot.changePct / MarketRiskPolicy.dailyRiskThresholdPct(
                snapshot.code,
                snapshot.dailyVolatilityPct,
            )
        }
        return when {
            severe.size >= 3 ->
                Candidate(
                    MarketAlertLevel.CRASH,
                    MarketAlertScope.SYSTEMIC,
                    "systemic_dynamic_day",
                    "三个及以上核心/成长指数跌幅超过各自近期正常波动的严重区间；市场宽度作为辅助证据，不阻断系统性风险升级",
                )
            coreRisk.isNotEmpty() && growthRisk.isNotEmpty() ->
                Candidate(
                    MarketAlertLevel.DANGER,
                    MarketAlertScope.MARKET,
                    "cross_style_dynamic_day",
                    "核心宽基与成长风格指数同步跌入各自异常区间，风险跨风格扩散",
                )
            coreRisk.size >= 2 ->
                Candidate(
                    MarketAlertLevel.DANGER,
                    MarketAlertScope.MARKET,
                    "core_market_dynamic_day",
                    "上证与深成指同步跌入各自异常区间，核心宽基市场走弱",
                )
            growthRisk.size >= 2 ->
                Candidate(
                    MarketAlertLevel.DANGER,
                    MarketAlertScope.MARKET,
                    "growth_style_dynamic_day",
                    "创业板与科创50同步跌入各自异常区间，成长风格风险提升",
                )
            individual != null -> {
                val threshold = MarketRiskPolicy.dailyRiskThresholdPct(
                    individual.code,
                    individual.dailyVolatilityPct,
                )
                Candidate(
                    MarketAlertLevel.DANGER,
                    MarketAlertScope.INDEX,
                    "index_dynamic_day:${individual.code}",
                    String.format(
                        Locale.CHINA,
                        "%s 日跌幅 %+.2f%%，超过近期20日正常波动风险线 %.2f%%",
                        individual.name,
                        individual.changePct,
                        threshold,
                    ),
                    indexName = individual.name,
                )
            }
            else -> null
        }
    }

    private fun speedCandidate(
        bars: Map<String, List<IndexBar>>,
        breadth: MarketBreadth,
        completedTimestamp: Long,
    ): Candidate? {
        if (bars.size < 2) return null
        val evidence =
            bars.mapNotNull { (code, values) ->
                speedEvidence(code, values, completedTimestamp)
            }
                .associateBy { it.code }
        val synchronizedShocks =
            evidence.values
                .groupBy { it.shockTimestamp }
                .values
                .map { group -> group.filter { it.shockReturnPct <= -it.riskThresholdPct } }
                .filter { it.size >= 2 }
        val synchronizedSevereShocks =
            evidence.values
                .groupBy { it.shockTimestamp }
                .values
                .map { group -> group.filter { it.shockReturnPct <= -it.severeThresholdPct } }
                .filter { it.size >= 2 }
        val currentRisk =
            evidence.values.count { it.currentReturnPct <= -it.riskThresholdPct }
        val shockRisk = synchronizedShocks.sumOf { it.size }
        val shockSevere = synchronizedSevereShocks.sumOf { it.size }
        val severeFollowThrough =
            synchronizedSevereShocks
                .filter { group ->
                    group.all { it.shockIsPrevious && it.followThrough && !it.shockAbsorbed }
                }
                .sumOf { it.size }
        val volumeFollowThrough =
            evidence.values.filter {
                it.shockReturnPct <= -it.riskThresholdPct &&
                    it.shockIsPrevious &&
                    it.shockVolumeRatio >= MarketRiskPolicy.EXPANDED_VOLUME_RATIO &&
                    it.followThrough &&
                    !it.shockAbsorbed
            }

        if (shockSevere >= 2 && severeFollowThrough >= 2) {
            return Candidate(
                MarketAlertLevel.CRASH,
                MarketAlertScope.SYSTEMIC,
                "systemic_speed",
                "多个指数同一5分钟窗口急跌超过 -0.8%，下一根继续恶化",
            )
        }
        if ((currentRisk >= 2 || shockRisk >= 2) && breadth.weak) {
            return Candidate(
                MarketAlertLevel.DANGER,
                MarketAlertScope.MARKET,
                "market_speed",
                "两个指数在同一5分钟窗口跌幅达到风险阈值，且市场宽度偏弱",
            )
        }

        val indexEvidence =
            evidence.values.firstOrNull {
                (it.shockIsPrevious &&
                        it.shockReturnPct <= -it.severeThresholdPct &&
                        it.followThrough &&
                        !it.shockAbsorbed) ||
                    (it.shockIsPrevious &&
                        it.shockReturnPct <= -it.riskThresholdPct &&
                        it.shockVolumeRatio >= MarketRiskPolicy.EXPANDED_VOLUME_RATIO &&
                        it.followThrough &&
                        !it.shockAbsorbed)
            } ?: volumeFollowThrough.firstOrNull()
        return indexEvidence?.let {
            Candidate(
                level = MarketAlertLevel.DANGER,
                scope = MarketAlertScope.INDEX,
                type = "index_follow_through",
                reason =
                    if (it.shockVolumeRatio >= MarketRiskPolicy.EXPANDED_VOLUME_RATIO && it.followThrough) {
                        "放量下跌后下一根仍未止跌或收复中点"
                    } else {
                        "短时急跌后下一根继续走弱"
                    },
                indexName = it.name,
            )
        }
    }

    private data class SpeedEvidence(
        val code: String,
        val name: String,
        val currentReturnPct: Double,
        val shockReturnPct: Double,
        val shockTimestamp: Long,
        val shockIsPrevious: Boolean,
        val shockVolumeRatio: Double,
        val followThrough: Boolean,
        val shockAbsorbed: Boolean,
        val riskThresholdPct: Double,
        val severeThresholdPct: Double,
    )

    private fun speedEvidence(
        code: String,
        bars: List<IndexBar>,
        completedTimestamp: Long,
    ): SpeedEvidence? {
        val currentIndex = bars.indexOfLast { it.timestamp <= completedTimestamp }
        if (currentIndex <= 0) return null
        val previous = bars[currentIndex - 1]
        val current = bars[currentIndex]
        val completedBars = bars.subList(0, currentIndex + 1)
        val returns =
            completedBars.zipWithNext { previous, next -> percentChange(previous.close, next.close) }
        val multiplier = MarketRiskPolicy.volatilityMultiplier(code, returns.dropLast(2))
        val currentReturnPct = percentChange(previous.close, current.close)
        val previousReturnPct =
            if (currentIndex >= 2) {
                percentChange(bars[currentIndex - 2].close, previous.close)
            } else {
                0.0
            }
        val riskThresholdPct = MarketRiskPolicy.RISK_SPEED_PCT * multiplier
        val currentIsShock = currentReturnPct <= -riskThresholdPct
        val shock = if (currentIsShock) current else previous
        val shockIsPrevious = !currentIsShock
        val shockRange = shock.high - shock.low
        val lowerShadowRatio =
            if (shockRange > 0.0) {
                (minOf(shock.open, shock.close) - shock.low) / shockRange
            } else {
                0.0
            }
        return SpeedEvidence(
            code = code,
            name = current.name,
            currentReturnPct = currentReturnPct,
            shockReturnPct = if (currentIsShock) currentReturnPct else previousReturnPct,
            shockTimestamp = shock.timestamp,
            shockIsPrevious = shockIsPrevious,
            shockVolumeRatio =
                volumeRatioAt(
                    completedBars,
                    if (currentIsShock) completedBars.lastIndex else completedBars.lastIndex - 1,
                ),
            followThrough =
                shockIsPrevious &&
                    currentIndex >= 2 &&
                    current.close < previous.close &&
                    current.close < previous.midpoint &&
                    currentReturnPct < 0.0,
            shockAbsorbed =
                lowerShadowRatio >= MarketRiskPolicy.ABSORPTION_LOWER_SHADOW_RATIO &&
                    shock.close >= shock.midpoint,
            riskThresholdPct = riskThresholdPct,
            severeThresholdPct = MarketRiskPolicy.SEVERE_SPEED_PCT * multiplier,
        )
    }

    private fun volumeRatioAt(bars: List<IndexBar>, index: Int): Double {
        if (index <= 0) return 1.0
        val baseline = bars.subList(maxOf(0, index - 20), index).map { it.volume }.filter { it > 0.0 }
        val median = baseline.sorted().let { values ->
            if (values.isEmpty()) return 1.0
            if (values.size % 2 == 1) values[values.size / 2]
            else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
        }
        return if (median > 0.0) bars[index].volume / median else 1.0
    }

    private fun shouldEmit(candidate: Candidate, nowMs: Long?): Boolean {
        val previousLevel = emittedLevel ?: return true
        if (candidate.level.priority > previousLevel.priority) return true
        if (candidate.level.priority < previousLevel.priority) return false
        val previousScope = emittedScope ?: return true
        if (candidate.scope.priority > previousScope.priority) return true
        // 同级冷却：原本同级当日只报一次，且 rearm 只在风险完全消退时解锁——弱势持续
        // 一整天时（daily candidate 一直非 null，isRearmed 永不执行），下午的同级二次
        // 急跌会永远静默。距上次同级提醒满 60 分钟即允许再报；升级（更高级别/范围）仍即时。
        val now = nowMs ?: return false
        if (lastEmitAtMs <= 0L) return true
        return now - lastEmitAtMs >= SAME_LEVEL_REARM_MS
    }

    private fun isRearmed(snapshots: List<IndexSnapshot>): Boolean =
        snapshots.size >= 3 &&
            snapshots.all { it.changePct > -MarketRiskPolicy.REARM_PCT }

    private fun candidateComparator(): Comparator<Candidate> =
        compareBy<Candidate> { it.level.priority }
            .thenBy { it.scope.priority }

    private fun buildAlert(
        snapshots: List<IndexSnapshot>,
        breadth: MarketBreadth,
        candidate: Candidate,
    ): Alert {
        val indexText = snapshots.joinToString("，") {
            String.format(Locale.CHINA, "%s %+.2f%%", it.name, it.changePct)
        }
        val isGapFade = candidate.type.startsWith("index_gap_fade")
        val title = if (isGapFade) {
            "📉 ${candidate.indexName ?: "指数"}高开低走"
        } else {
            when (candidate.scope) {
                MarketAlertScope.INDEX -> "⚠️ ${candidate.indexName ?: "单一指数"}风险确认"
                MarketAlertScope.MARKET -> "🚨 市场风险预警"
                MarketAlertScope.SYSTEMIC -> "💥 系统性风险预警"
            }
        }
        val body =
            "$indexText；${candidate.reason}；${breadth.summary()}。" +
                if (isGapFade) "注意指数回落向持仓传导；不追高、不在下跌途中补仓。" else "请注意控制仓位。"
        return Alert(
            level = candidate.level,
            scope = candidate.scope,
            type = candidate.type,
            title = title,
            body = body,
            vibrate = candidate.level.priority >= MarketAlertLevel.DANGER.priority,
        )
    }

    private fun percentChange(previous: Double, current: Double): Double =
        if (previous > 0.0 && current.isFinite()) (current - previous) / previous * 100.0 else 0.0
}
