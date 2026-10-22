package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.FundFlow
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.RowSnapshot
import java.util.Locale
import kotlin.math.abs

data class OpeningStructure(
    val previousClose: Double,
    val open: Double,
    val gapPct: Double,
    val openToCurrentPct: Double,
    val type: String,
    val structure: String,
)

data class Volatility(
    val atr: Double,
    val atrPct: Double?,
    val bollMid: Double,
    val bollUpper: Double,
    val bollLower: Double,
    val bollPosition: Double?,
)

data class TrendStrength(val adx: Double?, val plusDi: Double?, val minusDi: Double?)

data class MacdValues(val dif: Double, val dea: Double, val hist: Double, val previousHist: Double)

data class KdjValues(val k: Double, val d: Double, val j: Double)

/** 个股技术分析结果，字段对应 stock_analysis.analyze_history 的返回结构。 */
data class TechnicalResult(
    val code: String,
    val name: String,
    val asOf: String,
    val rows: Int,
    val price: Double,
    val priceSource: String,
    val priceChange: Double,
    val opening: OpeningStructure,
    val volumeRatio: Double?,
    /** 量比是否为「盘中按已交易时间折算」的口径；false 表示最近一日/20日均量的纯日线口径。 */
    val volumeRatioIntraday: Boolean,
    /** 价格位置上下文（近 60 日区间位置 + 从区间低点反弹幅度），决定量价信号往哪个方向解释；日线不足时为 null。 */
    val priceContext: PriceContext?,
    val volatility: Volatility,
    val trendStrength: TrendStrength,
    val obvChange: Double,
    /** OBV 量价背离判定（顶/底背离）。判定比的是两段行情的 OBV 峰谷值，不是价格高点当日读数。 */
    val divergence: DivergenceResult,
    val relativeStrength: Double?,
    val ma5: Double,
    val ma10: Double,
    val ma20: Double,
    val ma60: Double,
    val support: Double,
    val resistance: Double,
    val entrySetup: EntrySetup,
    val macd: MacdValues,
    val rsi: Double?,
    val kdj: KdjValues,
    val chip: ChipPeaks,
    val score: Int,
    val signals: List<String>,
    /** 量价那条结论（如「低位放量滞涨，量能主要在换手，尚需价格确认」）。signals 末尾只留三条，报告第二节单独用它。 */
    val volumeNote: String? = null,
    val action: String,
    val trendAssessment: TrendAssessment? = null,
    val boxRange: BoxRangeAssessment? = null,
    val highPositionEvidence: HighPositionEvidence? = null,
    val highPosition: HighPositionDecision? = null,
    val impulse: ImpulseContext? = null,
    val fundFlow: FundFlow? = null,
    val fundFlowAssessment: FundFlowAssessment? = null,
    val fundFlowNote: String? = null,
    val fundFlowScoreDelta: Int = 0,
    val fundFlowWindowAssessment: FundFlowWindowAssessment? = null,
    val fundFlowWindowNote: String? = null,
) {
    val intradayScore: Int
        get() = score - fundFlowScoreDelta
}

/**
 * 单股技术分析，逐条对应 stock_analysis.analyze_history / format_report。
 * 只做本地计算，网络请求由调用方负责。
 */
object StockAnalysis {

    fun analyze(
        history: List<DayBar>,
        code: String,
        name: String,
        live: Quote? = null,
        benchmark: List<DayBar>? = null,
        /**
         * 今日已过的交易分钟数（09:30 起算，午休不计）。
         * 默认 240 = 全天口径，不做盘中折算，与 macOS 版参考实现保持一致；
         * 盘中调用时传 TradingCalendar.tradingMinutesElapsed()。
         */
        elapsedTradingMinutes: Int = VolumeRatio.TRADING_MINUTES,
        fundFlow: FundFlow? = null,
        fundFlowHistory: List<FundFlow> = emptyList(),
    ): TechnicalResult {
        require(history.size >= 60) { "历史数据不足60个交易日，无法进行稳定分析" }
        val bars = history.filter { it.close > 0 }
        require(bars.size >= 60) { "清洗有效历史数据不足60个交易日" }

        val close = bars.map { it.close }
        val high = bars.map { it.high }
        val low = bars.map { it.low }
        val volume = bars.map { it.volume }

        val (dif, dea, macdHist) = Indicators.macd(close)
        val rsiSeries = Indicators.rsi(close)
        val kdjSeries = Indicators.kdj(high, low, close)
        val atrSeries = Indicators.atr(high, low, close)
        val boll = Indicators.bollinger(close)
        val adxSeries = Indicators.adx(high, low, close)
        val obvChange = Indicators.obvChange(close, volume)
        // OBV 量价背离（顶/底背离）：对照阶段高/低点所在段的 OBV 峰谷值，
        // 回答「价创新高时 OBV 跟没跟上」。currentPrice 只参与「是否创新高」，不动历史摆动结构。
        val divergence = ObvDivergence.evaluate(bars, currentPrice = live?.price)

        val latest = bars.size - 1
        val previous = maxOf(0, latest - 1)
        val price = live?.price ?: close[latest]
        val previousClose = live?.prevClose ?: close[previous]
        val todayOpen = live?.open ?: bars[latest].open
        val priceChange = live?.changePct
            ?: (if (previousClose != 0.0) (price / previousClose - 1) * 100 else 0.0)

        val gapPct = if (previousClose != 0.0) (todayOpen / previousClose - 1) * 100 else 0.0
        val openToCurrentPct = if (todayOpen != 0.0) (price / todayOpen - 1) * 100 else 0.0
        val openingType = when {
            gapPct >= 1 -> "高开"
            gapPct <= -1 -> "低开"
            else -> "平开"
        }
        val openingStructure = when {
            openToCurrentPct >= 0.3 ->
                "$openingType" + "后站稳/走强（现价较开盘" + pct(openToCurrentPct) + "）"
            openToCurrentPct <= -0.3 ->
                "$openingType" + "低走（现价较开盘" + pct(openToCurrentPct) + "）"
            else -> "$openingType" + "后横盘（现价较开盘" + pct(openToCurrentPct) + "）"
        }

        // 分母排除当日：收盘后新浪日线已补上当日，不剔除的话今天的量会同时出现在分子和分母里，
        // 报告里的量比就会比「自选股 / 选股列表」低一截。口径见 VolumeRatio.averageVolumeExcludingToday。
        val historicalVolume20 = VolumeRatio.averageVolumeExcludingToday(bars) ?: 0.0
        // 隔夜 / 集合竞价（elapsed = 0）时实时行情里的成交量不是「今日累计」，不能当分子用，
        // 退回下面的日线口径（最近一日 ÷ 20日均量），口径与展示文案才对得上。
        val liveVolume = live?.volume?.takeIf { it > 0 && elapsedTradingMinutes > 0 }
        // 量比口径统一走 VolumeRatio（「自选股」列表的「放量」标识用的是同一套计算）：
        // 盘中把今日累计成交量按已过交易时间折算到全天再除 20 日均量，
        // 收盘后不折算；没有实时成交量时退回最近一日成交量 ÷ 20 日均量。
        val volumeRatio = when {
            historicalVolume20 <= 0 -> null
            liveVolume != null -> VolumeRatio.of(liveVolume, historicalVolume20, elapsedTradingMinutes)
            else -> volume[latest] / historicalVolume20
        }

        val ma5 = Indicators.mean(close.takeLast(5))
        val ma10 = Indicators.mean(close.takeLast(10))
        val ma20 = Indicators.mean(close.takeLast(20))
        val ma60 = Indicators.mean(close.takeLast(60))
        val support = low.takeLast(20).min()
        val resistance = high.takeLast(20).max()
        // 位置之外还要带上「从区间低点反弹多少」：同是低位，「贴底磨」与「已弹起来」的含义相反。
        val priceContext = PricePosition.of(bars, TimeUtils.todayText())?.contextOf(price)
        val location = priceContext?.location

        val chip = ChipEstimator.estimate(bars)
        val difValue = dif[latest]
        val deaValue = dea[latest]
        val histValue = macdHist[latest]
        val previousHistValue = macdHist[previous]
        val rsiValue = rsiSeries[latest].takeIf { !it.isNaN() }
        val kValue = kdjSeries.k[latest]
        val dValue = kdjSeries.d[latest]
        val jValue = kdjSeries.j[latest]
        val atrValue = atrSeries[latest]
        val bollMiddle = boll.middle[latest]
        val bollUpper = boll.upper[latest]
        val bollLower = boll.lower[latest]
        val adxValue = adxSeries.adx[latest].takeIf { !it.isNaN() }
        val plusDiValue = adxSeries.plusDi[latest].takeIf { !it.isNaN() }
        val minusDiValue = adxSeries.minusDi[latest].takeIf { !it.isNaN() }

        val relativeStrength = computeRelativeStrength(close, benchmark)
        val entrySetup = TrendEntryStrategy.evaluate(
            history = bars,
            price = price,
            volumeRatio = volumeRatio,
            atr = atrValue,
            benchmark = benchmark,
            currentOpen = live?.open,
            currentHigh = live?.high,
            currentLow = live?.low,
            elapsedTradingMinutes = elapsedTradingMinutes,
        )
        val trendAssessment = TrendAssessmentPolicy.evaluateBars(
            history = bars,
            asOf = TimeUtils.todayText(),
        )
        val highPositionEvidence = HighPositionEvidence(
            price = price,
            rangeHigh = high.takeLast(60).maxOrNull() ?: resistance,
            rangeLow = low.takeLast(60).minOrNull() ?: support,
            atrPct = if (price > 0) atrValue / price * 100 else 0.0,
            gain5Pct = if (close.size >= 6) (price / close[close.lastIndex - 5] - 1.0) * 100 else 0.0,
            volumeRatio = volumeRatio,
            pullbackVolumeRatio = volumeRatio?.takeIf { priceChange < 0 },
            recentLows = low.takeLast(4),
            recentHighs = high.takeLast(4),
            recentCloses = close.takeLast(4),
            support = support,
            marketHealthy = false,
            sectorHealthy = false,
            dualTimeframeAllowed = false,
            trend = trendAssessment,
        )
        val highPosition = HighPositionPolicy.evaluate(highPositionEvidence)
        val impulse = ImpulseContext.from(close)
        val boxRange = BoxRangePolicy.evaluate(
            history = bars,
            asOf = TimeUtils.todayText(),
            currentPrice = price,
            currentVolumeRatio = volumeRatio,
        )

        // ── 筹码位置判断 ──
        val chipLower = chip.chip70Lower
        val chipUpper = chip.chip70Upper
        when {
            chipLower == null || chipUpper == null -> {
                chip.pricePosition = "无法判断"
                chip.interpretation = "筹码数据不足，无法判断集中度。"
            }
            price < chipLower -> {
                chip.pricePosition = "位于70%筹码区下方"
                chip.interpretation = "筹码主要分布在上方，当前可能面临套牢压力；集中度本身不等于主力控盘。"
            }
            price > chipUpper -> {
                chip.pricePosition = "位于70%筹码区上方"
                chip.interpretation = "价格脱离主要成交区，短线偏强但也要防止乖离过大。"
            }
            chip.concentration == "较集中" && price >= ma20 && obvChange > 0 -> {
                chip.pricePosition = "位于70%筹码区内部"
                chip.interpretation = "筹码较集中且价格/量能配合偏强，存在承接改善迹象，但不能据此确认主力控盘。"
            }
            chip.concentration == "较集中" -> {
                chip.pricePosition = "位于70%筹码区内部"
                chip.interpretation = "筹码较集中，但价格和量能尚未共同确认方向，暂不能判断主力控盘。"
            }
            else -> {
                chip.pricePosition = "位于70%筹码区内部"
                chip.interpretation = "筹码区间不够集中，支撑和压力可能较分散。"
            }
        }
        chip.controlCaution = "筹码集中不等于主力控盘，需结合趋势、换手和量价确认。"

        // ── 评分 ──
        var score = 0
        val signals = mutableListOf<String>()
        if (price > ma20 && ma20 > ma60) {
            score += 2
            signals.add("价格在MA20/MA60上方，趋势偏强")
        } else if (price < ma20 && ma20 < ma60) {
            score -= 2
            signals.add("价格在MA20/MA60下方，趋势偏弱")
        } else {
            signals.add("均线交错，趋势仍在整理")
        }
        if (difValue > deaValue) {
            score += 1
            signals.add("MACD DIF位于DEA上方")
        } else {
            score -= 1
            signals.add("MACD DIF位于DEA下方")
        }
        if (histValue > previousHistValue) signals.add("MACD柱体较前一日改善")
        else signals.add("MACD柱体较前一日走弱")

        if (rsiValue != null) {
            when {
                rsiValue in 45.0..70.0 -> {
                    score += 1
                    signals.add("RSI处于中性偏强区间")
                }
                rsiValue > 75 -> {
                    score -= 1
                    signals.add("RSI偏高，短线有过热风险")
                }
                rsiValue < 30 -> signals.add("RSI处于超卖区，等待反转确认")
            }
        }
        if (kValue > dValue && kValue < 80) {
            score += 1
            signals.add("KDJ中K位于D上方")
        } else if (kValue < dValue) {
            score -= 1
            signals.add("KDJ中K位于D下方")
        }
        // 量价结论单独留一份给报告：signals 只展示末尾三条，这条排在中间会被截掉。
        var volumeNote: String? = null
        if (volumeRatio != null) {
            val volumeNoteStart = signals.size
            // 量价结论要配价格位置一起看：同一个「放量上涨」在低位是资金进场，
            // 在涨了一大段之后的高位更可能是情绪高潮（见 VolumePrice、PricePosition）。
            val flat = abs(priceChange) <= VolumePrice.FLAT_PCT
            val expand = volumeRatio >= VolumeRatio.EXPAND_THRESHOLD
            val shrink = volumeRatio <= VolumeRatio.SHRINK_THRESHOLD
            when {
                expand && flat -> {
                    // 只有确认在高位才按「借量出货」扣分；低位再分「贴底磨」与「已反弹」两档，
                    // 措辞与 VolumePrice 给出的标签保持一致。
                    val rebound = priceContext?.reboundFromLow ?: 0.0
                    when (location) {
                        PriceLocation.HIGH -> {
                            signals.add("高位放量滞涨，量能未能推动价格，需警惕承接不足")
                            score -= 1
                        }
                        PriceLocation.LOW -> if (rebound >= VolumePrice.REBOUND_MIN) {
                            signals.add("低位区间已从低点反弹${pct(rebound * 100)}，放量滞涨更偏冲高遇阻，尚待突破确认")
                        } else {
                            signals.add("低位放量滞涨，量能主要在换手，尚需价格确认")
                        }
                        else -> {
                            signals.add("放量滞涨，价格未动，方向尚未确认")
                        }
                    }
                }
                expand && priceChange > 0 -> {
                    if (location == PriceLocation.HIGH) {
                        // 位置已高，不再计为买入加分，只提示风险。
                        signals.add("高位放量上涨，位置偏高需防冲高回落")
                    } else {
                        signals.add("上涨伴随放量，资金参与度提高")
                        score += 1
                    }
                }
                expand && priceChange < 0 -> {
                    signals.add("下跌伴随放量，需警惕承接不足")
                    score -= 1
                }
                shrink && flat -> signals.add("窄幅缩量，方向尚未确认")
                shrink && priceChange < 0 -> signals.add("缩量下跌，买盘不足，趋势大概率还没走完")
                shrink && priceChange > 0 -> when (location) {
                    PriceLocation.HIGH -> signals.add("高位缩量上涨，多为控盘加速，追高需谨慎")
                    PriceLocation.LOW -> signals.add("低位缩量上涨，多为无量反弹，不是吸筹")
                    else -> signals.add("缩量上涨，筹码惜售，属持有信号而非买点")
                }
            }
            volumeNote = signals.getOrNull(volumeNoteStart)
        }
        if (adxValue != null && plusDiValue != null && minusDiValue != null) {
            when {
                adxValue >= 25 && plusDiValue > minusDiValue -> {
                    score += 1
                    signals.add(String.format(Locale.CHINA, "ADX %.1f，趋势强度较高且多方占优", adxValue))
                }
                adxValue >= 25 -> {
                    score -= 1
                    signals.add(String.format(Locale.CHINA, "ADX %.1f，趋势强度较高但空方占优", adxValue))
                }
                adxValue < 15 ->
                    signals.add(String.format(Locale.CHINA, "ADX %.1f，趋势强度较弱，震荡概率较高", adxValue))
            }
        }
        if (relativeStrength != null) {
            if (relativeStrength > 2) {
                score += 1
                signals.add(String.format(Locale.CHINA, "近20日相对沪指强%+.1f个百分点", relativeStrength))
            } else if (relativeStrength < -2) {
                score -= 1
                signals.add(String.format(Locale.CHINA, "近20日相对沪指弱%+.1f个百分点", relativeStrength))
            }
        }
        // OBV 背离按结论加减分（顶背离 -1 / 底背离 +1；同步与中性不加分），须在 action 计算之前
        score += divergence.scoreDelta
        signals.add(divergence.short)
        if (obvChange > 0) signals.add("OBV近20日净变化为正，量能方向改善")
        else if (obvChange < 0) signals.add("OBV近20日净变化为负，量能方向偏弱")

        if (openToCurrentPct <= -0.3) {
            score -= 1
            signals.add("开盘后走弱，短线承接不足")
        } else if (openToCurrentPct >= 0.3) {
            score += 1
            signals.add("开盘后站稳，短线承接较好")
        }

        val effectiveFundFlow = fundFlow ?: fundFlowHistory.lastOrNull()
        val fundFlowAssessment = FundFlowPolicy.assess(priceChange, effectiveFundFlow)
        val fundFlowWindowAssessment = FundFlowWindowPolicy.assess(fundFlowHistory)
        val fundFlowDelta = listOf(
            FundFlowPolicy.scoreDelta(fundFlowAssessment),
            FundFlowWindowPolicy.scoreDelta(fundFlowWindowAssessment),
        ).let { deltas ->
            when {
                deltas.any { it < 0 } -> -1
                deltas.any { it > 0 } -> 1
                else -> 0
            }
        }
        score += fundFlowDelta
        val fundFlowNote = effectiveFundFlow?.let { fundFlowAssessment.reason }
        val fundFlowWindowNote = fundFlowHistory.takeIf { it.isNotEmpty() }?.let {
            fundFlowWindowAssessment.reason
        }

        val action = when {
            score >= 4 -> "技术面偏强：不建议追高，优先等待回踩MA20或前高突破确认。"
            score >= 2 -> "技术面震荡偏强：可观察，不在放量冲高时追入，等待量价共振。"
            score <= -2 -> "技术面偏弱：无持仓不宜主动介入，等待重新站回MA20并出现放量确认。"
            else -> "技术面中性：暂以观察为主，等待趋势和量能给出更明确方向。"
        }

        return TechnicalResult(
            code = code,
            name = name,
            asOf = TimeUtils.nowText(),
            rows = bars.size,
            price = price,
            priceSource = if (live != null) "实时行情" else "历史日线收盘价（非实时）",
            priceChange = priceChange,
            opening = OpeningStructure(
                previousClose = previousClose,
                open = todayOpen,
                gapPct = gapPct,
                openToCurrentPct = openToCurrentPct,
                type = openingType,
                structure = openingStructure,
            ),
            volumeRatio = volumeRatio,
            volumeRatioIntraday = liveVolume != null && VolumeRatio.isIntraday(elapsedTradingMinutes),
            priceContext = priceContext,
            volatility = Volatility(
                atr = atrValue,
                atrPct = if (price != 0.0) atrValue / price * 100 else null,
                bollMid = bollMiddle,
                bollUpper = bollUpper,
                bollLower = bollLower,
                bollPosition = if (bollUpper > bollLower)
                    (price - bollLower) / (bollUpper - bollLower) * 100 else null,
            ),
            trendStrength = TrendStrength(adxValue, plusDiValue, minusDiValue),
            obvChange = obvChange,
            divergence = divergence,
            relativeStrength = relativeStrength,
            ma5 = ma5,
            ma10 = ma10,
            ma20 = ma20,
            ma60 = ma60,
            support = support,
            resistance = resistance,
            entrySetup = entrySetup,
            macd = MacdValues(difValue, deaValue, histValue, previousHistValue),
            rsi = rsiValue,
            kdj = KdjValues(kValue, dValue, jValue),
            chip = chip,
            score = score,
            signals = signals,
            volumeNote = volumeNote,
            action = action,
            trendAssessment = trendAssessment,
            boxRange = boxRange,
            highPositionEvidence = highPositionEvidence,
            highPosition = highPosition,
            impulse = impulse,
            fundFlow = effectiveFundFlow,
            fundFlowAssessment = effectiveFundFlow?.let { fundFlowAssessment },
            fundFlowNote = fundFlowNote,
            fundFlowScoreDelta = fundFlowDelta,
            fundFlowWindowAssessment = fundFlowWindowNote?.let { fundFlowWindowAssessment },
            fundFlowWindowNote = fundFlowWindowNote,
        )
    }

    private fun computeRelativeStrength(close: List<Double>, benchmark: List<DayBar>?): Double? {
        if (benchmark == null || benchmark.size < 21 || close.size < 21) return null
        val benchmarkClose = benchmark.map { it.close }.filter { it > 0 }
        if (benchmarkClose.size < 21) return null
        val stockReturn = close[close.size - 1] / close[close.size - 21] - 1
        val benchmarkReturn = benchmarkClose[benchmarkClose.size - 1] / benchmarkClose[benchmarkClose.size - 21] - 1
        return (stockReturn - benchmarkReturn) * 100
    }

    fun price(value: Double): String =
        if (value < 100) String.format(Locale.CHINA, "%.2f", value)
        else String.format(Locale.CHINA, "%.0f", value)

    fun pct(value: Double): String = String.format(Locale.CHINA, "%+.2f%%", value)

    /** 完整技术分析报告，等价于 format_report。 */
    /**
     * 个股技术分析报告。
     *
     * [env] 是分析环境（大盘 / 板块 / 上次快照）。**不传就完全照旧**——
     * 指标数值与默认文案一字不改（`StockAnalysisReferenceTest` 逐位对齐 macOS Python 参考）。
     * 传了才做两件事：报告头写清环境，以及**就地改写**那些在塌陷期会说反话的结论
     * （「回踩不破均线可以继续持有」在系统性下跌里正好是错的建议）。
     */
    fun formatReport(result: TechnicalResult, env: EnvContext? = null): String {
        val volumeRatio = result.volumeRatio
        val volumeText = when {
            volumeRatio == null -> "暂无成交量"
            result.volumeRatioIntraday ->
                String.format(Locale.CHINA, "量比 %.2f 倍（今日累计，已按交易时间折算到全天）", volumeRatio)
            else -> String.format(Locale.CHINA, "最近一日/20日均量 %.2f 倍", volumeRatio)
        }
        // 与自选股 / 选股列表同一套标签（同一个 VolumePrice），这里直接给出结论，不再让用户自己拼量比和涨跌幅。
        val volumeSignal = VolumePrice.of(volumeRatio, result.priceChange, result.priceContext)
        val signalText = if (volumeSignal == VolumePriceSignal.NEUTRAL) {
            "标签：量比处于 0.8~1.3 的常态区间，不做量价标注。"
        } else {
            "标签：${VolumePrice.tag(volumeSignal)}"
        }
        val priceContext = result.priceContext
        val locationMeaning = when {
            priceContext == null -> ""
            priceContext.location == PriceLocation.LOW &&
                priceContext.reboundFromLow >= VolumePrice.REBOUND_MIN ->
                "价格处于近60日区间低位，但已从低点反弹${pct(priceContext.reboundFromLow * 100)}，" +
                    "同样的量价形态更偏冲高遇阻，不能直接当底部吸筹讲。"
            priceContext.location == PriceLocation.LOW -> "价格处于近60日区间低位且累计涨幅为负，同样的量价形态更偏底部换手。"
            priceContext.location == PriceLocation.HIGH -> "价格处于近60日区间高位且累计涨幅已大，同样的量价形态要防冲高回落、借量出货。"
            else -> "价格处于近60日区间中位，位置上的倾向不明显。"
        }
        val adx = result.trendStrength.adx
        // 「可以继续持有」在塌陷期是**反话**——系统性下跌里支撑会一起破，所以按环境就地改写。
        val holdAdvice = holdAdvice(env, "回踩不破均线")
        val adxMeaning = when {
            adx == null -> "ADX 数据暂缺，判断不了趋势强度，只能按震荡对待。"
            adx >= 25 && (result.trendStrength.plusDi ?: 0.0) > (result.trendStrength.minusDi ?: 0.0) ->
                String.format(Locale.CHINA, "ADX %.1f 高于 25，当前有趋势且由多方主导，%s。", adx, holdAdvice)
            adx >= 25 ->
                String.format(Locale.CHINA, "ADX %.1f 高于 25，当前有趋势但由空方主导，反弹到压力位要减仓而不是抄底。", adx)
            adx < 15 ->
                String.format(Locale.CHINA, "ADX %.1f 低于 15，趋势还没成型，属震荡行情，金叉死叉容易失效，按区间对待。", adx)
            else ->
                String.format(Locale.CHINA, "ADX %.1f 在 15~25 的过渡区，趋势强度一般，仓位不宜重。", adx)
        }
        val relativeMeaning = when {
            result.relativeStrength == null -> "缺少沪指基准，判断不了相对大盘强弱，这一项跳过。"
            result.relativeStrength > 2 ->
                String.format(Locale.CHINA, "近20日跑赢沪指 %.2f 个百分点，强于大盘，走独立行情的概率更高。", result.relativeStrength)
            result.relativeStrength < -2 ->
                String.format(Locale.CHINA, "近20日跑输沪指 %.2f 个百分点，弱于大盘，反弹大概率也弱于指数。", -result.relativeStrength)
            else ->
                String.format(Locale.CHINA, "近20日与沪指相差 %.2f 个百分点，基本同步，跟大盘走。", result.relativeStrength)
        }
        val macdMeaning =
            (if (result.macd.dif > result.macd.dea) "DIF 在 DEA 上方，处在多头区间；"
            else "DIF 在 DEA 下方，处在空头区间；") +
                if (result.macd.hist > result.macd.previousHist) {
                    String.format(
                        Locale.CHINA,
                        "柱体由 %.3f 改善到 %.3f，下行动能在减弱，但改善不等于反转，要看能否站上压力位。",
                        result.macd.previousHist, result.macd.hist,
                    )
                } else {
                    String.format(
                        Locale.CHINA,
                        "柱体由 %.3f 走弱到 %.3f，动能还在衰减，反弹前先等柱体重新放大。",
                        result.macd.previousHist, result.macd.hist,
                    )
                }
        val kdjMeaning = if (result.kdj.k > result.kdj.d)
            String.format(Locale.CHINA, "K %.1f 在 D %.1f 上方，短线动能偏强；J 值高位钝化时不宜追涨。", result.kdj.k, result.kdj.d)
        else
            String.format(Locale.CHINA, "K %.1f 在 D %.1f 下方，短线动能偏弱，等 K 重新上穿 D 再谈转强。", result.kdj.k, result.kdj.d)
        val rsiMeaning = when {
            result.rsi == null -> "RSI 数据暂缺，判断不了超买超卖。"
            result.rsi >= 70 -> String.format(Locale.CHINA, "RSI %.1f 偏高、短线过热，但超买不等于马上会跌，要配合量能看。", result.rsi)
            result.rsi <= 30 -> String.format(Locale.CHINA, "RSI %.1f 偏低、进入超卖区，但要等反转信号确认才成立。", result.rsi)
            else -> String.format(Locale.CHINA, "RSI %.1f 在中间区间，既不超买也不超卖，本身不构成买卖信号。", result.rsi)
        }
        val chip = result.chip
        val peakText = chip.peaks
            .joinToString("、") { "${price(it.first)}附近(${String.format(Locale.CHINA, "%.1f", it.second)}%)" }
            .ifEmpty { "暂无明显密集区" }
        val rsiText = result.rsi?.let { String.format(Locale.CHINA, "%.1f", it) } ?: "暂缺"
        // volumeNote 是给列表用的短句，本身不带句号，进报告时补上。
        val volumeNote = result.volumeNote?.let { if (it.endsWith("。")) it else "$it。" }
            ?: "量比或涨跌幅数据不足，本节无法判断量价关系。"

        return buildString {
            appendLine("📈 ${result.name}（${result.code}）技术分析")
            appendLine("数据截至：${result.asOf}｜有效日线：${result.rows}个交易日")
            // 环境放在报告头（不是新章节，免得打乱「一、二、…」的编号与结构校验）。
            // 技术面不能是个股自洽的孤岛：熊市塌陷时支撑会被系统性击穿。
            if (env != null) {
                appendLine("市场环境：${env.market.regime.label}——${env.market.reason}")
                val sectorName = env.sector?.industry?.name
                if (sectorName == null) {
                    // 归属查不到（或压根没查）都明说，不猜——把「贵州板块」当行业会得出完全错的结论。
                    appendLine("板块归属暂缺")
                } else {
                    val sector = env.sector
                    // 涨跌的来源单独标出来：板块日线拉不到时会退回按名称匹配的行业板块行情，
                    // 报告里标了来源，一眼就知道哪条路通了。**拿不到时更要说清为什么**——
                    // 「行业板块行情未加载」和「行业板块里无同名项」是两个完全不同的问题。
                    val change = sector.changePct?.let { "｜板块今日 ${pct(it)}（${sector.changeSource}）" }
                        ?: "｜板块涨跌暂缺（${sector.changeSource}）"
                    val regime = sector.state?.regime?.let { "｜板块${it.label}" } ?: ""
                    appendLine("板块：$sectorName$change$regime（归属：${sector.source}）")
                    // 板块自身涨跌只是背景，真正有信息量的是「个股 vs 板块」——
                    // 独立行情 / 掉队 / 一起塌是三种完全不同的结论。
                    sector.interpret(result.priceChange)?.let { appendLine(it) }
                }
            }
            appendLine()
            // 顶部只给概览，真正的综合结论放在报告最后（第十节），关键数据在那里标红。
            appendLine("一、快速概览")
            appendLine("技术评分：${scoreText(result.score)}｜当前价：${price(result.price)}（${result.priceSource}）｜较前一日：${pct(result.priceChange)}")
            // 只有状态快照说明得了「现在在哪」，这句话才说「这次比上次变了什么」。
            env?.let { appendLine(previousChangeNote(it.prev, result)) }
            appendLine("结论（无持仓假设）：${result.action}${noHoldingCaveat(env)}")
            appendLine()
            appendLine("二、量价关系")
            appendLine(volumeText)
            appendLine(signalText)
            if (locationMeaning.isNotBlank()) appendLine("位置：$locationMeaning")
            // 第二节只讲量价，这里放量价那一条结论；它不在 signals 末尾，用 takeLast 会取到别的指标。
            appendLine("结论：$volumeNote")
            result.fundFlowNote?.let { appendLine("资金流：$it") }
            result.fundFlowWindowNote?.let { appendLine("多日资金流：$it") }
            appendLine()
            appendLine("三、开盘结构")
            appendLine("昨收 ${price(result.opening.previousClose)}｜今开 ${price(result.opening.open)}｜跳空 ${String.format(Locale.CHINA, "%+.2f%%", result.opening.gapPct)}")
            appendLine("当前价相对开盘 ${String.format(Locale.CHINA, "%+.2f%%", result.opening.openToCurrentPct)}｜判断：${result.opening.structure}")
            appendLine("结论：${openingConclusion(result.opening)}")
            appendLine()
            appendLine("四、均线与趋势")
            appendLine("MA5 ${price(result.ma5)}｜MA10 ${price(result.ma10)}｜MA20 ${price(result.ma20)}｜MA60 ${price(result.ma60)}")
            appendLine("近20日支撑参考：${price(result.support)}｜压力参考：${price(result.resistance)}")
            appendLine("结论：${maConclusion(result)}")
            appendLine()
            appendLine("五、波动与趋势强度")
            appendLine("ATR14 ${price(result.volatility.atr)}（约占现价${String.format(Locale.CHINA, "%.2f", result.volatility.atrPct ?: 0.0)}%）")
            appendLine("布林带：下轨 ${price(result.volatility.bollLower)}｜中轨 ${price(result.volatility.bollMid)}｜上轨 ${price(result.volatility.bollUpper)}")
            appendLine("结论：${volatilityConclusion(result)}")
            val adxLine = if (adx != null)
                "ADX ${String.format(Locale.CHINA, "%.1f", adx)}｜+DI ${String.format(Locale.CHINA, "%.1f", result.trendStrength.plusDi ?: 0.0)}｜-DI ${String.format(Locale.CHINA, "%.1f", result.trendStrength.minusDi ?: 0.0)}"
            else "ADX数据暂缺"
            appendLine(adxLine)
            appendLine("结论：$adxMeaning")
            appendLine(
                if (result.relativeStrength != null)
                    "相对沪指（近20日）：${String.format(Locale.CHINA, "%+.2f", result.relativeStrength)}个百分点"
                else "相对沪指：数据暂缺"
            )
            appendLine("结论：$relativeMeaning")
            appendLine("OBV近20日净变化：${String.format(Locale.CHINA, "%+.0f", result.obvChange)}（仅表示区间累计资金流向，不等于跟上最新股价高点）")
            val obvDiv = result.divergence
            val pair = obvDiv.prior to obvDiv.current
            if (pair.first != null && pair.second != null) {
                val prior = pair.first!!
                val current = pair.second!!
                appendLine(
                    "阶段高/低点对照：前点 ${price(prior.price)}（${prior.date}，段内OBV峰值 ${String.format(Locale.CHINA, "%.0f", prior.obv)}）" +
                        " → 后点 ${price(current.price)}（${current.date}，段内OBV峰值 ${String.format(Locale.CHINA, "%.0f", current.obv)}）"
                )
            } else {
                appendLine("阶段高/低点对照：暂无两个可比的阶段高/低点")
            }
            appendLine("量价背离判定：${obvDiv.reason}")
            appendLine("结论：${obvDiv.meaning.ifEmpty { "有效日线不足，暂无背离含义可给。" }}")
            appendLine("口径：${obvDiv.method}")
            appendLine()
            appendLine("六、MACD")
            appendLine(
                "DIF ${String.format(Locale.CHINA, "%.3f", result.macd.dif)}｜DEA ${String.format(Locale.CHINA, "%.3f", result.macd.dea)}" +
                    "｜柱体 ${String.format(Locale.CHINA, "%.3f", result.macd.hist)}（前值 ${String.format(Locale.CHINA, "%.3f", result.macd.previousHist)}）"
            )
            appendLine(if (result.macd.dif > result.macd.dea) "判断：DIF在DEA上方" else "判断：DIF在DEA下方")
            appendLine("结论：$macdMeaning")
            appendLine()
            appendLine("七、KDJ / RSI")
            appendLine(
                "K ${String.format(Locale.CHINA, "%.1f", result.kdj.k)}｜D ${String.format(Locale.CHINA, "%.1f", result.kdj.d)}" +
                    "｜J ${String.format(Locale.CHINA, "%.1f", result.kdj.j)}｜RSI $rsiText"
            )
            appendLine("结论（KDJ）：$kdjMeaning")
            appendLine("结论（RSI）：$rsiMeaning")
            appendLine()
            appendLine("八、筹码峰结构（估算）")
            appendLine(
                if (chip.chip70Lower != null && chip.chip70Upper != null)
                    "70%筹码区间：${price(chip.chip70Lower)}～${price(chip.chip70Upper)}"
                else "70%筹码区间：暂缺"
            )
            appendLine(
                if (chip.chip70WidthPct != null)
                    "集中度区间宽度：${String.format(Locale.CHINA, "%.2f", chip.chip70WidthPct)}%（${chip.concentration}；数值越小表示区间越集中）"
                else "集中度区间宽度：暂缺"
            )
            appendLine("当前价格位置：${chip.pricePosition}")
            appendLine("控盘提示：${chip.controlCaution}")
            appendLine("估算成本中枢：${chip.averageCost?.let { price(it) } ?: "暂缺"}")
            appendLine("成交密集区：$peakText")
            appendLine("方法说明：${chip.method}；不是券商真实持仓筹码分布。")
            appendLine("结论：${chip.interpretation}成交密集区可作潜在支撑/压力参考，但不是真实持仓成本，也没有必然的支撑作用。")
            appendLine()
            appendLine("九、综合行动计划")
            appendLine("结论：${planConclusion(result, env)}")
            result.trendAssessment?.let {
                appendLine("趋势状态：${it.summary}")
            }
            result.boxRange?.let {
                appendLine("箱体结构：${it.summary}")
            }
            result.highPosition?.let {
                appendLine("高位结构：${it.phase.label}｜${it.reason}")
            }
            result.impulse?.let {
                appendLine("个股延伸：连续上涨${it.upDays}天、连续下跌${it.downDays}天，近5日${pct(it.gain5Pct)}；天数只作风险权重，不按固定周期预测分歧。")
            }
            appendLine("入场结构：${result.entrySetup.conciseText()}")
            result.entrySetup.stopPrice?.let {
                appendLine("预设止损：${price(it)}（按 ATR 与关键位留出缓冲，不是保证成交价）")
            }
            appendLine("执行原则：先看支撑/压力是否被有效突破，再看成交量是否配合；不要因为单一指标金叉就追入。")
            appendLine("上方突破：有效站上${price(result.resistance)}并放量，趋势才有进一步确认；")
            appendLine("下方风险：跌破${price(result.support)}且放量，需防止趋势转弱；")
            appendLine("任何单一指标都不构成买卖依据，需结合市场环境和个人风险承受能力。")
            appendLine()
            appendLine("十、综合结论（红色为关键数据）")
            appendLine(
                "· 技术评分 ${red(scoreText(result.score))}（${trendWord(result.score)}）" +
                    "｜现价 ${red(price(result.price))} 元（较前一日 ${pct(result.priceChange)}）"
            )
            appendLine("· 量价：${VolumePrice.tag(volumeSignal)}${locationSuffix(priceContext)}")
            appendLine(
                "· 关键价位：支撑 ${red(price(result.support))} 元" +
                    "｜压力 ${red(price(result.resistance))} 元" +
                    "｜MA20 ${red(price(result.ma20))} 元"
            )
            appendLine("· 入场结构：${result.entrySetup.conciseText()}")
            result.entrySetup.stopPrice?.let {
                appendLine("· 预设止损：${red(price(it))} 元")
            }
            appendLine(
                "· 波动：ATR14 ${red(price(result.volatility.atr))} 元" +
                    "（约占现价 ${red(String.format(Locale.CHINA, "%.2f", result.volatility.atrPct ?: 0.0))}%），" +
                    "做 T、止损的幅度按这个量级留余地"
            )
            appendLine("· 操作（无持仓假设）：${result.action}")
            appendLine()
            appendLine("⚠️ 本报告基于历史行情技术计算，不构成投资建议；筹码峰为成交量加权估算。")
        }
    }

    /** 开盘结构的结论：跳空方向 + 开盘后走势，最后交代「只代表早盘」。 */
    private fun openingConclusion(opening: OpeningStructure): String {
        val gap = when {
            opening.gapPct >= 1 -> "高开 ${pct(opening.gapPct)}"
            opening.gapPct <= -1 -> "低开 ${pct(opening.gapPct)}"
            else -> "平开（${pct(opening.gapPct)}）"
        }
        val intraday = when {
            opening.openToCurrentPct >= 1 -> "开盘后继续走高 ${pct(opening.openToCurrentPct)}，早盘承接较强"
            opening.openToCurrentPct >= 0.3 -> "开盘后小幅站稳 ${pct(opening.openToCurrentPct)}，承接尚可"
            opening.openToCurrentPct <= -1 -> "开盘后一路走低 ${pct(opening.openToCurrentPct)}，早盘抛压偏重"
            opening.openToCurrentPct <= -0.3 -> "开盘后小幅走弱 ${pct(opening.openToCurrentPct)}，承接一般"
            else -> "开盘后价格基本没动（${pct(opening.openToCurrentPct)}），方向还没定"
        }
        return "$gap，$intraday。单日开盘结构只反映早盘，不能代表全天趋势。"
    }

    /**
     * 均线结论：排列方向 + 现价到支撑/压力的距离。
     * 最后半句是用户真正要用的：离压力近就别追，离支撑近跌破就是明确信号。
     */
    private fun maConclusion(result: TechnicalResult): String {
        val trend = when {
            result.price > result.ma20 && result.ma20 > result.ma60 -> "价格在 MA20、MA60 上方，中期趋势偏强"
            result.price < result.ma20 && result.ma20 < result.ma60 -> "价格在 MA20、MA60 下方，中期趋势偏弱"
            else -> "均线相互交错，中期趋势还没确认"
        }
        val short = when {
            result.ma5 > result.ma10 && result.ma10 > result.ma20 -> "，短期均线多头排列"
            result.ma5 < result.ma10 && result.ma10 < result.ma20 -> "，短期均线空头排列"
            else -> "，短期均线缠绕，没有明确方向"
        }
        if (result.price <= 0 || result.resistance <= result.price || result.support >= result.price) {
            return "$trend$short。"
        }
        val upRoom = (result.resistance / result.price - 1) * 100
        val downRoom = (1 - result.support / result.price) * 100
        val advice = if (upRoom < downRoom) {
            "离压力更近，向上的空间不如向下的缓冲大，追高的性价比不高。"
        } else {
            "离支撑更近，跌破支撑就是明确的转弱信号。"
        }
        return String.format(Locale.CHINA, "$trend$short。现价上方 %.1f%% 是压力、下方 %.1f%% 是支撑，$advice", upRoom, downRoom)
    }

    /** 波动结论：ATR 占现价的量级放最前，因为做 T 和止损都直接按它定幅度。 */
    private fun volatilityConclusion(result: TechnicalResult): String {
        val atrPct = result.volatility.atrPct
        val level = when {
            atrPct == null -> "波动幅度暂缺"
            atrPct >= 5 -> String.format(Locale.CHINA, "ATR 占现价 %.2f%%，日内波动明显偏大", atrPct)
            atrPct >= 3 -> String.format(Locale.CHINA, "ATR 占现价 %.2f%%，日内波动偏大", atrPct)
            atrPct >= 1.5 -> String.format(Locale.CHINA, "ATR 占现价 %.2f%%，日内波动属常规水平", atrPct)
            else -> String.format(Locale.CHINA, "ATR 占现价 %.2f%%，日内波动偏小", atrPct)
        }
        if (result.volatility.bollUpper <= result.volatility.bollLower) {
            return "$level；布林带区间暂不可用。"
        }
        val band = when (val position = result.volatility.bollPosition) {
            null -> "，布林带位置暂缺。"
            in 80.0..Double.MAX_VALUE -> "，价格靠近布林上轨，短线偏强但要防冲高回落。"
            in Double.NEGATIVE_INFINITY..20.0 -> "，价格靠近布林下轨，属弱势区，要等止跌信号。"
            else -> "，价格在布林带中部，没出现极端信号。"
        }
        return level + band
    }

    private fun scoreText(score: Int): String = String.format(Locale.CHINA, "%+d", score)

    /**
     * 行动计划的结论。
     *
     * 第一节已经给过一次操作建议，这里换一个角度：先说按趋势做还是按震荡做（由 ADX 决定），
     * 再说具体怎么执行，避免三节里重复同一句话。
     */
    /**
     * 行动计划的结论。
     *
     * 第一节已经给过一次操作建议，这里换一个角度：先说按趋势做还是按震荡做（由 ADX 决定），
     * 再说具体怎么执行，避免三节里重复同一句话。[env] 用来改写「可以继续持有」那句——
     * 塌陷期它给的正好是反建议。
     */
    private fun planConclusion(result: TechnicalResult, env: EnvContext?): String {
        val adx = result.trendStrength.adx
        if (adx == null) {
            return "趋势强度取不到，按震荡对待更稳妥：接近区间下沿才考虑，接近上沿不追。"
        }
        if (adx >= 25) {
            // 有趋势时方向决定策略，多空用反了会给出完全相反的建议。
            return if ((result.trendStrength.plusDi ?: 0.0) > (result.trendStrength.minusDi ?: 0.0)) {
                String.format(
                    Locale.CHINA,
                    "ADX %.1f 说明当前有趋势且多方占优，按趋势跟随做：%s，不猜顶、也不在半路加仓。",
                    adx,
                    holdAdvice(env, "回踩不破 MA20 "),
                )
            } else {
                String.format(
                    Locale.CHINA,
                    "ADX %.1f 说明当前有趋势且空方占优，按趋势规避做：反弹到 MA20 或压力位是减仓点，不抢反弹。",
                    adx,
                )
            }
        }
        return String.format(
            Locale.CHINA,
            "ADX %.1f 说明趋势还没成型，按区间做：接近区间下沿才考虑，接近上沿不追，做小波段而不是等单边。",
            adx,
        )
    }

    /**
     * 「结论（无持仓假设）」的环境补充。
     *
     * [TechnicalResult.action] 是通用提示，塌陷期那句「暂以观察为主」会让人以为只是没方向，
     * 而实际意思是**别进场**。只在报告渲染时补——`action` 字段本身与 macOS Python 参考
     * 逐位对齐（`StockAnalysisReferenceTest` 有 `assertEquals`），不能动。
     */
    private fun noHoldingCaveat(env: EnvContext?): String = when (env?.market?.regime) {
        MarketRegime.COLLAPSE ->
            "｜大盘塌陷：这里的『观察』是别进场，不是在等买点——系统性下跌里个股支撑会一起破"
        MarketRegime.WEAK ->
            "｜大盘转弱：先别试仓，等重新站上 MA20 再谈"
        else -> ""
    }

    /**
     * 「可以继续持有」这类措辞的环境改写。
     *
     * 塌陷期它给的是**反建议**：系统性下跌里支撑会一起破，「回踩不破」不等于可以拿着、
     * 更不等于可以加仓。所以就地改写这句，而不是加一句备注了事——
     * 备注会被当成补充说明，正文那句「可以继续持有」照样会被照做。
     */
    private fun holdAdvice(env: EnvContext?, leadIn: String): String = when (env?.market?.regime) {
        MarketRegime.COLLAPSE ->
            "但大盘塌陷：${leadIn}也别加仓，『持有』只是别割在恐慌位，反弹冲高优先减"
        MarketRegime.WEAK ->
            "但大盘转弱：${leadIn}先观察，不加仓，等重新站上 MA20"
        else -> "${leadIn}可以继续持有"
    }

    /** 「较上次分析」叙述。个股报告是手动触发的，上次可能是三小时前，所以不叫「较昨日」。 */
    private fun previousChangeNote(prev: RowSnapshot?, result: TechnicalResult): String {
        if (prev == null) return "较上次分析：无上次快照，写不出变化（不是「没变化」）"
        val diffs = mutableListOf<String>()
        if (prev.score != result.score) diffs.add("评分 ${scoreText(prev.score)}→${scoreText(result.score)}")
        if (prev.support > 0 && result.support > 0 && prev.support != result.support) {
            diffs.add("支撑 ${price(prev.support)}→${price(result.support)}")
        }
        if (prev.resistance > 0 && result.resistance > 0 && prev.resistance != result.resistance) {
            diffs.add("压力 ${price(prev.resistance)}→${price(result.resistance)}")
        }
        if (prev.price > 0 && result.price > 0 &&
            kotlin.math.abs(result.price / prev.price - 1.0) >= 0.01
        ) {
            diffs.add("现价 ${price(prev.price)}→${price(result.price)}")
        }
        if (kotlin.math.abs(result.priceChange - prev.changePct) >= 0.5) {
            diffs.add(
                String.format(
                    Locale.CHINA,
                    "涨跌 %+.1f%%→%+.1f%%",
                    prev.changePct,
                    result.priceChange,
                )
            )
        }
        if (prev.volumeRatio > 0 && result.volumeRatio != null &&
            kotlin.math.abs(result.volumeRatio - prev.volumeRatio) >= 0.3
        ) {
            diffs.add(
                String.format(
                    Locale.CHINA,
                    "量比 %.2f→%.2f",
                    prev.volumeRatio,
                    result.volumeRatio,
                )
            )
        }
        return if (diffs.isEmpty()) {
            "较上次分析：与上次无变化"
        } else {
            "较上次分析：" + diffs.joinToString("｜")
        }
    }

    private fun trendWord(score: Int): String = when {
        score >= 4 -> "偏强"
        score <= -4 -> "偏弱"
        else -> "中性"
    }

    private fun locationSuffix(context: PriceContext?): String = when (context?.location) {
        PriceLocation.LOW -> "，位置在近 60 日区间低位"
        PriceLocation.HIGH -> "，位置在近 60 日区间高位"
        PriceLocation.MID -> "，位置在近 60 日区间中位"
        null -> ""
    }

    private fun red(value: String): String = ReportMarkup.red(value)
}
