package com.example.stockmonitor.analysis

/** 选股列表的当前涨停筛选口径。 */
object LimitUpFilterPolicy {
    enum class Option(val label: String) {
        ALL("全部"),
        LIMIT_10("10cm涨停"),
        LIMIT_20("20cm涨停"),
        LIMIT_30("30cm涨停"),
    }

    enum class LimitBand {
        LIMIT_10,
        LIMIT_20,
        LIMIT_30,
    }

    data class Stock(
        val code: String,
        val name: String,
        val isLimitUp: Boolean,
    )

    fun bandOf(stock: Stock): LimitBand? {
        val number = stock.code.takeLast(6)
        return when {
            number.startsWith("8") || number.startsWith("4") || number.startsWith("920") ->
                LimitBand.LIMIT_30
            number.startsWith("30") || number.startsWith("688") ->
                LimitBand.LIMIT_20
            stock.name.contains("ST", ignoreCase = true) -> null
            else -> LimitBand.LIMIT_10
        }
    }

    fun matches(option: Option, stock: Stock): Boolean {
        if (option == Option.ALL) return true
        if (!stock.isLimitUp) return false
        return bandOf(stock) == when (option) {
            Option.LIMIT_10 -> LimitBand.LIMIT_10
            Option.LIMIT_20 -> LimitBand.LIMIT_20
            Option.LIMIT_30 -> LimitBand.LIMIT_30
            Option.ALL -> return true
        }
    }

    fun filter(stocks: List<Stock>, option: Option): List<Stock> =
        stocks.filter { matches(option, it) }

    fun <T> filterBy(
        items: List<T>,
        option: Option,
        snapshotOf: (T) -> Stock,
    ): List<T> = items.filter { matches(option, snapshotOf(it)) }
}
