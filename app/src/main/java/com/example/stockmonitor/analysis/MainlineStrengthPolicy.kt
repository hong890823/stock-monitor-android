package com.example.stockmonitor.analysis

enum class BoardKind {
    INDUSTRY,
    CONCEPT,
}

data class SectorStrengthPoint(
    val kind: BoardKind,
    val date: String = "current",
    val name: String,
    val code: String? = null,
    val changePct: Double,
    val changeRankPct: Double?,
    val turnoverRankPct: Double?,
    val mainNetRatioPct: Double? = null,
)

data class SectorStrengthDay(
    val date: String,
    val points: List<SectorStrengthPoint>,
)

data class MainlineEvidence(
    val isMainline: Boolean,
    val strongWatch: Boolean,
    val currentStrong: Boolean,
    val latestStrong: Boolean,
    val strongDays: Int,
    val windowDays: Int,
) {
    val label: String
        get() = if (isMainline && currentStrong) {
            "主线板块·最近5日强势${strongDays}/${windowDays}"
        } else if (isMainline && strongWatch) {
            "主线正常分歧·暂保留1个交易日｜最近5日强势${strongDays}/${windowDays}"
        } else if (currentStrong) {
            "单日强势·尚未形成主线"
        } else {
            ""
        }
}

/**
 * 主线不看单日涨幅，而看最近 5 个交易日是否反复占据前列。
 *
 * 单日强势只给低档板块分，不激活容量中军/情绪核心加分；但仍保留在强势观察档位，
 * 避免主线历史尚在积累时把已有强势候选一次性全部掉档。
 */
object MainlineStrengthPolicy {
    const val WINDOW_DAYS = 5
    const val MIN_STRONG_DAYS = 3
    const val MAX_HISTORY_DAYS = 10
    private const val MIN_CHANGE_PCT = 1.0
    private const val TOP_RANK_PCT = 0.25
    private const val BROKEN_CHANGE_PCT = -2.0
    private const val BROKEN_RANK_PCT = 0.75
    private const val BROKEN_OUTFLOW_PCT = -3.0

    fun isStrongDay(point: SectorStrengthPoint): Boolean {
        val rankedStrong = finiteAtMost(point.changeRankPct, TOP_RANK_PCT)
        val corroborated = finiteAtMost(point.turnoverRankPct, TOP_RANK_PCT) ||
            finiteGreaterThan(point.mainNetRatioPct, 0.0)
        return finiteAtLeast(point.changePct, MIN_CHANGE_PCT) && rankedStrong && corroborated
    }

    fun upsert(history: List<SectorStrengthDay>, incoming: SectorStrengthDay): List<SectorStrengthDay> =
        (history.filterNot { it.date == incoming.date } + incoming)
            .sortedBy { it.date }
            .takeLast(MAX_HISTORY_DAYS)

    fun assess(
        history: List<SectorStrengthDay>,
        current: SectorStrengthPoint,
        windowSize: Int = WINDOW_DAYS,
        minimumStrongDays: Int = MIN_STRONG_DAYS,
    ): MainlineEvidence {
        val normalized = upsert(
            history,
            SectorStrengthDay(
                date = current.dateOrLatest(history),
                points = listOf(current),
            ),
        )
        val matching = normalized
            .takeLast(windowSize.coerceAtLeast(1))
            .mapNotNull { day ->
                day.points.firstOrNull {
                    it.kind == current.kind && StrongSectorRankingPolicy.boardNamesMatch(it.name, current.name)
                }
            }
        val statuses = matching.map(::isStrongDay)
        val currentStrong = isStrongDay(current)
        val latestStrong = statuses.lastOrNull() ?: currentStrong
        val strongDays = statuses.count { it }
        val activeMainline = currentStrong && latestStrong && strongDays >= minimumStrongDays
        val normalDivergence = !currentStrong &&
            !isBrokenDay(current) &&
            wasMainlineBeforeCurrent(normalized, current, windowSize, minimumStrongDays)
        val isMainline = activeMainline || normalDivergence
        return MainlineEvidence(
            isMainline = isMainline,
            strongWatch = activeMainline || normalDivergence,
            currentStrong = currentStrong,
            latestStrong = latestStrong,
            strongDays = strongDays,
            windowDays = matching.size,
        )
    }

    private fun wasMainlineBeforeCurrent(
        days: List<SectorStrengthDay>,
        current: SectorStrengthPoint,
        windowSize: Int,
        minimumStrongDays: Int,
    ): Boolean {
        val currentDate = current.dateOrLatest(days)
        val currentIndex = days.indexOfLast { it.date == currentDate }
        if (currentIndex <= 0) return false
        val previous = matchingPoints(
            days = days.subList(0, currentIndex),
            current = current,
            windowSize = windowSize,
        ).map(::isStrongDay)
        return previous.lastOrNull() == true && previous.count { it } >= minimumStrongDays
    }

    private fun matchingPoints(
        days: List<SectorStrengthDay>,
        current: SectorStrengthPoint,
        windowSize: Int,
    ): List<SectorStrengthPoint> =
        days
            .takeLast(windowSize.coerceAtLeast(1))
            .mapNotNull { day ->
                day.points.firstOrNull {
                    it.kind == current.kind && StrongSectorRankingPolicy.boardNamesMatch(it.name, current.name)
                }
            }

    private fun isBrokenDay(point: SectorStrengthPoint): Boolean =
        finiteAtMost(point.changePct, BROKEN_CHANGE_PCT) ||
            finiteAtLeast(point.changeRankPct, BROKEN_RANK_PCT) ||
            finiteAtMost(point.mainNetRatioPct, BROKEN_OUTFLOW_PCT)

    private fun SectorStrengthPoint.dateOrLatest(history: List<SectorStrengthDay>): String =
        date.takeIf { it.isNotBlank() && it != "current" }
            ?: history.maxOfOrNull { it.date }?.takeIf { it.isNotBlank() }
            ?: "current"

    private fun finiteAtLeast(value: Double?, threshold: Double): Boolean =
        value != null && value.isFinite() && value >= threshold

    private fun finiteAtMost(value: Double?, threshold: Double): Boolean =
        value != null && value.isFinite() && value <= threshold

    private fun finiteGreaterThan(value: Double?, threshold: Double): Boolean =
        value != null && value.isFinite() && value > threshold
}
