package com.example.stockmonitor.analysis

data class MarketBreadthSnapshot(
    val rising: Int,
    val total: Int,
    val updatedAtMs: Long,
)

object MarketBreadthFreshnessPolicy {
    val MAX_AGE_MS: Long = 35L * 60L * 1_000L

    fun freshRatio(
        snapshot: MarketBreadthSnapshot?,
        nowMs: Long,
        maxAgeMs: Long = MAX_AGE_MS,
    ): Double? {
        val current = snapshot ?: return null
        if (current.total <= 0 || current.rising !in 0..current.total) return null
        if (current.updatedAtMs <= 0L || maxAgeMs <= 0L) return null
        val age = nowMs - current.updatedAtMs
        if (age !in 0..maxAgeMs) return null
        return current.rising.toDouble() / current.total
    }
}
