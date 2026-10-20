package com.example.stockmonitor.analysis

import kotlin.math.max

data class PaperTradingConfig(
    val commissionRate: Double = 0.0002,
    val minimumCommission: Double = 5.0,
    val stampDutyRate: Double = 0.0005,
    val transferFeeRate: Double = 0.00001,
    val lotSize: Int = 100,
)

data class PaperPosition(
    val code: String,
    val name: String,
    val quantity: Int,
    val averageCost: Double,
)

data class PaperTradeReceipt(
    val code: String,
    val side: String,
    val quantity: Int,
    val price: Double,
    val grossValue: Double,
    val fees: Double,
    val totalCashChange: Double,
    val realizedPnl: Double? = null,
)

private data class PaperLot(
    val code: String,
    val name: String,
    val quantity: Int,
    val unitCost: Double,
    val buyDate: String,
)

/**
 * A 股最小纸上交易账本：只做本地策略验证，不连接券商，也不代表真实成交。
 */
class PaperTradingLedger(
    initialCash: Double,
    private val config: PaperTradingConfig = PaperTradingConfig(),
) {
    private val lots = mutableListOf<PaperLot>()

    var cash: Double = initialCash
        private set

    init {
        require(initialCash >= 0.0) { "初始资金不能为负" }
        require(config.commissionRate >= 0.0) { "佣金费率不能为负" }
        require(config.minimumCommission >= 0.0) { "最低佣金不能为负" }
        require(config.stampDutyRate >= 0.0) { "印花税率不能为负" }
        require(config.transferFeeRate >= 0.0) { "过户费率不能为负" }
        require(config.lotSize > 0) { "最小交易单位必须为正数" }
    }

    fun position(code: String): PaperPosition? {
        val matching = lots.filter { it.code == code }
        if (matching.isEmpty()) return null
        val quantity = matching.sumOf { it.quantity }
        if (quantity <= 0) return null
        val totalCost = matching.sumOf { it.quantity * it.unitCost }
        return PaperPosition(
            code = code,
            name = matching.first().name,
            quantity = quantity,
            averageCost = totalCost / quantity,
        )
    }

    fun buy(code: String, name: String, quantity: Int, price: Double, date: String): PaperTradeReceipt {
        validateTrade(quantity, price, date)
        val gross = quantity * price
        val fees = buyFees(gross)
        val cashChange = gross + fees
        check(cash >= cashChange) { "可用资金不足" }
        cash -= cashChange
        lots += PaperLot(code, name, quantity, cashChange / quantity, date)
        return PaperTradeReceipt(code, "buy", quantity, price, gross, fees, cashChange)
    }

    fun sell(code: String, quantity: Int, price: Double, date: String): PaperTradeReceipt {
        validateTrade(quantity, price, date)
        var remaining = quantity
        val available = lots.filter { it.code == code && it.buyDate < date }
        check(available.sumOf { it.quantity } >= quantity) { "A 股买入当日不可卖出或可卖数量不足" }

        var costBasis = 0.0
        for (lot in available) {
            if (remaining == 0) break
            val used = minOf(remaining, lot.quantity)
            costBasis += used * lot.unitCost
            remaining -= used
        }

        val gross = quantity * price
        val fees = sellFees(gross)
        val cashChange = gross - fees
        cash += cashChange

        remaining = quantity
        val iterator = lots.listIterator()
        while (iterator.hasNext() && remaining > 0) {
            val lot = iterator.next()
            if (lot.code != code || lot.buyDate >= date) continue
            val used = minOf(remaining, lot.quantity)
            remaining -= used
            if (used == lot.quantity) {
                iterator.remove()
            } else {
                iterator.set(lot.copy(quantity = lot.quantity - used))
            }
        }
        return PaperTradeReceipt(
            code = code,
            side = "sell",
            quantity = quantity,
            price = price,
            grossValue = gross,
            fees = fees,
            totalCashChange = cashChange,
            realizedPnl = cashChange - costBasis,
        )
    }

    private fun validateTrade(quantity: Int, price: Double, date: String) {
        require(quantity > 0 && quantity % config.lotSize == 0) { "数量必须是${config.lotSize}股的整数倍" }
        require(price > 0.0) { "成交价必须为正数" }
        require(date.isNotBlank()) { "交易日期不能为空" }
    }

    private fun commission(gross: Double): Double = max(config.minimumCommission, gross * config.commissionRate)

    private fun buyFees(gross: Double): Double = commission(gross) + gross * config.transferFeeRate

    private fun sellFees(gross: Double): Double =
        commission(gross) + gross * config.stampDutyRate + gross * config.transferFeeRate
}
