package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import java.util.Locale

/**
 * 成交量加权筹码分布估算（纯函数，无 IO，不等同真实筹码分布）。
 * 口径（与 mac 端 chip_distribution.py 2026-11-26 一致）：
 * - 取最近 [LOOKBACK] 根「已完成」日线做分布（窗口太短会截掉高位套牢筹码、低估抛压）；
 *   当日 K 线收盘（15:00）前不计入分布，与完成 K 线口径一致；
 * - 每根 K 线的成交量按其 [low, high] 区间重叠比例分摊到价格桶，
 *   并按 decay = 0.5 + 0.5*(i+1)/n 线性换手衰减（越近权重越高）；
 * - 获利盘 = 现价及下方筹码占比；上方 +N% 套牢密度 = 现价上方 N% 区间筹码占比；
 * - 压力位 = 现价上方密度最高的价格桶；
 * - 近 N 日买入者成本 = 近 N 根（含当日）收盘×成交量加权均价——与分布口径分离，
 *   当日成交是真实换手，浮盈按现价对成本计算。
 *
 * 只作成交量加权估算证据，不能单独证明主力控盘、筹码锁定或洗盘完成。
 * 70% 筹码区间/峰值仍由 [ChipEstimator] 提供，这里不重复计算。
 */
object ChipDistributionPolicy {

    /** 分布回看窗口（根）：约一年。 */
    const val LOOKBACK = 250

    /** 上方套牢密度观察带（百分比）。 */
    val TRAPPED_BANDS: List<Int> = listOf(3, 5, 8)

    /** 分布细网格桶数。 */
    private const val BINS = 100

    /** 近 N 日买入者成本的窗口。 */
    private val RECENT_WINDOWS: List<Int> = listOf(5, 20)

    data class ChipDistribution(
        /** 获利盘（现价及下方筹码占比，%）。 */
        val profitRatioPct: Double? = null,
        /** 上方 +N% 内套牢密度（%，key = N）。 */
        val trappedAbovePct: Map<Int, Double> = emptyMap(),
        /** 压力位 = 现价上方密度最高的价格桶。 */
        val pressurePrice: Double? = null,
        /** 压力位距现价（%）。 */
        val pressureDistancePct: Double? = null,
        val recent5dCost: Double? = null,
        val recent5dFloatPct: Double? = null,
        val recent20dCost: Double? = null,
        val recent20dFloatPct: Double? = null,
    ) {
        /** 单行摘要：筹码：获利盘x%｜压力位p（距+y%）｜近20日浮盈+z%。获利盘缺失视为数据不足返回 null。 */
        val summaryLine: String?
            get() {
                val profit = profitRatioPct?.takeIf { it.isFinite() } ?: return null
                val parts = mutableListOf(String.format(Locale.CHINA, "筹码：获利盘%.0f%%", profit))
                val pressure = pressurePrice?.takeIf { it.isFinite() && it > 0 }
                val distance = pressureDistancePct?.takeIf { it.isFinite() }
                if (pressure != null && distance != null) {
                    parts += String.format(Locale.CHINA, "压力位%.2f（距+%.1f%%）", pressure, distance)
                }
                recent20dFloatPct?.takeIf { it.isFinite() }?.let {
                    parts += String.format(Locale.CHINA, "近20日浮盈%+.1f%%", it)
                }
                return parts.joinToString("｜")
            }

        /** 副行：上方套牢密度（成交量加权估算）。任一带缺失视为数据不足返回 null。 */
        val trappedLine: String?
            get() {
                val parts = TRAPPED_BANDS.map { band ->
                    val value = trappedAbovePct[band]?.takeIf { it.isFinite() } ?: return null
                    String.format(Locale.CHINA, "+%d%%内%.1f%%", band, value)
                }
                return "上方套牢：" + parts.joinToString("｜") + "（成交量加权估算）"
            }
    }

    /**
     * 估算筹码分布。[todayBarCompleted] 控制当日 K 线是否计入分布（收盘前不计）；
     * 近 N 日成本不受影响（含当日真实成交）。数据不足时各指标为 null。
     */
    fun evaluate(
        bars: List<DayBar>,
        price: Double? = null,
        todayDate: String = TimeUtils.todayText(),
        todayBarCompleted: Boolean = TimeUtils.clockText() >= "15:00",
    ): ChipDistribution {
        val rows = bars.filter { bar ->
            bar.close.isFinite() && bar.close > 0 &&
                bar.low.isFinite() && bar.low > 0 &&
                bar.high.isFinite() && bar.high > 0 &&
                bar.volume.isFinite() && bar.volume >= 0 &&
                bar.low <= maxOf(bar.open, bar.close, bar.high) &&
                bar.high >= minOf(bar.open, bar.close, bar.low)
        }.sortedBy { it.date }
        if (rows.isEmpty()) return ChipDistribution()
        // 今天以前的都算完成；今天的看 todayBarCompleted。
        val completed = rows.filter { bar ->
            when {
                bar.date < todayDate -> true
                bar.date > todayDate -> false
                else -> todayBarCompleted
            }
        }
        val window = completed.takeLast(LOOKBACK)
        if (window.isEmpty()) return ChipDistribution()

        val currentPrice = price?.takeIf { it.isFinite() && it > 0 } ?: window.last().close

        // 近N日买入者成本（含当日未完成K线：当日成交是真实换手）
        val recent = RECENT_WINDOWS.associateWith { days ->
            weightedMean(rows.takeLast(days).map { it.close to it.volume })
        }
        val recent5dCost = recent[5]
        val recent20dCost = recent[20]
        val recent5dFloatPct = recent5dCost?.takeIf { it > 0 }?.let { (currentPrice / it - 1) * 100 }
        val recent20dFloatPct = recent20dCost?.takeIf { it > 0 }?.let { (currentPrice / it - 1) * 100 }

        val lowPrice = window.minOf { it.low }
        val highPrice = window.maxOf { it.high }
        if (highPrice <= lowPrice) {
            if (window.sumOf { it.volume } <= 0) return ChipDistribution(
                recent5dCost = recent5dCost,
                recent5dFloatPct = recent5dFloatPct,
                recent20dCost = recent20dCost,
                recent20dFloatPct = recent20dFloatPct,
            )
            // 价格无波动（如一字板）：全部筹码集中在单一价位，按点质量计算，
            // 不像峰/70% 区间那样放弃——获利盘/套牢/压力在单点上依然定义良好。
            val point = lowPrice
            val profit = if (point <= currentPrice) 100.0 else 0.0
            val trapped = TRAPPED_BANDS.associateWith { band ->
                val upper = currentPrice * (1 + band / 100.0)
                if (currentPrice < point && point <= upper) 100.0 else 0.0
            }
            val pressure = point.takeIf { it > currentPrice }
            return ChipDistribution(
                profitRatioPct = profit,
                trappedAbovePct = trapped,
                pressurePrice = pressure,
                pressureDistancePct = pressure?.let { (it / currentPrice - 1) * 100 },
                recent5dCost = recent5dCost,
                recent5dFloatPct = recent5dFloatPct,
                recent20dCost = recent20dCost,
                recent20dFloatPct = recent20dFloatPct,
            )
        }

        // 线性换手衰减 + 按 [low, high] 重叠比例分摊到价格桶
        val width = (highPrice - lowPrice) / BINS
        val dens = DoubleArray(BINS)
        val count = window.size
        window.forEachIndexed { index, row ->
            val weight = row.volume * (0.5 + 0.5 * (index + 1) / count)
            if (weight <= 0) return@forEachIndexed
            val span = row.high - row.low
            if (span <= 0) {
                val bucket = ((row.close - lowPrice) / width).toInt().coerceIn(0, BINS - 1)
                dens[bucket] += weight
                return@forEachIndexed
            }
            for (bucket in 0 until BINS) {
                val binLow = lowPrice + bucket * width
                val binHigh = binLow + width
                val overlap = minOf(binHigh, row.high) - maxOf(binLow, row.low)
                if (overlap > 0) {
                    dens[bucket] += weight * overlap / span
                }
            }
        }
        val total = dens.sum()
        if (total <= 0) return ChipDistribution(
            recent5dCost = recent5dCost,
            recent5dFloatPct = recent5dFloatPct,
            recent20dCost = recent20dCost,
            recent20dFloatPct = recent20dFloatPct,
        )

        val centers = DoubleArray(BINS) { lowPrice + (it + 0.5) * width }
        val profitRatioPct = centers.indices.sumOf { i -> if (centers[i] <= currentPrice) dens[i] else 0.0 } / total * 100
        val trapped = TRAPPED_BANDS.associateWith { band ->
            val upper = currentPrice * (1 + band / 100.0)
            centers.indices.sumOf { i ->
                if (currentPrice < centers[i] && centers[i] <= upper) dens[i] else 0.0
            } / total * 100
        }
        var pressurePrice: Double? = null
        var pressureDistancePct: Double? = null
        centers.indices
            .filter { centers[it] > currentPrice && dens[it] > 0 }
            .maxByOrNull { dens[it] }
            ?.let {
                pressurePrice = centers[it]
                pressureDistancePct = (centers[it] / currentPrice - 1) * 100
            }

        return ChipDistribution(
            profitRatioPct = profitRatioPct,
            trappedAbovePct = trapped,
            pressurePrice = pressurePrice,
            pressureDistancePct = pressureDistancePct,
            recent5dCost = recent5dCost,
            recent5dFloatPct = recent5dFloatPct,
            recent20dCost = recent20dCost,
            recent20dFloatPct = recent20dFloatPct,
        )
    }

    private fun weightedMean(pairs: List<Pair<Double, Double>>): Double? {
        val totalWeight = pairs.sumOf { it.second }
        if (totalWeight <= 0) return null
        return pairs.sumOf { it.first * it.second } / totalWeight
    }
}
