package com.example.stockmonitor.ui

import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.core.widget.NestedScrollView
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.FiveOneThreeHit
import com.example.stockmonitor.analysis.FiveOneThreeStage
import com.example.stockmonitor.analysis.VolumePrice
import com.example.stockmonitor.analysis.VolumePriceSignal
import com.example.stockmonitor.analysis.ObservationEligibility
import com.example.stockmonitor.analysis.LimitUpFilterPolicy
import com.example.stockmonitor.analysis.StrongSectorRankingPolicy
import com.example.stockmonitor.core.ScreenHit
import com.example.stockmonitor.core.TrendScreenHit
import com.example.stockmonitor.core.LimitStock
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.monitor.MonitorService
import com.example.stockmonitor.monitor.Repo
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.Locale

/**
 * 选股页。布局跟着两套刷新逻辑走：
 * - 状态卡：两套扫描共享的运行时状态（当前扫描、进度、结果缓存时间）；
 * - 快扫卡：指标两档（严格/宽松），池子是成交量前 200，秒级，卡内自带刷新按钮；
 * - 深扫卡：趋势入场 + 513 形态，池子是全市场，几分钟，卡内自带深扫按钮。
 * 盘中不自动扫描；盘后自动全市场深扫一次，结果落盘、重启后仍在。
 */
class ScreenFragment : BaseFragment(R.layout.fragment_scroll) {
    private val strongRankingCache = mutableMapOf<String, StrongSectorRankingPolicy.Result>()
    private var renderPending = false
    private var selectionScrolling = false
    private val selectionScrollIdleRunnable = Runnable {
        selectionScrolling = false
        renderIfStale()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val scrollView = (view as? android.view.ViewGroup)?.getChildAt(0) as? NestedScrollView
        scrollView?.setOnScrollChangeListener { _, _, _, _, _ ->
            selectionScrolling = true
            view.removeCallbacks(selectionScrollIdleRunnable)
            view.postDelayed(selectionScrollIdleRunnable, 160)
        }
    }

    override fun onDestroyView() {
        view?.removeCallbacks(selectionScrollIdleRunnable)
        selectionScrolling = false
        super.onDestroyView()
    }

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
        val technicalScore: Int = 0,
        val weeklyTrendLabel: String = "数据不足",
        val monthlyTrendLabel: String = "数据不足",
    )

    private data class BoardMetric(
        val name: String,
        val change: Double,
        val volume: Double,
        val code: String? = null,
        val changeRankPct: Double? = null,
        val turnoverRankPct: Double? = null,
    )

    private data class SelectionRankingContext(
        val observationByCode: Map<String, com.example.stockmonitor.core.ObservationCandidate>,
        val limitByCode: Map<String, LimitStock>,
        val turnoverRankByCode: Map<String, Double>,
        val industryBoards: List<BoardMetric>,
        val conceptBoards: List<BoardMetric>,
    )

    private fun selectionRankingContext(): SelectionRankingContext = SelectionRankingContext(
        observationByCode = ObservationEligibility
            .deduplicateCandidates(Repo.observationPool)
            .associateBy { it.code },
        limitByCode = Repo.limits?.up.orEmpty().associateBy { it.code },
        turnoverRankByCode = rankPercentilesByCode(Repo.quotes.map { it.key to it.value.turnover }),
        industryBoards = withBoardRanks(
            Repo.sectors?.all.orEmpty().map {
                BoardMetric(name = it.name, change = it.change, volume = it.volume)
            },
        ),
        conceptBoards = withBoardRanks(
            Repo.conceptSummary?.sectors.orEmpty().map {
                BoardMetric(name = it.name, change = it.change, volume = it.volume, code = it.code)
            },
        ),
    )

    private fun strongSectorRanking(
        rankingContext: SelectionRankingContext,
        code: String,
        stockChangePct: Double,
    ): StrongSectorRankingPolicy.Result {
        strongRankingCache[code]?.let { return it }
        val observationCandidate = rankingContext.observationByCode[code]
        val boardNames = buildSet {
            Repo.sectorContexts[code]?.industry?.name?.let(::add)
            observationCandidate?.boards.orEmpty().forEach(::add)
            rankingContext.limitByCode[code]?.boards.orEmpty().forEach(::add)
        }
        val stockChange = stockChangePct.takeIf { it.isFinite() } ?: Repo.quotes[code]?.changePct
        val stockTurnoverRankPct = rankingContext.turnoverRankByCode[code]
        val limitStock = rankingContext.limitByCode[code]
        val limitStreak = limitStock?.limitStreak ?: observationCandidate?.limitStreak
        val sealQualityScore = limitStock?.sealQualityScore ?: observationCandidate?.sealQualityScore
        val sealQualityCoveragePct =
            limitStock?.sealQualityCoveragePct ?: observationCandidate?.sealQualityCoveragePct
        val industryBoards = rankingContext.industryBoards
        val conceptBoards = rankingContext.conceptBoards
        val best = boardNames.mapNotNull { boardName ->
            val industryItem = industryBoards.firstOrNull {
                StrongSectorRankingPolicy.boardNamesMatch(it.name, boardName)
            }
            val conceptItem = conceptBoards.firstOrNull {
                StrongSectorRankingPolicy.boardNamesMatch(it.name, boardName)
            }
            val item = industryItem ?: conceptItem ?: return@mapNotNull null
            val boardCode = Repo.sectorContexts[code]?.industry?.boardCode?.takeIf {
                StrongSectorRankingPolicy.boardNamesMatch(
                    Repo.sectorContexts[code]?.industry?.name.orEmpty(),
                    boardName,
                )
            } ?: conceptItem?.code
            val kind = if (industryItem != null) {
                com.example.stockmonitor.analysis.BoardKind.INDUSTRY
            } else {
                com.example.stockmonitor.analysis.BoardKind.CONCEPT
            }
            val currentPoint = com.example.stockmonitor.analysis.SectorStrengthPoint(
                kind = kind,
                date = Repo.quotes[code]?.quoteDate?.take(10)
                    ?: com.example.stockmonitor.analysis.TimeUtils.todayText(),
                name = item.name,
                code = boardCode,
                changePct = item.change,
                changeRankPct = item.changeRankPct,
                turnoverRankPct = item.turnoverRankPct,
                mainNetRatioPct = boardCode?.let { Repo.sectorFlows[it]?.mainNetRatio },
            )
            val mainline = com.example.stockmonitor.analysis.MainlineStrengthPolicy.assess(
                history = Repo.sectorStrengthHistory,
                current = currentPoint,
            )
            val result = StrongSectorRankingPolicy.score(
                sectorChangePct = item.change,
                sectorChangeRankPct = item.changeRankPct,
                sectorMainNetRatioPct = boardCode?.let { Repo.sectorFlows[it]?.mainNetRatio },
                sectorTurnoverRankPct = item.turnoverRankPct,
                stockTurnoverRankPct = stockTurnoverRankPct,
                stockChangePct = stockChange,
                limitStreak = limitStreak,
                sealQualityScore = sealQualityScore,
                sealQualityCoveragePct = sealQualityCoveragePct,
                mainline = mainline,
            )
            result.copy(boardName = boardName)
        }.maxByOrNull { it.total } ?: StrongSectorRankingPolicy.score(
            sectorChangePct = null,
            sectorChangeRankPct = null,
            sectorMainNetRatioPct = null,
            sectorTurnoverRankPct = null,
            stockTurnoverRankPct = stockTurnoverRankPct,
            stockChangePct = stockChange,
            limitStreak = limitStreak,
            sealQualityScore = sealQualityScore,
            sealQualityCoveragePct = sealQualityCoveragePct,
            mainline = com.example.stockmonitor.analysis.MainlineStrengthPolicy.assess(
                history = Repo.sectorStrengthHistory,
                current = com.example.stockmonitor.analysis.SectorStrengthPoint(
                    kind = com.example.stockmonitor.analysis.BoardKind.INDUSTRY,
                    date = Repo.quotes[code]?.quoteDate?.take(10)
                        ?: com.example.stockmonitor.analysis.TimeUtils.todayText(),
                    name = boardNames.firstOrNull().orEmpty(),
                    changePct = stockChange ?: Double.NaN,
                    changeRankPct = null,
                    turnoverRankPct = null,
                ),
            ),
        )
        return best.also { strongRankingCache[code] = it }
    }

    private fun withBoardRanks(items: List<BoardMetric>): List<BoardMetric> {
        val sortedChanges = sortedFiniteValues(items.map { it.change })
        val sortedTurnover = sortedFiniteValues(items.map { it.volume })
        return items.map {
            it.copy(
                changeRankPct = rankPercentile(sortedChanges, it.change),
                turnoverRankPct = rankPercentile(sortedTurnover, it.volume),
            )
        }
    }

    private fun rankPercentilesByCode(items: List<Pair<String, Double>>): Map<String, Double> {
        val sorted = sortedFiniteValues(items.map { it.second })
        return items.mapNotNull { (code, value) ->
            rankPercentile(sorted, value)?.let { code to it }
        }.toMap()
    }

    private fun sortedFiniteValues(values: List<Double>): List<Double> =
        values.filter { it.isFinite() }.sorted()

    private fun rankPercentile(sortedValues: List<Double>, current: Double): Double? {
        if (!current.isFinite() || sortedValues.size < 2) return null
        var low = 0
        var high = sortedValues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (sortedValues[middle] <= current) low = middle + 1 else high = middle
        }
        return (sortedValues.size - low).toDouble() / (sortedValues.size - 1)
    }

    private fun UiKit.Card.addExpandableSelectionSection(
        context: Context,
        rankingContext: SelectionRankingContext,
        title: String,
        items: List<SelectionRowData>,
        pageKey: String,
        titleColor: Int? = null,
        emptyText: String = "暂无符合条件的股票。",
    ) {
        val rankedItems = StrongSectorRankingPolicy.rankByCombined(
            items,
            scoreOf = { strongSectorRanking(rankingContext, it.code, it.changePct) },
            technicalScoreOf = { it.technicalScore },
        )
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
        val lastPage = (rankedItems.size - 1) / pageSize
        val pages = context.getSharedPreferences("screen_list_pages", Context.MODE_PRIVATE)
        val pageContent = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        add(pageContent)
        var currentPage = pages.getInt(pageKey, 0).coerceIn(0, lastPage)
        var rowKeys = emptyList<String>()
        var rows = emptyList<UiKit.ExpandableQuoteRowView>()
        fun updateToggle() {
            toggle.text = when {
                QuoteRowExpansionState.allExpanded(rowKeys) && lastPage > 0 -> "本页收起 ▴"
                QuoteRowExpansionState.allExpanded(rowKeys) -> "全部收起 ▴"
                lastPage > 0 -> "本页展开 ▾"
                else -> "全部展开 ▾"
            }
        }
        fun showPage(targetPage: Int, scrollToFirstItem: Boolean = false) {
            currentPage = targetPage.coerceIn(0, lastPage)
            pages.edit().putInt(pageKey, currentPage).apply()
            pageContent.removeAllViews()
            val visibleItems = rankedItems.drop(currentPage * pageSize).take(pageSize)
            rowKeys = visibleItems.map { "selection:${it.item.key}" }
            val pageRows = mutableListOf<UiKit.ExpandableQuoteRowView>()
            visibleItems.forEach { ranked ->
                val item = ranked.item
                val ranking = ranked.result
                val markerParts = buildList {
                    val sustainedMainline = ranking.labels.any { it.startsWith("主线板块") }
                    if (sustainedMainline) {
                        add("🔥 持续主线：${ranking.boardName ?: "已识别"}")
                    } else if (ranking.mainlineDiverging) {
                        add("🟡 主线分歧：${ranking.boardName ?: "已识别"}（暂保留1个交易日）")
                    } else if (ranking.sectorScore > 0) {
                        add("⚡ 单日强势：${ranking.boardName ?: "已识别"}（未形成主线）")
                    }
                    if ("容量中军" in ranking.labels) add("容量中军")
                    if ("情绪核心" in ranking.labels) add("情绪核心")
                }
                val markerText = markerParts.joinToString("｜")
                val breakdown = StrongSectorRankingPolicy.scoreBreakdown(item.technicalScore, ranking)
                val overallScore = breakdown.total
                val scoreLine = "综合质量分 $overallScore/100"
                val rankedSummary = SpannableStringBuilder()
                    .append(scoreLine)
                    .append("\n")
                    .apply {
                        if (markerText.isNotBlank()) append("$markerText\n")
                    }
                    .append(item.summary)
                val scoreDetails = listOf(
                    UiKit.DetailRow("综合质量分", "$overallScore/100"),
                    UiKit.DetailRow("评分构成",
                        "技术 ${breakdown.technicalPoints}/35（原始${item.technicalScore}/100）｜板块 ${breakdown.sectorPoints}/35｜" +
                            "核心角色 ${breakdown.rolePoints}/20｜数据可信度 ${breakdown.confidencePoints}/10",
                    ),
                    UiKit.DetailRow("技术趋势", "周K${item.weeklyTrendLabel}｜月K${item.monthlyTrendLabel}"),
                )
                val mainlineDetails = if (ranking.mainlineWindowDays > 0) {
                    val mainlineStatus = when {
                        ranking.mainlineDiverging -> "｜主线正常分歧，暂保留1个交易日"
                        ranking.labels.any { it.startsWith("主线板块") } -> "｜已形成持续主线"
                        else -> "｜未形成持续主线"
                    }
                    listOf(
                        UiKit.DetailRow("主线持续", "最近${ranking.mainlineWindowDays}个记录日强势${ranking.mainlineStrongDays}日$mainlineStatus"),
                    )
                } else {
                    emptyList()
                }
                val rankedDetails = if (markerParts.isEmpty()) {
                    item.details + scoreDetails + mainlineDetails
                } else {
                    item.details + scoreDetails + mainlineDetails + UiKit.DetailRow("板块标记", markerText)
                }
                val row = UiKit.expandableQuoteRow(
                    context = context,
                    stateKey = "selection:${item.key}",
                    name = item.name,
                    code = item.code,
                    price = item.price,
                    changePct = item.changePct,
                    summary = rankedSummary,
                    details = rankedDetails,
                    onExpansionChanged = { updateToggle() },
                )
                pageRows.add(row)
                pageContent.addView(row.view)
            }
            rows = pageRows
            updateToggle()
            if (lastPage > 0) {
                pageContent.addView(
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
                                    showPage(target, scrollToFirstItem = true)
                                }
                            }
                        addView(pageButton(android.R.drawable.ic_media_previous, "上一页", currentPage - 1))
                        addView(
                            UiKit.body(
                                context,
                                "${currentPage + 1}/${lastPage + 1} 页 · 共${items.size}只",
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
                        addView(pageButton(android.R.drawable.ic_media_next, "下一页", currentPage + 1))
                    },
                )
            }
            if (scrollToFirstItem) {
                pageContent.post {
                    pageContent.requestRectangleOnScreen(
                        android.graphics.Rect(
                            0,
                            0,
                            pageContent.width,
                            1,
                        ),
                        true,
                    )
                }
            }
        }
        toggle.setOnClickListener {
            val expanded = QuoteRowExpansionState.toggleAll(rowKeys)
            rows.forEach { it.setExpanded(expanded) }
            updateToggle()
        }
        showPage(currentPage)
    }

    private fun limitFilterSnapshot(
        rankingContext: SelectionRankingContext,
        code: String,
        name: String,
    ): LimitUpFilterPolicy.Stock {
        val limit = rankingContext.limitByCode[code]
        return LimitUpFilterPolicy.Stock(
            code = code,
            name = limit?.name ?: name,
            isLimitUp = limit != null,
        )
    }

    private fun filterLimitUpRows(
        rows: List<SelectionRowData>,
        option: LimitUpFilterPolicy.Option,
        rankingContext: SelectionRankingContext,
    ): List<SelectionRowData> = LimitUpFilterPolicy.filterBy(rows, option) {
        limitFilterSnapshot(rankingContext, it.code, it.name)
    }

    private fun <T> filterLimitUpItems(
        items: List<T>,
        option: LimitUpFilterPolicy.Option,
        rankingContext: SelectionRankingContext,
        codeOf: (T) -> String,
        nameOf: (T) -> String,
    ): List<T> = LimitUpFilterPolicy.filterBy(items, option) {
        limitFilterSnapshot(rankingContext, codeOf(it), nameOf(it))
    }

    private fun currentLimitFilter(context: Context): LimitUpFilterPolicy.Option {
        val saved = context.getSharedPreferences("screen_limit_filter", Context.MODE_PRIVATE)
            .getString("value", LimitUpFilterPolicy.Option.ALL.name)
        return LimitUpFilterPolicy.Option.values().firstOrNull { it.name == saved }
            ?: LimitUpFilterPolicy.Option.ALL
    }

    private fun addLimitFilter(
        context: Context,
        selected: LimitUpFilterPolicy.Option,
    ) {
        val preferences = context.getSharedPreferences("screen_limit_filter", Context.MODE_PRIVATE)
        val group = MaterialButtonToggleGroup(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = UiKit.dp(context, 6)
            }
        }
        val buttonIds = mutableMapOf<LimitUpFilterPolicy.Option, Int>()
        LimitUpFilterPolicy.Option.values().zip(
            listOf("全部", "10cm", "20cm", "30cm"),
        ).forEach { (option, filterLabel) ->
            val button = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                id = android.view.View.generateViewId()
                text = filterLabel
                contentDescription = option.label
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            }
            buttonIds[option] = button.id
            group.addView(button)
        }
        group.check(buttonIds.getValue(selected))
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val option = buttonIds.entries.firstOrNull { it.value == checkedId }?.key ?: return@addOnButtonCheckedListener
            if (option == selected) return@addOnButtonCheckedListener
            preferences.edit().putString("value", option.name).apply()
            context.getSharedPreferences("screen_list_pages", Context.MODE_PRIVATE).edit().clear().apply()
            render()
        }
        content?.addView(group)
    }

    private fun renderNow(): Unit = rebuild { container ->
        strongRankingCache.clear()
        val context = requireContext()
        val result = Repo.screenResult
        val rankingContext = selectionRankingContext()
        val limitFilter = currentLimitFilter(context)
        addLimitFilter(context, limitFilter)

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
            val sections = ObservationEligibility.sections(Repo.observationPool, Repo.quotes)
            val active = filterLimitUpItems(
                items = sections.values.flatten(),
                option = limitFilter,
                rankingContext = rankingContext,
                codeOf = { it.code },
                nameOf = { it.name },
            )
            val preBreakout = active.filter { it.strategy == ObservationStrategy.PRE_BREAKOUT }
            val leaders = active.filter { it.strategy == ObservationStrategy.LEADER }
            val limitContinuations = active.filter { it.strategy == ObservationStrategy.LIMIT_UP_CONTINUATION }
            fun isLimitUpDeferred(item: com.example.stockmonitor.core.ObservationCandidate): Boolean =
                ObservationEligibility.isLimitUpDeferred(item, Repo.quotes[item.code])

            val prioritizedRaw = ObservationEligibility.prioritizedSections(
                candidates = Repo.observationPool,
                quotes = Repo.quotes,
            ) { item ->
                strongSectorRanking(
                    rankingContext,
                    item.code,
                    Repo.quotes[item.code]?.changePct ?: Double.NaN,
                ).strongWatch
            }
            val prioritized = prioritizedRaw.copy(
                buy = filterLimitUpItems(prioritizedRaw.buy, limitFilter, rankingContext, { it.code }, { it.name }),
                strong = filterLimitUpItems(prioritizedRaw.strong, limitFilter, rankingContext, { it.code }, { it.name }),
                waiting = filterLimitUpItems(prioritizedRaw.waiting, limitFilter, rankingContext, { it.code }, { it.name }),
                other = filterLimitUpItems(prioritizedRaw.other, limitFilter, rankingContext, { it.code }, { it.name }),
            )
            val buyItems = prioritized.buy
            val strongWaitItems = prioritized.strong
            val nearItems = prioritized.waiting
            val otherWaitItems = prioritized.other
            val buyCandidates = buyItems.size
            val nearEntry = nearItems.size
            val highestBuy = active.count {
                (it.highestStage ?: it.stage) == ObservationStage.BUY_CANDIDATE
            }
            val highestNear = active.count {
                (it.highestStage ?: it.stage) == ObservationStage.NEAR_ENTRY
            }
            addKeyValue(
                "状态总览",
                "可参与候选 ${buyCandidates}只｜强势观察 ${strongWaitItems.size}只｜等待确认 ${nearEntry}只｜其他观察 ${otherWaitItems.size}只",
            )
            addKeyValue("强弱顺序", "由强到弱")
            addText("1️⃣ ✅ 可参与候选（买点已确认）")
            addText("2️⃣ 🔥 强势观察（强而未确认）")
            addText("3️⃣ 🕐 等待确认（条件未齐）")
            addText("4️⃣ ⏸ 其他观察（偏弱/风险）")
            val observationDetails = buildString {
                appendLine("历史最高")
                appendLine("买点已确认 ${highestBuy}只｜买点待确认 ${highestNear}只｜个股详情保留首次达到时间与状态变化")
                appendLine()
                appendLine("策略路线")
                appendLine("${active.size}只｜涨停持续观察 ${limitContinuations.size}只｜强势龙头 ${leaders.size}只｜启动前潜力 ${preBreakout.size}只")
                appendLine()
                appendLine("晋级规则")
                appendLine("启动前潜力：趋势/风险门槛通过｜强势龙头：连续2轮复评｜涨停持续：封板只看、开板确认（3板3轮）｜3日走弱/10日未确认淘汰")
                appendLine()
                appendLine("排序标记")
                appendLine("强势板块决定强势观察档位；容量中军、情绪核心提高综合质量分。")
                appendLine()
                appendLine("验证数据")
                appendLine("${Repo.observationSnapshots.size}条快照｜纸上交易${Repo.paperTrades.size}笔（未平${Repo.paperTrades.count { it.isOpen }}笔）")
                appendLine()
                append(ScreenHelpCopy.observationDetailText())
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
                    text = "查看观察池详情 ›"
                    textSize = 12f
                    setTextColor(UiKit.color(context, R.color.brand_primary))
                    setPadding(0, UiKit.dp(context, 2), 0, UiKit.dp(context, 6))
                    setOnClickListener {
                        Dialogs.showDetails(
                            requireActivity() as androidx.appcompat.app.AppCompatActivity,
                            "观察池详情",
                            observationDetails,
                        )
                    }
                }
            )
            fun observationRow(item: com.example.stockmonitor.core.ObservationCandidate): SelectionRowData {
                val quote = Repo.quotes[item.code]
                val limitUpDeferred = isLimitUpDeferred(item)
                val stockStrategies = Repo.observationPool
                    .filter { it.code == item.code }
                    .map { it.strategy }
                    .toSet()
                val routeLabel = ObservationStrategy.entries
                    .filter { it in stockStrategies }
                    .joinToString("＋") { it.label }
                val statusLabel = when {
                    item.limitUpShrinkAcceleration ->
                        "⚠ 缩量加速风险"
                    limitUpDeferred && item.continuousObservation ->
                        "⏸ 涨停封板持续观察"
                    limitUpDeferred -> "⏸ 封板后待重新确认"
                    else -> item.stage.label
                }
                val summaryLine = "$statusLabel｜$routeLabel｜已观察${item.observedDays}天"
                val currentFlow = Repo.fundFlowHistory[item.code]?.lastOrNull()
                val flowHistory = Repo.fundFlowHistory[item.code].orEmpty().filter {
                    (it.tradingDate ?: "") >= item.addedDate
                }
                val flowWindow = com.example.stockmonitor.analysis.FundFlowWindowPolicy.assess(flowHistory)
                val flowSummary =
                    listOf(
                        com.example.stockmonitor.analysis.LimitReport.fundFlowSummary(currentFlow),
                        flowWindow.summary,
                    ).filter { it.isNotBlank() }.joinToString("｜")
                val summary = SpannableStringBuilder(summaryLine).apply {
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
                    append("下一步：${item.reason.ifBlank { "继续观察" }}")
                    if (flowSummary.isNotBlank()) {
                        append("\n资金流：$flowSummary")
                    }
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
                    technicalScore = item.technicalScore
                        ?: (item.score.coerceIn(0, 3) * 40 / 3),
                    weeklyTrendLabel = item.weeklyTrendLabel ?: "数据不足",
                    monthlyTrendLabel = item.monthlyTrendLabel ?: "数据不足",
                    details = listOf(
                        UiKit.DetailRow("状态", statusLabel),
                        UiKit.DetailRow(
                            "历史最高",
                            (item.highestStage ?: item.stage).label,
                        ),
                        UiKit.DetailRow(
                            "首次达到时间",
                            item.highestStageDate ?: item.lastEvaluatedDate ?: item.addedDate,
                        ),
                        UiKit.DetailRow("策略路线", routeLabel),
                        UiKit.DetailRow("下一步", item.reason.ifBlank { "继续观察" }),
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
                        UiKit.DetailRow("观察天数", "${item.observedDays}天"),
                        UiKit.DetailRow("最近复评", item.lastEvaluatedDate ?: "尚未复评"),
                        UiKit.DetailRow(
                            "状态变化",
                            item.statusChanges.takeLast(3).joinToString("；") { change ->
                                val previous = change.previousStage?.let { "←${it.label}" }.orEmpty()
                                "${change.date} ${change.stage.label}$previous"
                            }.ifBlank { "暂无记录" },
                        ),
                        UiKit.DetailRow("止损价", item.stopPrice?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "—"),
                        UiKit.DetailRow("当前资金流", flowSummary.ifBlank { "暂无" }),
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
            fun addStatusGroup(
                title: String,
                description: String,
                items: List<com.example.stockmonitor.core.ObservationCandidate>,
                pageKey: String,
                titleColor: Int,
            ) {
                addDivider()
                addText(description, 11.5f, UiKit.color(context, R.color.text_secondary))
                addExpandableSelectionSection(
                    context = context,
                    rankingContext = rankingContext,
                    title = title,
                    items = items.map(::observationRow),
                    pageKey = pageKey,
                    titleColor = UiKit.color(context, titleColor),
                )
            }
            addStatusGroup(
                title = "✅ 可参与候选（${buyItems.size}只）",
                description = "强度最高：已经确认买点；仍需核对仓位、止损和A股交易约束。",
                items = buyItems,
                pageKey = "observation:status:buy",
                titleColor = R.color.risk_good,
            )
            addStatusGroup(
                title = "🔥 强势观察（${strongWaitItems.size}只）",
                description = "强度次高：强势板块或核心角色，但尚未确认买点；封板仍只看不买。",
                items = strongWaitItems,
                pageKey = "observation:status:strong-wait",
                titleColor = R.color.warn,
            )
            addStatusGroup(
                title = "🕐 等待确认（${nearItems.size}只）",
                description = "强度一般：还差趋势、回踩或连续复评确认；先看每只股票的“下一步”。",
                items = nearItems,
                pageKey = "observation:status:near",
                titleColor = R.color.risk_watch,
            )
            addStatusGroup(
                title = "⏸ 其他暂缓 / 封板 / 观察（${otherWaitItems.size}只）",
                description = "强度偏弱或有风险：封板、缩量加速或尚未形成入场结构；本组不是直接买入列表。",
                items = otherWaitItems,
                pageKey = "observation:status:wait",
                titleColor = R.color.text_secondary,
            )
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
            val strictRows = filterLimitUpRows(
                rows = result.strict.map { hit ->
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
                        technicalScore = hit.technicalScore,
                        weeklyTrendLabel = hit.weeklyTrendLabel,
                        monthlyTrendLabel = hit.monthlyTrendLabel,
                    )
                },
                option = limitFilter,
                rankingContext = rankingContext,
            )
            addExpandableSelectionSection(
                context = context,
                rankingContext = rankingContext,
                title = "✅ 严格策略（${strictRows.size}只）",
                items = strictRows,
                pageKey = "indicator:strict",
                emptyText = "暂无符合严格条件的股票。",
            )
            addDivider()
            val relaxedRows = filterLimitUpRows(
                rows = result.relaxed.map { hit ->
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
                        technicalScore = hit.technicalScore,
                        weeklyTrendLabel = hit.weeklyTrendLabel,
                        monthlyTrendLabel = hit.monthlyTrendLabel,
                    )
                },
                option = limitFilter,
                rankingContext = rankingContext,
            )
            addExpandableSelectionSection(
                context = context,
                rankingContext = rankingContext,
                title = "⚠️ 宽松预警（${relaxedRows.size}只）",
                items = relaxedRows,
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
            val deep513Rows = filterLimitUpRows(
                rows = result.fiveOneThree.map { hit ->
                    SelectionRowData(
                        key = "deep-513:${hit.code}",
                        name = hit.name,
                        code = hit.code,
                        price = String.format(Locale.CHINA, "%.2f", hit.price),
                        changePct = Double.NaN,
                        summary = fiveOneThreeNote(context, hit),
                        details = fiveOneThreeDetails(hit),
                        technicalScore = 40,
                    )
                },
                option = limitFilter,
                rankingContext = rankingContext,
            )
            addExpandableSelectionSection(
                context = context,
                rankingContext = rankingContext,
                title = "🧭 513低位形态（${deep513Rows.size}只）",
                items = deep513Rows,
                pageKey = "deep:513",
                emptyText = "暂无符合 513 形态阶段的股票。",
            )
            addDivider()
            val trendRows = filterLimitUpRows(
                rows = result.trend.map { hit ->
                    SelectionRowData(
                        key = "deep-trend:${hit.code}",
                        name = trendIcon(hit),
                        code = hit.code,
                        price = String.format(Locale.CHINA, "%.2f", hit.price),
                        changePct = hit.changePct,
                        summary = trendNote(context, hit),
                        details = trendDetails(hit),
                        technicalScore = hit.technicalScore,
                        weeklyTrendLabel = hit.weeklyTrendLabel,
                        monthlyTrendLabel = hit.monthlyTrendLabel,
                    )
                },
                option = limitFilter,
                rankingContext = rankingContext,
            )
            addExpandableSelectionSection(
                context = context,
                rankingContext = rankingContext,
                title = "📐 趋势精选（${trendRows.size}只）",
                items = trendRows,
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
    override fun render() {
        if (selectionScrolling) return markRenderDirty()
        if (renderPending) return
        val target = view
        if (target == null) {
            renderNow()
            return
        }
        renderPending = true
        view?.post {
            renderPending = false
            if (!selectionScrolling && isAdded && view?.isShown == true) renderNow() else markRenderDirty()
        }
    }

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
        UiKit.DetailRow("技术质量分", "${hit.technicalScore}/100"),
        UiKit.DetailRow("周/月趋势", "周K${hit.weeklyTrendLabel}｜月K${hit.monthlyTrendLabel}"),
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
        UiKit.DetailRow("技术质量分", "${hit.technicalScore}/100"),
        UiKit.DetailRow("周/月趋势", "周K${hit.weeklyTrendLabel}｜月K${hit.monthlyTrendLabel}"),
    )

    private fun Double.formatPct(): String = String.format(Locale.CHINA, "%+.1f%%", this)
}
