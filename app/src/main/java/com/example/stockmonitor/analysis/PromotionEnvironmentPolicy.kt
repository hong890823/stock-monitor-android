package com.example.stockmonitor.analysis

data class PromotionEnvironmentInput(
    val shanghaiChangePct: Double?,
    val shenzhenChangePct: Double?,
    val limitUpCount: Int?,
    val limitDownCount: Int?,
    val boardChanges: List<Double?> = emptyList(),
)

object PromotionEnvironmentPolicy {
    private const val INDEX_RISK_PCT = -0.8
    private const val BOARD_WEAK_PCT = -1.0
    private const val LIMIT_DOWN_BREADTH_COUNT = 20

    fun gate(input: PromotionEnvironmentInput): String? {
        val indexes = listOf(input.shanghaiChangePct, input.shenzhenChangePct)
            .filterNotNull()
            .filter { it.isFinite() }
        if (indexes.any { it <= INDEX_RISK_PCT }) {
            return "主要指数进入风险区，暂缓候选晋级"
        }
        val up = input.limitUpCount
        val down = input.limitDownCount
        if (up != null && down != null && down >= LIMIT_DOWN_BREADTH_COUNT && down > up) {
            return "跌停数量明显偏多，市场承接转弱，暂缓候选晋级"
        }
        val boards = input.boardChanges.filterNotNull().filter { it.isFinite() }
        if (boards.isNotEmpty() && boards.all { it <= BOARD_WEAK_PCT }) {
            return "所属板块同步转弱，暂缓候选晋级"
        }
        return null
    }
}
