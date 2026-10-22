package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ActiveStock
import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.FiveOneThreeStats
import com.example.stockmonitor.core.Http
import com.example.stockmonitor.core.ScreenHit
import com.example.stockmonitor.core.ScreenResult
import com.example.stockmonitor.core.SinaClient
import com.example.stockmonitor.core.TrendScreenHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 选股扫描，逐条对应 stock_menubar.screen_by_indicators / 513 / 趋势入场策略。
 *
 * 按池子和成本拆成两个入口，对应两个手动按钮：
 * - [screenIndicators]：成交量前 200、只算 MACD / RSI / KDJ 两档——秒级，随时可点；
 * - [screenPatterns]：全市场趋势 + 513（指标两档仍只对活跃前 200 算）——数千根日K，
 *   盘中不自动跑，只在夜间窗口自动一轮或手动触发。
 *
 * 严格策略：MACD DIF/DEA 零轴上方金叉 且 RSI < 30 且 KDJ 低位金叉（K < 30）。
 * 宽松预警：MACD改善必选，RSI < 45 或低位K>D至少一项，排除M20明显破位，最多20只。
 */
class IndicatorScreener(private val sina: SinaClient) {

    /** 指标快扫：只扫成交量前 200，只出严格/宽松两档，不动趋势/513 的既有结果。 */
    suspend fun screenIndicators(
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): ScreenResult {
        val universe = sina.activeStocks(INDICATOR_UNIVERSE_LIMIT)
        return scan(
            universe = universe,
            indicatorCodes = universe.mapTo(HashSet()) { it.code },
            runPatterns = false,
            onProgress = onProgress,
        )
    }

    /**
     * 形态深扫：全市场。列表按成交量降序返回，前 [INDICATOR_UNIVERSE_LIMIT] 只天然就是
     * 指标档的活跃池，不必再单独拉一次列表。
     */
    suspend fun screenPatterns(
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): ScreenResult {
        val universe = sina.allAStocks()
        return scan(
            universe = universe,
            indicatorCodes = universe.take(INDICATOR_UNIVERSE_LIMIT).mapTo(HashSet()) { it.code },
            runPatterns = true,
            onProgress = onProgress,
        )
    }

    private suspend fun scan(
        universe: List<ActiveStock>,
        indicatorCodes: Set<String>,
        runPatterns: Boolean,
        onProgress: ((done: Int, total: Int) -> Unit)?,
    ): ScreenResult = withContext(Dispatchers.IO) {
        if (universe.isEmpty()) throw Http.HttpError("新浪API未返回数据")
        val benchmark = runCatching { sina.kline("sh000001", 120) }.getOrNull()
        val elapsedTradingMinutes = TradingCalendar.tradingMinutesElapsed()
        val semaphore = Semaphore(4)
        var completed = 0
        val total = universe.size
        val evaluated = coroutineScope {
            universe.map { stock ->
                async {
                    val hit = semaphore.withPermit {
                        evaluate(stock, stock.code in indicatorCodes, runPatterns, benchmark, elapsedTradingMinutes)
                    }
                    synchronized(this@IndicatorScreener) {
                        completed++
                        onProgress?.invoke(completed, total)
                    }
                    hit
                }
            }.awaitAll()
        }

        val successful = evaluated.count { it != null }
        val coverage = if (total > 0) successful.toDouble() / total else 0.0
        if (total == 0 || coverage < 0.5) {
            throw Http.HttpError("历史数据成功率过低: $successful/$total")
        }

        val filled = fillLiveData(evaluated)
        val strict = orderStrictHits(
            evaluated.mapNotNull { item -> item?.takeIf { it.strict }?.hit?.let { filled[it.code] } }
        )
        val relaxed = RelaxedScreenSelection.select(
            evaluated.mapNotNull { item -> item?.takeIf { it.relaxed }?.hit?.let { filled[it.code] } }
        )
        if (!runPatterns) {
            return@withContext ScreenResult(strict, relaxed)
        }
        val fiveOneThree = orderFiveOneThreeHits(evaluated.mapNotNull { it?.fiveOneThree })
        val trend = TrendScreenSelection.select(evaluated.mapNotNull { it?.trend })
        val fiveOneThreeStats = FiveOneThreeStats(
            totalStocks = total,
            historyAvailable = evaluated.count { it?.fiveOneThreeInspection?.historyAvailable == true },
            historicalFiveBullish = evaluated.count { it?.fiveOneThreeInspection?.historicalFiveBullish == true },
            recentFiveBullish = evaluated.count { it?.fiveOneThreeInspection?.recentFiveBullish == true },
            waitingConfirmation = evaluated.count {
                it?.fiveOneThree?.stage == FiveOneThreeStage.WAITING_CONFIRMATION
            },
            confirmed = evaluated.count {
                it?.fiveOneThree?.stage == FiveOneThreeStage.CONFIRMED
            },
        )
        ScreenResult(strict, relaxed, fiveOneThree, fiveOneThreeStats, trend)
    }

    /**
     * 给命中项补「当日涨跌幅 + 量比 + 价格位置」。
     *
     * 新浪日线接口盘中不含当日（最后一根是昨天），所以这两个值只能另取一次实时行情；
     * 取不到就保持 null，界面不显示量价标签，不影响选股结果本身。
     */
    private fun fillLiveData(evaluated: List<Evaluated?>): Map<String, ScreenHit> {
        val items = evaluated.filterNotNull().filter { it.hit != null }
        if (items.isEmpty()) return emptyMap()
        val live = runCatching { sina.quotes(items.mapNotNull { it.hit?.code }) }.getOrDefault(emptyMap())
        val elapsed = TradingCalendar.tradingMinutesElapsed()
        return items.associate { item ->
            val hit = item.hit ?: error("带入实时行情的选股项缺少指标命中数据")
            val quote = live[hit.code]
            hit.code to if (quote == null) {
                hit
            } else {
                hit.copy(
                    changePct = quote.changePct,
                    volumeRatio = VolumeRatio.of(quote.volume, item.avgVolume20, elapsed),
                    priceContext = item.window?.contextOf(quote.price),
                )
            }
        }
    }

    /**
     * 把当日实时快照拼成一根 K 线接到日线末尾。
     *
     * 新浪日线接口盘中不含当日（最后一根是昨天），不补的话选股的指标和「当前价」都会滞后一天，
     * 而 macOS 版用的 akshare 日线是含当日的。收盘后日线已含当日，此时直接用日线数据，不再覆盖。
     */
    private fun withTodayBar(history: List<DayBar>, stock: ActiveStock): List<DayBar> {
        val today = TimeUtils.todayText()
        if (history.lastOrNull()?.date == today) return history
        if (stock.price <= 0) return history
        return history + DayBar(
            date = today,
            open = stock.open.takeIf { it > 0 } ?: stock.price,
            high = stock.high.takeIf { it > 0 } ?: stock.price,
            low = stock.low.takeIf { it > 0 } ?: stock.price,
            close = stock.price,
            volume = stock.volume,
        )
    }

    /** 量比的分母要排除当日，口径见 VolumeRatio.averageVolumeExcludingToday。 */
    private fun averageVolumeExcludingToday(bars: List<DayBar>): Double =
        VolumeRatio.averageVolumeExcludingToday(bars) ?: 0.0

    private data class Evaluated(
        val hit: ScreenHit?,
        val strict: Boolean,
        val relaxed: Boolean,
        val fiveOneThree: FiveOneThreeHit?,
        val fiveOneThreeInspection: FiveOneThreeInspection,
        val trend: TrendScreenHit?,
        /** 20 日均量（不含当日），量比的分母；日线里算一次即可。 */
        val avgVolume20: Double,
        /** 近 60 日价格区间（不含当日），用于判断量价标签该往哪个方向解释。 */
        val window: PriceWindow?,
    )

    private suspend fun evaluate(
        stock: ActiveStock,
        includeIndicators: Boolean,
        runPatterns: Boolean,
        benchmark: List<DayBar>?,
        elapsedTradingMinutes: Int,
    ): Evaluated? {
        return try {
            // 限速用 delay 而不是 Thread.sleep：不占死 IO 线程，四个并发槽位也更稳。
            delay(60)
            val history = sina.kline(stock.code, 120)
            if (history.size < 35) return null
            // 快扫不算形态：513/趋势的判定和统计都跳过，结果里两档列表保持为空。
            val fiveOneThreeInspection = if (runPatterns) {
                FiveOneThreeScreener.inspect(
                    code = stock.code,
                    name = stock.name,
                    bars = history,
                    today = TimeUtils.todayText(),
                    currentPrice = stock.price,
                )
            } else {
                FiveOneThreeInspection(false, false, false, null)
            }
            val fiveOneThree = fiveOneThreeInspection.hit
            // 日线序列是否已经带上当日随数据源而变，缺了就补一根，否则指标和价格会滞后一天。
            val bars = withTodayBar(history, stock)
            val avgVolume20 = averageVolumeExcludingToday(bars)
            val trendHit = if (runPatterns) {
                val trendSetup = TrendEntryStrategy.evaluateDaily(
                    bars,
                    benchmark,
                    elapsedTradingMinutes = elapsedTradingMinutes,
                )
                trendSetup.takeIf { it.type != EntrySetupType.NONE }?.let {
                    TrendScreenHit(
                        code = stock.code,
                        name = stock.name,
                        price = stock.price,
                        changePct = if (stock.prevClose > 0) {
                            (stock.price / stock.prevClose - 1) * 100
                        } else {
                            Double.NaN
                        },
                        volumeRatio = VolumeRatio.of(stock.volume, avgVolume20, elapsedTradingMinutes),
                        entrySetup = it,
                    )
                }
            } else {
                null
            }
            if (!includeIndicators) {
                return Evaluated(
                    hit = null,
                    strict = false,
                    relaxed = false,
                    fiveOneThree = fiveOneThree,
                    fiveOneThreeInspection = fiveOneThreeInspection,
                    trend = trendHit,
                    avgVolume20 = 0.0,
                    window = null,
                )
            }
            val close = bars.map { it.close }
            val high = bars.map { it.high }
            val low = bars.map { it.low }
            val (dif, dea, _) = Indicators.macd(close)
            val rsi = Indicators.rsi(close)
            val kdj = Indicators.kdj(high, low, close)

            val last = bars.size - 1
            val previous = last - 1
            val latestDif = dif[last]
            val latestDea = dea[last]
            val previousDif = dif[previous]
            val previousDea = dea[previous]
            val latestK = kdj.k[last]
            val latestD = kdj.d[last]
            val previousK = kdj.k[previous]
            val previousD = kdj.d[previous]
            val latestRsi = rsi[last]

            // 严格策略
            val strictMacd = previousDif <= previousDea && latestDif > latestDea &&
                latestDif > 0 && latestDea > 0
            val strictRsi = !latestRsi.isNaN() && latestRsi < 30
            val strictKdj = previousK <= previousD && latestK > latestD && latestK < 30

            // 宽松策略
            val latestHist = latestDif - latestDea
            val previousHist = previousDif - previousDea
            val crossZero = previousHist < 0 && latestHist >= 0
            val narrowRising = latestHist < 0 && latestHist > previousHist && latestDif > previousDif
            val expandPositive = latestHist > 0 && latestHist > previousHist
            val relaxedMacd = crossZero || narrowRising || expandPositive
            val macdDetail = when {
                crossZero -> "柱体转正"
                narrowRising -> "柱体收窄+DIF↑"
                expandPositive -> "柱体放大"
                else -> ""
            }
            val relaxedRsi = !latestRsi.isNaN() && latestRsi < 45
            val relaxedKdj = latestK > latestD && latestK < 50
            val relaxedScore = listOf(relaxedMacd, relaxedRsi, relaxedKdj).count { it }

            Evaluated(
                avgVolume20 = avgVolume20,
                window = PricePosition.of(bars, TimeUtils.todayText()),
                hit = ScreenHit(
                    code = stock.code,
                    name = stock.name,
                    price = close[last],
                    rsi = latestRsi,
                    k = latestK,
                    d = latestD,
                    dif = latestDif,
                    relaxedScore = relaxedScore,
                    macdDetail = if (relaxedMacd) macdDetail else "",
                    rsiOk = relaxedRsi,
                    kdjOk = relaxedKdj,
                    relaxedTrendEligible = RelaxedScreenSelection.trendEligible(bars),
                ),
                strict = strictMacd && strictRsi && strictKdj,
                relaxed = relaxedMacd && (relaxedRsi || relaxedKdj),
                fiveOneThree = fiveOneThree,
                fiveOneThreeInspection = fiveOneThreeInspection,
                trend = trendHit,
            )
        } catch (error: Exception) {
            null
        }
    }

    companion object {
        /** 指标两档的扫描池：成交量前 N 只活跃股。 */
        const val INDICATOR_UNIVERSE_LIMIT = 200
    }
}

/** 严格档先按条件评分降序；同分时按 RSI 升序，越靠前越超卖。 */
internal fun orderStrictHits(hits: List<ScreenHit>): List<ScreenHit> =
    hits.sortedWith(
        compareByDescending<ScreenHit> { it.relaxedScore }
            .thenBy { it.rsi }
            .thenBy { it.code },
    )

/**
 * 宽松档先按满足项数降序，再优先DIF非负、KDJ修复；不把更低RSI视为更高优先级。
 * 同等条件优先指标完整项，最后按代码保持稳定排序。
 */
internal fun orderRelaxedHits(hits: List<ScreenHit>): List<ScreenHit> =
    hits.sortedWith(
        compareByDescending<ScreenHit> { it.relaxedScore }
            .thenByDescending { it.dif.isFinite() && it.dif >= 0 }
            .thenByDescending { it.kdjOk }
            .thenByDescending { it.rsi.isFinite() }
            .thenBy { it.code },
    )

internal fun orderFiveOneThreeHits(hits: List<FiveOneThreeHit>): List<FiveOneThreeHit> =
    hits.sortedWith(
        compareByDescending<FiveOneThreeHit> { it.stage.ordinal }
            .thenByDescending { it.gain60Pct }
            .thenBy { it.name },
    )

internal fun orderTrendHits(hits: List<TrendScreenHit>): List<TrendScreenHit> =
    hits.sortedWith(
        compareByDescending<TrendScreenHit> { it.entrySetup.canBuy }
            .thenByDescending { it.entrySetup.confirmed }
            .thenBy { it.entrySetup.type.ordinal }
            .thenByDescending { it.volumeRatio ?: 0.0 },
    )
