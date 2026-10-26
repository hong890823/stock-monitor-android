package com.example.stockmonitor.analysis

import com.example.stockmonitor.TestProjectFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrongSectorRankingPolicyTest {

    data class Row(val code: String)

    @Test
    fun strongSectorAndCoreRolesReceiveCappedBonus() {
        val result = StrongSectorRankingPolicy.score(
            sectorChangePct = 2.4,
            sectorChangeRankPct = 0.08,
            sectorMainNetRatioPct = 2.0,
            sectorTurnoverRankPct = 0.12,
            stockTurnoverRankPct = 0.04,
            stockChangePct = 3.0,
            limitStreak = 2,
            sealQualityScore = 80,
            sealQualityCoveragePct = 90,
            mainline = MainlineEvidence(
                isMainline = true,
                strongWatch = true,
                currentStrong = true,
                latestStrong = true,
                strongDays = 3,
                windowDays = 5,
            ),
        )

        assertEquals(4, result.total)
        assertEquals(2, result.sectorScore)
        assertEquals(2, result.roleScore)
        assertTrue(result.labels.any { it.startsWith("主线板块") })
        assertTrue(result.labels.contains("容量中军"))
        assertTrue(result.labels.contains("情绪核心"))
        assertEquals(10, result.confidenceScore)
        assertTrue(result.strongWatch)
    }

    @Test
    fun missingSectorDataDoesNotCreateStrongWatch() {
        val result = StrongSectorRankingPolicy.score(
            sectorChangePct = null,
            sectorChangeRankPct = null,
            sectorMainNetRatioPct = null,
            sectorTurnoverRankPct = null,
            stockTurnoverRankPct = 0.01,
            stockChangePct = 5.0,
            limitStreak = null,
            sealQualityScore = null,
            sealQualityCoveragePct = null,
        )

        assertEquals(0, result.total)
        assertEquals(emptyList<String>(), result.labels)
        assertFalse(result.strongWatch)
    }

    @Test
    fun coreRoleOutsideSustainedMainlineDoesNotGetRoleBonus() {
        val result = StrongSectorRankingPolicy.score(
            sectorChangePct = -0.5,
            sectorChangeRankPct = 0.70,
            sectorMainNetRatioPct = -1.0,
            sectorTurnoverRankPct = 0.80,
            stockTurnoverRankPct = 0.50,
            stockChangePct = 10.0,
            limitStreak = 1,
            sealQualityScore = 80,
            sealQualityCoveragePct = 80,
        )

        assertEquals(0, result.total)
        assertEquals(0, result.sectorScore)
        assertEquals(0, result.roleScore)
        assertEquals(emptyList<String>(), result.labels)
        assertFalse(result.strongWatch)
    }

    @Test
    fun oneStrongDayStaysInStrongWatchWithoutMainlineBonus() {
        val result = StrongSectorRankingPolicy.score(
            sectorChangePct = 2.4,
            sectorChangeRankPct = 0.08,
            sectorMainNetRatioPct = 2.0,
            sectorTurnoverRankPct = 0.12,
            stockTurnoverRankPct = 0.04,
            stockChangePct = 3.0,
            limitStreak = 2,
            sealQualityScore = 80,
            sealQualityCoveragePct = 90,
            mainline = MainlineEvidence(
                isMainline = false,
                strongWatch = false,
                currentStrong = true,
                latestStrong = true,
                strongDays = 1,
                windowDays = 1,
            ),
        )

        assertEquals(1, result.sectorScore)
        assertEquals(0, result.roleScore)
        assertTrue(result.strongWatch)
        assertTrue(result.labels.contains("单日强势·尚未形成主线"))
    }

    @Test
    fun oneNormalMainlineDivergenceDayKeepsRankingWithPartialSectorScore() {
        val result = StrongSectorRankingPolicy.score(
            sectorChangePct = -0.5,
            sectorChangeRankPct = 0.70,
            sectorMainNetRatioPct = -0.2,
            sectorTurnoverRankPct = 0.45,
            stockTurnoverRankPct = 0.04,
            stockChangePct = 2.0,
            limitStreak = 2,
            sealQualityScore = 80,
            sealQualityCoveragePct = 90,
            mainline = MainlineEvidence(
                isMainline = true,
                strongWatch = true,
                currentStrong = false,
                latestStrong = false,
                strongDays = 3,
                windowDays = 5,
            ),
        )

        assertEquals(1, result.sectorScore)
        assertEquals(2, result.roleScore)
        assertTrue(result.strongWatch)
        assertTrue(result.labels.any { it.startsWith("主线正常分歧") })
        assertEquals(25, StrongSectorRankingPolicy.scoreBreakdown(100, result).sectorPoints)
    }

    @Test
    fun rankingMovesHigherBonusUpAndKeepsTiesStable() {
        val rows = listOf(Row("base-1"), Row("bonus"), Row("base-2"))

        val ranked = StrongSectorRankingPolicy.rank(rows) {
            when (it.code) {
                "bonus" -> StrongSectorRankingPolicy.score(
                    sectorChangePct = 2.0,
                    sectorChangeRankPct = 0.1,
                    sectorMainNetRatioPct = 1.0,
                    sectorTurnoverRankPct = 0.1,
                    stockTurnoverRankPct = 0.5,
                    stockChangePct = 2.0,
                    limitStreak = null,
                    sealQualityScore = null,
                    sealQualityCoveragePct = null,
                    mainline = MainlineEvidence(
                        isMainline = true,
                        strongWatch = true,
                        currentStrong = true,
                        latestStrong = true,
                        strongDays = 3,
                        windowDays = 5,
                    ),
                )
                else -> StrongSectorRankingPolicy.score(
                    sectorChangePct = null,
                    sectorChangeRankPct = null,
                    sectorMainNetRatioPct = null,
                    sectorTurnoverRankPct = null,
                    stockTurnoverRankPct = null,
                    stockChangePct = null,
                    limitStreak = null,
                    sealQualityScore = null,
                    sealQualityCoveragePct = null,
                )
            }
        }

        assertEquals(listOf("bonus", "base-1", "base-2"), ranked.map { it.item.code })
    }

    @Test
    fun coreRoleWinsTieAgainstSectorOnlyCandidate() {
        val rows = listOf(Row("sector-only"), Row("emotion-core"))

        val ranked = StrongSectorRankingPolicy.rank(rows) {
            when (it.code) {
                "emotion-core" -> StrongSectorRankingPolicy.score(
                    sectorChangePct = 2.0,
                    sectorChangeRankPct = 0.10,
                    sectorMainNetRatioPct = null,
                    sectorTurnoverRankPct = null,
                    stockTurnoverRankPct = null,
                    stockChangePct = 10.0,
                    limitStreak = 2,
                    sealQualityScore = 80,
                    sealQualityCoveragePct = 90,
                    mainline = MainlineEvidence(
                        isMainline = true,
                        strongWatch = true,
                        currentStrong = true,
                        latestStrong = true,
                        strongDays = 3,
                        windowDays = 5,
                    ),
                )
                else -> StrongSectorRankingPolicy.score(
                    sectorChangePct = 2.0,
                    sectorChangeRankPct = 0.10,
                    sectorMainNetRatioPct = null,
                    sectorTurnoverRankPct = null,
                    stockTurnoverRankPct = null,
                    stockChangePct = null,
                    limitStreak = null,
                    sealQualityScore = null,
                    sealQualityCoveragePct = null,
                    mainline = MainlineEvidence(
                        isMainline = true,
                        strongWatch = true,
                        currentStrong = true,
                        latestStrong = true,
                        strongDays = 3,
                        windowDays = 5,
                    ),
                )
            }
        }

        assertEquals(listOf("emotion-core", "sector-only"), ranked.map { it.item.code })
        assertEquals(1, ranked.first().result.roleScore)
        assertEquals(0, ranked.last().result.roleScore)
    }

    @Test
    fun emotionCoreRequiresReliableSealQualityEvidence() {
        val weakSeal = StrongSectorRankingPolicy.score(
            sectorChangePct = null,
            sectorChangeRankPct = null,
            sectorMainNetRatioPct = null,
            sectorTurnoverRankPct = null,
            stockTurnoverRankPct = null,
            stockChangePct = 10.0,
            limitStreak = 1,
            sealQualityScore = 69,
            sealQualityCoveragePct = 80,
        )
        val lowCoverage = StrongSectorRankingPolicy.score(
            sectorChangePct = null,
            sectorChangeRankPct = null,
            sectorMainNetRatioPct = null,
            sectorTurnoverRankPct = null,
            stockTurnoverRankPct = null,
            stockChangePct = 10.0,
            limitStreak = 1,
            sealQualityScore = 80,
            sealQualityCoveragePct = 49,
        )

        assertFalse(weakSeal.labels.contains("情绪核心"))
        assertFalse(lowCoverage.labels.contains("情绪核心"))
    }

    @Test
    fun capacityCoreDoesNotRewardStockThatLagsItsStrongSector() {
        val result = StrongSectorRankingPolicy.score(
            sectorChangePct = 3.0,
            sectorChangeRankPct = 0.05,
            sectorMainNetRatioPct = 1.0,
            sectorTurnoverRankPct = 0.05,
            stockTurnoverRankPct = 0.01,
            stockChangePct = 1.0,
            limitStreak = null,
            sealQualityScore = null,
            sealQualityCoveragePct = null,
        )

        assertEquals(0, result.roleScore)
        assertFalse(result.labels.contains("容量中军"))
    }

    @Test
    fun overallScoreUsesTechnicalSectorRoleAndConfidenceWeights() {
        val ranking = StrongSectorRankingPolicy.score(
            sectorChangePct = 2.0,
            sectorChangeRankPct = 0.10,
            sectorMainNetRatioPct = 1.0,
            sectorTurnoverRankPct = 0.10,
            stockTurnoverRankPct = 0.10,
            stockChangePct = 2.0,
            limitStreak = 2,
            sealQualityScore = 80,
            sealQualityCoveragePct = 90,
            mainline = MainlineEvidence(
                isMainline = true,
                strongWatch = true,
                currentStrong = true,
                latestStrong = true,
                strongDays = 3,
                windowDays = 5,
            ),
        )

        assertEquals(100, StrongSectorRankingPolicy.overallScore(100, ranking))
        val noBonus = ranking.copy(
            sectorScore = 0,
            roleScore = 0,
            total = 0,
            labels = emptyList(),
            confidenceScore = 0,
            strongWatch = false,
            mainlineStrongDays = 0,
            mainlineWindowDays = 0,
        )
        assertEquals(35, StrongSectorRankingPolicy.overallScore(100, noBonus))
        assertEquals(65, StrongSectorRankingPolicy.overallScore(0, ranking))
    }

    @Test
    fun unifiedScoreWeightsAre35For35For20For10() {
        val mainline = MainlineEvidence(
            isMainline = true,
            strongWatch = true,
            currentStrong = true,
            latestStrong = true,
            strongDays = 3,
            windowDays = 5,
        )
        val result = StrongSectorRankingPolicy.score(
            sectorChangePct = 2.0,
            sectorChangeRankPct = 0.10,
            sectorMainNetRatioPct = 1.0,
            sectorTurnoverRankPct = 0.10,
            stockTurnoverRankPct = 0.10,
            stockChangePct = 2.0,
            limitStreak = 2,
            sealQualityScore = 80,
            sealQualityCoveragePct = 90,
            mainline = mainline,
        )
        val breakdown = StrongSectorRankingPolicy.scoreBreakdown(100, result)

        assertEquals(35, breakdown.technicalPoints)
        assertEquals(35, breakdown.sectorPoints)
        assertEquals(20, breakdown.rolePoints)
        assertEquals(10, breakdown.confidencePoints)
        assertEquals(100, breakdown.total)
    }

    @Test
    fun boardNameMatchingAllowsConceptAliases() {
        assertTrue(StrongSectorRankingPolicy.boardNamesMatch("机器人", "机器人概念"))
        assertTrue(StrongSectorRankingPolicy.boardNamesMatch("机器人概念", "机器人执行器"))
        assertFalse(StrongSectorRankingPolicy.boardNamesMatch("机器人", "工业母机"))
    }

    @Test
    fun boardNameMatchingCachesNormalizationWithoutRegexAllocation() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/analysis/StrongSectorRankingPolicy.kt")
            .readText()

        assertTrue(source.contains("private val normalizedBoardNameCache"))
        assertTrue(source.contains("normalizedBoardNameCache["))
        assertFalse(source.contains("Regex("))
    }
}
