package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.LimitStock

/**
 * 涨停池进入「强势龙头」观察的选股顺序。
 *
 * 旧实现按涨幅 `pct` 降序截断前 100：涨停数较多时 20cm/30cm 天然排在 10cm 前面，
 * 主板首板会被整批挤掉，首板承接链也就没有输入。这里先给首板单独配额，
 * 再用剩余额度放其它涨停。
 */
object LimitCandidateSelection {
    /** 首板单独配额：首板是承接观察链的入口，不能被高涨幅板挤掉。 */
    const val MAX_FIRST_BOARD_CANDIDATES = 150
    const val MAX_OTHER_CANDIDATES = 100
    const val MAX_TOTAL = 200

    fun select(stocks: List<LimitStock>): List<LimitStock> {
        if (stocks.isEmpty()) return emptyList()
        val sorted = stocks.sortedByDescending { it.pct }
        val firstBoards = sorted
            .filter { it.limitStreak == 1 }
            .take(MAX_FIRST_BOARD_CANDIDATES)
        val remaining = (MAX_TOTAL - firstBoards.size).coerceAtLeast(0)
        val others = sorted
            .filter { it.limitStreak != 1 }
            .take(minOf(MAX_OTHER_CANDIDATES, remaining))
        return firstBoards + others
    }
}