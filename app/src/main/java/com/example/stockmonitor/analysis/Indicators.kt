package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 技术指标计算，逐条对应 stock_analysis.py / stock_menubar.py 里的 pandas 公式：
 * - MACD：ewm(span=12/26/9, adjust=False)，柱体 = 2*(DIF-DEA)
 * - RSI：ewm(alpha=1/14, adjust=False)，从 index 0 以 0 起始递归，前 13 个值无效
 * - KDJ：9 日滚动高低点，RSV 补 50，K/D 用 alpha=1/3，J = 3K-2D
 * - ATR：真实波幅 ewm(alpha=1/14)
 * - BOLL：20 日均值 ± 2 倍总体标准差（ddof=0）
 * - ADX/+DI/-DI：Wilder 平滑（alpha=1/14）
 * - OBV：近 20 日量能方向累计
 */
object Indicators {

    /** pandas: ewm(span=span, adjust=False) */
    fun emaSpan(values: List<Double>, span: Int): DoubleArray =
        emaAlpha(values, 2.0 / (span + 1))

    /** pandas: ewm(alpha=alpha, adjust=False)，NaN 处沿用上一值。 */
    fun emaAlpha(values: List<Double>, alpha: Double): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        var previous = Double.NaN
        for (i in values.indices) {
            val value = values[i]
            if (value.isNaN()) {
                out[i] = previous
                continue
            }
            previous = if (previous.isNaN()) value else alpha * value + (1 - alpha) * previous
            out[i] = previous
        }
        return out
    }

    fun macd(close: List<Double>): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val fast = emaSpan(close, 12)
        val slow = emaSpan(close, 26)
        val dif = DoubleArray(close.size) { fast[it] - slow[it] }
        val dea = emaSpan(dif.toList(), 9)
        val histogram = DoubleArray(close.size) { 2 * (dif[it] - dea[it]) }
        return Triple(dif, dea, histogram)
    }

    fun rsi(close: List<Double>, period: Int = 14): DoubleArray {
        val out = DoubleArray(close.size) { Double.NaN }
        if (close.size < 2) return out
        val alpha = 1.0 / period
        var averageGain = 0.0
        var averageLoss = 0.0
        for (i in close.indices) {
            if (i > 0) {
                val delta = close[i] - close[i - 1]
                val gain = if (delta > 0) delta else 0.0
                val loss = if (delta < 0) -delta else 0.0
                averageGain = alpha * gain + (1 - alpha) * averageGain
                averageLoss = alpha * loss + (1 - alpha) * averageLoss
            }
            if (i < period - 1) continue
            // pandas 在平均跌幅为 0 时 rs 为 NaN，这里保持一致的“无 RSI 值”。
            out[i] = if (averageLoss == 0.0) Double.NaN
            else 100 - 100 / (1 + averageGain / averageLoss)
        }
        return out
    }

    fun kdj(high: List<Double>, low: List<Double>, close: List<Double>): KdjSeries {
        val size = close.size
        val rsv = DoubleArray(size)
        for (i in 0 until size) {
            val from = max(0, i - 8)
            var lowest = Double.MAX_VALUE
            var highest = -Double.MAX_VALUE
            for (j in from..i) {
                lowest = min(lowest, low[j])
                highest = max(highest, high[j])
            }
            rsv[i] = if (highest == lowest) 50.0 else (close[i] - lowest) / (highest - lowest) * 100
        }
        val k = emaAlpha(rsv.toList(), 1.0 / 3)
        val d = emaAlpha(k.toList(), 1.0 / 3)
        val j = DoubleArray(size) { 3 * k[it] - 2 * d[it] }
        return KdjSeries(k, d, j)
    }

    fun trueRange(high: List<Double>, low: List<Double>, close: List<Double>): DoubleArray {
        val out = DoubleArray(close.size)
        for (i in close.indices) {
            out[i] = if (i == 0) high[0] - low[0]
            else maxOf(high[i] - low[i], abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1]))
        }
        return out
    }

    fun atr(high: List<Double>, low: List<Double>, close: List<Double>): DoubleArray =
        emaAlpha(trueRange(high, low, close).toList(), 1.0 / 14)

    fun bollinger(close: List<Double>, period: Int = 20, mult: Double = 2.0): BollSeries {
        val size = close.size
        val middle = DoubleArray(size) { Double.NaN }
        val upper = DoubleArray(size) { Double.NaN }
        val lower = DoubleArray(size) { Double.NaN }
        for (i in period - 1 until size) {
            val window = close.subList(i - period + 1, i + 1)
            val mean = window.sum() / period
            var variance = 0.0
            for (value in window) variance += (value - mean) * (value - mean)
            val std = sqrt(variance / period)
            middle[i] = mean
            upper[i] = mean + mult * std
            lower[i] = mean - mult * std
        }
        return BollSeries(middle, upper, lower)
    }

    fun adx(high: List<Double>, low: List<Double>, close: List<Double>): AdxSeries {
        val size = close.size
        val plusDm = DoubleArray(size)
        val minusDm = DoubleArray(size)
        for (i in 1 until size) {
            val upMove = high[i] - high[i - 1]
            val downMove = low[i - 1] - low[i]
            if (upMove > downMove && upMove > 0) plusDm[i] = upMove
            if (downMove > upMove && downMove > 0) minusDm[i] = downMove
        }
        val tr14 = emaAlpha(trueRange(high, low, close).toList(), 1.0 / 14)
        val plusSmooth = emaAlpha(plusDm.toList(), 1.0 / 14)
        val minusSmooth = emaAlpha(minusDm.toList(), 1.0 / 14)
        val plusDi = DoubleArray(size) { Double.NaN }
        val minusDi = DoubleArray(size) { Double.NaN }
        val dx = DoubleArray(size) { Double.NaN }
        for (i in 0 until size) {
            if (tr14[i] == 0.0) continue
            plusDi[i] = 100 * plusSmooth[i] / tr14[i]
            minusDi[i] = 100 * minusSmooth[i] / tr14[i]
            val sum = plusDi[i] + minusDi[i]
            if (sum != 0.0) dx[i] = abs(plusDi[i] - minusDi[i]) / sum * 100
        }
        return AdxSeries(plusDi, minusDi, emaAlpha(dx.toList(), 1.0 / 14))
    }

    /** OBV 近 window 日净变化。 */
    fun obvChange(close: List<Double>, volume: List<Double>, window: Int = 20): Double {
        var total = 0.0
        for (i in max(0, close.size - window) until close.size) {
            if (i == 0) continue
            if (close[i] > close[i - 1]) total += volume[i]
            else if (close[i] < close[i - 1]) total -= volume[i]
        }
        return total
    }

    /** 成交量加权分位数，用于筹码区间估算。 */
    fun weightedQuantile(values: List<Double>, weights: List<Double>, quantile: Double): Double {
        val order = values.indices.sortedBy { values[it] }
        val sortedValues = order.map { values[it] }
        val sortedWeights = order.map { max(weights[it], 0.0) }
        val total = sortedWeights.sum()
        if (total <= 0) return percentile(sortedValues, quantile)
        val target = quantile * total
        val cumulative = DoubleArray(sortedValues.size)
        var running = 0.0
        for (i in sortedValues.indices) {
            running += sortedWeights[i]
            cumulative[i] = running
        }
        if (target <= cumulative.first()) return sortedValues.first()
        if (target >= cumulative.last()) return sortedValues.last()
        // 与 numpy.interp 一致：在累计权重上做线性插值
        for (i in 1 until cumulative.size) {
            if (cumulative[i] >= target) {
                val x0 = cumulative[i - 1]
                val x1 = cumulative[i]
                if (x1 == x0) return sortedValues[i]
                val ratio = (target - x0) / (x1 - x0)
                return sortedValues[i - 1] + (sortedValues[i] - sortedValues[i - 1]) * ratio
            }
        }
        return sortedValues.last()
    }

    private fun percentile(sortedValues: List<Double>, quantile: Double): Double {
        if (sortedValues.isEmpty()) return Double.NaN
        val position = quantile * (sortedValues.size - 1)
        val lower = position.toInt()
        val upper = min(lower + 1, sortedValues.size - 1)
        val fraction = position - lower
        return sortedValues[lower] * (1 - fraction) + sortedValues[upper] * fraction
    }

    fun mean(values: List<Double>): Double = if (values.isEmpty()) Double.NaN else values.sum() / values.size

    data class KdjSeries(val k: DoubleArray, val d: DoubleArray, val j: DoubleArray)
    data class BollSeries(val middle: DoubleArray, val upper: DoubleArray, val lower: DoubleArray)
    data class AdxSeries(val plusDi: DoubleArray, val minusDi: DoubleArray, val adx: DoubleArray)
}

/** 筹码峰估算结果，对应 stock_analysis._estimate_chip_peaks。 */
data class ChipPeaks(
    val averageCost: Double?,
    val peaks: List<Pair<Double, Double>>,
    val chip70Lower: Double?,
    val chip70Upper: Double?,
    val chip70WidthPct: Double?,
    val concentration: String,
    val method: String,
    var pricePosition: String = "无法判断",
    var interpretation: String = "筹码数据不足，无法判断集中度。",
    var controlCaution: String = "筹码集中不等于主力控盘，需结合趋势、换手和量价确认。",
)

object ChipEstimator {
    /** 近 120 个交易日收盘价按成交量加权估算成交密集区。 */
    fun estimate(bars: List<DayBar>): ChipPeaks {
        val sample = bars.takeLast(120).filter { it.close > 0 }
        if (sample.isEmpty()) {
            return ChipPeaks(null, emptyList(), null, null, null, "数据不足", "无有效价格数据")
        }
        val prices = sample.map { it.close }
        var weights = sample.map { it.volume }
        if (weights.sum() <= 0) weights = sample.map { 1.0 }

        val lower70 = Indicators.weightedQuantile(prices, weights, 0.15)
        val upper70 = Indicators.weightedQuantile(prices, weights, 0.85)
        val middle = (lower70 + upper70) / 2
        val widthPct = if (middle != 0.0) (upper70 - lower70) / middle * 100 else null
        val concentration = when {
            widthPct == null -> "数据不足"
            widthPct <= 12 -> "较集中"
            widthPct <= 25 -> "中等"
            else -> "较分散"
        }
        val lowPrice = prices.min()
        val highPrice = prices.max()
        if (highPrice <= lowPrice) {
            return ChipPeaks(
                averageCost = prices.last(),
                peaks = listOf(prices.last() to 100.0),
                chip70Lower = lower70,
                chip70Upper = upper70,
                chip70WidthPct = widthPct,
                concentration = concentration,
                method = "价格无波动，无法形成峰值区间",
            )
        }
        val bucketCount = 20
        val step = (highPrice - lowPrice) / bucketCount
        val buckets = DoubleArray(bucketCount)
        for (i in prices.indices) {
            val index = min(bucketCount - 1, max(0, ((prices[i] - lowPrice) / step).toInt()))
            buckets[index] += weights[i]
        }
        val total = buckets.sum()
        val peaks = buckets.indices
            .sortedByDescending { buckets[it] }
            .take(3)
            .filter { buckets[it] > 0 }
            .map { ((lowPrice + step * it) + (lowPrice + step * (it + 1))) / 2 to buckets[it] / total * 100 }
        val averageCost = prices.indices.sumOf { prices[it] * weights[it] } / weights.sum()
        return ChipPeaks(
            averageCost = averageCost,
            peaks = peaks,
            chip70Lower = lower70,
            chip70Upper = upper70,
            chip70WidthPct = widthPct,
            concentration = concentration,
            method = "近120个交易日收盘价按成交量加权估算；70%区间取加权15%至85%分位",
        )
    }
}
