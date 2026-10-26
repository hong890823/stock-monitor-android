package com.example.stockmonitor.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.FundFlowDisplayPolicy
import com.example.stockmonitor.analysis.MarketSummary
import com.example.stockmonitor.analysis.TimeUtils
import com.example.stockmonitor.analysis.LimitReport
import com.example.stockmonitor.analysis.PriceContext
import com.example.stockmonitor.analysis.TechnicalTrendSnapshot
import com.example.stockmonitor.analysis.VolumePrice
import com.example.stockmonitor.analysis.VolumePriceSignal
import com.example.stockmonitor.analysis.VolumeRatio
import com.example.stockmonitor.core.ConceptSource
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.core.WatchItem
import com.example.stockmonitor.monitor.MonitorService
import com.example.stockmonitor.monitor.Repo
import com.google.android.material.button.MaterialButton
import java.util.Locale

/** 市场页：指数、自选股、行业/概念板块、涨跌停。 */
class MarketFragment : BaseFragment(R.layout.fragment_scroll) {

    override fun render(): Unit = rebuild { container ->
        val context = requireContext()
        val store = Store(context)
        val watchlist = Repo.watchlistOrder.ifEmpty { store.watchlist.map { it.code to it.name } }
        val barCodes = store.watchlist.filter { it.showInBar }.map { it.code }.toSet()
        // 「同步到自选股」只对之后新增的监控股票生效，早先加进「持仓」的不会自动回填，
        // 这里给一个一键补齐的入口，只有真的缺了才显示。
        val existingCodes = watchlist.map { it.first }.toSet()
        val missingPositions = store.portfolio.positions.filterNot { it.code in existingCodes }
        val fillAction: (() -> Unit)? = if (missingPositions.isEmpty()) {
            null
        } else {
            {
                store.watchlist = (store.watchlist + missingPositions.map {
                    WatchItem(it.code, it.name, showInBar = false)
                }).toMutableList()
                Toast.makeText(context, "已补进 ${missingPositions.size} 只监控股票", Toast.LENGTH_SHORT).show()
                MonitorService.send(context, MonitorService.ACTION_REFRESH)
                render()
            }
        }

        val indexQuotes = watchlist
            .filter { it.first.startsWith("sh000") || it.first.startsWith("sz399") }
            .mapNotNull { item -> Repo.quotes[item.first]?.let { item to it } }
        val stockItems = watchlist.filter { (code, _) ->
            !code.startsWith("sh000") && !code.startsWith("sz399")
        }

        // ── 概况 ──
        UiKit.card(context, "📊 市场概览").apply {
            addText(
                MarketSummary.overviewTitle(
                    indexQuotes.map { it.second },
                    Repo.turnoverSnapshot,
                    includeTurnover = false,
                ),
                13f,
                UiKit.color(context, R.color.text_primary),
            )
            // 休市时段（周末/节假日/清晨）显示的是最近交易日收盘，标出来免得当成实时行情。
            val dataDate = Repo.quotes.values
                .mapNotNull { it.quoteDate?.substringBefore(' ') }
                .maxOrNull()
            if (dataDate != null && dataDate < TimeUtils.todayText()) {
                addText(
                    "休市中 · 显示 $dataDate 收盘数据",
                    11.5f,
                    UiKit.color(context, R.color.text_secondary),
                )
            }
            // 成交额整块统一用最近一次 15 分钟采样的值，保证标题、总额、对比口径一致。
            val snapshot = Repo.turnoverSnapshot
            val displayTurnover = snapshot?.today ?: Repo.turnoverToday
            if (displayTurnover > 0) {
                val notes = mutableListOf<String>()
                snapshot?.let { notes.add("${it.sampledAt} 采样") }
                val predicted = snapshot?.predictedFullDay
                if (predicted != null && predicted > 0 && snapshot.sampleMinute < 900) {
                    notes.add(String.format(Locale.CHINA, "全天预估约 %,.0f亿", predicted / 1e8))
                }
                addKeyValue(
                    "两市总成交",
                    String.format(Locale.CHINA, "%,.0f亿", displayTurnover / 1e8),
                    note = notes.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                )
                val sampled = snapshot?.today ?: 0.0
                val previous = snapshot?.previousSameTime
                if (previous != null && previous > 0 && sampled > 0) {
                    val delta = sampled - previous
                    addKeyValue(
                        "较昨日同期（${snapshot.sampledAt}）",
                        String.format(Locale.CHINA, "%+,.0f亿 (%+.1f%%)", delta / 1e8, delta / previous * 100),
                        UiKit.changeColor(context, delta),
                    )
                } else {
                    addKeyValue("较昨日同期", "暂无数据", note = "已开始按 15 分钟记录，明天起可对比")
                }
            }
            // 「状态」行暂时不展示（Repo.statusText 仍在通知与「工具」页使用）。
            // addKeyValue("状态", Repo.statusText)
            Repo.lastUpdateAt?.let { addKeyValue("最近更新行情", it) }
            Repo.lastError?.let { addText("⚠️ $it", 11.5f, UiKit.color(context, R.color.warn)) }
            container.addView(view)
        }

        // ── 指数 ──
        UiKit.card(context, "📈 指数").apply {
            if (indexQuotes.isEmpty()) addText("等待行情数据…")
            indexQuotes.forEach { (item, quote) ->
                add(
                    UiKit.quoteRow(
                        context,
                        item.second,
                        quote.code,
                        StockPriceText.of(quote.price),
                        quote.changePct,
                        note = String.format(
                            Locale.CHINA, "昨收 %.2f｜今开 %.2f｜高 %.2f｜低 %.2f｜成交 %.0f亿",
                            quote.prevClose, quote.open, quote.high, quote.low, quote.turnover / 1e8,
                        ),
                    )
                )
            }
            container.addView(view)
        }

        // ── 自选股 ──
        val watchRowKeys = stockItems.mapNotNull { (code, _) ->
            Repo.quotes[code]?.let { "market-watch:$code" }
        }
        val watchRows = mutableListOf<UiKit.ExpandableQuoteRowView>()
        UiKit.card(
            context,
            "⭐ 自选股",
            actionText = if (fillAction == null) null else "补齐监控股票（${missingPositions.size}）",
            onAction = fillAction,
            onSecondaryAction = {
                val expanded = QuoteRowExpansionState.toggleAll(watchRowKeys)
                watchRows.forEach { it.setExpanded(expanded) }
                expanded
            },
        ).apply {
            if (stockItems.isEmpty()) addText("还没有自选股，可在「工具」页添加。")
            stockItems.forEach { (code, name) ->
                val quote = Repo.quotes[code]
                if (quote == null) {
                    addKeyValue(name, "等待行情…")
                } else {
                    val row = UiKit.expandableQuoteRow(
                            context = context,
                            stateKey = "market-watch:$code",
                            name = name + if (code in barCodes) "  ●" else "",
                            code = code,
                            price = StockPriceText.of(quote.price),
                            changePct = quote.changePct,
                            summary = watchSummary(
                                context = context,
                                changePct = quote.changePct,
                                ratio = Repo.volumeRatios[code],
                                priceContext = Repo.priceContexts[code],
                                fundFlow = currentFundFlowSummary(code),
                                technicalTrend = Repo.technicalTrendSnapshots[code],
                            ),
                            details = watchDetails(
                                code = code,
                                quote = quote,
                                ratio = Repo.volumeRatios[code],
                                priceContext = Repo.priceContexts[code],
                                technicalTrend = Repo.technicalTrendSnapshots[code],
                            ),
                            onExpansionChanged = {
                                setSecondaryExpanded(QuoteRowExpansionState.allExpanded(watchRowKeys))
                            },
                    )
                    watchRows.add(row)
                    add(row.view)
                }
            }
            setSecondaryExpanded(QuoteRowExpansionState.allExpanded(watchRowKeys))
            container.addView(view)
        }

        // ── 行业涨幅榜 / 跌幅榜 ──
        val sectors = Repo.sectors
        UiKit.card(context, "🔥 行业涨幅榜").apply {
            val top = sectors?.top.orEmpty()
            if (top.isEmpty()) addText("暂无数据 · 等待更新（板块每30分钟刷新）")
            top.forEach { sector ->
                addKeyValue(
                    sector.name,
                    String.format(Locale.CHINA, "%+.2f%%", sector.change),
                    UiKit.changeColor(context, sector.change),
                    note = if (sector.volume > 0)
                        String.format(Locale.CHINA, "成分股 %d 只｜成交 %.0f亿", sector.count, sector.volume / 1e8)
                    else null,
                )
            }
            container.addView(view)
        }
        UiKit.card(context, "💧 行业跌幅榜").apply {
            val bottom = sectors?.bottom.orEmpty()
            if (bottom.isEmpty()) addText("暂无数据 · 等待更新")
            bottom.forEach { sector ->
                addKeyValue(
                    sector.name,
                    String.format(Locale.CHINA, "%+.2f%%", sector.change),
                    UiKit.changeColor(context, sector.change),
                )
            }
            container.addView(view)
        }

        // ── 概念板块 ──
        UiKit.card(context, "🤖 概念板块").apply {
            val boards = Repo.concepts
            val concepts = boards?.top.orEmpty()
            if (concepts.isEmpty()) addText("暂无数据 · 等待更新")
            concepts.forEach { concept ->
                addKeyValue(
                    concept.name,
                    String.format(Locale.CHINA, "%+.2f%%", concept.change),
                    UiKit.changeColor(context, concept.change),
                )
            }
            if (boards != null && concepts.isNotEmpty()) {
                val source = when (boards.source) {
                    ConceptSource.SINA -> "${boards.source.label}（东财不可达时的备用口径，分类与东财不同）"
                    else -> boards.source.label
                }
                val stamp = if (boards.stamp.isBlank()) "" else " · ${boards.stamp}"
                addText("来源：$source$stamp", 11.5f, UiKit.color(context, R.color.text_secondary))
            }
            container.addView(view)
        }

        // ── 涨跌停 ──
        UiKit.card(context, "📊 涨跌停").apply {
            val limits = Repo.limits
            if (limits == null) {
                addText("暂无数据 · 等待更新")
            } else {
                addKeyValue("涨停 / 跌停", "${limits.up.size} 只 / ${limits.down.size} 只")
                addText(
                    "每侧最多显示 8 只；封单为数据源时点快照。数据来源：${limits.source.label}。" +
                        "封板质量仅用于涨停观察排序，不代表买入信号。",
                    11.5f,
                )
                val upRowKeys = limits.up.take(8).map { "market-limit-up:${it.code}" }
                val downRowKeys = limits.down.take(8).map { "market-limit-down:${it.code}" }
                val upRows = mutableListOf<UiKit.ExpandableQuoteRowView>()
                val downRows = mutableListOf<UiKit.ExpandableQuoteRowView>()
                val upToggle = TextView(context).apply {
                    textSize = 12f
                    setTextColor(UiKit.color(context, R.color.brand_primary))
                    gravity = android.view.Gravity.END
                    setPadding(0, UiKit.dp(context, 2), 0, UiKit.dp(context, 4))
                }
                val downToggle = TextView(context).apply {
                    textSize = 12f
                    setTextColor(UiKit.color(context, R.color.brand_primary))
                    gravity = android.view.Gravity.END
                    setPadding(0, UiKit.dp(context, 4), 0, UiKit.dp(context, 4))
                }
                fun updateLimitToggle(toggle: TextView, keys: List<String>) {
                    toggle.text = if (QuoteRowExpansionState.allExpanded(keys)) {
                        "全部收起 ▴"
                    } else {
                        "全部展开 ▾"
                    }
                }
                add(upToggle)
                limits.up.take(8).forEach { stock ->
                    val boards = stock.boards.take(3).joinToString("、")
                    val row = UiKit.expandableQuoteRow(
                        context = context,
                        stateKey = "market-limit-up:${stock.code}",
                        name = "▲ ${stock.name}",
                        code = stock.code,
                        price = StockPriceText.of(stock.price),
                        changePct = stock.pct,
                        summary = buildString {
                            append(stock.code)
                            if (boards.isNotBlank()) append("｜$boards")
                            appendLine()
                            append("封板质量 ${stock.sealQualityScore?.let { "${it}分" } ?: "—"}")
                            stock.sealQualityCoveragePct?.let { append("（覆盖 $it%）") }
                            stock.limitStreak?.takeIf { it > 0 }?.let { append("｜${it}连板") }
                            stock.firstSealTime?.takeIf { it.length >= 5 }?.let {
                                append("｜首封 ${it.substring(0, 5)}")
                            }
                            stock.lastSealTime?.takeIf { it.length >= 5 }?.let {
                                append("｜末封 ${it.substring(0, 5)}")
                            }
                            append("｜${LimitReport.fundFlowSummary(stock.fundFlow)}")
                        },
                        details = LimitReport.detailRows(stock).map { (label, value) ->
                            UiKit.DetailRow(label, value)
                        },
                        onExpansionChanged = {
                            updateLimitToggle(upToggle, upRowKeys)
                        },
                    )
                    upRows.add(row)
                    add(row.view)
                }
                updateLimitToggle(upToggle, upRowKeys)
                upToggle.setOnClickListener {
                    val expanded = QuoteRowExpansionState.toggleAll(upRowKeys)
                    upRows.forEach { it.setExpanded(expanded) }
                    updateLimitToggle(upToggle, upRowKeys)
                }
                add(
                    MaterialButton(context).apply {
                        text = "查看全部涨停（${limits.up.size}只）"
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = UiKit.dp(context, 8) }
                        setOnClickListener {
                            ReportActivity.open(context, "📊 全部涨停", LimitReport.formatUp(limits))
                        }
                    }
                )
                addDivider()
                add(downToggle)
                limits.down.take(8).forEach { stock ->
                    val boards = stock.boards.take(3).joinToString("、")
                    val row = UiKit.expandableQuoteRow(
                        context = context,
                        stateKey = "market-limit-down:${stock.code}",
                        name = "▼ ${stock.name}",
                        code = stock.code,
                        price = StockPriceText.of(stock.price),
                        changePct = stock.pct,
                        summary = buildString {
                            append(stock.code)
                            if (boards.isNotBlank()) append("｜$boards")
                            appendLine()
                            append("开板 ${stock.openCount?.toString() ?: "—"}")
                            if (stock.openCount != null) append("次")
                            append("｜${LimitReport.fundFlowSummary(stock.fundFlow)}")
                        },
                        details = LimitReport.detailRows(stock).map { (label, value) ->
                            UiKit.DetailRow(label, value)
                        },
                        onExpansionChanged = {
                            updateLimitToggle(downToggle, downRowKeys)
                        },
                    )
                    downRows.add(row)
                    add(row.view)
                }
                updateLimitToggle(downToggle, downRowKeys)
                downToggle.setOnClickListener {
                    val expanded = QuoteRowExpansionState.toggleAll(downRowKeys)
                    downRows.forEach { it.setExpanded(expanded) }
                    updateLimitToggle(downToggle, downRowKeys)
                }
                add(
                    MaterialButton(context).apply {
                        text = "查看全部跌停（${limits.down.size}只）"
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = UiKit.dp(context, 8) }
                        setOnClickListener {
                            ReportActivity.open(context, "📊 全部跌停", LimitReport.formatDown(limits))
                        }
                    }
                )
            }
            container.addView(view)
        }

        UiKit.card(context, "ℹ️ 数据说明").apply {
            addText(
                "行情来自新浪财经；概念板块主源为东方财富，东财不可达时自动退回新浪概念板块" +
                    "（分类口径不同，卡片下方会标注来源）。均为公开接口，数据可能有几秒延迟。",
                11.5f,
            )
            addText(requireContext().getString(R.string.disclaimer), 11.5f)
            container.addView(view)
        }
    }

    /**
     * 自选股一行的副标题。
     *
     * 成交额本身保留，但是否算「有效放量」要配当日涨跌方向和价格位置一起看：同样是放量，
     * 低位上涨是资金进场（成交额与标签用涨色），高位放量则要防情绪高潮/借量出货（警示色）；
     * 缩量只作中性文字提示，不动成交额颜色，因为缩量的好坏同样取决于位置。
     * 量比口径与个股报告、持仓报告里的「量比」完全一致（见 VolumeRatio），
     * 位置口径见 PricePosition。
     */
    private fun watchSummary(
        context: Context,
        changePct: Double,
        ratio: Double?,
        priceContext: PriceContext?,
        fundFlow: String,
        technicalTrend: TechnicalTrendSnapshot?,
    ): CharSequence {
        val builder = SpannableStringBuilder()
        builder.append(
            if (technicalTrend == null) {
                "技术综合评分计算中｜周/月趋势数据不足"
            } else {
                "技术综合评分 ${technicalTrend.technicalScore}/100｜周/月趋势 " +
                    "${technicalTrend.weeklyTrend.label}/${technicalTrend.monthlyTrend.label}"
            },
        )
        builder.append("｜")
        val signal = VolumePrice.of(ratio, changePct, priceContext)
        if (signal != VolumePriceSignal.NEUTRAL && ratio != null) {
            val color = UiKit.signalColor(context, signal)
            val labelStart = builder.length
            builder.append(String.format(Locale.CHINA, "%s %.2f倍", VolumePrice.tag(signal), ratio))
            builder.setSpan(
                ForegroundColorSpan(color),
                labelStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        } else {
            builder.append("量价中性")
        }
        builder.append("｜$fundFlow")
        return builder
    }

    private fun watchDetails(
        code: String,
        quote: Quote,
        ratio: Double?,
        priceContext: PriceContext?,
        technicalTrend: TechnicalTrendSnapshot?,
    ): List<UiKit.DetailRow> {
        val signal = VolumePrice.of(ratio, quote.changePct, priceContext)
        return listOfNotNull(
            UiKit.DetailRow("股票代码", code),
            UiKit.DetailRow(
                "技术综合评分",
                technicalTrend?.let { "${it.technicalScore}/100" } ?: "计算中",
            ),
            UiKit.DetailRow(
                "周/月趋势",
                technicalTrend?.let { "${it.weeklyTrend.label}/${it.monthlyTrend.label}" } ?: "数据不足",
            ),
            technicalTrend?.let { UiKit.DetailRow("技术分构成", it.evidenceSummary.removePrefix("技术分构成：")) },
            technicalTrend?.let { UiKit.DetailRow("操作建议", it.primaryAdvice) },
            UiKit.DetailRow("成交额", String.format(Locale.CHINA, "%.2f亿", quote.turnover / 1e8)),
            UiKit.DetailRow(
                "最高 / 最低",
                String.format(Locale.CHINA, "%.2f / %.2f", quote.high, quote.low),
            ),
            UiKit.DetailRow("量比", ratio?.let { String.format(Locale.CHINA, "%.2f倍", it) } ?: "—"),
            UiKit.DetailRow("量价信号", VolumePrice.tag(signal).ifBlank { "中性" }),
            UiKit.DetailRow("资金流", currentFundFlowSummary(code)),
            UiKit.DetailRow("近3日资金流", fundFlowWindowSummary(code).ifBlank { "样本不足" }),
        )
    }

    private fun currentFundFlowSummary(code: String): String {
        val current = Repo.fundFlowHistory[code].orEmpty().lastOrNull()
        return FundFlowDisplayPolicy.current(current)
    }

    private fun fundFlowWindowSummary(code: String): String {
        return FundFlowDisplayPolicy.multiDay(Repo.fundFlowHistory[code].orEmpty())
    }

}

/** 价格显示规则与 macOS 版一致：百元以上取整，其余两位小数。 */
object StockPriceText {
    fun of(price: Double): String =
        if (price >= 100) String.format(Locale.CHINA, "%.0f", price)
        else String.format(Locale.CHINA, "%.2f", price)
}
