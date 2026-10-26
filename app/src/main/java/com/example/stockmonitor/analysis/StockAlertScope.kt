package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Position

enum class StockAlertType {
    CONFIRMED_BUY,
    DROP,
    MARKET,
    DIP_REBOUND,
    REBOUND_TRACKING,
    SEAL_SPRINT,
    SEAL_STATUS,
    INTRADAY_SIGNAL,
    PORTFOLIO_SIGNAL,
    OBSERVATION_BREAKOUT_RISK,
}

object StockAlertScope {
    private val portfolioSignalLabels = setOf("买入确认", "买入观察", "减仓风险")

    fun heldCodes(positions: List<Position>): Set<String> = positions
        .filter { it.quantity.isFinite() && it.quantity > 0 }
        .map { it.code }
        .toSet()

    fun shouldNotify(
        type: StockAlertType,
        held: Boolean,
        buyPointConfirmed: Boolean = false,
    ): Boolean = if (type == StockAlertType.CONFIRMED_BUY) {
        buyPointConfirmed
    } else {
        held
    }

    fun shouldNotifyPortfolioSignal(signal: String, exitStage: HoldingExitStage?): Boolean =
        signal in portfolioSignalLabels &&
            exitStage in setOf(
                HoldingExitStage.REDUCE,
                HoldingExitStage.HIGH_REDUCE,
                HoldingExitStage.EXIT,
            )
}
