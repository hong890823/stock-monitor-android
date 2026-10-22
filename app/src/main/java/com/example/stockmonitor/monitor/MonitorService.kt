package com.example.stockmonitor.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.stockmonitor.analysis.DailyReview
import com.example.stockmonitor.analysis.DayPnl
import com.example.stockmonitor.analysis.DeepLossPolicy
import com.example.stockmonitor.analysis.BoxRangePolicy
import com.example.stockmonitor.analysis.BreakoutRiskEvidence
import com.example.stockmonitor.analysis.BreakoutRiskPolicy
import com.example.stockmonitor.analysis.DipReboundEngine
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.EnvContext
import com.example.stockmonitor.analysis.EventCoil
import com.example.stockmonitor.analysis.FundamentalHealthPolicy
import com.example.stockmonitor.analysis.Fundamentals
import com.example.stockmonitor.analysis.HighPositionPolicy
import com.example.stockmonitor.analysis.HoldingBreakTracker
import com.example.stockmonitor.analysis.HoldingTContext
import com.example.stockmonitor.analysis.HoldingTTracker
import com.example.stockmonitor.analysis.IndicatorRefreshScheduler
import com.example.stockmonitor.analysis.IndicatorScreener
import com.example.stockmonitor.analysis.Indicators
import com.example.stockmonitor.analysis.IntradaySignal
import com.example.stockmonitor.analysis.IntradaySignalType
import com.example.stockmonitor.analysis.IntradayTactics
import com.example.stockmonitor.analysis.LimitSealTracker
import com.example.stockmonitor.analysis.MarketAlertEngine
import com.example.stockmonitor.analysis.MarketBreadthFreshnessPolicy
import com.example.stockmonitor.analysis.MarketBreadthSnapshot
import com.example.stockmonitor.analysis.MarketEnvironmentQuality
import com.example.stockmonitor.analysis.MarketTurnoverFallbackPolicy
import com.example.stockmonitor.analysis.MarketTurnoverSource
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.MarketRiskContext
import com.example.stockmonitor.analysis.MarketState
import com.example.stockmonitor.analysis.MarketSummary
import com.example.stockmonitor.analysis.MarketTimingContext
import com.example.stockmonitor.analysis.MultiTimeframeTrend
import com.example.stockmonitor.analysis.ObservationBuyAlert
import com.example.stockmonitor.analysis.ObservationBreakoutRiskAlert
import com.example.stockmonitor.analysis.ObservationEligibility
import com.example.stockmonitor.analysis.ObservationEntryPolicy
import com.example.stockmonitor.analysis.ObservationEvidenceFactory
import com.example.stockmonitor.analysis.ObservationPool
import com.example.stockmonitor.analysis.PaperTradeTracker
import com.example.stockmonitor.analysis.PortfolioAnalysis
import com.example.stockmonitor.analysis.PortfolioSwing
import com.example.stockmonitor.analysis.PriceContext
import com.example.stockmonitor.analysis.PricePosition
import com.example.stockmonitor.analysis.PriceWindow
import com.example.stockmonitor.analysis.ReboundTracker
import com.example.stockmonitor.analysis.SealSprintTracker
import com.example.stockmonitor.analysis.RelaxedScreenSelection
import com.example.stockmonitor.analysis.SectorContext
import com.example.stockmonitor.analysis.SectorTurnoverTracker
import com.example.stockmonitor.analysis.StockAlertScope
import com.example.stockmonitor.analysis.StockAnalysis
import com.example.stockmonitor.analysis.StockDropLevel
import com.example.stockmonitor.analysis.StockRiskPolicy
import com.example.stockmonitor.analysis.StrongContinuationEvent
import com.example.stockmonitor.analysis.SwingEvidence
import com.example.stockmonitor.analysis.SwingPlan
import com.example.stockmonitor.analysis.TickPlan
import com.example.stockmonitor.analysis.TimeUtils
import com.example.stockmonitor.analysis.TradeNotification
import com.example.stockmonitor.analysis.TradingCalendar
import com.example.stockmonitor.analysis.TrendEntryStrategy
import com.example.stockmonitor.analysis.TrendAssessmentPolicy
import com.example.stockmonitor.analysis.TrendScreenSelection
import com.example.stockmonitor.analysis.TurnoverContext
import com.example.stockmonitor.analysis.TurnoverTracker
import com.example.stockmonitor.analysis.VolumeRatio
import com.example.stockmonitor.core.BoardMember
import com.example.stockmonitor.core.BoardTagCache
import com.example.stockmonitor.core.CachedScreen
import com.example.stockmonitor.core.ConceptBoards
import com.example.stockmonitor.core.ConceptSummary
import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.DualTimeframePolicy
import com.example.stockmonitor.core.EastMoneyClient
import com.example.stockmonitor.core.Errors
import com.example.stockmonitor.core.FundamentalsClient
import com.example.stockmonitor.core.Http
import com.example.stockmonitor.core.IntradayBarWindow
import com.example.stockmonitor.core.LeaderOverrides
import com.example.stockmonitor.core.LeaderStrengthTracker
import com.example.stockmonitor.core.LimitData
import com.example.stockmonitor.core.LimitStock
import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationSnapshot
import com.example.stockmonitor.core.ObservationSnapshotHistory
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.core.Portfolio
import com.example.stockmonitor.core.PortfolioSnapshotJson
import com.example.stockmonitor.core.PortfolioSnapshotState
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.QuoteCache
import com.example.stockmonitor.core.ScreenResult
import com.example.stockmonitor.core.ScreenResultJson
import com.example.stockmonitor.core.SectorLeaders
import com.example.stockmonitor.core.SinaClient
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.core.TradeLotLedger
import com.example.stockmonitor.core.TurnoverCurve
import com.example.stockmonitor.core.resolvedAvailableCapital
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台行情监控服务，对应 macOS 菜单栏的 update_interval=30 秒定时器 + _fetch_data：
 * - 每 30 秒刷新一次自选股行情，工作日 08:30-16:00 才请求新数据
 * - 行业/概念板块、涨跌停每 30 分钟刷新一次
 * - 指标选股盘中每 30 分钟一个槽位，盘后按夜间窗口重试
 * - 持仓分析盘中每 30 分钟一个槽位
 * - 盘面总结每天 16:00 / 18:00 / 21:00 各生成一次
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sina = SinaClient()
    private val eastMoney = EastMoneyClient()
    private val tickBusy = AtomicBoolean(false)

    /** 两套扫描各一把锁：快扫不被深扫挡住，深扫也不被快扫打断。 */
    private val indicatorScanBusy = AtomicBoolean(false)
    private val patternScanBusy = AtomicBoolean(false)
    private val portfolioBusy = AtomicBoolean(false)
    private val summaryBusy = AtomicBoolean(false)
    private val observationRefreshBusy = AtomicBoolean(false)
    private var lastObservationIntradayRefreshAtMs = 0L
    private val observationHistory = java.util.concurrent.ConcurrentHashMap<String, Pair<String, List<DayBar>>>()
    private val swingHistory = java.util.concurrent.ConcurrentHashMap<String, Pair<String, List<DayBar>>>()
    private val intraday15ByCode = java.util.concurrent.ConcurrentHashMap<String, IntradayBarWindow>()
    private val intraday30ByCode = java.util.concurrent.ConcurrentHashMap<String, IntradayBarWindow>()
    private val intradayLastFetchAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private lateinit var store: Store
    private lateinit var screener: IndicatorScreener
    private lateinit var indicatorScheduler: IndicatorRefreshScheduler
    private var tickJob: Job? = null

    private var firstLoadDone = false
    private var sectorTick = SECTOR_INTERVAL_TICKS - 1
    private var sectorFlowTick = SECTOR_FLOW_INTERVAL_TICKS - 1
    private var turnoverTracker: TurnoverTracker? = null
    private val sectorTurnoverTracker = SectorTurnoverTracker()
    private var turnoverCurveDay: String? = null
    private var turnoverCurve: TurnoverCurve? = null
    private var lastTurnoverSlot: Int = -1
    private var lastTurnoverDay: String? = null
    private val previousPrices = mutableMapOf<String, Double>()
    private val stockDropAlertLevels = mutableMapOf<String, StockDropLevel>()
    private val stockDropAlertLastAt = mutableMapOf<String, Long>()
    private var stockDropAlertDay: String? = null
    private val marketAlertEngine = MarketAlertEngine()
    private val dipReboundEngine = DipReboundEngine()
    private val reboundTracker = ReboundTracker()
    private val sealSprintTracker = SealSprintTracker()
    private val limitSealTracker = LimitSealTracker()
    private val portfolioSignals = mutableMapOf<String, String>()

    /** 盘中提醒的当前形态：只有形态变化（含从无到有）才提醒，见 checkIntradaySignals。 */
    private val signalAlertActive = mutableMapOf<String, String>()

    /** 尚未完成连续确认的候选信号；条件消失或类型变化时重新计数。 */
    private val signalAlertPending = mutableMapOf<String, PendingIntradaySignal>()
    private val signalAlertLastAt = mutableMapOf<String, Long>()
    private val signalAlertCount = mutableMapOf<String, Int>()

    /**
     * 龙头强度状态机：炸板守住高位线的转弱确认计时。
     * 判定用 [LeaderStrengthTracker.strongNames]（含 3 分钟防抖），界面展示用
     * [LeaderStrengthTracker.sealedNames] / [LeaderStrengthTracker.heldHighNames]（纯计算）。
     */
    private val leaderStrengthTracker = LeaderStrengthTracker()
    private var holdingTContexts: Map<String, HoldingTContext> = emptyMap()

    /** 今日支撑位状态：破过位、破位后是否站回过。决定「站回 / 反抽失败」两类文案。 */
    private data class SupportBreakFlags(
        var broken: Boolean = false,
        var reclaimed: Boolean = false,
    )

    private val supportBreakStates = mutableMapOf<String, SupportBreakFlags>()

    /** 同一代码同一天所有「可操作」提醒（买卖点 + 下探回升）共用的条数预算。 */
    private var pushBudgetDay: String? = null
    private val dailyPushCount = mutableMapOf<String, Int>()

    /** 基本面按需补拉：上次尝试时间，配合 [FundamentalHealthPolicy.needsFetch] 做失败重试节流。 */
    private val healthLastAttemptAt = mutableMapOf<String, Long>()
    private val healthInFlight =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    private var signalAlertDay: String? = null
    private val volumeBaseline = LinkedHashMap<String, Double>()

    /** 近 60 日价格区间，和均量一起每天取一次，供「位置」判定（见 PricePosition）。 */
    private val priceWindows = LinkedHashMap<String, PriceWindow>()
    private var volumeBaselineDay: String? = null
    private val volumeBaselineBusy = AtomicBoolean(false)
    private var volumeBaselineRetryAt = 0L
    private var lastPortfolioSlot: String? = null
    private var portfolioSlotInFlight: String? = null

    private data class PendingIntradaySignal(
        val type: IntradaySignalType,
        val rounds: Int,
    )

    private var lastLimitHour: Int = -1

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        store.lastStartupError?.let { startupError ->
            Repo.lastError = startupError
            Repo.statusText = startupError
            store.lastStartupError = null
        }
        screener = IndicatorScreener(sina)
        turnoverTracker = TurnoverTracker(store)
        indicatorScheduler = IndicatorRefreshScheduler(store)
        Notifier.ensureChannels(this)
        startForegroundCompat(Notifier.buildMonitorNotification(this, "股票监控", "正在加载行情…", null))
        Repo.summaryText = store.lastSummaryText
        // 持仓报告落盘过，重启后「查看持仓报告」不至于空着（正文里带分析时间）。
        Repo.portfolioReport = store.lastPortfolioReport
        Repo.strongContinuationEvents = StrongContinuationEvent.decode(store.strongContinuationEvents)
        // 状态快照同样落盘：没有它，重启后第一份报告写不出「较昨日」。
        Repo.portfolioSnapshot = PortfolioSnapshotJson.decode(store.portfolioSnapshot)
        // 复盘同样落盘，重启后仍能翻看昨天收盘时的那一份。
        Repo.dailyReviewText = store.lastDailyReviewText
        // 选股结果同样落盘：盘中不自动扫描后，重启靠它恢复，不再空白。
        // 超过保质期的组不回填——几天前的命中断章取义，不如空着等新扫描。
        ScreenResultJson.decode(store.lastScreenResult)?.let { cached ->
            val nowMs = System.currentTimeMillis()
            val keepIndicator = ScreenResultJson.isRetained(cached.indicatorAtMs, nowMs)
            val keepPattern = ScreenResultJson.isRetained(cached.patternAtMs, nowMs)
            if (keepIndicator) {
                Repo.screenIndicatorAt = cached.indicatorAt
                Repo.screenIndicatorAtMs = cached.indicatorAtMs
            }
            if (keepPattern) {
                Repo.screenPatternAt = cached.patternAt
                Repo.screenPatternAtMs = cached.patternAtMs
            }
            Repo.screenResult =
                when {
                    keepIndicator && keepPattern -> {
                        cached.result.copy(
                            trend = TrendScreenSelection.select(cached.result.trend),
                            relaxed = RelaxedScreenSelection.select(cached.result.relaxed),
                        )
                    }

                    keepIndicator -> {
                        ScreenResult(
                            cached.result.strict,
                            RelaxedScreenSelection.select(cached.result.relaxed),
                        )
                    }

                    keepPattern -> {
                        ScreenResult(
                            emptyList(),
                            emptyList(),
                            cached.result.fiveOneThree,
                            cached.result.fiveOneThreeStats,
                            TrendScreenSelection.select(cached.result.trend),
                        )
                    }

                    else -> {
                        ScreenResult(emptyList(), emptyList())
                    }
                }
        }
        // 先用落盘的概念板块榜垫上，避免服务刚起来时卡片显示“暂无数据”，等首轮刷新再覆盖。
        Repo.concepts = store.conceptBoards
        Repo.observationPool = store.observationPool
        Repo.observationSnapshots = store.observationSnapshots
        Repo.paperTrades = store.paperTrades
        Repo.statusText = "正在加载行情…"
        restoreVolumeBaseline()
        restoreQuoteCache()
        lastPortfolioSlot = store.portfolioSlot
        tickJob = scope.launch { runTickLoop() }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_REFRESH -> {
                scope.launch { tick(force = true) }
            }

            ACTION_SCREEN -> {
                scope.launch { runIndicatorScan() }
            }

            ACTION_SCREEN_FULL -> {
                scope.launch {
                    runPatternScan(manual = true, calendar = TimeUtils.nowCalendar())
                }
            }

            ACTION_OBSERVE_SCREEN -> {
                observeCurrentScreen()
            }

            ACTION_SUMMARY -> {
                scope.launch { generateSummary() }
            }

            // 手动重跑复盘走 AnalysisService.enqueueDailyReview（临时前台服务，不再拉起监控）。

            // 手动触发：不受盘中 30 分钟槽位限制，随时可以重新分析一次。
            ACTION_PORTFOLIO -> {
                scope.launch { runPortfolioAnalysis(MANUAL_SLOT) }
            }

            ACTION_STOP -> {
                store.monitorEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        scope.cancel()
        Repo.refreshing = false
        super.onDestroy()
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Log.w(TAG, "前台数据同步服务达到系统时限，准备安全停止：startId=$startId type=$fgsType")
        Repo.statusText = "后台监控达到系统时长限制，请重新打开应用继续"
        Repo.notifyChanged()
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifier.NOTIFICATION_ID_MONITOR,
                notification,
                ForegroundServicePolicy.activeTypeFor(Build.VERSION.SDK_INT),
            )
        } else {
            startForeground(Notifier.NOTIFICATION_ID_MONITOR, notification)
        }
    }

    private suspend fun runTickLoop() {
        while (scope.isActive) {
            runCatching { tick(force = false) }
            delay(TICK_INTERVAL_MS)
        }
    }

    /** 一轮刷新：先更新显示，再按交易时段决定是否请求新数据。 */
    private suspend fun tick(force: Boolean) {
        if (!tickBusy.compareAndSet(false, true)) return
        try {
            val calendar = TimeUtils.nowCalendar()
            when (TradingCalendar.tickPlan(firstLoadDone, force, calendar)) {
                // 与 macOS 版一致：非交易时段跳过行情刷新，但仍执行定时任务，
                // 否则 18:00-06:00 的夜间指标刷新与 18:00/21:00 的盘面总结永远不会触发。
                TickPlan.OFF_HOURS_TASKS -> {
                    launchNightScreenIfNeeded(calendar)
                    autoGenerateSummary(calendar)
                    autoDailyReview(calendar)
                    updateDisplay()
                    return
                }

                TickPlan.FETCH_AND_TASKS -> {
                    Unit
                }
            }
            Repo.refreshing = true
            Repo.statusText = "正在刷新行情…"
            Repo.notifyChanged()
            fetchData(calendar)
            // 「首轮加载完成」的真正含义是手里有行情了：拉到才算，
            // 否则空响应会让市场页一直「等待行情数据…」，且盘后不再重试。
            firstLoadDone = Repo.quotes.isNotEmpty()
            Repo.refreshing = false
            updateDisplay()
        } catch (error: Exception) {
            Log.e(TAG, "刷新失败", error)
            Repo.lastError = Errors.friendly(error)
            Repo.statusText = "刷新失败：${Errors.friendly(error)}"
            // 失败但手里已有行情（落盘缓存或早前拉到的）就不再在盘后逐轮打接口；
            // 盘中窗口由 tickPlan 的时段判断继续拉，不受这里影响。
            firstLoadDone = firstLoadDone || Repo.quotes.isNotEmpty()
        } finally {
            tickBusy.set(false)
            Repo.refreshing = false
            Repo.notifyChanged()
        }
    }

    private suspend fun fetchData(calendar: Calendar) {
        Repo.lastError = null
        val watchlist = store.watchlist
        Repo.watchlistOrder = watchlist.map { it.code to it.name }
        // 持仓股也要进拉取列表：不在自选里的持仓否则既没有盘中信号，也不会触发个股下探回升。
        val codes =
            (
                watchlist.map { it.code } + store.portfolio.positions.map { it.code } +
                    store.observationPool.filter { it.stage != ObservationStage.ELIMINATED }.map { it.code } + MARKET_INDEX_CODES +
                    // 板块龙头也要逐轮拿实时行情：靠它判封板/炸板，龙头一炸板做T提醒就恢复。
                    Repo.sectorLeaders.values.flatMap { leaders -> leaders.members.map { it.quoteCode } }
            ).distinct()
        if (codes.isNotEmpty()) {
            val incoming = sina.quotes(codes)
            val today = TimeUtils.todayText()
            // 显示口径：不比内存旧就收。盘后/周末/清晨新浪返回的是最近交易日的收盘快照，
            // 日期不是「今天」也照单全收——那正是市场页要显示的收盘数据；
            // 但旧行情绝不覆盖内存里更新的行情（防延迟响应把今日数据盖回昨天）。
            Repo.quotes = QuoteCache.mergeNewer(Repo.quotes, incoming)
            if (incoming.isNotEmpty()) {
                // 快照落盘：进程被杀后重启先垫上这份数据（见 restoreQuoteCache）。
                store.lastQuotes = QuoteCache.encode(QuoteCache.Snapshot(TimeUtils.nowText(), Repo.quotes))
                // 成交额与量比是展示口径，用合并后的行情算，夜间/周末重启也能出数。
                refreshTurnover(Repo.quotes, calendar)
                refreshVolumeMetrics(calendar)
            }
            // 盘中口径：预警、信号、分钟K线只认今天的行情，防旧行情触发盘中逻辑。
            val freshQuotes = incoming.filterValues { it.quoteDate == today }
            if (freshQuotes.isNotEmpty()) {
                checkDropAlerts(freshQuotes, calendar)
                checkMarketRiskAlert(freshQuotes, calendar)
                checkDipReboundAlerts(freshQuotes, calendar)
                // 反弹跟踪先于信号链跑：同一 tick 的接回/站回支撑要读它刚判出的「已企稳」。
                checkReboundTracking(calendar, freshQuotes)
                checkSealAlerts(calendar, freshQuotes)
                checkIntradaySignals(calendar, freshQuotes)
                // 风险卖出必须先于分钟K线网络请求送达；数据不全只降级普通操作，绝不延迟风险处置。
                refreshIntradayBars(freshQuotes, calendar)
                launchObservationPoolRefresh(freshQuotes)
            } else {
                Log.w(TAG, "今日行情未更新（today=$today），跳过盘中预警")
            }
        }

        if (++sectorTick >= SECTOR_INTERVAL_TICKS) {
            sectorTick = 0
            refreshSectors()
            refreshConcepts()
            ensureConceptCache()
            refreshLimits()
            // 龙头名单跟着板块数据一起定：盘中不变，逐轮扫成分股纯属浪费接口。
            refreshSectorLeaders()
        }
        if (++sectorFlowTick >= SECTOR_FLOW_INTERVAL_TICKS) {
            sectorFlowTick = 0
            refreshSectorFlows()
        }

        Repo.lastUpdateAt = TimeUtils.nowText()
        Repo.notifyChanged()

        // 选股盘中不自动刷新：需要时手动点（快扫指标 / 深扫全市场）。
        launchPortfolioIfNeeded(calendar)
        autoGenerateSummary(calendar)
        autoDailyReview(calendar)
    }

    /** 行业板块，等价于 get_sector_data + update_data 里的“保留上次有效数据”。 */
    private fun refreshSectors() {
        val fetched =
            runCatching {
                Http.retry(retries = 2, delayMs = 1_000, label = "行业板块", isValid = { it.all.isNotEmpty() }) {
                    sina.sectors()
                }
            }.getOrElse {
                Repo.lastError = "行业板块：${Errors.friendly(it)}"
                null
            }
        if (fetched != null) {
            Repo.sectors = fetched
            Repo.sectorTurnoverRatios = sectorTurnoverTracker.update(fetched.all, System.currentTimeMillis())
            Repo.sectorDataAsOfMs = System.currentTimeMillis()
        }
    }

    /** 资金流单独失败时保留上一次有效快照，不影响行业涨跌和已有提醒。 */
    private fun refreshSectorFlows() {
        val fetched =
            runCatching {
                Http.retry(retries = 2, delayMs = 1_000, label = "板块主力资金") {
                    eastMoney.industryBoardFlows().takeIf { it.isNotEmpty() }
                        ?: throw Http.HttpError("板块主力资金数据为空")
                }
            }.getOrElse {
                Log.w(TAG, "板块主力资金暂不可用：${Errors.friendly(it)}")
                return
            }
        Repo.sectorFlows = fetched
        Repo.sectorFlowAsOfMs = System.currentTimeMillis()
    }

    /**
     * 各持仓所属归类的「龙头」名单：拉一次板块成分股，按当日成交额取前 [SectorLeaders.SIZE] 名，
     * 再叠加你在「板块龙头」里的人工指定 / 剔除。
     *
     * 归类看人工修正（[BoardGroupBook]）：持仓挂靠优先于 F10 归属，归类内个股也按人工名单来
     * （F10 的行业粒度经常和实盘的「同板块」对不上，最终以人的判断为准）。自定义归类没有东财板块码，
     * 成分股就是人工名单本身，涨跌幅 / 成交额照样从实时行情取。
     *
     * **跟着板块数据刷新走（30 分钟一轮），不做盘中扫描**——名单盘中不会变，
     * 逐轮扫成分股纯属浪费接口。只拉持仓实际归属的那些归类。
     *
     * **只认龙头，跟风小票不算**：龙头封板才说明板块还有空间。成交额口径与两市成交额同源（新浪），
     * 不用东财市值字段：那份字段写这份代码时没在线核实过（接口当时拒连）。
     *
     * 强弱**不在这里判**：龙头的实时行情每 15 秒就有，见 [LeaderStrengthTracker.strongNames]，
     * 所以龙头一走弱下一轮 tick 就恢复做 T 提醒，不留刷新窗口。
     */
    private fun refreshSectorLeaders() {
        val book = store.boardGroups
        // 归类 id：持仓挂靠后的实际归属（人工优先，F10 兜底），加上纯自定义归类。
        val groupIds = LinkedHashSet<String>()
        store.portfolio.positions.forEach { position ->
            val f10Board = Repo.sectorContexts[position.code]?.industry?.boardCode
            book.groupIdOf(position.code, f10Board)?.let { groupIds.add(it) }
        }
        book.groups.values
            .filter { it.custom }
            .forEach { groupIds.add(it.id) }
        if (groupIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val overrides = store.sectorLeaderOverrides
        val leaders = LinkedHashMap(Repo.sectorLeaders)
        val membersByBoard = LinkedHashMap(Repo.sectorMembers)
        groupIds.forEach { groupId ->
            val group = book.groups[groupId]
            // 自定义归类没有东财板块码，成分就是人工名单；自动归类照拉东财成分——
            // 人工名单生效归生效（membersOf 按它挑龙头），但编辑器还要拿自动成分当候选用，
            // 勾掉的股票才勾得回来。
            val autoMembers =
                if (group?.custom == true) {
                    emptyList()
                } else {
                    runCatching { eastMoney.boardMembers(groupId) }.getOrNull().orEmpty()
                }
            val members = book.membersOf(groupId, autoMembers)
            val groupOverrides = overrides[groupId] ?: LeaderOverrides()
            val pinnedMembers =
                groupOverrides.pinned.map { (code, name) ->
                    BoardMember(code, name, 0.0)
                }
            val knownMembers = (members + pinnedMembers).distinctBy { it.code }
            if (knownMembers.isEmpty()) {
                // 人工名单已清空（个股删光）就把候选池也清掉，不然删掉的股一直占着编辑器列表；
                // 自动归类拉不到东财成分时不算清空，保留旧的等下轮网络恢复。
                if (group?.members != null) {
                    membersByBoard[groupId] = emptyList()
                    leaders.remove(groupId)
                }
                return@forEach
            }
            val quotes = runCatching { sina.quotes(knownMembers.map { it.quoteCode }) }.getOrNull().orEmpty()
            val turnovers = quotes.mapValues { it.value.turnover }
            // 一只都查不到成交额就别猜排名——保留旧的，或干脆没有，判定侧按「暂缺」处理。
            if (groupOverrides.pinned.isEmpty() && turnovers.values.none { it.isFinite() && it > 0 }) return@forEach
            // 编辑器候选用全集（自动成分 ∪ 人工名单），有效名单只用来挑龙头。
            membersByBoard[groupId] = (autoMembers + knownMembers).distinctBy { it.code }
            leaders[groupId] = SectorLeaders.of(knownMembers, turnovers, groupOverrides, now)
        }
        Repo.sectorMembers = membersByBoard
        Repo.sectorLeaders = leaders
    }

    /**
     * 概念板块，等价于 get_concept_data + “请求失败保留上次有效数据”。
     * 东方财富是主源；不可达时退回新浪概念板块（两套概念分类口径不同，界面会标注来源），
     * 两个源都失败才保留上一次结果。成功结果落盘，重启后不会空窗。
     */
    private fun refreshConcepts() {
        var failure: Throwable? = null
        val fromEastMoney =
            runCatching {
                Http.retry(retries = 2, delayMs = 1_000, label = "概念板块", isValid = { it.top.isNotEmpty() }) {
                    eastMoney.conceptBoards()
                }
            }.onFailure { failure = it }.getOrNull()

        val fetched = fromEastMoney ?: fetchConceptsFromSina()
        if (fetched == null) {
            Repo.lastError = "概念板块：${Errors.friendly(failure ?: Http.HttpError("两个数据源都取不到"))}"
            return
        }
        if (fromEastMoney == null) {
            val reason = failure?.let { Errors.friendly(it) } ?: "未知错误"
            Log.i(TAG, "概念板块：东方财富不可用（$reason），已用新浪备用口径")
        }
        val published = fetched.copy(stamp = TimeUtils.nowText())
        Repo.concepts = published
        store.conceptBoards = published
    }

    /** 新浪兜底：顺手填 conceptSummary，供盘面总结与涨跌停板块标注复用。 */
    private fun fetchConceptsFromSina(): ConceptBoards? {
        val summary = runCatching { sina.conceptSummary() }.getOrNull() ?: return null
        Repo.conceptSummary = summary
        return summary.boards(SinaClient.SECTOR_COUNT)
    }

    /** 与 macOS 版一致：统计涨跌停前先确保概念缓存已建立，用于标注所属板块。 */
    private fun ensureConceptCache() {
        if (Repo.conceptSummary != null) return
        runCatching { Repo.conceptSummary = sina.conceptSummary() }
    }

    /** 涨跌停统计，等价于 get_limit_stocks：结果为 0 时保留上次有效数据。 */
    private fun refreshLimits() {
        if (!store.limitScanEnabled) return
        val scanned =
            runCatching { sina.limitStocks() }.getOrElse {
                Repo.lastError = "涨跌停：${Errors.friendly(it)}"
                return
            }
        if (scanned.up.isEmpty() && scanned.down.isEmpty()) {
            Log.i(TAG, "涨跌停：结果为0，可能非交易时段，保留上次数据")
            return
        }
        val boardMap = buildBoardMap()
        Repo.limits =
            LimitData(
                up = scanned.up.map { it.copy(boards = boardMap[it.name].orEmpty()) },
                down = scanned.down.map { it.copy(boards = boardMap[it.name].orEmpty()) },
            )
        addLeaderCandidates(Repo.limits?.up.orEmpty())
    }

    /** 从概念缓存构建 {股票名: [板块1, 板块2, ...]}，等价于 _build_board_map。 */
    private fun buildBoardMap(): Map<String, List<String>> {
        val summary = Repo.conceptSummary ?: return emptyMap()
        val map = LinkedHashMap<String, MutableList<String>>()
        summary.sectors.forEach { sector ->
            if (sector.lead.isNotBlank() && sector.name.isNotBlank()) {
                map.getOrPut(sector.lead) { mutableListOf() }.add(sector.name)
            }
        }
        return map
    }

    /**
     * 自选股的「放量」标识与价格位置：20 日均量和近 60 日价格区间每天取一次（日线接口），
     * 实时成交量 / 现价每 15 秒变一次，所以量比与位置每轮 tick 重算，
     * 口径与个股报告里的「量比」完全一致（见 VolumeRatio、PricePosition）。
     */
    private fun refreshVolumeMetrics(
        calendar: Calendar,
        currentQuotes: Map<String, Quote> = Repo.quotes,
    ) {
        val codes =
            (
                watchCodes() +
                    store.portfolio.positions
                        .filter { it.quantity.isFinite() && it.quantity > 0 }
                        .map { it.code }
            ).distinct()
        if (codes.isEmpty()) return
        ensureVolumeBaseline(codes, currentQuotes)
        val elapsed = TradingCalendar.tradingMinutesElapsed(calendar)
        val today = TimeUtils.todayText()
        val ratios = LinkedHashMap<String, Double>()
        val contexts = LinkedHashMap<String, PriceContext>()
        codes.forEach { code ->
            val quote = currentQuotes[code] ?: return@forEach
            volumeBaseline[code]?.let { average ->
                // 行情日期不是今天时（周末/清晨拿到的是最近交易日收盘），成交量是那一整场的，
                // 按全天口径算，不能按墙钟折算——周六上午按「已走 30 分钟」折算会把量比放大约 8 倍。
                val quoteElapsed =
                    if (quote.quoteDate?.substringBefore(' ') == today) elapsed else VolumeRatio.TRADING_MINUTES
                VolumeRatio.of(quote.volume, average, quoteElapsed)?.let { ratios[code] = it }
            }
            priceWindows[code]?.contextOf(quote.price)?.let { contexts[code] = it }
        }
        Repo.volumeRatios = ratios.toMap()
        Repo.priceContexts = contexts.toMap()
    }

    /** 自选股里的个股（不含指数），与「市场」页自选股列表的口径一致。 */
    private fun watchCodes(): List<String> = store.watchlist.map { it.code }.filter { !it.startsWith("sh000") && !it.startsWith("sz399") }

    /** 服务重启后沿用当天已落盘的均量基线与价格区间，省掉一轮日线请求。 */
    private fun restoreVolumeBaseline() {
        val json = store.volumeBaseline
        val date = json.optString("date")
        if (date != TimeUtils.todayText()) return
        // 日线口径换代（新浪未复权 → 腾讯前复权）后，当天早先落盘的区间是旧口径，
        // 直接沿用会让除权股的位置判断继续用旧数据，所以按口径标记丢弃重拉。
        if (json.optString("source") != KLINE_SOURCE) return
        json.optJSONObject("avg")?.let { average ->
            average.keys().forEach { code ->
                val value = average.optDouble(code, 0.0)
                if (value > 0) volumeBaseline[code] = value
            }
        }
        json.optJSONObject("window")?.let { window ->
            window.keys().forEach { code ->
                val row = window.optJSONArray(code) ?: return@forEach
                val low = row.optDouble(0, 0.0)
                val high = row.optDouble(1, 0.0)
                val baseClose = row.optDouble(2, 0.0)
                if (low > 0 && high > low && baseClose > 0) {
                    priceWindows[code] = PriceWindow(low, high, baseClose)
                }
            }
        }
        volumeBaselineDay = date
    }

    /**
     * 重启后先用落盘的最近一份行情垫上，「市场」页开屏即有数（盘后/周末尤其需要），
     * 网络到位后由 tick 的新一轮用更新的响应覆盖。
     */
    private fun restoreQuoteCache() {
        val cache = QuoteCache.decode(store.lastQuotes) ?: return
        if (cache.quotes.isEmpty()) return
        Repo.quotes = cache.quotes
        Repo.lastUpdateAt = cache.savedAt
        val total = TURNOVER_CODES.sumOf { cache.quotes[it]?.turnover ?: 0.0 }
        if (total > 0) Repo.turnoverToday = total
        // 「较昨日同期」按快照里的交易日期从落盘采样重建：周末重启时墙钟是周六、
        // 采样挂在周五名下，不能拿「今天」去查。
        val sessionDate = cache.quotes["sh000001"]?.quoteDate?.substringBefore(' ')
            ?: cache.savedAt.substringBefore(' ')
        turnoverTracker?.restoreSnapshot(sessionDate)?.let { Repo.turnoverSnapshot = it }
    }

    /** 只在缺基线时拉日线；失败后隔 15 分钟再试，避免每 15 秒打一次接口。 */
    private fun ensureVolumeBaseline(codes: List<String>, currentQuotes: Map<String, Quote> = Repo.quotes) {
        val today = TimeUtils.todayText()
        if (volumeBaselineDay != today) {
            volumeBaseline.clear()
            priceWindows.clear()
            volumeBaselineDay = today
        }
        val missing = codes.filterNot { volumeBaseline.containsKey(it) }
        if (missing.isEmpty() || volumeBaselineBusy.get()) return
        val now = System.currentTimeMillis()
        if (now < volumeBaselineRetryAt) return
        if (!volumeBaselineBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val fetched = LinkedHashMap<String, Double>()
                val windows = LinkedHashMap<String, PriceWindow>()
                missing.forEach { code ->
                    // 70 根日线：够算 20 日均量，也够出近 60 日区间。
                    // 均量排除的是「行情标注的那一天」（分子的量来自那天），不是墙钟的今天：
                    // 周末/清晨行情是最近交易日的，排错日子会把那天的量同时算进分子和分母。
                    val bars = runCatching { sina.kline(code, 70) }.getOrNull() ?: return@forEach
                    val excludeDate =
                        currentQuotes[code]?.quoteDate?.substringBefore(' ') ?: TimeUtils.todayText()
                    VolumeRatio
                        .averageVolumeExcludingToday(bars, excludeDate)
                        ?.takeIf { it > 0 }
                        ?.let { fetched[code] = it }
                    PricePosition.of(bars, TimeUtils.todayText())?.let { windows[code] = it }
                }
                if (fetched.isNotEmpty() || windows.isNotEmpty()) {
                    volumeBaseline.putAll(fetched)
                    priceWindows.putAll(windows)
                    store.volumeBaseline =
                        JSONObject()
                            .put("date", TimeUtils.todayText())
                            .put("source", KLINE_SOURCE)
                            .put("avg", JSONObject().apply { fetched.forEach { (code, value) -> put(code, value) } })
                            .put(
                                "window",
                                JSONObject().apply {
                                    windows.forEach { (code, value) ->
                                        put(code, JSONArray().put(value.low).put(value.high).put(value.baseClose))
                                    }
                                },
                            )
                    Repo.notifyChanged()
                }
                // 全部拿到就不用再拉；有缺的或失败的隔 15 分钟重试。
                volumeBaselineRetryAt =
                    if (fetched.size >= missing.size) {
                        0L
                    } else {
                        System.currentTimeMillis() + VOLUME_BASELINE_RETRY_MS
                    }
            } finally {
                volumeBaselineBusy.set(false)
            }
        }
    }

    /** 个股风险预警：日内累计跌幅分级，另保留较上次刷新急跌提醒。 */
    private fun checkDropAlerts(
        quotes: Map<String, Quote>,
        calendar: Calendar,
    ) {
        val heldCodes = StockAlertScope.heldCodes(store.portfolio.positions)
        previousPrices.keys.retainAll(heldCodes)
        stockDropAlertLevels.keys.retainAll(heldCodes)
        stockDropAlertLastAt.keys.retainAll(heldCodes)
        val today = TimeUtils.todayText()
        if (stockDropAlertDay != today) {
            stockDropAlertDay = today
            stockDropAlertLevels.clear()
            stockDropAlertLastAt.clear()
        }
        if (!store.alertsEnabled || !TradingCalendar.inMarketHoursForAlert(calendar)) {
            previousPrices.putAll(quotes.filterKeys { it in heldCodes }.mapValues { it.value.price })
            return
        }
        val threshold = store.dropAlertPct
        val now = System.currentTimeMillis()
        quotes.forEach { (code, quote) ->
            if (code in MARKET_INDEX_CODES || code !in heldCodes) return@forEach
            val previous = previousPrices[code]
            val diffPct =
                if (previous != null && previous > 0) {
                    (quote.price - previous) / previous * 100
                } else {
                    null
                }
            val dailyLevel = StockRiskPolicy.levelFor(quote.changePct)
            val previousDailyLevel = stockDropAlertLevels[code] ?: StockDropLevel.NONE
            val dailyEscalated = dailyLevel.priority > previousDailyLevel.priority
            val instantDrop = diffPct != null && diffPct <= -threshold
            val instantAllowed = now - (stockDropAlertLastAt[code] ?: 0L) >= STOCK_DROP_ALERT_COOLDOWN_MS
            if (dailyEscalated || (instantDrop && instantAllowed)) {
                val text =
                    if (dailyEscalated) {
                        String.format(
                            Locale.CHINA,
                            "日内较昨收下跌 %.2f%%，现价 %.2f%s",
                            -quote.changePct,
                            quote.price,
                            if (dailyLevel == StockDropLevel.DANGER) "，已进入风险区间" else "",
                        )
                    } else {
                        String.format(Locale.CHINA, "较上次刷新下跌 %.2f%%，现价 %.2f", -diffPct!!, quote.price)
                    }
                val title =
                    when {
                        dailyLevel == StockDropLevel.DANGER -> "🚨 ${quote.name} 日内跌幅风险"
                        dailyEscalated -> "⚠️ ${quote.name} 日内跌幅预警"
                        else -> "⚡ ${quote.name} 急跌预警"
                    }
                Notifier.alert(this, ALERT_ID_DROP + code.hashCode(), title, text)
                stockDropAlertLastAt[code] = now
            }
            if (dailyLevel != StockDropLevel.NONE) {
                stockDropAlertLevels[code] =
                    if (dailyLevel.priority > previousDailyLevel.priority) dailyLevel else previousDailyLevel
            } else {
                stockDropAlertLevels.remove(code)
            }
            previousPrices[code] = quote.price
        }
    }

    /**
     * 大盘风险预警：使用指数相对昨收的日内跌幅，而不是相邻两轮的跌幅。
     * 这样全天缓慢下跌到危险区间时也能提醒；同一轮下跌只提醒一次。
     */
    private fun checkMarketRiskAlert(
        quotes: Map<String, Quote>,
        calendar: Calendar,
    ) {
        if (!store.alertsEnabled || !TradingCalendar.inMarketHoursForAlert(calendar)) {
            marketAlertEngine.reset()
            return
        }
        val timestamp = System.currentTimeMillis()
        val turnoverRatio = marketTurnoverRatio(quotes, calendar)
        val snapshots =
            MARKET_INDEX_CODES.mapNotNull { code ->
                quotes[code]?.let { quote ->
                    MarketAlertEngine.IndexSnapshot(
                        code = quote.code,
                        name = quote.name,
                        price = quote.price,
                        changePct = quote.changePct,
                        turnover = quote.turnover,
                        timestamp = timestamp,
                        turnoverRatio = turnoverRatio,
                    )
                }
            }
        marketAlertEngine.update(snapshots, TimeUtils.todayText()).forEach { alert ->
            if (alert.vibrate) {
                Notifier.marketRisk(this, ALERT_ID_MARKET_RISK, alert.title, alert.body)
            } else {
                Notifier.alert(this, ALERT_ID_MARKET_WEAKNESS, alert.title, alert.body)
            }
        }
    }

    /**
     * 下探回升提醒：大盘用两市成交额口径（深套补仓 / 做 T 的观察窗口），个股只看持仓、
     * 用量比口径。放量收复才震动——没量的回升只是超跌反弹，见 [DipReboundEngine]。
     */
    private fun checkDipReboundAlerts(
        quotes: Map<String, Quote>,
        calendar: Calendar,
    ) {
        if (!store.alertsEnabled || !TradingCalendar.inMarketHoursForAlert(calendar)) {
            dipReboundEngine.reset()
            return
        }
        val today = TimeUtils.todayText()
        val timestamp = System.currentTimeMillis()
        val turnoverRatio = marketTurnoverRatio(quotes, calendar)
        val samples = mutableListOf<DipReboundEngine.Sample>()
        MARKET_INDEX_CODES.forEach { code ->
            quotes[code]?.let { quote ->
                samples +=
                    DipReboundEngine.Sample(
                        code = quote.code,
                        name = quote.name,
                        price = quote.price,
                        changePct = quote.changePct,
                        volumeRatio = turnoverRatio,
                        cumulativeTurnover = quote.turnover,
                        timestamp = timestamp,
                        stock = false,
                    )
            }
        }
        // 个股下探回升是补仓 / 做 T 类提示，和买卖点共用 intradaySignalEnabled 开关。
        if (store.intradaySignalEnabled) {
            val positionCodes = StockAlertScope.heldCodes(store.portfolio.positions)
            quotes.forEach { (code, quote) ->
                if (code in MARKET_INDEX_CODES || code !in positionCodes) return@forEach
                samples +=
                    DipReboundEngine.Sample(
                        code = quote.code,
                        name = quote.name,
                        price = quote.price,
                        changePct = quote.changePct,
                        volumeRatio = Repo.volumeRatios[code],
                        cumulativeTurnover = quote.turnover,
                        timestamp = timestamp,
                        atrPct =
                            Repo.portfolioResults
                                .firstOrNull { it.code == code }
                                ?.volatility
                                ?.atrPct,
                        stock = true,
                    )
            }
        }
        dipReboundEngine.update(samples, today).forEach { alert ->
            if (alert.stock && alert.code !in StockAlertScope.heldCodes(store.portfolio.positions)) return@forEach
            if (!consumePushBudget(alert.code)) return@forEach
            val id = ALERT_ID_REBOUND + alert.code.hashCode()
            when {
                !alert.vibrate -> Notifier.alert(this, id, alert.title, alert.body)
                alert.stock -> Notifier.signal(this, id, alert.title, alert.body)
                else -> Notifier.marketRisk(this, id, alert.title, alert.body)
            }
        }
    }

    /**
     * 反弹质量跟踪：深砸回升段的「企稳确认 / 诱多警示」（见 [ReboundTracker]）。
     *
     * 覆盖持仓 + 自选股 + 观察池：非持仓的票给「关注/别追」类提示，防诱多也管到想进的标的。
     * 与买卖点提示共用 [intradaySignalEnabled] 开关和每日推送预算；确认结果同 tick 先于
     * 信号链写好，接回 / 站回支撑（见 [IntradayTactics]）拿它当确认源。
     */
    private fun checkReboundTracking(
        calendar: Calendar,
        currentQuotes: Map<String, Quote>,
    ) {
        if (!store.alertsEnabled || !TradingCalendar.inMarketHoursForAlert(calendar)) {
            reboundTracker.reset()
            return
        }
        if (!store.intradaySignalEnabled) return
        val heldCodes = StockAlertScope.heldCodes(store.portfolio.positions)
        val watchCodes = store.watchlist
            .map { it.code }
            .filter { !it.startsWith("sh000") && !it.startsWith("sz399") }
        val observationCodes = store.observationPool
            .filter { it.stage != ObservationStage.ELIMINATED }
            .map { it.code }
        val codes = (heldCodes + watchCodes + observationCodes).distinct()
        if (codes.isEmpty()) return
        val today = TimeUtils.todayText()
        val timestamp = System.currentTimeMillis()
        val levels = Repo.portfolioResults.associateBy { it.code }
        val samples = codes.mapNotNull { code ->
            val quote = currentQuotes[code] ?: return@mapNotNull null
            ReboundTracker.Sample(
                code = code,
                name = quote.name,
                price = quote.price,
                changePct = quote.changePct,
                volumeRatio = Repo.volumeRatios[code],
                cumulativeTurnover = quote.turnover,
                timestamp = timestamp,
                atrPct = levels[code]?.volatility?.atrPct,
                resistance = levels[code]?.resistance ?: 0.0,
                held = code in heldCodes,
            )
        }
        reboundTracker.update(samples, today).forEach { event ->
            if (!consumePushBudget(event.code)) return@forEach
            val id = ALERT_ID_REBOUND_TRACK + event.code.hashCode()
            if (event.vibrate) {
                Notifier.signal(this, id, event.title, event.body)
            } else {
                Notifier.alert(this, id, event.title, event.body)
            }
        }
    }

    /**
     * 封板冲刺预警 + 封板/炸板提醒（见 [SealSprintTracker] / [LimitSealTracker]）。
     *
     * 覆盖持仓 + 自选股 + 观察池。封板是持仓事实级事件——此前封板/炸板只给板块龙头的
     * 做T闸门和页面展示用，持仓封板本身从不推送，只能靠盯盘发现。按跌预警同口径
     * 只看 alertsEnabled，不占共享推送预算：事实不能被机会类预算吞掉。
     */
    private fun checkSealAlerts(
        calendar: Calendar,
        currentQuotes: Map<String, Quote>,
    ) {
        if (!store.alertsEnabled || !TradingCalendar.inMarketHoursForAlert(calendar)) {
            sealSprintTracker.reset()
            limitSealTracker.reset()
            return
        }
        val heldCodes = StockAlertScope.heldCodes(store.portfolio.positions)
        val watchCodes = store.watchlist
            .map { it.code }
            .filter { !it.startsWith("sh000") && !it.startsWith("sz399") }
        val observationCodes = store.observationPool
            .filter { it.stage != ObservationStage.ELIMINATED }
            .map { it.code }
        val codes = (heldCodes + watchCodes + observationCodes).distinct()
        if (codes.isEmpty()) return
        val today = TimeUtils.todayText()
        val timestamp = System.currentTimeMillis()
        val quotes = codes.mapNotNull { code -> currentQuotes[code]?.let { code to it } }

        sealSprintTracker.update(
            quotes.map { (code, quote) ->
                SealSprintTracker.Sample(
                    code = code,
                    name = quote.name,
                    price = quote.price,
                    prevClose = quote.prevClose,
                    high = quote.high,
                    volume = quote.volume,
                    limitPct = SinaClient.limitPctFor(code, quote.name),
                    timestamp = timestamp,
                    held = code in heldCodes,
                )
            },
            today,
        ).forEach { event ->
            Notifier.signal(this, ALERT_ID_SEAL_SPRINT + event.code.hashCode(), event.title, event.body)
        }

        limitSealTracker.update(
            quotes.map { (code, quote) ->
                LimitSealTracker.Sample(
                    code = code,
                    name = quote.name,
                    price = quote.price,
                    prevClose = quote.prevClose,
                    limitPct = SinaClient.limitPctFor(code, quote.name),
                    buy1Volume = quote.buy1Volume,
                    timestamp = timestamp,
                    held = code in heldCodes,
                )
            },
            today,
        ).forEach { event ->
            val id = ALERT_ID_SEAL + event.code.hashCode()
            if (event.vibrate) {
                Notifier.signal(this, id, event.title, event.body)
            } else {
                Notifier.alert(this, id, event.title, event.body)
            }
        }
    }

    /**
     * 同一代码同一天的「可操作」提醒（买卖点、下探回升）共用 [SHARED_PUSH_MAX_PER_DAY] 条
     * 预算：几套策略各自有冷却和日限，但叠加起来仍可能一天十几条，这里给个合并上限。
     * 大盘指数类提醒不占个股预算（主体不同）。
     */
    private fun consumePushBudget(code: String): Boolean {
        if (code in MARKET_INDEX_CODES) return true
        val today = TimeUtils.todayText()
        if (pushBudgetDay != today) {
            pushBudgetDay = today
            dailyPushCount.clear()
        }
        val used = dailyPushCount[code] ?: 0
        if (used >= SHARED_PUSH_MAX_PER_DAY) return false
        dailyPushCount[code] = used + 1
        return true
    }

    /**
     * 计算当前时刻相对历史同期成交额中位数的倍数。
     * 历史侧按同一钟表时刻取数，和当前实时累计对齐，不用向下取整的槽位起点。
     */
    private fun marketTurnoverRatio(
        quotes: Map<String, Quote>,
        calendar: Calendar,
    ): Double? {
        if (TURNOVER_CODES.any { quotes[it] == null }) return null
        val total = TURNOVER_CODES.sumOf { quotes[it]?.turnover ?: 0.0 }
        if (TradingCalendar.turnoverSlotMinute(calendar) == null) return null
        return turnoverTracker?.sameTimeRatio(TimeUtils.todayText(), TimeUtils.minuteOfDay(calendar), total)
    }

    private fun refreshIntradayBars(
        currentQuotes: Map<String, Quote>,
        calendar: Calendar,
    ) {
        val heldCodes = StockAlertScope.heldCodes(store.portfolio.positions)
        val now = System.currentTimeMillis()
        val today = TimeUtils.todayText()
        for (code in heldCodes) {
            val lastFetchedAt = intradayLastFetchAtMs[code] ?: 0L
            if (lastFetchedAt == 0L || now - lastFetchedAt >= MINUTE_KLINE_REFRESH_MS) {
                intradayLastFetchAtMs[code] = now
                runCatching { sina.intradayKline(code, 15, MINUTE_KLINE_LENGTH) }
                    .onSuccess { bars ->
                        intraday15ByCode[code] = IntradayBarWindow(bars.filter { it.timestamp.startsWith(today) })
                    }
                    .onFailure { Log.w(TAG, "15分钟K线暂不可用：code=$code", it) }
                runCatching { sina.intradayKline(code, 30, MINUTE_KLINE_LENGTH) }
                    .onSuccess { bars ->
                        intraday30ByCode[code] = IntradayBarWindow(bars.filter { it.timestamp.startsWith(today) })
                    }
                    .onFailure { Log.w(TAG, "30分钟K线暂不可用：code=$code", it) }
            }

            val quote = currentQuotes[code]?.takeIf { it.quoteDate == today } ?: continue
            intraday15ByCode.computeIfAbsent(code) { IntradayBarWindow(emptyList()) }
                .let { window ->
                    intraday15ByCode[code] = upsertLiveBar(window, quote, alignedTimestamp(calendar, 15))
                }
            intraday30ByCode.computeIfAbsent(code) { IntradayBarWindow(emptyList()) }
                .let { window ->
                    intraday30ByCode[code] = upsertLiveBar(window, quote, alignedTimestamp(calendar, 30))
                }
        }
    }

    private fun upsertLiveBar(
        window: IntradayBarWindow,
        quote: Quote,
        timestamp: String,
    ): IntradayBarWindow {
        val current = window.bars.firstOrNull { it.timestamp == timestamp }
        val live = com.example.stockmonitor.core.IntradayBar(
            timestamp = timestamp,
            open = current?.open ?: quote.price,
            high = maxOf(current?.high ?: quote.price, quote.price),
            low = minOf(current?.low ?: quote.price, quote.price),
            close = quote.price,
            volume = current?.volume ?: 0.0,
        )
        return if (live.valid) window.upsert(live) else window
    }

    private fun alignedTimestamp(calendar: Calendar, minutes: Int): String {
        val local = calendar.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        val alignedMinute = local.minute / minutes * minutes
        return local
            .withMinute(alignedMinute)
            .withSecond(0)
            .withNano(0)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    /**
     * 盘中买卖点 / 做 T 提醒。
     *
     * 判定逻辑在 IntradayTactics（纯函数，另有单测）里：支撑 / 压力 / 评分取最近一次持仓分析的
     * 结果，实时价与量比取本轮行情刷新，所以 15 秒一轮也不会额外打接口。
     *
     * 防打扰三层：只在「形态发生变化」时提醒（而不是条件满足就一直提醒）；同一只股票同一类信号
     * 10 分钟内不重复；机会提示每只股票每天最多 8 条，风险升级不受机会次数限制。
     */
    private fun checkIntradaySignals(
        calendar: Calendar,
        currentQuotes: Map<String, Quote> = Repo.quotes,
    ) {
        if (!store.alertsEnabled) {
            HoldingAnalyzer.holdingBreakTracker.clear()
            return
        }
        val portfolio = store.portfolio
        val heldCodes = StockAlertScope.heldCodes(portfolio.positions)
        HoldingAnalyzer.holdingTTracker.retainCodes(heldCodes)
        HoldingAnalyzer.holdingBreakTracker.retainCodes(heldCodes)
        signalAlertActive.keys.retainAll(heldCodes)
        signalAlertPending.keys.retainAll(heldCodes)
        supportBreakStates.keys.retainAll(heldCodes)
        if (heldCodes.isEmpty()) return
        if (!TradingCalendar.inMarketHoursForAlert(calendar)) return
        val today = TimeUtils.todayText()
        val tradeBook = store.tradeBook.takeIf { it.lots.isNotEmpty() || it.entries.isNotEmpty() }
            ?: TradeLotLedger.migrate(portfolio.positions).also { store.tradeBook = it }
        val availabilityByCode = TradeLotLedger
            .availability(tradeBook, portfolio.positions, today)
            .associateBy { it.code }
        if (signalAlertDay != today) {
            signalAlertDay = today
            signalAlertActive.clear()
            signalAlertPending.clear()
            signalAlertLastAt.clear()
            signalAlertCount.clear()
            supportBreakStates.clear()
            HoldingAnalyzer.holdingTTracker.clear()
            HoldingAnalyzer.holdingBreakTracker.clear()
            leaderStrengthTracker.clear()
        }
        val levels = Repo.portfolioResults.associateBy { it.code }
        val boardGroups = store.boardGroups
        val now = System.currentTimeMillis()
        val adaptiveMarketTurnoverRatio = marketTurnoverRatio(currentQuotes, calendar)
        for (position in portfolio.positions) {
            if (position.code !in heldCodes || !position.quantity.isFinite() || position.quantity <= 0) continue
            val quote = currentQuotes[position.code] ?: continue
            val tObservation = HoldingAnalyzer.holdingTTracker.observe(quote, today, now)
            if (quote.quoteDate != today) {
                signalAlertPending.remove(position.code)
                continue
            }
            updateStrongContinuationEventCurrentPrice(position.code, quote.price)
            val level = levels[position.code]
            val breakObservation =
                HoldingAnalyzer.holdingBreakTracker.observe(
                    quote,
                    level?.support ?: 0.0,
                    level?.volatility?.atrPct,
                    today,
                    now,
                )
            val sectorContext = Repo.sectorContexts[position.code]
            val sectorChange =
                Repo.sectorContexts[position.code]
                    ?.industry
                    ?.name
                    ?.let { Repo.sectorChangeByName(it) }
                    ?.takeIf { change ->
                        val asOf = change.asOfMs ?: return@takeIf false
                        now - asOf in 0..SECTOR_DATA_MAX_AGE_MS
                    }
            val sectorFlow =
                sectorContext
                    ?.industry
                    ?.boardCode
                    ?.let { Repo.sectorFlows[it] }
                    ?.takeIf { now - (Repo.sectorFlowAsOfMs ?: return@takeIf false) in 0..SECTOR_DATA_MAX_AGE_MS }
            // 同板块龙头是否仍强（封板，或炸板守住高位线）：用**实时行情**判，龙头一走弱下一轮 tick
            // 就恢复做T，不留刷新窗口。名单本身过期则当暂缺（null），宁可照常提醒减仓，也不拿旧名单压掉提示。
            // 归属到哪个组看人工归类：挂靠优先，其次 F10 板块码（见 BoardGroupBook.groupIdOf）。
            val groupId = boardGroups.groupIdOf(position.code, sectorContext?.industry?.boardCode)
            val strongLeaders =
                groupId
                    ?.let { Repo.sectorLeaders[it] }
                    ?.takeIf { now - it.asOfMs in 0..SECTOR_LEADERS_MAX_AGE_MS }
                    ?.let { leaderStrengthTracker.strongNames(it.members, currentQuotes, now) }
            val turnoverSnapshot = Repo.turnoverSnapshot
            val recentTurnovers = turnoverTracker?.recentFullDayTurnoversYi(TimeUtils.todayText()) ?: emptyList()
            val allSectors = Repo.sectors?.all.orEmpty()
            val industryTotal = allSectors.size
            val industryBreadth =
                allSectors
                    .takeIf { industryTotal > 0 }
                    ?.count { it.change > 0 }
                    ?.toDouble()
                    ?.div(industryTotal)
            val concept = Repo.conceptSummary
            val conceptTotal = concept?.let { if (it.sectors.isNotEmpty()) it.sectors.size else it.rising + it.falling } ?: 0
            val conceptBreadth =
                concept?.takeIf { conceptTotal > 0 }?.let {
                    (if (it.sectors.isNotEmpty()) it.sectors.count { item -> item.change > 0 } else it.rising).toDouble() / conceptTotal
                }
            // 支撑状态机：破过位、之后又站回过，用于区分「首次破位 / 站回 / 反抽失败」。
            val breakFlags = supportBreakStates.getOrPut(position.code) { SupportBreakFlags() }
            val belowSupport =
                level != null && level.support > 0 &&
                    quote.price < level.support * (1 - IntradayTactics.BREAK_TOLERANCE)
            if (belowSupport) {
                breakFlags.broken = true
            } else if (breakFlags.broken && level != null && level.support > 0 && quote.price >= level.support) {
                breakFlags.reclaimed = true
            }
            val marketHealthy = Repo.marketState?.regime?.let {
                it != MarketRegime.WEAK && it != MarketRegime.COLLAPSE
            } == true
            val sectorHealthy = sectorContext?.state?.regime?.let {
                it != MarketRegime.WEAK && it != MarketRegime.COLLAPSE
            } == true
            val highPositionDecision = level?.highPositionEvidence?.let {
                HighPositionPolicy.evaluate(
                    it.copy(
                        marketHealthy = marketHealthy,
                        sectorHealthy = sectorHealthy,
                        dualTimeframeAllowed = true,
                    ),
                )
            } ?: level?.highPosition
            val tacticsInput =
                IntradayTactics.Input(
                    code = position.code,
                    name = position.name,
                    price = quote.price,
                    changePct = quote.changePct,
                    volumeRatio = Repo.volumeRatios[position.code],
                    limitPct = SinaClient.limitPctFor(position.code, position.name),
                    support = level?.support ?: 0.0,
                    resistance = level?.resistance ?: 0.0,
                    score = level?.score ?: 0,
                    quantity = position.quantity,
                    cost = position.cost,
                    totalCapital = portfolio.totalCapital,
                    availableCapital = portfolio.resolvedAvailableCapital(),
                    initialEntryPct = portfolio.initialEntryPct,
                    maxPositionPct = portfolio.maxPositionPct,
                    sellableQuantity = availabilityByCode[position.code]?.sellableToday,
                    lockedQuantity = availabilityByCode[position.code]?.lockedToday ?: 0.0,
                    untrackedQuantity = availabilityByCode[position.code]?.untrackedQuantity ?: 0.0,
                    reboundConfirmed = reboundTracker.isConfirmed(position.code),
                    entrySetup = level?.entrySetup,
                    ma20 = level?.ma20 ?: 0.0,
                    ma5 = level?.ma5 ?: 0.0,
                    fundamentalHealth =
                        Repo.fundamentalHealth[position.code]
                            ?.takeIf { now - it.fetchedAtMs in 0..FundamentalHealthPolicy.CACHE_TTL_MS }
                            ?.health,
                    atrPct = level?.volatility?.atrPct,
                    brokenSupport = breakFlags.broken,
                    reclaimedSupport = breakFlags.reclaimed,
                    sectorChangePct = sectorChange?.pct,
                    sectorTurnoverRatio = sectorChange?.turnoverRatio,
                    sectorMainNetInflow = sectorFlow?.mainNetInflow,
                    sectorMainNetRatio = sectorFlow?.mainNetRatio,
                    strongLeaderCount = strongLeaders?.size,
                    strongLeaderNames = strongLeaders.orEmpty(),
                    todayTurnoverYi = quote.turnover.takeIf { it.isFinite() && it >= 0 }?.div(100_000_000.0),
                    predictedMarketTurnoverYi = turnoverSnapshot?.predictedFullDay?.div(100_000_000.0),
                    marketChangePct = currentQuotes["sh000001"]?.changePct,
                    dailyHighPrice = quote.high,
                    marketIndexPrice = currentQuotes["sh000001"]?.price,
                    marketIndexHighPrice = currentQuotes["sh000001"]?.high,
                    industryBreadth = industryBreadth,
                    conceptBreadth = conceptBreadth,
                    recentMarketTurnoversYi = recentTurnovers,
                    marketTurnoverRatio = adaptiveMarketTurnoverRatio,
                    impulse = level?.impulse,
                    marketRegime = Repo.marketState?.regime ?: MarketRegime.NEUTRAL,
                    sectorRegime = sectorContext?.state?.regime,
                    holdingTContext = holdingTContexts[position.code]?.takeIf { it.isFresh(today, now) },
                    holdingTObservation = tObservation,
                    breakObservation = breakObservation,
                    openPrice = quote.open,
                    previousClose = quote.prevClose,
                    priceContext = priceWindows[position.code]?.takeIf { volumeBaselineDay == today }?.contextOf(quote.price),
                    trendAssessment = level?.trendAssessment,
                    boxRange = level?.boxRange,
                    highPosition = highPositionDecision,
                )
            val evaluatedSignal = IntradayTactics.evaluate(tacticsInput)
            maybeRefreshFundamentalHealth(
                code = position.code,
                name = position.name,
                price = quote.price,
                cost = position.cost,
                support = level?.support ?: 0.0,
            )
            if (evaluatedSignal == null) {
                // 条件不再成立：清掉形态和未完成的确认，下次再触发才会重新提醒。
                signalAlertActive.remove(position.code)
                signalAlertPending.remove(position.code)
                continue
            }
            var signal: IntradaySignal = evaluatedSignal
            var timeframeReason: String? = null
            if (signal.type.requiresDualTimeframe) {
                val decision = DualTimeframePolicy.decide(
                    riskSignal = signal.type.isRisk,
                    execution = intraday15ByCode[position.code] ?: IntradayBarWindow(emptyList()),
                    direction = intraday30ByCode[position.code] ?: IntradayBarWindow(emptyList()),
                )
                if (!decision.allowed) {
                    timeframeReason = decision.reason
                    signal = IntradayTactics.watchOnly(signal, decision)
                }
            }
            if (!signal.type.isRisk && !store.intradaySignalEnabled) {
                signalAlertPending.remove(position.code)
                continue
            }

            val pending = signalAlertPending[position.code]
            val rounds = if (pending?.type == signal.type) pending.rounds + 1 else 1
            val requiredRounds = IntradayTactics.confirmationRounds(signal.type)
            if (rounds < requiredRounds) {
                signalAlertPending[position.code] = PendingIntradaySignal(signal.type, rounds)
                continue
            }
            signalAlertPending.remove(position.code)
            if (signalAlertActive[position.code] == signal.type.name) continue
            val key = position.code + "|" + signal.type.name
            if (!signal.type.isRisk && now - (signalAlertLastAt[key] ?: 0L) < SIGNAL_COOLDOWN_MS) continue
            val fired = signalAlertCount[position.code] ?: 0
            if (!signal.type.isRisk && fired >= SIGNAL_MAX_PER_DAY) continue
            signalAlertActive[position.code] = signal.type.name
            signalAlertLastAt[key] = now
            if (!signal.type.isRisk) signalAlertCount[position.code] = fired + 1
            if (signal.type == IntradaySignalType.T_HOLD || signal.type == IntradaySignalType.T_HOLD_CAUTION) {
                val fallbackTurnover = MarketTurnoverFallbackPolicy.evaluate(
                    predictedTurnoverYi = turnoverSnapshot?.predictedFullDay?.div(100_000_000.0),
                    recentTurnoversYi = recentTurnovers,
                )
                val environmentSource = when {
                    adaptiveMarketTurnoverRatio != null -> MarketTurnoverSource.SAME_TIME
                    fallbackTurnover != null -> fallbackTurnover.source
                    else -> null
                }
                val environment =
                    MarketEnvironmentQuality.evaluateAdaptive(
                        marketTurnoverRatio = adaptiveMarketTurnoverRatio ?: fallbackTurnover?.ratio,
                        marketTurnoverSource = environmentSource,
                        marketChangePct = currentQuotes["sh000001"]?.changePct,
                        marketPrice = currentQuotes["sh000001"]?.price,
                        marketHighPrice = currentQuotes["sh000001"]?.high,
                        industryBreadth = industryBreadth,
                        conceptBreadth = conceptBreadth,
                    )
                recordStrongContinuationEvent(
                    position = position,
                    quote = quote,
                    marketChangePct = currentQuotes["sh000001"]?.changePct,
                    sectorChangePct = sectorChange?.pct,
                    predictedTurnoverYi = turnoverSnapshot?.predictedFullDay?.div(100_000_000.0),
                    environmentStatus = environment.status.label,
                    environmentReasons = environment.reasons,
                )
            }
            // 风险提醒（破位）永远发、不占预算：预算只挡机会类提示，绝不能把风险提示吞掉。
            val riskSignal = signal.type.isRisk
            if (riskSignal || (store.intradaySignalEnabled && consumePushBudget(position.code))) {
                if (position.code !in StockAlertScope.heldCodes(store.portfolio.positions)) continue
                val body = TradeNotification.intraday(tacticsInput, signal, timeframeReason)
                if (riskSignal) {
                    Notifier.riskSell(this, ALERT_ID_SIGNAL + key.hashCode(), signal.title, body)
                } else {
                    Notifier.signal(this, ALERT_ID_SIGNAL + key.hashCode(), signal.title, body)
                }
            }
        }
    }

    private fun recordStrongContinuationEvent(
        position: Position,
        quote: Quote,
        marketChangePct: Double?,
        sectorChangePct: Double?,
        predictedTurnoverYi: Double?,
        environmentStatus: String,
        environmentReasons: List<String>,
    ) {
        val turnover = predictedTurnoverYi ?: return
        val marketChange = marketChangePct ?: return
        val sectorChange = sectorChangePct ?: return
        if (!turnover.isFinite() || !marketChange.isFinite() || !sectorChange.isFinite()) return
        val today = TimeUtils.todayText()
        val now = TimeUtils.nowText()
        val events = Repo.strongContinuationEvents.toMutableList()
        val index = events.indexOfFirst { it.date == today && it.code == position.code }
        if (index >= 0) {
            events[index] = events[index].withObservation(quote.price, now)
        } else {
            events +=
                StrongContinuationEvent(
                    date = today,
                    code = position.code,
                    name = position.name,
                    triggeredAt = now,
                    triggerPrice = quote.price,
                    currentPrice = quote.price,
                    predictedTurnoverYi = turnover,
                    marketChangePct = marketChange,
                    sectorChangePct = sectorChange,
                    environmentStatus = environmentStatus,
                    environmentReasons = environmentReasons,
                )
        }
        Repo.strongContinuationEvents = events
        store.strongContinuationEvents = StrongContinuationEvent.encode(events)
    }

    private fun updateStrongContinuationEventCurrentPrice(
        code: String,
        currentPrice: Double,
    ) {
        val today = TimeUtils.todayText()
        val index = Repo.strongContinuationEvents.indexOfFirst { it.date == today && it.code == code }
        if (index < 0) return
        val events = Repo.strongContinuationEvents.toMutableList()
        events[index] = events[index].withObservation(currentPrice, TimeUtils.nowText())
        Repo.strongContinuationEvents = events
        store.strongContinuationEvents = StrongContinuationEvent.encode(events)
    }

    /**
     * 两市总成交额：今日累计每轮行情刷新更新；
     * 每 15 分钟槽位做一次采样落盘，并重算「昨日同一时刻」对比与全天预估。
     */
    private fun refreshTurnover(
        quotes: Map<String, Quote>,
        calendar: Calendar,
    ) {
        if (TURNOVER_CODES.any { quotes[it] == null }) return
        val total = TURNOVER_CODES.sumOf { quotes[it]?.turnover ?: 0.0 }
        if (total <= 0) return
        Repo.turnoverToday = total

        val slotMinute = TradingCalendar.turnoverSlotMinute(calendar) ?: return
        val today = TimeUtils.todayText()
        if (lastTurnoverDay != today) {
            lastTurnoverDay = today
            lastTurnoverSlot = -1
        }
        // 曲线每天只需成功取到一次，取不到就每个 15 秒 tick 重试，
        // 免得一次瞬时失败让「昨日同期」空等 15 分钟。
        val curve = previousCurve(today)
        val staleSnapshot = Repo.turnoverSnapshot?.source != TurnoverTracker.Source.CURVE
        if (slotMinute != lastTurnoverSlot || (curve != null && staleSnapshot)) {
            lastTurnoverSlot = slotMinute
            Repo.turnoverSnapshot = turnoverTracker?.sample(total, calendar, curve)
        }
    }

    /** 昨日两市分时曲线，每个交易日只取一次；取不到时由本地采样兜底。 */
    private fun previousCurve(today: String): TurnoverCurve? {
        if (turnoverCurveDay == today) return turnoverCurve
        // 网络失败不写缓存，下一轮 tick 继续重试。
        val curves =
            runCatching { TURNOVER_SECIDS.flatMap { eastMoney.indexTurnoverCurves(it) } }
                .getOrNull() ?: return null
        turnoverCurveDay = today
        turnoverCurve =
            curves
                .map { it.date }
                .filter { it < today }
                .maxOrNull()
                ?.let { eastMoney.mergeCurves(curves, it) }
        return turnoverCurve
    }

    /** 夜间调度：18:00–06:00 窗口自动全市场深扫一次，成功即停（失败 90 分钟重试 ≤8 次）。盘中不自动扫描。 */
    private fun launchNightScreenIfNeeded(calendar: Calendar = TimeUtils.nowCalendar()) {
        if (!indicatorScheduler.inWindow(calendar)) return
        // 手动深扫正在进行：这里不消耗夜间尝试次数，下一轮再看。
        if (patternScanBusy.get()) return
        if (!indicatorScheduler.canAttempt(calendar)) {
            Repo.screenStatus = indicatorScheduler.status(calendar)
            return
        }
        indicatorScheduler.beginAttempt(calendar)
        scope.launch { runPatternScan(manual = false, calendar = calendar) }
    }

    /** 手动「重扫指标」：前 200、只更新严格/宽松两档，秒级返回。 */
    private suspend fun runIndicatorScan() {
        if (!indicatorScanBusy.compareAndSet(false, true)) {
            Repo.screenStatus = "指标扫描进行中…"
            Repo.notifyChanged()
            return
        }
        try {
            Repo.screenStatus = "正在扫描指标（前 200）…"
            Repo.screenProgress = "0/0"
            Repo.notifyChanged()
            val result =
                runCatching {
                    screener.screenIndicators { done, total ->
                        Repo.screenProgress = "$done/$total"
                        if (done % 20 == 0) Repo.notifyChanged()
                    }
                }.getOrElse { error ->
                    Log.e(TAG, "指标选股失败", error)
                    Repo.screenStatus = "指标扫描失败：${error.message}"
                    Repo.lastError = "指标选股：${error.message}"
                    return
                }
            val keptAny = mergeScreenResult(result, includePatterns = false)
            Repo.screenStatus =
                if (keptAny) {
                    "指标本次无命中，保留旧结果"
                } else {
                    "指标已更新（${TimeUtils.nowText()}）"
                }
        } finally {
            indicatorScanBusy.set(false)
            Repo.screenProgress = null
            Repo.notifyChanged()
        }
    }

    /**
     * 形态深扫：全市场趋势 + 513（顺带全量刷新指标两档）。夜间窗口自动一轮，
     * 或手动「深扫全市场」触发。数千根日K、一次几分钟，所以盘中不自动跑。
     */
    private suspend fun runPatternScan(
        manual: Boolean,
        calendar: Calendar,
    ) {
        if (!patternScanBusy.compareAndSet(false, true)) {
            Repo.screenStatus = "全市场深扫进行中…"
            Repo.notifyChanged()
            return
        }
        try {
            Repo.screenStatus = "正在深扫全市场…"
            Repo.screenProgress = "0/0"
            Repo.notifyChanged()
            val result =
                runCatching {
                    screener.screenPatterns { done, total ->
                        Repo.screenProgress = "$done/$total"
                        if (done % 20 == 0) Repo.notifyChanged()
                    }
                }.getOrElse { error ->
                    Log.e(TAG, "选股深扫失败", error)
                    if (manual) {
                        Repo.screenStatus = "深扫失败：${error.message}"
                    } else {
                        indicatorScheduler.recordFailure(error.message ?: "扫描未返回结果", calendar)
                        Repo.screenStatus = indicatorScheduler.status(calendar)
                    }
                    Repo.lastError = "选股深扫：${error.message}"
                    return
                }
            val keptAny = mergeScreenResult(result, includePatterns = true)
            if (manual) {
                Repo.screenStatus =
                    if (keptAny) {
                        "本次部分档位无命中，保留旧结果"
                    } else {
                        "全市场已更新（${TimeUtils.nowText()}）"
                    }
                // 夜间窗口里的手动深扫也算该窗口的成功：数据刚扫过，别让自动再跑一遍全市场。
                // 只能在窗口内记——白天记会让夜间调度误以为已完成。
                if (indicatorScheduler.inWindow(calendar)) {
                    indicatorScheduler.recordSuccess(calendar)
                }
            } else {
                indicatorScheduler.recordSuccess(calendar)
                Repo.screenStatus = indicatorScheduler.status(calendar)
            }
        } finally {
            patternScanBusy.set(false)
            Repo.screenProgress = null
            Repo.notifyChanged()
        }
    }

    /**
     * 合并扫描结果并落盘。严格档和513按列表各自保留：本次为空、上次非空**且还在保质期内**就沿用上次
     * （[ScreenResultJson.STALE_RETENTION_MS]，旧命中超期自动清，不拿几天前的命中冒充现状）；
     * 宽松预警、趋势深扫无命中时清空；[includePatterns] 为 false（指标快扫）时保留趋势/513/统计。
     * 趋势旧结果同样应用精选规则。指标组和形态组各有各的
     * 刷新时间——哪组被完整刷新，哪组的时间才更新。返回是否保留了旧列表。
     */
    private fun mergeScreenResult(
        result: ScreenResult,
        includePatterns: Boolean,
    ): Boolean {
        val previous = Repo.screenResult
        val nowMs = System.currentTimeMillis()
        val nowText = TimeUtils.nowText()
        val indicatorRetained = ScreenResultJson.isRetained(Repo.screenIndicatorAtMs, nowMs)
        val patternRetained = ScreenResultJson.isRetained(Repo.screenPatternAtMs, nowMs)

        val strictKept = result.strict.isEmpty() && previous.strict.isNotEmpty() && indicatorRetained
        val fiveOneThreeKept =
            includePatterns && result.fiveOneThree.isEmpty() &&
                previous.fiveOneThree.isNotEmpty() && patternRetained

        val merged =
            ScreenResult(
                strict = if (strictKept) previous.strict else result.strict,
                relaxed = RelaxedScreenSelection.select(result.relaxed),
                fiveOneThree = if (!includePatterns || fiveOneThreeKept) previous.fiveOneThree else result.fiveOneThree,
                fiveOneThreeStats =
                    if (includePatterns && result.fiveOneThreeStats.totalStocks > 0) {
                        result.fiveOneThreeStats
                    } else {
                        previous.fiveOneThreeStats
                    },
                trend = TrendScreenSelection.merge(previous.trend, result.trend, includePatterns),
            )

        val keptAny = strictKept || fiveOneThreeKept
        Repo.screenResult = merged
        addScreenCandidates(merged)

        // 该组没保留任何旧列表才算完整刷新；保下来的还是老内容，时间不动。
        if (!strictKept) {
            Repo.screenIndicatorAtMs = nowMs
            Repo.screenIndicatorAt = nowText
        }
        if (includePatterns && !fiveOneThreeKept) {
            Repo.screenPatternAtMs = nowMs
            Repo.screenPatternAt = nowText
        }

        if (keptAny) {
            Repo.screenStale = true
            // 保留下来的是旧结果，时间用保留内容自己的生成时间（取更早的那个）。
            Repo.screenStaleAt = listOfNotNull(
                if (strictKept) Repo.screenIndicatorAt else null,
                if (fiveOneThreeKept) Repo.screenPatternAt else null,
            ).minByOrNull { ScreenResultJson.parseText(it) } ?: "最近一次"
        } else {
            Repo.screenStale = false
            Repo.screenStaleAt = null
        }
        // 落盘：重启后选股页靠它恢复（盘中已不自动扫描）。落盘失败只影响重启恢复，不回滚内存结果。
        runCatching {
            store.lastScreenResult =
                ScreenResultJson.encode(
                    CachedScreen(
                        indicatorAt = Repo.screenIndicatorAt,
                        indicatorAtMs = Repo.screenIndicatorAtMs,
                        patternAt = Repo.screenPatternAt,
                        patternAtMs = Repo.screenPatternAtMs,
                        result = merged,
                    ),
                )
        }.onFailure { error -> Log.w(TAG, "选股结果落盘失败", error) }
        return keptAny
    }

    private fun addScreenCandidates(result: ScreenResult) {
        val existing = store.observationPool.associateBy(::observationKey).toMutableMap()
        val today = TimeUtils.todayText()
        val candidates =
            buildList {
                result.strict.forEach {
                    add(
                        ObservationCandidate(
                            it.code,
                            it.name,
                            today,
                            null,
                            ObservationStage.OBSERVING,
                            0,
                            0,
                            source = "指标严格",
                            strategy = ObservationStrategy.PRE_BREAKOUT,
                        ),
                    )
                }
                result.relaxed.forEach {
                    add(
                        ObservationCandidate(
                            it.code,
                            it.name,
                            today,
                            null,
                            ObservationStage.OBSERVING,
                            0,
                            0,
                            source = "指标宽松",
                            strategy = ObservationStrategy.PRE_BREAKOUT,
                        ),
                    )
                }
                result.trend.forEach {
                    add(
                        ObservationCandidate(
                            it.code,
                            it.name,
                            today,
                            null,
                            ObservationStage.OBSERVING,
                            0,
                            0,
                            source = "趋势深扫",
                            strategy = ObservationStrategy.PRE_BREAKOUT,
                        ),
                    )
                }
                result.fiveOneThree.forEach {
                    add(
                        ObservationCandidate(
                            it.code,
                            it.name,
                            today,
                            null,
                            ObservationStage.OBSERVING,
                            0,
                            0,
                            source = "513形态",
                            strategy = ObservationStrategy.PRE_BREAKOUT,
                        ),
                    )
                }
            }
        candidates.forEach { candidate ->
            val previous = existing[observationKey(candidate)]
            if (previous == null || previous.stage != ObservationStage.ELIMINATED) {
                existing[observationKey(candidate)] = previous ?: candidate
            }
        }
        val updated =
            existing.values.sortedWith(
                compareBy<ObservationCandidate> { it.stage == ObservationStage.ELIMINATED }
                    .thenByDescending { it.lastEvaluatedDate ?: it.addedDate },
            )
        store.observationPool = updated.toMutableList()
        Repo.observationPool = updated
    }

    private fun addLeaderCandidates(limitStocks: List<LimitStock>) {
        if (limitStocks.isEmpty()) return
        val existing = store.observationPool.associateBy(::observationKey).toMutableMap()
        val today = TimeUtils.todayText()
        limitStocks.sortedByDescending { it.pct }.take(MAX_LEADER_CANDIDATES).forEach { stock ->
            val candidate =
                ObservationCandidate(
                    code = stock.code,
                    name = stock.name,
                    addedDate = today,
                    lastEvaluatedDate = null,
                    stage = ObservationStage.OBSERVING,
                    score = 0,
                    weakDays = 0,
                    lastPrice = stock.price,
                    source = "涨停强势",
                    reason = "今日接近涨停，等待持续性和承接确认",
                    strategy = ObservationStrategy.LEADER,
                )
            val key = observationKey(candidate)
            val previous = existing[key]
            if (previous == null || previous.stage != ObservationStage.ELIMINATED) {
                existing[key] = previous ?: candidate
            }
        }
        val updated =
            existing.values.sortedWith(
                compareBy<ObservationCandidate> { it.stage == ObservationStage.ELIMINATED }
                    .thenByDescending { it.lastEvaluatedDate ?: it.addedDate },
            )
        store.observationPool = updated.toMutableList()
        Repo.observationPool = updated
    }

    private fun observeCurrentScreen() {
        addScreenCandidates(Repo.screenResult)
        Repo.statusText = "已将当前选股结果加入观察池"
        Repo.notifyChanged()
    }

    private fun launchObservationPoolRefresh(quotes: Map<String, Quote>) {
        val current = store.observationPool
        val guarded = current.map { ObservationEligibility.applyQuoteGate(it, quotes[it.code]) }
        if (guarded != current) {
            store.observationPool = guarded.toMutableList()
            Repo.observationPool = guarded
            Repo.notifyChanged()
        }
        if (!TradingCalendar.isMarketOpen()) return
        if (!observationRefreshBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                refreshObservationPool(quotes)
            } catch (error: Exception) {
                Log.w(TAG, "观察池复评失败", error)
            } finally {
                observationRefreshBusy.set(false)
            }
        }
    }

    private suspend fun refreshObservationPool(quotes: Map<String, Quote>) {
        val current = store.observationPool
        if (current.isEmpty()) return
        val today = TimeUtils.todayText()
        val now = System.currentTimeMillis()
        val intradayDue = now - lastObservationIntradayRefreshAtMs >= OBSERVATION_INTRADAY_REFRESH_MS
        val pending =
            current.filter {
                ObservationEligibility.needsRefresh(it, today, quotes[it.code]) &&
                    (it.lastEvaluatedDate != today || intradayDue)
            }
        if (pending.isEmpty()) return
        val activeCodes = current.filter { it.stage != ObservationStage.ELIMINATED }.map { it.code }.toSet()
        observationHistory.keys.retainAll(activeCodes)
        lastObservationIntradayRefreshAtMs = now
        val result = Repo.screenResult
        val screened =
            (
                result.strict.map { it.code } + result.relaxed.map { it.code } +
                    result.fiveOneThree.map { it.code } + result.trend.map { it.code }
            ).toSet()
        val benchmark = runCatching { sina.kline(MARKET_INDEX_CODES.first(), 60) }.getOrNull()
        val semaphore = Semaphore(4)
        val evaluatedQuotes = java.util.concurrent.ConcurrentHashMap<Pair<String, ObservationStrategy>, Quote>()
        val evaluated: Map<Pair<String, ObservationStrategy>, ObservationCandidate> =
            coroutineScope {
                pending
                    .map { candidate ->
                        async {
                            semaphore.withPermit {
                                val history =
                                    observationHistory[candidate.code]?.takeIf { it.first == today }?.second
                                        ?: runCatching { sina.kline(candidate.code, 60) }
                                            .getOrNull()
                                            ?.filter { it.date < today }
                                            ?.takeIf { it.size >= 30 }
                                            ?.also { observationHistory[candidate.code] = today to it }
                                        ?: return@withPermit null
                                if (history.size < 30) return@withPermit null
                                val quote = Repo.quotes[candidate.code] ?: quotes[candidate.code] ?: return@withPermit null
                                if (!ObservationEligibility.needsRefresh(candidate, today, quote)) return@withPermit null
                                val key = candidate.code to candidate.strategy
                                evaluatedQuotes[key] = quote
                                key to
                                    ObservationPool.evaluate(
                                        candidate,
                                        buildObservationSignal(
                                            candidate = candidate,
                                            quote = quote,
                                            history = history,
                                            benchmark = benchmark,
                                            screened = screened,
                                        ),
                                    )
                            }
                        }
                    }.awaitAll()
                    .filterNotNull()
                    .toMap()
            }
        val latest = store.observationPool
        val currentByKey = latest.associateBy { it.code to it.strategy }
        val accepted =
            evaluated
                .filter { (key, candidate) ->
                    key in currentByKey && (
                        (candidate.stage != ObservationStage.BUY_CANDIDATE && candidate.entryConfirmationRounds == 0) ||
                            (Repo.quotes[key.first] ?: quotes[key.first]) == evaluatedQuotes[key]
                    )
                }.mapValues { (key, candidate) ->
                    ObservationEligibility.applyQuoteGate(candidate, Repo.quotes[key.first] ?: quotes[key.first])
                }
        val updated = latest.map { accepted[it.code to it.strategy] ?: it }
        val buyAlerts =
            accepted.values.filter { candidate ->
                val previous = currentByKey[candidate.code to candidate.strategy] ?: return@filter false
                ObservationBuyAlert.shouldNotify(previous, candidate)
            }
        val breakoutRiskAlerts =
            accepted.values.filter { candidate ->
                val previous = currentByKey[candidate.code to candidate.strategy] ?: return@filter false
                ObservationBreakoutRiskAlert.shouldNotify(previous, candidate)
            }
        store.observationPool = updated.toMutableList()
        Repo.observationPool = updated
        val evaluatedSnapshots =
            accepted.values.map { candidate ->
                val quote = evaluatedQuotes[candidate.code to candidate.strategy]
                ObservationSnapshot(
                    code = candidate.code,
                    name = candidate.name,
                    date = candidate.lastEvaluatedDate ?: today,
                    stage = candidate.stage,
                    strategy = candidate.strategy,
                    score = candidate.score,
                    price = candidate.lastPrice.takeIf { it > 0.0 } ?: quote?.price ?: 0.0,
                    changePct = quote?.changePct ?: 0.0,
                    weakDays = candidate.weakDays,
                    observedDays = candidate.observedDays,
                    stopPrice = candidate.stopPrice,
                    reason = candidate.reason,
                    evidence = candidate.evidence,
                )
            }
        if (evaluatedSnapshots.isNotEmpty()) {
            val history =
                evaluatedSnapshots.fold(store.observationSnapshots) { acc, snapshot ->
                    ObservationSnapshotHistory.append(acc, snapshot)
                }
            store.observationSnapshots = history
            Repo.observationSnapshots = history
            val paperTrades = PaperTradeTracker.reconcile(store.paperTrades, history)
            store.paperTrades = paperTrades
            Repo.paperTrades = paperTrades
        }
        buyAlerts.forEach { candidate ->
            if (!store.alertsEnabled || !store.intradaySignalEnabled) return@forEach
            if (!TradingCalendar.inMarketHoursForAlert()) return@forEach
            if (!consumePushBudget(candidate.code)) return@forEach
            Notifier.signal(
                this,
                ALERT_ID_OBSERVATION_BUY + observationKey(candidate).hashCode(),
                ObservationBuyAlert.title(candidate),
                ObservationBuyAlert.body(candidate),
            )
        }
        breakoutRiskAlerts.forEach { candidate ->
            if (!store.alertsEnabled || !store.intradaySignalEnabled) return@forEach
            if (!TradingCalendar.inMarketHoursForAlert()) return@forEach
            Notifier.signal(
                this,
                ALERT_ID_OBSERVATION_RISK + observationKey(candidate).hashCode(),
                ObservationBreakoutRiskAlert.title(candidate),
                ObservationBreakoutRiskAlert.body(candidate),
            )
        }
        Repo.notifyChanged()
    }

    private fun buildObservationSignal(
        candidate: ObservationCandidate,
        quote: Quote,
        history: List<DayBar>,
        benchmark: List<DayBar>?,
        screened: Set<String>,
    ): ObservationSignal {
        val elapsed = TradingCalendar.tradingMinutesElapsed()
        val asOf = quote.quoteDate ?: TimeUtils.todayText()
        val averageVolume = VolumeRatio.averageVolumeExcludingToday(history, quote.quoteDate ?: TimeUtils.todayText()) ?: 0.0
        val volumeRatio = VolumeRatio.of(quote.volume, averageVolume, elapsed)
        val atr =
            Indicators
                .atr(
                    history.map { it.high },
                    history.map { it.low },
                    history.map { it.close },
                ).lastOrNull()
                ?.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val trend =
            runCatching {
                TrendEntryStrategy.evaluateDaily(
                    history = ObservationEntryPolicy.withLiveBar(history, quote),
                    atrOverride = atr,
                    benchmark = benchmark,
                    elapsedTradingMinutes = elapsed,
                    ignoreMarketFilter = candidate.strategy == ObservationStrategy.LEADER,
                )
            }.getOrNull()
        val trendAssessment = TrendAssessmentPolicy.evaluateBars(
            history = ObservationEntryPolicy.withLiveBar(history, quote),
            asOf = asOf,
        )
        val analysisBars = ObservationEntryPolicy.withLiveBar(history, quote)
        val analysisCloses = analysisBars.map { it.close }
        val ma5 = analysisCloses.takeLast(5).takeIf { it.size == 5 }?.average()
        val ma20 = analysisCloses.takeLast(20).takeIf { it.size == 20 }?.average()
        val priceLocation = PricePosition.of(analysisBars, asOf)?.contextOf(quote.price)?.location
        val biasFromMa5Pct = ma5?.takeIf { it > 0 }?.let { (quote.price / it - 1.0) * 100.0 }
        val highPositionEvidence = com.example.stockmonitor.analysis.HighPositionEvidence(
            price = quote.price,
            rangeHigh = analysisBars.takeLast(60).maxOfOrNull { it.high } ?: quote.high,
            rangeLow = analysisBars.takeLast(60).minOfOrNull { it.low } ?: quote.low,
            atrPct = if (quote.price > 0) atr / quote.price * 100 else 0.0,
            gain5Pct = if (history.size >= 5) (quote.price / history[history.lastIndex - 4].close - 1.0) * 100 else 0.0,
            volumeRatio = volumeRatio,
            pullbackVolumeRatio = volumeRatio?.takeIf { quote.changePct < 0 },
            recentLows = analysisBars.takeLast(4).map { it.low },
            recentHighs = analysisBars.takeLast(4).map { it.high },
            recentCloses = analysisBars.takeLast(4).map { it.close },
            support = analysisBars.takeLast(20).minOfOrNull { it.low } ?: quote.low,
            marketHealthy = Repo.marketState?.regime?.let {
                it != MarketRegime.WEAK && it != MarketRegime.COLLAPSE
            } == true,
            sectorHealthy = Repo.sectorContexts[candidate.code]?.state?.regime?.let {
                it != MarketRegime.WEAK && it != MarketRegime.COLLAPSE
            } == true,
            dualTimeframeAllowed = false,
            trend = trendAssessment,
        )
        val highPosition = HighPositionPolicy.evaluate(highPositionEvidence)
        val boxRange = BoxRangePolicy.evaluate(
            history = ObservationEntryPolicy.withLiveBar(history, quote),
            asOf = asOf,
            currentPrice = quote.price,
            currentVolumeRatio = volumeRatio,
        )
        val breakoutRisk = BreakoutRiskPolicy.evaluate(
            BreakoutRiskEvidence(
                price = quote.price,
                dailyHighPrice = quote.high,
                breakoutReference = trend?.priorHigh ?: highPositionEvidence.rangeHigh,
                changePct = quote.changePct,
                volumeRatio = volumeRatio,
                atrPct = highPositionEvidence.atrPct,
                continuation = BreakoutRiskPolicy.continuation(trend?.type, highPosition.phase),
                priceLocation = priceLocation,
                ma5 = ma5,
                ma20 = ma20,
                biasFromMa5Pct = biasFromMa5Pct,
            ),
        )
        val stopPrice = trend?.stopPrice ?: atr.takeIf { it > 0 }?.let { quote.price - it * 1.5 }
        val relativeStrength = relativeStrength(history, quote.price, benchmark)
        val recentLimitUpDays = recentLimitUpDays(candidate.code, candidate.name, history, quote)
        val limitUpBlocked = ObservationEligibility.isAtLimitUp(candidate.code, candidate.name, quote)
        val limitUpShrinkAcceleration =
            ObservationEligibility.isLimitUpShrinkAcceleration(
                limitUpBlocked = limitUpBlocked,
                changePct = quote.changePct,
                volumeRatio = volumeRatio,
                wasDeferred = candidate.limitUpShrinkAcceleration,
            )
        val ratio = volumeRatio ?: 0.0
        return when (candidate.strategy) {
            ObservationStrategy.PRE_BREAKOUT -> {
                val hasScreenSignal = candidate.code in screened
                val setupScore =
                    listOf(
                        hasScreenSignal,
                        trend != null && trend.type != EntrySetupType.NONE,
                        trend?.confirmed == true,
                    ).count { it }
                val trendCanBuy = trend?.canBuy == true
                ObservationSignal(
                    date = quote.quoteDate ?: TimeUtils.todayText(),
                    price = quote.price,
                    hasScreenSignal = hasScreenSignal,
                    confirmedEntry = trend?.confirmed == true,
                    canBuy = trendCanBuy && !limitUpBlocked && !limitUpShrinkAcceleration,
                    trendWeak = trend?.trendWeak == true || quote.changePct <= -3.0,
                    stopPrice = stopPrice,
                    changePct = quote.changePct,
                    strategy = candidate.strategy,
                    setupScore = setupScore,
                    relativeStrengthPct = relativeStrength,
                    volumeRatio = volumeRatio,
                    limitUpBlocked = limitUpBlocked,
                    limitUpShrinkAcceleration = limitUpShrinkAcceleration,
                    riskFlag = trend?.trendWeak == true,
                    signalReason =
                        when {
                            limitUpShrinkAcceleration -> ObservationEligibility.SHRINK_WAIT_REASON
                            limitUpBlocked -> ObservationEligibility.LIMIT_WAIT_REASON
                            else -> trend?.conciseText().orEmpty()
                        },
                ).let {
                    it.copy(
                        evidence = ObservationEvidenceFactory.from(it),
                        trendAssessment = trendAssessment,
                        boxRange = boxRange,
                        highPosition = highPosition,
                        breakoutRisk = breakoutRisk,
                    )
                }
            }

            ObservationStrategy.LEADER -> {
                val entryConfirmed =
                    ObservationEntryPolicy.leaderCanBuy(
                        recentLimitUpDays,
                        relativeStrength,
                        volumeRatio,
                        quote.price,
                        trend,
                        trendAssessment = trendAssessment,
                        stopPrice = stopPrice,
                        priceLocation = priceLocation,
                        ma5 = ma5,
                        ma20 = ma20,
                        biasFromMa5Pct = biasFromMa5Pct,
                    )
                val strongScore =
                    listOf(
                        recentLimitUpDays >= 1,
                        relativeStrength != null && relativeStrength >= ObservationEntryPolicy.RELATIVE_STRENGTH_PCT,
                        entryConfirmed,
                    ).count { it }
                val riskFlag =
                    trend?.trendWeak == true || quote.changePct <= -3.0 ||
                        (ratio >= LEADER_DISTRIBUTION_VOLUME_RATIO && quote.changePct < 1.0) ||
                        (recentLimitUpDays >= 2 && quote.changePct < -1.0)
                val canBuy = entryConfirmed && !riskFlag && !limitUpBlocked && !limitUpShrinkAcceleration
                val confirmationText =
                    when {
                        limitUpShrinkAcceleration -> ObservationEligibility.SHRINK_WAIT_REASON
                        limitUpBlocked -> ObservationEligibility.LIMIT_WAIT_REASON
                        canBuy -> "回踩企稳条件满足，须连续复评确认；不代表资金承接已获证实"
                        riskFlag -> "高位分歧风险"
                        else -> "强势仅作观察，等待缩量回踩企稳及市场环境确认"
                    }
                ObservationSignal(
                    date = quote.quoteDate ?: TimeUtils.todayText(),
                    price = quote.price,
                    hasScreenSignal = recentLimitUpDays >= 1,
                    confirmedEntry = entryConfirmed,
                    canBuy = canBuy,
                    trendWeak = trend?.trendWeak == true || quote.changePct <= -3.0 || (relativeStrength != null && relativeStrength < -2.0),
                    stopPrice = stopPrice ?: quote.price * 0.93,
                    changePct = quote.changePct,
                    strategy = candidate.strategy,
                    strongScore = strongScore,
                    recentLimitUpDays = recentLimitUpDays,
                    relativeStrengthPct = relativeStrength,
                    volumeRatio = volumeRatio,
                    limitUpBlocked = limitUpBlocked,
                    limitUpShrinkAcceleration = limitUpShrinkAcceleration,
                    riskFlag = riskFlag,
                    evaluatedAtMs = System.currentTimeMillis(),
                    quoteVolume = quote.volume,
                    signalReason =
                        String.format(
                            Locale.CHINA,
                            "近5日涨停%d次｜相对大盘%+.1f%%｜量比%.2f",
                            recentLimitUpDays,
                            relativeStrength ?: 0.0,
                            ratio,
                        ) + "｜" + confirmationText,
                ).let {
                    it.copy(
                        evidence = ObservationEvidenceFactory.from(it),
                        trendAssessment = trendAssessment,
                        boxRange = boxRange,
                        highPosition = highPosition,
                        breakoutRisk = breakoutRisk,
                    )
                }
            }
        }
    }

    private fun relativeStrength(
        history: List<DayBar>,
        price: Double,
        benchmark: List<DayBar>?,
    ): Double? {
        val stockBars = history.filter { it.close > 0 && it.date != TimeUtils.todayText() }.takeLast(6)
        val marketBars = benchmark?.filter { it.close > 0 && it.date != TimeUtils.todayText() }?.takeLast(6)
        if (stockBars.size < 2 || marketBars == null || marketBars.size < 2) return null
        val stockReturn = price / stockBars.first().close - 1.0
        val marketReturn = marketBars.last().close / marketBars.first().close - 1.0
        return (stockReturn - marketReturn) * 100.0
    }

    private fun recentLimitUpDays(
        code: String,
        name: String,
        history: List<DayBar>,
        quote: Quote,
    ): Int {
        val limit = SinaClient.limitPctFor(code, name)
        val bars = history.filter { it.date != TimeUtils.todayText() }.takeLast(5)
        val historical =
            bars.zipWithNext().count { (previous, current) ->
                previous.close > 0 && (current.close / previous.close - 1.0) * 100.0 >= limit - 0.15
            }
        val current = if (quote.prevClose > 0 && quote.changePct >= limit - 0.15) 1 else 0
        return historical + current
    }

    private fun observationKey(candidate: ObservationCandidate): String = "${candidate.strategy.name}:${candidate.code}"

    /** 持仓分析：盘中每半小时槽位一次。 */
    private fun launchPortfolioIfNeeded(calendar: Calendar = TimeUtils.nowCalendar()) {
        val portfolio = store.portfolio
        if (portfolio.positions.isEmpty()) return
        val slot = TradingCalendar.intradaySlot(calendar) ?: return
        if (slot == lastPortfolioSlot || slot == portfolioSlotInFlight) return
        portfolioSlotInFlight = slot
        scope.launch {
            val completed = runPortfolioAnalysis(slot)
            if (completed) {
                lastPortfolioSlot = slot
                store.portfolioSlot = slot
            }
            if (portfolioSlotInFlight == slot) portfolioSlotInFlight = null
        }
    }

    private suspend fun runPortfolioAnalysis(slot: String): Boolean {
        if (!portfolioBusy.compareAndSet(false, true)) return false
        try {
            val portfolio = store.portfolio
            if (portfolio.positions.isEmpty()) {
                Repo.portfolioTask =
                    BackgroundTaskState.failure(
                        "持仓分析",
                        "没有配置监控股票",
                        TimeUtils.nowText(),
                    )
                Repo.notifyChanged()
                return false
            }
            Repo.portfolioTask =
                BackgroundTaskState.running(
                    "持仓分析",
                    total = portfolio.positions.size,
                    message = "正在获取行情并计算指标…",
                )
            Repo.portfolioSwing = null
            holdingTContexts = emptyMap()
            Repo.notifyChanged()
            // 板块上下文要先就位，否则这一轮的应对里没有板块修饰。
            HoldingAnalyzer.refreshSectorContexts(store, portfolio.positions)
            val results = mutableListOf<com.example.stockmonitor.analysis.PositionResult>()
            // 本轮取到的实时行情，用来算报告里的「今日盈亏」；取不到的股票会由 DayPnl 标成缺行情。
            val liveQuotes = mutableMapOf<String, Quote>()
            var failedCount = 0
            val benchmark = runCatching { sina.kline("sh000001", 240) }.getOrNull()
            // 大盘状态整批算一次（MA20/MA60 不用每只股重算），当日涨跌取沪指行情。
            val market = MarketRegime.evaluate(benchmark.orEmpty(), Repo.quotes["sh000001"]?.changePct)
            // 存下来给 UI 的「今日要点」用，别让界面层再去拉一次沪指日线重算 MA20/MA60。
            Repo.marketState = market
            val marketRisk =
                HoldingAnalyzer.refreshMarketRiskContext(
                    store = store,
                    turnoverTracker = turnoverTracker,
                    tradingDates = benchmark.orEmpty().map { it.date },
                )
            portfolio.positions.forEachIndexed { index, position ->
                runCatching {
                    val live = sina.quote(position.code) ?: throw IllegalStateException("实时行情获取失败")
                    liveQuotes[position.code] = live
                    val result = HoldingAnalyzer.analyzePosition(position, portfolio, live, benchmark, market, marketRisk)
                    results.add(result)
                    notifyPortfolioSignal(result)
                    maybeRefreshFundamentalHealth(
                        code = position.code,
                        name = position.name,
                        price = result.price,
                        cost = position.cost,
                        support = result.support,
                    )
                }.onFailure { error ->
                    failedCount++
                    Log.e(TAG, "持仓分析失败 ${position.name}", error)
                    Repo.lastError = "持仓分析 ${position.name}：${error.message}"
                }
                Repo.portfolioTask =
                    BackgroundTaskState.running(
                        "持仓分析",
                        done = index + 1,
                        total = portfolio.positions.size,
                        message = "正在分析 ${position.name}…",
                    )
                Repo.notifyChanged()
            }
            val swingPlan = buildPortfolioSwing(portfolio, benchmark, results).validatedFor(store.portfolio)
            Repo.portfolioSwing = swingPlan
            Repo.portfolioResults = results
            // 状态快照推进：同一天只刷新今天的 rows，跨天才把上一份降级成「昨日」。
            // 复盘与持仓报告的「较昨日」都读这份，写在这里保证「昨日」是上一交易日收盘的样子。
            if (results.isNotEmpty() &&
                PortfolioSnapshotJson.hasFreshTradingDate(liveQuotes.values, TimeUtils.todayText())
            ) {
                val snapshot =
                    PortfolioSnapshotJson.rollForward(
                        current = Repo.portfolioSnapshot,
                        today = TimeUtils.todayText(),
                        rows =
                            PortfolioSnapshotJson.mergeRows(
                                current = Repo.portfolioSnapshot,
                                today = TimeUtils.todayText(),
                                freshRows = PortfolioSnapshotJson.from(results),
                                activeCodes = portfolio.positions.map { it.code }.toSet(),
                            ),
                    )
                Repo.portfolioSnapshot = snapshot
                store.portfolioSnapshot = PortfolioSnapshotJson.encode(snapshot)
            } else {
                Log.i(TAG, "行情未标注为今日，保留持仓快照，避免把非交易日写成昨日")
            }
            val report =
                PortfolioAnalysis.formatReport(
                    results,
                    TimeUtils.nowText(),
                    DayPnl.summarize(portfolio.positions, liveQuotes),
                    market = market,
                    swingPlan = swingPlan,
                    strongContinuationEvents = Repo.strongContinuationEvents.filter { it.date == TimeUtils.todayText() },
                    marketEnvironment =
                        run {
                            val recent = turnoverTracker?.recentFullDayTurnoversYi(TimeUtils.todayText()).orEmpty()
                            val fallbackTurnover = MarketTurnoverFallbackPolicy.evaluate(
                                predictedTurnoverYi = Repo.turnoverSnapshot?.predictedFullDay?.div(100_000_000.0),
                                recentTurnoversYi = recent,
                            )
                            val ratio = Repo.marketRiskContext?.turnover?.sameTimeRatio
                            val source = when {
                                ratio != null -> MarketTurnoverSource.SAME_TIME
                                fallbackTurnover != null -> fallbackTurnover.source
                                else -> null
                            }
                            MarketEnvironmentQuality.evaluateAdaptive(
                                marketTurnoverRatio = ratio ?: fallbackTurnover?.ratio,
                                marketTurnoverSource = source,
                                marketChangePct = Repo.quotes["sh000001"]?.changePct,
                                marketPrice = Repo.quotes["sh000001"]?.price,
                                marketHighPrice = Repo.quotes["sh000001"]?.high,
                                industryBreadth = HoldingAnalyzer.marketBreadth().first,
                                conceptBreadth = HoldingAnalyzer.marketBreadth().second,
                            )
                        },
                )
            Repo.portfolioReport = report
            store.lastPortfolioReport = report
            Repo.portfolioTask =
                BackgroundTaskState.success(
                    "持仓分析",
                    TimeUtils.nowText(),
                    if (failedCount == 0) "已生成最新持仓报告" else "完成，但 $failedCount 只股票获取失败",
                )
            Repo.notifyChanged()
            return true
        } catch (error: Exception) {
            Log.e(TAG, "持仓分析失败", error)
            Repo.portfolioTask =
                BackgroundTaskState.failure(
                    "持仓分析",
                    error.message ?: "请稍后重试",
                    TimeUtils.nowText(),
                )
            Repo.notifyChanged()
            return false
        } finally {
            portfolioBusy.set(false)
        }
    }

    private suspend fun buildPortfolioSwing(
        portfolio: Portfolio,
        benchmark: List<DayBar>?,
        results: List<com.example.stockmonitor.analysis.PositionResult>,
    ): SwingPlan {
        val today = TimeUtils.todayText()
        val holdings = portfolio.positions.filter { it.quantity.isFinite() && it.quantity > 0 }.distinctBy { it.code }
        swingHistory.keys.retainAll(holdings.map { it.code }.toSet())
        val histories = mutableMapOf<String, List<DayBar>>()
        holdings.forEachIndexed { index, position ->
            Repo.portfolioTask =
                BackgroundTaskState.running(
                    "持仓分析",
                    done = index,
                    total = holdings.size,
                    message = "波段评估：正在核对${position.name}周/月历史与基本面…",
                )
            Repo.notifyChanged()
            val history =
                swingHistory[position.code]?.takeIf { it.first == today }?.second
                    ?: runCatching { sina.swingKline(position.code) }
                        .getOrNull()
                        .orEmpty()
                        .also { if (it.isNotEmpty()) swingHistory[position.code] = today to it }
            histories[position.code] = history
            val now = System.currentTimeMillis()
            if (FundamentalHealthPolicy.needsFetch(
                    Repo.fundamentalHealth[position.code],
                    healthLastAttemptAt[position.code] ?: 0L,
                    now,
                ) && healthInFlight.add(position.code)
            ) {
                healthLastAttemptAt[position.code] = now
                try {
                    loadFundamentalHealth(position.code, position.name)
                } catch (error: Exception) {
                    Log.w(TAG, "波段基本面核实失败 ${position.name}", error)
                } finally {
                    healthInFlight.remove(position.code)
                }
            }
        }
        val quotes = runCatching { sina.quotes(holdings.map { it.code } + "sh000001") }.getOrDefault(emptyMap())
        val indexQuote = quotes["sh000001"]?.takeIf { it.quoteDate == today }
        val marketBars = indexQuote?.let { ObservationEntryPolicy.withLiveBar(benchmark.orEmpty(), it) }.orEmpty()
        val market = marketBars.takeIf { it.size >= MarketRegime.MIN_BARS }?.let { MarketRegime.evaluate(it, indexQuote?.changePct) }
        val elapsed = TradingCalendar.tradingMinutesElapsed()
        val tContexts = mutableMapOf<String, HoldingTContext>()
        val evidence =
            holdings
                .mapNotNull { position ->
                    val quote = quotes[position.code] ?: return@mapNotNull null
                    val history = histories[position.code].orEmpty()
                    val cycles = MultiTimeframeTrend.evaluate(history, today, benchmark.orEmpty().map { it.date })
                    HoldingTContext
                        .from(history, cycles, today, System.currentTimeMillis(), benchmark.orEmpty().map { it.date })
                        ?.let { tContexts[position.code] = it }
                    val bars = ObservationEntryPolicy.withLiveBar(history, quote)
                    val entry = TrendEntryStrategy.evaluateDaily(bars, marketBars, elapsedTradingMinutes = elapsed)
                    val ma20 = bars.takeLast(20).map { it.close }.average()
                    val atr =
                        if (bars.isNotEmpty()) {
                            Indicators
                                .atr(bars.map { it.high }, bars.map { it.low }, bars.map { it.close })
                                .lastOrNull()
                                ?.takeIf { it.isFinite() && it > 0 }
                        } else {
                            null
                        }
                    position.code to
                        SwingEvidence(
                            quote = quote,
                            cycles = cycles,
                            entry = entry,
                            ma20 = ma20,
                            resistance = bars.takeLast(20).maxOfOrNull { it.high } ?: 0.0,
                            atrPct = atr?.let { it / quote.price * 100 },
                            health = Repo.fundamentalHealth[position.code],
                            sectorRegime = Repo.sectorContexts[position.code]?.state?.regime,
                            positionRisk =
                                results.firstOrNull { it.code == position.code }.let { result ->
                                    when {
                                        result == null -> {
                                            "本轮持仓分析未完成，暂不分配新增资金"
                                        }

                                        result.signal in setOf("减仓风险", "趋势转弱观察", "持有/止盈观察") -> {
                                            "持仓信号仍为${result.signal}，先处理原有仓位，不叠加买入建议"
                                        }

                                        else -> {
                                            null
                                        }
                                    }
                                },
                            exitStage = results.firstOrNull { it.code == position.code }?.exitDecision?.stage,
                            trendAssessment = results.firstOrNull { it.code == position.code }?.trendAssessment,
                            boxRange = results.firstOrNull { it.code == position.code }?.boxRange,
                            highPosition = results.firstOrNull { it.code == position.code }?.highPosition,
                        )
                }.toMap()
        holdingTContexts = tContexts.toMap()
        return PortfolioSwing
            .evaluate(portfolio, evidence, market, today, System.currentTimeMillis())
            .validatedFor(store.portfolio)
    }

    private fun loadFundamentalHealth(
        code: String,
        name: String,
    ) {
        val payload = FundamentalsClient().fetch(code, name)
        val result =
            Fundamentals.analyze(
                Fundamentals.Input(
                    periods = payload.periods,
                    balance = payload.balance,
                    valuations = payload.valuations,
                    totalShares = payload.totalShares,
                    price = payload.price,
                    peers = payload.peers,
                    mainBusiness = payload.mainBusiness,
                    announcements = payload.announcements,
                    shareholder = payload.shareholder,
                    today = TimeUtils.todayText(),
                    orgType = payload.orgType,
                ),
            )
        Repo.publishFundamentalHealth(code, FundamentalHealthPolicy.grade(result, TimeUtils.nowText()))
        Repo.publishAnnouncements(code, payload.announcements)
    }

    /**
     * 每天收盘后自动生成「持仓复盘」，**只缓存不弹窗**（与盘面总结同一约定：
     * 生成的文档不是可操作事件，不推送通知）。
     *
     * 日期戳**先落再跑**：失败不每 15 秒重试（会刷屏），需要就手动点「重跑」。
     * 手动触发不写日期戳，否则下午手动跑一次会把 16 点的自动档顶掉。
     */
    private fun autoDailyReview(calendar: Calendar = TimeUtils.nowCalendar()) {
        if (TimeUtils.isWeekend(calendar)) return
        if (TimeUtils.hour(calendar) < DAILY_REVIEW_HOUR) return
        val today = TimeUtils.todayText()
        if (store.lastDailyReviewDate == today) return
        store.lastDailyReviewDate = today
        // 生成机器在 HoldingAnalyzer（与手动重跑、持仓分析共用一份）；自动档只负责调度与日期戳。
        scope.launch { HoldingAnalyzer.generateDailyReview(store, turnoverTracker) }
    }

    /**
     * 基本面健康判断按需补拉：只对浮亏超 [HEALTH_WATCH_PNL_PCT] 或已跌破支撑的持仓，
     * 且**有新鲜缓存就用缓存**（含工具页 / AI 工具写入的，见 [FundamentalHealthPolicy.CACHE_TTL_MS]，
     * 基本面半个月内基本不变）。基本面是 6~10 个接口合成的 F10 数据，不能进 15 秒行情
     * 轮询全量刷；提醒本身只读 [Repo.fundamentalHealth]，从不在提醒时拉接口。
     */
    private fun maybeRefreshFundamentalHealth(
        code: String,
        name: String,
        price: Double,
        cost: Double,
        support: Double,
    ) {
        val pnl = DeepLossPolicy.floatingPnlPct(cost, price)
        val broken = support > 0 && price > 0 && price < support
        val watchPnl = pnl != null && pnl <= HEALTH_WATCH_PNL_PCT
        if (!broken && !watchPnl) return
        val now = System.currentTimeMillis()
        if (!FundamentalHealthPolicy.needsFetch(
                snapshot = Repo.fundamentalHealth[code],
                lastAttemptMs = healthLastAttemptAt[code] ?: 0L,
                nowMs = now,
            )
        ) {
            return
        }
        if (!healthInFlight.add(code)) return
        healthLastAttemptAt[code] = now
        scope.launch {
            try {
                loadFundamentalHealth(code, name)
                Repo.notifyChanged()
            } catch (error: Exception) {
                Log.w(TAG, "基本面补拉失败 $name", error)
            } finally {
                healthInFlight.remove(code)
            }
        }
    }

    /** 只在信号状态变化时提醒一次，等价于 _send_portfolio_notification。 */
    private fun notifyPortfolioSignal(result: com.example.stockmonitor.analysis.PositionResult) {
        if (result.code !in StockAlertScope.heldCodes(store.portfolio.positions)) {
            portfolioSignals.remove(result.code)
            return
        }
        if (!store.alertsEnabled || !TradingCalendar.inMarketHoursForAlert()) return
        val previous = portfolioSignals[result.code]
        val state = "${result.signal}|${result.exitDecision?.stage}"
        portfolioSignals[result.code] = state
        if (state == previous) return
        if (result.signal !in setOf("买入确认", "买入观察", "减仓风险")) return
        val today = TimeUtils.todayText()
        val tradeBook = store.tradeBook.takeIf { it.lots.isNotEmpty() || it.entries.isNotEmpty() }
            ?: TradeLotLedger.migrate(store.portfolio.positions).also { store.tradeBook = it }
        val availability = TradeLotLedger.availability(
            tradeBook,
            store.portfolio.positions,
            today,
            result.code,
        )
        val shortAction = TradeNotification.portfolio(
            result,
            sellableQuantity = availability.sellableToday,
            lockedToday = availability.lockedToday,
            untrackedQuantity = availability.untrackedQuantity,
        ) ?: result.action
        if (result.signal == "减仓风险") {
            Notifier.riskSell(
                this,
                ALERT_ID_PORTFOLIO + result.code.hashCode(),
                "📦 ${result.name}·${result.exitDecision?.stage?.label ?: result.signal}",
                String.format(Locale.CHINA, "现价 %.2f｜$shortAction", result.price),
            )
        } else Notifier.alert(
            this,
            ALERT_ID_PORTFOLIO + result.code.hashCode(),
            "📦 ${result.name}·${result.exitDecision?.stage?.label ?: result.signal}",
            String.format(Locale.CHINA, "现价 %.2f｜$shortAction", result.price),
        )
    }

    /** 每天 16:00 / 18:00 / 21:00 生成盘面总结，只缓存不弹窗。 */
    private fun autoGenerateSummary(calendar: Calendar = TimeUtils.nowCalendar()) {
        val today = TimeUtils.todayText()
        val hour = TimeUtils.hour(calendar)
        val shouldRun =
            when (hour) {
                16 -> store.lastSummaryAfternoon != today
                18 -> store.lastSummaryDate != today
                21 -> store.lastSummaryNight != today
                else -> false
            }
        if (!shouldRun) return
        scope.launch {
            if (generateSummary()) {
                when (hour) {
                    16 -> {
                        store.lastSummaryAfternoon = today
                    }

                    18 -> {
                        store.lastSummaryDate = today
                    }

                    21 -> {
                        store.lastSummaryDate = null
                        store.lastSummaryNight = today
                    }
                }
            }
        }
    }

    private suspend fun generateSummary(): Boolean {
        if (!summaryBusy.compareAndSet(false, true)) return false
        try {
            Repo.summaryTask =
                BackgroundTaskState.running(
                    "盘面总结",
                    message = "正在获取板块、指数和成交额数据…",
                )
            Repo.notifyChanged()
            val today = TimeUtils.todayText()
            val freshQuotes = Repo.quotes.filterValues { it.quoteDate == today }
            if (!MarketSummary.hasFreshIndexQuotes(freshQuotes, today)) {
                Repo.summaryTask =
                    BackgroundTaskState.failure(
                        "盘面总结",
                        "指数行情未更新，保留旧总结",
                        TimeUtils.nowText(),
                    )
                Repo.notifyChanged()
                return false
            }
            // 与 macOS 版一致：新浪优先、东方财富备用，都失败时回落到概念缓存。
            val fresh =
                runCatching { sina.conceptSummary() }.getOrNull()
                    ?: runCatching { eastMoney.conceptSummary() }.getOrNull()
            if (fresh != null) Repo.conceptSummary = fresh
            val concept = fresh ?: Repo.conceptSummary ?: ConceptSummary(emptyList(), 0, 0)
            val klines = runCatching { sina.kline("sh000001", 5) }.getOrElse { emptyList() }
            val amounts = runCatching { eastMoney.indexDailyAmounts("1.000001") }.getOrNull()
            Repo.summaryTask = BackgroundTaskState.running("盘面总结", message = "正在整理报告…")
            Repo.notifyChanged()
            val text =
                MarketSummary.generate(
                    MarketSummary.Inputs(
                        quotes = freshQuotes,
                        sectors = Repo.sectors,
                        concept = concept,
                        limits = Repo.limits,
                        turnoverLines =
                            MarketSummary.turnoverLines(
                                Repo.turnoverSnapshot?.today ?: Repo.turnoverToday,
                                Repo.turnoverSnapshot,
                            ),
                        klines = klines,
                        amounts = amounts,
                        predictedTurnoverYi = Repo.turnoverSnapshot?.predictedFullDay?.div(100_000_000.0),
                        recentTurnoversYi =
                            turnoverTracker?.recentFullDayTurnoversYi(TimeUtils.todayText())
                                ?: emptyList(),
                    ),
                )
            Repo.summaryText = text
            store.lastSummaryText = text
            store.lastSummaryGeneratedAt = TimeUtils.nowText()
            Repo.summaryTask = BackgroundTaskState.success("盘面总结", TimeUtils.nowText(), "最新总结已保存")
            Repo.notifyChanged()
            return true
        } catch (error: Exception) {
            Log.e(TAG, "盘面总结失败", error)
            Repo.summaryTask =
                BackgroundTaskState.failure(
                    "盘面总结",
                    error.message ?: "请稍后重试",
                    TimeUtils.nowText(),
                )
            Repo.lastError = "盘面总结：${error.message}"
            Repo.notifyChanged()
            return false
        } finally {
            summaryBusy.set(false)
        }
    }

    /** 更新常驻通知与状态文本，等价于 update_display。 */
    private fun updateDisplay() {
        val watchlist = Repo.watchlistOrder.ifEmpty { store.watchlist.map { it.code to it.name } }
        val barItems = store.watchlist.filter { it.showInBar }
        // 顶栏股票最多显示 3 只，多的用 … 省略：否则标题会折成两行，把正文挤下去。
        val barQuotes =
            barItems.mapNotNull { item ->
                Repo.quotes[item.code]?.let { quote -> formatBarQuote(item.name, quote) }
            }
        val title =
            when {
                barQuotes.size > BAR_QUOTE_LIMIT -> barQuotes.take(BAR_QUOTE_LIMIT).joinToString(" ") + " …"
                else -> barQuotes.joinToString(" ")
            }
        val indexItems =
            watchlist
                .filter { (code, _) -> code.startsWith("sh000") || code.startsWith("sz399") }
        // 已经出现在顶栏的指数不在正文里重复一次；全被顶栏占用时退回完整概况。
        // 名字用自选股里的短名（上证/创业/科创），比新浪返回的「上证指数/创业板指」省一行宽度。
        val barCodes = barItems.map { it.code }.toSet()
        val overviewQuotes =
            indexItems
                .filterNot { it.first in barCodes }
                .ifEmpty { indexItems }
                .mapNotNull { (code, name) -> Repo.quotes[code]?.copy(name = name) }
        val overview = MarketSummary.overviewTitle(overviewQuotes, Repo.turnoverSnapshot)
        Repo.statusText =
            when {
                Repo.refreshing -> "正在刷新行情…"
                Repo.lastError != null -> "刷新异常，自动重试中"
                else -> "监控运行中 · 每 15 秒刷新"
            }
        // 展开态只留行情和更新时间：自选股数量、刷新间隔属于设置项，不必占用通知。
        val timeText = if (Repo.lastUpdateAt != null) "${TimeUtils.clockText()} 更新" else "等待首次加载"
        startForegroundCompat(
            Notifier.buildMonitorNotification(
                this,
                title.ifBlank { "📊 股票监控" },
                overview,
                "$overview\n$timeText",
            ),
        )
    }

    /** 顶栏一只股票：价格与 App 其他位置一致，百元以上取整，避免标题过长。 */
    private fun formatBarQuote(
        name: String,
        quote: Quote,
    ): String {
        val price =
            if (quote.price >= 100) {
                String.format(Locale.CHINA, "%.0f", quote.price)
            } else {
                String.format(Locale.CHINA, "%.2f", quote.price)
            }
        return String.format(Locale.CHINA, "%s%s(%+.2f%%)", name, price, quote.changePct)
    }

    companion object {
        private const val TAG = "StockMonitor"
        const val ACTION_REFRESH = "com.example.stockmonitor.action.REFRESH"
        const val ACTION_SCREEN = "com.example.stockmonitor.action.SCREEN"
        const val ACTION_SCREEN_FULL = "com.example.stockmonitor.action.SCREEN_FULL"
        const val ACTION_OBSERVE_SCREEN = "com.example.stockmonitor.action.OBSERVE_SCREEN"
        const val ACTION_SUMMARY = "com.example.stockmonitor.action.SUMMARY"
        const val ACTION_PORTFOLIO = "com.example.stockmonitor.action.PORTFOLIO"

        /** 复盘的自动档：每天这个点（含）之后生成一次。15:05 数据可能未定，16 点更稳。 */
        const val DAILY_REVIEW_HOUR = 16

        /** 手动触发持仓分析时用的槽位标记，不参与盘中槽位去重。 */
        private const val MANUAL_SLOT = "manual"
        const val ACTION_STOP = "com.example.stockmonitor.action.STOP"

        /** 通知栏顶栏最多同时显示的股票数，超出的用 … 省略。 */
        private const val BAR_QUOTE_LIMIT = 3
        private const val MAX_LEADER_CANDIDATES = 100
        private const val LEADER_DISTRIBUTION_VOLUME_RATIO = 2.0
        private const val OBSERVATION_INTRADAY_REFRESH_MS = 60_000L

        /** 自选股/指数行情与跌幅预警的刷新间隔。 */
        const val TICK_INTERVAL_MS = 15_000L

        /** 均量基线拉取失败后的重试间隔。 */
        private const val VOLUME_BASELINE_RETRY_MS = 15 * 60 * 1000L

        /** 浮亏到这个幅度（相对成本）或已跌破支撑，才按需补拉基本面健康判断。 */
        private const val HEALTH_WATCH_PNL_PCT = -10.0

        /** 同一代码同一天「可操作」提醒的合并上限（买卖点 + 下探回升共用）。 */
        private const val SHARED_PUSH_MAX_PER_DAY = 8

        /** 落盘的日均量与价格区间对应的日线口径；换数据源时改这个值即可让旧缓存自动失效。 */
        private const val KLINE_SOURCE = "tencent-qfq"

        /** 板块/概念/涨跌停的刷新间隔（120 x 15s = 30 分钟）。 */
        const val SECTOR_INTERVAL_TICKS = 120

        /** 板块主力资金刷新间隔（20 x 15s = 5 分钟）。 */
        const val SECTOR_FLOW_INTERVAL_TICKS = 20
        private const val SECTOR_DATA_MAX_AGE_MS = 45 * 60 * 1000L

        /**
         * 板块龙头**名单**的有效期（谁是龙头，不是谁封板）。5 分钟一轮刷新，10 分钟后当暂缺，
         * 别拿旧名单把减仓提示压掉。封板/炸板另由实时行情逐轮判，不看这个 TTL。
         */
        private const val SECTOR_LEADERS_MAX_AGE_MS = 10 * 60 * 1000L

        /** 两市总成交额 = 上证指数 + 深证成指。 */
        private val TURNOVER_CODES = listOf("sh000001", "sz399001")
        private val TURNOVER_SECIDS = listOf("1.000001", "0.399001")
        private const val ALERT_ID_DROP = 3000
        private const val ALERT_ID_PORTFOLIO = 4000
        private const val ALERT_ID_SIGNAL = 5000
        private const val ALERT_ID_MARKET_WEAKNESS = 6000
        private const val ALERT_ID_MARKET_RISK = 6001

        /** 下探回升提醒（大盘 + 持仓个股共用，按 code 取模错开）。 */
        private const val ALERT_ID_REBOUND = 7000

        /** 反弹质量跟踪（企稳确认 / 诱多警示）的提醒 ID 基址，与下探回升的通知并存。 */
        private const val ALERT_ID_REBOUND_TRACK = 7200

        /** 封板冲刺预警 / 封板炸板提醒的 ID 基址。 */
        private const val ALERT_ID_SEAL_SPRINT = 7400
        private const val ALERT_ID_SEAL = 7600

        private const val ALERT_ID_OBSERVATION_BUY = 8000
        private const val ALERT_ID_OBSERVATION_RISK = 8001
        private val MARKET_INDEX_CODES = listOf("sh000001", "sz399001", "sz399006")
        private const val STOCK_DROP_ALERT_COOLDOWN_MS = 10 * 60 * 1000L

        /** 同一只股票同一类盘中信号的冷却时间。 */
        private const val SIGNAL_COOLDOWN_MS = 10 * 60 * 1000L

        /** 每只股票每天的盘中提醒上限，防止极端行情里被同一个标的刷屏。 */
        private const val SIGNAL_MAX_PER_DAY = 8

        /** 15/30分钟K线每5分钟刷新；两次刷新之间用实时价更新当前未收盘柱。 */
        private const val MINUTE_KLINE_REFRESH_MS = 5 * 60 * 1000L
        private const val MINUTE_KLINE_LENGTH = 120

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun send(
            context: Context,
            action: String,
        ) {
            val intent = Intent(context, MonitorService::class.java).setAction(action)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
