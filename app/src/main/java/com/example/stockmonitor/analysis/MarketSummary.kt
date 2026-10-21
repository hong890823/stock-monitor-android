package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ConceptSummary
import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.LimitData
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.SectorData
import com.example.stockmonitor.core.TurnoverCurve
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * 盘面总结，对应 stock_menubar.generate_market_summary。
 * 输出结构与章节顺序、判断阈值与 macOS 版保持一致。
 */
object MarketSummary {

    private val INDEX_CODES = setOf("sh000001", "sz399001", "sz399006")

    data class Inputs(
        val quotes: Map<String, Quote>,
        val sectors: SectorData?,
        val concept: ConceptSummary?,
        val limits: LimitData?,
        val turnoverLines: List<String>,
        val klines: List<DayBar>,
        /** 东方财富成交额（日期 -> 元）；缺失时按成交量展示，与 macOS 版一致。 */
        val amounts: Map<String, Double>? = null,
        val predictedTurnoverYi: Double? = null,
        val recentTurnoversYi: List<Double> = emptyList(),
        val marketTurnoverRatio: Double? = null,
        val marketTurnoverSource: MarketTurnoverSource? = null,
    )

    fun hasFreshIndexQuotes(quotes: Map<String, Quote>, today: String): Boolean =
        INDEX_CODES.all { code -> quotes[code]?.quoteDate == today }

    fun generate(inputs: Inputs, calendar: Calendar = TimeUtils.nowCalendar()): String {
        val sh = inputs.quotes["sh000001"]
        val sz = inputs.quotes["sz399001"]
        val cy = inputs.quotes["sz399006"]
        val sectors = inputs.sectors
        val concept = inputs.concept ?: ConceptSummary(emptyList(), 0, 0)
        val allSectors = sectors?.all ?: emptyList()

        val secRising = allSectors.count { it.change > 0 }
        val secFalling = allSectors.count { it.change < 0 }
        val secFlat = allSectors.count { it.change == 0.0 }
        val secTotal = allSectors.size
        val conRising = concept.rising
        val conFalling = concept.falling
        val conFlat = concept.sectors.count { it.change == 0.0 }
        val conTotal = if (concept.sectors.isNotEmpty()) concept.sectors.size else conRising + conFalling
        val upLimit = inputs.limits?.up ?: emptyList()
        val downLimit = inputs.limits?.down ?: emptyList()
        val klines = inputs.klines

        val out = StringBuilder()
        val monthDay = String.format(
            Locale.CHINA, "%d.%d",
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        out.appendLine("📊 $monthDay 复盘")
        out.appendLine()
        // 开头给「今日要点」：八节的长报告，想看「今天什么情况、要不要动」不该翻到底。
        out.appendLine("📌 今日要点")
        brief(inputs).forEach { out.appendLine("· $it") }
        out.appendLine()

        // ═══ 一、大盘观点 ═══
        out.appendLine("一、大盘观点")
        out.appendLine()
        if (sh != null && sz != null && cy != null) {
            val sp = sh.changePct
            val szp = sz.changePct
            val cyp = cy.changePct
            val trend = when {
                sp > 0.5 && szp > 0.5 -> "震荡走高，全线飘红"
                sp > 0 && szp > 0 -> "小幅反弹，分化不大"
                abs(sp) < 0.2 && abs(szp) < 0.3 -> "窄幅震荡，方向不明"
                sp < -1.0 -> "放量杀跌，恐慌蔓延"
                sp < -0.3 && szp < -0.3 -> "震荡走低，全线承压"
                else -> "弱势整理"
            }
            val qual = when {
                sp < -1.0 -> "偏空，防守为主"
                sp < -0.3 -> "偏空，防御为主"
                sp < 0 -> "偏弱，观望为主"
                sp > 0.5 -> "偏多，可适度参与"
                else -> "中性，结构性行情"
            }
            val breadth = listOf(
                breadthSentence("行业板块", secRising, secFalling, secFlat),
                breadthSentence("概念板块", conRising, conFalling, conFlat),
            ).joinToString("；")
            out.appendLine(
                String.format(Locale.CHINA, "%s。沪指%+.2f%%、深成指%+.2f%%、创业板%+.2f%%。定性：%s。%s。量能：%s。",
                    trend, sp, szp, cyp, qual, breadth, inputs.turnoverLines.joinToString("；"))
            )
        } else if (sh != null) {
            out.appendLine(String.format(Locale.CHINA, "沪指%+.2f%%。", sh.changePct))
        } else {
            out.appendLine("指数数据暂未获取。")
        }
        out.appendLine()

        // ═══ 二、板块异动 ═══
        out.appendLine("---")
        out.appendLine("🟠 二、板块异动")
        out.appendLine()
        out.appendLine("涨：")
        out.appendLine()
        if (!sectors?.top.isNullOrEmpty()) {
            val rows = sectors.top.take(5).map { sector ->
                listOf(
                    sector.name,
                    String.format(Locale.CHINA, "%+.2f%%", sector.change),
                    if (sector.volume > 0) "成交${formatYi(sector.volume)}亿" else "成交额暂缺",
                )
            }
            out.appendLine(table(listOf("板块", "涨幅", "成交额"), rows))
            out.appendLine("说明：当前新浪行业接口的领涨股归属存在错配风险，暂不显示未经独立核验的领涨股。")
        }
        out.appendLine()
        out.appendLine("跌：")
        out.appendLine()
        if (!sectors?.bottom.isNullOrEmpty()) {
            val rows = sectors.bottom.take(8).map { sector ->
                listOf(
                    sector.name,
                    String.format(Locale.CHINA, "%+.2f%%", sector.change),
                    if (sector.volume > 0) "成交${formatYi(sector.volume)}亿" else "",
                )
            }
            out.appendLine(table(listOf("板块", "跌幅", "说明"), rows))
        } else {
            out.appendLine("（当前没有检测到下跌行业板块）")
        }
        if (concept.sectors.isNotEmpty()) {
            val conceptText = concept.sectors.take(3)
                .joinToString("、") { String.format(Locale.CHINA, "%s%+.2f%%", it.name, it.change) }
            out.appendLine("概念线：$conceptText。仅表示涨跌排名，不代表资金净流入。")
        }
        out.appendLine()

        // ═══ 三、资金与成交额 ═══
        out.appendLine("---")
        out.appendLine("🟡 三、资金与成交额")
        out.appendLine()
        out.appendLine("成交额 TOP 行业（不等同于资金净流向）：")
        out.appendLine()
        if (allSectors.isNotEmpty()) {
            val volumeSorted = allSectors.sortedByDescending { it.volume }.take(8)
            val rows = volumeSorted.map { sector ->
                listOf(
                    sector.name,
                    String.format(Locale.CHINA, "%+.2f%%", sector.change),
                    "${formatYi(sector.volume)}亿",
                )
            }
            out.appendLine(table(listOf("板块", "涨跌", "成交额"), rows))
            val risingVolume = volumeSorted.filter { it.change > 0 }
            val fallingVolume = volumeSorted.filter { it.change < 0 }
            when {
                risingVolume.isNotEmpty() && fallingVolume.isNotEmpty() -> out.appendLine(
                    "点评：成交额靠前板块中，${risingVolume.first().name}等上涨，" +
                        "${fallingVolume.first().name}等下跌；这里只能说明成交活跃，" +
                        "不能直接等同于资金净流入或净流出。"
                )
                risingVolume.isNotEmpty() -> out.appendLine("点评：成交额靠前板块整体偏强，但仍需结合涨停扩散和后续量能确认。")
                fallingVolume.isNotEmpty() -> out.appendLine("点评：成交额靠前板块整体偏弱，放量下跌需警惕资金承接不足。")
            }
        } else {
            out.appendLine("成交额数据暂缺。")
        }
        out.appendLine()

        // ═══ 四、涨跌家数比 ═══
        out.appendLine("---")
        out.appendLine("🔵 四、涨跌家数比")
        out.appendLine()
        out.appendLine(
            if (secTotal > 0) "- ${breadthSentence("行业板块", secRising, secFalling, secFlat)}"
            else "- 行业板块：涨跌数据暂缺"
        )
        out.appendLine(
            if (conTotal > 0) "- ${breadthSentence("概念板块", conRising, conFalling, conFlat)}"
            else "- 概念板块：涨跌数据暂缺"
        )
        if (upLimit.isNotEmpty() || downLimit.isNotEmpty()) {
            out.appendLine("- 涨停${upLimit.size}只，跌停${downLimit.size}只")
            if (upLimit.isNotEmpty()) out.appendLine("  涨停代表：${upLimit.take(12).joinToString("、") { it.name }}")
            if (downLimit.isNotEmpty()) out.appendLine("  跌停代表：${downLimit.take(8).joinToString("、") { it.name }}")
        } else {
            out.appendLine("- 涨跌停数据暂缺")
        }
        if (conTotal > 0) {
            val ratio = conRising.toDouble() / conTotal
            out.appendLine(
                when {
                    ratio >= 0.7 -> "- 📌 市场宽度偏暖，赚钱效应较强，但需防止情绪过热。"
                    ratio >= 0.5 -> "- 📌 市场宽度中性偏暖，板块轮动仍需量能确认。"
                    ratio >= 0.3 -> "- 📌 市场宽度偏弱，只有少数方向获得资金关注。"
                    else -> "- 📌 市场宽度较窄，亏钱效应仍在扩散。"
                }
            )
        }
        val environment = environmentQuality(inputs)
        if (environment.status != MarketEnvironmentStatus.NORMAL && inputs.predictedTurnoverYi != null) {
            out.appendLine("- 量能环境：${environment.status.label}。${environment.summary}")
        }
        out.appendLine()

        // ═══ 五、量价关系 ═══
        out.appendLine("---")
        out.appendLine("🟣 五、量价关系")
        out.appendLine()
        if (inputs.turnoverLines.isNotEmpty()) {
            out.appendLine(inputs.turnoverLines.joinToString("；"))
            out.appendLine()
        }
        if (klines.isNotEmpty()) {
            val useAmount = inputs.amounts != null &&
                klines.all { inputs.amounts.containsKey(it.date) }
            val valueLabel = if (useAmount) "沪指成交额" else "沪指成交量"
            val valueUnit = if (useAmount) "亿" else "亿股"
            val rows = klines.mapIndexed { index, bar ->
                val value = klineValue(bar, inputs.amounts, useAmount)
                val changeText = if (index == 0) "—"
                else String.format(
                    Locale.CHINA, "%+.0f%s",
                    value - klineValue(klines[index - 1], inputs.amounts, useAmount), valueUnit
                )
                listOf(bar.date.drop(5), String.format(Locale.CHINA, "%.0f%s", value, valueUnit), changeText)
            }
            out.appendLine(table(listOf("日期", valueLabel, "变化"), rows))
            out.appendLine()
            if (klines.size >= 2 && useAmount) {
                val firstValue = klineValue(klines.first(), inputs.amounts, true)
                val lastValue = klineValue(klines.last(), inputs.amounts, true)
                if (firstValue > 0) {
                    val totalPct = (lastValue - firstValue) / firstValue * 100
                    if (totalPct < -10) {
                        out.appendLine(
                            String.format(Locale.CHINA, "→ 较%d个交易日前%s减少%.0f%%", klines.size, valueLabel, abs(totalPct))
                        )
                        if (sh != null && sh.changePct < 0) {
                            out.appendLine("→ 缩量下跌，承接力度偏弱，仍需观察后续量能。")
                        }
                    } else if (totalPct > 10) {
                        out.appendLine(
                            String.format(Locale.CHINA, "→ 较%d个交易日前%s增加%.0f%%", klines.size, valueLabel, totalPct)
                        )
                    }
                }
            } else if (klines.size >= 2) {
                val firstValue = klineValue(klines.first(), inputs.amounts, false)
                val lastValue = klineValue(klines.last(), inputs.amounts, false)
                if (firstValue > 0) {
                    val totalPct = (lastValue - firstValue) / firstValue * 100
                    if (totalPct < -10) {
                        out.appendLine(
                            String.format(Locale.CHINA, "→ 较%d个交易日前%s减少%.0f%%", klines.size, valueLabel, abs(totalPct))
                        )
                    } else if (totalPct > 10) {
                        out.appendLine(
                            String.format(Locale.CHINA, "→ 较%d个交易日前%s增加%.0f%%", klines.size, valueLabel, totalPct)
                        )
                    }
                }
            }
        } else {
            out.appendLine("  ⚠️ K线数据获取失败，量价分析暂缺")
        }
        out.appendLine()

        // ═══ 六、技术看盘 ═══
        out.appendLine("---")
        out.appendLine("📈 六、技术看盘")
        out.appendLine()
        if (sh != null) {
            out.appendLine(String.format(Locale.CHINA, "- 沪指%.0f点", sh.price))
            if (klines.size >= 3) {
                val recentHigh = klines.maxOf { it.high }
                val recentLow = klines.minOf { it.low }
                out.appendLine(
                    String.format(Locale.CHINA, "- 近%d日震荡区间：%.0f~%.0f", klines.size, recentLow, recentHigh)
                )
                val rangeSize = recentHigh - recentLow
                if (rangeSize > 0) {
                    val position = (sh.price - recentLow) / rangeSize
                    out.appendLine(
                        when {
                            position > 0.8 -> "  ⚠️ 处于近期高位，接近压力区"
                            position < 0.2 -> "  ⚠️ 逼近箱体下沿，若失守将打开向下空间"
                            else -> "  → 处于区间中部，箱体震荡格局"
                        }
                    )
                }
                out.appendLine(
                    String.format(Locale.CHINA, "- 下方支撑：%.0f附近，上方压力：%.0f附近", recentLow, recentHigh)
                )
            }
        }
        out.appendLine()

        // ═══ 七、后市推演 ═══
        out.appendLine("---")
        out.appendLine("🟢 七、后市推演")
        out.appendLine()
        if (sh != null) {
            val pct = sh.changePct
            if (conTotal > 0 && conRising.toDouble() / conTotal < 0.1) {
                out.appendLine("- 存量博弈到极致——概念板块上涨占比极低，几乎所有题材都在亏钱")
            }
            if (klines.size >= 2) {
                val useAmount = inputs.amounts != null && klines.all { inputs.amounts.containsKey(it.date) }
                val firstValue = klineValue(klines.first(), inputs.amounts, useAmount)
                val lastValue = klineValue(klines.last(), inputs.amounts, useAmount)
                if (firstValue > 0 && (lastValue - firstValue) / firstValue < -0.1) {
                    val label = if (useAmount) "成交额" else "成交量"
                    out.appendLine("- 量能较${klines.size}个交易日前${label}减少，增量资金尚未得到确认")
                }
                val recentLow = klines.minOf { it.low }
                out.appendLine(
                    String.format(Locale.CHINA, "- 短期看：%.0f是多头防线。守住则箱体延续，破则可能加速下行", recentLow)
                )
            }
            when {
                pct < -0.3 -> out.appendLine("- 📌 在放量上涨出现之前，任何反弹都是减仓窗口")
                pct > 0.3 -> out.appendLine("- 指数偏强但需量能继续配合，优先观察主线是否扩散，避免追高。")
            }
        }
        out.appendLine()

        // ═══ 八、机会与风险 ═══
        out.appendLine("---")
        out.appendLine("🔴 八、机会与风险 / 主线与轮动")
        out.appendLine()
        val topSectors = sectors?.top ?: emptyList()
        if (topSectors.isNotEmpty()) {
            val first = topSectors[0]
            val volume1 = if (first.volume > 0) "成交${formatYi(first.volume)}亿" else "成交额暂缺"
            out.appendLine(String.format(Locale.CHINA, "主线①：%s（%+.2f%%，%s）", first.name, first.change, volume1))
            out.appendLine(String.format(Locale.CHINA, "  板块涨幅%+.2f%%，领涨股字段暂不核验展示", first.change))
            if (topSectors.size > 1) {
                val second = topSectors[1]
                val volume2 = if (second.volume > 0) "成交${formatYi(second.volume)}亿" else "成交额暂缺"
                out.appendLine(String.format(Locale.CHINA, "主线②：%s（%+.2f%%，%s）", second.name, second.change, volume2))
                out.appendLine(String.format(Locale.CHINA, "  板块涨幅%+.2f%%", second.change))
            }
        }
        val bottomSectors = sectors?.bottom ?: emptyList()
        if (bottomSectors.isNotEmpty()) {
            val names = bottomSectors.take(3).joinToString("、") { it.name }
            out.appendLine("风险方向：$names（远离）")
            bottomSectors.take(3).forEach {
                out.appendLine(String.format(Locale.CHINA, "  %s%+.2f%%", it.name, it.change))
            }
        }
        if (topSectors.isNotEmpty()) {
            out.appendLine("机会：${topSectors[0].name}当前强度最高，关注涨幅能否延续并扩散。")
        }
        if (bottomSectors.isNotEmpty()) {
            out.appendLine("风险：${bottomSectors[0].name}跌幅靠前，若放量下跌需回避。")
        }
        out.appendLine("📌 核心结论：${brief(inputs).last().removePrefix("结论：")}")
        out.appendLine()

        // ═══ 下一步关注 ═══
        out.appendLine("---")
        out.appendLine("🎯 下一步关注")
        out.appendLine()
        if (sh != null) {
            out.appendLine(
                when {
                    sh.changePct < -0.3 -> "- 应对：控制仓位，等待量能回升和主线重新确认。"
                    sh.changePct > 0.5 -> "- 应对：不追单日涨幅，等待主线回踩或量能确认后再考虑增加暴露。"
                    else -> "- 应对：保持中性仓位，优先观察确定性较高且有成交额配合的方向。"
                }
            )
        }
        if (conTotal > 0) {
            out.appendLine(
                String.format(Locale.CHINA, "- 市场宽度：概念上涨占比%.0f%%", conRising.toDouble() / conTotal * 100)
            )
        }
        out.appendLine()
        out.appendLine("--- 数据截至 ${TimeUtils.nowText()}，仅供参考 ---")
        return out.toString()
    }

    /**
     * 「今日要点」：盘面总结开头的精要。
     *
     * 八节的长报告想看「今天什么情况、要不要动」得翻到底。抽成独立函数，
     * 报告开头调它，口径只有一份。
     */
    fun brief(inputs: Inputs): List<String> {
        val lines = mutableListOf<String>()
        val sh = inputs.quotes["sh000001"]
        val sz = inputs.quotes["sz399001"]

        // ① 大盘
        lines.add(
            "大盘：" + listOfNotNull(
                sh?.let { String.format(Locale.CHINA, "沪指 %+.2f%%", it.changePct) },
                sz?.let { String.format(Locale.CHINA, "深成指 %+.2f%%", it.changePct) },
            ).joinToString("、").ifBlank { "指数数据暂缺" }
        )

        // ② 量能：turnoverLines 已经是格式化好的「今日 vs 昨日同一时刻」
        lines.add("量能：${inputs.turnoverLines.firstOrNull() ?: "暂缺"}")

        // ③ 赚钱效应
        val conRising = inputs.concept?.rising ?: 0
        val conFalling = inputs.concept?.falling ?: 0
        val conTotal = conRising + conFalling
        lines.add(
            "赚钱效应：" + if (conTotal <= 0) {
                "暂缺"
            } else {
                String.format(
                    Locale.CHINA,
                    "概念板块上涨 %d : 下跌 %d（占比 %.0f%%）｜涨停 %d 家、跌停 %d 家",
                    conRising,
                    conFalling,
                    conRising.toDouble() / conTotal * 100,
                    inputs.limits?.up?.size ?: 0,
                    inputs.limits?.down?.size ?: 0,
                )
            }
        )

        // ④ 主线与风险
        val lead = inputs.sectors?.top?.take(2)?.joinToString("、") {
            String.format(Locale.CHINA, "%s %+.2f%%", it.name, it.change)
        }
        val lag = inputs.sectors?.bottom?.take(2)?.joinToString("、") {
            String.format(Locale.CHINA, "%s %+.2f%%", it.name, it.change)
        }
        lines.add(
            "主线与风险：" + listOfNotNull(
                lead?.let { "领涨 $it" },
                lag?.let { "领跌 $it" },
            ).joinToString("｜").ifBlank { "暂缺" }
        )

        // ⑤ 一句话结论：与正文第八节的核心结论同一条口径，别在两处给出两个判断
        val environment = environmentQuality(inputs)
        val conclusion = if (environment.status == MarketEnvironmentStatus.DIVERGENCE) {
            "同期相对量能较高但指数或板块扩散不匹配，按放量分歧看待，不据此判断趋势延续。"
        } else if (environment.status == MarketEnvironmentStatus.STRONG_SINGLE_DAY) {
            "同期相对量能、指数和板块扩散暂时同向，但连续性证据尚不足，待后续交易日确认。"
        } else if (environment.status == MarketEnvironmentStatus.STRONG_SUSTAINED) {
            "同期相对量能与指数、板块扩散暂时同向，仍需收盘确认，不代表个股必涨。"
        } else if (environment.status == MarketEnvironmentStatus.UNKNOWN && inputs.predictedTurnoverYi != null) {
            "环境证据不足，不把缺数据解释成放量分歧或强势延续。"
        } else if (conTotal <= 0) {
            "宽度数据暂缺，先看量能和主线能否确认。"
        } else {
            val ratio = conRising.toDouble() / conTotal
            when {
                ratio >= 0.7 -> "市场偏暖，重点观察量能和主线持续性，防止情绪过热。"
                ratio < 0.3 -> "市场宽度偏弱，优先防守，等待量能和主线扩散确认。"
                else -> "市场处于结构性轮动，暂不追逐单日强势。"
            }
        }
        lines.add("结论：$conclusion")
        return lines
    }

    private fun environmentQuality(inputs: Inputs): MarketEnvironmentQuality {
        val industry = inputs.sectors?.all.orEmpty()
        val industryBreadth = industry.takeIf { it.isNotEmpty() }
            ?.count { it.change > 0 }?.toDouble()?.div(industry.size)
        val concept = inputs.concept
        val conceptTotal = concept?.let { if (it.sectors.isNotEmpty()) it.sectors.size else it.rising + it.falling } ?: 0
        val conceptBreadth = concept?.takeIf { conceptTotal > 0 }?.let {
            (if (it.sectors.isNotEmpty()) it.sectors.count { sector -> sector.change > 0 } else it.rising).toDouble() / conceptTotal
        }
        val index = inputs.quotes["sh000001"]
        val fallbackTurnover = MarketTurnoverFallbackPolicy.evaluate(
            predictedTurnoverYi = inputs.predictedTurnoverYi,
            recentTurnoversYi = inputs.recentTurnoversYi,
        )
        val source = when {
            inputs.marketTurnoverRatio?.let { it.isFinite() && it > 0.0 } == true ->
                inputs.marketTurnoverSource ?: MarketTurnoverSource.SAME_TIME
            fallbackTurnover != null -> fallbackTurnover.source
            else -> null
        }
        return MarketEnvironmentQuality.evaluateAdaptive(
            marketTurnoverRatio = inputs.marketTurnoverRatio ?: fallbackTurnover?.ratio,
            marketTurnoverSource = source,
            marketChangePct = index?.changePct,
            marketPrice = index?.price,
            marketHighPrice = index?.high,
            industryBreadth = industryBreadth,
            conceptBreadth = conceptBreadth,
        )
    }

    /**
     * 市场概览一行摘要。
     * 成交额对比使用「昨日同一时刻」的累计值，不再用当天半日累计去比昨天全天。
     */
    fun overviewTitle(
        indexItems: List<Quote>,
        snapshot: TurnoverTracker.Snapshot?,
        includeTurnover: Boolean = true,
    ): String {
        val parts = mutableListOf<String>()
        indexItems.forEach { quote ->
            val priceText = if (quote.price >= 100) String.format(Locale.CHINA, "%.0f", quote.price)
            else String.format(Locale.CHINA, "%.2f", quote.price)
            parts.add(String.format(Locale.CHINA, "%s%s%+.1f%%", quote.name, priceText, quote.changePct))
        }
        val today = if (includeTurnover) snapshot?.today ?: 0.0 else 0.0
        if (today > 0) {
            parts.add("总${formatYi(today)}亿")
            val previous = snapshot?.previousSameTime
            if (previous != null && previous > 0) {
                val delta = today - previous
                parts.add(
                    String.format(
                        Locale.CHINA, "昨同期%+,.0f亿(%+.1f%%)",
                        delta / 1e8, delta / previous * 100,
                    )
                )
            }
        }
        return if (parts.isEmpty()) "市场概览" else parts.joinToString(" ")
    }

    /** 成交额展示行：今日累计、较昨日同一时刻、全天预估。 */
    fun turnoverLines(today: Double, snapshot: TurnoverTracker.Snapshot?): List<String> {
        if (today <= 0) return listOf("暂无市场成交数据")
        val lines = mutableListOf<String>()
        lines.add("两市总成交  ${formatYi(today)}亿")

        val sampled = snapshot?.today ?: 0.0
        val previous = snapshot?.previousSameTime
        if (previous != null && previous > 0 && sampled > 0) {
            val delta = sampled - previous
            lines.add(
                String.format(
                    Locale.CHINA, "较昨日同期（%s）  %+,.0f亿  (%+.1f%%)",
                    snapshot.sampledAt, delta / 1e8, delta / previous * 100,
                )
            )
        } else {
            lines.add("昨日同期数据暂缺，已开始记录，明天起可对比")
        }

        val predicted = snapshot?.predictedFullDay
        if (predicted != null && predicted > 0 && snapshot.sampleMinute < 900) {
            val basis = if (previous != null && previous > 0) "按昨日同期推算" else "按已过交易时间线性推算"
            lines.add("${basis}全天约  ${formatYi(predicted)}亿")
        }
        return lines
    }

    /** 全天成交额预估，对应 _predict_daily_turnover。 */
    fun predictDailyTurnover(
        current: Double,
        calendar: Calendar = TimeUtils.nowCalendar(),
    ): Pair<Double?, Double?> {
        if (TimeUtils.isWeekend(calendar)) return null to null
        val elapsed = TradingCalendar.tradingMinutesElapsed(calendar)
        if (elapsed <= 5) return null to null
        if (elapsed >= 240) return current to 1.0
        val pct = elapsed / 240.0
        return (if (pct > 0) current / pct else current) to pct
    }

    private fun klineValue(bar: DayBar, amounts: Map<String, Double>?, useAmount: Boolean): Double =
        if (useAmount) (amounts?.get(bar.date) ?: 0.0) / 1e8 else bar.volume / 1e8

    private fun breadthSentence(label: String, rising: Int, falling: Int, flat: Int): String {
        val total = rising + falling + flat
        if (total <= 0) return "${label}涨跌数据暂缺"
        val ratio = rising.toDouble() / total * 100
        val mood = when {
            ratio >= 70 -> "赚钱效应偏强"
            ratio >= 50 -> "市场情绪中性偏暖"
            ratio >= 30 -> "赚钱效应偏弱"
            else -> "亏钱效应明显"
        }
        val flatText = if (flat > 0) "，平$flat" else ""
        return String.format(Locale.CHINA, "%s涨%d : 跌%d%s，上涨占比%.0f%%，%s", label, rising, falling, flatText, ratio, mood)
    }

    private fun formatYi(value: Double): String = String.format(Locale.CHINA, "%,.0f", value / 1e8)

    /** 等宽表格，等价于 _make_table；按东亚字符宽度对齐以便手机端显示。 */
    fun table(headers: List<String>, rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val widths = headers.indices.map { index ->
            var width = displayWidth(headers[index])
            rows.forEach { row ->
                if (index < row.size) width = maxOf(width, displayWidth(row[index]))
            }
            width + 2
        }
        fun formatRow(cells: List<String>): String {
            val parts = cells.mapIndexed { index, cell ->
                val pad = (widths.getOrNull(index) ?: displayWidth(cell)) - displayWidth(cell)
                " $cell" + " ".repeat(maxOf(pad - 1, 0))
            }
            return "│" + parts.joinToString("│") + "│"
        }
        val top = "┌" + widths.joinToString("┬") { "─".repeat(it) } + "┐"
        val middle = "├" + widths.joinToString("┼") { "─".repeat(it) } + "┤"
        val bottom = "└" + widths.joinToString("┴") { "─".repeat(it) } + "┘"
        val lines = mutableListOf(top, formatRow(headers), middle)
        rows.forEach { lines.add(formatRow(it)) }
        lines.add(bottom)
        return lines.joinToString("\n")
    }

    private fun displayWidth(text: String): Int = text.sumOf { if (it.code > 0x2E80) 2 else 1 }
}
