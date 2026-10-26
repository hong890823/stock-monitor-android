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
class MarketAlertEngine {
    data class IndexSnapshot(
        val code: String,
        val name: String,
        val price: Double,
        val changePct: Double,
        val turnover: Double,
        val timestamp: Long,
        val turnoverRatio: Double? = null,
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
    )

    private var tradingDay: String? = null
    private var lastBarTimestamp: Long? = null
    private var emittedLevel: MarketAlertLevel? = null
    private var emittedScope: MarketAlertScope? = null

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
        val newestBarTimestamp =
            preparedBars.values
                .mapNotNull { it.lastOrNull()?.timestamp }
                .minOrNull()
        val newBarBatch = newestBarTimestamp != null && newestBarTimestamp != lastBarTimestamp
        if (newestBarTimestamp != null) lastBarTimestamp = newestBarTimestamp

        val daily = dailyCandidate(current, breadth)
        val speed = newestBarTimestamp
            ?.takeIf { newBarBatch }
            ?.let { speedCandidate(preparedBars, breadth, it) }
        val candidate = listOfNotNull(daily, speed).maxWithOrNull(candidateComparator())
        if (candidate == null) {
            if (isRearmed(current)) resetState()
            return emptyList()
        }
        if (!shouldEmit(candidate)) return emptyList()
        emittedLevel = candidate.level
        emittedScope = candidate.scope
        return listOf(buildAlert(current, breadth, candidate))
    }

    fun reset() {
        tradingDay = null
        lastBarTimestamp = null
        resetState()
    }

    private fun resetState() {
        emittedLevel = null
        emittedScope = null
    }

    private fun dailyCandidate(
        snapshots: List<IndexSnapshot>,
        breadth: MarketBreadth,
    ): Candidate? {
        val riskCount = snapshots.count { it.changePct <= -MarketRiskPolicy.RISK_WARNING_PCT }
        val severeCount = snapshots.count { it.changePct <= -MarketRiskPolicy.SEVERE_WARNING_PCT }
        val expandedTurnover = snapshots.mapNotNull { it.turnoverRatio }.maxOrNull()
            ?.let { it >= MarketRiskPolicy.EXPANDED_VOLUME_RATIO } == true
        return when {
            severeCount >= 2 && breadth.veryWeak ->
                Candidate(
                    MarketAlertLevel.CRASH,
                    MarketAlertScope.SYSTEMIC,
                    "systemic_day",
                    "至少两个指数日跌幅达到严重风险区间，且市场宽度极弱",
                )
            riskCount >= 2 && (breadth.weak || expandedTurnover) ->
                Candidate(
                    MarketAlertLevel.DANGER,
                    MarketAlertScope.MARKET,
                    "market_day",
                    "至少两个指数日跌幅达到 -0.8%，并出现普跌或放量确认",
                )
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

    private fun shouldEmit(candidate: Candidate): Boolean {
        val previousLevel = emittedLevel ?: return true
        if (candidate.level.priority > previousLevel.priority) return true
        if (candidate.level.priority < previousLevel.priority) return false
        val previousScope = emittedScope ?: return true
        return candidate.scope.priority > previousScope.priority
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
        val title = when (candidate.scope) {
            MarketAlertScope.INDEX -> "⚠️ ${candidate.indexName ?: "单一指数"}风险确认"
            MarketAlertScope.MARKET -> "🚨 市场风险预警"
            MarketAlertScope.SYSTEMIC -> "💥 系统性风险预警"
        }
        val body =
            "$indexText；${candidate.reason}；${breadth.summary()}。请注意控制仓位。"
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
