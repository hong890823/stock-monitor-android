package com.example.stockmonitor.ui

import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.PositionResult
import com.example.stockmonitor.analysis.TechnicalTrendSnapshot
import com.example.stockmonitor.analysis.FundFlowDisplayPolicy
import com.example.stockmonitor.analysis.VolumePrice
import com.example.stockmonitor.analysis.VolumePriceSignal
import com.example.stockmonitor.core.LeaderStrengthTracker
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.SectorLeaders
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.core.TradeLotLedger
import com.example.stockmonitor.core.cashPosition
import com.example.stockmonitor.monitor.MonitorService
import com.example.stockmonitor.monitor.Repo
import com.google.android.material.button.MaterialButton
import java.util.Locale

/** 持仓页：持仓配置、盘中信号、成本计算。 */
class PortfolioFragment : BaseFragment(R.layout.fragment_scroll) {
    override fun render(): Unit =
        rebuild { container ->
            val context = requireContext()
            val store = Store(context)
            val portfolio = store.portfolio
            val tradeBook = store.tradeBook
            val tradeAvailability = TradeLotLedger.availability(tradeBook, portfolio.positions, java.time.LocalDate.now().toString())
            val sellableTotal = tradeAvailability.sumOf { it.actionableSellableToday }
            val lockedTotal = tradeAvailability.sumOf { it.lockedToday }
            val nextDayTotal = tradeAvailability.sumOf { it.actionableNextTradingDaySellable }
            val untrackedTotal = tradeAvailability.sumOf { it.untrackedQuantity }
            val ledgerInconsistent = tradeAvailability.any { !it.ledgerConsistent }
            val swingPlan = Repo.portfolioSwing?.validatedFor(portfolio)
            val holdingRowKeys = if (Repo.portfolioResults.isEmpty()) {
                portfolio.positions.map { "portfolio:${it.code}" }
            } else {
                Repo.portfolioResults.map { "portfolio:${it.code}" }
            }
            val holdingRows = mutableListOf<UiKit.ExpandableQuoteRowView>()

            UiKit.card(context, "⚙️ 持仓设置").apply {
                val cash = portfolio.cashPosition()
                addKeyValue(
                    "总资金",
                    if (portfolio.totalCapital > 0) {
                        String.format(Locale.CHINA, "%,.0f 元", portfolio.totalCapital)
                    } else {
                        "未设置"
                    },
                    note = "本金口径，只有这里能改。",
                )
                // 可用现金只展示当前生效的一个值：自动算出来的，或手动校正后的，不再罗列推导过程。
                val cashValue =
                    if (cash.blockedReason != null && cash.value <= 0) {
                        "算不出"
                    } else {
                        String.format(Locale.CHINA, "%,.0f 元", cash.value) +
                            if (cash.isCorrected) " · 已校正" else ""
                    }
                addKeyValue(
                    "可用现金",
                    cashValue,
                    note = cash.blockedReason?.takeIf { cash.value <= 0 },
                )
                addKeyValue("单股初始仓位", String.format(Locale.CHINA, "%.0f%%", portfolio.initialEntryPct))
                addKeyValue("单股最大仓位", String.format(Locale.CHINA, "%.0f%%", portfolio.maxPositionPct))
                addKeyValue("监控股票", "${portfolio.positions.size} 只")
                addKeyValue(
                    "T+1 可卖",
                    if (ledgerInconsistent) {
                        "账本不一致，当前可执行卖出 0 股｜今日锁定 ${formatShares(lockedTotal)}｜下一交易日 0 股"
                    } else {
                        "今日可卖 ${formatShares(sellableTotal)}｜今日锁定 ${formatShares(lockedTotal)}｜下一交易日 ${formatShares(nextDayTotal)}"
                    },
                    note = if (ledgerInconsistent) {
                        "持仓数量与交易批次不一致，先在「记录交易」核对批次；暂不生成可执行卖量。"
                    } else if (untrackedTotal > 0) {
                        "其中 ${formatShares(untrackedTotal)} 股未记录买入日期，不会自动解锁；在「记录交易」里补记"
                    } else {
                        null
                    },
                )
                addDivider()
                add(
                    buttonRow(context) { row ->
                        row.addView(
                            actionButton(context, "💰 设置资金") {
                                Dialogs.portfolioCapital(requireActivity() as androidx.appcompat.app.AppCompatActivity) { render() }
                            },
                        )
                        row.addView(
                            actionButton(context, "➕ 追加股票") {
                                Dialogs.addPosition(requireScope(), context) { render() }
                            },
                        )
                        row.addView(
                            actionButton(context, "✏️ 编辑持仓") {
                                Dialogs.editPosition(context) { render() }
                            },
                        )
                        row.addView(
                            actionButton(context, "📒 记录交易") {
                                Dialogs.recordTrade(context) { render() }
                            },
                        )
                        row.addView(actionButton(context, "➖ 删除股票") { Dialogs.deletePosition(context) { render() } })
                    },
                )
                container.addView(view)
            }

            container.addView(portfolioBriefCard(context, store).view)

            UiKit
                .card(
                    context,
                    "📦 持仓股",
                    if (Repo.portfolioTask.isActive) "进行中…" else "立即分析",
                    onSecondaryAction = {
                        val expanded = QuoteRowExpansionState.toggleAll(holdingRowKeys)
                        holdingRows.forEach { it.setExpanded(expanded) }
                        expanded
                    },
                ) {
                    // 不用等盘中槽位，随时可以重跑一次；后台分析完成后 Repo 变化会自动刷新本页。
                    if (!Repo.portfolioTask.isActive) {
                        MonitorService.send(context, MonitorService.ACTION_PORTFOLIO)
                    }
                }.apply {
                    addTaskStatus(Repo.portfolioTask)
                    add(holdingSignalLegend(context))
                    val results = Repo.portfolioResults
                    if (portfolio.positions.isEmpty()) {
                        addText("还没有配置监控股票。先在「持仓设置」里追加股票，盘中每 30 分钟自动分析一次。")
                    } else if (results.isEmpty()) {
                        addText("等待盘中分析结果（每 30 分钟一个槽位，盘后保留最后一次结果）；也可点右上角「立即分析」。")
                        portfolio.positions.forEach { position ->
                            val quote = Repo.quotes[position.code]
                            val technicalTrend = Repo.technicalTrendSnapshots[position.code]
                            val row = UiKit.expandableQuoteRow(
                                    context = context,
                                    stateKey = "portfolio:${position.code}",
                                    name = position.name,
                                    code = position.code,
                                    price = quote?.let { String.format(Locale.CHINA, "%.2f", it.price) } ?: "—",
                                    changePct = quote?.changePct ?: Double.NaN,
                                    summary = portfolioSummary(
                                        positionText = positionSummary(position),
                                        fundFlow = currentFundFlowSummary(position.code),
                                        technicalTrend = technicalTrend,
                                    ),
                                    details = portfolioDetails(position, quote, technicalTrend),
                                    onExpansionChanged = {
                                        setSecondaryExpanded(
                                            QuoteRowExpansionState.allExpanded(holdingRowKeys),
                                        )
                                    },
                            )
                            holdingRows.add(row)
                            add(row.view)
                        }
                    } else {
                        results.forEach { result ->
                            val swing = swingPlan?.rows?.firstOrNull { it.code == result.code }
                            val technicalTrend = Repo.technicalTrendSnapshots[result.code]
                            val positionText =
                                if (result.quantity > 0) {
                                    String.format(Locale.CHINA, "持仓 %.0f 股｜成本 %.2f", result.quantity, result.cost)
                                } else {
                                    "未持仓观察"
                                }
                            val responseText =
                                swing?.takeIf { it.addQuantity > 0 }?.action() ?: result.action
                            val swingText =
                                swing?.takeIf { it.addQuantity <= 0 }?.action()
                            val row = UiKit.expandableQuoteRow(
                                    context = context,
                                    stateKey = "portfolio:${result.code}",
                                    name = "${signalIcon(result.signal)} ${result.name}",
                                    code = result.code,
                                    price = String.format(Locale.CHINA, "%.2f", result.price),
                                    changePct = result.changePct,
                                    summary = portfolioSummary(
                                        positionText = positionText,
                                        score = result.score,
                                        fundFlow = currentFundFlowSummary(result.code),
                                        technicalTrend = technicalTrend,
                                    ),
                                    details = portfolioDetails(
                                        result = result,
                                        positionText = positionText,
                                        responseText = responseText,
                                        swingText = swingText,
                                        technicalTrend = technicalTrend,
                                    ),
                                    onExpansionChanged = {
                                        setSecondaryExpanded(
                                            QuoteRowExpansionState.allExpanded(holdingRowKeys),
                                        )
                                    },
                            )
                            holdingRows.add(row)
                            add(row.view)
                            addDivider()
                        }

                    }
                    setSecondaryExpanded(QuoteRowExpansionState.allExpanded(holdingRowKeys))
                    container.addView(view)
                }

            UiKit.card(context, "📝 持仓复盘").apply {
                val hasReview = !Repo.dailyReviewText.isNullOrBlank() || !store.lastDailyReviewText.isNullOrBlank()
                addTaskStatus(Repo.dailyReviewTask)
                addText(
                    if (hasReview) {
                        "把当日盈亏与技术面拼成一篇复盘：今天为什么赚/亏、现在什么状态、接下来怎么办。" +
                            "已缓存最近一次，可随时查看。"
                    } else {
                        "尚未生成。每天 16:00 收盘后自动生成并落盘；周末与节假日不生成。"
                    },
                    12f,
                )
                add(
                    buttonRow(context) { row ->
                        row.addView(
                            actionButton(context, if (Repo.dailyReviewTask.isActive) "正在生成…" else "重跑复盘") {
                                if (!Repo.dailyReviewTask.isActive) {
                                    // 走临时前台服务（与工具页报告同款），不再为一份复盘拉起整个后台监控。
                                    com.example.stockmonitor.monitor.AnalysisService.enqueueDailyReview(context)
                                }
                            },
                        )
                        row.addView(
                            actionButton(context, "查看复盘报告") {
                                val text = Repo.dailyReviewText ?: store.lastDailyReviewText
                                if (text.isNullOrBlank()) {
                                    android.widget.Toast
                                        .makeText(
                                            context,
                                            "暂无复盘，先点「重跑复盘」",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                } else {
                                    ReportActivity.open(context, "📝 持仓复盘", text)
                                }
                            },
                        )
                    },
                )
                addText("自动档失败不会反复重试，可手动重跑；节假日会提示「今日非交易日」，不会伪造今日数据。", 11.5f)
                container.addView(view)
            }

            UiKit.card(context, "📌 板块龙头").apply {
                addText(
                    "「调整龙头」只改这个板块里谁算龙头；「板块管理」只改股票属于哪个板块。" +
                        "做T降级只认龙头：龙头强时才说明板块还有空间，龙头转弱后恢复做T提醒。",
                    11.5f,
                    UiKit.color(context, R.color.text_secondary),
                )
                add(
                    buttonRow(context) { row ->
                        row.addView(
                            actionButton(context, "📌 调整龙头") {
                                BoardLeaderDialogs.openPicker(requireScope(), context, store) { render() }
                            },
                        )
                        row.addView(
                            actionButton(context, "🗂 板块管理") {
                                BoardGroupDialogs.openManager(requireScope(), context, store) { render() }
                            },
                        )
                    },
                )
                val groups = BoardGroupDialogs.displayGroups(store).filter { it.holdingNames.isNotEmpty() || it.custom }
                if (groups.isEmpty()) {
                    addText("暂无持仓板块归属，可先点「板块管理」校正；「调整龙头」入口始终可用。", 11.5f)
                } else {
                    groups.forEach { group ->
                        val leaders = Repo.sectorLeaders[group.groupId]
                        addText(
                            "「${group.name}」" +
                                (
                                    if (group.holdingNames.isNotEmpty()) {
                                        "（持仓：${group.holdingNames.joinToString("、")}）"
                                    } else {
                                        "（无持仓）"
                                    }
                                ),
                            13f,
                            UiKit.color(context, R.color.text_primary),
                        )
                        if (leaders == null || leaders.members.isEmpty()) {
                            addText("龙头名单暂缺 · 可点上方「调整龙头」手动校正", 11.5f)
                        } else {
                            val sealed = LeaderStrengthTracker.sealedNames(leaders.members, Repo.quotes).toSet()
                            val heldHigh = LeaderStrengthTracker.heldHighNames(leaders.members, Repo.quotes).toSet()
                            leaders.members.forEach { member ->
                                val quote = Repo.quotes[member.quoteCode]
                                val mark =
                                    when {
                                        member.name in sealed -> "🔴"
                                        member.name in heldHigh -> "⚡"
                                        else -> ""
                                    }
                                val changePct = quote?.changePct?.takeIf { it.isFinite() } ?: member.changePct
                                val turnoverNote =
                                    quote
                                        ?.turnover
                                        ?.takeIf { it.isFinite() && it > 0 }
                                        ?.let { String.format(Locale.CHINA, "成交 %,.2f亿｜%s", it / 1e8, member.quoteCode) }
                                        ?: "成交额暂缺｜${member.quoteCode}"
                                add(
                                    UiKit.quoteRow(
                                        context,
                                        member.name + mark,
                                        member.quoteCode,
                                        quote?.let { StockPriceText.of(it.price) } ?: "—",
                                        changePct,
                                        note = turnoverNote,
                                    ),
                                )
                            }
                            addText(
                                (
                                    if (leaders.overrides.isEmpty) {
                                        "成交额前 ${SectorLeaders.SIZE} 名"
                                    } else {
                                        "成交额前 ${SectorLeaders.SIZE} 名 · 已人工调整"
                                    }
                                ) +
                                    (if (sealed.isNotEmpty()) "｜🔴${sealed.joinToString("、")} 封板" else "") +
                                    (
                                        if (heldHigh.minus(sealed).isNotEmpty()) {
                                            "｜⚡${heldHigh.minus(sealed).joinToString("、")} 炸板守住高位"
                                        } else {
                                            ""
                                        }
                                    ),
                                11.5f,
                                UiKit.color(context, R.color.text_secondary),
                            )
                        }
                        addDivider()
                    }
                }
                container.addView(view)
            }

            UiKit.card(context, "💰 交易工具").apply {
                add(
                    buttonRow(context) { row ->
                        row.addView(
                            actionButton(context, "买卖成本计算") {
                                Dialogs.tradeCalculator(requireScope(), context)
                            },
                        )
                        row.addView(
                            actionButton(context, "买卖策略（降低持仓成本）") {
                                Dialogs.costReductionStrategy(requireScope(), context)
                            },
                        )
                    },
                )
                addText(
                    "成本计算是正向（股数×价格→成本）；买卖策略是反向：输入想降到的目标成本，" +
                        "结合趋势、支撑/阻力、MA20 和波段区间给出补仓、高抛低吸或风险减仓方案。两者都不写回持仓配置；" +
                        "未计入的佣金、印花税、过户费会影响实际结果。",
                    11.5f,
                )
                container.addView(view)
            }
        }

    /** 量价标签与自选股 / 选股页同源；无信号时不动这行文本。 */
    private fun volumeTag(result: PositionResult): String =
        if (result.volumeSignal == VolumePriceSignal.NEUTRAL) {
            ""
        } else {
            String.format(Locale.CHINA, "｜%s", VolumePrice.tag(result.volumeSignal))
        }

    private fun fundFlowSummary(code: String): String {
        return listOf(currentFundFlowSummary(code), fundFlowWindowSummary(code))
            .joinToString("｜")
    }

    private fun currentFundFlowSummary(code: String): String =
        FundFlowDisplayPolicy.current(Repo.fundFlowHistory[code].orEmpty().lastOrNull())

    private fun fundFlowWindowSummary(code: String): String {
        return FundFlowDisplayPolicy.multiDay(Repo.fundFlowHistory[code].orEmpty())
    }

    private fun positionSummary(position: Position): String =
        if (position.quantity > 0) {
            String.format(
                Locale.CHINA,
                "持仓 %.0f 股｜成本 %.2f",
                position.quantity,
                position.cost,
            )
        } else {
            "未持仓观察"
        }

    private fun portfolioSummary(
        positionText: String,
        score: Int? = null,
        fundFlow: String,
        technicalTrend: TechnicalTrendSnapshot?,
    ): String = listOfNotNull(
        positionText,
        technicalTrend?.let {
            "技术综合评分 ${it.technicalScore}/100｜周/月趋势 ${it.weeklyTrend.label}/${it.monthlyTrend.label}"
        } ?: "技术综合评分计算中",
        score?.let { String.format(Locale.CHINA, "策略评分 %+d", it) },
        fundFlow,
    ).joinToString("｜")

    private fun portfolioDetails(
        position: Position,
        quote: Quote?,
        technicalTrend: TechnicalTrendSnapshot?,
    ): List<UiKit.DetailRow> =
        listOfNotNull(
            UiKit.DetailRow("股票代码", position.code),
            UiKit.DetailRow("持仓 / 成本", positionSummary(position)),
            UiKit.DetailRow("技术综合评分", technicalTrend?.let { "${it.technicalScore}/100" } ?: "计算中"),
            UiKit.DetailRow(
                "周/月趋势",
                technicalTrend?.let { "${it.weeklyTrend.label}/${it.monthlyTrend.label}" } ?: "数据不足",
            ),
            technicalTrend?.let { UiKit.DetailRow("技术分构成", it.evidenceSummary.removePrefix("技术分构成：")) },
            technicalTrend?.let { UiKit.DetailRow("操作建议", it.primaryAdvice) },
            quote?.let {
                UiKit.DetailRow(
                    "成交额",
                    String.format(Locale.CHINA, "%.2f亿", it.turnover / 1e8),
                )
            },
            UiKit.DetailRow("资金流", currentFundFlowSummary(position.code)),
            UiKit.DetailRow(
                "近3日资金流",
                fundFlowWindowSummary(position.code).ifBlank { "样本不足" },
            ),
            UiKit.DetailRow("分析状态", "等待盘中分析结果"),
        )

    private fun portfolioDetails(
        result: PositionResult,
        positionText: String,
        responseText: String,
        swingText: String?,
        technicalTrend: TechnicalTrendSnapshot?,
    ): List<UiKit.DetailRow> {
        val pct = result.positionPct?.let { String.format(Locale.CHINA, "，占总资金 %.1f%%", it) } ?: ""
        return listOfNotNull(
            UiKit.DetailRow("股票代码", result.code),
            UiKit.DetailRow("持仓 / 成本", positionText),
            UiKit.DetailRow("状态", "${result.exitDecision?.stage?.label ?: result.signal}$pct"),
            UiKit.DetailRow("策略评分", String.format(Locale.CHINA, "%+d", result.score)),
            UiKit.DetailRow("技术综合评分", technicalTrend?.let { "${it.technicalScore}/100" } ?: "计算中"),
            UiKit.DetailRow(
                "周/月趋势",
                technicalTrend?.let { "${it.weeklyTrend.label}/${it.monthlyTrend.label}" } ?: "数据不足",
            ),
            technicalTrend?.let { UiKit.DetailRow("技术分构成", it.evidenceSummary.removePrefix("技术分构成：")) },
            technicalTrend?.let { UiKit.DetailRow("操作建议", it.primaryAdvice) },
            UiKit.DetailRow(
                "支撑 / 压力",
                String.format(Locale.CHINA, "%.2f / %.2f", result.support, result.resistance),
            ),
            UiKit.DetailRow(
                "量比 / 量价",
                String.format(Locale.CHINA, "%.2f倍", result.volumeRatio) + volumeTag(result),
            ),
            UiKit.DetailRow("资金流", currentFundFlowSummary(result.code)),
            UiKit.DetailRow(
                "近3日资金流",
                fundFlowWindowSummary(result.code).ifBlank { "样本不足" },
            ),
            UiKit.DetailRow("入场结构", result.entrySetup.compactText()),
            UiKit.DetailRow("结构依据", result.entrySetup.reason),
            result.entrySetup.stopPrice?.let {
                UiKit.DetailRow("失效 / 止损", String.format(Locale.CHINA, "%.2f", it))
            },
            UiKit.DetailRow("应对", responseText),
            swingText?.let { UiKit.DetailRow("波段追加", it) },
        )
    }

    private fun formatShares(value: Double): String =
        String.format(Locale.CHINA, "%.0f 股", value)

    private fun signalIcon(signal: String): String =
        when (signal) {
            "买入确认" -> "🟢"
            "买入观察" -> "🔵"
            "持有观察" -> "⚪"
            "持有/止盈观察" -> "🟡"
            "减仓风险" -> "🔴"
            "趋势转弱观察", "等待" -> "⚫"
            else -> "⚪"
        }

    private fun holdingSignalLegend(context: android.content.Context): android.view.View =
        HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = UiKit.dp(context, 4)
                bottomMargin = UiKit.dp(context, 6)
            }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    listOf(
                        "🟢" to "买入确认",
                        "🔵" to "买入观察",
                        "⚪" to "持有观察",
                        "🟡" to "持有/止盈观察",
                        "⚫" to "趋势转弱观察 / 等待",
                        "🔴" to "减仓风险",
                    ).forEach { (icon, label) ->
                        addView(
                            UiKit.body(context, "$icon\n$label", 11f).apply {
                                gravity = android.view.Gravity.CENTER
                                minWidth = UiKit.dp(context, 58)
                                setPadding(
                                    UiKit.dp(context, 5),
                                    UiKit.dp(context, 2),
                                    UiKit.dp(context, 5),
                                    UiKit.dp(context, 2),
                                )
                                contentDescription = "$label：$icon"
                            },
                        )
                    }
                },
            )
        }

    private fun buttonRow(
        context: android.content.Context,
        block: (LinearLayout) -> Unit,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            block(this)
        }

    private fun actionButton(
        context: android.content.Context,
        text: String,
        onClick: () -> Unit,
    ): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            textSize = 13f
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = UiKit.dp(context, 4)
                        bottomMargin = UiKit.dp(context, 4)
                    }
            setOnClickListener { onClick() }
        }
}
