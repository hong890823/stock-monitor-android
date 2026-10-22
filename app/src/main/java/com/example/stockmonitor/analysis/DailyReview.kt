package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Portfolio
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * 「持仓复盘」报告：把当日盈亏和技术面拼成一篇可读的复盘文档。
 *
 * 它回答的是「今天为什么赚/亏、现在什么状态、接下来怎么办」，不是把数字再抄一遍——
 * 单纯的盈亏数字在行情软件里就能看到。
 *
 * **两个口径全文分开，不混用**：
 * - 今日盈亏 =（现价 − 昨收）× 数量，是**较昨收**的日内浮动（[DayPnl]）；
 * - 浮盈浮亏 =（现价 − 成本）× 数量，是**较成本**的持仓浮动（[DeepLossPolicy.floatingPnlPct]）。
 * 每处出现都带口径后缀，测试锁死这条。
 *
 * **贡献占比只在同号集合内算**：盈利股占「今日盈利」毛额、亏损股占「今日亏损」毛额，
 * 永不除以接近 0 的合计盈亏——正负相抵时那个百分比没有意义，那种日子只给金额排序。
 *
 * **缺数据写「暂缺 / 未计入」，绝不写 0**，不把「没行情」显示成「没亏没赚」。
 *
 * **利好只看近两日、且按交易日回溯**（见 [recentPositives]）：窗口是「上一交易日 → 今天」，
 * 周末/长假挂出的公告跟着上一交易日一起收；再往前的利好多半已兑现进股价，列出来是误导。
 *
 * 章节标题必须以中文数字开头（`ReportSectionAssertions` 的正则 `^[一二三四五六七八九十]+、`），
 * emoji 放在数字后面；每节最后一行顶格写「结论：」。
 */
object DailyReview {

    /** 组合层面的聚合量。单独拆出来是为了能直接 `assertEquals`，不靠整篇字符串断言。 */
    data class Aggregates(
        /** 持仓市值合计（元），只计有行情的持仓。 */
        val marketValue: Double,
        /** 总仓位 = 持仓市值 ÷ 总资金（%）；总资金未填时为 null。 */
        val totalPositionPct: Double?,
        /** 单股最大仓位上限（%），来自持仓设置。注意它是**单股**上限，不是总仓位上限。 */
        val maxPositionPct: Double,
        val topCode: String?,
        val topName: String?,
        /** 最大单股占持仓市值（%）。 */
        val topWeightOfHoldingsPct: Double?,
        /** 最大单股占总资金（%）；总资金未填时为 null。 */
        val topWeightOfCapitalPct: Double?,
        /** 成本基数 Σ(成本 × 数量)，只计成本已填且有行情的持仓。 */
        val costBasis: Double,
        /** 总浮盈浮亏 Σ(现价 − 成本) × 数量，**较成本**口径；一只都没填成本时为 null。 */
        val floatingPnl: Double?,
        /** 总浮盈浮亏 ÷ 成本基数（%）；同上，算不出时为 null。 */
        val floatingPnlPct: Double?,
        /** 按波动率口径已属深套的只数（较成本）。 */
        val deepLossCount: Int,
        /** 组合当日收益率 − 沪指当日涨跌（百分点）；任一侧缺失时为 null。 */
        val vsIndexPp: Double?,
    )

    /** 操作优先级分层。顺序就是「轻重缓急」的顺序。 */
    enum class PriorityTier(val title: String) {
        ACT_NOW("P1 今日必须复核"),
        TREND_WATCH("P2 趋势转弱，暂缓加仓"),
        TAKE_PROFIT_WATCH("P3 止盈 / 过热"),
        HOLD("P4 今天不要动"),
        WAIT_ENTRY("P5 等待位置"),
    }

    data class PriorityItem(
        val result: PositionResult,
        val tier: PriorityTier,
        /** 这一层里为什么点它，一句话。 */
        val reason: String,
        /** 当日盈亏（较昨收）；无行情时为 null。 */
        val dayPnl: Double?,
    )

    data class Inputs(
        val positions: List<Position>,
        /** 与 [positions] 同一时点的实时行情，缺行情的代码不出现即可。 */
        val quotes: Map<String, Quote>,
        val results: List<PositionResult>,
        val portfolio: Portfolio,
        val indexQuote: Quote?,
        /** 报告生成时刻，如 `2026-10-12 16:05`。 */
        val analyzedAt: String,
        /**
         * 大盘状态。有了它组合体检才会写「市场环境」，塌陷时风险点第一条会换成系统性风险——
         * 否则复盘在熊市里还在逐只慢慢看，正好给反了。
         */
        val market: MarketState? = null,
        /** 各持仓的板块上下文；用来出「板块塌陷」风险点与个股的板块行。 */
        val sectorContexts: Map<String, SectorContext> = emptyMap(),
        /** 当日强势环境下暂缓做T的盘中记录。 */
        val strongContinuationEvents: List<StrongContinuationEvent> = emptyList(),
        /**
         * 各持仓最近拉到的公告清单（[com.example.stockmonitor.monitor.Repo.recentAnnouncements]）。
         * 只用来出「近两日个股重大利好」一节；缺了就写「暂缺」，不猜。
         */
        val recentAnnouncements: Map<String, List<Announcement>> = emptyMap(),
        /**
         * 交易日期（如指数日线的日期表）。利好窗口要回溯到上一交易日——周一复盘时
         * 「今天+昨天」会漏掉周五盘后和周末的公告。缺表时退回自然日两天。
         */
        val tradingDates: List<String> = emptyList(),
    )

    /**
     * 交易日判定：指数行情标注的交易日期不是今天，就当作非交易日（节假日 / 休市），
     * 不生成复盘、不覆盖旧报告——否则会伪造一份「今日」。
     *
     * 拿不到交易日期（接口没给）时**放行**：判不出来就当交易日，让「全部缺行情」去兜底，
     * 总好过因为一个缺失字段把每个交易日都挡掉。
     */
    fun isTradingDay(indexQuote: Quote?, today: String): Boolean {
        val date = indexQuote?.quoteDate?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: return true
        return date == today
    }

    /**
     * 组合聚合量。[dayPnl] 由 [DayPnl.summarize] 算出，这里只做组合层面的再聚合。
     * [results] 只用来取 ATR%（深套阈值按波动率缩放），取不到就退回 [DeepLossPolicy.DEEP_LOSS_PCT]。
     */
    fun aggregate(
        positions: List<Position>,
        dayPnl: DayPnl.Summary,
        portfolio: Portfolio,
        indexQuote: Quote?,
        results: List<PositionResult> = emptyList(),
    ): Aggregates {
        val totalCapital = portfolio.totalCapital
        val priced = dayPnl.rows.filter { it.pnl != null && it.price > 0 }
        val marketValue = dayPnl.marketValue
        val top = priced.maxByOrNull { it.price * it.quantity }
        val topValue = top?.let { it.price * it.quantity }

        var costBasis = 0.0
        var floatingPnl = 0.0
        var costCount = 0
        var deepLossCount = 0
        val pricedByCode = priced.associateBy { it.code }
        for (position in positions) {
            if (position.quantity <= 0 || position.cost <= 0) continue
            val row = pricedByCode[position.code] ?: continue
            costBasis += position.cost * position.quantity
            floatingPnl += (row.price - position.cost) * position.quantity
            costCount++
            val atrPct = results.firstOrNull { it.code == position.code }?.volatility?.atrPct
            if (DeepLossPolicy.isDeepLoss(position.cost, row.price, atrPct)) deepLossCount++
        }

        val totalPct = dayPnl.totalPct
        val indexPct = indexQuote?.takeIf { it.prevClose > 0 }?.changePct
        return Aggregates(
            marketValue = marketValue,
            totalPositionPct = if (totalCapital > 0) marketValue / totalCapital * 100 else null,
            maxPositionPct = portfolio.maxPositionPct,
            topCode = top?.code,
            topName = top?.name,
            topWeightOfHoldingsPct = if (topValue != null && marketValue > 0) topValue / marketValue * 100 else null,
            topWeightOfCapitalPct = if (topValue != null && totalCapital > 0) topValue / totalCapital * 100 else null,
            costBasis = costBasis,
            floatingPnl = if (costCount > 0) floatingPnl else null,
            floatingPnlPct = if (costBasis > 0) floatingPnl / costBasis * 100 else null,
            deepLossCount = deepLossCount,
            vsIndexPp = if (totalPct != null && indexPct != null && indexPct.isFinite()) {
                totalPct - indexPct
            } else {
                null
            },
        )
    }

    /**
     * 操作优先级分层。只按 [PositionResult.signal] 分层、用涨跌幅/距压力/量价辅助排序，
     * **不重新实现信号判定**——信号该是什么已经在 [PortfolioAnalysis.evaluate] 里定了。
     *
     * 观察股（数量 0）没有仓位，一律进「等待位置」，不会出现在「今天不要动」里
     * （那句话的意思是「你手上这只今天不用动」）。
     */
    fun prioritize(results: List<PositionResult>, dayPnlRows: List<DayPnl.Row> = emptyList()): List<PriorityItem> {
        val pnlByCode = dayPnlRows.associate { it.code to it.pnl }
        val items = results.map { result ->
            val dayPnl = pnlByCode[result.code]
            val holding = result.quantity > 0
            val nearResistance = result.resistance > 0 && result.price > 0 &&
                (result.resistance / result.price - 1) * 100 <= 2.0
            val tier = when {
                result.signal == "减仓风险" -> PriorityTier.ACT_NOW
                result.signal == "趋势转弱观察" -> PriorityTier.TREND_WATCH
                result.signal == "持有/止盈观察" -> PriorityTier.TAKE_PROFIT_WATCH
                !holding -> PriorityTier.WAIT_ENTRY
                result.signal == "持有观察" && result.changePct >= 5.0 && nearResistance ->
                    PriorityTier.TAKE_PROFIT_WATCH
                result.signal == "持有观察" && result.volumeSignal.tone == Tone.CAUTION ->
                    PriorityTier.TAKE_PROFIT_WATCH
                result.signal == "持有观察" -> PriorityTier.HOLD
                else -> PriorityTier.WAIT_ENTRY
            }
            PriorityItem(result, tier, tierReason(result, tier, nearResistance), dayPnl)
        }
        // 层内排序：P1 按当日亏损从大到小（最该看的先看），P3 按当日涨幅从大到小。
        return items.sortedWith(
            compareBy<PriorityItem> { it.tier.ordinal }.thenBy { item ->
                when (item.tier) {
                    PriorityTier.ACT_NOW -> item.dayPnl ?: 0.0
                    PriorityTier.TAKE_PROFIT_WATCH -> -(item.result.changePct.takeIf { it.isFinite() } ?: 0.0)
                    else -> 0.0
                }
            },
        )
    }

    private fun tierReason(result: PositionResult, tier: PriorityTier, nearResistance: Boolean): String = when (tier) {
        PriorityTier.ACT_NOW -> "跌破支撑 ${priceText(result.support)}，破位是风险事件"
        PriorityTier.TREND_WATCH -> "趋势转弱，暂缓加仓；深套不盲目补仓"
        PriorityTier.TAKE_PROFIT_WATCH -> when {
            result.signal == "持有/止盈观察" -> "突破确认，持有同时防短线过热"
            result.changePct >= 5.0 && nearResistance -> "当日涨幅已大且逼近压力，防冲高回落"
            else -> "量价偏警示（${VolumePrice.tag(result.volumeSignal)}）"
        }
        PriorityTier.HOLD -> "持有观察，无须动作"
        PriorityTier.WAIT_ENTRY -> if (result.signal == "买入观察") "进入买入观察" else "仍等待"
    }

    /** 生成整篇复盘。持仓为空时给一句说明，而不是空壳章节。 */
    fun generate(inputs: Inputs): String {
        if (inputs.positions.isEmpty()) {
            return listOf(
                "📊 持仓复盘",
                "数据截至：${inputs.analyzedAt}",
                "",
                "尚未配置监控股票，没有可复盘的持仓。先在「持仓」页追加股票。",
                "",
                "⚠️ 市场有风险，投资需谨慎，不构成投资建议。",
            ).joinToString("\n")
        }

        val dayPnl = DayPnl.summarize(inputs.positions, inputs.quotes)
        val aggregates = aggregate(inputs.positions, dayPnl, inputs.portfolio, inputs.indexQuote, inputs.results)
        val reviewDate = inputs.analyzedAt.substringBefore(' ')

        return buildString {
            appendLine("📊 ${titleDate(inputs.analyzedAt)} 持仓复盘")
            appendLine(
                "数据截至：${inputs.analyzedAt}｜口径：今日盈亏为较昨收的日内浮动，" +
                    "浮盈浮亏为较成本口径，两者不混用"
            )
            appendLine()
            appendLine("持仓整体操作建议")
            PortfolioAnalysis.overallAdvice(inputs.results).forEach { appendLine("· $it") }
            append(separator())
            append(attributionSection(dayPnl))
            append(separator())
            append(healthSection(dayPnl, aggregates, inputs))
            append(separator())
            appendLine(
                recentPositiveSection(
                    recentPositives(inputs.positions, inputs.recentAnnouncements, reviewDate, inputs.tradingDates),
                    inputs,
                    reviewDate,
                )
            )
            if (inputs.strongContinuationEvents.isNotEmpty()) {
                append(separator())
                appendLine(strongContinuationSection(inputs.strongContinuationEvents))
            }
            append(separator())
            appendLine("说明：本报告由规则按固定口径拼装（不调用 AI），每个数字都可在「持仓 / 观察信号」卡片与「📦 持仓信息」报告里核对；")
            appendLine("只给条件式技术参考，不自动下单，不替你拍板。")
            appendLine("⚠️ 市场有风险，投资需谨慎，不构成投资建议。")
            append("--- 数据截至 ${inputs.analyzedAt}，仅供参考 ---")
        }
    }

    private fun strongContinuationSection(events: List<StrongContinuationEvent>): String = buildString {
        appendLine("四、📈 强势环境暂缓做T记录")
        appendLine("量能、市场和板块环境同时偏强时，记录为继续观察；后续价格变化仅用于复盘，不代表必然上涨。")
        events.forEach { appendLine("· ${it.reportLine()}") }
        appendLine("结论：这些时点优先观察趋势是否延续，不能仅凭单次冲高记录推断后续必然上涨。")
    }

    // ── 三、近两日个股重大利好 ────────────────────────────────────────────────

    /** 自然日兜底窗口（今天 + 昨天）；有交易日表时窗口回溯到上一交易日，见 [recentCutoff]。 */
    const val RECENT_POSITIVE_DAYS = 2L

    /** 一条近两日利好落地公告；[kind] 与 [EventCoil.positiveEventKind] 同一口径。 */
    data class RecentPositive(
        val name: String,
        val date: String,
        val kind: String,
        val title: String,
    )

    /**
     * 近两日重大利好清单：窗口是「上一交易日 → 今天」（见 [recentCutoff]），再早的不给——
     * 隔了几天的利好多半已经兑现进股价，列出来是误导。
     *
     * 「利好」判定复用 [EventCoil.positiveEventKind]，只收**真利好**（重组落地获批、业绩爆发、
     * 中标/重大合同、大股东增持、高占比回购注销——占比口径不看绝对金额；分红/股权激励/
     * 象征性动作都不算），口径全 app 只有一份。只看真实持仓（数量 > 0），观察股不在复盘范围。
     */
    fun recentPositives(
        positions: List<Position>,
        announcements: Map<String, List<Announcement>>,
        today: String,
        tradingDates: List<String> = emptyList(),
    ): List<RecentPositive> {
        val cutoff = recentCutoff(today, tradingDates) ?: return emptyList()
        return positions
            .filter { it.quantity.isFinite() && it.quantity > 0 }
            .flatMap { position ->
                announcements[position.code].orEmpty()
                    .filter { it.date.isNotBlank() && it.date in cutoff..today }
                    .mapNotNull { announcement ->
                        EventCoil.positiveEventKind(announcement.title, announcement.content)?.let { kind ->
                            RecentPositive(position.name, announcement.date, kind, announcement.title)
                        }
                    }
                    .sortedByDescending { it.date }
            }
    }

    /**
     * 利好窗口的起始日（含当天）：**上一交易日**——周一复盘时窗口要够到周五，
     * 周五盘后和周末挂出的公告才收得进来（自然日「今天+昨天」会漏）。
     *
     * 交易日表缺了或太旧（最近一根早于昨天）就退回「今天 + 昨天」[RECENT_POSITIVE_DAYS]，
     * 不拿旧表瞎放宽窗口；日期解析不了返回 null，由调用方写「暂缺」。
     */
    private fun recentCutoff(today: String, tradingDates: List<String>): String? {
        val yesterday = runCatching { LocalDate.parse(today).minusDays(RECENT_POSITIVE_DAYS - 1).toString() }
            .getOrNull() ?: return null
        val tableFresh = tradingDates.maxOrNull()?.let { it >= yesterday } == true
        if (!tableFresh) return yesterday
        return tradingDates.filter { it < today }.maxOrNull() ?: yesterday
    }

    private fun recentPositiveSection(
        items: List<RecentPositive>,
        inputs: Inputs,
        today: String,
    ): String {
        val holdings = inputs.positions.filter { it.quantity.isFinite() && it.quantity > 0 }
        // 公告没拉到 ≠ 没有利好：Repo.publishAnnouncements 空清单不落库，containsKey 才算「看过」。
        val announced = holdings.count { inputs.recentAnnouncements.containsKey(it.code) }
        val lines = mutableListOf<String>()
        lines.add("三、🔔 近两日个股重大利好")
        val cutoff = recentCutoff(today, inputs.tradingDates) ?: today
        val yesterday = runCatching { LocalDate.parse(today).minusDays(1).toString() }.getOrNull()
        val windowNote = if (yesterday != null && cutoff < yesterday) {
            "（回溯到上一交易日，周末/长假的公告一并收）"
        } else {
            "（今天与昨天）"
        }
        lines.add(
            "只列 $cutoff 以来的真利好落地公告$windowNote；" +
                "更早的利好可能已兑现进股价，不再列出。"
        )
        lines.add("")
        when {
            holdings.isEmpty() -> lines.add("没有持仓，本节不适用。")
            announced == 0 -> lines.add("公告数据暂缺（还没拉到公告清单），近两日是否有利好无法判断。")
            items.isEmpty() -> lines.add("近两日没有命中重大利好的公告。")
            else -> items.forEach {
                lines.add("· 『${it.name}』${it.date} ${it.kind}：${EventCoil.trimTitle(it.title)}")
            }
        }
        lines.add(
            "结论：" + when {
                holdings.isEmpty() -> "没有持仓，本节不适用。"
                announced == 0 -> "公告暂缺，利好面未知，不等于没有利好。"
                items.isEmpty() -> "近两日无重大利好消息面变化。"
                else -> "以上均为近两日的真利好落地，消息面较新；利好不等于股价必涨，市场是否认账仍看量价。"
            }
        )
        return lines.joinToString("\n")
    }

    /** 章节之间的分隔：空行 + `---` + 空行，与盘面总结同一排版。 */
    private fun separator(): String = "\n\n---\n\n"

    // ── 一、盈亏归因（较昨收） ────────────────────────────────────────────────

    private fun attributionSection(dayPnl: DayPnl.Summary): String {
        val lines = mutableListOf<String>()
        lines.add("一、💰 盈亏归因（今天赚/亏的钱从哪来）")
        lines.add("")

        val totalPct = dayPnl.totalPct
        if (totalPct == null) {
            lines.add("今天一只都算不出（全部缺行情或昨收无效），盈亏归因暂缺。")
            lines.add("结论：没有可用行情，今天无法做盈亏归因；等行情恢复后手动重跑即可。")
            return lines.joinToString("\n")
        }

        lines.add(
            "合计：今日盈亏 ${ReportMarkup.red(DayPnl.money(dayPnl.totalPnl) + " 元")}" +
                "（较昨日市值 ${DayPnl.pct(totalPct)}）" +
                "｜持仓市值 ${DayPnl.amount(dayPnl.marketValue)} 元" +
                "｜昨日市值 ${DayPnl.amount(dayPnl.prevMarketValue)} 元"
        )
        if (dayPnl.missing > 0) {
            lines.add("另有 ${dayPnl.missing} 只无行情未计入，归因只覆盖能算的 ${dayPnl.counted} 只。")
        }
        lines.add("")

        val counted = dayPnl.rows.filter { it.pnl != null }
        val profitRows = counted.filter { it.pnl!! > 0 }.sortedByDescending { it.pnl }
        val lossRows = counted.filter { it.pnl!! < 0 }.sortedBy { it.pnl }
        val grossProfit = profitRows.sumOf { it.pnl!! }
        val grossLoss = -lossRows.sumOf { it.pnl!! }

        lines.add(MarketSummary.table(
            headers = listOf("股票", "今日盈亏", "当日涨跌", "占比", "持仓"),
            rows = counted.sortedByDescending { it.pnl }.map { row ->
                val pnl = row.pnl!!
                val share = when {
                    pnl > 0 && grossProfit > 0 -> "占盈利 ${String.format(Locale.CHINA, "%.0f%%", pnl / grossProfit * 100)}"
                    pnl < 0 && grossLoss > 0 -> "占亏损 ${String.format(Locale.CHINA, "%.0f%%", -pnl / grossLoss * 100)}"
                    else -> "—"
                }
                listOf(
                    row.name,
                    DayPnl.money(pnl),
                    row.changePct?.let { DayPnl.pct(it) } ?: "—",
                    share,
                    String.format(Locale.CHINA, "%.0f 股", row.quantity),
                )
            },
        ))
        lines.add("")

        val narrative = attributionNarrative(dayPnl, profitRows, lossRows, grossProfit, grossLoss)
        lines.add(narrative.first)
        lines.add("本节金额均为较昨收的日内浮动；较成本的持仓浮盈浮亏见第二节。")
        lines.add("结论：${narrative.second}")
        return lines.joinToString("\n")
    }

    /**
     * 归因叙述与结论句。
     *
     * 正负混杂且合计接近 0 时只给金额排序：那种日子「占今日盈利 60%」这类百分比会被
     * 互相抵消的分母放大成没有意义的数字，宁可少说一个数。
     */
    private fun attributionNarrative(
        dayPnl: DayPnl.Summary,
        profitRows: List<DayPnl.Row>,
        lossRows: List<DayPnl.Row>,
        grossProfit: Double,
        grossLoss: Double,
    ): Pair<String, String> {
        val total = dayPnl.totalPnl
        val topProfit = profitRows.firstOrNull()
        val topLoss = lossRows.firstOrNull()

        // ① 正负对冲：合计接近 0 但两边都有货，占比无意义，只给排序。
        if (grossProfit > 0 && grossLoss > 0 && abs(total) <= dayPnl.prevMarketValue * 0.001) {
            val hedge = "今天正负基本对冲（${moneyYuan(grossProfit)} 与 ${moneyYuan(-grossLoss)} 相抵后 " +
                "${moneyYuan(total)}）：『${topProfit?.name}』贡献最多，『${topLoss?.name}』拖累最多。" +
                "正负相抵时贡献占比会失真，这里只给金额排序。"
            return hedge to "正负对冲，『${topProfit?.name}』最多赚、『${topLoss?.name}』最多亏，合计 ${moneyYuan(total)}。"
        }

        // ② 合计为 0（各只都没动）
        if (total == 0.0 && grossProfit == 0.0 && grossLoss == 0.0) {
            val flat = "今天各只都没动，盈亏为 0。"
            return flat to "今天没有波动，盈亏为 0。"
        }

        // ③ 盈利日
        if (total > 0 && topProfit != null) {
            val share = if (grossProfit > 0) topProfit.pnl!! / grossProfit * 100 else 0.0
            val rest = dayPnl.totalPnl - topProfit.pnl!!
            // 同号集合里只有一只时，「占今日盈利 100%」是废话，不写。
            val narrative = when {
                profitRows.size == 1 ->
                    "今天赚的钱全部来自『${topProfit.name}』（${moneyYuan(topProfit.pnl!!)}）。"
                share >= 60.0 ->
                    "今天赚的钱主要来自『${topProfit.name}』一只（${moneyYuan(topProfit.pnl!!)}，" +
                        "占今日盈利 ${String.format(Locale.CHINA, "%.0f%%", share)}）" +
                        "；其余 ${profitRows.size - 1} 只合计 ${moneyYuan(rest)}。"
                profitRows.size >= 3 && share < 30.0 ->
                    "今天盈利较分散：" + profitRows.take(3).joinToString("、") { "${it.name} ${moneyYuan(it.pnl!!)}" } +
                        "，没有单一主力。"
                else ->
                    "今天合计盈利 ${moneyYuan(total)}，『${topProfit.name}』贡献最多" +
                        "（${moneyYuan(topProfit.pnl!!)}，占今日盈利 ${String.format(Locale.CHINA, "%.0f%%", share)}）。"
            }
            val lossNote = if (topLoss != null) "『${topLoss.name}』拖累最多（${moneyYuan(topLoss.pnl!!)}）。" else ""
            return (narrative + lossNote) to
                "今天盈利 ${moneyYuan(total)}，主力是『${topProfit.name}』。"
        }

        // ④ 亏损日
        if (total < 0 && topLoss != null) {
            val share = if (grossLoss > 0) -topLoss.pnl!! / grossLoss * 100 else 0.0
            val narrative = when {
                lossRows.size == 1 ->
                    "今天亏损全部来自『${topLoss.name}』（${moneyYuan(topLoss.pnl!!)}）。"
                share >= 50.0 ->
                    "今天亏损主要来自『${topLoss.name}』（${moneyYuan(topLoss.pnl!!)}，" +
                        "占今日亏损 ${String.format(Locale.CHINA, "%.0f%%", share)}）。"
                else ->
                    "今天合计亏损 ${moneyYuan(total)}，『${topLoss.name}』拖累最多" +
                        "（${moneyYuan(topLoss.pnl!!)}，占今日亏损 ${String.format(Locale.CHINA, "%.0f%%", share)}）。"
            }
            val profitNote = if (topProfit != null) "『${topProfit.name}』贡献最多（${moneyYuan(topProfit.pnl!!)}）。" else ""
            return (narrative + profitNote) to
                "今天亏损 ${moneyYuan(total)}，主要拖累是『${topLoss.name}』。"
        }

        val fallback = "今天合计 ${moneyYuan(total)}。"
        return fallback to "今天合计 ${moneyYuan(total)}。"
    }

    // ── 二、组合体检 ──────────────────────────────────────────────────────────

    private fun healthSection(dayPnl: DayPnl.Summary, aggregates: Aggregates, inputs: Inputs): String {
        val lines = mutableListOf<String>()
        lines.add("二、🩺 组合体检（仓位、集中度、与大盘比）")
        lines.add("")

        // 市场环境放在最前面：塌陷期后面的仓位/集中度都不是重点，先看的是系统性风险。
        val market = inputs.market
        if (market != null) {
            lines.add("市场环境：${market.regime.label}——${market.reason}。")
            lines.add("")
        }

        // ① 总仓位。注意：持仓设置里的「最大仓位」是**单股**上限，不能拿来卡总仓位。
        val positionPct = aggregates.totalPositionPct
        lines.add("① 总仓位：" + if (positionPct == null) {
            "总资金未设置，总仓位暂缺（不是 0%）。持仓市值 ${DayPnl.amount(aggregates.marketValue)} 元。"
        } else {
            val band = when {
                positionPct >= 100 -> "已经满仓甚至超配，没有加仓余地"
                positionPct >= 80 -> "仓位偏重"
                positionPct >= 50 -> "仓位中等"
                positionPct >= 20 -> "仓位偏轻"
                else -> "仓位很轻"
            }
            "持仓市值 ${DayPnl.amount(aggregates.marketValue)} 元，占总资金 " +
                "${String.format(Locale.CHINA, "%.1f%%", positionPct)}，$band。" +
                "（持仓设置里的「最大仓位」是单股上限，不是总仓位上限。）"
        })

        // ② 单股集中度
        val topName = aggregates.topName
        val weightOfHoldings = aggregates.topWeightOfHoldingsPct
        lines.add("② 单股集中度：" + if (topName == null || weightOfHoldings == null) {
            "没有可统计的持仓，集中度暂缺。"
        } else {
            val weightOfCapital = aggregates.topWeightOfCapitalPct
            val capitalText = weightOfCapital?.let { "，占总资金 ${String.format(Locale.CHINA, "%.1f%%", it)}" } ?: ""
            val base = "最大单股『$topName』占持仓市值 ${String.format(Locale.CHINA, "%.1f%%", weightOfHoldings)}$capitalText"
            val over = if (weightOfCapital != null && weightOfCapital > aggregates.maxPositionPct) {
                "⚠️ 已超过你设置的单股最大仓位 ${String.format(Locale.CHINA, "%.0f%%", aggregates.maxPositionPct)}。"
            } else {
                ""
            }
            val read = when {
                weightOfHoldings >= 50 -> "高度集中在这一只，它今天基本决定你的账户。"
                weightOfHoldings >= 30 -> "偏集中，别在它身上继续堆仓位。"
                else -> "集中度不高。"
            }
            "$base；$read$over"
        })

        // ③ 与沪指比
        val vs = aggregates.vsIndexPp
        lines.add("③ 与大盘比：" + when {
            vs == null -> "缺沪指或组合当日收益率，无法对比。"
            vs >= 0.5 ->
                "今天组合跑赢沪指 ${ReportMarkup.red(String.format(Locale.CHINA, "%.2f", vs))} 个百分点" +
                    "（组合 ${DayPnl.pct(dayPnl.totalPct ?: 0.0)} vs 沪指 ${inputs.indexQuote?.changePct?.let { DayPnl.pct(it) } ?: "暂缺"}）。"
            vs <= -0.5 ->
                "今天组合跑输沪指 ${ReportMarkup.red(String.format(Locale.CHINA, "%.2f", -vs))} 个百分点" +
                    "（组合 ${DayPnl.pct(dayPnl.totalPct ?: 0.0)} vs 沪指 ${inputs.indexQuote?.changePct?.let { DayPnl.pct(it) } ?: "暂缺"}）；" +
                    "弱于大盘的日子，优先检查谁在拖后腿。"
            else -> "今天组合与沪指基本同步（相差 ${String.format(Locale.CHINA, "%.2f", vs)} 个百分点）。"
        })

        // ④ 总浮盈浮亏（较成本）
        val floating = aggregates.floatingPnl
        val floatingPct = aggregates.floatingPnlPct
        lines.add("④ 总浮盈浮亏（较成本）：" + when {
            floating == null || floatingPct == null ->
                "成本没填全，算不出（不是 0）。把每只的成本补上，这里才能给出较成本的浮动。"
            floatingPct <= -20.0 ->
                "总浮盈浮亏 ${DayPnl.money(floating)} 元（${DayPnl.pct(floatingPct)}），组合整体浮亏较深" +
                    (if (aggregates.deepLossCount > 0) "，其中 ${aggregates.deepLossCount} 只按波动率口径已属深套" else "") +
                    "。这是较成本口径，与第一节的今日盈亏不是一回事。"
            floatingPct >= 20.0 ->
                "总浮盈浮亏 ${DayPnl.money(floating)} 元（${DayPnl.pct(floatingPct)}），浮盈较厚，注意止盈节奏。" +
                    "这是较成本口径，与第一节的今日盈亏不是一回事。"
            else ->
                "总浮盈浮亏 ${DayPnl.money(floating)} 元（${DayPnl.pct(floatingPct)}，较成本）" +
                    (if (aggregates.deepLossCount > 0) "，其中 ${aggregates.deepLossCount} 只按波动率口径已属深套" else "") +
                    "。与第一节的今日盈亏不是同一口径。"
        })

        // 风险点
        val risks = healthRisks(dayPnl, aggregates, inputs)
        if (risks.isNotEmpty()) {
            lines.add("")
            lines.add("风险点：")
            risks.forEach { lines.add("· $it") }
        }

        lines.add("结论：${healthConclusion(dayPnl, aggregates, risks, inputs.market)}")
        return lines.joinToString("\n")
    }

    private fun healthRisks(dayPnl: DayPnl.Summary, aggregates: Aggregates, inputs: Inputs): List<String> {
        val risks = mutableListOf<String>()
        // 系统性风险排第一：塌陷期该做的是降整体仓位，不是逐只慢慢看——顺序反了会误导。
        if (inputs.market?.isCollapse == true) {
            risks.add("大盘塌陷，系统性风险优先：先降整体仓位，别指望个股独善其身")
        }
        // 板块塌陷是小一号的同一件事：那只股所在的行业自己在塌。
        val collapsedBoards = inputs.sectorContexts.values
            .mapNotNull { it.industry?.name }
            .distinct()
            .filter { name ->
                inputs.sectorContexts.values.any { it.industry?.name == name && it.isCollapse }
            }
        if (collapsedBoards.isNotEmpty()) {
            risks.add("板块塌陷：${collapsedBoards.joinToString("、")}——这些行业自己在跌，个股别指望独善其身")
        }
        val weightOfCapital = aggregates.topWeightOfCapitalPct
        if (weightOfCapital != null && weightOfCapital > aggregates.maxPositionPct) {
            risks.add(
                "『${aggregates.topName}』占总资金 ${String.format(Locale.CHINA, "%.1f%%", weightOfCapital)}，" +
                    "已超过你设置的单股最大仓位 ${String.format(Locale.CHINA, "%.0f%%", aggregates.maxPositionPct)}"
            )
        }
        if (aggregates.deepLossCount > 0) {
            risks.add("${aggregates.deepLossCount} 只按波动率口径已属深套（较成本），不要在破位处割肉摊成本")
        }
        val vs = aggregates.vsIndexPp
        if (vs != null && vs <= -0.5) {
            risks.add("今天跑输沪指 ${String.format(Locale.CHINA, "%.2f", -vs)} 个百分点")
        }
        if (dayPnl.missing > 0) {
            risks.add("${dayPnl.missing} 只无行情未计入，今天的盈亏与仓位统计都不完整")
        }
        val bigLoss = dayPnl.rows
            .filter { it.pnl != null && it.pnl < 0 && it.prevMarketValue > 0 }
            .maxByOrNull { -it.pnl!! / it.prevMarketValue }
        if (bigLoss != null) {
            val lossPctOfPrev = -bigLoss.pnl!! / bigLoss.prevMarketValue * 100
            if (lossPctOfPrev >= 1.0) {
                risks.add(
                    "『${bigLoss.name}』单日亏损 ${moneyYuan(bigLoss.pnl!!)}，" +
                        "占它昨日市值 ${String.format(Locale.CHINA, "%.1f%%", lossPctOfPrev)}，是今天最大的单一拖累"
                )
            }
        }
        return risks
    }

    /** 结论句正文（不含「结论：」前缀，由调用方统一加，避免出现两个前缀）。 */
    private fun healthConclusion(dayPnl: DayPnl.Summary, aggregates: Aggregates, risks: List<String>, market: MarketState?): String {
        val marketText = market?.let { "，大盘${it.regime.label}" } ?: ""
        val positionText = aggregates.totalPositionPct
            ?.let { "总仓位 ${String.format(Locale.CHINA, "%.1f%%", it)}" }
            ?: "总仓位暂缺"
        val floatingText = aggregates.floatingPnlPct
            ?.let { "，较成本浮盈浮亏 ${DayPnl.pct(it)}" }
            ?: ""
        val riskText = if (risks.isEmpty()) "没有明显风险点" else "风险点 ${risks.size} 项"
        return "$positionText$floatingText$marketText；$riskText，详见上面几条。"
    }

    // ── 小工具 ────────────────────────────────────────────────────────────────

    private fun titleDate(analyzedAt: String): String {
        val date = analyzedAt.substringBefore(' ')
        val parts = date.split("-")
        return if (parts.size >= 3) "${parts[1]}.${parts[2]}" else date
    }

    private fun priceText(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    /** 散文里的金额：带单位。表格列头已经写了「今日盈亏」，格子里就不用再带「元」。 */
    private fun moneyYuan(value: Double): String = DayPnl.money(value) + " 元"

    private fun signedPct(value: Double): String =
        if (value.isFinite()) DayPnl.pct(value) else "—"

}
