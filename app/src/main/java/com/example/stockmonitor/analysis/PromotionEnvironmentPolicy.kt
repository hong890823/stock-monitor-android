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

    /**
     * 板块标签存在来源与粒度误差，只作为排序/辅助证据；不再单独阻断候选晋级。
     * 真正的掉档仍由个股趋势、关键位、量价和市场风险共振决定。
     */
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
        return null
    }
}
