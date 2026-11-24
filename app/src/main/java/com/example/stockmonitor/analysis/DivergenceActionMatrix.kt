package com.example.stockmonitor.analysis

/**
 * 分歧期状态矩阵（docs/2026-11-24-divergence-execution-strategy.md 模块 C）：
 * 大盘分歧状态 × 仓位状态 → 提示文案。
 *
 * 全部是**提示不是命令**（风险动作比例原则）：低位/轻仓/无红灯只提示不动手；
 * 「别等连续大阳线追确认」的程序化已有 [StrategyThresholds.MAX_CHASE_MA5_BIAS_PCT]
 * 追高上限，这里只补矩阵措辞，不新增拦截逻辑。
 */
object DivergenceActionMatrix {

    /** 全局禁止项，非 [DivergencePhase.NONE] 时随矩阵一起给出。 */
    fun banLine(): String =
        "禁止：等连续大阳线追确认——涨了两三天再进，最好的位置早没了（追高上限见 MA5 乖离约束）"

    /**
     * 矩阵出词。[hasHoldings] 区分持仓/空仓轻仓；[riskFlag] 是重仓或红灯
     * （只影响降级行的措辞强度，不产生命令）。
     */
    fun promptsFor(phase: DivergencePhase, hasHoldings: Boolean, riskFlag: Boolean = false): List<String> {
        if (phase == DivergencePhase.NONE) return emptyList()
        val cell = when (phase) {
            DivergencePhase.NONE -> emptyList()
            DivergencePhase.REBOUND_HOLD -> if (hasHoldings) {
                listOf("洗盘确认（观测窗口收中阳）：冲高可减机动仓，留底仓不全兑现；分歧洗盘甩掉的是没信仰的筹码，已有浮盈不是卖出理由")
            } else {
                listOf("洗盘确认（观测窗口收中阳）：借分歧窗口分批进场（见分批口径）；回踩给的是位置，不是一把梭的信号")
            }
            DivergencePhase.WATCH -> if (hasHoldings) {
                listOf("分歧观察：不动——分歧洗盘观察中，今天一根 K 线定方向")
            } else {
                listOf("分歧观察：只准备不进场——候选和浅锚价先列好，批次1也等证伪后再动（宁慢勿追）")
            }
            DivergencePhase.DOWNGRADE -> if (hasHoldings) {
                if (riskFlag) {
                    listOf("证伪降级：降低仓位预期——重仓/红灯优先按既有风险规则减仓处置（提示不命令），不新增资金")
                } else {
                    listOf("证伪降级：降低仓位预期——轻仓/无红灯只提示不动作，不新增资金不摊平")
                }
            } else {
                listOf("证伪降级：冻结进场，不新增资金；等下一个分歧周期再谈")
            }
        }
        return cell + banLine()
    }
}
