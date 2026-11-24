package com.example.stockmonitor.analysis

/**
 * 分批进场参数（docs/2026-11-24-divergence-execution-strategy.md 模块 E）：
 * 「分批进场，不是一把梭」的程序化。只是把同一条加仓额度切成三批的执行口径，
 * **不放大额度**——总股数仍由 [PortfolioSwing.allocate] 的仓位/风险预算约束。
 *
 * 作废线与 [IntradayAdditionPolicy] 的 INVALIDATED 口径对齐：收盘跌破失效参考
 * 即取消全部后续批次（接飞刀禁令），盘中数量逻辑不动。
 */
object AddBatchPolicy {
    const val FIRST_BATCH_PCT = 40.0
    const val SECOND_BATCH_PCT = 30.0
    const val THIRD_BATCH_PCT = 30.0

    data class AddBatch(val index: Int, val sharePct: Double, val trigger: String)

    val BATCHES = listOf(
        AddBatch(1, FIRST_BATCH_PCT, "浅锚区间内缩量企稳（量比 ≤ 0.8 复用回踩缩量口径）"),
        AddBatch(2, SECOND_BATCH_PCT, "次日证伪确认（分歧定性洗盘）后，或回踩浅锚不破且量价修复"),
        AddBatch(3, THIRD_BATCH_PCT, "放量突破确认，或二次回踩锚位不破"),
    )

    const val INVALIDATION_NOTE = "任一批次后收盘跌破失效参考，取消全部后续批次（接飞刀禁令）"

    fun summary(): String =
        BATCHES.joinToString("；", prefix = "分批执行，不一次打满：", postfix = "。$INVALIDATION_NOTE。") { batch ->
            "第${batch.index}批 ${batch.sharePct.toInt()}%（${batch.trigger}）"
        }
}
