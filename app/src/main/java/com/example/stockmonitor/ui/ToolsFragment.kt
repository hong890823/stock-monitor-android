package com.example.stockmonitor.ui

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.FundamentalResult
import com.example.stockmonitor.analysis.FundamentalHealthPolicy
import com.example.stockmonitor.analysis.Fundamentals
import com.example.stockmonitor.analysis.FundamentalsReport
import com.example.stockmonitor.analysis.GlobalFundamentalsReport
import com.example.stockmonitor.analysis.AiAnalysisReport
import com.example.stockmonitor.analysis.EnvContext
import com.example.stockmonitor.analysis.Governance
import com.example.stockmonitor.analysis.IntradaySignal
import com.example.stockmonitor.analysis.IntradaySignalType
import com.example.stockmonitor.analysis.IntradayTactics
import com.example.stockmonitor.analysis.HoldingTContext
import com.example.stockmonitor.analysis.HoldingTTrend
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.QuarterPoint
import com.example.stockmonitor.analysis.ReportMarkup
import com.example.stockmonitor.analysis.RiskLevel
import com.example.stockmonitor.analysis.StockAnalysis
import com.example.stockmonitor.analysis.SwingTrend
import com.example.stockmonitor.analysis.TradeNotification
import com.example.stockmonitor.analysis.TechnicalResult
import com.example.stockmonitor.analysis.TechnicalReference
import com.example.stockmonitor.analysis.TechnicalSummary
import com.example.stockmonitor.analysis.VolumePrice
import com.example.stockmonitor.analysis.TradingCalendar
import com.example.stockmonitor.analysis.TimeUtils
import com.example.stockmonitor.core.FundamentalsClient
import com.example.stockmonitor.core.AiAnalysisInput
import com.example.stockmonitor.core.AiAnalysisResult
import com.example.stockmonitor.core.AnalysisReportCache
import com.example.stockmonitor.core.BoardTagCache
import com.example.stockmonitor.core.DeepSeekClient
import com.example.stockmonitor.core.EastMoneyClient
import com.example.stockmonitor.core.GlobalFundamentals
import com.example.stockmonitor.core.GlobalFundamentalsClient
import com.example.stockmonitor.core.GlobalSecurity
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.RowSnapshot
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.core.SinaClient
import com.example.stockmonitor.monitor.MonitorService
import com.example.stockmonitor.monitor.Notifier
import com.example.stockmonitor.monitor.Repo
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 工具页：个股技术分析、自选股管理、盘面总结、监控设置。 */
class ToolsFragment : BaseFragment(R.layout.fragment_scroll) {

    private var lastKeyword: String = ""

    /** 已生成的基本面结果。工具页每 15 秒整体重建一次，结果要留在字段里才不会被打断。 */
    private var fundamental: FundamentalCard? = null
    private var fundamentalBusy = false
    private var globalKeyword: String = ""
    private var globalSuggestions: List<GlobalSecurity> = emptyList()
    private var globalSelected: GlobalSecurity? = null
    private var globalSearchJob: Job? = null
    private var globalInput: EditText? = null
    private var globalSuggestionBox: LinearLayout? = null
    private var globalInputProgrammatic = false
    private var global: GlobalCard? = null
    private var globalBusy = false
    private var technical: TechnicalCard? = null
    private var technicalBusy = false
    private var aiKeyword: String = ""
    private var aiBusy = false
    private var ai: AiCard? = null

    private data class FundamentalCard(
        val name: String,
        val code: String,
        val price: Double,
        val result: FundamentalResult,
        val technical: TechnicalResult?,
        val report: String,
    )

    private data class TechnicalCard(
        val result: TechnicalResult,
        val summary: TechnicalSummary,
        val report: String,
    )

    private data class GlobalCard(
        val data: GlobalFundamentals,
        val technical: TechnicalResult?,
        val report: String,
    )

    private data class AiCard(
        val name: String,
        val code: String,
        val market: String,
        val result: AiAnalysisResult,
        val report: String,
    )

    /** 「试一次买卖点提醒」用的固定通知 id，和盘中自动提醒的 id 区间分开。 */
    private val TEST_SIGNAL_ID = 9001

    override fun render(): Unit = rebuild { container ->
        val context = requireContext()
        val store = Store(context)

        // ── 个股技术分析 ──
        UiKit.card(context, "🔍 个股技术分析").apply {
            addText(
                "输入名称、拼音或代码，生成与技术分析脚本一致的报告：量价、均线、MACD/KDJ/RSI、ATR/布林带/ADX、筹码峰与条件式建议。",
                12f,
            )
            val input = EditText(context).apply {
                hint = "例如：特变电工 / sh600089"
                setSingleLine(true)
                UiKit.styleInput(this)
                setText(lastKeyword)
                setSelection(text.length)
                // 这个页面每 15 秒会随行情刷新整体重建一次，
                // 不把正在输入的内容记下来，打到一半就会被清空。
                doAfterTextChanged { lastKeyword = it?.toString().orEmpty() }
            }
            add(input)
            add(
                MaterialButton(context).apply {
                    text = if (technicalBusy) "正在分析…" else "生成分析报告"
                    setOnClickListener {
                        if (technicalBusy) return@setOnClickListener
                        val keyword = input.text.toString().trim()
                        if (keyword.isEmpty()) {
                            Toast.makeText(context, "请输入股票名称或代码", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        analyze(keyword)
                    }
                }
            )
            technical?.let { showTechnical(context, it) }
            container.addView(view)
        }

        // ── 个股基本面分析 ──
        UiKit.card(context, "📊 个股基本面分析").apply {
            addText(
                "读最近 8 个单季的三张表（资产负债表 / 利润表 / 现金流量表）＋日频估值＋同板块对比＋公告：" +
                    "先看有效利润（归母和扣非分开）、利润率和现金偿债，再看资产质量与治理公告，" +
                    "卡片最后给综合结论和合理估值区间。",
                12f,
            )
            val input = EditText(context).apply {
                hint = "例如：华天科技 / 002185"
                setSingleLine(true)
                UiKit.styleInput(this)
                setText(lastKeyword)
                setSelection(text.length)
                doAfterTextChanged { lastKeyword = it?.toString().orEmpty() }
            }
            add(input)
            add(button(context, if (fundamentalBusy) "正在拉财报、估值与公告…" else "分析基本面") {
                if (fundamentalBusy) return@button
                val keyword = input.text.toString().trim()
                if (keyword.isEmpty()) {
                    Toast.makeText(context, "请输入股票名称或代码", Toast.LENGTH_SHORT).show()
                    return@button
                }
                analyzeFundamentals(keyword)
            })
            fundamental?.let { showFundamental(context, it) }
            container.addView(view)
        }

        // ── 港股 / 美股基本面分析 ──
        UiKit.card(context, "🌎 港股 / 美股基本面分析").apply {
            addText(
                "支持股票名称、代码和部分字符搜索，例如 NVIDIA、NVDA、英伟达、腾讯或 00700.HK。" +
                    "报告使用港美股自己的币种和财务口径，不套用 A 股接口。",
                12f,
            )
            val input = EditText(context).apply {
                hint = "例如：英伟达 / NVDA / 腾讯 / 00700.HK"
                setSingleLine(true)
                UiKit.styleInput(this)
                setText(globalKeyword)
                setSelection(text.length)
                globalInput = this
                doAfterTextChanged {
                    if (globalInputProgrammatic) return@doAfterTextChanged
                    globalKeyword = it?.toString().orEmpty()
                    globalSelected = null
                    globalSuggestions = emptyList()
                    renderGlobalSuggestions(context)
                    globalSearchJob?.cancel()
                    val query = globalKeyword.trim()
                    if (query.isBlank()) return@doAfterTextChanged
                    globalSearchJob = requireScope().launch {
                        delay(250)
                        val suggestions = withContext(Dispatchers.IO) {
                            runCatching { GlobalFundamentalsClient().search(query) }.getOrDefault(emptyList())
                        }
                        if (!isAdded || globalKeyword.trim() != query) return@launch
                        globalSuggestions = suggestions
                        renderGlobalSuggestions(context)
                    }
                }
            }
            add(input)
            globalSuggestionBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            add(globalSuggestionBox!!)
            renderGlobalSuggestions(context)
            add(button(context, if (globalBusy) "正在拉取港美股财务数据…" else "分析港股 / 美股") {
                if (globalBusy) return@button
                analyzeGlobal(globalKeyword.trim())
            })
            global?.let { showGlobal(context, it) }
            container.addView(view)
        }

        // ── AI 综合分析 ──
        UiKit.card(context, "🤖 AI 综合分析").apply {
            addText(
                "输入指数、股票名称或代码。系统会综合技术面、基本面、持仓报告和盘面总结，" +
                    "并补充最近几天的大盘与所属板块表现，再由 DeepSeek Flash 生成判断。",
                12f,
            )
            val input = EditText(context).apply {
                hint = "例如：英伟达最近为什么下跌？\n特变电工估值贵不贵？"
                setSingleLine(false)
                minLines = 2
                maxLines = 5
                gravity = android.view.Gravity.TOP
                UiKit.styleInput(this, multiline = true)
                setText(aiKeyword)
                setSelection(text.length)
                doAfterTextChanged { aiKeyword = it?.toString().orEmpty() }
            }
            add(input)
            add(button(context, if (aiBusy) "正在汇总数据并请求 AI…" else "分析并回答") {
                if (aiBusy) return@button
                val keyword = input.text.toString().trim()
                if (keyword.isEmpty()) {
                    Toast.makeText(context, "请输入指数名称、股票名称或代码", Toast.LENGTH_SHORT).show()
                    return@button
                }
                analyzeAi(keyword)
            })
            ai?.let { showAi(context, it) }
            container.addView(view)
        }

        // ── 自选股管理 ──
        container.addView(watchlistCard(context, store.watchlist,
            onAdd = { Dialogs.addWatchItem(requireScope(), context) { render() } },
            onManage = {
                if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag("watchlist_manager") == null) {
                    WatchlistDialogFragment().showNow(childFragmentManager, "watchlist_manager")
                }
            },
        ).view)

        // ── 盘面总结 ──
        UiKit.card(context, "📋 盘面总结").apply {
            val hasSummary = !Repo.summaryText.isNullOrBlank() || !store.lastSummaryText.isNullOrBlank()
            addTaskStatus(Repo.summaryTask)
            addText(
                if (hasSummary) "已缓存最近一次盘面总结，可随时查看。" else "尚未生成，每天 16:00 / 18:00 / 21:00 自动生成。",
                12f,
            )
            add(button(context, if (Repo.summaryTask.isActive) "正在生成…" else "立即生成") {
                if (!Repo.summaryTask.isActive) {
                    MonitorService.send(context, MonitorService.ACTION_SUMMARY)
                }
            })
            add(button(context, "查看最近一次总结") {
                val text = Repo.summaryText ?: store.lastSummaryText
                if (text.isNullOrBlank()) {
                    Toast.makeText(context, "暂无总结，请先生成", Toast.LENGTH_SHORT).show()
                } else {
                    ReportActivity.open(context, "📋 盘面总结", text)
                }
            })
            container.addView(view)
        }

        // ── 监控设置 ──
        UiKit.card(context, "⚙️ 监控与提醒").apply {
            addKeyValue("后台监控", if (store.monitorEnabled) "已开启" else "已关闭")
            addKeyValue("开机自启动", if (store.bootAutoStart) "已开启" else "已关闭")
            addKeyValue("行情提醒", if (store.alertsEnabled) "已开启" else "已关闭")
            addKeyValue(
                "买卖点 / 做T提醒",
                if (store.alertsEnabled && store.intradaySignalEnabled) "已开启" else "已关闭",
            )
            addKeyValue("涨跌停扫描", if (store.limitScanEnabled) "已开启" else "已关闭")
            MonitoringCopy.rules(store.dropAlertPct).forEach { rule -> addKeyValue(rule.label, rule.text) }
            addKeyValue("当前状态", Repo.statusText)
            add(button(context, "打开设置") {
                Dialogs.showSettings(requireActivity() as AppCompatActivity) { render() }
            })
            add(button(context, "立即刷新行情") {
                MonitorService.send(context, MonitorService.ACTION_REFRESH)
                Toast.makeText(context, "已请求刷新", Toast.LENGTH_SHORT).show()
            })
            add(button(context, "试一次买卖点提醒") {
                if (!store.alertsEnabled || !store.intradaySignalEnabled) {
                    Toast.makeText(context, "请先开启「行情提醒」与「买卖点 / 做T提醒」", Toast.LENGTH_SHORT).show()
                } else {
                    val status = Notifier.signalStatus(context)
                    val demoInput = IntradayTactics.Input(
                        "sh600000", "示例股票", 20.70, 3.5, 1.5, 10.0,
                        19.00, 22.00, 0, 1800.0, 24.50,
                        80_000.0, 20_000.0, 5.0, 20.0,
                        todayTurnoverYi = 3.0,
                        predictedMarketTurnoverYi = 16_000.0,
                        marketIndexPrice = 3200.0,
                    ).copy(
                        holdingTContext = HoldingTContext(
                            date = "示例",
                            generatedAtMs = System.currentTimeMillis(),
                            trend = HoldingTTrend.RANGE,
                            gain5Pct = 3.0,
                            atrPct = 3.0,
                            weekTrend = SwingTrend.UP,
                            monthTrend = SwingTrend.UP,
                        ),
                    )
                    Notifier.signal(
                        context,
                        TEST_SIGNAL_ID,
                        "🔔 示例·冲高做T",
                        TradeNotification.intraday(
                            demoInput,
                            IntradaySignal(IntradaySignalType.T_SELL, "示例·冲高做T", ""),
                        ),
                    )
                    Toast.makeText(context, Notifier.signalStatusMessage(status), Toast.LENGTH_LONG).show()
                }
            })
            addText("划掉最近任务卡片会立即停止后台监控，常驻通知同时消失；重新打开应用即恢复。", 11.5f)
            add(button(context, "停止后台监控") {
                MonitorService.stop(context)
                store.monitorEnabled = false
                Repo.reset()
                render()
            })
            container.addView(view)
        }

        // ── 关于 ──
        UiKit.card(context, "ℹ️ 关于").apply {
            addText("股票监控 Android 版：由 macOS 菜单栏版按同一套数据源与算法迁移而来。", 12f)
            addText(
                "数据源：新浪财经实时行情 / 日线 / 行业板块；东方财富概念板块与指数成交额" +
                    "（东财不可达时概念板块退回新浪备用口径）。\n" +
                    "刷新节奏：15 秒一次行情，两市成交额 15 分钟一次，板块 30 分钟，" +
                    "指标选股与持仓分析每 30 分钟一个槽位，" +
                    "盘面总结每天 16:00 / 18:00 / 21:00。",
                12f,
            )
            addText(context.getString(R.string.disclaimer), 12f, UiKit.color(context, R.color.warn))
            container.addView(view)
        }
    }

    override fun onRefresh() {
        MonitorService.send(requireContext(), MonitorService.ACTION_REFRESH)
    }

    private fun analyze(keyword: String) {
        val context = requireContext()
        val scope = requireScope()
        technicalBusy = true
        render()
        scope.launch {
            try {
                val store = Store(context)
                // 网络与指标计算全部放到 IO 线程，避免 NetworkOnMainThreadException。
                val payload = withContext(Dispatchers.IO) {
                    runCatching {
                        val sina = SinaClient()
                        val hit = sina.search(keyword).firstOrNull()
                            ?: throw IllegalStateException("没有找到「$keyword」对应的股票")
                        val live = sina.quote(hit.first)
                        val history = sina.kline(hit.first, 240)
                        if (history.size < 60) throw IllegalStateException("日线数据不足60个交易日，无法稳定分析")
                        val benchmark = if (hit.first == "sh000001") null else sina.kline("sh000001", 240)
                        val result = StockAnalysis.analyze(
                            history,
                            hit.first,
                            hit.second,
                            live,
                            benchmark,
                            // 盘中按已过交易时间折算量比，收盘后自动退回纯日线口径。
                            elapsedTradingMinutes = TradingCalendar.tradingMinutesElapsed(),
                        )
                        // 环境要先就位，否则报告里那句「回踩不破均线可以继续持有」在塌陷期是反话。
                        val env = EnvContext(
                            market = MarketRegime.evaluate(benchmark.orEmpty()),
                            sector = BoardTagCache.loadContext(
                                store = store,
                                eastMoney = EastMoneyClient(),
                                code = hit.first,
                                boardChangeFallback = { names ->
                                    val boards = runCatching { EastMoneyClient().industryBoardChanges() }
                                        .getOrDefault(emptyMap())
                                    BoardTagCache.matchBoardChange(boards, names) { name ->
                                        Repo.sectorChangeByName(name) {
                                            runCatching { SinaClient().sectors() }.getOrNull()
                                        }
                                    }
                                },
                            ),
                            prev = AnalysisReportCache.get(store.analysisReports, hit.first)?.snapshot,
                        )
                        TechnicalCard(
                            result = result,
                            summary = TechnicalSummary.from(result),
                            report = StockAnalysis.formatReport(result, env),
                        )
                    }
                }
                payload.fold(
                    onSuccess = { card ->
                        technical = card
                        val stamp = TimeUtils.nowText()
                        store.analysisReports = AnalysisReportCache.merge(
                            raw = store.analysisReports,
                            code = card.result.code,
                            name = card.result.name,
                            technical = card.report,
                            fundamental = null,
                            updatedAt = stamp,
                            // 存下这次的状态，下次的「较上次分析」才有对照。
                            snapshot = RowSnapshot(
                                code = card.result.code,
                                signal = card.result.entrySetup.label,
                                score = card.result.score,
                                support = card.result.support,
                                resistance = card.result.resistance,
                                price = card.result.price,
                                changePct = card.result.priceChange,
                                volumeRatio = card.result.volumeRatio ?: 0.0,
                            ),
                        )
                        ReportActivity.open(context, "🔍 ${card.result.name} 技术分析", card.report)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "个股分析失败: $keyword", error)
                        Toast.makeText(context, error.message ?: "分析失败", Toast.LENGTH_LONG).show()
                    },
                )
            } finally {
                technicalBusy = false
                if (isAdded && view != null) render()
            }
        }
    }

    private fun UiKit.Card.showTechnical(context: Context, card: TechnicalCard) {
        val result = card.result
        val summary = card.summary
        addDivider()
        addKeyValue(
            result.name,
            "${StockAnalysis.price(result.price)} 元",
            note = "${result.code}｜数据截至 ${result.asOf}｜${result.priceSource}",
        )
        addKeyValue(
            "涨跌幅",
            StockAnalysis.pct(result.priceChange),
            UiKit.changeColor(context, result.priceChange),
        )
        add(button(context, "查看完整报告（可复制、分享）") {
            ReportActivity.open(context, "🔍 ${result.name} 技术分析", card.report)
        })
        addKeyValue(
            "技术评分",
            "${summary.score} · ${summary.scoreLabel}",
            technicalScoreColor(context, summary.score),
        )
        addText("趋势：${summary.trend}", 12f)
        if (summary.divergence.isNotBlank()) {
            addText(summary.divergence, 12f)
        }
        add(UiKit.body(context, technicalVolumeLine(context, result), 12f))
        add(UiKit.body(context, technicalMomentumLine(context, result), 12f))
        add(UiKit.body(context, technicalLevelsLine(context, result), 12f))
        addText("入场结构：${summary.entry}", 12.5f, UiKit.color(context, R.color.text_primary))
        addText("操作建议：${summary.action}", 12.5f, UiKit.color(context, R.color.text_primary))
    }

    private fun technicalVolumeLine(context: Context, result: TechnicalResult): CharSequence {
        val builder = SpannableStringBuilder("量价：量比 ")
        val ratio = result.volumeRatio?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "暂缺"
        appendTechnicalNumber(context, builder, ratio)
        builder.append(" 倍")
        if (result.volumeRatioIntraday) builder.append("（盘中折算）")
        val signal = VolumePrice.of(result.volumeRatio, result.priceChange, result.priceContext)
        builder.append("｜")
        appendTechnicalNumber(context, builder, VolumePrice.tag(signal), UiKit.signalColor(context, signal))
        return builder
    }

    private fun technicalMomentumLine(context: Context, result: TechnicalResult): CharSequence {
        val builder = SpannableStringBuilder("动能：MACD ")
        appendTechnicalNumber(context, builder, if (result.macd.dif > result.macd.dea) "多头" else "空头")
        builder.append("｜RSI ")
        appendTechnicalNumber(context, builder, result.rsi?.let { String.format(Locale.CHINA, "%.1f", it) } ?: "暂缺")
        builder.append("｜KDJ K ")
        appendTechnicalNumber(context, builder, String.format(Locale.CHINA, "%.1f", result.kdj.k))
        builder.append(" / D ")
        appendTechnicalNumber(context, builder, String.format(Locale.CHINA, "%.1f", result.kdj.d))
        return builder
    }

    private fun technicalLevelsLine(context: Context, result: TechnicalResult): CharSequence {
        val builder = SpannableStringBuilder("关键价位：支撑 ")
        appendTechnicalNumber(context, builder, StockAnalysis.price(result.support))
        builder.append("｜压力 ")
        appendTechnicalNumber(context, builder, StockAnalysis.price(result.resistance))
        builder.append("｜MA20 ")
        appendTechnicalNumber(context, builder, StockAnalysis.price(result.ma20))
        builder.append("｜ATR ")
        appendTechnicalNumber(context, builder, StockAnalysis.price(result.volatility.atr))
        builder.append("（")
        appendTechnicalNumber(
            context,
            builder,
            String.format(Locale.CHINA, "%.2f", result.volatility.atrPct ?: 0.0),
        )
        builder.append("%）")
        return builder
    }

    private fun appendTechnicalNumber(
        context: Context,
        builder: SpannableStringBuilder,
        text: String,
        color: Int = UiKit.color(context, R.color.report_highlight),
    ) {
        val start = builder.length
        builder.append(text)
        builder.setSpan(
            ForegroundColorSpan(color),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun analyzeFundamentals(keyword: String) {
        val context = requireContext()
        val scope = requireScope()
        fundamentalBusy = true
        render()
        scope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    runCatching {
                        val sina = SinaClient()
                        val hit = sina.search(keyword).firstOrNull()
                            ?: throw IllegalStateException("没有找到「$keyword」对应的股票")
                        val data = FundamentalsClient().fetch(hit.first, hit.second)
                        val technicalResult = runCatching {
                            val history = sina.kline(hit.first, 240)
                            if (history.size < 60) {
                                null
                            } else {
                                val benchmark = if (hit.first == "sh000001") null else sina.kline("sh000001", 240)
                                StockAnalysis.analyze(
                                    history = history,
                                    code = hit.first,
                                    name = hit.second,
                                    live = runCatching { sina.quote(hit.first) }.getOrNull(),
                                    benchmark = benchmark,
                                    elapsedTradingMinutes = TradingCalendar.tradingMinutesElapsed(),
                                )
                            }
                        }.getOrNull()
                        val today = TimeUtils.todayText()
                        val result = Fundamentals.analyze(
                            Fundamentals.Input(
                                periods = data.periods,
                                balance = data.balance,
                                valuations = data.valuations,
                                totalShares = data.totalShares,
                                price = data.price,
                                peers = data.peers,
                                mainBusiness = data.mainBusiness,
                                announcements = data.announcements,
                                shareholder = data.shareholder,
                                today = today,
                            )
                        )
                        val asOf = TimeUtils.nowText()
                        FundamentalCard(
                            name = hit.second,
                            code = hit.first,
                            price = data.price,
                            result = result,
                            technical = technicalResult,
                            report = FundamentalsReport.format(
                                hit.second,
                                hit.first,
                                data.price,
                                asOf,
                                result,
                                technical = technicalResult?.let {
                                    TechnicalReference(it.support, it.resistance, it.entrySetup)
                                },
                            ),
                        )
                    }
                }
                payload.fold(
                    onSuccess = { card ->
                        fundamental = card
                        // 手动分析的结果写进健康度缓存：盘中破位提醒立刻能用，
                        // 监控链路在冷却内也不会把同样的数据再拉一遍。
                        Repo.publishFundamentalHealth(
                            card.code,
                            FundamentalHealthPolicy.grade(card.result, TimeUtils.nowText()),
                        )
                        val store = Store(context)
                        val stamp = TimeUtils.nowText()
                        store.analysisReports = AnalysisReportCache.merge(
                            raw = store.analysisReports,
                            code = card.code,
                            name = card.name,
                            technical = null,
                            fundamental = card.report,
                            updatedAt = stamp,
                        )
                        // 和技术分析一致：算完直接落到三级报告页，复制/分享都在那一页。
                        ReportActivity.open(context, "📊 ${card.name} 基本面分析", card.report)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "基本面分析失败: $keyword", error)
                        Toast.makeText(context, error.message ?: "基本面分析失败", Toast.LENGTH_LONG).show()
                    },
                )
            } finally {
                fundamentalBusy = false
                if (isAdded && view != null) render()
            }
        }
    }

    private fun renderGlobalSuggestions(context: Context) {
        val box = globalSuggestionBox ?: return
        box.removeAllViews()
        globalSuggestions.take(6).forEach { security ->
            box.addView(
                UiKit.body(
                    context,
                    "${security.name}  ·  ${security.symbol}  ·  ${security.exchange}  ·  ${security.currency}",
                    12.5f,
                ).apply {
                    setPadding(UiKit.dp(context, 8), UiKit.dp(context, 8), 0, UiKit.dp(context, 8))
                    setOnClickListener {
                        globalSelected = security
                        globalKeyword = security.symbol
                        globalSuggestions = emptyList()
                        globalInputProgrammatic = true
                        globalInput?.setText(security.symbol)
                        globalInput?.setSelection(globalInput?.text?.length ?: 0)
                        globalInputProgrammatic = false
                        renderGlobalSuggestions(context)
                    }
                },
            )
        }
    }

    private fun analyzeGlobal(keyword: String) {
        val context = requireContext()
        val scope = requireScope()
        globalBusy = true
        render()
        scope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    runCatching {
                        val client = GlobalFundamentalsClient()
                        val security = globalSelected
                            ?: client.search(keyword).firstOrNull()
                            ?: throw IllegalStateException("没有找到「$keyword」对应的港股或美股")
                        val data = client.fetch(security)
                        val technicalResult = runCatching {
                            val history = client.kline(security, 240)
                            if (history.size < 60) {
                                null
                            } else {
                                val latest = history.last()
                                val previous = history[history.lastIndex - 1]
                                val livePrice = data.price ?: latest.close
                                StockAnalysis.analyze(
                                    history = history,
                                    code = security.symbol,
                                    name = security.name,
                                    live = Quote(
                                        code = security.symbol,
                                        name = security.name,
                                        price = livePrice,
                                        prevClose = previous.close,
                                        open = latest.open,
                                        high = maxOf(latest.high, livePrice),
                                        low = minOf(latest.low, livePrice),
                                        volume = latest.volume,
                                        turnover = 0.0,
                                    ),
                                )
                            }
                        }.getOrNull()
                    val asOf = TimeUtils.nowText()
                        GlobalCard(
                            data = data,
                            technical = technicalResult,
                            report = GlobalFundamentalsReport.format(data, asOf, technicalResult),
                        )
                    }
                }
                payload.fold(
                    onSuccess = { card ->
                        global = card
                        val store = Store(context)
                        val stamp = TimeUtils.nowText()
                        store.analysisReports = AnalysisReportCache.merge(
                            raw = store.analysisReports,
                            code = card.data.security.symbol,
                            name = card.data.security.name,
                            technical = null,
                            fundamental = card.report,
                            updatedAt = stamp,
                        )
                        ReportActivity.open(
                            context,
                            "🌎 ${card.data.security.name} 基本面分析",
                            card.report,
                        )
                    },
                    onFailure = { error ->
                        Log.e(TAG, "港美股基本面分析失败: $keyword", error)
                        Toast.makeText(context, error.message ?: "港美股基本面分析失败", Toast.LENGTH_LONG).show()
                    },
                )
            } finally {
                globalBusy = false
                if (isAdded && view != null) render()
            }
        }
    }

    private fun analyzeAi(keyword: String) {
        val context = requireContext()
        val scope = requireScope()
        aiBusy = true
        render()
        scope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    runCatching {
                        val tools = StockAiToolExecutor(context)
                        val input = AiAnalysisInput(
                            question = keyword,
                            name = "",
                            code = "",
                            market = "待工具识别",
                            technicalReport = null,
                            fundamentalReport = null,
                            trendSummary = "",
                            research = emptyList(),
                            generatedAt = TimeUtils.nowText(),
                        )
                        val result = DeepSeekClient().analyze(input, tools)
                        val security = tools.resolvedSecurity
                        val name = security?.name ?: "综合分析"
                        val code = security?.code.orEmpty()
                        val market = security?.market ?: "未指定标的"
                        val asOf = TimeUtils.nowText()
                        AiCard(
                            name = name,
                            code = code,
                            market = market,
                            result = result,
                            report = AiAnalysisReport.format(result, name, code, asOf),
                        )
                    }
                }
                payload.fold(
                    onSuccess = { card ->
                        ai = card
                        ReportActivity.open(context, "🤖 ${card.name} AI 综合分析", card.report)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "AI 综合分析失败: $keyword", error)
                        Toast.makeText(context, error.message ?: "AI 综合分析失败", Toast.LENGTH_LONG).show()
                    },
                )
            } finally {
                aiBusy = false
                if (isAdded && view != null) render()
            }
        }
    }

    private fun UiKit.Card.showAi(context: Context, card: AiCard) {
        addDivider()
        addText("${card.name}｜${card.code}｜${card.market}", 12f, UiKit.color(context, R.color.text_secondary))
        addText("精要摘要", 12f, UiKit.color(context, R.color.brand_primary))
        addText("核心结论", 12f, UiKit.color(context, R.color.text_secondary))
        addText(card.result.answer, 15f)
        addText("操作建议：${card.result.operationAdvice}", 14f, UiKit.color(context, R.color.brand_primary))
        addText(
            "主要风险：${card.result.risks.firstOrNull() ?: "暂未识别，仍需结合原始数据核对"}",
            12f,
            UiKit.color(context, R.color.risk_watch),
        )
        addText("可信度：${card.result.confidence}", 12f)
        add(button(context, "查看完整分析（可复制、分享）") {
            ReportActivity.open(context, "🤖 ${card.name} AI 综合分析", card.report)
        })
    }

    private fun styledReportText(context: Context, text: String): CharSequence {
        val highlight = UiKit.color(context, R.color.report_highlight)
        val builder = SpannableStringBuilder()
        ReportMarkup.parse(text).forEach { segment ->
            val start = builder.length
            builder.append(segment.text)
            if (segment.highlight) {
                builder.setSpan(
                    ForegroundColorSpan(highlight),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return builder
    }

    private fun UiKit.Card.showGlobal(context: Context, card: GlobalCard) {
        val data = card.data
        addDivider()
        addKeyValue(
            data.security.name,
            data.price?.let { GlobalFundamentalsReport.formatPrice(it, data.currency) } ?: "—",
            note = "${data.security.symbol}｜${data.security.exchange}",
        )
        add(button(context, "查看完整报告（可复制、分享）") {
            ReportActivity.open(context, "🌎 ${data.security.name} 基本面分析", card.report)
        })
        addKeyValue("市值", data.marketCap?.let { GlobalFundamentalsReport.formatAmount(it, data.currency) } ?: "—")
        addKeyValue(
            "数据来源",
            data.dataSources.joinToString(" + ").ifBlank { "未识别" },
            note = "最新财务期间：${data.latestPeriod ?: "暂缺"}",
        )
        addKeyValue(
            "PE / PB",
            "${data.pe?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} / " +
                "${data.pb?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} 倍",
        )
        addKeyValue(
            "毛利率 / 净利率",
            "${data.grossMargin?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "—"} / " +
                "${data.profitMargin?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "—"}",
        )
        addKeyValue(
            "经营现金流 / 自由现金流",
            "${data.operatingCashFlow?.let { GlobalFundamentalsReport.formatAmount(it, data.currency) } ?: "—"} / " +
                "${data.freeCashFlow?.let { GlobalFundamentalsReport.formatAmount(it, data.currency) } ?: "—"}",
        )
        addKeyValue("行业", "${data.sector ?: "—"} / ${data.industry ?: "—"}")
        addText(GlobalFundamentalsReport.fairValueSummary(data), 12f)
        card.technical?.let { technical ->
            addText(
                "技术面：MA20 ${String.format(Locale.US, "%.2f", technical.ma20)}｜" +
                    "支撑 ${String.format(Locale.US, "%.2f", technical.support)}｜" +
                    "压力 ${String.format(Locale.US, "%.2f", technical.resistance)}",
                12f,
            )
            addText("趋势：${technical.action}｜${technical.chip.pricePosition}", 12f)
        } ?: addText("技术面：历史 K 线暂缺，未计算技术指标。", 12f)
        addText(
            "结论：${if (data.pe != null && data.pe > 35.0) "估值偏高，依赖未来增长兑现" else "请结合增长、现金流和同行估值判断安全边际"}。",
            12f,
        )
        if (data.dataWarnings.isNotEmpty()) {
            addText("数据完整度提醒：${data.dataWarnings.joinToString("；")}", 11.5f)
        }
        addText("缺失字段不会用 A 股数据补齐；完整来源和计算细节请查看报告。", 11.5f)
    }

    /** 基本面卡片：数字用颜色分档，最后两行才是综合结论与合理估值。 */
    private fun UiKit.Card.showFundamental(context: Context, card: FundamentalCard) {
        val result = card.result
        addDivider()
        addKeyValue(
            card.name,
            "${FundamentalsReport.money(card.price)} 元",
            note = "${card.code}｜最新报告期 ${result.latest?.date ?: "—"}",
        )
        // 完整报告在三级页面（等宽字体、可复制、可分享），入口放这里，别让人翻到卡片底部才找到。
        add(button(context, "查看完整报告（可复制、分享）") {
            ReportActivity.open(context, "📊 ${card.name} 基本面分析", card.report)
        })
        result.shareholder?.let { shareholder ->
            val flowLevel = when {
                shareholder.institutionalNetFunds == null -> null
                shareholder.institutionalNetFunds > 0.0 -> RiskLevel.GOOD
                shareholder.institutionalNetFunds < 0.0 -> RiskLevel.WATCH
                else -> null
            }
            addText(
                FundamentalsReport.shareholderSummary(result),
                12.5f,
                flowLevel?.let { riskColor(context, it) } ?: UiKit.color(context, R.color.text_primary),
            )
            addText(
                "机构流入：${shareholder.inflows.take(3).joinToString("、") { it.name }.ifBlank { "—" }}｜" +
                    "流出：${shareholder.outflows.take(3).joinToString("、") { it.name }.ifBlank { "—" }}",
                11.5f,
                UiKit.color(context, R.color.text_secondary),
            )
        }

        metric(
            context,
            "有效利润：扣非占归母",
            FundamentalsReport.pct1(result.deductRatio?.times(100)),
            Fundamentals.Bands.deductRatio(result.deductRatio),
            note = "归母 TTM ${yi(result.ttmNetProfit)}｜扣非 TTM ${yi(result.ttmDeductNetProfit)}",
        )
        addKeyValue(
            "EPS-TTM / BPS",
            "${FundamentalsReport.numOrDash(result.eps)} / ${FundamentalsReport.numOrDash(result.bps)} 元",
        )
        addKeyValue(
            "毛利率 / 净利率",
            "${FundamentalsReport.pct1(result.grossMargin)} / ${FundamentalsReport.pct1(result.netMargin)}",
        )
        metric(
            context,
            "ROE（TTM 归母净利 / 期末净资产）",
            FundamentalsReport.pct1(result.roe?.times(100)),
            Fundamentals.Bands.roe(result.roe),
        )

        metric(
            context,
            "经营现金流 / 归母净利",
            "${FundamentalsReport.numOrDash(result.ocfToProfit)} 倍",
            Fundamentals.Bands.ocfToProfit(result.ocfToProfit),
        )
        metric(
            context,
            "销售收现比",
            FundamentalsReport.numOrDash(result.cashToRevenue),
            Fundamentals.Bands.cashToRevenue(result.cashToRevenue),
        )
        metric(
            context,
            "净现金 / 净资产",
            FundamentalsReport.pct1(result.netCashRatio?.times(100)),
            Fundamentals.Bands.netCash(result.netCashRatio, result.balance),
            note = "有息负债高于账面货币资金时记负",
        )
        result.balance?.let { balance ->
            // 资产负债率行业差异太大，单看一个数字给颜色容易误导，这里只给数不给判断。
            addKeyValue(
                "资产负债率",
                FundamentalsReport.pct1(FundamentalsReport.debtRatio(balance)?.times(100)),
                note = "货币资金 ${FundamentalsReport.yi(balance.monetaryFunds)} 亿｜" +
                    "有息负债 ${FundamentalsReport.yi(balance.interestBearingDebt)} 亿",
            )
            metric(
                context,
                "商誉占净资产",
                FundamentalsReport.pct1(FundamentalsReport.goodwillRatio(balance)?.times(100)),
                Fundamentals.Bands.goodwill(FundamentalsReport.goodwillRatio(balance)),
                note = "商誉 ${FundamentalsReport.yi(balance.goodwill)} 亿｜" +
                    "其他应收款 ${FundamentalsReport.yi(balance.otherReceivable)} 亿",
            )
        }

        addText("公告与治理提示（近 ${Governance.WINDOW_DAYS} 天）", 12f)
        if (result.announcementsScanned == 0) {
            addText("公告接口没取到数据，这一块暂缺（不代表没问题）。", 11.5f, UiKit.color(context, R.color.text_secondary))
        } else if (result.governance.isEmpty()) {
            addText(
                "最近 ${result.announcementsScanned} 条公告中暂未识别出需要重点提示的事项；" +
                    "这不能当成「没有风险」的证明。",
                11.5f,
                UiKit.color(context, R.color.text_secondary),
            )
        } else {
            result.governance.take(4).forEach {
                addText("${mark(it.level)}${it.date} ${it.title}", 12f, riskColor(context, it.level))
                addText(
                    "判断：${it.assessment ?: "正文暂未取到，建议打开公告原文核对。"}",
                    11.5f,
                    UiKit.color(context, R.color.text_secondary),
                )
            }
        }

        result.peers?.let { peers ->
            addKeyValue(
                "行业地位：${peers.boardName}",
                peers.marketCapRank?.let { "第 $it/${peers.total} 名" } ?: "排名暂缺",
                note = "板块 PE 中位 ${FundamentalsReport.numOrDash(peers.peMedian)}" +
                    "（自身 ${FundamentalsReport.peText(result.currentPe)}）｜" +
                    "板块 PB 中位 ${FundamentalsReport.numOrDash(peers.pbMedian)}" +
                    "（自身 ${FundamentalsReport.numOrDash(result.currentPb)}）",
            )
        }
        result.mainBusiness.filter { it.ratio >= 0.01 }.take(3).forEach {
            addText(
                "主营·${it.name}：${FundamentalsReport.yi(it.income)} 亿" +
                    "（占 ${FundamentalsReport.pct1(it.ratio * 100)}）" +
                    "毛利率 ${FundamentalsReport.pct1(it.grossMargin?.times(100))}",
                12f,
            )
        }

        if (result.quarters.isNotEmpty()) {
            addText("单季同比（最近 4 季，营收 / 归母单位亿元）", 12f)
            result.quarters.takeLast(4).forEach { add(UiKit.body(context, quarterLine(context, it), 12.5f)) }
        }

        result.risks.forEach {
            addText("${mark(it.level)}${it.text}", 12f, riskColor(context, it.level))
        }
        result.notes.forEach {
            addText("※ $it", 11.5f, UiKit.color(context, R.color.warn))
        }

        addDivider()
        addKeyValue(
            "综合结论",
            "${result.verdict} · ${result.score}/100",
            scoreColor(context, result.score),
        )
        addText(FundamentalsReport.headline(result), 12.5f)
        addKeyValue(
            "合理估值区间",
            if (result.fairLow != null && result.fairHigh != null) {
                "${FundamentalsReport.money(result.fairLow)} ~ ${FundamentalsReport.money(result.fairHigh)} 元"
            } else {
                "暂不出区间"
            },
        )
        addText(FundamentalsReport.valuationText(result, card.price), 12.5f)
        addText(
            FundamentalsReport.combinedValuationText(
                result,
                card.technical?.let {
                    TechnicalReference(it.support, it.resistance, it.entrySetup)
                },
            ),
            12.5f,
        )
        addText("下一财报期：${FundamentalsReport.nextQuarterDirection(result)}", 12f)
        addText("未来估值：${FundamentalsReport.futureValuationSummary(result)}", 12f)
    }

    /** 单季一行：营收和归母各自跟着自己的同比涨跌色。 */
    private fun quarterLine(context: Context, quarter: QuarterPoint): CharSequence {
        val builder = SpannableStringBuilder()
        builder.append("${quarter.label}  营收 ${FundamentalsReport.yi(quarter.revenue)}亿 ")
        val revenueStart = builder.length
        builder.append(FundamentalsReport.signedPct(quarter.revenueYoy))
        val revenueEnd = builder.length
        builder.append("  归母 ${FundamentalsReport.yi(quarter.netProfit)}亿 ")
        val profitStart = builder.length
        builder.append(FundamentalsReport.signedPct(quarter.netProfitYoy))
        val profitEnd = builder.length
        builder.setSpan(
            ForegroundColorSpan(yoyColor(context, quarter.revenueYoy)),
            revenueStart,
            revenueEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            ForegroundColorSpan(yoyColor(context, quarter.netProfitYoy)),
            profitStart,
            profitEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return builder
    }

    private fun yoyColor(context: Context, value: Double?): Int =
        if (value == null) UiKit.color(context, R.color.text_secondary) else UiKit.changeColor(context, value)

    private fun UiKit.Card.metric(
        context: Context,
        label: String,
        value: String,
        level: RiskLevel?,
        note: String? = null,
    ) {
        addKeyValue(label, value, level?.let { riskColor(context, it) } ?: UiKit.color(context, R.color.text_primary), note)
    }

    private fun riskColor(context: Context, level: RiskLevel): Int = when (level) {
        RiskLevel.GOOD -> UiKit.color(context, R.color.risk_good)
        RiskLevel.WATCH -> UiKit.color(context, R.color.risk_watch)
        RiskLevel.ALERT -> UiKit.color(context, R.color.risk_alert)
    }

    private fun scoreColor(context: Context, score: Int): Int = when {
        score >= 70 -> UiKit.color(context, R.color.risk_good)
        score >= 55 -> UiKit.color(context, R.color.text_primary)
        score >= 40 -> UiKit.color(context, R.color.risk_watch)
        else -> UiKit.color(context, R.color.risk_alert)
    }

    private fun technicalScoreColor(context: Context, score: Int): Int = when {
        score >= 4 -> UiKit.color(context, R.color.risk_good)
        score <= -4 -> UiKit.color(context, R.color.risk_alert)
        else -> UiKit.color(context, R.color.text_primary)
    }

    private fun yi(value: Double?): String =
        value?.let { "${FundamentalsReport.yi(it)} 亿" } ?: "—"

    private fun mark(level: RiskLevel): String = when (level) {
        RiskLevel.GOOD -> "✅ "
        RiskLevel.WATCH -> "⚠️ "
        RiskLevel.ALERT -> "⛔ "
    }

    companion object {
        private const val TAG = "StockMonitor"
    }

    private fun button(context: android.content.Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            textSize = 13f
            minHeight = UiKit.dp(context, 40)
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = UiKit.dp(context, 3); bottomMargin = UiKit.dp(context, 3) }
            setOnClickListener { onClick() }
        }
}
