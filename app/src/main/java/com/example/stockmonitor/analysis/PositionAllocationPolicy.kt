package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.PositionAllocationConfig
import com.example.stockmonitor.core.PositionAllocationMode

data class PositionAllocation(
    val totalQuantity: Double,
    val baseQuantityTarget: Double?,
    val baseQuantity: Double,
    val rollingQuantity: Double,
    val riskObservationQuantity: Double,
    val configured: Boolean,
    val mode: PositionAllocationMode?,
) {
    val displayText: String
        get() = if (!configured) {
            "分仓未设置（暂列观察）"
        } else if (mode == PositionAllocationMode.RISK_OBSERVATION) {
            "风险观察仓 ${formatAllocationShares(riskObservationQuantity)} 股"
        } else {
            "底仓 ${formatAllocationShares(baseQuantity)} 股｜滚动仓 ${formatAllocationShares(rollingQuantity)} 股"
        }
}

object PositionAllocationPolicy {
    fun derive(position: Position, config: PositionAllocationConfig?): PositionAllocation {
        val total = position.quantity.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        if (config == null) {
            return PositionAllocation(
                totalQuantity = total,
                baseQuantityTarget = null,
                baseQuantity = 0.0,
                rollingQuantity = 0.0,
                riskObservationQuantity = total,
                configured = false,
                mode = null,
            )
        }
        return when (config.mode) {
            PositionAllocationMode.NORMAL -> {
                val base = minOf(total, config.baseQuantityTarget)
                PositionAllocation(
                    totalQuantity = total,
                    baseQuantityTarget = config.baseQuantityTarget,
                    baseQuantity = base,
                    rollingQuantity = (total - base).coerceAtLeast(0.0),
                    riskObservationQuantity = 0.0,
                    configured = true,
                    mode = config.mode,
                )
            }
            PositionAllocationMode.RISK_OBSERVATION ->
                PositionAllocation(
                    totalQuantity = total,
                    baseQuantityTarget = config.baseQuantityTarget,
                    baseQuantity = 0.0,
                    rollingQuantity = 0.0,
                    riskObservationQuantity = total,
                    configured = true,
                    mode = config.mode,
                )
        }
    }

}

private fun formatAllocationShares(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.2f".format(java.util.Locale.CHINA, value)
