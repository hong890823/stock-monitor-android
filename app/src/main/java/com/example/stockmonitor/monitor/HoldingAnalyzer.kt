package com.example.stockmonitor.monitor

import android.util.Log
import com.example.stockmonitor.analysis.DailyReview
import com.example.stockmonitor.analysis.DayPnl
import com.example.stockmonitor.analysis.EnvContext
import com.example.stockmonitor.analysis.EventCoil
import com.example.stockmonitor.analysis.FundamentalHealthPolicy
import com.example.stockmonitor.analysis.HoldingBreakTracker
import com.example.stockmonitor.analysis.HoldingTContext
import com.example.stockmonitor.analysis.HoldingTTracker
import com.example.stockmonitor.analysis.MarketBreadthFreshnessPolicy
import com.example.stockmonitor.analysis.MarketBreadthSnapshot
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.MarketRiskContext
import com.example.stockmonitor.analysis.MarketState
import com.example.stockmonitor.analysis.MarketTimingContext
import com.example.stockmonitor.analysis.MultiTimeframeTrend
import com.example.stockmonitor.analysis.ObservationEntryPolicy
import com.example.stockmonitor.analysis.PortfolioAnalysis
import com.example.stockmonitor.analysis.PositionResult
import com.example.stockmonitor.analysis.StockAnalysis
import com.example.stockmonitor.analysis.TimeUtils
import com.example.stockmonitor.analysis.TradingCalendar
import com.example.stockmonitor.analysis.TurnoverContext
import com.example.stockmonitor.analysis.TurnoverTracker
import com.example.stockmonitor.core.BoardTagCache
import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.DailyRiskMetrics
import com.example.stockmonitor.core.EastMoneyClient
import com.example.stockmonitor.core.FundamentalsClient
import com.example.stockmonitor.core.MarketNewsClient
import com.example.stockmonitor.core.Portfolio
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.SinaClient
import com.example.stockmonitor.core.Store
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 持仓分析与持仓复盘**共用的分析机器**：单股「行情 + 技术 + 信号」判定、市场宽度、
 * 板块/市场风险上下文、公告按需补拉，以及「持仓复盘」整份报告的生成。
 *
 * 为什么独立成对象：复盘的后台执行有两个宿主——16:00 自动档由 [MonitorService] 的定时槽触发，
 * 手动「重跑复盘」由 [AnalysisService]（临时前台服务）触发。两边共用这一份，
 * 数字才对得上；观察用的 [holdingTTracker] / [holdingBreakTracker] 也放在这里，
 * 盘中 tick 与分析/复盘共享同一份观察状态。
 */
object HoldingAnalyzer {

    private const val TAG = "StockMonitor"

    /** 公告清单按需补拉（事件后蓄势识别用）：比基本面轻，12 小时一份足够。 */
    private const val ANNOUNCEMENT_REFRESH_MS = 12 * 60 * 60 * 1000L

    private val sina = SinaClient()
    private val eastMoney = EastMoneyClient()

    /** 补拉公告用的独立作用域：与宿主服务的生命周期解耦，短任务跑完即走。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val holdingTTracker = HoldingTTracker()
    val holdingBreakTracker = HoldingBreakTracker()

    /** 公告清单按需补拉：上次尝试时间与在途任务表，失败不重试轰炸。 */
    private val announcementLastFetchAt = mutableMapOf<String, Long>()
    private val announcementJobs = ConcurrentHashMap<String, Deferred<Unit>>()

    private val dailyReviewBusy = AtomicBoolean(false)

    /**
     * 单只持仓的「行情 + 技术分析 + 信号判定」。持仓分析与复盘共用这一份，
     * 免得两条链路各写一遍、数字对不上。
     *
     * **副作用留给调用方**：盘中信号推送与基本面按需补拉只属于盘中持仓分析，收盘后复盘不该触发。
     */
    suspend fun analyzePosition(
        position: Position,
        portfolio: Portfolio,
        live: Quote,
        benchmark: List<DayBar>?,
        market: MarketState,
        marketRisk: MarketRiskContext? = Repo.marketRiskContext,
    ): PositionResult {
        val history: List<DayBar> = sina.kline(position.code, 240)
        val technical =
            StockAnalysis.analyze(
                history = history,
                code = position.code,
                name = position.name,
                live = live,
                benchmark = if (position.code == "sh000001") null else benchmark,
                // 盘中按已过交易时间折算量比，收盘后自动退回纯日线口径。
                elapsedTradingMinutes = TradingCalendar.tradingMinutesElapsed(),
                fundFlowHistory = Repo.fundFlowHistory[position.code].orEmpty(),
            )
        val today = TimeUtils.todayText()
        val now = System.currentTimeMillis()
        val tContext =
            HoldingTContext.from(
                history,
                MultiTimeframeTrend.evaluate(history, today, benchmark.orEmpty().map { it.date }),
                today,
                now,
                benchmark.orEmpty().map { it.date },
            )
        val tObservation = holdingTTracker.snapshot(live, today, now) ?: holdingTTracker.observe(live, today, now)
        // 事件后蓄势：公告（基本面抓取顺带缓存，缺了按需轻量补拉）+ 含今日实时段的日线。
        maybeRefreshAnnouncements(position.code)
        val eventCoil = Repo.recentAnnouncements[position.code]?.let { announcements ->
            val bars = ObservationEntryPolicy.withLiveBar(history, live).takeIf { it.isNotEmpty() } ?: history
            EventCoil.evaluate(announcements, bars, today)
        }
        val result = PortfolioAnalysis.evaluate(
            position,
            technical,
            live,
            portfolio,
            Repo.fundamentalHealth[position.code]
                ?.takeIf {
                    System.currentTimeMillis() - it.fetchedAtMs in 0..FundamentalHealthPolicy.CACHE_TTL_MS
                }?.health,
            // 环境是「同一根 K 线该怎么读」的依据：大盘/板块塌陷时建议会整个改写，
            // 昨日快照用来写「较昨日」。
            envContext =
                marketBreadth().let { breadth ->
                    EnvContext(
                        market = market,
                        sector = Repo.sectorContexts[position.code],
                        prev = Repo.portfolioSnapshot?.prevRows?.get(position.code),
                        marketRisk = marketRisk,
                        industryBreadth = breadth.first,
                        conceptBreadth = breadth.second,
                    )
                },
            breakObservation = holdingBreakTracker.snapshot(position.code, live, TimeUtils.todayText(), System.currentTimeMillis()),
            holdingTContext = tContext,
            holdingTObservation = tObservation,
            eventCoil = eventCoil,
        )
        val dailyMetrics = DailyRiskMetrics.fromBars(
            history,
            history.lastOrNull()?.date ?: live.quoteDate?.substringBefore(' ') ?: today,
        )
        return result.copy(
            analysisDate = live.quoteDate?.substringBefore(' ').orEmpty(),
            fiveDayNoNewLow = dailyMetrics.fiveDayNoNewLow,
            closesBelowMa20 = dailyMetrics.closesBelowMa20,
        )
    }

    /** 行业/概念涨跌广度（0~1 新鲜度折算后）；持仓报告的市场环境质量判定也读这一份。 */
    fun marketBreadth(): Pair<Double?, Double?> {
        val sectors = Repo.sectors?.all.orEmpty()
        val industryBreadth = MarketBreadthFreshnessPolicy.freshRatio(
            sectors.takeIf { it.isNotEmpty() }?.let {
                MarketBreadthSnapshot(
                    rising = it.count { sector -> sector.change > 0 },
                    total = it.size,
                    updatedAtMs = Repo.sectorsUpdatedAtMs,
                )
            },
            nowMs = System.currentTimeMillis(),
        )
        val concept = Repo.conceptSummary
        val conceptTotal = concept?.let {
            if (it.sectors.isNotEmpty()) it.sectors.size else it.rising + it.falling
        } ?: 0
        val conceptBreadth = MarketBreadthFreshnessPolicy.freshRatio(
            concept?.takeIf { conceptTotal > 0 }?.let {
                MarketBreadthSnapshot(
                    rising = if (it.sectors.isNotEmpty()) {
                        it.sectors.count { item -> item.change > 0 }
                    } else {
                        it.rising
                    },
                    total = conceptTotal,
                    updatedAtMs = Repo.conceptSummaryUpdatedAtMs,
                )
            },
            nowMs = System.currentTimeMillis(),
        )
        return industryBreadth to conceptBreadth
    }

    fun refreshMarketRiskContext(
        store: Store,
        turnoverTracker: TurnoverTracker? = null,
        calendar: Calendar = TimeUtils.nowCalendar(),
        tradingDates: List<String> = emptyList(),
    ): MarketRiskContext {
        val snapshot = Repo.turnoverSnapshot
        val gaps = mutableListOf<String>()
        val cached = Repo.marketRiskContext
        val northbound =
            cached?.northbound?.takeIf { cached.isFresh() }
                ?: runCatching { eastMoney.northboundDailyFlow() }.getOrNull()
                ?: run {
                    gaps.add("北向资金数据暂缺")
                    null
                }
        val sameTimeRatio =
            snapshot?.let {
                turnoverTracker?.sameTimeRatio(TimeUtils.todayText(), it.sampleMinute, it.today)
            }
        val turnover =
            TurnoverContext(
                today = (snapshot?.today ?: Repo.turnoverToday).takeIf { it > 0 },
                previousSameTime = snapshot?.previousSameTime,
                predictedFullDay = snapshot?.predictedFullDay,
                sameTimeRatio = sameTimeRatio,
            ).takeIf { it.today != null || it.previousSameTime != null || it.predictedFullDay != null }
                ?: run {
                    gaps.add("成交额快照暂缺")
                    null
                }
        val context =
            MarketRiskContext(
                timing = MarketTimingContext.from(calendar, tradingDates),
                northbound = northbound,
                turnover = turnover,
                recentMarketText = Repo.summaryText ?: store.lastSummaryText,
                dataGaps = gaps,
                generatedAtMs = System.currentTimeMillis(),
            )
        Repo.marketRiskContext = context
        return context
    }

    suspend fun refreshSectorContexts(store: Store, positions: List<Position>) {
        if (positions.isEmpty()) return
        val boardBars = mutableMapOf<String, List<DayBar>>()
        val industryBoards = runCatching { eastMoney.industryBoardChanges() }.getOrDefault(emptyMap())
        Repo.sectorContexts =
            positions.associate { position ->
                position.code to
                    BoardTagCache.loadContext(
                        store = store,
                        eastMoney = eastMoney,
                        code = position.code,
                        boardBars = boardBars,
                        boardChangeFallback = { names ->
                            BoardTagCache.matchBoardChange(industryBoards, names) { name ->
                                Repo.sectorChangeByName(name) { runCatching { sina.sectors() }.getOrNull() }
                            }
                        },
                    )
            }
    }

    /**
     * 发起公告补拉（12 小时按「上次尝试」节流 + 在途去重），返回在途任务。
     * 盘中持仓分析拿到任务后不等（fire-and-forget，行情分析不能被轻量公告请求拖住）；
     * 复盘则 await 它——否则复盘在补拉完成前读 [Repo.recentAnnouncements]，
     * 会拿到 12 小时前的旧缓存（时间戳发起时即写入，复盘不会再触发第二次拉取）。
     */
    private fun startAnnouncementRefresh(code: String): Deferred<Unit>? {
        // 在途任务直接复用：等同一个请求完成，不对同一 code 打两次接口。
        announcementJobs[code]?.let { job -> if (!job.isCompleted) return job }
        val now = System.currentTimeMillis()
        if (now - (announcementLastFetchAt[code] ?: 0L) < ANNOUNCEMENT_REFRESH_MS) return null
        announcementLastFetchAt[code] = now
        val job = scope.async<Unit> {
            try {
                Repo.publishAnnouncements(code, FundamentalsClient().announcements(code))
                Repo.notifyChanged()
            } catch (error: Exception) {
                Log.w(TAG, "公告补拉失败 $code", error)
            }
        }
        announcementJobs[code] = job
        return job
    }

    /** 盘中持仓分析入口：发起即返回，不等待补拉完成。 */
    private fun maybeRefreshAnnouncements(code: String) {
        startAnnouncementRefresh(code)
    }

    /**
     * 每天收盘后自动生成「持仓复盘」，**只缓存不弹窗**（与盘面总结同一约定：
     * 生成的文档不是可操作事件，不推送通知）。
     *
     * 日期戳**先落再跑**的节奏由自动档调度方（[MonitorService.autoDailyReview]）负责：
     * 失败不每 15 秒重试（会刷屏），需要就手动点「重跑」；手动触发不写日期戳，
     * 否则下午手动跑一次会把 16 点的自动档顶掉。
     */
    suspend fun generateDailyReview(store: Store, turnoverTracker: TurnoverTracker? = null) {
        if (!dailyReviewBusy.compareAndSet(false, true)) return
        try {
            val portfolio = store.portfolio
            if (portfolio.positions.isEmpty()) {
                Repo.dailyReviewTask =
                    BackgroundTaskState.failure(
                        "持仓复盘",
                        "没有配置监控股票",
                        TimeUtils.nowText(),
                    )
                Repo.notifyChanged()
                return
            }
            Repo.dailyReviewTask =
                BackgroundTaskState.running(
                    "持仓复盘",
                    total = portfolio.positions.size,
                    message = "正在获取行情并计算…",
                )
            Repo.notifyChanged()

            // 一次批量取价，保证复盘里所有数字是同一时点的快照（逐只取会有先后差）。
            val codes = (portfolio.positions.map { it.code } + "sh000001").distinct()
            val quotes = sina.quotes(codes)
            val indexQuote = quotes["sh000001"]
            val today = TimeUtils.todayText()
            // 节假日没有行情日期表，靠指数行情标注的交易日期判：不是今天就不生成，
            // 也不覆盖旧报告——否则会伪造一份「今日」。
            if (!DailyReview.isTradingDay(indexQuote, today)) {
                Repo.dailyReviewTask =
                    BackgroundTaskState.failure(
                        "持仓复盘",
                        "今日非交易日，不生成复盘",
                        TimeUtils.nowText(),
                    )
                Repo.notifyChanged()
                return
            }

            // 一次取得近两自然日的“只看重要”快讯，再按真实持仓在本地匹配，不能逐股重复请求。
            val wallstreetcnNews = runCatching {
                if (portfolio.positions.any { it.quantity.isFinite() && it.quantity > 0 }) {
                    MarketNewsClient.wallstreetcnImportant(System.currentTimeMillis(), maxAgeDays = 2)
                } else {
                    emptyList()
                }
            }

            val benchmark = runCatching { sina.kline("sh000001", 240) }.getOrNull()
            val market = MarketRegime.evaluate(benchmark.orEmpty(), indexQuote?.changePct)
            Repo.marketState = market
            val marketRisk =
                refreshMarketRiskContext(
                    store = store,
                    turnoverTracker = turnoverTracker,
                    tradingDates = benchmark.orEmpty().map { it.date },
                )
            refreshSectorContexts(store, portfolio.positions)
            val results = mutableListOf<PositionResult>()
            val liveQuotes = mutableMapOf<String, Quote>()
            val failedNames = mutableListOf<String>()
            portfolio.positions.forEachIndexed { index, position ->
                val live = quotes[position.code]
                // 取不到行情的留给 DayPnl 标「未计入」，不拿 0 填成没亏没赚；
                // 缺行情的也要计入进度，否则进度条永远到不了底。
                if (live == null) {
                    failedNames.add(position.name)
                    Repo.dailyReviewTask =
                        BackgroundTaskState.running(
                            "持仓复盘",
                            done = index + 1,
                            total = portfolio.positions.size,
                            message = "${position.name} 缺行情，跳过",
                        )
                    Repo.notifyChanged()
                    return@forEachIndexed
                }
                liveQuotes[position.code] = live
                // 复盘要用最新公告（近两日利好一节的原料）：analyzePosition 里的补拉是
                // fire-and-forget，立刻读缓存会拿到 12 小时前的旧数据，这里同步等
                // 补拉完成再分析；节流与在途去重与盘中同源，不会打两次接口。
                startAnnouncementRefresh(position.code)?.await()
                // 复盘不发盘中信号、不补拉基本面，所以只取分析结果。
                runCatching { results.add(analyzePosition(position, portfolio, live, benchmark, market, marketRisk)) }
                    .onFailure {
                        failedNames.add(position.name)
                        Log.e(TAG, "复盘分析失败 ${position.name}", it)
                    }
                // 与持仓分析同一写法：每完成一只推进 done，进度条才动（曾漏传 done，一直 0/N）。
                Repo.dailyReviewTask =
                    BackgroundTaskState.running(
                        "持仓复盘",
                        done = index + 1,
                        total = portfolio.positions.size,
                        message = "正在分析 ${position.name}…",
                    )
                Repo.notifyChanged()
            }

            // 一只都没分析成功：不生成“没有风险”的假报告，也不覆盖上一份可用复盘。
            if (results.isEmpty() && portfolio.positions.any { it.quantity.isFinite() && it.quantity > 0 }) {
                Repo.dailyReviewTask =
                    BackgroundTaskState.failure(
                        "持仓复盘",
                        "全部持仓分析失败，保留上一份复盘（${failedNames.distinct().joinToString("、")}）",
                        TimeUtils.nowText(),
                    )
                Repo.notifyChanged()
                return
            }
            // 与盘中同一口径标注统一优先级的确认位（MonitorService 持仓分析循环同款）：
            // 否则复盘走默认 false，头部永远「暂无已确认的买卖动作」，而个股应对里
            // 却可能写着「退出优先」——同一份报告自相矛盾。盘后 15 分钟 K 线已完整，
            // 风险确认的证据窗口比盘中更充分。
            val priorityNow = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            for (index in results.indices) {
                val result = results[index]
                val bars = runCatching { sina.intradayKline(result.code, 15, 120) }.getOrNull().orEmpty()
                val evidence = com.example.stockmonitor.core.TimeframeEvidence.build(
                    bars15 = com.example.stockmonitor.core.IntradayBarWindow(bars)
                        .withCompletionState(priorityNow, result.analysisDate, 15).bars,
                    bars30 = emptyList(),
                    price = result.price,
                    support = result.support,
                    resistance = result.resistance,
                    now = priorityNow,
                    quoteDate = result.analysisDate,
                )
                val confirmed = evidence.riskComplete && evidence.riskConfirmed && evidence.riskConfirmations >= 3
                val exitReference = com.example.stockmonitor.analysis.HoldingExitPolicy.exitReference(result.support, result.volatility.atrPct)
                results[index] = result.copy(
                    priorityRiskConfirmed = confirmed,
                    priorityExitTriggered = confirmed && result.price < exitReference && result.price < result.ma20 * 0.995,
                )
            }

            val text =
                DailyReview.generate(
                    DailyReview.Inputs(
                        positions = portfolio.positions,
                        quotes = liveQuotes,
                        results = results,
                        portfolio = portfolio,
                        indexQuote = indexQuote,
                        analyzedAt = TimeUtils.nowText(),
                        market = market,
                        sectorContexts = Repo.sectorContexts,
                        strongContinuationEvents = Repo.strongContinuationEvents.filter { it.date == today },
                        // 近两日重大利好一节的原料；复盘循环里已对缺缓存/过期持仓同步等补拉完成。
                        recentAnnouncements = Repo.recentAnnouncements,
                        // 利好窗口要回溯到上一交易日（周一复盘才收得进周五盘后与周末的公告）。
                        tradingDates = benchmark.orEmpty().map { it.date },
                        wallstreetcnImportantNews = wallstreetcnNews.getOrDefault(emptyList()),
                        wallstreetcnNewsLoaded = wallstreetcnNews.isSuccess,
                        wallstreetcnNewsError = wallstreetcnNews.exceptionOrNull()?.message,
                        tradeBook = store.tradeBook,
                        todayAcquisitions = DayPnl.acquisitions(store.tradeBook, today),
                        expectedPositionCount = portfolio.positions.count {
                            it.quantity.isFinite() && it.quantity > 0
                        },
                        analysisFailures = failedNames,
                    ),
                )
            Repo.dailyReviewText = text
            store.lastDailyReviewText = text
            Repo.dailyReviewTask =
                BackgroundTaskState.success(
                    "持仓复盘",
                    TimeUtils.nowText(),
                    if (failedNames.isEmpty()) {
                        "复盘已生成"
                    } else {
                        "复盘已生成；${failedNames.distinct().size} 只分析失败，报告已标注风险未核实"
                    },
                )
            Repo.notifyChanged()
        } catch (error: kotlinx.coroutines.CancellationException) {
            // 取消不折叠成「复盘失败」：宿主服务被停是正常路径，留给下次自动档或手动重跑。
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "持仓复盘失败", error)
            Repo.dailyReviewTask =
                BackgroundTaskState.failure(
                    "持仓复盘",
                    error.message ?: "请稍后重试",
                    TimeUtils.nowText(),
                )
            Repo.lastError = "持仓复盘：${error.message}"
            Repo.notifyChanged()
        } finally {
            dailyReviewBusy.set(false)
        }
    }
}
