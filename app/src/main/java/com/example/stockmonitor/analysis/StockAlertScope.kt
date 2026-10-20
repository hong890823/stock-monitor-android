package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Position

object StockAlertScope {
    fun heldCodes(positions: List<Position>): Set<String> = positions
        .filter { it.quantity.isFinite() && it.quantity > 0 }
        .map { it.code }
        .toSet()
}
