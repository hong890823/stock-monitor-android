package com.example.stockmonitor.analysis

import java.util.Locale

enum class MarketAlertLevel(val priority: Int) {
    WARNING(1),
    DANGER(2),
    CRASH(3),
}

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

    data class Alert(
        val level: MarketAlertLevel,
        val type: String,
        val title: String,
        val body: String,
        val vibrate: Boolean,
    )

    private var tradingDay: String? = null
    private var weaknessRounds = 0
    private var speedWarningRounds = 0
    private var emittedLevel: MarketAlertLevel? = null
    private val history = mutableMapOf<String, MutableList<IndexSnapshot>>()

    private data class SpeedAssessment(
        val level: MarketAlertLevel?,
        val details: List<String>,
    )

    fun update(snapshots: List<IndexSnapshot>, day: String): List<Alert> {
        if (tradingDay != day) {
            reset()
            tradingDay = day
        }
        val current = snapshots.filter { it.price > 0.0 }.distinctBy { it.code }
        if (current.isEmpty()) return emptyList()
        appendHistory(current)
        val speed = assessSpeed(current)
        if (current.all { it.changePct > -MarketRiskPolicy.REARM_PCT } && speed.level == null) {
            resetState()
            return emptyList()
        }

        val earlyTriggered = current.count { it.changePct <= -MarketRiskPolicy.EARLY_WARNING_PCT } >= 2
        weaknessRounds = if (earlyTriggered) weaknessRounds + 1 else 0
        speedWarningRounds = if (speed.level == MarketAlertLevel.WARNING) speedWarningRounds + 1 else 0

        val correlation = current.count { it.changePct <= -MarketRiskPolicy.EARLY_WARNING_PCT } >= 2 &&
            current.map { it.changePct }.average() <= -0.7
        val absoluteCandidate = when {
            current.any { it.changePct <= -MarketRiskPolicy.SEVERE_WARNING_PCT } -> MarketAlertLevel.CRASH
            current.any { it.changePct <= -MarketRiskPolicy.RISK_WARNING_PCT } -> MarketAlertLevel.DANGER
            weaknessRounds >= 2 -> MarketAlertLevel.WARNING
            else -> null
        }
        val confirmedSpeedCandidate = when (speed.level) {
            MarketAlertLevel.CRASH, MarketAlertLevel.DANGER -> speed.level
            MarketAlertLevel.WARNING -> MarketAlertLevel.WARNING.takeIf { speedWarningRounds >= 2 }
            null -> null
        }
        val candidate = listOfNotNull(absoluteCandidate, confirmedSpeedCandidate)
            .maxByOrNull { it.priority }
            ?: return emptyList()

        val previous = emittedLevel
        if (previous != null && candidate.priority <= previous.priority) return emptyList()
        emittedLevel = candidate
        return listOf(buildAlert(current, candidate, correlation, speed.details))
    }

    fun reset() {
        tradingDay = null
        resetState()
        history.clear()
    }

    private fun resetState() {
        weaknessRounds = 0
        speedWarningRounds = 0
        emittedLevel = null
    }

    private fun appendHistory(current: List<IndexSnapshot>) {
        val newest = current.maxOf { it.timestamp }
        current.forEach { snapshot ->
            val samples = history.getOrPut(snapshot.code) { mutableListOf() }
            samples.removeAll { it.timestamp >= snapshot.timestamp }
            samples += snapshot
            samples.removeAll { newest - it.timestamp > HISTORY_WINDOW_MS }
        }
    }

    private fun assessSpeed(current: List<IndexSnapshot>): SpeedAssessment {
        val details = mutableListOf<String>()
        var highest: MarketAlertLevel? = null
        current.forEach { snapshot ->
            val target = snapshot.timestamp - SPEED_WINDOW_MS
            val reference = history[snapshot.code]
                ?.asSequence()
                ?.filter { it.timestamp <= target && target - it.timestamp <= SPEED_TOLERANCE_MS }
                ?.maxByOrNull { it.timestamp }
                ?: return@forEach
            if (reference.price <= 0.0) return@forEach
            val change = (snapshot.price - reference.price) / reference.price * 100.0
            val level = when {
                change <= -0.8 -> MarketAlertLevel.CRASH
                change <= -0.5 -> MarketAlertLevel.DANGER
                change <= -0.3 -> MarketAlertLevel.WARNING
                else -> null
            }
            if (level != null) {
                if (highest == null || level.priority > highest!!.priority) highest = level
                details += String.format(Locale.CHINA, "%s 过去 5 分钟 %.2f%%", snapshot.name, change)
            }
        }
        return SpeedAssessment(highest, details)
    }

    private fun buildAlert(
        snapshots: List<IndexSnapshot>,
        level: MarketAlertLevel,
        correlation: Boolean,
        speedDetails: List<String>,
    ): Alert {
        val indexText = snapshots.joinToString("，") {
            String.format(Locale.CHINA, "%s %+.2f%%", it.name, it.changePct)
        }
        val fallingVolumeRatio = snapshots
            .filter { it.changePct < 0.0 }
            .mapNotNull { it.turnoverRatio }
            .maxOrNull()
        val volumeDetail = when (MarketVolumeBaseline.classify(fallingVolumeRatio)) {
            MarketVolumeLevel.ELEVATED ->
                "成交额约为近 20 日同期中位数的 ${MarketVolumeBaseline.formatRatio(fallingVolumeRatio!!)} 倍，放量下跌"
            MarketVolumeLevel.HIGH ->
                "成交额约为近 20 日同期中位数的 ${MarketVolumeBaseline.formatRatio(fallingVolumeRatio!!)} 倍，明显放量下跌"
            MarketVolumeLevel.EXTREME ->
                "成交额约为近 20 日同期中位数的 ${MarketVolumeBaseline.formatRatio(fallingVolumeRatio!!)} 倍，异常放量下跌"
            MarketVolumeLevel.NONE -> null
        }
        val type = when {
            correlation && (speedDetails.isNotEmpty() || volumeDetail != null) -> "combined"
            correlation -> "correlation"
            speedDetails.isNotEmpty() || volumeDetail != null -> "confirmed"
            level == MarketAlertLevel.CRASH -> "crash"
            else -> "absolute"
        }
        val title = when (level) {
            MarketAlertLevel.WARNING -> "📉 大盘转弱提醒"
            MarketAlertLevel.DANGER -> "🚨 大盘风险预警"
            MarketAlertLevel.CRASH -> "💥 大盘严重风险"
        }
        val details = buildList {
            add(
                when {
                    correlation -> "${snapshots.count { it.changePct <= -MarketRiskPolicy.EARLY_WARNING_PCT }} 个主要指数同步走弱"
                    level == MarketAlertLevel.CRASH -> "主要指数已进入严重下跌区间"
                    level == MarketAlertLevel.DANGER -> "主要指数已进入明显下跌区间"
                    else -> "市场出现持续走弱迹象"
                }
            )
            addAll(speedDetails)
            volumeDetail?.let(::add)
        }
        val detail = details.joinToString("；")
        return Alert(
            level = level,
            type = type,
            title = title,
            body = "$indexText；$detail，请注意控制仓位。",
            vibrate = level.priority >= MarketAlertLevel.DANGER.priority,
        )
    }

    companion object {
        private const val SPEED_WINDOW_MS = 5 * 60 * 1000L
        private const val SPEED_TOLERANCE_MS = 30 * 1000L
        private const val HISTORY_WINDOW_MS = 60 * 60 * 1000L
    }
}
