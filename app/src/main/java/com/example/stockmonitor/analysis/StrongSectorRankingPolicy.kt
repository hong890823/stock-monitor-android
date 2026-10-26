package com.example.stockmonitor.analysis

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object StrongSectorRankingPolicy {
    private val normalizedBoardNameCache = ConcurrentHashMap<String, String>()

    data class Result(
        val sectorScore: Int,
        val roleScore: Int,
        val total: Int,
        val labels: List<String>,
        val boardName: String? = null,
        val confidenceScore: Int = 0,
        val strongWatch: Boolean = false,
        val mainlineDiverging: Boolean = false,
        val mainlineStrongDays: Int = 0,
        val mainlineWindowDays: Int = 0,
    )

    data class ScoreBreakdown(
        val technicalPoints: Int,
        val sectorPoints: Int,
        val rolePoints: Int,
        val confidencePoints: Int,
        val total: Int,
    )

    data class Ranked<T>(
        val item: T,
        val result: Result,
    )

    fun scoreBreakdown(technicalScore: Int?, result: Result): ScoreBreakdown {
        val technical = technicalScore?.coerceIn(0, 100) ?: 0
        val technicalPoints = (technical * 35.0 / 100.0).roundToInt()
        val sectorPoints = if (result.mainlineDiverging) {
            25
        } else {
            (result.sectorScore.coerceIn(0, 2) * 35.0 / 2.0).roundToInt()
        }
        val rolePoints = (result.roleScore.coerceIn(0, 2) * 20.0 / 2.0).roundToInt()
        val confidencePoints = result.confidenceScore.coerceIn(0, 10)
        return ScoreBreakdown(
            technicalPoints = technicalPoints,
            sectorPoints = sectorPoints,
            rolePoints = rolePoints,
            confidencePoints = confidencePoints,
            total = (technicalPoints + sectorPoints + rolePoints + confidencePoints).coerceIn(0, 100),
        )
    }

    fun overallScore(technicalScore: Int?, result: Result): Int =
        scoreBreakdown(technicalScore, result).total

    fun score(
        sectorChangePct: Double?,
        sectorChangeRankPct: Double?,
        sectorMainNetRatioPct: Double?,
        sectorTurnoverRankPct: Double?,
        stockTurnoverRankPct: Double?,
        stockChangePct: Double?,
        limitStreak: Int?,
        sealQualityScore: Int?,
        sealQualityCoveragePct: Int? = null,
        mainline: MainlineEvidence = MainlineEvidence(
            isMainline = false,
            strongWatch = false,
            currentStrong = false,
            latestStrong = false,
            strongDays = 0,
            windowDays = 0,
        ),
    ): Result {
        val labels = mutableListOf<String>()
        val currentStrong =
            finiteAtLeast(sectorChangePct, 1.0) &&
                finiteAtMost(sectorChangeRankPct, 0.25)
        val sustainedMainline = currentStrong && mainline.isMainline
        val mainlineDiverging = !currentStrong && mainline.isMainline && mainline.strongWatch
        val retainedMainline = sustainedMainline || mainlineDiverging
        val sectorScore = when {
            sustainedMainline -> 2
            mainlineDiverging -> 1
            currentStrong -> 1
            else -> 0
        }
        if (sustainedMainline) {
            labels += mainline.label.ifBlank { "主线板块·最近5日强势${mainline.strongDays}/${mainline.windowDays}" }
        } else if (mainlineDiverging) {
            labels += mainline.label.ifBlank { "主线正常分歧·暂保留1个交易日" }
        } else if (currentStrong) {
            labels += "单日强势·尚未形成主线"
        }

        var roleScore = 0
        val capacityCore =
            retainedMainline &&
                finiteAtLeast(stockChangePct, sectorChangePct ?: Double.NaN) &&
                finiteAtMost(stockTurnoverRankPct, 0.10)
        if (capacityCore) {
            roleScore += 1
            labels += "容量中军"
        }
        val reliableSealQuality =
            sealQualityCoveragePct != null &&
                sealQualityCoveragePct >= LimitRanking.MIN_RELIABLE_COVERAGE_PCT &&
                sealQualityScore != null
        val emotionCore = retainedMainline &&
            (limitStreak ?: 0) >= 1 &&
            (sealQualityScore ?: 0) >= 70 &&
            reliableSealQuality
        if (emotionCore) {
            roleScore += 1
            labels += "情绪核心"
        }

        val readyEvidence = listOf(
            finite(sectorChangePct) && finite(sectorChangeRankPct),
            finite(sectorMainNetRatioPct) && finite(sectorTurnoverRankPct),
            finite(stockChangePct) && finite(stockTurnoverRankPct),
            (limitStreak == null && finite(stockChangePct)) || reliableSealQuality,
            mainline.windowDays >= 3,
        ).count { it }
        val confidenceScore = (readyEvidence * 10.0 / 5.0).roundToInt()

        return Result(
            sectorScore = sectorScore,
            roleScore = roleScore.coerceIn(0, 2),
            total = sectorScore + roleScore,
            labels = labels,
            confidenceScore = confidenceScore,
            strongWatch = retainedMainline || currentStrong,
            mainlineDiverging = mainlineDiverging,
            mainlineStrongDays = mainline.strongDays,
            mainlineWindowDays = mainline.windowDays,
        )
    }

    fun boardNamesMatch(left: String, right: String): Boolean {
        if (left == right) return true
        val normalizedLeft = normalizeBoardName(left)
        val normalizedRight = normalizeBoardName(right)
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) return false
        return normalizedLeft == normalizedRight ||
            normalizedLeft.contains(normalizedRight) ||
            normalizedRight.contains(normalizedLeft)
    }

    fun <T> rank(items: List<T>, scoreOf: (T) -> Result): List<Ranked<T>> =
        rankByCombined(items, scoreOf = scoreOf, technicalScoreOf = { null })

    fun <T> rankByCombined(
        items: List<T>,
        scoreOf: (T) -> Result,
        technicalScoreOf: (T) -> Int?,
    ): List<Ranked<T>> =
        items
            .mapIndexed { index, item -> IndexedValue(index, Ranked(item, scoreOf(item))) }
            .sortedWith(
                compareByDescending<IndexedValue<Ranked<T>>> {
                    overallScore(technicalScoreOf(it.value.item), it.value.result)
                }
                    .thenByDescending { it.value.result.roleScore }
                    .thenByDescending { it.value.result.sectorScore }
                    .thenBy { it.index },
            )
            .map { it.value }

    private fun normalizeBoardName(name: String): String {
        normalizedBoardNameCache[name]?.let { return it }
        val compact = buildString(name.length) {
            name.lowercase().forEach { char ->
                if (!isBoardSeparator(char)) append(char)
            }
        }
        val normalized = removeBoardQualifier(compact)
        normalizedBoardNameCache[name] = normalized
        return normalized
    }

    private fun isBoardSeparator(char: Char): Boolean = when (char) {
        ' ', '\t', '\n', '\u000B', '\u000C', '\r', '（', '）', '(', ')', '·', '_', '-' -> true
        else -> false
    }

    private fun removeBoardQualifier(name: String): String = when {
        name.endsWith("产业链") -> name.removeSuffix("产业链")
        name.endsWith("执行器") -> name.removeSuffix("执行器")
        name.endsWith("减速器") -> name.removeSuffix("减速器")
        name.endsWith("传感器") -> name.removeSuffix("传感器")
        name.endsWith("控制器") -> name.removeSuffix("控制器")
        name.endsWith("概念") -> name.removeSuffix("概念")
        name.endsWith("板块") -> name.removeSuffix("板块")
        name.endsWith("行业") -> name.removeSuffix("行业")
        name.endsWith("相关") -> name.removeSuffix("相关")
        name.endsWith("电机") -> name.removeSuffix("电机")
        else -> name
    }

    private fun finite(value: Double?): Boolean = value != null && value.isFinite()

    private fun finiteAtLeast(value: Double?, threshold: Double, exclusive: Boolean = false): Boolean {
        if (!finite(value)) return false
        val checked = requireNotNull(value)
        return if (exclusive) checked > threshold else checked >= threshold
    }

    private fun finiteAtMost(value: Double?, threshold: Double): Boolean =
        finite(value) && requireNotNull(value) <= threshold
}
