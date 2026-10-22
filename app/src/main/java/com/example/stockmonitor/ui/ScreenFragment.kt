package com.example.stockmonitor.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.FiveOneThreeHit
import com.example.stockmonitor.analysis.FiveOneThreeStage
import com.example.stockmonitor.analysis.VolumePrice
import com.example.stockmonitor.analysis.VolumePriceSignal
import com.example.stockmonitor.analysis.StrategyPerformance
import com.example.stockmonitor.analysis.StrategyPerformanceSummary
import com.example.stockmonitor.analysis.PaperTradeTracker
import com.example.stockmonitor.analysis.ObservationEligibility
import com.example.stockmonitor.analysis.ObservationSection
import com.example.stockmonitor.core.ScreenHit
import com.example.stockmonitor.core.TrendScreenHit
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.monitor.MonitorService
import com.example.stockmonitor.monitor.Repo
import com.google.android.material.button.MaterialButton
import java.util.Locale

/**
 * 选股页。布局跟着两套刷新逻辑走：
 * - 状态卡：两套扫描共享的运行时状态（当前扫描、进度、结果缓存时间）；
 * - 快扫卡：指标两档（严格/宽松），池子是成交量前 200，秒级，卡内自带刷新按钮；
 * - 深扫卡：趋势入场 + 513 形态，池子是全市场，几分钟，卡内自带深扫按钮。
 * 盘中不自动扫描；盘后自动全市场深扫一次，结果落盘、重启后仍在。
 */
class ScreenFragment : BaseFragment(R.layout.fragment_scroll) {

    private fun screenCard(context: Context, key: String, title: String): UiKit.Card {
        val preferences = context.getSharedPreferences("screen_card_expansion", Context.MODE_PRIVATE)
        return UiKit.collapsibleCard(context, title, preferences.getBoolean(key, true)) { expanded ->
            preferences.edit().putBoolean(key, expanded).apply()
        }
    }

    private data class SelectionRowData(
        val key: String,
        val name: String,
        val code: String,
        val price: String,
        val changePct: Double,
        val summary: CharSequence,
        val details: List<UiKit.DetailRow>,
    )

    private fun UiKit.Card.addExpandableSelectionSection(
        context: Context,
        title: String,
        items: List<SelectionRowData>,
        pageKey: String,
        titleColor: Int? = null,
        emptyText: String = "暂无符合条件的股票。",
    ) {
        val heading = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, UiKit.dp(context, 5), 0, UiKit.dp(context, 2))
        }
        heading.addView(
            UiKit.body(context, title, 13f, titleColor).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            },
        )
        val toggle = android.widget.TextView(context).apply {
            textSize = 12f
            setTextColor(UiKit.color(context, R.color.brand_primary))
            gravity = android.view.Gravity.END
            setPadding(UiKit.dp(context, 8), UiKit.dp(context, 4), 0, UiKit.dp(context, 4))
        }
        heading.addView(toggle)
        add(heading)
        if (items.isEmpty()) {
            toggle.visibility = android.view.View.GONE
            addText(emptyText, 12f)
            return
        }

        val pageSize = ObservationEligibility.PAGE_SIZE
        val lastPage = (items.size - 1) / pageSize
        val pages = context.getSharedPreferences("screen_list_pages", Context.MODE_PRIVATE)
        val page = pages.getInt(pageKey, 0).coerceIn(0, lastPage)
        val visibleItems = items.drop(page * pageSize).take(pageSize)
        val rowKeys = visibleItems.map { "selection:${it.key}" }
        val rows = mutableListOf<UiKit.ExpandableQuoteRowView>()
        fun updateToggle() {
            toggle.text = when {
                QuoteRowExpansionState.allExpanded(rowKeys) && lastPage > 0 -> "本页收起 ▴"
                QuoteRowExpansionState.allExpanded(rowKeys) -> "全部收起 ▴"
                lastPage > 0 -> "本页展开 ▾"
                else -> "全部展开 ▾"
            }
        }
        visibleItems.forEach { item ->
            val row = UiKit.expandableQuoteRow(
                context = context,
                stateKey = "selection:${item.key}",
                name = item.name,
                code = item.code,
                price = item.price,
                changePct = item.changePct,
                summary = item.summary,
                details = item.details,
                onExpansionChanged = { updateToggle() },
            )
            rows.add(row)
            add(row.view)
        }
        updateToggle()
        toggle.setOnClickListener {
            val expanded = QuoteRowExpansionState.toggleAll(rowKeys)
            rows.forEach { it.setExpanded(expanded) }
            updateToggle()
        }
        if (lastPage > 0) {
            add(
                android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    fun pageButton(icon: Int, label: String, target: Int) =
                        android.widget.ImageButton(context).apply {
                            setImageResource(icon)
                            contentDescription = label
                            tooltipText = label
                            background = null
                            imageTintList = android.content.res.ColorStateList.valueOf(
                                UiKit.color(context, R.color.text_secondary),
                            )
                            isEnabled = target in 0..lastPage
                            alpha = if (isEnabled) 1f else 0.3f
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                UiKit.dp(context, 44),
                                UiKit.dp(context, 44),
                            )
                            setOnClickListener {
                                pages.edit().putInt(pageKey, target).apply()
                                render()
                            }
                        }
                    addView(pageButton(android.R.drawable.ic_media_previous, "上一页", page - 1))
                    addView(
                        UiKit.body(
                            context,
                            "${page + 1}/${lastPage + 1} 页 · 共${items.size}只",
                            12f,
                        ).apply {
                            gravity = android.view.Gravity.CENTER
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                0,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                        },
                    )
                    addView(pageButton(android.R.drawable.ic_media_next, "下一页", page + 1))
                },
            )
        }
    }

    override fun render(): Unit = rebuild { container ->
        val context = requireContext()
        val result = Repo.screenResult

        screenCard(context, "scan_status", "📡 扫描状态").apply {
            addKeyValue("当前状态", Repo.screenStatus)
            Repo.screenProgress?.let { addKeyValue("进度", it) }
            if (Repo.screenStale) {
                addText(
                    "提示：部分档位本次无命中，保留 ${Repo.screenStaleAt ?: "最近一次"} 的结果（超过 3 天自动清）。",
                    12f,
                    UiKit.color(context, R.color.warn),
                )
            }
            addText(
                "盘中不自动扫描，需要时在下面两组各自手动刷新。" +
                    "盘后 18:00–06:00 自动全市场深扫一次（成功即停，失败 90 分钟重试最多 8 次）。",
                11.5f,
            )
            container.addView(view)
        }

        screenCard(context, "observation_pool", "👀 持续观察池").apply {
            val sections = ObservationEligibility.entrySections(Repo.observationPool, Repo.quotes)
            val active = sections.values.flatten()
            val preBreakout = active.filter { it.strategy == ObservationStrategy.PRE_BREAKOUT }
            val leaders = active.filter { it.strategy == ObservationStrategy.LEADER }
            val limitContinuations = active.filter { it.strategy == ObservationStrategy.LIMIT_UP_CONTINUATION }
            fun isLimitUpDeferred(item: com.example.stockmonitor.core.ObservationCandidate): Boolean =
                ObservationEligibility.isLimitUpDeferred(item, Repo.quotes[item.code])

            val buyCandidates = sections[ObservationSection.BUY]?.size ?: 0
            val nearEntry = sections[ObservationSection.NEAR]?.size ?: 0
            addKeyValue(
                "策略路线",
                "${active.size}只｜涨停持续观察 ${limitContinuations.size}只｜强势龙头 ${leaders.size}只｜启动前潜力 ${preBreakout.size}只",
            )
            addKeyValue(
                "阶段统计",
                "${ObservationStage.BUY_CANDIDATE.label} ${buyCandidates}只\n${ObservationStage.NEAR_ENTRY.label} ${nearEntry}只",
            )
            addText(
                "同为“买点待确认”不代表同一条晋级路线：启动前潜力主要看趋势入场与风险门槛；" +
                    "强势龙头和涨停持续观察还要经过连续复评，涨停封板期间不直接买入。",
                11.5f,
                UiKit.color(context, R.color.text_secondary),
            )
            addKeyValue("复盘证据", "${Repo.observationSnapshots.size}条快照｜每只最近30个交易日")
            val paperOutcomes = Repo.paperTrades.mapNotNull { it.outcome() }
            val paperPerformance = StrategyPerformance.summarize(
                paperOutcomes,
                initialCapital = 100_000.0,
            )
            val pre = paperPerformance[ObservationStrategy.PRE_BREAKOUT]
            val leader = paperPerformance[ObservationStrategy.LEADER]
            addKeyValue(
                "纸上验证",
                "${Repo.paperTrades.size}笔｜未平${Repo.paperTrades.count { it.isOpen }}笔｜每笔模拟${PaperTradeTracker.DEFAULT_QUANTITY}股",
            )
            if (pre != null || leader != null) {
                addText(
                    "龙头：${performanceText(leader)}｜启动前：${performanceText(pre)}。仅统计已触发止损或淘汰的闭合记录。",
                    11.5f,
                )
            }
            addText(ScreenHelpCopy.OBSERVATION_SUMMARY, 12f)
            if (buyCandidates > 0) {
                addText(
                    "${ObservationStage.BUY_CANDIDATE.label} ${buyCandidates}只；封板及缩量加速对象暂缓参与。",
                    12f,
                    UiKit.color(context, R.color.risk_good),
                )
            }
            add(
                android.widget.TextView(context).apply {
                    text = "补录当前结果到观察池 ›"
                    textSize = 12f
                    setTextColor(UiKit.color(context, R.color.text_secondary))
                    setPadding(0, UiKit.dp(context, 6), 0, UiKit.dp(context, 4))
                    setOnClickListener {
                        MonitorService.send(requireContext(), MonitorService.ACTION_OBSERVE_SCREEN)
                    }
                }
            )
            add(
                android.widget.TextView(context).apply {
                    text = "查看观察池规则 ›"
                    textSize = 12f
                    setTextColor(UiKit.color(context, R.color.brand_primary))
                    setPadding(0, UiKit.dp(context, 2), 0, UiKit.dp(context, 6))
                    setOnClickListener {
                        Dialogs.showDetails(
                            requireActivity() as androidx.appcompat.app.AppCompatActivity,
                            "观察池规则",
                            ScreenHelpCopy.observationDetailText(),
                        )
                    }
                }
            )
            fun observationRow(item: com.example.stockmonitor.core.ObservationCandidate): SelectionRowData {
                val quote = Repo.quotes[item.code]
                val limitUpDeferred = isLimitUpDeferred(item)
                val routeLabel = when (item.strategy) {
                    ObservationStrategy.PRE_BREAKOUT -> "启动前潜力"
                    ObservationStrategy.LEADER -> "强势龙头"
                    ObservationStrategy.LIMIT_UP_CONTINUATION -> "涨停持续观察"
                }
                val stageText = when {
                    item.limitUpShrinkAcceleration ->
                        "⚠ 缩量加速风险｜已降级观察｜已观察${item.observedDays}天"
                    limitUpDeferred && item.continuousObservation ->
                        "⏸ 涨停封板持续观察｜开板后重新确认｜已观察${item.observedDays}天"
                    limitUpDeferred -> "⏸ 封板后待重新确认｜已观察${item.observedDays}天"
                    item.stage == ObservationStage.BUY_CANDIDATE ->
                        "${ObservationStage.BUY_CANDIDATE.label}｜已观察${item.observedDays}天"
                    else ->
                        "${item.stage.label}｜${item.score}/3分｜已观察${item.observedDays}天"
                }
                val currentFlow = Repo.fundFlowHistory[item.code]?.lastOrNull()
                val flowHistory = Repo.fundFlowHistory[item.code].orEmpty().filter {
                    (it.tradingDate ?: "") >= item.addedDate
                }
                val flowWindow = com.example.stockmonitor.analysis.FundFlowWindowPolicy.assess(flowHistory)
                val windowText = when {
                    flowWindow.sampleCount >= 2 ->
                        "近3日${flowWindow.recent3PositiveDays}/${minOf(flowWindow.sampleCount, 3)}日净流入"
                    flowWindow.sampleCount == 1 -> "多日样本1/3"
                    else -> ""
                }
                val flowSummary =
                    listOf(
                        com.example.stockmonitor.analysis.LimitReport.fundFlowSummary(currentFlow),
                        windowText,
                    ).filter { it.isNotBlank() }.joinToString("｜")
                val summary = SpannableStringBuilder(stageText).apply {
                    val color = when {
                        item.limitUpShrinkAcceleration -> UiKit.color(context, R.color.warn)
                        limitUpDeferred -> UiKit.color(context, R.color.risk_watch)
                        item.stage == ObservationStage.BUY_CANDIDATE ->
                            UiKit.color(context, R.color.risk_good)
                        else -> UiKit.color(context, R.color.text_secondary)
                    }
                    setSpan(
                        ForegroundColorSpan(color),
                        0,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    append("\n")
                    append(listOf(routeLabel, item.reason, flowSummary).filter { it.isNotBlank() }.joinToString("｜"))
                }
                val limitFlow = if (item.continuousObservation) {
                    Repo.limits?.up?.find { it.code == item.code }
                        ?.let { com.example.stockmonitor.analysis.LimitReport.metricLine(it) }
                } else {
                    null
                }
                return SelectionRowData(
                    key = "observation:${item.code}",
                    name = item.name,
                    code = item.code,
                    price = String.format(Locale.CHINA, "%.2f", quote?.price ?: item.lastPrice),
                    changePct = quote?.changePct ?: Double.NaN,
                    summary = summary,
                    details = listOf(
                        UiKit.DetailRow("晋级路线", routeLabel),
                        UiKit.DetailRow("阶段", stageText),
                        UiKit.DetailRow(
                            "路线口径",
                            when (item.strategy) {
                                ObservationStrategy.PRE_BREAKOUT ->
                                    "趋势入场和风险门槛通过后可晋级，不强制强势龙头式多轮确认"
                                ObservationStrategy.LEADER ->
                                    "回踩企稳需连续2轮复评确认，属于强势结构候选"
                                ObservationStrategy.LIMIT_UP_CONTINUATION ->
                                    "封板期间持续观察；开板后重新确认，三板及以上需连续3轮复评"
                            },
                        ),
                        UiKit.DetailRow("入选理由", item.reason.ifBlank { "暂无" }),
                        UiKit.DetailRow("最近复评", item.lastEvaluatedDate ?: "尚未复评"),
                        UiKit.DetailRow("止损价", item.stopPrice?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "—"),
                        UiKit.DetailRow("涨停与资金流", limitFlow ?: "未处于涨停持续观察"),
                        UiKit.DetailRow("多日资金流", flowWindow.reason),
                        UiKit.DetailRow("支持证据", item.evidence.supporting.joinToString("；").ifBlank { "暂无" }),
                        UiKit.DetailRow("风险证据", item.evidence.counter.joinToString("；").ifBlank { "暂无记录，不代表无风险" }),
                        UiKit.DetailRow("缺失证据", item.evidence.missing.joinToString("；").ifBlank { "暂无记录" }),
                        UiKit.DetailRow("失效条件", item.evidence.invalidators.joinToString("；").ifBlank { "需持续复评" }),
                        UiKit.DetailRow("入池日期", item.addedDate),
                        UiKit.DetailRow("来源", item.source),
                    ),
                )
            }
            fun addStrategyGroup(
                strategy: ObservationStrategy,
                items: List<com.example.stockmonitor.core.ObservationCandidate>,
            ) {
                addDivider()
                addText("${strategy.label}（${items.size}只）", 16f)
                addText(
                    when (strategy) {
                        ObservationStrategy.PRE_BREAKOUT ->
                            "指标严格/宽松、趋势、513等潜力结果进入此路线；重点看趋势入场和风险门槛，不要求龙头式多轮确认。"
                        ObservationStrategy.LEADER ->
                            "强势结构路线；买点确认需要回踩企稳并连续2轮复评。"
                        ObservationStrategy.LIMIT_UP_CONTINUATION ->
                            "涨停持续观察路线；封板期间不直接买入，开板后重新确认，三板及以上需连续3轮复评。"
                    },
                    11.5f,
                    UiKit.color(context, R.color.text_secondary),
                )
                if (items.isEmpty()) {
                    addText("暂无买点已确认或买点待确认的股票。", 12f)
                    return
                }
                ObservationEligibility.entrySections(items, Repo.quotes).forEach { (section, candidates) ->
                    if (candidates.isEmpty()) return@forEach
                    val color = when (section) {
                        ObservationSection.BUY -> R.color.risk_good
                        ObservationSection.NEAR, ObservationSection.LIMIT_CONTINUOUS,
                        ObservationSection.DEFERRED -> R.color.risk_watch
                        ObservationSection.OBSERVING -> R.color.text_secondary
                    }
                    addExpandableSelectionSection(
                        context = context,
                        title = "${section.label}（${candidates.size}只）",
                        items = candidates.map(::observationRow),
                        pageKey = "observation:${strategy.name}:${section.name}",
                        titleColor = UiKit.color(context, color),
                    )
                }
            }
            addStrategyGroup(ObservationStrategy.LEADER, leaders)
            addStrategyGroup(ObservationStrategy.LIMIT_UP_CONTINUATION, limitContinuations)
            addStrategyGroup(ObservationStrategy.PRE_BREAKOUT, preBreakout)
            container.addView(view)
        }

        screenCard(context, "indicator_scan", "📊 指标两档 · 快扫").apply {
            addKeyValue("池子", "成交量前200只 / 近120日日线")
            addKeyValue("耗时", "秒级")
            addKeyValue("上次刷新", Repo.screenIndicatorAt ?: "尚未扫描")
            addText(ScreenHelpCopy.INDICATOR_SCAN_SUMMARY, 12f)
            addText(
                "入池去向：持续观察池 → 启动前潜力",
                11.5f,
                UiKit.color(context, R.color.brand_primary),
            )
            add(
                android.widget.TextView(context).apply {
                    text = "查看快扫策略口径 ›"
                    textSize = 12f
                    setTextColor(UiKit.color(context, R.color.brand_primary))
                    setPadding(0, UiKit.dp(context, 3), 0, UiKit.dp(context, 5))
                    setOnClickListener {
                        Dialogs.showDetails(
                            requireActivity() as androidx.appcompat.app.AppCompatActivity,
                            "指标快扫口径",
                            ScreenHelpCopy.indicatorScanDetailText(),
                        )
                    }
                }
            )
            add(
                MaterialButton(context).apply {
                    text = "重扫指标"
                    setOnClickListener {
                        MonitorService.send(requireContext(), MonitorService.ACTION_SCREEN)
                    }
                }
            )
            addExpandableSelectionSection(
                context = context,
                title = "✅ 严格策略（${result.strict.size}只）",
                items = result.strict.map { hit ->
                    SelectionRowData(
                        key = "indicator-strict:${hit.code}",
                        name = hit.name,
                        code = hit.code,
                        price = String.format(Locale.CHINA, "%.2f", hit.price),
                        changePct = hit.changePct,
                        summary = screenNote(
                            context,
                            hit,
                            String.format(
                                Locale.CHINA,
                                "%s｜RSI %.0f｜K %.0f > D %.0f｜DIF %.3f",
                                hit.code, hit.rsi, hit.k, hit.d, hit.dif,
                            ),
                        ),
                        details = screenDetails(hit),
                    )
                },
                pageKey = "indicator:strict",
                emptyText = "暂无符合严格条件的股票。",
            )
            addDivider()
            addExpandableSelectionSection(
                context = context,
                title = "⚠️ 宽松预警（${result.relaxed.size}只）",
                items = result.relaxed.map { hit ->
                    val tags = mutableListOf<String>()
                    if (hit.macdDetail.isNotBlank()) tags.add("MACD:${hit.macdDetail}")
                    if (hit.rsiOk) tags.add("RSI")
                    if (hit.kdjOk) tags.add("KDJ低位K>D")
                    SelectionRowData(
                        key = "indicator-relaxed:${hit.code}",
                        name = hit.name,
                        code = hit.code,
                        price = String.format(Locale.CHINA, "%.2f", hit.price),
                        changePct = hit.changePct,
                        summary = screenNote(
                            context,
                            hit,
                            String.format(
                                Locale.CHINA,
                                "%s｜%d/3分｜%s",
                                hit.code, hit.relaxedScore, tags.joinToString(" | "),
                            ),
                        ),
                        details = screenDetails(hit),
                    )
                },
                pageKey = "indicator:relaxed",
                emptyText = "暂无符合宽松预警的股票。",
            )
            container.addView(view)
        }

        screenCard(context, "trend_scan", "🔭 513 与趋势 · 深扫").apply {
            addKeyValue("池子", "全部沪深A股 / 近120日日线")
            addKeyValue("耗时", "数分钟（数千根日K）")
            addKeyValue("上次刷新", Repo.screenPatternAt ?: "尚未扫描")
            addText(ScreenHelpCopy.DEEP_SCAN_SUMMARY, 12f)
            addText(
                "入池去向：持续观察池 → 启动前潜力",
                11.5f,
                UiKit.color(context, R.color.brand_primary),
            )
            add(
                android.widget.TextView(context).apply {
                    text = "查看深扫策略口径 ›"
                    textSize = 12f
                    setTextColor(UiKit.color(context, R.color.brand_primary))
                    setPadding(0, UiKit.dp(context, 3), 0, UiKit.dp(context, 5))
                    setOnClickListener {
                        Dialogs.showDetails(
                            requireActivity() as androidx.appcompat.app.AppCompatActivity,
                            "513与趋势深扫口径",
                            ScreenHelpCopy.deepScanDetailText(),
                        )
                    }
                }
            )
            add(
                MaterialButton(context).apply {
                    text = "深扫全市场"
                    setOnClickListener {
                        MonitorService.send(requireContext(), MonitorService.ACTION_SCREEN_FULL)
                    }
                }
            )
            addText(
                "样本 ${result.fiveOneThreeStats.totalStocks}只｜有效日线 ${result.fiveOneThreeStats.historyAvailable}只\n" +
                    "5连阳 历史${result.fiveOneThreeStats.historicalFiveBullish} / 当前${result.fiveOneThreeStats.recentFiveBullish}｜" +
                    "倍量确认 ${result.fiveOneThreeStats.waitingConfirmation}｜完整确认 ${result.fiveOneThreeStats.confirmed}",
                11.5f,
            )
            addExpandableSelectionSection(
                context = context,
                title = "🧭 513低位形态（${result.fiveOneThree.size}只）",
                items = result.fiveOneThree.map { hit ->
                    SelectionRowData(
                        key = "deep-513:${hit.code}",
                        name = hit.name,
                        code = hit.code,
                        price = String.format(Locale.CHINA, "%.2f", hit.price),
                        changePct = Double.NaN,
                        summary = fiveOneThreeNote(context, hit),
                        details = fiveOneThreeDetails(hit),
                    )
                },
                pageKey = "deep:513",
                emptyText = "暂无符合 513 形态阶段的股票。",
            )
            addDivider()
            addExpandableSelectionSection(
                context = context,
                title = "📐 趋势精选（${result.trend.size}只）",
                items = result.trend.map { hit ->
                    SelectionRowData(
                        key = "deep-trend:${hit.code}",
                        name = trendIcon(hit),
                        code = hit.code,
                        price = String.format(Locale.CHINA, "%.2f", hit.price),
                        changePct = hit.changePct,
                        summary = trendNote(context, hit),
                        details = trendDetails(hit),
                    )
                },
                pageKey = "deep:trend",
                emptyText = "暂无通过趋势精选条件的股票。",
            )
            container.addView(view)
        }

        screenCard(context, "help", "ℹ️ 说明").apply {
            addText(ScreenHelpCopy.PRIMARY_SUMMARY, 11.5f)
            add(
                MaterialButton(context).apply {
                    text = "查看口径与标签说明"
                    setOnClickListener {
                        Dialogs.showDetails(
                            requireActivity() as androidx.appcompat.app.AppCompatActivity,
                            "口径与标签说明",
                            ScreenHelpCopy.detailText(),
                        )
                    }
                },
            )
            container.addView(view)
        }
    }

    /**
     * 选股一行的副标题。命中时在指标后面补一段量价标签。
     *
     * 量比单独看是二义的，所以按「量比 × 当日涨跌 × 价格位置」给结论：
     * 位置不高时放量上涨记资金进场，位置高时记高位放量（防冲高回落）；
     * 缩量只作中性文字提示，不加褒贬色。
     */
    private fun screenNote(context: Context, hit: ScreenHit, detail: String): CharSequence {
        val builder = SpannableStringBuilder(detail)
        val ratio = hit.volumeRatio ?: return builder
        val signal = VolumePrice.of(ratio, hit.changePct, hit.priceContext)
        if (signal == VolumePriceSignal.NEUTRAL) return builder
        val start = builder.length
        builder.append(
            String.format(
                Locale.CHINA, "｜%s %.2f倍",
                VolumePrice.tag(signal), ratio,
            )
        )
        builder.setSpan(
            ForegroundColorSpan(UiKit.signalColor(context, signal)),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return builder
    }

    private fun screenDetails(hit: ScreenHit): List<UiKit.DetailRow> = listOf(
        UiKit.DetailRow("股票代码", hit.code),
        UiKit.DetailRow("观察池去向", "启动前潜力"),
        UiKit.DetailRow("RSI", String.format(Locale.CHINA, "%.1f", hit.rsi)),
        UiKit.DetailRow("KDJ", String.format(Locale.CHINA, "K %.1f / D %.1f", hit.k, hit.d)),
        UiKit.DetailRow("DIF", String.format(Locale.CHINA, "%.3f", hit.dif)),
        UiKit.DetailRow("MACD", hit.macdDetail.ifBlank { "—" }),
        UiKit.DetailRow("条件评分", "${hit.relaxedScore}/3"),
        UiKit.DetailRow("量比", hit.volumeRatio?.let { String.format(Locale.CHINA, "%.2f倍", it) } ?: "—"),
        UiKit.DetailRow(
            "量价信号",
            hit.volumeRatio?.let {
                VolumePrice.tag(VolumePrice.of(it, hit.changePct, hit.priceContext)).ifBlank { "中性" }
            } ?: "—",
        ),
        UiKit.DetailRow("价格位置", hit.priceContext?.location?.label ?: "—"),
        UiKit.DetailRow(
            "低点反弹",
            hit.priceContext?.let { String.format(Locale.CHINA, "%.1f%%", it.reboundFromLow * 100) } ?: "—",
        ),
        UiKit.DetailRow("趋势资格", if (hit.relaxedTrendEligible) "符合" else "不符合"),
    )

    private fun fiveOneThreeNote(context: Context, hit: FiveOneThreeHit): CharSequence {
        val stage = when (hit.stage) {
            FiveOneThreeStage.OBSERVING -> "513观察中：5连阳形成，等待倍量阳线"
            FiveOneThreeStage.WAITING_CONFIRMATION -> "513等待确认：倍量阳出现，等待3天缩量"
            FiveOneThreeStage.CONFIRMED -> "513形态确认：5连阳 → 倍量阳 → 3天缩量"
        }
        val builder = SpannableStringBuilder(stage)
        val stageColor = when (hit.stage) {
            FiveOneThreeStage.OBSERVING -> UiKit.color(context, R.color.brand_primary)
            FiveOneThreeStage.WAITING_CONFIRMATION -> UiKit.color(context, R.color.risk_watch)
            FiveOneThreeStage.CONFIRMED -> UiKit.color(context, R.color.risk_good)
        }
        builder.setSpan(
            ForegroundColorSpan(stageColor),
            0,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.append("\n${hit.detail}｜近60日涨幅 ${hit.gain60Pct.formatPct()}")
        return builder
    }

    private fun fiveOneThreeDetails(hit: FiveOneThreeHit): List<UiKit.DetailRow> {
        val stage = when (hit.stage) {
            FiveOneThreeStage.OBSERVING -> "观察中"
            FiveOneThreeStage.WAITING_CONFIRMATION -> "等待确认"
            FiveOneThreeStage.CONFIRMED -> "形态确认"
        }
        return listOf(
            UiKit.DetailRow("股票代码", hit.code),
            UiKit.DetailRow("观察池去向", "启动前潜力"),
            UiKit.DetailRow("阶段", stage),
            UiKit.DetailRow("进度", hit.progress),
            UiKit.DetailRow("形态明细", hit.detail),
            UiKit.DetailRow("近60日涨幅", hit.gain60Pct.formatPct()),
        )
    }

    private fun trendIcon(hit: TrendScreenHit): String =
        if (hit.entrySetup.canBuy) "🟢 ${hit.name}" else "🟡 ${hit.name}"

    private fun trendNote(context: Context, hit: TrendScreenHit): CharSequence {
        val text = buildString {
            append(hit.entrySetup.conciseText())
            hit.volumeRatio?.let { append(String.format(Locale.CHINA, "｜量比 %.2f", it)) }
            hit.entrySetup.stopPrice?.let { append(String.format(Locale.CHINA, "｜止损 %.2f", it)) }
        }
        val builder = SpannableStringBuilder(text)
        val color = if (hit.entrySetup.canBuy) {
            UiKit.color(context, R.color.risk_good)
        } else {
            UiKit.color(context, R.color.risk_watch)
        }
        builder.setSpan(ForegroundColorSpan(color), 0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return builder
    }

    private fun trendDetails(hit: TrendScreenHit): List<UiKit.DetailRow> = listOf(
        UiKit.DetailRow("股票代码", hit.code),
        UiKit.DetailRow("观察池去向", "启动前潜力"),
        UiKit.DetailRow("入场结构", hit.entrySetup.label),
        UiKit.DetailRow("确认状态", if (hit.entrySetup.confirmed) "已确认" else "待确认"),
        UiKit.DetailRow("交易属性", if (hit.entrySetup.canBuy) "可执行" else "仅作观察"),
        UiKit.DetailRow("结构原因", hit.entrySetup.reason),
        UiKit.DetailRow("止损价", hit.entrySetup.stopPrice?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "—"),
        UiKit.DetailRow("量比", hit.volumeRatio?.let { String.format(Locale.CHINA, "%.2f倍", it) } ?: "—"),
        UiKit.DetailRow("前高", hit.entrySetup.priorHigh?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "—"),
        UiKit.DetailRow(
            "M20斜率",
            hit.entrySetup.ma20SlopePct?.let { String.format(Locale.CHINA, "%+.2f%%", it) } ?: "—",
        ),
        UiKit.DetailRow("放量确认", if (hit.entrySetup.volumeConfirmed) "已确认" else "未确认"),
        UiKit.DetailRow("大盘过滤", hit.entrySetup.marketFilter.label),
        UiKit.DetailRow("趋势风险", if (hit.entrySetup.trendWeak) "结构走弱" else "未见明显破位"),
    )

    private fun performanceText(summary: StrategyPerformanceSummary?): String {
        if (summary == null || summary.closedTrades == 0) return "暂无闭合记录"
        return String.format(
            Locale.CHINA,
            "闭合%d笔｜胜率%.0f%%｜收益%+.2f元｜回撤%.2f%%",
            summary.closedTrades,
            summary.winRatePct,
            summary.totalPnl,
            summary.maxDrawdownPct,
        )
    }

    private fun Double.formatPct(): String = String.format(Locale.CHINA, "%+.1f%%", this)
}
