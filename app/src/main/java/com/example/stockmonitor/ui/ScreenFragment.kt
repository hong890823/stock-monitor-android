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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            fun isLimitUpDeferred(item: com.example.stockmonitor.core.ObservationCandidate): Boolean =
                ObservationEligibility.isLimitUpDeferred(item, Repo.quotes[item.code])

            val buyCandidates = sections.getValue(ObservationSection.BUY).size
            val nearEntry = sections.getValue(ObservationSection.NEAR).size
            addKeyValue("当前展示", "${active.size}只｜龙头 ${leaders.size}只｜启动前 ${preBreakout.size}只")
            addKeyValue(
                "阶段统计",
                "${ObservationStage.BUY_CANDIDATE.label} ${buyCandidates}只\n${ObservationStage.NEAR_ENTRY.label} ${nearEntry}只",
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
            addText(
                "全池盘中复评；连续3个交易日走弱或观察10个交易日未确认则淘汰。龙头候选需回踩企稳及连续复评通过。",
                12f,
            )
            if (buyCandidates > 0) {
                addText(
                    "${ObservationStage.BUY_CANDIDATE.label} ${buyCandidates}只；封板及缩量加速对象暂缓参与。",
                    12f,
                    UiKit.color(context, R.color.risk_good),
                )
            }
            add(
                android.widget.TextView(context).apply {
                    text = "补录当前结果到观察池  ›"
                    textSize = 12f
                    setTextColor(UiKit.color(context, R.color.text_secondary))
                    setPadding(0, UiKit.dp(context, 6), 0, UiKit.dp(context, 4))
                    setOnClickListener {
                        MonitorService.send(requireContext(), MonitorService.ACTION_OBSERVE_SCREEN)
                    }
                }
            )
            addText("扫描结果会自动进入观察池；只有要补录当前页面结果时才点击。", 11.5f)
            fun addObservationRow(item: com.example.stockmonitor.core.ObservationCandidate) {
                val quote = Repo.quotes[item.code]
                val limitUpDeferred = isLimitUpDeferred(item)
                val stageText = when {
                    item.limitUpShrinkAcceleration ->
                        "⚠ 缩量加速风险｜已降级观察｜已观察${item.observedDays}天"
                    limitUpDeferred -> "⏸ 封板后待重新确认｜已观察${item.observedDays}天"
                    item.stage == ObservationStage.BUY_CANDIDATE ->
                        "${ObservationStage.BUY_CANDIDATE.label}｜已观察${item.observedDays}天"
                    else ->
                        "${item.stage.label}｜${item.score}/3分｜已观察${item.observedDays}天"
                }
                val detail = buildString {
                    append(stageText)
                    append("｜${item.reason}")
                    quote?.let { append(String.format(Locale.CHINA, "｜现价 %.2f %+.2f%%", it.price, it.changePct)) }
                    item.stopPrice?.let { append(String.format(Locale.CHINA, "｜止损 %.2f", it)) }
                    append("\n最近复评：${item.lastEvaluatedDate ?: "尚未复评"}")
                    append("\n支持证据：${if (limitUpDeferred) "当前暂缓参与" else item.evidence.supporting.joinToString("；").ifBlank { "暂无" }}")
                    append("\n风险：${item.evidence.counter.joinToString("；").ifBlank { "暂无记录，不代表无风险" }}")
                    append("\n缺口：${item.evidence.missing.joinToString("；").ifBlank { "暂无记录" }}")
                    append("\n失效条件：${item.evidence.invalidators.joinToString("；").ifBlank { "需持续复评" }}")
                }
                val shortNote = "$stageText\n${item.reason}"
                add(
                    UiKit.quoteRow(
                        context,
                        item.name,
                        item.code,
                        String.format(Locale.CHINA, "%.2f", quote?.price ?: item.lastPrice),
                        quote?.changePct ?: Double.NaN,
                        note = shortNote,
                    ).apply {
                        val subtitle = (this as android.view.ViewGroup).getChildAt(childCount - 1) as android.widget.TextView
                        subtitle.maxLines = 2
                        subtitle.ellipsize = android.text.TextUtils.TruncateAt.END
                        if (item.stage == ObservationStage.BUY_CANDIDATE && !limitUpDeferred) {
                            subtitle.setTextColor(UiKit.color(context, R.color.risk_good))
                        }
                        isFocusable = true
                        contentDescription = "${item.name} ${item.code}，$shortNote，查看详情"
                        setOnClickListener {
                            MaterialAlertDialogBuilder(context)
                                .setTitle("${item.name} · ${item.code}")
                                .setMessage(detail)
                                .setPositiveButton("关闭", null)
                                .show()
                        }
                    }
                )
            }
            fun addStrategyGroup(
                strategy: ObservationStrategy,
                items: List<com.example.stockmonitor.core.ObservationCandidate>,
            ) {
                addDivider()
                addText("${strategy.label}（${items.size}只）", 16f)
                if (items.isEmpty()) {
                    addText("暂无买点已确认或买点待确认的股票。", 12f)
                    return
                }
                val pages = context.getSharedPreferences("observation_pages", Context.MODE_PRIVATE)
                ObservationEligibility.entrySections(items, Repo.quotes).forEach { (section, candidates) ->
                    if (candidates.isEmpty()) return@forEach
                    val color = when (section) {
                        ObservationSection.BUY -> R.color.risk_good
                        ObservationSection.NEAR, ObservationSection.DEFERRED -> R.color.risk_watch
                        ObservationSection.OBSERVING -> R.color.text_secondary
                    }
                    addText("${section.label}（${candidates.size}只）", 13f, UiKit.color(context, color))
                    val key = "${strategy.name}:${section.name}"
                    val lastPage = (candidates.size - 1) / ObservationEligibility.PAGE_SIZE
                    val page = pages.getInt(key, 0).coerceIn(0, lastPage)
                    ObservationEligibility.page(candidates, page).forEach(::addObservationRow)
                    if (lastPage > 0) {
                        add(android.widget.LinearLayout(context).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            fun pageButton(icon: Int, label: String, target: Int) = android.widget.ImageButton(context).apply {
                                setImageResource(icon)
                                contentDescription = label
                                tooltipText = label
                                background = null
                                imageTintList = android.content.res.ColorStateList.valueOf(UiKit.color(context, R.color.text_secondary))
                                isEnabled = target in 0..lastPage
                                alpha = if (isEnabled) 1f else 0.3f
                                layoutParams = android.widget.LinearLayout.LayoutParams(UiKit.dp(context, 48), UiKit.dp(context, 48))
                                setOnClickListener {
                                    pages.edit().putInt(key, target).apply()
                                    render()
                                }
                            }
                            addView(pageButton(android.R.drawable.ic_media_previous, "上一页", page - 1))
                            addView(UiKit.body(context, "${page + 1}/${lastPage + 1} 页 · 共${candidates.size}只", 12f).apply {
                                gravity = android.view.Gravity.CENTER
                                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            })
                            addView(pageButton(android.R.drawable.ic_media_next, "下一页", page + 1))
                        })
                    }
                }
            }
            addStrategyGroup(ObservationStrategy.LEADER, leaders)
            addStrategyGroup(ObservationStrategy.PRE_BREAKOUT, preBreakout)
            container.addView(view)
        }

        screenCard(context, "indicator_scan", "📊 指标两档 · 快扫").apply {
            addKeyValue("池子", "成交量前200只 / 近120日日线")
            addKeyValue("耗时", "秒级")
            addKeyValue("上次刷新", Repo.screenIndicatorAt ?: "尚未扫描")
            addText(
                "严格策略：DIF/DEA 零轴上方金叉 + RSI < 30 + KDJ 低位金叉且 K < 30，三项同时满足。\n" +
                    "宽松预警：MACD 柱体转正 / 负区收窄+DIF↑ / 正区放大之一必须满足，" +
                    "再满足 RSI < 45 或 K>D 且 K < 50；排除M20明显破位，最多20只，仅作观察候选。",
                12f,
            )
            add(
                MaterialButton(context).apply {
                    text = "重扫指标"
                    setOnClickListener {
                        MonitorService.send(requireContext(), MonitorService.ACTION_SCREEN)
                    }
                }
            )
            addDivider()
            addText("✅ 严格策略（${result.strict.size}只）", 13f)
            if (result.strict.isEmpty()) {
                addText("暂无符合严格条件的股票。")
            }
            result.strict.take(100).forEach { hit ->
                add(
                    UiKit.quoteRow(
                        context,
                        hit.name,
                        hit.code,
                        String.format(Locale.CHINA, "%.2f", hit.price),
                        hit.changePct,
                        note = screenNote(
                            context,
                            hit,
                            String.format(
                                Locale.CHINA,
                                "%s｜RSI %.0f｜K %.0f > D %.0f｜DIF %.3f",
                                hit.code, hit.rsi, hit.k, hit.d, hit.dif,
                            ),
                        ),
                    )
                )
            }
            if (result.strict.size > 100) addText("命中较多，仅展示排序靠前的 100 只。", 11.5f)
            addDivider()
            addText("⚠️ 宽松预警（${result.relaxed.size}只）", 13f)
            if (result.relaxed.isEmpty()) {
                addText("暂无符合宽松预警的股票。")
            }
            result.relaxed.forEach { hit ->
                val tags = mutableListOf<String>()
                if (hit.macdDetail.isNotBlank()) tags.add("MACD:${hit.macdDetail}")
                if (hit.rsiOk) tags.add("RSI")
                if (hit.kdjOk) tags.add("KDJ低位K>D")
                add(
                    UiKit.quoteRow(
                        context,
                        hit.name,
                        hit.code,
                        String.format(Locale.CHINA, "%.2f", hit.price),
                        hit.changePct,
                        note = screenNote(
                            context,
                            hit,
                            String.format(
                                Locale.CHINA,
                                "%s｜%d/3分｜%s",
                                hit.code, hit.relaxedScore, tags.joinToString(" | "),
                            ),
                        ),
                    )
                )
            }
            container.addView(view)
        }

        screenCard(context, "trend_scan", "🔭 513 与趋势 · 深扫").apply {
            addKeyValue("池子", "全部沪深A股 / 近120日日线")
            addKeyValue("耗时", "数分钟（数千根日K）")
            addKeyValue("上次刷新", Repo.screenPatternAt ?: "尚未扫描")
            add(
                MaterialButton(context).apply {
                    text = "深扫全市场"
                    setOnClickListener {
                        MonitorService.send(requireContext(), MonitorService.ACTION_SCREEN_FULL)
                    }
                }
            )
            addDivider()
            addText("🧭 513低位形态（${result.fiveOneThree.size}只）", 13f)
            addText(
                "按 5 连阳 → 倍量阳 → 3 天缩量的顺序扫描；只使用最近完整交易日，" +
                    "不把盘中未完成 K 线直接判为确认。确认超过 3 个交易日的形态不再列出。",
                12f,
            )
            addText(
                "本轮扫描：${result.fiveOneThreeStats.totalStocks}只｜有效日线：${result.fiveOneThreeStats.historyAvailable}只\n" +
                    "历史5连阳：${result.fiveOneThreeStats.historicalFiveBullish}只｜当前5连阳：${result.fiveOneThreeStats.recentFiveBullish}只\n" +
                    "倍量阳确认：${result.fiveOneThreeStats.waitingConfirmation}只｜完整513确认：${result.fiveOneThreeStats.confirmed}只",
                12f,
            )
            if (result.fiveOneThree.isEmpty()) {
                addText("暂无符合 513 形态阶段的股票。")
            }
            result.fiveOneThree.take(100).forEach { hit ->
                add(
                    UiKit.quoteRow(
                        context,
                        hit.name,
                        hit.code,
                        String.format(Locale.CHINA, "%.2f", hit.price),
                        Double.NaN,
                        note = fiveOneThreeNote(context, hit),
                    )
                )
            }
            if (result.fiveOneThree.size > 100) addText("命中较多，仅展示排序靠前的 100 只。", 11.5f)
            addText("该策略只表示量价形态符合条件，不代表主力建仓或必然上涨；请结合基本面、市场环境和止损纪律判断。", 11.5f)
            addDivider()
            addText("📐 趋势精选（${result.trend.size}只）", 13f)
            addText(
                "仅保留缩量回踩企稳或突破后次日守住的结构，最多20只；回踩确认优先。" +
                    "突破确认仅作观察，买点仍需持续观察池复核。",
                12f,
            )
            if (result.trend.isEmpty()) {
                addText("暂无通过趋势精选条件的股票。")
            }
            result.trend.forEach { hit ->
                add(
                    UiKit.quoteRow(
                        context,
                        trendIcon(hit),
                        hit.code,
                        String.format(Locale.CHINA, "%.2f", hit.price),
                        hit.changePct,
                        note = trendNote(context, hit),
                    )
                )
            }
            addText("趋势策略只提供候选和确认状态，不代表必然上涨；仍需结合基本面、仓位和止损。", 11.5f)
            container.addView(view)
        }

        screenCard(context, "help", "ℹ️ 说明").apply {
            addText("扫描使用腾讯前复权日线，与 macOS 版 akshare 的前复权口径一致；除权股的支撑/压力是复权价，和当年真实成交价不同。", 11.5f)
            addText("评分和信号只做条件式技术参考，不构成买卖建议。", 11.5f)
            addText(VolumePrice.helpText, 11.5f)
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
