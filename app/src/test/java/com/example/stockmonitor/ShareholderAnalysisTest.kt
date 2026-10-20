package com.example.stockmonitor

import com.example.stockmonitor.analysis.InstitutionalPosition
import com.example.stockmonitor.analysis.ShareholderAnalysis
import com.example.stockmonitor.analysis.ShareholderDisclosure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareholderAnalysisTest {

    @Test
    fun fewerRetailHoldersAndInstitutionalNetInflowIsMarkedAsPositive() {
        val result = ShareholderAnalysis.from(
            latest = ShareholderDisclosure(
                reportDate = "2026-06-30",
                holderCount = 80_000,
                institutions = listOf(
                    InstitutionalPosition("甲基金", 1_200_000.0),
                    InstitutionalPosition("乙资管", 700_000.0),
                ),
            ),
            previous = ShareholderDisclosure(
                reportDate = "2026-03-31",
                holderCount = 100_000,
                institutions = listOf(
                    InstitutionalPosition("甲基金", 1_000_000.0),
                    InstitutionalPosition("丙保险", 500_000.0),
                ),
            ),
            currentPrice = 10.0,
        )!!

        assertEquals(80_000L, result.retailHolderCount)
        assertEquals(-20.0, result.retailHolderChangePct!!, 1e-6)
        assertEquals(4_000_000.0, result.institutionalNetFunds!!, 1e-6)
        assertEquals(listOf("乙资管", "甲基金"), result.inflows.map { it.name })
        assertEquals(listOf("丙保险"), result.outflows.map { it.name })
        assertTrue(result.conclusion.contains("利好"))
    }

    @Test
    fun institutionalNetOutflowIsMarkedAsRiskAndListsReducedPositions() {
        val result = ShareholderAnalysis.from(
            latest = ShareholderDisclosure(
                reportDate = "2026-06-30",
                holderCount = 120_000,
                institutions = listOf(InstrumentalPosition("甲基金", 600_000.0)),
            ),
            previous = ShareholderDisclosure(
                reportDate = "2026-03-31",
                holderCount = 100_000,
                institutions = listOf(
                    InstitutionalPosition("甲基金", 1_000_000.0),
                    InstitutionalPosition("乙资管", 500_000.0),
                ),
            ),
            currentPrice = 10.0,
        )!!

        assertEquals(-9_000_000.0, result.institutionalNetFunds!!, 1e-6)
        assertEquals(listOf("乙资管", "甲基金"), result.outflows.map { it.name })
        assertTrue(result.conclusion.contains("风险"))
    }
}

private fun InstrumentalPosition(name: String, shares: Double): InstitutionalPosition =
    InstitutionalPosition(name, shares)
