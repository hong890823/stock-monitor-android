package com.example.stockmonitor.ui

import android.widget.LinearLayout
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.PositionResult
import com.example.stockmonitor.analysis.VolumePrice
import com.example.stockmonitor.analysis.VolumePriceSignal
import com.example.stockmonitor.core.LeaderStrengthTracker
import com.example.stockmonitor.core.SectorLeaders
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.core.TradeLotLedger
import com.example.stockmonitor.core.availableCapitalIsManual
import com.example.stockmonitor.core.resolvedAvailableCapital
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
            val sellableTotal = tradeAvailability.sumOf { it.sellableToday }
            val lockedTotal = tradeAvailability.sumOf { it.lockedToday }
            val nextDayTotal = tradeAvailability.sumOf { it.nextTradingDaySellable }
            val swingPlan = Repo.portfolioSwing?.validatedFor(portfolio)

            UiKit.card(context, "⚙️ 持仓设置").apply {
                addKeyValue(
                    "总资金",
                    if (portfolio.totalCapital > 0) {
                        String.format(Locale.CHINA, "%,.0f 元", portfolio.totalCapital)
                    } else {
                        "未设置"
                    },
                )
                addKeyValue("单股初始仓位", String.format(Locale.CHINA, "%.0f%%", portfolio.initialEntryPct))
                addKeyValue(
                    "可用现金",
                    String.format(
                        Locale.CHINA,
                        "%,.0f 元（%s）",
                        portfolio.resolvedAvailableCapital(),
                        if (portfolio.availableCapitalIsManual()) "手动校正" else "自动计算",
                    ),
                )
                addKeyValue("单股最大仓位", String.format(Locale.CHINA, "%.0f%%", portfolio.maxPositionPct))
                addKeyValue("监控股票", "${portfolio.positions.size} 只")
                addKeyValue(
                    "T+1 可卖",
                    "今日可卖 ${formatShares(sellableTotal)}｜今日锁定 ${formatShares(lockedTotal)}｜下一交易日 ${formatShares(nextDayTotal)}",
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
                            actionButton(context, "✏️ 编辑股数 / 成本") {
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
                    "📦 持仓 / 观察信号",
                    if (Repo.portfolioTask.isActive) "进行中…" else "立即分析",
                ) {
                    // 不用等盘中槽位，随时可以重跑一次；后台分析完成后 Repo 变化会自动刷新本页。
                    if (!Repo.portfolioTask.isActive) {
                        MonitorService.send(context, MonitorService.ACTION_PORTFOLIO)
                    }
                }.apply {
                    addTaskStatus(Repo.portfolioTask)
                    val results = Repo.portfolioResults
                    if (portfolio.positions.isEmpty()) {
                        addText("还没有配置监控股票。先在「持仓设置」里追加股票，盘中每 30 分钟自动分析一次。")
                    } else if (results.isEmpty()) {
                        addText("等待盘中分析结果（每 30 分钟一个槽位，盘后保留最后一次结果）；也可点右上角「立即分析」。")
                        portfolio.positions.forEach { position ->
                            val quote = Repo.quotes[position.code]
                            add(
                                UiKit.quoteRow(
                                    context,
                                    position.name,
                                    position.code,
                                    quote?.let { String.format(Locale.CHINA, "%.2f", it.price) } ?: "—",
                                    quote?.changePct ?: Double.NaN,
                                    note =
                                        if (position.quantity > 0) {
                                            String.format(
                                                Locale.CHINA,
                                                "%s｜持仓 %.0f 股｜成本 %.2f",
                                                position.code,
                                                position.quantity,
                                                position.cost,
                                            )
                                        } else {
                                            "${position.code}｜未持仓观察"
                                        },
                                ),
                            )
                        }
                    } else {
                        results.forEach { result ->
                            val swing = swingPlan?.rows?.firstOrNull { it.code == result.code }
                            val positionText =
                                if (result.quantity > 0) {
                                    String.format(Locale.CHINA, "持仓 %.0f 股｜成本 %.2f", result.quantity, result.cost)
                                } else {
                                    "未持仓观察"
                                }
                            // 用行情行的样式：价格旁边直接给出当日涨跌幅，信号仍由名称前的图标表示。
                            add(
                                UiKit.quoteRow(
                                    context,
                                    "${signalIcon(result.signal)} ${result.name}",
                                    result.code,
                                    String.format(Locale.CHINA, "%.2f", result.price),
                                    result.changePct,
                                    note =
                                        String.format(
                                            Locale.CHINA,
                                            "%s｜%s｜评分 %+d",
                                            result.code,
                                            positionText,
                                            result.score,
                                        ),
                                ),
                            )
                            val pct = result.positionPct?.let { String.format(Locale.CHINA, "，占总资金 %.1f%%", it) } ?: ""
                            addText(
                                "状态：${result.exitDecision?.stage?.label ?: result.signal}$pct\n" +
                                    "支撑 ${String.format(Locale.CHINA, "%.2f", result.support)}｜" +
                                    "压力 ${String.format(Locale.CHINA, "%.2f", result.resistance)}｜" +
                                    "量比 ${String.format(Locale.CHINA, "%.2f", result.volumeRatio)}" +
                                    volumeTag(result) + "\n" +
                                    "入场结构：${result.entrySetup.conciseText()}" +
                                    (
                                        result.entrySetup.stopPrice?.let {
                                            "｜止损 ${String.format(Locale.CHINA, "%.2f", it)}"
                                        } ?: ""
                                    ) + "\n" +
                                    "应对：${swing?.takeIf { it.addQuantity > 0 }?.action() ?: result.action}" +
                                    (swing?.takeIf { it.addQuantity <= 0 }?.let { "\n波段追加：${it.action()}" } ?: ""),
                                12f,
                            )
                            addDivider()
                        }

                    }
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
                                    MonitorService.send(context, MonitorService.ACTION_DAILY_REVIEW)
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
                            actionButton(context, "📌 调整龙头（手动指定 / 排除）") {
                                BoardLeaderDialogs.openPicker(requireScope(), context, store) { render() }
                            },
                        )
                        row.addView(
                            actionButton(context, "🗂 板块管理（修改板块归属）") {
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

    private fun formatShares(value: Double): String =
        String.format(Locale.CHINA, "%.0f 股", value)

    private fun signalIcon(signal: String): String =
        when (signal) {
            "买入确认" -> "🟢"
            "买入观察" -> "🔵"
            "持有观察" -> "⚪"
            "持有/止盈观察" -> "🟡"
            "减仓风险" -> "🔴"
            else -> "⚫"
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
