package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.LimitDataSource
import com.example.stockmonitor.core.LimitStock

object LimitCandidatePolicy {
    const val MIN_SEAL_TO_FLOAT_PCT = 0.5
    const val MIN_SEAL_TO_TURNOVER_PCT = 10.0
    const val MAX_OPEN_COUNT = 1

    fun eligible(stock: LimitStock): Boolean {
        if (stock.source != LimitDataSource.EASTMONEY_LIMIT_POOL) return false
        if (stock.limitStreak == null || stock.limitStreak < 1) return false
        if (stock.openCount == null || stock.openCount < 0 || stock.openCount > MAX_OPEN_COUNT) return false
        val sealToFloat = stock.sealToFloatPct
        val sealToTurnover = stock.sealToTurnoverPct
        return (sealToFloat != null && sealToFloat >= MIN_SEAL_TO_FLOAT_PCT) ||
            (sealToTurnover != null && sealToTurnover >= MIN_SEAL_TO_TURNOVER_PCT)
    }
}
