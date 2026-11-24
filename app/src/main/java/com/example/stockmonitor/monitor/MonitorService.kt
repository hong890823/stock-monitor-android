package com.example.stockmonitor.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.stockmonitor.analysis.DailyReview
import com.example.stockmonitor.analysis.AccelerationPolicy
import com.example.stockmonitor.analysis.AdviceDirection
import com.example.stockmonitor.analysis.AdviceKind
import com.example.stockmonitor.analysis.DailyAdvicePolicy
import com.example.stockmonitor.core.RiskExitReview
import com.example.stockmonitor.analysis.DayPnl
import com.example.stockmonitor.analysis.DeepLossPolicy
import com.example.stockmonitor.analysis.BoxRangePolicy
import com.example.stockmonitor.analysis.BreakoutRiskEvidence
import com.example.stockmonitor.analysis.BreakoutRiskPolicy
import com.example.stockmonitor.analysis.DipReboundEngine
import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.EnvContext
import com.example.stockmonitor.analysis.EventCoil
import com.example.stockmonitor.analysis.FundamentalHealthPolicy
import com.example.stockmonitor.analysis.Fundamentals
import com.example.stockmonitor.analysis.HighPositionPolicy
import com.example.stockmonitor.analysis.HighPositionPhase
import com.example.stockmonitor.analysis.HeadlineSelector
import com.example.stockmonitor.analysis.HoldingBreakTracker
import com.example.stockmonitor.analysis.HoldingTContext
import com.example.stockmonitor.analysis.HoldingTTracker
import com.example.stockmonitor.analysis.IndicatorRefreshScheduler
import com.example.stockmonitor.analysis.IndicatorScreener
import com.example.stockmonitor.analysis.Indicators
import com.example.stockmonitor.analysis.IntradaySignal
import com.example.stockmonitor.analysis.IntradaySignalType
import com.example.stockmonitor.analysis.IntradaySignalState
import com.example.stockmonitor.analysis.IntradayTactics
import com.example.stockmonitor.analysis.IntradayAdditionBase
import com.example.stockmonitor.analysis.IntradayAdditionPolicy
import com.example.stockmonitor.analysis.IntradayAdditionSample
import com.example.stockmonitor.analysis.IntradayAdditionStatus
import com.example.stockmonitor.analysis.IntradayFundFlowTracker
import com.example.stockmonitor.analysis.FundFlowPolicy
import com.example.stockmonitor.analysis.FundFlowWindowPolicy
import com.example.stockmonitor.analysis.FirstBoardReclaimTracker
import com.example.stockmonitor.analysis.FirstBoardClockPolicy
import com.example.stockmonitor.analysis.FirstBoardReclaimPolicy
import com.example.stockmonitor.analysis.BuyPointFundFlowPolicy
import com.example.stockmonitor.analysis.MainForceConfidence
import com.example.stockmonitor.analysis.MainForceDirectionPolicy
import com.example.stockmonitor.analysis.MarketFundFlowPolicy
import com.example.stockmonitor.analysis.MarketFundFlowState
import com.example.stockmonitor.analysis.MarketFundFlowTone
import com.example.stockmonitor.analysis.MarketFundFlowTracker
import com.example.stockmonitor.analysis.ObservationPoolRetentionPolicy
import com.example.stockmonitor.analysis.AlertEventKeys
import com.example.stockmonitor.analysis.AlertOrchestrator
import com.example.stockmonitor.analysis.StrategyThresholds
import com.example.stockmonitor.analysis.LaunchDiscoveryCoveragePolicy
import com.example.stockmonitor.analysis.LaunchDiscoveryTracker
import com.example.stockmonitor.analysis.LimitCandidatePolicy
import com.example.stockmonitor.analysis.LimitCandidateSelection
import com.example.stockmonitor.analysis.LimitSealTracker
import com.example.stockmonitor.analysis.LimitStockRanking
import com.example.stockmonitor.analysis.MarketAlertEngine
import com.example.stockmonitor.analysis.MarketBreadthFreshnessPolicy
import com.example.stockmonitor.analysis.MarketBreadthSnapshot
import com.example.stockmonitor.analysis.MarketEnvironmentQuality
import com.example.stockmonitor.analysis.MarketTurnoverFallbackPolicy
import com.example.stockmonitor.analysis.MarketTurnoverSource
import com.example.stockmonitor.analysis.MarketDivergenceCheck
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.NewsFocusContext
import com.example.stockmonitor.analysis.MarketRiskContext
import com.example.stockmonitor.analysis.MarketRiskPolicy
import com.example.stockmonitor.analysis.MarketState
import com.example.stockmonitor.analysis.MarketSummary
import com.example.stockmonitor.analysis.MarketTimingContext
import com.example.stockmonitor.analysis.MajorTrend
import com.example.stockmonitor.analysis.MultiTimeframeTrend
import com.example.stockmonitor.analysis.ObservationBuyAlert
import com.example.stockmonitor.analysis.ObservationBreakoutRiskAlert
import com.example.stockmonitor.analysis.ObservationEligibility
import com.example.stockmonitor.analysis.ObservationEntryPolicy
import com.example.stockmonitor.analysis.ObservationEvidenceFactory
import com.example.stockmonitor.analysis.ObservationPool
import com.example.stockmonitor.analysis.ObservationReentryPolicy
import com.example.stockmonitor.analysis.ObservationRefreshPolicy
import com.example.stockmonitor.analysis.PaperTradeTracker
import com.example.stockmonitor.analysis.PortfolioAnalysis
import com.example.stockmonitor.analysis.PortfolioSwing
import com.example.stockmonitor.analysis.PriceContext
import com.example.stockmonitor.analysis.PricePosition
import com.example.stockmonitor.analysis.PriceWindow
import com.example.stockmonitor.analysis.PromotionEnvironmentInput
import com.example.stockmonitor.analysis.PromotionEnvironmentPolicy
import com.example.stockmonitor.analysis.ReboundTracker
import com.example.stockmonitor.analysis.SealSprintTracker
import com.example.stockmonitor.analysis.RelaxedScreenSelection
import com.example.stockmonitor.analysis.BoardKind
import com.example.stockmonitor.analysis.MainlineStrengthPolicy
import com.example.stockmonitor.analysis.MarketFilter
import com.example.stockmonitor.analysis.SectorContext
import com.example.stockmonitor.analysis.SectorStrengthDay
import com.example.stockmonitor.analysis.SectorStrengthPoint
import com.example.stockmonitor.analysis.SectorTurnoverTracker
import com.example.stockmonitor.analysis.StockAlertScope
import com.example.stockmonitor.analysis.StockAlertType
import com.example.stockmonitor.analysis.StockAnalysis
import com.example.stockmonitor.analysis.StockDropLevel
import com.example.stockmonitor.analysis.StockRiskPolicy
import com.example.stockmonitor.analysis.StrongSectorRankingPolicy
import com.example.stockmonitor.analysis.StrongContinuationEvent
import com.example.stockmonitor.analysis.TechnicalTrendSnapshotPolicy
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
import com.example.stockmonitor.analysis.VolumePriceAnomalyTracker
import com.example.stockmonitor.analysis.WatchlistBuyCandidatePool
import com.example.stockmonitor.core.BoardMembershipPolicy
import com.example.stockmonitor.core.BoardMember
import com.example.stockmonitor.core.BoardTagCache
import com.example.stockmonitor.core.CachedScreen
import com.example.stockmonitor.core.ConceptBoards
import com.example.stockmonitor.core.ConceptSummary
import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.DailyRiskContextCache
import com.example.stockmonitor.core.DailyRiskContextInput
import com.example.stockmonitor.core.DualTimeframePolicy
import com.example.stockmonitor.core.EastMoneyClient
import com.example.stockmonitor.core.EastMoneyLimitClient
import com.example.stockmonitor.core.EastMoneyFundFlowClient
import com.example.stockmonitor.core.FundFlowHistoryJson
import com.example.stockmonitor.core.FirstBoardReclaimObservation
import com.example.stockmonitor.core.Errors
import com.example.stockmonitor.core.FundamentalsClient
import com.example.stockmonitor.core.GuardedAction
import com.example.stockmonitor.core.GuardedActionLevel
import com.example.stockmonitor.core.GuardedTradeAction
import com.example.stockmonitor.core.Http
import com.example.stockmonitor.core.IntradayBarWindow
import com.example.stockmonitor.core.IntradayVolumeContext
import com.example.stockmonitor.core.PullbackPhaseVolumeEvidence
import com.example.stockmonitor.core.NotificationIds
import com.example.stockmonitor.core.LimitDataSource
import com.example.stockmonitor.core.LeaderOverrides
import com.example.stockmonitor.core.LeaderStrengthTracker
import com.example.stockmonitor.core.LimitData
import com.example.stockmonitor.core.LimitStock
import com.example.stockmonitor.core.MarketNewsClient
import com.example.stockmonitor.core.MarketNewsJson
import com.example.stockmonitor.core.ObservationAlertState
import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationMarketRisk
import com.example.stockmonitor.core.ObservationStructureQuality
import com.example.stockmonitor.core.ObservationSnapshot
import com.example.stockmonitor.core.ObservationSnapshotHistory
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
import com.example.stockmonitor.core.TimeframeEvidence
import com.example.stockmonitor.core.TradeLotLedger
import com.example.stockmonitor.core.TradeActionEligibility
import com.example.stockmonitor.core.TradeActionGuard
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
    private val eastMoneyLimits = EastMoneyLimitClient()
    private val eastMoneyFundFlow = EastMoneyFundFlowClient()
    private val tickBusy = AtomicBoolean(false)

    /** 两套扫描各一把锁：快扫不被深扫挡住，深扫也不被快扫打断。 */
    private val indicatorScanBusy = AtomicBoolean(false)
    private val patternScanBusy = AtomicBoolean(false)
    private val portfolioBusy = AtomicBoolean(false)
    private val summaryBusy = AtomicBoolean(false)
    private val newsBusy = AtomicBoolean(false)
    private val observationRefreshBusy = AtomicBoolean(false)
    /**
     * 观察池是「读 → 改 → 整表写回」的共享状态：复评协程、涨跌停扫描、手动选股、
     * 启动发现会并发写。所有写入口都过这把锁，避免后写者用旧快照覆盖先写者的新增/降档。
     */
    private val observationPoolLock = Any()
    // 晋级环境门控（2026-10-23 promotion-hardening 设计目标）：指数风险区/跌停宽度/板块联弱时
    // 延迟候选晋级（只延迟不删除，数据缺失不拦截）。10-22 落地后一直灰度关闭，现启用。
    private val promotionEnvironmentGateEnabled = true
    private var lastObservationIntradayRefreshAtMs = 0L
    private var lastRiskPolicyMigrationAttemptDate: String? = null
    private var lastWatchlistBuyRefreshAtMs = 0L
    private val observationHistory = java.util.concurrent.ConcurrentHashMap<String, Pair<String, List<DayBar>>>()
    /**
     * 启动发现专用的日线缓存。不能复用 [observationHistory]：观察池复评每分钟会把
     * 非池内代码裁掉，而启动发现扫描的正是“尚未入池”的票，复用会导致同一批票反复拉
     * 520 根日线，60 次/天配额在开盘一小时内耗尽。
     */
    private val launchHistory = java.util.concurrent.ConcurrentHashMap<String, Pair<String, List<DayBar>>>()
    private var observationBenchmarkDay: String? = null
    private var observationBenchmark: List<DayBar>? = null
    private val swingHistory = java.util.concurrent.ConcurrentHashMap<String, Pair<String, List<DayBar>>>()
    private val technicalTrendHistory =
        java.util.concurrent.ConcurrentHashMap<String, Pair<String, List<DayBar>>>()
    private val technicalTrendBusy = AtomicBoolean(false)
    private var lastTechnicalTrendRefreshAtMs = 0L
    private val intraday15ByCode = java.util.concurrent.ConcurrentHashMap<String, IntradayBarWindow>()
    private val intraday30ByCode = java.util.concurrent.ConcurrentHashMap<String, IntradayBarWindow>()
    private val intradayLastFetchAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val marketFiveMinuteBars =
        java.util.concurrent.ConcurrentHashMap<String, List<MarketAlertEngine.IndexBar>>()
    private val marketBarsBusy = AtomicBoolean(false)
    private val marketBarsUpdatedAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val marketBarsDay = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val marketRiskDailyVolatility = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Double>>()
    private val marketRiskDailyVolatilityBusy = AtomicBoolean(false)
    private val dailyRiskContextCache = DailyRiskContextCache()

    private lateinit var store: Store
    private lateinit var screener: IndicatorScreener
    private lateinit var indicatorScheduler: IndicatorRefreshScheduler
    private var tickJob: Job? = null

    private var firstLoadDone = false
    private var sectorTick = SECTOR_INTERVAL_TICKS - 1
    private var limitTick = LIMIT_SCAN_INTERVAL_TICKS - 1
    private var launchTick = LAUNCH_SCAN_INTERVAL_TICKS - 1
    private val launchDiscoveryTracker = LaunchDiscoveryTracker()
    private val launchDiscoveryBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var launchFetchDay: String? = null
    private var launchFetchCount = 0
    private var launchAlertCount = 0
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
    private val firstBoardReclaimTracker = FirstBoardReclaimTracker()
    private val sealSprintTracker = SealSprintTracker()
    private val limitSealTracker = LimitSealTracker()
    private val volumePriceAnomalyTracker = VolumePriceAnomalyTracker()
    /** 量价异动最近一次触发的时刻（code→ms）：同一「放量」事件别再从 BREAKOUT 通道重复推。 */
    private val volumePriceAnomalyAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val intradayFundFlowTracker = IntradayFundFlowTracker()
    private val marketFundFlowTracker = MarketFundFlowTracker()
    private var marketFundFlowNotifyDay: String? = null
    private var marketFundFlowNotifyCount = 0
    private var marketFundFlowLastNotifyAtMs = 0L
    private val lastVolumePriceFundFlowRefreshAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val portfolioSignals = mutableMapOf<String, String>()
    private val exitReviewDedup = com.example.stockmonitor.core.ExitReviewDedup()
    private val pendingObservationBuyAlerts =
        java.util.concurrent.ConcurrentHashMap<String, ObservationCandidate>()
    private val pendingWatchlistBuyAlerts =
        java.util.concurrent.ConcurrentHashMap<String, ObservationCandidate>()

    private val intradaySignalState =
        IntradaySignalState(
            cooldownMs = SIGNAL_COOLDOWN_MS,
            maxPerDay = SIGNAL_MAX_PER_DAY,
        )

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

    /** 严格盘中补仓的短窗样本；重启后重新确认样本，但最高接受价从 Store 恢复。 */
    private val intradayAdditionSamples = mutableMapOf<String, MutableList<IntradayAdditionSample>>()
    private var intradayAdditionSampleDay: String? = null

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
        MarketNewsJson.decode(store.lastNewsJson)?.let { cached ->
            Repo.todayHeadlinesState = TodayHeadlinesState(
                items = cached.items,
                updatedAtMs = cached.updatedAtMs,
                lastAttemptAtMs = cached.lastAttemptAtMs,
            )
        }
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
        Repo.firstBoardReclaimObservations = store.firstBoardReclaimObservations.toList()
        Repo.fundFlowHistory = store.fundFlowHistory
        Repo.sectorStrengthHistory = store.sectorStrengthHistory
        Repo.observationSnapshots = store.observationSnapshots
        Repo.technicalTrendSnapshots = store.technicalTrendSnapshots
        Repo.paperTrades = store.paperTrades
        Repo.statusText = "正在加载行情…"
        restoreVolumeBaseline()
        restoreQuoteCache()
        val technicalItems =
            (
                store.watchlist.map { it.code to it.name } +
                    store.portfolio.positions.map { it.code to it.name }
            ).distinctBy { it.first }
        scope.launch {
            refreshTechnicalTrendSnapshots(technicalItems, Repo.quotes)
        }
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

            ACTION_NEWS -> {
                scope.launch { refreshTodayHeadlines(force = true) }
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
                    autoRefreshTodayHeadlines(calendar)
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
                    store.observationPool.filter { it.stage != ObservationStage.ELIMINATED }.map { it.code } +
                    MARKET_INDEX_CODES + MARKET_RISK_INDEX_CODES +
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
            val technicalItems =
                (
                    watchlist.map { it.code to it.name } +
                        store.portfolio.positions.map { it.code to it.name }
                ).distinctBy { it.first }
            scope.launch {
                refreshTechnicalTrendSnapshots(technicalItems, Repo.quotes)
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
                checkVolumePriceAnomalies(calendar, freshQuotes)
                // 风险卖出也必须基于已完成15分钟K线；先刷新K线，未完成槽位随后由证据层过滤。
                refreshIntradayBars(freshQuotes, calendar)
                checkIntradaySignals(calendar, freshQuotes)
                launchObservationPoolRefresh(freshQuotes)
                checkFirstBoardReclaimObservations(calendar, freshQuotes)
            } else {
                Log.w(TAG, "今日行情未更新（today=$today），跳过盘中预警")
            }
        }

        if (++limitTick >= LIMIT_SCAN_INTERVAL_TICKS) {
            limitTick = 0
            refreshLimits()
        }
        if (++launchTick >= LAUNCH_SCAN_INTERVAL_TICKS) {
            launchTick = 0
            checkLaunchDiscovery(calendar)
        }
        if (++sectorTick >= SECTOR_INTERVAL_TICKS) {
            sectorTick = 0
            refreshFundFlowHistory(currentFundFlowCodes())
            refreshSectors()
            refreshConcepts()
            ensureConceptCache()
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
        autoRefreshTodayHeadlines(calendar)
        autoGenerateSummary(calendar)
        autoDailyReview(calendar)
    }

    /**
     * 自选股与持仓股共用 35/25/20/20 的技术综合评分。长历史每只每天只取一次，
     * 结论缓存落盘，行情轮询本身不反复请求几百根 K 线。
     */
    private suspend fun refreshTechnicalTrendSnapshots(
        items: List<Pair<String, String>>,
        quotes: Map<String, Quote>,
    ) {
        if (items.isEmpty() || !technicalTrendBusy.compareAndSet(false, true)) return
        try {
            val nowMs = System.currentTimeMillis()
            val hasMissing = items.any { (code, _) -> Repo.technicalTrendSnapshots[code] == null }
            if (!hasMissing && nowMs - lastTechnicalTrendRefreshAtMs < 30L * 60L * 1000L) return
            val benchmark = observationBenchmark()
            val elapsed = TradingCalendar.tradingMinutesElapsed()
            val semaphore = Semaphore(3)
            val snapshots = coroutineScope {
                items
                    .distinctBy { it.first }
                    .map { (code, name) ->
                        async {
                            semaphore.withPermit {
                                val quote = quotes[code] ?: return@withPermit null
                                val history =
                                    technicalTrendHistory[code]?.takeIf { it.first == TimeUtils.todayText() }?.second
                                        ?: runCatching { sina.kline(code, 520) }
                                            .getOrNull()
                                            ?.takeIf { it.size >= 250 }
                                            ?.also { technicalTrendHistory[code] = TimeUtils.todayText() to it }
                                        ?: return@withPermit null
                                val bars = ObservationEntryPolicy.withLiveBar(history, quote)
                                val entrySetup = TrendEntryStrategy.evaluateDaily(
                                    bars,
                                    benchmark,
                                    elapsedTradingMinutes = elapsed,
                                )
                                // 多周期摘要同一份 history 只算一次：趋势结论
                                // （TrendAssessmentPolicy）与综合分（TechnicalTrendSnapshotPolicy）
                                // 两个消费者共用，之前各算一遍白跑一半重活。
                                val asOf = quote.quoteDate?.substringBefore(' ') ?: TimeUtils.todayText()
                                val cycles = MultiTimeframeTrend.evaluate(history, asOf)
                                val trendAssessment = TrendAssessmentPolicy.evaluateBars(
                                    history = history,
                                    asOf = asOf,
                                    cycles = cycles,
                                )
                                TechnicalTrendSnapshotPolicy.evaluate(
                                    history = history,
                                    code = code,
                                    name = name,
                                    asOf = asOf,
                                    entrySetup = entrySetup,
                                    trendAssessment = trendAssessment,
                                    marketRegime = Repo.marketState?.regime,
                                    cycles = cycles,
                                )
                            }
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
            }
            if (snapshots.isNotEmpty()) {
                Repo.technicalTrendSnapshots = Repo.technicalTrendSnapshots +
                    snapshots.associateBy { it.code }
                store.technicalTrendSnapshots = Repo.technicalTrendSnapshots
                lastTechnicalTrendRefreshAtMs = nowMs
                Repo.notifyChanged()
            }
        } finally {
            technicalTrendBusy.set(false)
        }
    }

    private fun autoRefreshTodayHeadlines(calendar: Calendar = TimeUtils.nowCalendar()) {
        val nowMs = calendar.timeInMillis
        if (Repo.todayHeadlinesState.shouldRefresh(nowMs)) {
            scope.launch { refreshTodayHeadlines(nowMs = nowMs) }
        }
    }

    private suspend fun refreshTodayHeadlines(
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val current = Repo.todayHeadlinesState
        if (!current.shouldRefresh(nowMs, force) || !newsBusy.compareAndSet(false, true)) return
        Repo.todayHeadlinesState = current.copy(
            loading = true,
            lastAttemptAtMs = nowMs,
            error = null,
        )
        Repo.newsTask = BackgroundTaskState.running("今日要闻", message = "正在获取公开财经快讯…")
        Repo.notifyChanged()
        try {
            val fetched = MarketNewsClient.today(nowMs)
            val selected = HeadlineSelector.select(
                fetched.items,
                nowMs,
                HeadlineSelector.DETAIL_LIMIT,
                newsFocusContext(),
            )
            Repo.todayHeadlinesState = TodayHeadlinesState(
                items = selected,
                updatedAtMs = nowMs,
                lastAttemptAtMs = nowMs,
                error = partialNewsFailure(fetched.failures),
            )
            store.lastNewsJson = MarketNewsJson.encode(selected, nowMs, nowMs)
            store.lastNewsUpdatedAtMs = nowMs
            store.lastNewsAttemptAtMs = nowMs
            Repo.newsTask = BackgroundTaskState.success(
                "今日要闻",
                TimeUtils.nowText(),
                buildString {
                    append(if (selected.isEmpty()) "今日暂无可用要闻" else "已更新 ${selected.size} 条")
                    if (fetched.failures.isNotEmpty()) {
                        append("；${fetched.failures.size} 个来源暂不可用")
                    }
                },
            )
        } catch (error: Exception) {
            Log.w(TAG, "今日要闻刷新失败", error)
            val retained = Repo.todayHeadlinesState
            Repo.todayHeadlinesState = retained.copy(
                loading = false,
                lastAttemptAtMs = nowMs,
                error = Errors.friendly(error),
            )
            store.lastNewsAttemptAtMs = nowMs
            Repo.newsTask = BackgroundTaskState.failure(
                "今日要闻",
                Errors.friendly(error),
                TimeUtils.nowText(),
            )
        } finally {
            newsBusy.set(false)
            Repo.notifyChanged()
        }
    }

    private fun newsFocusContext(): NewsFocusContext {
        fun codes(values: List<String>): Set<String> = values.flatMapTo(mutableSetOf()) { code ->
            listOf(
                code,
                code.removePrefix("sh"),
                code.removePrefix("sz"),
                code.removePrefix("bj"),
            )
        }

        val held = store.portfolio.positions
        val watched = store.watchlist
        val observed = store.observationPool.filter { it.stage != ObservationStage.ELIMINATED }
        return NewsFocusContext(
            heldCodes = codes(held.map { it.code }),
            heldNames = held.mapTo(mutableSetOf()) { it.name },
            watchCodes = codes(watched.map { it.code }),
            watchNames = watched.mapTo(mutableSetOf()) { it.name },
            observationCodes = codes(observed.map { it.code }),
            observationNames = observed.mapTo(mutableSetOf()) { it.name },
        )
    }

    private fun partialNewsFailure(failures: List<com.example.stockmonitor.core.NewsSourceFailure>): String? {
        if (failures.isEmpty()) return null
        return failures.joinToString("；", prefix = "部分来源暂不可用：") { "${it.label}：${it.message}" }
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
            captureSectorStrengthSnapshot()
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
        val today = TimeUtils.todayText()
        val marketFundFlow = marketFundFlowTracker.update(
            today,
            MarketFundFlowPolicy.assess(fetched.values),
        )
        Repo.marketFundFlow = marketFundFlow
        maybeNotifyMarketFundFlow(marketFundFlow, today)
        captureSectorStrengthSnapshot()
    }

    /**
     * 资金环境转向时才提醒：档位跃迁由 [MarketFundFlowTracker] 连续两次同向采样确认，
     * 这里再做交易时段、日内次数和冷却三道闸门。只读提示，不生成建议计划、不改做 T 份额。
     */
    private fun maybeNotifyMarketFundFlow(state: MarketFundFlowState, today: String) {
        if (!state.changed || state.tone == MarketFundFlowTone.UNAVAILABLE) return
        if (!store.alertsEnabled || !store.intradaySignalEnabled) return
        if (!TradingCalendar.inMarketHoursForAlert(Calendar.getInstance())) return
        val held = store.portfolio.positions.filter { it.quantity > 0 }
        if (held.isEmpty()) return
        val now = System.currentTimeMillis()
        if (marketFundFlowNotifyDay != today) {
            marketFundFlowNotifyDay = today
            marketFundFlowNotifyCount = 0
            marketFundFlowLastNotifyAtMs = 0L
        }
        if (marketFundFlowNotifyCount >= MARKET_FUND_FLOW_MAX_NOTIFY_PER_DAY) return
        if (now - marketFundFlowLastNotifyAtMs < MARKET_FUND_FLOW_COOLDOWN_MS) return
        val lines = held.take(MARKET_FUND_FLOW_MAX_HOLDING_LINES).mapNotNull { position ->
            val stockPct = intradayFundFlowTracker.context(position.code)?.dayMainNetPct
                ?: Repo.fundFlowHistory[position.code]?.lastOrNull()?.mainNetPct
            MarketFundFlowPolicy.holdingAdvice(state, stockPct)
                ?.let { "${position.name}：${it.text}" }
        }
        if (lines.isEmpty()) return
        val body = buildString {
            append(MarketFundFlowPolicy.marketSummary(state.snapshot))
            append('。')
            append('\n')
            append(lines.joinToString("\n"))
            append("\n资金流按订单规模估算，不代表机构身份，不构成交易指令。")
        }
        val posted = Notifier.marketFundFlow(
            this,
            NotificationIds.of(ALERT_ID_MARKET_FUND_FLOW, "market_fund_flow"),
            MarketFundFlowPolicy.notificationTitle(state),
            body,
        )
        if (!posted) return
        marketFundFlowNotifyCount += 1
        marketFundFlowLastNotifyAtMs = now
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
        captureSectorStrengthSnapshot()
    }

    private fun captureSectorStrengthSnapshot() {
        val date = Repo.quotes.values
            .mapNotNull { it.quoteDate?.take(10) }
            .filter { it.isNotBlank() }
            .maxOrNull() ?: TimeUtils.todayText()
        val industryItems = Repo.sectors?.all.orEmpty().map { item ->
            RankedBoardInput(
                kind = BoardKind.INDUSTRY,
                date = date,
                name = item.name,
                code = Repo.sectorContexts.values
                    .mapNotNull { it.industry }
                    .firstOrNull { StrongSectorRankingPolicy.boardNamesMatch(it.name, item.name) }
                    ?.boardCode,
                changePct = item.change,
                volume = item.volume,
            )
        }
        val conceptItems = (
            Repo.conceptSummary?.sectors.orEmpty().ifEmpty {
                (Repo.concepts?.top.orEmpty() + Repo.concepts?.bottom.orEmpty())
                    .distinctBy { it.code.ifBlank { it.name } }
            }
        ).map { item ->
            RankedBoardInput(
                kind = BoardKind.CONCEPT,
                date = date,
                name = item.name,
                code = item.code.takeIf { it.isNotBlank() },
                changePct = item.change,
                volume = item.volume,
            )
        }
        val points = rankSectorStrengthInputs(industryItems) + rankSectorStrengthInputs(conceptItems)
        if (points.isEmpty()) return
        val next = MainlineStrengthPolicy.upsert(
            Repo.sectorStrengthHistory,
            SectorStrengthDay(date = date, points = points),
        )
        if (next == Repo.sectorStrengthHistory) return
        Repo.sectorStrengthHistory = next
        store.sectorStrengthHistory = next
    }

    private data class RankedBoardInput(
        val kind: BoardKind,
        val date: String,
        val name: String,
        val code: String?,
        val changePct: Double,
        val volume: Double,
    )

    private fun rankSectorStrengthInputs(items: List<RankedBoardInput>): List<SectorStrengthPoint> {
        val changeValues = items.map { it.changePct }.filter { it.isFinite() }.sorted()
        val volumeValues = items.map { it.volume }.filter { it.isFinite() }.sorted()
        return items.map { item ->
            SectorStrengthPoint(
                kind = item.kind,
                date = item.date,
                name = item.name,
                code = item.code,
                changePct = item.changePct,
                changeRankPct = percentileRank(changeValues, item.changePct),
                turnoverRankPct = percentileRank(volumeValues, item.volume),
                mainNetRatioPct = item.code?.let { Repo.sectorFlows[it]?.mainNetRatio },
            )
        }
    }

    private fun percentileRank(sortedValues: List<Double>, current: Double): Double? {
        if (!current.isFinite() || sortedValues.size < 2) return null
        var low = 0
        var high = sortedValues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (sortedValues[middle] <= current) low = middle + 1 else high = middle
        }
        return (sortedValues.size - low).toDouble() / (sortedValues.size - 1)
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
            runCatching { eastMoneyLimits.pools(TimeUtils.todayText()) }.getOrElse { poolError ->
                Log.w(TAG, "东方财富涨跌停池不可用，退回新浪近似扫描", poolError)
                runCatching { sina.limitStocks() }.getOrElse { sinaError ->
                    Repo.lastError = "涨跌停：${Errors.friendly(sinaError)}"
                    return
                }
            }
        if (scanned.source == com.example.stockmonitor.core.LimitDataSource.SINA_APPROX &&
            scanned.up.isEmpty() && scanned.down.isEmpty()
        ) {
            Log.i(TAG, "涨跌停：新浪近似扫描结果为0，可能非交易时段，保留上次数据")
            return
        }
        val missingFlowCodes =
            currentFundFlowCodes(scanned.up.map { it.code } + scanned.down.map { it.code })
                .filterNot { Repo.fundFlowHistory.containsKey(it) }
        refreshFundFlowHistory(missingFlowCodes)
        val boardMap = resolveBoardMap(scanned.up + scanned.down)
        val flowByCode = Repo.fundFlowHistory.mapValues { (_, flows) -> flows.lastOrNull() }
        val enrichedUp = scanned.up.map {
            it.copy(boards = boardMap[it.code].orEmpty(), fundFlow = flowByCode[it.code])
        }
        val enrichedDown = scanned.down.map {
            it.copy(boards = boardMap[it.code].orEmpty(), fundFlow = flowByCode[it.code])
        }
        Repo.limits =
            LimitData(
                up = LimitStockRanking.sortUp(enrichedUp),
                down = enrichedDown,
                source = scanned.source,
            )
        val limitUps = Repo.limits?.up.orEmpty()
        val eligible = limitUps.filter(LimitCandidatePolicy::eligible)
        ObsPoolTrace.limitScan(TimeUtils.todayText(), scanned.source, limitUps, eligible)
        addLimitContinuationCandidates(eligible)
        addLeaderCandidates(
            limitUps.filterNot(LimitCandidatePolicy::eligible),
            degraded = scanned.source != LimitDataSource.EASTMONEY_LIMIT_POOL,
        )
    }

    /**
     * 首板前启动突破观察：每 5 分钟扫一次活跃股，命中只写入观察池并发观察级提醒。
     *
     * 日线与观察池复评共用 [observationHistory] 按交易日缓存，重复扫描不再请求；
     * 每次扫描最多补 6 只、每天最多 60 只，避免把低频发现变成全市场逐票拉日线。
     */
    private fun checkLaunchDiscovery(calendar: Calendar) {
        if (!TradingCalendar.inMarketHoursForAlert(calendar)) return
        val today = TimeUtils.todayText()
        // 工作日节假日（国庆/春节等）inMarketHoursForAlert 仍为 true；指数行情日期不是今天
        // 说明拿到的是上一交易日快照，不能采样，否则会误报“启动突破”并污染观察池。
        if (Repo.quotes["sh000001"]?.quoteDate?.substringBefore(' ') != today) return
        if (launchFetchDay != today) {
            launchFetchDay = today
            launchFetchCount = 0
            launchAlertCount = 0
            launchHistory.clear()
        }
        if (launchAlertCount >= LAUNCH_MAX_ALERTS_PER_DAY) return
        if (!launchDiscoveryBusy.compareAndSet(false, true)) return
        val elapsedTradingMinutes = TradingCalendar.tradingMinutesElapsed(calendar)
        scope.launch {
            try {
                val universe = runCatching { sina.activeStocks(LAUNCH_UNIVERSE_LIMIT) }.getOrElse { error ->
                    Log.w(TAG, "启动观察：活跃股列表不可用", error)
                    return@launch
                }
                if (universe.isEmpty()) return@launch
                // 淘汰条目不算“已覆盖”：清理策略保证它们只短期保留，之后可以像新票一样重新发现。
                val knownCodes = LaunchDiscoveryCoveragePolicy.coveredCodes(
                    observationPool = store.observationPool,
                    watchlistCandidates = store.watchlistBuyCandidates,
                )
                var fetchedThisScan = 0
                universe.asSequence()
                    .filter { it.code !in knownCodes && !it.code.startsWith("sh000") && !it.code.startsWith("sz399") }
                    .filter { stock ->
                        stock.price.isFinite() && stock.prevClose > 0.0 &&
                            (stock.price / stock.prevClose - 1.0) * 100.0 >= LAUNCH_PREFILTER_PCT
                    }
                    .filterNot { stock ->
                        isSealedAtLimit(stock.code, stock.name, stock.price, stock.high, stock.prevClose)
                    }
                    .forEach { stock ->
                        // 缓存命中不消耗配额；配额只限制“真正需要新拉日线”的票，
                        // 且在成功拿到可用历史后才计数，失败不烧当天额度。
                        val history = launchHistory[stock.code]?.takeIf { it.first == today }?.second
                            ?: run {
                                if (fetchedThisScan >= LAUNCH_MAX_FETCH_PER_SCAN ||
                                    launchFetchCount >= LAUNCH_MAX_FETCH_PER_DAY
                                ) {
                                    return@forEach
                                }
                                val fetched = runCatching { sina.kline(stock.code, 520) }.getOrNull()
                                    ?.filter { it.date < today }
                                    ?.takeIf { it.size >= LAUNCH_MIN_HISTORY_BARS }
                                    ?: return@forEach
                                fetchedThisScan++
                                launchFetchCount++
                                launchHistory[stock.code] = today to fetched
                                fetched
                            }
                        val closes = history.map { it.close }
                        val platform = closes.takeLast(20).maxOrNull() ?: return@forEach
                        val averageVolume = history.takeLast(5).map { it.volume }.average()
                        val ma5 = (closes.takeLast(4).sum() + stock.price) / 5.0
                        val sealed = isSealedAtLimit(
                            stock.code, stock.name, stock.price, stock.high, stock.prevClose,
                        )
                        val alert = launchDiscoveryTracker.update(
                            LaunchDiscoveryTracker.Sample(
                                code = stock.code,
                                name = stock.name,
                                tradingDay = today,
                                price = stock.price,
                                previousClose = stock.prevClose,
                                platform = platform,
                                volumeRatio =
                                    VolumeRatio.of(stock.volume, averageVolume, elapsedTradingMinutes),
                                biasFromMa5Pct = if (ma5 > 0.0) (stock.price / ma5 - 1.0) * 100.0 else null,
                                timestamp = System.currentTimeMillis(),
                                limitUp = sealed,
                            ),
                        ) ?: return@forEach
                        // 形态站稳只是“值得看”；主力借突破出货且无近端承接时，不打扰、先不入池。
                        val quality = buyPointFundFlowDecision(
                            code = stock.code,
                            price = stock.price,
                            changePct = (stock.price / stock.prevClose - 1.0) * 100.0,
                            quoteDate = today,
                            volume = stock.volume,
                            turnover = 0.0,
                        )
                        if (!quality.allow) {
                            launchDiscoveryTracker.rearm(stock.code)
                            ObsPoolTrace.launchFundFlowBlocked(today, stock.code, quality.reason)
                            return@forEach
                        }
                        ObsPoolTrace.launchAlert(today, alert.code, alert.price, alert.platform)
                        addLaunchCandidate(alert.code, alert.name, alert.price, alert.platform)
                        val posted =
                            sendCheckedAlert(
                                type = "launchDiscovery",
                                code = alert.code,
                                id = NotificationIds.of(ALERT_ID_LAUNCH_DISCOVERY, alert.code),
                                title = alert.title,
                                body = alert.body + "｜资金：${quality.evidence}；${quality.reason}",
                                signal = false,
                            )
                        if (!posted) {
                            launchDiscoveryTracker.rearm(alert.code)
                            return@forEach
                        }
                        launchAlertCount++
                    }
            } finally {
                launchDiscoveryBusy.set(false)
            }
        }
    }

    /** 与 ObservationEligibility.isAtLimitUp 同口径，作用于活跃股快照而不是 Quote。 */
    private fun isSealedAtLimit(
        code: String,
        name: String,
        price: Double,
        high: Double,
        previousClose: Double,
    ): Boolean {
        if (!price.isFinite() || !previousClose.isFinite() || previousClose <= 0.0) return false
        val changePct = (price / previousClose - 1.0) * 100.0
        val atSessionHigh = high <= 0.0 || price >= high * 0.9995
        return changePct >= SinaClient.limitPctFor(code, name) - SinaClient.LIMIT_TOLERANCE_PCT &&
            atSessionHigh
    }

    /**
     * 观察池统一写入口：串行化「读-改-写」，并清理超过保留期的淘汰条目。
     * [transform] 在锁内基于最新池计算，避免复评协程用旧快照覆盖并发新增的候选。
     */
    private fun commitObservationPool(
        onCommitted: (before: List<ObservationCandidate>, after: List<ObservationCandidate>) -> Unit =
            { _, _ -> },
        transform: (List<ObservationCandidate>) -> List<ObservationCandidate>,
    ) {
        val committed = synchronized(observationPoolLock) {
            val current = store.observationPool
            val updated = ObservationPoolRetentionPolicy.prune(
                transform(current.toList()),
                TimeUtils.todayText(),
            )
            if (updated == current) return@synchronized null
            store.observationPool = updated.toMutableList()
            Repo.observationPool = updated
            onCommitted(current, updated)
            updated
        } ?: return
        // 通知放在锁外：监听方可能重建整页 UI，不该阻塞其它入池/复评写入。
        Repo.notifyChanged()
    }

    private fun addLaunchCandidate(code: String, name: String, price: Double, platform: Double) {
        val today = TimeUtils.todayText()
        val candidate =
            ObservationCandidate(
                code = code,
                name = name,
                addedDate = today,
                lastEvaluatedDate = null,
                stage = ObservationStage.OBSERVING,
                score = 0,
                weakDays = 0,
                lastPrice = price,
                source = LAUNCH_DISCOVERY_SOURCE,
                sources = setOf(LAUNCH_DISCOVERY_SOURCE),
                reason = String.format(
                    java.util.Locale.CHINA,
                    "突破20日平台 %.2f 并站稳15分钟，等待回踩承接确认",
                    platform,
                ),
                strategy = ObservationStrategy.PRE_BREAKOUT,
            )
        val key = observationKey(candidate)
        commitObservationPool(
            onCommitted = { before, after -> ObsPoolTrace.poolAdd("launch", today, before, after) },
        ) { pool ->
            val existing = pool.associateBy(::observationKey).toMutableMap()
            val previous = existing[key]
            when {
                previous == null -> existing[key] = candidate
                // 淘汰后重新被发现：复活并开启新一轮观察，而不是永久跳过。
                previous.stage == ObservationStage.ELIMINATED ->
                    existing[key] = ObservationReentryPolicy.revive(previous, candidate)
                else -> return@commitObservationPool pool
            }
            existing.values.sortedWith(
                compareBy<ObservationCandidate> { it.stage == ObservationStage.ELIMINATED }
                    .thenByDescending { it.lastEvaluatedDate ?: it.addedDate },
            )
        }
    }

    private fun refreshFundFlowHistory(codes: List<String>) {
        if (codes.isEmpty()) return
        val fetched =
            runCatching { eastMoneyFundFlow.flows(codes) }
                .getOrElse { error ->
                    Log.w(TAG, "资金流刷新失败，保留最近缓存", error)
                    return
                }
        // 盘前、休市和部分异常响应会返回金额与资金流均为 0 的占位快照。
        // 这种快照没有资金流含义，若按交易日覆盖历史会把上一次有效数据清掉，
        // 继而让所有主力动向都只能显示“样本不足”。
        val usable = fetched.filterValues { flow ->
            flow.amountYuan?.let { it.isFinite() && it > 0.0 } == true
        }
        if (usable.isEmpty()) return
        val dated =
            usable.mapValues { (code, flow) ->
                val quoteDate = Repo.quotes[code]?.quoteDate?.substringBefore(' ')
                flow.copy(tradingDate = quoteDate ?: TimeUtils.todayText())
            }
        dated.forEach { (code, flow) ->
            val mainNetYuan = flow.mainNetYuan ?: return@forEach
            val amountYuan = flow.amountYuan ?: return@forEach
            intradayFundFlowTracker.update(
                code = code,
                day = flow.tradingDate ?: TimeUtils.todayText(),
                timestampMs = flow.asOfMs,
                cumulativeMainNetYuan = mainNetYuan,
                cumulativeAmountYuan = amountYuan,
                dayMainNetPct = flow.mainNetPct,
            )
        }
        val merged = FundFlowHistoryJson.withSnapshot(Repo.fundFlowHistory, dated)
        Repo.fundFlowHistory = merged
        store.fundFlowHistory = merged
    }

    /**
     * 组合风险只数：仅统计可执行风险档（减仓/高位减仓/退出）与技术面 riskOnly。
     * WATCH（首次破位未确认）、NOTICE（三项缓释后的「去留自定」）、DEEP_T（深套观察）
     * 属「只提示」——按比例原则不能当成已确认风险抬高组合风险层级、放大通知强度。
     */
    private fun actionablePortfolioRiskCount(results: List<com.example.stockmonitor.analysis.PositionResult>): Int =
        results.count {
            it.trendAssessment?.riskOnly == true ||
                it.notificationExitDecision?.stage in setOf(
                    com.example.stockmonitor.analysis.HoldingExitStage.REDUCE,
                    com.example.stockmonitor.analysis.HoldingExitStage.HIGH_REDUCE,
                    com.example.stockmonitor.analysis.HoldingExitStage.EXIT,
                )
        }

    private fun currentFundFlowCodes(extra: List<String> = emptyList()): List<String> =
        (
            store.watchlist.map { it.code } + store.portfolio.positions.map { it.code } +
                store.observationPool.map { it.code } + Repo.limits?.up.orEmpty().map { it.code } +
                Repo.limits?.down.orEmpty().map { it.code } + extra
        ).filter(::isFundFlowStockCode).distinct()

    private fun isFundFlowStockCode(code: String): Boolean =
        code.startsWith("sh6") || code.startsWith("sh688") ||
            code.startsWith("sz0") || code.startsWith("sz3") ||
            code.startsWith("bj4") || code.startsWith("bj8")

    /** 概念榜自带的领涨股归属，作为 F10 个股板块缓存的快速兜底。 */
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

    private fun resolveBoardMap(stocks: List<LimitStock>): Map<String, List<String>> {
        val result = BoardMembershipPolicy.resolve(
            stocks = stocks.map { BoardMembershipPolicy.Stock(it.code, it.name) },
            leaderBoardsByName = buildBoardMap(),
            cachedEntries = BoardTagCache.decode(store.boardTags),
            nowMs = System.currentTimeMillis(),
        ) { code ->
            runCatching { eastMoney.stockBoards(code) }.getOrDefault(emptyList())
        }
        if (result.refreshedCodes.isNotEmpty()) {
            store.boardTags = BoardTagCache.encode(result.entries)
        }
        return result.boardsByCode
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
        val today = currentQuotes.values.firstNotNullOfOrNull { it.quoteDate?.substringBefore(' ') }
            ?: TimeUtils.todayText()
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
                Notifier.alert(this, NotificationIds.of(ALERT_ID_DROP, code), title, text)
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
     * 市场风险预警：使用指数相对昨收的日内跌幅，而不是相邻两轮的跌幅。
     * 这样全天缓慢下跌到危险区间时也能提醒；同一轮风险在重新武装前只提醒一次。
     */
    private fun tradingMinutesSinceOpen(time: java.time.LocalTime): Int {
        val minute = time.hour * 60 + time.minute
        return when {
            minute < 570 -> 0
            minute <= 690 -> minute - 570
            minute < 780 -> 120
            minute <= 900 -> 120 + (minute - 780)
            else -> 240
        }
    }

    private fun checkMarketRiskAlert(
        quotes: Map<String, Quote>,
        calendar: Calendar,
    ) {
        if (!store.alertsEnabled || !TradingCalendar.inMarketHoursForAlert(calendar)) return
        val timestamp = System.currentTimeMillis()
        val today = TimeUtils.todayText()
        refreshMarketRiskDailyVolatility(today)
        if (marketBarsDay.getAndSet(today) != today) {
            marketFiveMinuteBars.clear()
            marketBarsUpdatedAtMs.set(0L)
        }
        val turnoverRatio = marketTurnoverRatio(quotes, calendar)
        refreshMarketFiveMinuteBars(quotes, timestamp)
        val sessionMinutes = calendar.toInstant()
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .let(::tradingMinutesSinceOpen)
        val snapshots =
            MARKET_RISK_INDEX_CODES.mapNotNull { code ->
                quotes[code]?.let { quote ->
                    MarketAlertEngine.IndexSnapshot(
                        code = quote.code,
                        name = quote.name,
                        price = quote.price,
                        changePct = quote.changePct,
                        turnover = quote.turnover,
                        timestamp = timestamp,
                        turnoverRatio = turnoverRatio,
                        dailyVolatilityPct = marketRiskDailyVolatility[code]
                            ?.takeIf { it.first == today }
                            ?.second,
                        open = quote.open,
                        previousClose = quote.prevClose,
                        high = quote.high,
                        sessionMinutes = sessionMinutes,
                    )
                }
            }
        val breadth = marketBreadth(timestamp)
        marketAlertEngine
            .update(snapshots, HashMap(marketFiveMinuteBars), today, breadth)
            .forEach { alert ->
            if (alert.vibrate) {
                Notifier.marketRisk(
                    this,
                    ALERT_ID_MARKET_RISK,
                    alert.title,
                    alert.body,
                    level = alert.level.name,
                    triggerType = alert.type,
                )
            } else {
                Notifier.alert(this, ALERT_ID_MARKET_WEAKNESS, alert.title, alert.body)
            }
        }
    }

    /** 每个交易日异步刷新一次四大指数的近20日稳健日波动率；初次未就绪时风险策略使用指数最低容差。 */
    private fun refreshMarketRiskDailyVolatility(today: String) {
        val missing = MARKET_RISK_INDEX_CODES.filter { marketRiskDailyVolatility[it]?.first != today }
        if (missing.isEmpty() || !marketRiskDailyVolatilityBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                missing.forEach { code ->
                    val history = runCatching { sina.kline(code, 60) }
                        .onFailure { Log.w(TAG, "指数日线暂不可用：code=$code", it) }
                        .getOrDefault(emptyList())
                        .filter { it.date < today }
                    MarketRiskPolicy.robustDailyVolatilityPct(history)?.let { volatility ->
                        marketRiskDailyVolatility[code] = today to volatility
                    }
                }
            } finally {
                marketRiskDailyVolatilityBusy.set(false)
            }
        }
    }

    private fun refreshMarketFiveMinuteBars(
        quotes: Map<String, Quote>,
        nowMs: Long,
    ) {
        if (
            marketBarsUpdatedAtMs.get() > 0L &&
                nowMs - marketBarsUpdatedAtMs.get() < MARKET_FIVE_MINUTE_REFRESH_MS
        ) {
            return
        }
        if (!marketBarsBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val now =
                    java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(nowMs),
                        java.time.ZoneId.of("Asia/Shanghai"),
                    )
                // 四大指数都参与分钟级速度与组合风险；日线和分钟口径保持一致。
                MARKET_RISK_INDEX_CODES.forEach { code ->
                    val bars =
                        runCatching { sina.intradayKline(code, minutes = 5, length = 240) }
                            .onFailure { Log.w(TAG, "5分钟K线暂不可用：code=$code", it) }
                            .getOrDefault(emptyList())
                    val quoteDate = quotes[code]?.quoteDate ?: TimeUtils.todayText()
                    val latestCompleted =
                        TimeframeEvidence.latestCompletedSlot(now, minutes = 5, quoteDate = quoteDate)
                    val converted =
                        bars
                            .filter {
                                it.valid &&
                                    latestCompleted != null &&
                                    it.timestamp.take(10) == quoteDate.take(10) &&
                                    it.timestamp <= latestCompleted
                            }
                            .mapNotNull { bar ->
                                TimeUtils.parseDateTime(bar.timestamp)?.time?.let { timestamp ->
                                    MarketAlertEngine.IndexBar(
                                        code = code,
                                        name = quotes[code]?.name ?: code,
                                        timestamp = timestamp,
                                        open = bar.open,
                                        high = bar.high,
                                        low = bar.low,
                                        close = bar.close,
                                        volume = bar.volume,
                                    )
                                }
                            }
                            .distinctBy { it.timestamp }
                            .sortedBy { it.timestamp }
                    if (converted.isNotEmpty() && marketBarsDay.get() == quoteDate.take(10)) {
                        marketFiveMinuteBars[code] = converted
                    }
                }
                if (marketBarsDay.get() == TimeUtils.todayText()) {
                    marketBarsUpdatedAtMs.set(nowMs)
                }
            } finally {
                marketBarsBusy.set(false)
            }
        }
    }

    private fun marketBreadth(nowMs: Long): MarketAlertEngine.MarketBreadth {
        val industry =
            Repo.sectors?.all.orEmpty().let { sectors ->
                MarketBreadthFreshnessPolicy.freshRatio(
                    MarketBreadthSnapshot(
                        rising = sectors.count { it.change > 0 },
                        total = sectors.size,
                        updatedAtMs = Repo.sectorsUpdatedAtMs,
                    ),
                    nowMs = nowMs,
                )
            }
        val concept =
            Repo.conceptSummary?.let { summary ->
                MarketBreadthFreshnessPolicy.freshRatio(
                    MarketBreadthSnapshot(
                        rising = summary.rising,
                        total = summary.rising + summary.falling,
                        updatedAtMs = Repo.conceptSummaryUpdatedAtMs,
                    ),
                    nowMs = nowMs,
                )
            }
        return MarketAlertEngine.MarketBreadth(industry, concept)
    }

    /**
     * 下探回升提醒：大盘用两市成交额口径（深套补仓 / 做 T 的观察窗口），个股只看持仓、
     * 用量比口径。放量收复才震动——没量的回升只是超跌反弹，见 [DipReboundEngine]。
     */
    private fun checkDipReboundAlerts(
        quotes: Map<String, Quote>,
        calendar: Calendar,
    ) {
        if (!store.alertsEnabled || !TradingCalendar.inMarketHoursForAlert(calendar)) return
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
        val heldCodes = StockAlertScope.heldCodes(store.portfolio.positions)
        dipReboundEngine.update(samples, today).forEach { alert ->
            val id = NotificationIds.of(ALERT_ID_REBOUND, alert.code)
            if (!alert.stock) {
                if (alert.vibrate) {
                    Notifier.marketRisk(this, id, alert.title, alert.body)
                } else {
                    Notifier.alert(this, id, alert.title, alert.body)
                }
                return@forEach
            }
            if (alert.code !in heldCodes) return@forEach
            sendCheckedAlert(
                type = "dipRebound",
                code = alert.code,
                id = id,
                title = alert.title,
                body = alert.body,
                signal = alert.vibrate,
                // 同一坑只发一次；创更深的新低是新事件，引擎允许当日 3 次。
                eventKey = AlertEventKeys.dipRebound(alert.lowPct),
            )
        }
    }

    /**
     * 反弹质量跟踪：深砸回升段的「企稳确认 / 诱多警示」（见 [ReboundTracker]）。
     *
     * 检测覆盖持仓 + 自选股 + 观察池，但只有持仓股推送；非持仓票仅由买点确认链提醒。
     * 与买卖点提示共用 [intradaySignalEnabled] 开关和每日推送预算；确认结果同 tick 先于
     * 信号链写好，接回 / 站回支撑（见 [IntradayTactics]）拿它当确认源。
     */
    private fun checkReboundTracking(
        calendar: Calendar,
        currentQuotes: Map<String, Quote>,
    ) {
        if (!store.alertsEnabled || !TradingCalendar.inMarketHoursForAlert(calendar)) return
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
        val marketVolume = IntradayVolumeContext.build(
            marketPredictedFullDayTurnover = Repo.turnoverSnapshot?.predictedFullDay?.div(100_000_000.0),
            recentCompleteMarketTurnovers = turnoverTracker?.recentFullDayTurnoversYi(today).orEmpty(),
            now = calendar.toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime(),
            quoteDate = today,
        )
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
                marketExpanding = marketVolume.marketExpanding,
            )
        }
        reboundTracker.update(samples, today).forEach { event ->
            if (!StockAlertScope.shouldNotify(
                    StockAlertType.REBOUND_TRACKING,
                    event.held,
                    watchlistEnabled = store.watchlistIntradayAlerts,
                )
            ) {
                return@forEach
            }
            sendCheckedAlert(
                type = "reboundTrack",
                code = event.code,
                id = NotificationIds.of(ALERT_ID_REBOUND_TRACK, event.code),
                title = event.title,
                body = event.body,
                signal = event.vibrate && event.held,
                // 企稳确认 / 缩量观察 / 抛压 / 诱多失效是不同事件，各自保留当日额度；
                // 不能被上午的“企稳确认”把下午的“反弹失效”永久吞掉。
                eventKey = AlertEventKeys.reboundTrack(event.kind),
            )
        }
    }

    /**
     * 首板承接转强 → 选股页「🔥 涨停晋级观察」卡片。
     *
     * 只记录、不推送：满足首板后 1–3 个交易日、日内下探开盘价、低点稳定后收复开盘价、
     * MA5 乖离不超边界且资金未在出货的候选，写入本地记录供盘后选股复核。
     *
     * 资金在出货时不记录并回滚 `emitted`，资金转好后同一形态仍可再触发。
     * 记录不依赖提醒开关：它是选股数据而不是盘中打扰，只要监控服务在盘中运行就会积累。
     */
    private fun checkFirstBoardReclaimObservations(
        calendar: Calendar,
        currentQuotes: Map<String, Quote>,
    ) {
        if (!TradingCalendar.inMarketHoursForAlert(calendar)) return
        val today = TimeUtils.todayText()
        // 用指数日线的真实交易日序列计算首板窗口：法定节假日不算交易日。
        val tradingDates = observationBenchmark()?.map { it.date }.orEmpty().also {
            if (it.isNotEmpty()) Repo.marketTradingDates = it
        }
        val qualityByCode = mutableMapOf<String, BuyPointFundFlowPolicy.Decision>()
        val candidateByCode = mutableMapOf<String, ObservationCandidate>()
        val candidates = store.observationPool.asSequence()
            .filter { candidate ->
                val windowOk = candidate.limitStreak == 1 &&
                    FirstBoardReclaimPolicy.windowEligible(
                        FirstBoardClockPolicy.effectiveBoardDate(candidate),
                        candidate.observedDays,
                        today,
                        tradingDates,
                    )
                val reason = when {
                    candidate.strategy !in RECLAIM_STRATEGIES -> "strategy=${candidate.strategy}"
                    candidate.limitStreak != 1 -> "streak=${candidate.limitStreak}"
                    !windowOk -> FirstBoardReclaimPolicy.describeWindow(
                        FirstBoardClockPolicy.effectiveBoardDate(candidate),
                        candidate.observedDays,
                        today,
                        tradingDates,
                    )
                    candidate.stage == ObservationStage.ELIMINATED -> "eliminated"
                    else -> null
                }
                ObsPoolTrace.reclaimGate(
                    today, candidate.code, candidate.name, reason == null, reason ?: "ok",
                )
                reason == null
            }
            .mapNotNull { candidate ->
                val quote = currentQuotes[candidate.code]?.takeIf { it.quoteDate == today }
                    ?: run {
                        ObsPoolTrace.reclaimGate(today, candidate.code, candidate.name, false, "staleQuote")
                        return@mapNotNull null
                    }
                val bias = ma5BiasPct(candidate.code, quote.price, today)
                if (!FirstBoardReclaimPolicy.withinPriceBound(bias)) {
                    ObsPoolTrace.reclaimGate(
                        today, candidate.code, candidate.name, false,
                        String.format(java.util.Locale.CHINA, "bias=%.2f%%", bias),
                    )
                    return@mapNotNull null
                }
                // 价格形态转强还不够：主力是否在借反弹出货、有没有资金承接，决定值不值得进观察卡。
                qualityByCode[candidate.code] = buyPointFundFlowDecision(candidate.code, quote)
                candidateByCode[candidate.code] = candidate
                FirstBoardReclaimTracker.Sample(
                    code = candidate.code,
                    name = candidate.name,
                    tradingDay = today,
                    previousClose = quote.prevClose,
                    open = quote.open,
                    low = quote.low,
                    price = quote.price,
                    timestamp = System.currentTimeMillis(),
                    recentFirstBoard = true,
                    limitUp = ObservationEligibility.isAtLimitUp(candidate.code, candidate.name, quote),
                    biasFromMa5Pct = bias,
                )
            }
        candidates.forEach { sample ->
            val alert = firstBoardReclaimTracker.update(sample) ?: return@forEach
            val quality = qualityByCode[sample.code]
            if (quality != null && !quality.allow) {
                // 形态满足但资金在出货且无承接：回滚 emitted，资金转好后同一形态仍可再记录。
                firstBoardReclaimTracker.rearm(sample.code)
                ObsPoolTrace.reclaimGate(today, sample.code, sample.name, false, "fundFlow")
                return@forEach
            }
            val candidate = candidateByCode[sample.code]
            val boardDate = candidate?.let(FirstBoardClockPolicy::effectiveBoardDate).orEmpty()
            val record = FirstBoardReclaimObservation(
                code = alert.code,
                name = sample.name.ifBlank { alert.code },
                tradingDay = today,
                detectedAt = TimeUtils.nowText(),
                firstBoardDate = boardDate,
                tradingDaysAfterBoard = FirstBoardReclaimPolicy.tradingDaysAfter(
                    boardDate,
                    today,
                    tradingDates,
                ),
                previousClose = sample.previousClose,
                open = sample.open,
                low = sample.low,
                price = sample.price,
                biasFromMa5Pct = sample.biasFromMa5Pct,
                fundFlowEvidence = quality?.evidence.orEmpty(),
                fundFlowReason = quality?.reason.orEmpty(),
                boards = candidate?.boards.orEmpty(),
            )
            Repo.firstBoardReclaimObservations = store.appendFirstBoardReclaimObservation(record, today)
            ObsPoolTrace.reclaimObservation(today, alert.code, sample.price)
            Repo.notifyChanged()
        }
    }

    /**
     * 首板观察的价格边界：用当日已缓存的日线计算动态 MA5 乖离。
     * 缓存缺失时返回 null，由 [FirstBoardReclaimPolicy.withinPriceBound] 按“数据缺失”处理，
     * 不把缺失当成 0%。
     */
    private fun ma5BiasPct(code: String, price: Double, today: String): Double? {
        if (!price.isFinite() || price <= 0.0) return null
        val history = observationHistory[code]?.takeIf { it.first == today }?.second ?: return null
        if (history.size < 4) return null
        val ma5 = (history.takeLast(4).sumOf { it.close } + price) / 5.0
        if (!ma5.isFinite() || ma5 <= 0.0) return null
        return (price / ma5 - 1.0) * 100.0
    }

    /**
     * 封板冲刺预警 + 封板/炸板提醒（见 [SealSprintTracker] / [LimitSealTracker]）。
     *
     * 检测覆盖持仓 + 自选股 + 观察池，但推送只保留持仓事实级事件——此前封板/炸板只给板块龙头的
     * 做T闸门和页面展示用，持仓封板本身从不推送，只能靠盯盘发现。按跌预警同口径
     * 只看 alertsEnabled，不占共享推送预算：事实不能被机会类预算吞掉。
     */
    private fun checkSealAlerts(
        calendar: Calendar,
        currentQuotes: Map<String, Quote>,
    ) {
        if (!store.alertsEnabled || !TradingCalendar.inMarketHoursForAlert(calendar)) return
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
            if (!StockAlertScope.shouldNotify(
                    StockAlertType.SEAL_SPRINT,
                    event.held,
                    watchlistEnabled = store.watchlistIntradayAlerts,
                )
            ) {
                return@forEach
            }
            sendCheckedAlert(
                type = "sealSprint",
                code = event.code,
                id = NotificationIds.of(ALERT_ID_SEAL_SPRINT, event.code),
                title = event.title,
                body = event.body,
                signal = event.held,
                consumeBudget = false,
            )
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
            if (!StockAlertScope.shouldNotify(
                    StockAlertType.SEAL_STATUS,
                    event.held,
                    watchlistEnabled = store.watchlistIntradayAlerts,
                )
            ) {
                return@forEach
            }
            sendCheckedAlert(
                type = "sealStatus-${event.kind.name}",
                code = event.code,
                id = NotificationIds.of(ALERT_ID_SEAL, event.code),
                title = event.title,
                body = event.body,
                signal = event.vibrate && event.held,
                consumeBudget = false,
            )
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

    private fun refundPushBudget(code: String) {
        if (code in MARKET_INDEX_CODES) return
        val used = dailyPushCount[code] ?: return
        if (used <= 1) dailyPushCount.remove(code) else dailyPushCount[code] = used - 1
    }

    /**
     * 统一提醒投递：开关 → 行情新鲜度 → 当日事件去重 → 推送预算 → 投递。
     *
     * 去重键在投递前同步落盘（重启后仍生效）；投递失败或预算不足时回滚，
     * 下一轮满足条件可以重试。`signal=false` 走普通通知，不占用震动通道。
     */
    private fun sendCheckedAlert(
        type: String,
        code: String,
        id: Int,
        title: String,
        body: String,
        signal: Boolean,
        quoteFresh: Boolean = true,
        consumeBudget: Boolean = true,
        /**
         * 当日事件身份。默认 `type`（每码每天一次）；下探回升按低点、反弹跟踪按事件类型
         * 传入更细的键——它们的引擎本身允许同日多次，不能被“每日一次”压掉。
         */
        eventKey: String = type,
    ): Boolean {
        if (!store.alertsEnabled) return false
        if (signal && !store.intradaySignalEnabled) return false
        val today = TimeUtils.todayText()
        val key = "$eventKey:$code"
        val reserved = store.reserveAlertEventKey("$today:$key", today)
        val budgetOk = if (reserved && consumeBudget) consumePushBudget(code) else true
        val decision =
            AlertOrchestrator.decide(
                AlertOrchestrator.Input(
                    alertsEnabled = store.alertsEnabled,
                    intradayEnabled = if (signal) store.intradaySignalEnabled else true,
                    quoteFresh = quoteFresh,
                    duplicate = !reserved,
                    budgetAvailable = budgetOk,
                ),
            )
        if (decision != AlertOrchestrator.Decision.SEND) {
            if (reserved) store.releaseAlertEventKey("$today:$key", today)
            // 行情过期时预算已经扣过，必须回滚；SKIP_DUPLICATE 没扣、SKIP_BUDGET 没扣成功。
            if (decision == AlertOrchestrator.Decision.SKIP_STALE && reserved && consumeBudget) {
                refundPushBudget(code)
            }
            when (decision) {
                AlertOrchestrator.Decision.SKIP_DUPLICATE -> ObsPoolTrace.deliveryDuplicate(type, today, code)
                AlertOrchestrator.Decision.SKIP_BUDGET -> ObsPoolTrace.reclaimBudgetBlocked(today, code)
                else -> Unit
            }
            return false
        }
        val posted =
            if (signal) {
                Notifier.signal(this, id, title, body)
            } else {
                Notifier.alert(this, id, title, body)
            }
        if (!posted) {
            store.releaseAlertEventKey("$today:$key", today)
            if (consumeBudget) refundPushBudget(code)
            ObsPoolTrace.deliveryFailed(type, today, code)
        }
        return posted
    }

    /**
     * 通知里统一显示股票名称。候选名可能缺失，或被旧缓存/外部导入写成代码，
     * 此时用行情、自选和持仓里的名称兜底；实在拿不到才退回代码。
     */
    private fun alertDisplayName(code: String, fallback: String): String {
        fun usable(name: String?): String? =
            name?.takeIf { it.isNotBlank() && it != code }
        return usable(fallback)
            ?: usable(Repo.quotes[code]?.name)
            ?: usable(store.watchlist.firstOrNull { it.code == code }?.name)
            ?: usable(store.portfolio.positions.firstOrNull { it.code == code }?.name)
            ?: usable(Repo.watchlistOrder.firstOrNull { it.first == code }?.second)
            ?: fallback
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
        val scopedCodes = (
            StockAlertScope.heldCodes(store.portfolio.positions) +
                store.watchlist.map { it.code } +
                store.observationPool
                    .filter { it.stage != ObservationStage.ELIMINATED }
                    .map { it.code }
        ).distinct()
        val now = System.currentTimeMillis()
        val today = TimeUtils.todayText()
        val nowLocal = calendar.toInstant()
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime()
        for (code in scopedCodes) {
            if (code.startsWith("sh000") || code.startsWith("sz399")) continue
            val lastFetchedAt = intradayLastFetchAtMs[code] ?: 0L
            if (lastFetchedAt == 0L || now - lastFetchedAt >= MINUTE_KLINE_REFRESH_MS) {
                intradayLastFetchAtMs[code] = now
                runCatching { sina.intradayKline(code, 15, MINUTE_KLINE_LENGTH) }
                    .onSuccess { bars ->
                        intraday15ByCode[code] = IntradayBarWindow(bars.filter { it.timestamp.startsWith(today) })
                            .withCompletionState(nowLocal, today, 15)
                    }
                    .onFailure { Log.w(TAG, "15分钟K线暂不可用：code=$code", it) }
                runCatching { sina.intradayKline(code, 30, MINUTE_KLINE_LENGTH) }
                    .onSuccess { bars ->
                        intraday30ByCode[code] = IntradayBarWindow(bars.filter { it.timestamp.startsWith(today) })
                            .withCompletionState(nowLocal, today, 30)
                    }
                    .onFailure { Log.w(TAG, "30分钟K线暂不可用：code=$code", it) }
            }

            val quote = currentQuotes[code]?.takeIf { it.quoteDate == today } ?: continue
            if (!TradingCalendar.isMarketOpen(calendar)) continue
            intraday15ByCode.computeIfAbsent(code) { IntradayBarWindow(emptyList()) }
                .let { window ->
                    intraday15ByCode[code] = upsertLiveBar(
                        window.withCompletionState(nowLocal, today, 15),
                        quote,
                        currentSlotEndTimestamp(calendar, 15),
                    )
                }
            intraday30ByCode.computeIfAbsent(code) { IntradayBarWindow(emptyList()) }
                .let { window ->
                    intraday30ByCode[code] = upsertLiveBar(
                        window.withCompletionState(nowLocal, today, 30),
                        quote,
                        currentSlotEndTimestamp(calendar, 30),
                    )
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
                completed = current?.completed ?: false,
        )
        return if (live.valid) window.upsert(live) else window
    }

    /** 当前未完成槽位按结束时间标记；证据层只接受小于等于最新已完成槽位的K线。 */
    private fun currentSlotEndTimestamp(calendar: Calendar, minutes: Int): String {
        val local = calendar.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        val slotStartMinute = local.minute / minutes * minutes
        return local.withMinute(slotStartMinute)
            .plusMinutes(minutes.toLong())
            .withSecond(0)
            .withNano(0)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    /**
     * 盘中量价异动：持仓 + 自选 + 观察池同一套检测，只报告量价事实。
     * 持仓沿用点火/异动分级；非持仓仅在开启“自选股盘中提醒”时，推“强”级别并占统一预算。
     */
    private fun checkVolumePriceAnomalies(
        calendar: Calendar,
        currentQuotes: Map<String, Quote>,
    ) {
        if (!store.alertsEnabled) return
        val heldCodes = StockAlertScope.heldCodes(store.portfolio.positions)
        val watchCodes = store.watchlist
            .map { it.code }
            .filter { !it.startsWith("sh000") && !it.startsWith("sz399") }
        val observationCodes = store.observationPool
            .filter { it.stage != ObservationStage.ELIMINATED }
            .map { it.code }
        val scopeCodes = (heldCodes + watchCodes + observationCodes).distinct()
        if (scopeCodes.isEmpty() || !TradingCalendar.inMarketHoursForAlert(calendar)) return
        val nowMs = System.currentTimeMillis()
        val needsFundFlowRefresh =
            scopeCodes.any { intradayFundFlowTracker.context(it) == null } ||
                nowMs - lastVolumePriceFundFlowRefreshAtMs.get() >= 60_000L
        if (needsFundFlowRefresh) {
            lastVolumePriceFundFlowRefreshAtMs.set(nowMs)
            refreshFundFlowHistory(scopeCodes)
        }
        val timestamp = calendar.toInstant()
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime()
        if (!VolumePriceAnomalyTracker.isDetectionTime(timestamp)) return
        val today = TimeUtils.todayText()
        for (code in scopeCodes) {
            val quote = currentQuotes[code] ?: continue
            if (quote.quoteDate != today) continue
            val alert =
                volumePriceAnomalyTracker.update(
                    VolumePriceAnomalyTracker.Sample(
                        code = code,
                        name = quote.name,
                        price = quote.price,
                        cumulativeVolume = quote.volume,
                        cumulativeTurnover = quote.turnover,
                        sessionHigh = quote.high,
                        timestamp = timestamp,
                    ),
                    today,
                    intradayFundFlowTracker.context(code),
                ) ?: continue
            volumePriceAnomalyAtMs[code] = nowMs
            val id = NotificationIds.of(ALERT_ID_VOLUME_PRICE_ANOMALY, code)
            if (code in heldCodes) {
                if (alert.strong) {
                    Notifier.intradayIgnition(this, id, alert.title, alert.body)
                } else {
                    Notifier.volumePriceAnomaly(this, id, alert.title, alert.body)
                }
                continue
            }
            if (!store.watchlistIntradayAlerts || !alert.strong) continue
            if (!consumePushBudget(code)) continue
            val posted = Notifier.alert(this, id, alert.title, alert.body)
            if (!posted) refundPushBudget(code)
        }
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
        if (!store.alertsEnabled) return
        val portfolio = store.portfolio
        val heldCodes = StockAlertScope.heldCodes(portfolio.positions)
        HoldingAnalyzer.holdingTTracker.retainCodes(heldCodes)
        HoldingAnalyzer.holdingBreakTracker.retainCodes(heldCodes)
        intradaySignalState.retainCodes(heldCodes)
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
            intradaySignalState.clear()
            supportBreakStates.clear()
            HoldingAnalyzer.holdingTTracker.clear()
            HoldingAnalyzer.holdingBreakTracker.clear()
            leaderStrengthTracker.clear()
            // 去重键不含日期：不换日清零的话，昨天推过的状态会永久吞掉今天同样的风险提醒。
            portfolioSignals.clear()
            intradayAdditionSamples.clear()
            intradayAdditionSampleDay = today
        }
        val levels = Repo.portfolioResults.associateBy { it.code }
        val holdings = levels.values.toList()
        val holdingCount = holdings.size
        val systemicRisk = holdingCount > 0 && (
            holdings.count { it.changePct < 0.0 } >= (holdingCount + 1) / 2 ||
                Repo.marketState?.regime == MarketRegime.COLLAPSE
            )
        val portfolioRisk = holdingCount >= 3 && actionablePortfolioRiskCount(holdings) >= (holdingCount + 1) / 2
        val boardGroups = store.boardGroups
        val now = System.currentTimeMillis()
        val adaptiveMarketTurnoverRatio = marketTurnoverRatio(currentQuotes, calendar)
        for (position in portfolio.positions) {
            if (position.code !in heldCodes || !position.quantity.isFinite() || position.quantity <= 0) continue
            val quote = currentQuotes[position.code] ?: continue
            val tObservation = HoldingAnalyzer.holdingTTracker.observe(quote, today, now)
            if (quote.quoteDate != today) {
                intradaySignalState.clearPending(position.code)
                continue
            }
            updateStrongContinuationEventCurrentPrice(position.code, quote.price)
            val level = levels[position.code]
            // 跨日旧分析结果：支撑/MA20/入场结构都是昨天的，不能据此生成盘中点位。
            if (level != null && !com.example.stockmonitor.analysis.IntradayLevelFreshness
                    .usable(level.analysisDate, today)
            ) {
                intradaySignalState.clearPending(position.code)
                continue
            }
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
            val priceWindow = priceWindows[position.code]?.takeIf { volumeBaselineDay == today }
            val currentPriceContext = priceWindow?.contextOf(quote.price)
            val dailyInput = DailyRiskContextInput(
                quoteDate = level?.analysisDate.orEmpty(),
                priceLocation = currentPriceContext?.location?.name
                    ?: if (level?.highPosition?.phase?.name != "NONE") "HIGH" else null,
                rangeLow = level?.highPositionEvidence?.rangeLow ?: priceWindow?.low,
                invalidationPrice = level?.entrySetup?.stopPrice,
                majorTrend = level?.trendAssessment?.major?.name,
                fiveDayNoNewLow = level?.fiveDayNoNewLow,
                closesBelowMa20 = level?.closesBelowMa20 ?: 0,
                highExitRisk = level?.highPosition?.phase?.name == "DISTRIBUTION_RISK" ||
                    (currentPriceContext?.location?.name == "HIGH" && level?.entrySetup?.trendWeak == true),
                systemicRisk = systemicRisk,
                portfolioRisk = portfolioRisk,
                eventRisk = (
                    Repo.fundamentalHealth[position.code]?.health?.level?.name == "ALERT" ||
                        level?.fundamentalHealth?.level?.name == "ALERT"
                    ),
                positionRisk = (level?.positionPct ?: if (portfolio.totalCapital > 0) {
                    position.quantity * position.cost / portfolio.totalCapital * 100.0
                } else {
                    null
                })?.let { it > 10.0 } == true,
                liquidityRisk = false,
            )
            val dailyContext = if (level != null) {
                dailyRiskContextCache
                    .getOrBuild(position.code, quote.quoteDate?.substringBefore(' ')) { dailyInput }
                    ?.withCurrentInput(dailyInput)
            } else {
                null
            }
            val bars15 = intraday15ByCode[position.code]?.bars.orEmpty()
            val bars30 = intraday30ByCode[position.code]?.bars.orEmpty()
            val nowLocal = calendar.toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            val timeframeEvidence = TimeframeEvidence.build(
                bars15 = bars15,
                bars30 = bars30,
                price = quote.price,
                support = level?.support ?: 0.0,
                resistance = level?.resistance ?: 0.0,
                now = nowLocal,
                quoteDate = today,
                dailyContext = dailyContext,
                riskLine = level?.let {
                    com.example.stockmonitor.analysis.NormalDivergencePolicy.riskLine(
                        price = quote.price,
                        ma5 = it.ma5,
                        ma20 = it.ma20,
                        priceLocation = currentPriceContext?.location,
                        biasFromMa5Pct = it.ma5.takeIf { ma5 -> ma5 > 0.0 }
                            ?.let { ma5 -> (quote.price / ma5 - 1.0) * 100.0 },
                    )
                },
            )
            val volumeContext = IntradayVolumeContext.build(
                marketPredictedFullDayTurnover = turnoverSnapshot?.predictedFullDay?.div(100_000_000.0),
                recentCompleteMarketTurnovers = recentTurnovers,
                stockVolumeRatio = Repo.volumeRatios[position.code] ?: level?.volumeRatio,
                priceChangePct = quote.changePct,
                bars15 = bars15,
                price = quote.price,
                support = level?.support ?: 0.0,
                now = nowLocal,
                quoteDate = quote.quoteDate?.substringBefore(' '),
            )
            // 接回只使用当前已完成15分钟K形成的不可变量能快照；实时槽位、全天累计量比均不参与。
            val pullbackPhaseVolumeEvidence = PullbackPhaseVolumeEvidence.build(
                bars15 = bars15,
                support = level?.support ?: 0.0,
                ma5 = level?.ma5 ?: 0.0,
                ma20 = level?.ma20 ?: 0.0,
                atrPct = level?.volatility?.atrPct,
                now = nowLocal,
                quoteDate = quote.quoteDate?.substringBefore(' '),
            )
            val marketHealthy = Repo.marketState?.regime?.let {
                it != MarketRegime.WEAK && it != MarketRegime.COLLAPSE
            } == true
            val sectorHealthy = sectorContext?.state?.regime?.let {
                it != MarketRegime.WEAK && it != MarketRegime.COLLAPSE
            }
            val dualTimeframeDecision = DualTimeframePolicy.decide(
                riskSignal = false,
                execution = IntradayBarWindow(
                    bars15.filter {
                        it.completed &&
                            timeframeEvidence.lastCompleted15m?.let { last -> it.timestamp <= last } == true
                    },
                ),
                direction = IntradayBarWindow(
                    bars30.filter {
                        it.completed &&
                            timeframeEvidence.lastCompleted30m?.let { last -> it.timestamp <= last } == true
                    },
                ),
            )
            val highPositionDecision = level?.highPositionEvidence?.let {
                HighPositionPolicy.evaluateIntraday(
                    evidence = it.copy(
                        marketHealthy = marketHealthy,
                        sectorHealthy = sectorHealthy,
                    ),
                    price = quote.price,
                    volumeRatio = Repo.volumeRatios[position.code],
                    dualTimeframeAllowed = dualTimeframeDecision.allowed,
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
                    score = level?.intradayScore ?: 0,
                    quantity = position.quantity,
                    cost = position.cost,
                    totalCapital = portfolio.totalCapital,
                    availableCapital = portfolio.resolvedAvailableCapital(),
                    initialEntryPct = portfolio.initialEntryPct,
                    maxPositionPct = portfolio.maxPositionPct,
                    sellableQuantity = availabilityByCode[position.code]?.actionableSellableToday,
                    positionAllocation = store.positionAllocations[position.code],
                    lockedQuantity = availabilityByCode[position.code]?.lockedToday ?: 0.0,
                    untrackedQuantity = availabilityByCode[position.code]?.untrackedQuantity ?: 0.0,
                    ledgerConsistent = availabilityByCode[position.code]?.ledgerConsistent ?: false,
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
                    sessionMinutes = tradingMinutesSinceOpen(nowLocal.toLocalTime()),
                    priceContext = currentPriceContext,
                    trendAssessment = level?.trendAssessment,
                    boxRange = level?.boxRange,
                    highPosition = highPositionDecision,
                    gain5Pct = level?.highPositionEvidence?.gain5Pct,
                    rangeHigh = level?.highPositionEvidence?.rangeHigh,
                    tradeDate = today,
                    dailyRiskContext = dailyContext,
                    timeframeEvidence = timeframeEvidence,
                    volumeContext = volumeContext,
                    pullbackPhaseVolumeEvidence = pullbackPhaseVolumeEvidence,
                    // 预测做T卖仅是策略提示，绝不回填接回容量；没有真实成交流水时始终观察。
                    reentryCapacityQuantity = 0.0,
                )
            checkIntradayAdditionOpportunity(
                position = position,
                quote = quote,
                indexQuote = currentQuotes["sh000001"],
                level = level,
                plan = Repo.portfolioSwing?.validatedFor(portfolio),
                timeframeEvidence = timeframeEvidence,
                volumeContext = volumeContext,
                tacticsInput = tacticsInput,
                today = today,
                nowMs = now,
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
                intradaySignalState.clearCode(position.code)
                continue
            }
            var signal: IntradaySignal = evaluatedSignal
            var timeframeReason: String? = null
            if (signal.type.requiresDualTimeframe) {
                if (!dualTimeframeDecision.allowed) {
                    timeframeReason = dualTimeframeDecision.reason
                    signal = IntradayTactics.watchOnly(signal, dualTimeframeDecision)
                }
            }
            if (signal.type.isPureObservation) {
                // 与上面 evaluatedSignal == null 的分支同语义：条件已转入纯观察态，
                // 上一次提交的 active 也要一并清掉，否则第二次同类信号会被
                // canEmit 的 activeKey 匹配吞掉，用户再也收不到提醒。
                intradaySignalState.clearCode(position.code)
                continue
            }
            if (!signal.type.isRisk && !store.intradaySignalEnabled) {
                intradaySignalState.clearPending(position.code)
                continue
            }

            val requiredRounds = IntradayTactics.confirmationRounds(signal.type)
            if (requiredRounds == 0) {
                intradaySignalState.clearPending(position.code)
                continue
            }
            if (!intradaySignalState.confirmed(position.code, signal.type, requiredRounds)) continue
            val key = position.code + "|" + signal.type.name
            val guarded = TradeNotification.intradayGuarded(tacticsInput, signal, timeframeReason)
            if (guarded.requiresExitReview) {
                publishExitReview(today, position.code, position.name, guarded, timeframeEvidence)
                continue
            }
            // 「破位提示」是 NOTICE 缓释档：只报告风险事实，不生成卖量，也不占机会类预算。
            // 此前 RISK_FACT 被 ACTIONABLE 过滤掉，导致这一档在 UI 可见但从不通知。
            if (guarded.level == GuardedActionLevel.RISK_FACT &&
                (signal.type == IntradaySignalType.BREAK_NOTICE ||
                    signal.type == IntradaySignalType.GAP_FADE)
            ) {
                sendCheckedAlert(
                    type = "breakNotice",
                    code = position.code,
                    id = NotificationIds.of(ALERT_ID_SIGNAL, "breakNotice|${position.code}"),
                    title = signal.title,
                    body = guarded.text,
                    signal = false,
                    consumeBudget = false,
                )
                continue
            }
            if (guarded.level != GuardedActionLevel.ACTIONABLE) continue
            // 同一「放量」事件去重：量价异动通道刚提醒过的，短窗内不再从 BREAKOUT 通道
            // 重复推送——两套量能基准（3×短窗均量 vs 量比1.3/20日均量）相互独立，
            // 不去重的话同一次放量突破用户会收到两条口径不同的通知。
            if (signal.type == IntradaySignalType.BREAKOUT &&
                now - (volumePriceAnomalyAtMs[position.code] ?: 0L) < VOLUME_PRICE_ANOMALY_DEDUP_MS
            ) {
                intradaySignalState.clearPending(position.code)
                continue
            }
            val riskSignal = guarded.action == GuardedAction.RISK_EXIT ||
                guarded.action == GuardedAction.RISK_REDUCE
            val riskStage = if (riskSignal) timeframeEvidence.riskConfirmations.coerceIn(0, 3) else 0
            // 破位重发节奏（用户拍板 stage≥2 才重发）：首次破位照报；此后第 1 根 15 分钟
            // 风险K线完成不再重发，第 2/3 根证据坐实时各再报一次——同一破位事件最多 3 条，
            // 不再每根风险K线都推（此前绕过冷却与日限最多连推 4 条）。
            if (riskSignal && riskStage < 2 && intradaySignalState.hasActiveRisk(position.code, signal.type)) {
                continue
            }
            if (!intradaySignalState.canEmit(position.code, signal.type, now, riskSignal, riskStage)) continue
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
            // 持仓复查放在扣预算之前：复评中途清仓时预算不能白扣一条。
            if (position.code !in StockAlertScope.heldCodes(store.portfolio.positions)) continue
            var budgetConsumed = false
            val allowed = if (riskSignal) {
                true
            } else if (store.intradaySignalEnabled) {
                budgetConsumed = consumePushBudget(position.code)
                budgetConsumed
            } else {
                false
            }
            if (allowed) {
                val advice = when {
                    guarded.action == GuardedAction.RISK_EXIT -> AdviceDirection.SELL to AdviceKind.EXIT
                    guarded.action == GuardedAction.RISK_REDUCE -> AdviceDirection.SELL to AdviceKind.REDUCE
                    signal.type in setOf(IntradaySignalType.T_BUY, IntradaySignalType.RECLAIM_SUPPORT) -> AdviceDirection.BUY to AdviceKind.T_BUY
                    signal.type == IntradaySignalType.T_SELL -> AdviceDirection.SELL to AdviceKind.T_SELL
                    signal.type in setOf(
                        IntradaySignalType.REDUCE_POSITION,
                        IntradaySignalType.BREAK_SUPPORT,
                        IntradaySignalType.BREAKOUT_REVERSAL_RISK,
                        IntradaySignalType.HIGH_REDUCE_POSITION,
                    ) -> AdviceDirection.SELL to AdviceKind.REDUCE
                    signal.type in setOf(
                        IntradaySignalType.EXIT_POSITION,
                        IntradaySignalType.EXIT_WINDOW,
                    ) -> AdviceDirection.SELL to AdviceKind.EXIT
                    else -> AdviceDirection.BUY to AdviceKind.HOLDING_ADD
                }
                val targetQuantity = if (advice.first == AdviceDirection.BUY) {
                    guarded.maxBuyQuantity
                } else {
                    guarded.maxSellQuantity
                }
                val posted = publishDailyAdvice(
                    date = today,
                    code = position.code,
                    direction = advice.first,
                    kind = advice.second,
                    targetQuantity = targetQuantity,
                    currentQuantity = position.quantity,
                    title = signal.title,
                    riskPriority = TradeNotification.riskPriority(guarded),
                    riskStage = riskStage,
                ) { quantity, continuity ->
                    val finalAction = TradeNotification.intradayGuarded(tacticsInput, signal, timeframeReason, quantity)
                    if (finalAction.level != GuardedActionLevel.ACTIONABLE) return@publishDailyAdvice false
                    val estimate = if (advice.first == AdviceDirection.SELL) TradeNotification.estimateSell(
                        holdingQuantity = position.quantity,
                        averageCost = position.cost,
                        price = quote.price,
                        quantity = finalAction.maxSellQuantity,
                    ) else null
                    val notificationBody = finalAction.text +
                        estimate?.let { "｜${TradeNotification.sellEstimateText(it)}" }.orEmpty() + "｜$continuity" +
                        levelFreshnessNote(level)
                    if (riskSignal) {
                        Notifier.riskSell(
                            this,
                            NotificationIds.of(ALERT_ID_SIGNAL, key),
                            signal.title,
                            notificationBody,
                        )
                    } else {
                        Notifier.signal(
                            this,
                            NotificationIds.of(ALERT_ID_SIGNAL, key),
                            signal.title,
                            notificationBody,
                        )
                    }
                }
                if (posted) {
                    intradaySignalState.commit(position.code, signal.type, now, riskSignal, riskStage)
                } else if (budgetConsumed) {
                    // 计划互斥/复核态/数量不足等失败会每 tick 重试；预算必须回滚，
                    // 否则 8 条/日配额会在 2 分钟内被无效重试耗光。
                    refundPushBudget(position.code)
                }
            }
        }
    }

    /**
     * 严格价格窗口的盘中补仓复核。基础候选来自最近一次持仓分析，当前函数只负责新鲜证据、
     * 价格窗口和已有统一交易门控；条件消失时不生成反向卖出建议。
     */
    private fun checkIntradayAdditionOpportunity(
        position: com.example.stockmonitor.core.Position,
        quote: Quote,
        indexQuote: Quote?,
        level: com.example.stockmonitor.analysis.PositionResult?,
        plan: com.example.stockmonitor.analysis.SwingPlan?,
        timeframeEvidence: TimeframeEvidence,
        volumeContext: IntradayVolumeContext?,
        tacticsInput: IntradayTactics.Input,
        today: String,
        nowMs: Long,
    ) {
        val swing = plan?.rows?.firstOrNull { it.code == position.code && it.addQuantity > 0.0 }
        val support = level?.support?.takeIf { it.isFinite() && it > 0.0 } ?: return clearIntradayAdditionSamples(position.code)
        // 支撑位来自 30 分钟槽位的持仓分析：超龄时不再据此生成“补仓”建议，避免拿半小时前的位置下单。
        if (levelStale(level)) return clearIntradayAdditionSamples(position.code)
        val stop = swing?.riskPrice?.takeIf { it.isFinite() && it > 0.0 }
            ?: level?.entrySetup?.stopPrice?.takeIf { it.isFinite() && it > 0.0 }
        val pressure = swing?.target?.takeIf { it.isFinite() && it > 0.0 }
            ?: level?.resistance?.takeIf { it.isFinite() && it > 0.0 }
        if (swing == null || stop == null || pressure == null) {
            clearIntradayAdditionSamples(position.code)
            return
        }
        val createdAtMs = parseAnalysisTimeMs(plan.asOf) ?: run {
            clearIntradayAdditionSamples(position.code)
            return
        }
        val portfolioFingerprint = store.portfolio.positions
            .sortedBy { it.code }
            .joinToString("|") { "${it.code}:${it.quantity}:${it.cost}" } +
            "|cash=${store.portfolio.resolvedAvailableCapital()}"
        val evaluationFingerprint = listOf(
            plan.asOf,
            swing.code,
            swing.addQuantity,
            swing.riskPrice,
            swing.target,
            swing.rewardRisk,
        ).joinToString("|")
        val base = IntradayAdditionBase(
            code = position.code,
            name = position.name,
            tradingDate = today,
            createdAtMs = createdAtMs,
            support = support,
            pressure = pressure,
            stopPrice = stop,
            baselineAddQuantity = swing.addQuantity,
            maxQuantityAtHighestPrice = swing.addQuantity,
            riskRewardApproved = swing.rewardRisk?.let { it >= PortfolioSwing.MIN_REWARD_RISK } == true,
            availableCapital = store.portfolio.resolvedAvailableCapital(),
            portfolioFingerprint = portfolioFingerprint,
            evaluationFingerprint = evaluationFingerprint,
        )
        if (intradayAdditionSampleDay != today) {
            intradayAdditionSamples.clear()
            intradayAdditionSampleDay = today
        }
        val samples = intradayAdditionSamples.getOrPut(position.code) { mutableListOf() }
        val previousSample = samples.lastOrNull()
        val indexFresh = indexQuote?.quoteDate?.substringBefore(' ') == today
        val marketTimeframeEvidence = indexQuote?.let {
            TimeframeEvidence.build(
                bars15 = intraday15ByCode["sh000001"]?.bars.orEmpty(),
                bars30 = intraday30ByCode["sh000001"]?.bars.orEmpty(),
                price = it.price,
                support = 0.0,
                resistance = 0.0,
                now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
                quoteDate = today,
            )
        }
        val timeframeConfirmed = timeframeEvidence.complete &&
            timeframeEvidence.m15Confirmations >= 2 && timeframeEvidence.m30Aligned
        val volumeConfirmed = volumeContext?.stockVolumeConfirming == true &&
            volumeContext.marketExpanding == true &&
            volumeContext.barVolumeConfirming == true
        if (previousSample?.timestampMs != nowMs) {
            samples += IntradayAdditionSample(
                timestampMs = nowMs,
                price = quote.price,
                quoteDate = quote.quoteDate?.substringBefore(' ').orEmpty(),
                marketQuoteDate = indexQuote?.quoteDate?.substringBefore(' ').orEmpty(),
                sequence = samples.size + 1,
                priceRecovered = previousSample == null || quote.price >= previousSample.price,
                indexConfirmed = indexFresh && marketTimeframeEvidence?.complete == true &&
                    timeframeConfirmed && Repo.marketState?.regime != MarketRegime.COLLAPSE,
                fundingConfirmed = volumeConfirmed,
                indexAgeMs = if (indexFresh) {
                    indexQuote?.quoteDate?.let { quoteAgeMs(it, nowMs) } ?: 0L
                } else {
                    Long.MAX_VALUE
                },
                timeframeAgeMs = if (timeframeConfirmed && marketTimeframeEvidence?.complete == true) 0L else Long.MAX_VALUE,
            )
            while (samples.size > 8) samples.removeAt(0)
        }
        if (store.intradayAdditionStateCorrupted) {
            Log.w(TAG, "盘中补仓状态损坏，暂停 ${position.code}")
            return
        }
        val previousState = runCatching {
            store.intradayAdditionStates[position.code]
                ?.takeIf { it.date == today && it.portfolioFingerprint == base.portfolioFingerprint && it.evaluationFingerprint == base.evaluationFingerprint }
        }.getOrElse {
            Log.w(TAG, "盘中补仓状态损坏，暂停 ${position.code}", it)
            return
        }
        val decision = IntradayAdditionPolicy.evaluate(
            base = base,
            currentPrice = quote.price,
            quoteDate = quote.quoteDate?.substringBefore(' '),
            marketQuoteDate = indexQuote?.quoteDate?.substringBefore(' '),
            nowMs = nowMs,
            samples = samples.toList(),
            previousState = previousState,
        )
        decision.state?.let { state ->
            runCatching {
                val states = store.intradayAdditionStates.toMutableMap()
                states[position.code] = state
                store.intradayAdditionStates = states
            }.onFailure { Log.w(TAG, "盘中补仓状态保存失败 ${position.code}", it) }
        }
        if (decision.status == IntradayAdditionStatus.OUTSIDE_WINDOW) {
            samples.clear()
            return
        }
        if (!decision.isActionable) return

        val strictSignal = IntradaySignal(
            type = IntradaySignalType.PULLBACK,
            title = "📈 ${position.name}·严格价格窗口补仓",
            body = "现价 ${String.format(Locale.CHINA, "%.2f", quote.price)} 在允许区间内，等待统一交易门控复核",
        )
        val guarded = TradeNotification.intradayGuarded(tacticsInput, strictSignal)
        if (guarded.level != GuardedActionLevel.ACTIONABLE) return
        val quantity = minOf(decision.quantity, guarded.maxBuyQuantity)
        if (quantity < IntradayAdditionPolicy.LOT) return
        val lower = decision.lowerBound ?: return
        val upper = decision.upperBound ?: return
        val title = "📈 ${position.name}·严格价格窗口补仓"
        publishDailyAdvice(
            date = today,
            code = position.code,
            direction = AdviceDirection.BUY,
            kind = AdviceKind.HOLDING_ADD,
            targetQuantity = quantity,
            currentQuantity = position.quantity,
            title = title,
        ) { finalQuantity, continuity ->
            val finalGuarded = TradeNotification.intradayGuarded(
                tacticsInput,
                strictSignal,
                maxActionQuantity = finalQuantity,
            )
            if (finalGuarded.level != GuardedActionLevel.ACTIONABLE || finalGuarded.maxBuyQuantity < finalQuantity) {
                return@publishDailyAdvice false
            }
            // 最终投递前再核一次可用现金：计划生成后到发布之间可能已用掉现金。
            val estimatedCost = finalQuantity * quote.price
            val feeReserve = maxOf(5.0, estimatedCost * 0.001)
            if (!estimatedCost.isFinite() ||
                estimatedCost + feeReserve > store.portfolio.resolvedAvailableCapital() + 0.01
            ) {
                return@publishDailyAdvice false
            }
            val body = String.format(
                Locale.CHINA,
                "报价时间 %s｜现价 %.2f｜允许买入区间 %.2f～%.2f｜最高接受价 %.2f｜建议数量 %.0f 股｜投入约 %.2f 元｜跌破 %.2f 失效",
                TimeUtils.clockText(), quote.price, lower, upper, decision.acceptedPrice ?: upper,
                finalQuantity, finalQuantity * quote.price, stop,
            ) + "｜$continuity"
            Notifier.signal(this, NotificationIds.of(ALERT_ID_SIGNAL, position.code), title, body)
        }
    }

    private fun clearIntradayAdditionSamples(code: String) {
        intradayAdditionSamples.remove(code)
    }

    private fun parseAnalysisTimeMs(value: String): Long? {
        val normalized = value.trim().let { if (it.length == 16) "$it:00" else it }
        return TimeUtils.parseDateTime(normalized)?.time
    }

    /** 持仓分析给出的支撑/压力超过 [StrategyThresholds.LEVEL_MAX_AGE_MS] 即标注来源时间。 */
    private fun levelFreshnessNote(level: com.example.stockmonitor.analysis.PositionResult?): String {
        level ?: return ""
        val at = parseAnalysisTimeMs(level.analyzedAt) ?: return ""
        if (System.currentTimeMillis() - at <= StrategyThresholds.LEVEL_MAX_AGE_MS) return ""
        return "｜支撑/压力来自 ${level.analyzedAt.substringAfter(' ')}，已超过 35 分钟，注意位置变化"
    }

    private fun levelStale(level: com.example.stockmonitor.analysis.PositionResult?): Boolean {
        level ?: return false
        val at = parseAnalysisTimeMs(level.analyzedAt) ?: return false
        return System.currentTimeMillis() - at > StrategyThresholds.LEVEL_MAX_AGE_MS
    }

    private fun quoteAgeMs(quoteDate: String, nowMs: Long): Long {
        if (quoteDate.trim().length == 10) return 0L
        val parsed = TimeUtils.parseDateTime(quoteDate.trim().let { if (it.length == 16) "$it:00" else it })?.time
            ?: return Long.MAX_VALUE
        return (nowMs - parsed).coerceAtLeast(0L)
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
        // 只把本轮真实命中的新结果送去入池；保留的旧列表不能复活已经淘汰的候选。
        addScreenCandidates(result, includePatterns = includePatterns)

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

    private fun addScreenCandidates(
        result: ScreenResult,
        allowRevive: Boolean = true,
        includePatterns: Boolean = true,
    ) {
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
                            sources = setOf("指标严格"),
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
                            sources = setOf("指标宽松"),
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
                            source = ObservationEligibility.TREND_SOURCE,
                            sources = setOf(ObservationEligibility.TREND_SOURCE),
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
                            source = ObservationEligibility.PATTERN_SOURCE,
                            sources = setOf(ObservationEligibility.PATTERN_SOURCE),
                            strategy = ObservationStrategy.PRE_BREAKOUT,
                            sourceValidTradingDays = FIVE_ONE_THREE_SOURCE_VALID_TRADING_DAYS,
                        ),
                    )
                }
            }
        commitObservationPool { pool ->
            val existing = pool.associateBy(::observationKey).toMutableMap()
            candidates.groupBy(::observationKey).forEach { (key, currentRoutes) ->
                val previous = existing[key]
                val mergedSources = ObservationEligibility.mergeScreenSources(
                    current = currentRoutes.flatMap { it.allSources },
                    previous = previous?.allSources.orEmpty(),
                    includePatterns = includePatterns,
                )
                val currentScanPattern = currentRoutes.any {
                    ObservationEligibility.PATTERN_SOURCE in it.allSources
                }
                val previousPattern = previous?.allSources?.contains(ObservationEligibility.PATTERN_SOURCE) == true
                // currentRoutes 才是本轮扫描真实命中的来源；mergedSources 会保留上一轮来源，
                // 拿它判断“本轮命中”会让 513 连续命中时 sourceObservedDays 永不重置，
                // 每 3 个观察日被淘汰一次、晚上又被扫描复活。
                val patternObservedDays = when {
                    currentScanPattern -> 0
                    previousPattern -> previous?.sourceObservedDays ?: 0
                    else -> 0
                }
                val base = currentRoutes.firstOrNull {
                    ObservationEligibility.PATTERN_SOURCE !in it.allSources
                } ?: currentRoutes.first()
                val merged = base.copy(
                    source = base.source,
                    sources = mergedSources.toCollection(linkedSetOf()),
                    sourceExpiresAtMs = 0L,
                    sourceValidTradingDays = when {
                        currentScanPattern -> FIVE_ONE_THREE_SOURCE_VALID_TRADING_DAYS
                        previousPattern -> previous?.sourceValidTradingDays ?: 0
                        else -> 0
                    },
                    sourceObservedDays = patternObservedDays,
                )
                existing[key] = when {
                    previous == null -> merged
                    previous.stage == ObservationStage.ELIMINATED && allowRevive ->
                        ObservationReentryPolicy.revive(previous, merged).copy(
                            sources = mergedSources.toCollection(linkedSetOf()),
                            sourceExpiresAtMs = 0L,
                            sourceValidTradingDays = merged.sourceValidTradingDays,
                            sourceObservedDays = patternObservedDays,
                        )
                    else -> previous.copy(
                        source = base.source,
                        sources = mergedSources.toCollection(linkedSetOf()),
                        sourceExpiresAtMs = 0L,
                        sourceValidTradingDays = merged.sourceValidTradingDays,
                        sourceObservedDays = patternObservedDays,
                    )
                }
            }
            existing.values.sortedWith(
                compareBy<ObservationCandidate> { it.stage == ObservationStage.ELIMINATED }
                    .thenByDescending { it.lastEvaluatedDate ?: it.addedDate },
            )
        }
    }

    /**
     * 涨停池降级（东财不可用、退回新浪近似）时，连板数 / 封单质量 / 开板次数都缺失：
     * 这些票只能作为“近似涨停”观察，不能进入首板承接链，也不能写成“今日接近涨停”。
     */
    private fun addLeaderCandidates(
        limitStocks: List<LimitStock>,
        degraded: Boolean = false,
    ) {
        if (limitStocks.isEmpty()) return
        val today = TimeUtils.todayText()
        val ordered = LimitCandidateSelection.select(limitStocks)
        commitObservationPool(
            onCommitted = { before, after -> ObsPoolTrace.poolAdd("leader", today, before, after) },
        ) { pool ->
            val existing = pool.associateBy(::observationKey).toMutableMap()
            ordered.forEach { stock ->
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
                        source = if (degraded) "涨停近似" else "涨停强势",
                        sources = setOf(if (degraded) "涨停近似" else "涨停强势"),
                        reason = if (degraded) {
                            "涨停池降级：仅按新浪近似数据观察，封单质量与连板数缺失，不参与首板承接判断"
                        } else {
                            "今日接近涨停，等待持续性和承接确认"
                        },
                        strategy = ObservationStrategy.LEADER,
                        limitStreak = if (degraded) null else stock.limitStreak,
                        firstBoardDate = if (!degraded && stock.limitStreak == 1) today else null,
                        sealQualityScore = if (degraded) null else stock.sealQualityScore,
                        sealQualityCoveragePct = if (degraded) null else stock.sealQualityCoveragePct,
                        boards = stock.boards,
                    )
                val key = observationKey(candidate)
                val previous = existing[key]
                existing[key] = when {
                    previous == null -> candidate
                    previous.stage == ObservationStage.ELIMINATED ->
                        ObservationReentryPolicy.revive(previous, candidate)
                    else -> {
                        // 同一策略行可能经历多轮首板：重新首板时开启新周期，而不是沿用旧 addedDate。
                        val newCycle = FirstBoardClockPolicy.startsNewCycle(
                            stock.limitStreak, previous, today, degraded,
                        )
                        val base = if (newCycle) {
                            FirstBoardClockPolicy.startNewCycle(previous, today)
                        } else {
                            previous
                        }
                        val boardDate = when {
                            degraded -> previous.firstBoardDate
                            stock.limitStreak == 1 -> if (newCycle) {
                                today
                            } else {
                                FirstBoardClockPolicy.effectiveBoardDate(previous)
                            }
                            else -> previous.firstBoardDate ?: previous.addedDate
                        }
                        base.copy(
                            limitStreak = if (degraded) null else stock.limitStreak,
                            firstBoardDate = boardDate,
                            sealQualityScore = if (degraded) null else stock.sealQualityScore,
                            sealQualityCoveragePct = if (degraded) null else stock.sealQualityCoveragePct,
                            boards = stock.boards,
                        )
                    }
                }
            }
            existing.values.sortedWith(
                compareBy<ObservationCandidate> { it.stage == ObservationStage.ELIMINATED }
                    .thenByDescending { it.lastEvaluatedDate ?: it.addedDate },
            )
        }
    }

    private fun addLimitContinuationCandidates(limitStocks: List<LimitStock>) {
        if (limitStocks.isEmpty()) return
        val today = TimeUtils.todayText()
        commitObservationPool(
            onCommitted = { before, after -> ObsPoolTrace.poolAdd("limitContinuation", today, before, after) },
        ) { pool ->
            val existing = pool.associateBy(::observationKey).toMutableMap()
            limitStocks.forEach { stock ->
                // 连板持续路线与强势龙头路线各自保留状态和确认轮次；展示层按策略分开呈现。
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
                        source = "涨停持续观察",
                        sources = setOf("涨停持续观察"),
                        reason = ObservationEligibility.CONTINUOUS_LIMIT_WAIT_REASON,
                        strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
                        continuousObservation = true,
                        limitUpBlocked = true,
                        limitStreak = stock.limitStreak,
                        firstBoardDate = if (stock.limitStreak == 1) today else null,
                        sealQualityScore = stock.sealQualityScore,
                        sealQualityCoveragePct = stock.sealQualityCoveragePct,
                        boards = stock.boards,
                    )
                val key = observationKey(candidate)
                val previous = existing[key]
                existing[key] = when {
                    previous == null -> candidate
                    previous.stage == ObservationStage.ELIMINATED ->
                        ObservationReentryPolicy.revive(previous, candidate)
                    else -> {
                        val newCycle = FirstBoardClockPolicy.startsNewCycle(
                            stock.limitStreak, previous, today, degraded = false,
                        )
                        val base = if (newCycle) {
                            FirstBoardClockPolicy.startNewCycle(previous, today)
                        } else {
                            previous
                        }
                        val boardDate = when {
                            stock.limitStreak == 1 -> if (newCycle) {
                                today
                            } else {
                                FirstBoardClockPolicy.effectiveBoardDate(previous)
                            }
                            else -> previous.firstBoardDate ?: previous.addedDate
                        }
                        base.copy(
                            continuousObservation = true,
                            limitUpBlocked = true,
                            limitStreak = stock.limitStreak,
                            firstBoardDate = boardDate,
                            sealQualityScore = stock.sealQualityScore,
                            sealQualityCoveragePct = stock.sealQualityCoveragePct,
                            boards = stock.boards,
                        )
                    }
                }
            }
            existing.values.sortedWith(
                compareBy<ObservationCandidate> { it.stage == ObservationStage.ELIMINATED }
                    .thenByDescending { it.lastEvaluatedDate ?: it.addedDate },
            )
        }
    }

    private fun observeCurrentScreen() {
        addScreenCandidates(Repo.screenResult, allowRevive = false)
        Repo.statusText = "已将当前选股结果加入观察池"
        Repo.notifyChanged()
    }

    private fun launchObservationPoolRefresh(quotes: Map<String, Quote>) {
        val current = store.observationPool
        val nowMs = System.currentTimeMillis()
        val today = TimeUtils.todayText()
        val migrationDue = current.any {
            it.riskPolicyVersion < ObservationPool.CURRENT_RISK_POLICY_VERSION
        } && lastRiskPolicyMigrationAttemptDate != today
        commitObservationPool(
            onCommitted = { before, after -> ObsPoolTrace.poolRefresh("quoteGate", today, before, after) },
        ) { pool ->
            pool
                .map { ObservationEligibility.expireStaleSource(it, nowMs, today) }
                .map { ObservationEligibility.applyQuoteGate(it, quotes[it.code]) }
        }
        // 规则升级后即使已收盘也要补一次完整复评，否则旧风险信号会一直留在界面上；
        // 每个交易日最多补试一次，避免盘后逐轮重复打日K接口。
        if (migrationDue) {
            lastRiskPolicyMigrationAttemptDate = today
            lastObservationIntradayRefreshAtMs = 0L
        }
        if (!TradingCalendar.isMarketOpen() && !migrationDue) return
        if (!observationRefreshBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                refreshObservationPool(quotes)
                refreshWatchlistBuyCandidates(quotes)
                notifyTailObservationCandidates()
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
        // 缓存同时服务随后的 refreshWatchlistBuyCandidates：只按观察池收敛会把自选 codes
        // 逐出，自选池下一轮复评每个候选都重拉一次 60 根日 K（约每分钟一轮）。
        val watchlistCodes = store.watchlistBuyCandidates.mapTo(mutableSetOf()) { it.code }
        observationHistory.keys.retainAll(activeCodes + watchlistCodes)
        lastObservationIntradayRefreshAtMs = now
        val result = Repo.screenResult
        val screened =
            (
                result.strict.map { it.code } + result.relaxed.map { it.code } +
                    result.fiveOneThree.map { it.code } + result.trend.map { it.code }
            ).toSet()
        val benchmark = observationBenchmark()
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
                                        ?: runCatching { sina.kline(candidate.code, 520) }
                                            .getOrNull()
                                            ?.filter { it.date < today }
                                            // 正常买点和二波确认都要周/月/MA250 授权，60根日线无法形成该证据。
                                            ?.takeIf { it.size >= 250 }
                                            ?.also { observationHistory[candidate.code] = today to it }
                                        ?: return@withPermit null
                                if (history.size < 250) return@withPermit null
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
                    key in currentByKey && ObservationRefreshPolicy.shouldPersist(
                        evaluatedQuote = evaluatedQuotes[key],
                        currentQuote = Repo.quotes[key.first] ?: quotes[key.first],
                        protectsEntry = candidate.stage == ObservationStage.BUY_CANDIDATE ||
                            candidate.entryConfirmationRounds > 0,
                    )
                }.mapValues { (key, candidate) ->
                    ObservationEligibility.applyQuoteGate(candidate, Repo.quotes[key.first] ?: quotes[key.first])
                }
        val alertQueuedAtMs = System.currentTimeMillis()
        val transitionedKeys = accepted.values
            .filter { candidate ->
                val previous = currentByKey[candidate.code to candidate.strategy] ?: return@filter false
                ObservationBuyAlert.shouldNotify(previous, candidate)
            }
            .map(ObservationBuyAlert::alertKey)
            .toSet()
        var updated = latest.map { accepted[it.code to it.strategy] ?: it }
        updated = updated.map { candidate ->
            if (
                ObservationBuyAlert.alertKey(candidate) in transitionedKeys &&
                candidate.stage == ObservationStage.BUY_CANDIDATE
            ) {
                candidate.copy(
                    alertState = ObservationAlertState.PENDING,
                    alertDetail = "买点已确认，等待通知投递",
                    alertUpdatedAtMs = alertQueuedAtMs,
                )
            } else {
                candidate
            }
        }
        val transitionedBuyAlerts =
            updated.filter { ObservationBuyAlert.alertKey(it) in transitionedKeys }
        val updatedKeys = updated.map { ObservationBuyAlert.alertKey(it) }.toSet()
        pendingObservationBuyAlerts.keys.retainAll(updatedKeys)
        pendingObservationBuyAlerts.keys.toList().forEach { key ->
            // 同一 code 可有多策略条目（选股 PRE_BREAKOUT 与涨停池 LEADER 并存）：
            // 只要任一条目当日仍是 BUY_CANDIDATE 就保留待发提醒。此前 firstOrNull 按
            // 列表顺序取条目，取到 OBSERVING 那条会误删仍在 BUY_CANDIDATE 的待发提醒，
            // 且 shouldNotify 要求 previous 非 BUY，误删后该买点提醒永久丢失。
            val current = updated
                .filter { ObservationBuyAlert.alertKey(it) == key }
                .firstOrNull { it.stage == ObservationStage.BUY_CANDIDATE && it.lastEvaluatedDate == today }
            if (current == null) {
                pendingObservationBuyAlerts.remove(key)
            } else {
                pendingObservationBuyAlerts[key] = current
            }
        }
        transitionedBuyAlerts.forEach { candidate ->
            pendingObservationBuyAlerts[ObservationBuyAlert.alertKey(candidate)] = candidate
        }
        val buyAlerts = (transitionedBuyAlerts + pendingObservationBuyAlerts.values)
            .distinctBy { ObservationBuyAlert.alertKey(it) }
        commitObservationPool(
            onCommitted = { before, after -> ObsPoolTrace.poolRefresh("evaluate", today, before, after) },
        ) { pool ->
            // 复评期间可能有新入池候选：只覆盖本轮真正评估过的 key，其余保留最新值；
            // 并发删除的 key 也不会被本轮的旧快照重新加回来。
            val evaluatedByKey = updated.associateBy { it.code to it.strategy }
            pool.map { candidate ->
                val key = candidate.code to candidate.strategy
                if (key in accepted) evaluatedByKey[key] ?: candidate else candidate
            }
        }
        // 突破延续下杀：从“没有风险”转为“有风险”时报告一次事实，停止追买。
        // 走当日事件账本（同股同日一次），不占机会类预算；观察池是用户显式维护的列表，
        // 不受“自选股盘中提醒”开关限制。
        accepted.values
            .filter { candidate ->
                val previous = currentByKey[candidate.code to candidate.strategy]
                previous != null && ObservationBreakoutRiskAlert.shouldNotify(previous, candidate)
            }
            .forEach { candidate ->
                sendCheckedAlert(
                    type = "observationBreakoutRisk",
                    code = candidate.code,
                    id = NotificationIds.of(ALERT_ID_OBSERVATION_BREAKOUT_RISK, candidate.code),
                    title = ObservationBreakoutRiskAlert.title(
                        candidate,
                        alertDisplayName(candidate.code, candidate.name),
                    ),
                    body = ObservationBreakoutRiskAlert.body(
                        candidate,
                        alertDisplayName(candidate.code, candidate.name),
                    ),
                    signal = false,
                    consumeBudget = false,
                )
            }
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
                    highestStage = candidate.highestStage ?: candidate.stage,
                    highestStageDate = candidate.highestStageDate
                        ?: candidate.lastEvaluatedDate
                        ?: candidate.addedDate,
                    highestStageAtMs = candidate.highestStageAtMs,
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
        val alertOutcomes = mutableMapOf<String, ObservationAlertAttempt>()
        buyAlerts.forEach { candidate ->
            val key = ObservationBuyAlert.alertKey(candidate)
            val quote = Repo.quotes[candidate.code] ?: quotes[candidate.code]
            val outcome = if (quote == null) {
                ObservationAlertAttempt(ObservationAlertState.BLOCKED, "当日行情缺失，无法核对执行条件")
            } else {
                deliverObservationBuyAlert(candidate, quote)
            }
            alertOutcomes[key] = outcome
            if (outcome.state.isSent()) pendingObservationBuyAlerts.remove(key)
        }
        applyObservationAlertOutcomes(alertOutcomes)
        Repo.notifyChanged()
    }

    /** 自选股只保留观察池同口径的“有效买点确认”，不推送反弹、封板或突破风险。 */
    private suspend fun refreshWatchlistBuyCandidates(quotes: Map<String, Quote>) {
        val today = TimeUtils.todayText()
        val heldCodes = StockAlertScope.heldCodes(store.portfolio.positions)
        // 「当日淘汰」也要排除：否则观察池当天刚因连续走弱淘汰的自选股会立刻以全新
        // OBSERVING 状态（观察天数归零）落进自选池，绕开「淘汰当日不复活」的池内规则。
        // 次日起两侧都允许开始新一轮观察，语义一致。
        val observationCodes = store.observationPool
            .filter { it.stage != ObservationStage.ELIMINATED || it.lastEvaluatedDate == today }
            .map { it.code }
            .toSet()
        val synced = WatchlistBuyCandidatePool.sync(
            watchlist = store.watchlist,
            previous = store.watchlistBuyCandidates,
            heldCodes = heldCodes,
            observationCodes = observationCodes,
            today = today,
        )
        if (synced != store.watchlistBuyCandidates) {
            store.watchlistBuyCandidates = synced.toMutableList()
        }
        if (synced.isEmpty()) return
        val now = System.currentTimeMillis()
        val intradayDue = now - lastWatchlistBuyRefreshAtMs >= OBSERVATION_INTRADAY_REFRESH_MS
        val pending = synced.filter {
            ObservationEligibility.needsRefresh(it, today, quotes[it.code]) &&
                (it.lastEvaluatedDate != today || intradayDue)
        }
        if (pending.isEmpty()) return
        lastWatchlistBuyRefreshAtMs = now
        val result = Repo.screenResult
        val screened =
            (
                result.strict.map { it.code } + result.relaxed.map { it.code } +
                    result.fiveOneThree.map { it.code } + result.trend.map { it.code }
            ).toSet()
        val benchmark = observationBenchmark()
        val semaphore = Semaphore(4)
        val evaluatedQuotes = java.util.concurrent.ConcurrentHashMap<String, Quote>()
        val evaluated =
            coroutineScope {
                pending
                    .map { candidate ->
                        async {
                            semaphore.withPermit {
                                val history =
                                    observationHistory[candidate.code]?.takeIf { it.first == today }?.second
                                        ?: runCatching { sina.kline(candidate.code, 520) }
                                            .getOrNull()
                                            ?.filter { it.date < today }
                                            // 正常买点和二波确认都要周/月/MA250 授权，60根日线无法形成该证据。
                                            ?.takeIf { it.size >= 250 }
                                            ?.also { observationHistory[candidate.code] = today to it }
                                        ?: return@withPermit null
                                if (history.size < 250) return@withPermit null
                                val quote = Repo.quotes[candidate.code] ?: quotes[candidate.code] ?: return@withPermit null
                                if (!ObservationEligibility.needsRefresh(candidate, today, quote)) return@withPermit null
                                evaluatedQuotes[candidate.code] = quote
                                candidate.code to
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
        val currentByCode = synced.associateBy { it.code }
        val accepted =
            evaluated
                .filter { (code, candidate) ->
                    code in currentByCode && ObservationRefreshPolicy.shouldPersist(
                        evaluatedQuote = evaluatedQuotes[code],
                        currentQuote = Repo.quotes[code] ?: quotes[code],
                        protectsEntry = candidate.stage == ObservationStage.BUY_CANDIDATE ||
                            candidate.entryConfirmationRounds > 0,
                    )
                }.mapValues { (code, candidate) ->
                    ObservationEligibility.applyQuoteGate(candidate, Repo.quotes[code] ?: quotes[code])
                }
        // 自选股候选同样在“突破转下杀”时报告一次风险事实。
        accepted.values
            .filter { candidate ->
                val previous = currentByCode[candidate.code]
                previous != null && ObservationBreakoutRiskAlert.shouldNotify(previous, candidate)
            }
            .forEach { candidate ->
                sendCheckedAlert(
                    type = "observationBreakoutRisk",
                    code = candidate.code,
                    id = NotificationIds.of(ALERT_ID_OBSERVATION_BREAKOUT_RISK, candidate.code),
                    title = ObservationBreakoutRiskAlert.title(
                        candidate,
                        alertDisplayName(candidate.code, candidate.name),
                    ),
                    body = ObservationBreakoutRiskAlert.body(
                        candidate,
                        alertDisplayName(candidate.code, candidate.name),
                    ),
                    signal = false,
                    consumeBudget = false,
                )
            }
        val alertQueuedAtMs = System.currentTimeMillis()
        val transitionedKeys = accepted.values
            .filter { candidate ->
                val previous = currentByCode[candidate.code] ?: return@filter false
                ObservationBuyAlert.shouldNotify(previous, candidate)
            }
            .map(ObservationBuyAlert::alertKey)
            .toSet()
        var updated = synced.map { accepted[it.code] ?: it }
        updated = updated.map { candidate ->
            if (
                ObservationBuyAlert.alertKey(candidate) in transitionedKeys &&
                candidate.stage == ObservationStage.BUY_CANDIDATE
            ) {
                candidate.copy(
                    alertState = ObservationAlertState.PENDING,
                    alertDetail = "买点已确认，等待通知投递",
                    alertUpdatedAtMs = alertQueuedAtMs,
                )
            } else {
                candidate
            }
        }
        val transitionedBuyAlerts = updated.filter { ObservationBuyAlert.alertKey(it) in transitionedKeys }
        val updatedKeys = updated.map { ObservationBuyAlert.alertKey(it) }.toSet()
        pendingWatchlistBuyAlerts.keys.retainAll(updatedKeys)
        pendingWatchlistBuyAlerts.keys.toList().forEach { key ->
            val current = updated.firstOrNull { ObservationBuyAlert.alertKey(it) == key }
            if (current == null ||
                current.stage != ObservationStage.BUY_CANDIDATE ||
                current.lastEvaluatedDate != today
            ) {
                pendingWatchlistBuyAlerts.remove(key)
            } else {
                pendingWatchlistBuyAlerts[key] = current
            }
        }
        transitionedBuyAlerts.forEach { candidate ->
            pendingWatchlistBuyAlerts[ObservationBuyAlert.alertKey(candidate)] = candidate
        }
        val buyAlerts = (transitionedBuyAlerts + pendingWatchlistBuyAlerts.values)
            .distinctBy { ObservationBuyAlert.alertKey(it) }
        store.watchlistBuyCandidates = updated.toMutableList()
        val alertOutcomes = mutableMapOf<String, ObservationAlertAttempt>()
        buyAlerts.forEach { candidate ->
            val key = ObservationBuyAlert.alertKey(candidate)
            val quote = Repo.quotes[candidate.code] ?: quotes[candidate.code]
            val outcome = if (quote == null) {
                ObservationAlertAttempt(ObservationAlertState.BLOCKED, "当日行情缺失，无法核对执行条件")
            } else {
                deliverObservationBuyAlert(candidate, quote)
            }
            alertOutcomes[key] = outcome
            if (outcome.state.isSent()) pendingWatchlistBuyAlerts.remove(key)
        }
        applyObservationAlertOutcomes(alertOutcomes)
        Repo.notifyChanged()
    }

    /**
     * 14:30 后对当前仍有效的买点做尾盘复核。实时转入买点的通知仍按原链路发送；
     * 这里是独立的收盘前复核，每只股票每天至多补发一次，且必须再次通过交易守卫。
     */
    private fun notifyTailObservationCandidates() {
        val calendar = TimeUtils.nowCalendar()
        if (!TradingCalendar.inTailObservationReviewWindow(calendar)) return
        if (!store.alertsEnabled || !store.intradaySignalEnabled) return
        val today = TimeUtils.todayText()
        val sentKeys = store.candidateBuyAlertKeys.filter { it.startsWith("$today:") }.toSet()
        if (sentKeys != store.candidateBuyAlertKeys) store.candidateBuyAlertKeys = sentKeys
        val sentCodes = sentKeys.asSequence()
            .map { it.substringAfter(':') }
            .toSet()
        val candidates = (store.observationPool + store.watchlistBuyCandidates)
            .filter {
                it.stage == ObservationStage.BUY_CANDIDATE &&
                    it.lastEvaluatedDate == today &&
                    it.code !in sentCodes &&
                    ObservationBuyAlert.canNotify(it)
            }
            .groupBy(ObservationBuyAlert::alertKey)
            .mapNotNull { (_, sameCode) ->
                sameCode.maxWithOrNull(
                    compareByDescending<ObservationCandidate> { it.score }
                        .thenByDescending { it.entryConfirmationRounds }
                        .thenBy { it.strategy.ordinal },
                )
            }
            .sortedWith(
                compareByDescending<ObservationCandidate> { it.score }
                    .thenByDescending { it.entryConfirmationRounds }
                    .thenBy { it.code },
            )
            .take((TAIL_OBSERVATION_MAX_NOTIFICATIONS - sentCodes.size).coerceAtLeast(0))
        for (candidate in candidates) {
            val sentKey = "$today:${candidate.code}"
            if (sentKey in sentKeys) continue
            val quote = Repo.quotes[candidate.code] ?: continue
            if (quote.quoteDate != today) continue
            // 尾盘复核同样先过资金过滤；主力净流出且无承接时不补发。
            val fundFlow = buyPointFundFlowDecision(candidate.code, quote)
            if (!fundFlow.allow) continue
            val guarded = guardedObservationBuy(candidate, quote)
            if (guarded.level != GuardedActionLevel.ACTIONABLE) continue
            if (!StockAlertScope.shouldNotify(
                    StockAlertType.CONFIRMED_BUY,
                    held = candidate.code in StockAlertScope.heldCodes(store.portfolio.positions),
                    buyPointConfirmed = true,
                )
            ) continue
            if (!consumePushBudget(candidate.code)) continue
            if (!store.reserveCandidateBuyAlertKey(sentKey, today)) {
                refundPushBudget(candidate.code)
                continue
            }
            val posted = Notifier.tailObservation(
                context = this,
                id = NotificationIds.of(ALERT_ID_TAIL_OBSERVATION, candidate.code),
                title = "⏰ 尾盘候选复核｜${candidate.name}",
                text = ObservationBuyAlert.body(candidate, guarded.text) +
                    "｜资金：${fundFlow.evidence}；${fundFlow.reason}",
                triggerType = "$TAIL_OBSERVATION_TRIGGER:$today:${candidate.code}",
            )
            if (!posted) {
                store.releaseCandidateBuyAlertKey(sentKey, today)
                refundPushBudget(candidate.code)
            }
        }
    }

    private data class ObservationAlertAttempt(
        val state: ObservationAlertState,
        val detail: String,
        val atMs: Long = System.currentTimeMillis(),
    )

    /**
     * 状态机确认买点后，不再让执行守卫成为静默的“通知黑洞”。
     * 守卫通过时发送可执行提醒；暂未通过时仍发送一次买点结构确认，并在正文写清缺口。
     * 开关、时段、系统通知权限、预算与去重失败则记录为明确未发送原因，供候选详情展示。
     */
    /**
     * 买点类提醒共用的资金承接判断（首板承接观察 + 观察池/自选“买点已确认”）。
     * 资金流缺失时不硬判出货，具体规则见 [BuyPointFundFlowPolicy]。
     */
    private fun buyPointFundFlowDecision(code: String, quote: Quote): BuyPointFundFlowPolicy.Decision =
        buyPointFundFlowDecision(
            code = code,
            price = quote.price,
            changePct = quote.changePct,
            quoteDate = quote.quoteDate,
            volume = quote.volume,
            turnover = quote.turnover,
        )

    /**
     * 买点类提醒共用的资金承接判断（首板承接、启动突破、观察池/自选“买点已确认”）。
     * 资金流缺失时不硬判出货，具体规则见 [BuyPointFundFlowPolicy]。
     */
    private fun buyPointFundFlowDecision(
        code: String,
        price: Double,
        changePct: Double?,
        quoteDate: String?,
        volume: Double,
        turnover: Double,
    ): BuyPointFundFlowPolicy.Decision {
        val flow = intradayFundFlowTracker.context(code)
        val mainForceDirection = runCatching {
            MainForceDirectionPolicy.assess(
                changePct = changePct,
                quoteDate = quoteDate,
                history = Repo.fundFlowHistory[code].orEmpty(),
            )
        }.getOrNull()
            // 缓存方向不能用来否定今天的行情。
            ?.takeIf { it.confidence != MainForceConfidence.CACHED }
            ?.direction
        val vwap = if (volume > 0.0 && turnover > 0.0) {
            // 单位异常时（成交量/成交额口径不一致）宁可不用均价，也不能误判所有票都在均价下方。
            (turnover / volume).takeIf { it.isFinite() && it in price * 0.5..price * 2.0 }
        } else {
            null
        }
        return BuyPointFundFlowPolicy.assess(
            BuyPointFundFlowPolicy.Input(
                price = price,
                vwap = vwap,
                volumeRatio = Repo.volumeRatios[code],
                dayMainNetPct = flow?.dayMainNetPct,
                recent5MainNetPct = flow?.recent5MainNetPct,
                recent15MainNetPct = flow?.recent15MainNetPct,
                mainForceDirection = mainForceDirection,
            ),
        )
    }

    private fun deliverObservationBuyAlert(
        candidate: ObservationCandidate,
        quote: Quote,
    ): ObservationAlertAttempt {
        val today = TimeUtils.todayText()
        if (quote.quoteDate != today) {
            return ObservationAlertAttempt(ObservationAlertState.BLOCKED, "不是当日行情，无法确认盘中执行条件")
        }
        if (!ObservationBuyAlert.canNotify(candidate)) {
            return ObservationAlertAttempt(ObservationAlertState.BLOCKED, "买点确认状态已失效")
        }
        if (!StockAlertScope.shouldNotify(
                StockAlertType.CONFIRMED_BUY,
                held = candidate.code in StockAlertScope.heldCodes(store.portfolio.positions),
                buyPointConfirmed = true,
            )
        ) {
            return ObservationAlertAttempt(ObservationAlertState.BLOCKED, "该股票不在买点提醒范围")
        }
        if (!store.alertsEnabled || !store.intradaySignalEnabled) {
            return ObservationAlertAttempt(ObservationAlertState.BLOCKED, "行情提醒或买卖点提醒开关已关闭")
        }
        if (!TradingCalendar.inObservationBuySignalWindow()) {
            return ObservationAlertAttempt(ObservationAlertState.BLOCKED, "不在 09:45–14:45 买点提醒时段")
        }
        // 买点确认后先过资金过滤：主力净流出且近端无承接时不打扰，保留待发状态等资金转好。
        val fundFlow = buyPointFundFlowDecision(candidate.code, quote)
        if (!fundFlow.allow) {
            return ObservationAlertAttempt(
                ObservationAlertState.BLOCKED,
                "资金过滤：${fundFlow.reason}（${fundFlow.evidence}）",
            )
        }

        val guardedBuy = guardedObservationBuy(candidate, quote)
        val executionReady = guardedBuy.level == GuardedActionLevel.ACTIONABLE
        val dedupKey = "$today:${candidate.code}"
        if (!store.reserveCandidateBuyAlertKey(dedupKey, today)) {
            return ObservationAlertAttempt(
                ObservationAlertState.SENT_EARLIER,
                "今日已有同股买点提醒，本次不重复推送",
            )
        }
        if (!consumePushBudget(candidate.code)) {
            store.releaseCandidateBuyAlertKey(dedupKey, today)
            return ObservationAlertAttempt(ObservationAlertState.BLOCKED, "该股今日提醒次数已达上限")
        }
        val posted = Notifier.signal(
            this,
            NotificationIds.of(ALERT_ID_OBSERVATION_BUY, ObservationBuyAlert.alertKey(candidate)),
            ObservationBuyAlert.title(candidate, executionReady),
            ObservationBuyAlert.body(candidate, guardedBuy.text, executionReady) +
                "｜资金：${fundFlow.evidence}；${fundFlow.reason}",
        )
        if (!posted) {
            store.releaseCandidateBuyAlertKey(dedupKey, today)
            refundPushBudget(candidate.code)
            return ObservationAlertAttempt(
                ObservationAlertState.BLOCKED,
                "系统通知或买卖点提醒渠道不可用，请在系统设置中开启",
            )
        }
        return ObservationAlertAttempt(
            if (executionReady) {
                ObservationAlertState.SENT_ACTIONABLE
            } else {
                ObservationAlertState.SENT_CAUTION
            },
            guardedBuy.text,
        )
    }

    private fun applyObservationAlertOutcomes(outcomes: Map<String, ObservationAlertAttempt>) {
        if (outcomes.isEmpty()) return
        fun applyTo(items: List<ObservationCandidate>): Pair<List<ObservationCandidate>, Boolean> {
            var changed = false
            val updated = items.map { candidate ->
                val outcome = outcomes[candidate.code] ?: return@map candidate
                if (candidate.alertState.isSent() && !outcome.state.isSent()) return@map candidate
                if (candidate.alertState.isSent() && outcome.state.priority() < candidate.alertState.priority()) {
                    return@map candidate
                }
                if (candidate.alertState == outcome.state && candidate.alertDetail == outcome.detail) {
                    return@map candidate
                }
                changed = true
                candidate.copy(
                    alertState = outcome.state,
                    alertDetail = outcome.detail,
                    alertUpdatedAtMs = outcome.atMs,
                )
            }
            return updated to changed
        }
        commitObservationPool { pool ->
            val (observation, observationChanged) = applyTo(pool)
            if (observationChanged) observation else pool
        }
        val (watchlist, watchlistChanged) = applyTo(store.watchlistBuyCandidates)
        if (watchlistChanged) store.watchlistBuyCandidates = watchlist.toMutableList()
    }

    private fun ObservationAlertState.isSent(): Boolean = when (this) {
        ObservationAlertState.SENT_ACTIONABLE,
        ObservationAlertState.SENT_CAUTION,
        ObservationAlertState.SENT_EARLIER,
        -> true
        else -> false
    }

    private fun ObservationAlertState.priority(): Int = when (this) {
        ObservationAlertState.UNKNOWN -> 0
        ObservationAlertState.BLOCKED -> 1
        ObservationAlertState.PENDING -> 2
        ObservationAlertState.SENT_EARLIER -> 3
        ObservationAlertState.SENT_CAUTION -> 4
        ObservationAlertState.SENT_ACTIONABLE -> 5
    }

    private fun guardedObservationBuy(
        candidate: ObservationCandidate,
        quote: Quote,
    ): GuardedTradeAction {
        val breakoutReference = candidate.breakoutRisk?.breakoutReference
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return GuardedTradeAction(
                GuardedActionLevel.OBSERVATION,
                GuardedAction.HOLD,
                "仅观察：缺少真实突破参考位，不执行买入。",
            )
        val analysisBars = ObservationEntryPolicy.withLiveBar(
            observationHistory[candidate.code]?.second.orEmpty(),
            quote,
        )
        val closes = analysisBars.map { it.close }
        val ma5 = closes.takeLast(5).takeIf { it.size == 5 }?.average()
        val ma20 = closes.takeLast(20).takeIf { it.size == 20 }?.average()
        val priceLocation = PricePosition
            .of(analysisBars, quote.quoteDate?.substringBefore(' '))
            ?.contextOf(quote.price)
            ?.location
        val riskLine = if (ma5 != null && ma20 != null) {
            com.example.stockmonitor.analysis.NormalDivergencePolicy.riskLine(
                price = quote.price,
                ma5 = ma5,
                ma20 = ma20,
                priceLocation = priceLocation,
                biasFromMa5Pct = ma5.takeIf { it > 0.0 }?.let { (quote.price / it - 1.0) * 100.0 },
            )
        } else {
            null
        }
        return TradeActionGuard.evaluate(
            intent = "buy",
            evidence = TimeframeEvidence.build(
                bars15 = intraday15ByCode[candidate.code]?.bars.orEmpty(),
                bars30 = intraday30ByCode[candidate.code]?.bars.orEmpty(),
                price = quote.price,
                support = candidate.stopPrice?.takeIf { it > 0.0 } ?: quote.price * 0.97,
                resistance = breakoutReference,
                now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
                quoteDate = quote.quoteDate?.substringBefore(' '),
                riskLine = riskLine,
            ),
            volume = IntradayVolumeContext.build(
                marketPredictedFullDayTurnover = Repo.turnoverSnapshot?.predictedFullDay?.div(100_000_000.0),
                recentCompleteMarketTurnovers = turnoverTracker
                    ?.recentFullDayTurnoversYi(quote.quoteDate?.substringBefore(' ') ?: TimeUtils.todayText())
                    .orEmpty(),
                stockVolumeRatio = Repo.volumeRatios[candidate.code],
                priceChangePct = quote.changePct,
                bars15 = intraday15ByCode[candidate.code]?.bars.orEmpty(),
                price = quote.price,
                support = candidate.stopPrice?.takeIf { it > 0.0 } ?: quote.price * 0.97,
                now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
                quoteDate = quote.quoteDate?.substringBefore(' '),
            ),
            eligibility = TradeActionEligibility(
                buyCapacityQuantity = if (
                    store.portfolio.totalCapital > 0 && quote.price > 0 && store.portfolio.initialEntryPct > 0
                ) {
                    kotlin.math.floor(
                        store.portfolio.totalCapital * store.portfolio.initialEntryPct /
                            100.0 / quote.price / 100.0,
                    ) * 100.0
                } else {
                    0.0
                },
                ledgerConsistent = true,
            ),
            tradeDate = quote.quoteDate?.substringBefore(' '),
            invalidationPrice = candidate.stopPrice ?: 0.0,
        )
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
        // 走弱阈值与做 T 同口径按板块涨跌停幅度缩放（主板 3%、创业板/科创板 6%、北交所 9%）。
        // 固定 -3% 会把 20% 板的普通回踩计成走弱日、加速「连续 3 日走弱」淘汰：
        // 近 120 日收盘口径样本中创业板 76%、科创板 71% 的走弱日属此类误判，
        // 创业板 10 次三连走弱淘汰全部为误判（缩放口径 0 次）；盘中按实时涨跌幅逐轮
        // 判定，日内深跌收盘收复的日子现口径同样计走弱，实际噪声只多不少。
        val weakChangePct = SinaClient.limitPctFor(candidate.code, candidate.name) * IntradayTactics.T_RATIO
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
                    ignoreMarketFilter = candidate.strategy != ObservationStrategy.PRE_BREAKOUT,
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
        val candidateDualTimeframeAllowed = DualTimeframePolicy.decide(
            riskSignal = false,
            execution = IntradayBarWindow(intraday15ByCode[candidate.code]?.bars.orEmpty().filter { it.completed }),
            direction = IntradayBarWindow(intraday30ByCode[candidate.code]?.bars.orEmpty().filter { it.completed }),
        ).allowed
        val priceLocation = PricePosition.of(analysisBars, asOf)?.contextOf(quote.price)?.location
        // 突破参考必须排除当前盘中 bar；否则当前 high 会把参考位抬到现价上方，二波确认永远不可达。
        val priorRangeHigh = analysisBars.dropLast(1).takeLast(60).maxOfOrNull { it.high } ?: quote.high
        val biasFromMa5Pct = ma5?.takeIf { it > 0 }?.let { (quote.price / it - 1.0) * 100.0 }
        val highPositionEvidence = com.example.stockmonitor.analysis.HighPositionEvidence(
            price = quote.price,
            rangeHigh = priorRangeHigh,
            rangeLow = analysisBars.takeLast(60).minOfOrNull { it.low } ?: quote.low,
            priceLocation = priceLocation,
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
            },
            dualTimeframeAllowed = candidateDualTimeframeAllowed,
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
        val relativeStrength = relativeStrength(
            history,
            quote.price,
            benchmark,
            marketPrice = Repo.quotes[MARKET_INDEX_CODES.first()]?.price,
        )
        val recentLimitUpDays = recentLimitUpDays(candidate.code, candidate.name, history, quote)
        val limitUpBlocked = ObservationEligibility.isAtLimitUp(candidate.code, candidate.name, quote)
        val postLimitHighHoldCandidate =
            ObservationEligibility.isPostLimitHighHoldCandidate(candidate.code, candidate.name, quote)
        val limitUpShrinkAcceleration =
            ObservationEligibility.isLimitUpShrinkAcceleration(
                limitUpBlocked = limitUpBlocked,
                changePct = quote.changePct,
                volumeRatio = volumeRatio,
                wasDeferred = candidate.limitUpShrinkAcceleration,
            )
        // 两板及以上的日线回踩不是普通开板追价：须经 ATR 容差、均线方向和至少两项止跌证据共同确认。
        val ma20SupportByAtr = ma20?.takeIf { it > 0.0 }?.let { line ->
            atr > 0.0 && quote.price in (line - atr)..(line + atr * 2.0)
        } == true
        val previousBar = analysisBars.dropLast(1).lastOrNull()
        val higherLows = analysisBars.takeLast(3).map { it.low }.let { lows ->
            lows.size == 3 && lows.zipWithNext().all { (previous, current) -> current >= previous }
        }
        val stopConfirmations = listOf(
            ma5?.let { quote.price >= it } == true,
            previousBar?.let { quote.price >= it.high } == true,
            quote.price > quote.open,
            higherLows,
        ).count { it }
        val postLimitDailyBaseEntry = candidate.limitStreak?.let { it >= 2 } == true &&
            relativeStrength?.let { it >= ObservationEntryPolicy.RELATIVE_STRENGTH_PCT } == true &&
            trend?.type == EntrySetupType.M20_PULLBACK && trend.confirmed && trend.canBuy &&
            trend.volumeConfirmed && trend.ma20SlopePct?.let { it > 0.0 } == true &&
            ma20SupportByAtr && stopConfirmations >= 2 && !limitUpBlocked && !limitUpShrinkAcceleration
        val run5Pct = analysisCloses.takeIf { it.size >= 6 }?.let {
            val base = it[it.lastIndex - 5]
            if (base > 0.0) (quote.price / base - 1.0) * 100.0 else null
        }
        val acceleration = AccelerationPolicy.assess(
            price = quote.price,
            run5Pct = run5Pct,
            volumeRatio = volumeRatio,
            high60 = priorRangeHigh,
            todayPct = quote.changePct,
        )
        val accelerationBlocker = AccelerationPolicy.entryBlocker(
            acceleration,
            leader = candidate.strategy == ObservationStrategy.LEADER ||
                candidate.strategy == ObservationStrategy.LIMIT_UP_CONTINUATION,
            confirmedPullback = (trend?.type == EntrySetupType.M20_PULLBACK && trend.confirmed) ||
                postLimitDailyBaseEntry,
        )
        val ratio = volumeRatio ?: 0.0
        val candidateFlowHistory = Repo.fundFlowHistory[candidate.code].orEmpty().filter {
            (it.tradingDate ?: "") >= candidate.addedDate
        }
        val fundFlow = candidateFlowHistory.lastOrNull()
        val fundFlowWindow = FundFlowWindowPolicy.assess(candidateFlowHistory)
        val promotionEnvironment = promotionEnvironmentGate(candidate)
        // 为什么：观察信号的技术分从旧的「日线40/周30/月30」质量分（TechnicalQualityPolicy）
        // 切换为与自选/持仓页同一套「技术 X/100」四维综合评分（TechnicalAssessmentPolicy，
        // 经快照链路输出）。entrySetup/trendAssessment 复用本函数已算出的结果，快照内部
        // 自带周/月 cycles，不重复计算。历史传带实时价的 analysisBars：观察是盘中口径，
        // 且原始 60 根过滤当日后可能只剩 59 根，不足 StockAnalysis.analyze 的 60 根下限；
        // 评估失败或历史不足时退回 null，UI 沿用既有「数据不足」兜底，不影响信号其余字段。
        val technicalSnapshot = runCatching {
            TechnicalTrendSnapshotPolicy.evaluate(
                history = analysisBars,
                code = candidate.code,
                name = candidate.name,
                asOf = asOf,
                entrySetup = trend ?: EntrySetup(
                    type = EntrySetupType.NONE,
                    confirmed = false,
                    canBuy = false,
                    trendWeak = false,
                    priorHigh = null,
                    ma20SlopePct = null,
                    volumeConfirmed = false,
                    marketFilter = MarketFilter.NEUTRAL,
                    stopPrice = null,
                    reason = "趋势结构评估失败，按无结构处理",
                ),
                trendAssessment = trendAssessment,
                marketRegime = Repo.marketState?.regime,
            )
        }.getOrNull()
        // 展示排序明确记录个股结构，不再把状态机的 0–3 资格分伪装成综合质量分。
        val structureQuality = when {
            highPosition.phase == HighPositionPhase.SECOND_WAVE_CONFIRMED && highPosition.allowBuy ->
                ObservationStructureQuality.SECOND_WAVE_CONFIRMED
            postLimitDailyBaseEntry -> ObservationStructureQuality.POST_LIMIT_DAILY_BASE
            postLimitHighHoldCandidate -> ObservationStructureQuality.POST_LIMIT_HIGH_HOLD
            trend?.type == EntrySetupType.M20_PULLBACK && trend.confirmed ->
                ObservationStructureQuality.M20_PULLBACK_CONFIRMED
            trend?.type == EntrySetupType.BREAKOUT && trend.confirmed ->
                ObservationStructureQuality.BREAKOUT_CONFIRMED
            trendAssessment?.major == MajorTrend.BOTTOMING ->
                ObservationStructureQuality.SETUP_WATCH
            trend?.type != null && trend.type != EntrySetupType.NONE ->
                ObservationStructureQuality.SETUP_WATCH
            else -> ObservationStructureQuality.NONE
        }
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
                    trendWeak = trend?.trendWeak == true || quote.changePct <= -weakChangePct,
                    stopPrice = stopPrice,
                    changePct = quote.changePct,
                    strategy = candidate.strategy,
                    setupScore = setupScore,
                    relativeStrengthPct = relativeStrength,
                    volumeRatio = volumeRatio,
                    limitUpBlocked = limitUpBlocked,
                    limitUpShrinkAcceleration = limitUpShrinkAcceleration,
                    riskFlag = trend?.trendWeak == true,
                    fundFlow = fundFlow?.let { FundFlowPolicy.assess(quote.changePct, it) },
                    fundFlowWindow = fundFlowWindow,
                    environmentGate = promotionEnvironment.blockReason
                        ?.takeUnless { promotionEnvironment.allowsHighQualityException },
                    environmentTightened = promotionEnvironment.tightened || promotionEnvironment.allowsHighQualityException,
                    structureQuality = structureQuality,
                    marketRisk = promotionEnvironment.marketRisk,
                    evaluatedAtMs = System.currentTimeMillis(),
                    quoteVolume = quote.volume,
                    technicalScore = technicalSnapshot?.technicalScore,
                    weeklyTrendLabel = technicalSnapshot?.weeklyTrend?.label,
                    monthlyTrendLabel = technicalSnapshot?.monthlyTrend?.label,
                    settlesWeakDay = TimeUtils.minuteOfDay() >= WEAK_DAY_SETTLEMENT_START_MINUTE,
                    postLimitDailyBaseEntry = postLimitDailyBaseEntry,
                    postLimitHighHoldCandidate = postLimitHighHoldCandidate,
                    inTradingSession = TradingCalendar.isMarketOpen(),
                    acceleration = acceleration.state,
                    accelerationBlocker = accelerationBlocker,
                    signalReason =
                        when {
                            limitUpShrinkAcceleration -> ObservationEligibility.SHRINK_WAIT_REASON
                            limitUpBlocked -> ObservationEligibility.LIMIT_WAIT_REASON
                            postLimitHighHoldCandidate ->
                                "炸板后守住涨停高位线，等待平台承接和不破低点确认"
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

            ObservationStrategy.LEADER, ObservationStrategy.LIMIT_UP_CONTINUATION -> {
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
                        entryConfirmed || postLimitHighHoldCandidate,
                    ).count { it }
                val riskFlag =
                    trend?.trendWeak == true || quote.changePct <= -weakChangePct ||
                        (ratio >= LEADER_DISTRIBUTION_VOLUME_RATIO && quote.changePct < 1.0) ||
                        (recentLimitUpDays >= 2 && quote.changePct < -1.0)
                val canBuy = entryConfirmed && !riskFlag && !limitUpBlocked && !limitUpShrinkAcceleration
                val confirmationText =
                    when {
                        limitUpShrinkAcceleration -> ObservationEligibility.SHRINK_WAIT_REASON
                        limitUpBlocked -> ObservationEligibility.LIMIT_WAIT_REASON
                        postLimitHighHoldCandidate ->
                            "炸板后守住涨停高位线，等待平台承接满5分钟且不破低点"
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
                    trendWeak = trend?.trendWeak == true || quote.changePct <= -weakChangePct || (relativeStrength != null && relativeStrength < -2.0),
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
                    fundFlow = fundFlow?.let { FundFlowPolicy.assess(quote.changePct, it) },
                    fundFlowWindow = fundFlowWindow,
                    environmentGate = promotionEnvironment.blockReason
                        ?.takeUnless { promotionEnvironment.allowsHighQualityException },
                    environmentTightened = promotionEnvironment.tightened || promotionEnvironment.allowsHighQualityException,
                    structureQuality = structureQuality,
                    marketRisk = promotionEnvironment.marketRisk,
                    evaluatedAtMs = System.currentTimeMillis(),
                    quoteVolume = quote.volume,
                    technicalScore = technicalSnapshot?.technicalScore,
                    weeklyTrendLabel = technicalSnapshot?.weeklyTrend?.label,
                    monthlyTrendLabel = technicalSnapshot?.monthlyTrend?.label,
                    settlesWeakDay = TimeUtils.minuteOfDay() >= WEAK_DAY_SETTLEMENT_START_MINUTE,
                    postLimitDailyBaseEntry = postLimitDailyBaseEntry,
                    postLimitHighHoldCandidate = postLimitHighHoldCandidate,
                    inTradingSession = TradingCalendar.isMarketOpen(),
                    acceleration = acceleration.state,
                    accelerationBlocker = accelerationBlocker,
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

    /**
     * 大盘基准（相对强度与市场过滤用），观察池/自选池复评与快照链路共用。
     * 新浪日 K 盘中不含当日且内容恒定：盘中按日缓存成功结果，把两个池每分钟
     * 各拉一次改为全天只拉一次；当日首次失败不写缓存，下一轮继续重试。
     * 盘外不缓存：收盘后日 K 会补上当日这根，早盘缓存跨收盘复用会让个股侧
     * （带当日实时 bar）与大盘基准错位一天；盘外调用频率低（快照链路 ≤30 分钟
     * 一次），直接拉取。
     */
    private fun observationBenchmark(): List<DayBar>? {
        val today = TimeUtils.todayText()
        if (TradingCalendar.isMarketOpen() && observationBenchmarkDay == today) {
            return observationBenchmark
        }
        return runCatching { sina.kline(MARKET_INDEX_CODES.first(), 60) }.getOrNull()?.also {
            observationBenchmark = it
            observationBenchmarkDay = today
        }
    }

    private data class CandidatePromotionEnvironment(
        val blockReason: String? = null,
        val tightened: Boolean = false,
        /** 非系统性弱市下，仅允许二波或强相对强度的日线承接作为高质量例外。 */
        val allowsHighQualityException: Boolean = false,
        /** 供候选展示排序使用的市场风险层级，不作为额外晋级条件。 */
        val marketRisk: ObservationMarketRisk? = null,
    )

    private fun promotionEnvironmentGate(candidate: ObservationCandidate): CandidatePromotionEnvironment {
        if (!promotionEnvironmentGateEnabled) return CandidatePromotionEnvironment()
        val quotes = Repo.quotes
        val marketEnvironment = candidateMarketRiskGate(candidate.code, quotes)
        if (
            marketEnvironment.blockReason != null || marketEnvironment.tightened ||
            marketEnvironment.allowsHighQualityException
        ) {
            return marketEnvironment
        }
        val limits = Repo.limits
        val conceptChanges = Repo.conceptSummary?.sectors?.associateBy({ it.name }, { it.change }).orEmpty()
        val sectorChanges = Repo.sectors?.all?.associateBy({ it.name }, { it.change }).orEmpty()
        val boardChanges = candidate.boards.map { board -> conceptChanges[board] ?: sectorChanges[board] }
        return PromotionEnvironmentPolicy.gate(
            PromotionEnvironmentInput(null, null, limits?.up?.size, limits?.down?.size, boardChanges),
        )?.let {
            CandidatePromotionEnvironment(
                blockReason = it,
                marketRisk = ObservationMarketRisk.BLOCKED,
            )
        } ?: marketEnvironment
    }

    private fun candidateMarketRiskGate(code: String, quotes: Map<String, Quote>): CandidatePromotionEnvironment {
        val today = TimeUtils.todayText()
        val risks = MARKET_RISK_INDEX_CODES.mapNotNull { indexCode ->
            quotes[indexCode]?.takeIf { it.quoteDate == today }?.takeIf { quote ->
                quote.changePct <= -MarketRiskPolicy.dailyRiskThresholdPct(
                    indexCode,
                    marketRiskDailyVolatility[indexCode]?.takeIf { it.first == today }?.second,
                )
            }?.code
        }.toSet()
        val severeCount = MARKET_RISK_INDEX_CODES.count { indexCode ->
            quotes[indexCode]?.takeIf { it.quoteDate == today }?.let { quote ->
                quote.changePct <= -MarketRiskPolicy.dailySevereThresholdPct(
                    indexCode,
                    marketRiskDailyVolatility[indexCode]?.takeIf { it.first == today }?.second,
                )
            } == true
        }
        val core = setOf(MarketRiskPolicy.SHANGHAI_CODE, MarketRiskPolicy.SHENZHEN_CODE)
        val growth = setOf(MarketRiskPolicy.CHINEXT_CODE, MarketRiskPolicy.SCI_TECH_50_CODE)
        val coreRisk = risks.intersect(core)
        val growthRisk = risks.intersect(growth)
        val growthCandidate = code.startsWith("sz30") || code.startsWith("sh68")
        val hasMarketRiskInput = MARKET_RISK_INDEX_CODES.any { indexCode ->
            quotes[indexCode]?.quoteDate == today
        }
        return when {
            severeCount >= 3 -> CandidatePromotionEnvironment(
                blockReason = "三指数严重动态风险，暂停普通候选晋级",
                marketRisk = ObservationMarketRisk.BLOCKED,
            )
            coreRisk.isNotEmpty() && growthRisk.isNotEmpty() -> CandidatePromotionEnvironment(
                blockReason = "核心与成长指数同步动态风险，仅保留弱市抱团高质量例外",
                allowsHighQualityException = true,
                marketRisk = ObservationMarketRisk.HIGH_QUALITY_ONLY,
            )
            coreRisk.size >= 2 -> CandidatePromotionEnvironment(
                blockReason = "上证与深成指同步动态风险，仅保留弱市抱团高质量例外",
                allowsHighQualityException = true,
                marketRisk = ObservationMarketRisk.HIGH_QUALITY_ONLY,
            )
            coreRisk.isNotEmpty() -> CandidatePromotionEnvironment(
                tightened = true,
                marketRisk = ObservationMarketRisk.TIGHTENED,
            )
            growthRisk.isNotEmpty() && growthCandidate -> CandidatePromotionEnvironment(
                tightened = true,
                marketRisk = ObservationMarketRisk.TIGHTENED,
            )
            hasMarketRiskInput -> CandidatePromotionEnvironment(marketRisk = ObservationMarketRisk.NORMAL)
            else -> CandidatePromotionEnvironment()
        }
    }

    /**
     * 近 5 日相对大盘的超额收益（百分点）。
     *
     * 个股侧带今日盘中实时价；大盘侧原先只算到昨日收盘——指数当日涨 X% 时全池
     * relativeStrength 系统性偏 -X pp（晋级门槛 3.0 与走弱线 -2.0 都被大盘当日
     * 涨跌污染）。现取今日指数实时价对齐口径；指数实时价缺失时两侧同时退回
     * 昨收，保持窗口对称而不是单边带今日。
     */
    private fun relativeStrength(
        history: List<DayBar>,
        price: Double,
        benchmark: List<DayBar>?,
        marketPrice: Double? = null,
    ): Double? {
        val stockBars = history.filter { it.close > 0 && it.date != TimeUtils.todayText() }.takeLast(6)
        val marketBars = benchmark?.filter { it.close > 0 && it.date != TimeUtils.todayText() }?.takeLast(6)
        if (stockBars.size < 2 || marketBars == null || marketBars.size < 2) return null
        val marketNow = marketPrice?.takeIf { it.isFinite() && it > 0 }
        val stockReturn =
            (if (marketNow != null) price else stockBars.last().close) / stockBars.first().close - 1.0
        val marketReturn = (marketNow ?: marketBars.last().close) / marketBars.first().close - 1.0
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
                previous.close > 0 && (current.close / previous.close - 1.0) * 100.0 >= limit - SinaClient.LIMIT_TOLERANCE_PCT
            }
        val current = if (quote.prevClose > 0 && quote.changePct >= limit - SinaClient.LIMIT_TOLERANCE_PCT) 1 else 0
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
            val failedNames = mutableListOf<String>()
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
                    maybeRefreshFundamentalHealth(
                        code = position.code,
                        name = position.name,
                        price = result.price,
                        cost = position.cost,
                        support = result.support,
                    )
                }.onFailure { error ->
                    failedCount++
                    failedNames.add(position.name)
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
            var swingPlan = buildPortfolioSwing(portfolio, benchmark, results).validatedFor(store.portfolio)
            Repo.portfolioSwing = swingPlan
            val priorityNow = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            for (index in results.indices) {
                val result = results[index]
                val bars = runCatching { sina.intradayKline(result.code, 15, MINUTE_KLINE_LENGTH) }
                    .getOrElse { intraday15ByCode[result.code]?.bars.orEmpty() }
                val evidence = TimeframeEvidence.build(
                    bars15 = IntradayBarWindow(bars).withCompletionState(priorityNow, result.analysisDate, 15).bars,
                    bars30 = emptyList(), price = result.price, support = result.support,
                    resistance = result.resistance, now = priorityNow, quoteDate = result.analysisDate,
                )
                val confirmed = evidence.riskComplete && evidence.riskConfirmed && evidence.riskConfirmations >= 3
                val exitReference = com.example.stockmonitor.analysis.HoldingExitPolicy.exitReference(result.support, result.volatility.atrPct)
                results[index] = result.copy(
                    priorityRiskConfirmed = confirmed,
                    priorityExitTriggered = confirmed && result.price < exitReference && result.price < result.ma20 * 0.995,
                    priorityExitAudit = evidence.riskAuditText +
                        "｜退出参考$exitReference｜MA20破位参考${result.ma20 * 0.995}｜连续完成K线确认$confirmed",
                )
            }
            val resultByCode = results.associateBy { it.code }
            swingPlan = swingPlan.copy(rows = swingPlan.rows.map { row ->
                val result = resultByCode[row.code]
                val blocked = result == null ||
                    !com.example.stockmonitor.analysis.PortfolioPriorityPolicy.canApproveAddition(
                        com.example.stockmonitor.analysis.PortfolioAnalysis.priorityInput(result),
                        TimeUtils.todayText(),
                    )
                if (blocked && row.addQuantity > 0) {
                    row.copy(
                        addQuantity = 0.0,
                        estimatedCash = 0.0,
                        afterPositionPct = null,
                        reason = "该持仓有未解决风险或数据未确认，暂不新增",
                    )
                } else {
                    row
                }
            })
            for (index in results.indices) {
                val result = results[index]
                val swing = swingPlan.rows.firstOrNull { it.code == result.code }
                results[index] = result.copy(
                    priorityBuyApproved = swingPlan.capitalIssue == null &&
                        swing?.addQuantity?.let { it > 0 && swing.status == com.example.stockmonitor.analysis.SwingStatus.READY } == true,
                )
            }
            Repo.portfolioSwing = swingPlan
            Repo.portfolioResults = results
            results.forEach { result ->
                liveQuotes[result.code]?.let { quote -> notifyPortfolioSignal(result, quote) }
            }
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
                    DayPnl.summarize(
                        portfolio.positions,
                        liveQuotes,
                        DayPnl.acquisitions(store.tradeBook, TimeUtils.todayText()),
                    ),
                    market = market,
                    swingPlan = swingPlan,
                    // 分歧期状态（模块 A/B）：只用已完成指数日线（剔除今日未收盘 K 线）推导，
                    // 不落盘——状态可从历史确定性重算，见 MarketDivergenceCheck.phaseOf。
                    divergence = MarketDivergenceCheck.phaseOf(
                        MarketDivergenceCheck.fromBars(
                            benchmark.orEmpty().filter { it.date != TimeUtils.todayText() },
                        ),
                    ),
                    strongContinuationEvents = Repo.strongContinuationEvents.filter { it.date == TimeUtils.todayText() },
                    positionAllocations = store.positionAllocations,
                    expectedPositionCount = portfolio.positions.count {
                        it.quantity.isFinite() && it.quantity > 0
                    },
                    analysisFailures = failedNames,
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
                    val recentCloses = bars.takeLast(10).map { it.close }
                    val ma5 = bars.takeLast(5).map { it.close }.average()
                    val ma10 = bars.takeLast(10).map { it.close }.average()
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

                                        // 「破位提示」是 NOTICE 缓释档的新 signal 文案，
                                        // 与「减仓风险」同走这条阻塞分支，行为不随改名漂移。
                                        result.signal in setOf("减仓风险", "破位提示", "趋势转弱观察", "持有/止盈观察") -> {
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
                            ma5 = ma5,
                            ma10 = ma10,
                            recentCloses = recentCloses,
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
                    balanceHistory = payload.balanceHistory,
                    valuations = payload.valuations,
                    totalShares = payload.totalShares,
                    price = payload.price,
                    peers = payload.peers,
                    mainBusiness = payload.mainBusiness,
                    announcements = payload.announcements,
                    shareholder = payload.shareholder,
                    today = TimeUtils.todayText(),
                    orgType = payload.orgType,
                    name = payload.name,
                    quoteName = payload.quoteName,
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

    @Synchronized
    private fun publishExitReview(
        date: String,
        code: String,
        name: String,
        action: GuardedTradeAction,
        evidence: TimeframeEvidence?,
    ) {
        if (!action.requiresExitReview || code !in StockAlertScope.heldCodes(store.portfolio.positions)) return
        val previousPlan = store.dailyAdvicePlans.firstOrNull { it.date == date && it.code == code }
        val paused = store.pauseDailyAdviceForExitReview(date, code, System.currentTimeMillis())
        val key = RiskExitReview.evidenceKey(date, evidence)
        if (!exitReviewDedup.shouldEmit(date, code, key) && paused) return
        val id = NotificationIds.of(ALERT_ID_PORTFOLIO, "riskExitReview|$code")
        val previous = store.alertRecords.lastOrNull { it.kind == "risk_exit_review" && it.notificationId == id }
        val summary = RiskExitReview.evidenceSummary(evidence)
        val comparison = previous?.evidenceSummary?.let {
            "上次复核证据：$it｜本次复核证据：$summary；仅列证据变化，不据此自动升级清仓。"
        } ?: "此前提醒未留存可比的完整退出证据，无法确认相较原计划新增了哪些恶化；本次首次记录复核证据。"
        val body = action.text + "｜$comparison｜" + RiskExitReview.continuity(previousPlan) +
            if (paused) "" else "｜计划暂停保存失败，请勿沿用旧指令，须人工核对。"
        if (Notifier.riskReview(this, id, "⚠️ $name·重大风险待复核", body, key, summary) && paused) {
            exitReviewDedup.markSent(date, code, key)
        }
    }

    @Synchronized
    private fun publishDailyAdvice(
        date: String,
        code: String,
        direction: AdviceDirection,
        kind: AdviceKind,
        targetQuantity: Double,
        currentQuantity: Double,
        title: String,
        riskPriority: Int = 0,
        riskStage: Int = 0,
        send: (Double, String) -> Boolean,
    ): Boolean {
        if (riskPriority >= 2) return false
        if (!targetQuantity.isFinite() || targetQuantity <= 0.0) return false
        val previous = store.dailyAdvicePlans.firstOrNull { it.date == date && it.code == code }
        val plan = store.prepareDailyAdvice(
            date = date,
            code = code,
            direction = direction,
            kind = kind,
            targetQuantity = targetQuantity,
            currentQuantity = currentQuantity,
            riskPriority = riskPriority,
            riskStage = riskStage,
        )
        if (plan.state == com.example.stockmonitor.analysis.AdvicePlanState.REVIEW_REQUIRED &&
            previous?.state != com.example.stockmonitor.analysis.AdvicePlanState.REVIEW_REQUIRED
        ) {
            Notifier.alert(
                this,
                NotificationIds.of(ALERT_ID_SIGNAL, "review|$code"),
                "$code·同日计划待复核",
                DailyAdvicePolicy.displayText(plan),
            )
        }
        if (!DailyAdvicePolicy.canNotify(plan)) return false
        if (plan.direction != direction || plan.kind != kind || plan.riskPriority != riskPriority) return false
        val quantity = DailyAdvicePolicy.publicationQuantity(plan, targetQuantity)
        if (quantity < 100.0) return false
        val reservation = store.reserveDailyAdvice(plan, date, code)
        if (!reservation.accepted) return false
        val result = runCatching { send(quantity, DailyAdvicePolicy.continuityText(reservation.plan, quantity)) }
        if (result.isFailure) {
            store.markDailyAdviceDeliveryUnknown(reservation.plan, reason = "通知异常，送达情况待核对：$title")
            return false
        }
        val posted = result.getOrDefault(false)
        if (posted) {
            val committed = store.commitDailyAdvice(reservation.plan)
            if (committed == null) return false
        } else {
            store.rollbackDailyAdvice(reservation.plan, reason = "通知发送失败：$title")
        }
        return posted
    }

    /** 只在信号状态变化时提醒一次，等价于 _send_portfolio_notification。 */
    private fun notifyPortfolioSignal(
        result: com.example.stockmonitor.analysis.PositionResult,
        quote: Quote,
    ) {
        if (result.code !in StockAlertScope.heldCodes(store.portfolio.positions)) {
            portfolioSignals.remove(result.code)
            return
        }
        if (!store.alertsEnabled || !TradingCalendar.inMarketHoursForAlert()) return
        val previous = portfolioSignals[result.code]
        val priority = PortfolioAnalysis.priorityDecision(result)
        val notificationStage = priority.state
        if (!priority.actionConfirmed) {
            portfolioSignals.remove(result.code)
            return
        }
        val today = quote.quoteDate?.substringBefore(' ') ?: TimeUtils.todayText()
        val tradeBook = store.tradeBook.takeIf { it.lots.isNotEmpty() || it.entries.isNotEmpty() }
            ?: TradeLotLedger.migrate(store.portfolio.positions).also { store.tradeBook = it }
        val availability = TradeLotLedger.availability(
            tradeBook,
            store.portfolio.positions,
            today,
            result.code,
        )
        val nowLocal = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
        val levels = Repo.portfolioResults.associateBy { it.code }
        val holdingCount = levels.size
        val systemicRisk = holdingCount > 0 && (
            levels.values.count { it.changePct < 0.0 } >= (holdingCount + 1) / 2 ||
                Repo.marketState?.regime == MarketRegime.COLLAPSE
            )
        val portfolioRisk = holdingCount >= 3 && actionablePortfolioRiskCount(levels.values.toList()) >= (holdingCount + 1) / 2
        val dailyInput = DailyRiskContextInput(
            quoteDate = result.analysisDate,
            priceLocation = Repo.priceContexts[result.code]?.location?.name,
            rangeLow = result.highPositionEvidence?.rangeLow,
            invalidationPrice = result.entrySetup.stopPrice,
            majorTrend = result.trendAssessment?.major?.name,
            fiveDayNoNewLow = result.fiveDayNoNewLow,
            closesBelowMa20 = result.closesBelowMa20,
            highExitRisk = result.highPosition?.phase?.name == "DISTRIBUTION_RISK",
            systemicRisk = systemicRisk,
            portfolioRisk = portfolioRisk,
            eventRisk = result.fundamentalHealth?.level?.name == "ALERT",
            positionRisk = (result.positionPct ?: 0.0) > 10.0,
        )
        val dailyContext = dailyRiskContextCache
            .getOrBuild(result.code, today) { dailyInput }
            ?.withCurrentInput(dailyInput)
        val evidence = TimeframeEvidence.build(
            bars15 = intraday15ByCode[result.code]?.bars.orEmpty(),
            bars30 = intraday30ByCode[result.code]?.bars.orEmpty(),
            price = quote.price,
            support = result.support,
            resistance = result.resistance,
            now = nowLocal,
            quoteDate = today,
            dailyContext = dailyContext,
            riskLine = com.example.stockmonitor.analysis.NormalDivergencePolicy.riskLine(
                price = quote.price,
                ma5 = result.ma5,
                ma20 = result.ma20,
                priceLocation = Repo.priceContexts[result.code]?.location,
                biasFromMa5Pct = result.ma5.takeIf { it > 0.0 }
                    ?.let { (quote.price / it - 1.0) * 100.0 },
            ),
        )
        val volume = IntradayVolumeContext.build(
            marketPredictedFullDayTurnover = Repo.turnoverSnapshot?.predictedFullDay?.div(100_000_000.0),
            recentCompleteMarketTurnovers = turnoverTracker
                ?.recentFullDayTurnoversYi(today)
                .orEmpty(),
            stockVolumeRatio = Repo.volumeRatios[result.code] ?: result.volumeRatio,
            priceChangePct = quote.changePct,
            bars15 = intraday15ByCode[result.code]?.bars.orEmpty(),
            price = quote.price,
            support = result.support,
            now = nowLocal,
            quoteDate = today,
        )
        val guarded = TradeNotification.portfolio(
            result,
            sellableQuantity = availability.actionableSellableToday,
            lockedToday = availability.lockedToday,
            untrackedQuantity = availability.untrackedQuantity,
            ledgerConsistent = availability.ledgerConsistent,
            tradeDate = today,
            evidence = evidence,
            volume = volume,
        ) ?: return
        if (guarded.requiresExitReview) {
            publishExitReview(today, result.code, result.name, guarded, evidence)
            return
        }
        if (guarded.level != GuardedActionLevel.ACTIONABLE) {
            portfolioSignals.remove(result.code)
            return
        }
        val state = "${result.signal}|$notificationStage|${guarded.action}|${evidence.riskConfirmations}"
        if (state == previous) return
        portfolioSignals[result.code] = state
        val titleAction = when (guarded.action) {
            GuardedAction.RISK_EXIT -> "退出优先"
            GuardedAction.RISK_REDUCE -> "分批减仓"
            else -> notificationStage.label
        }
        val advice = when (guarded.action) {
            GuardedAction.RISK_EXIT -> AdviceDirection.SELL to AdviceKind.EXIT
            GuardedAction.RISK_REDUCE -> AdviceDirection.SELL to AdviceKind.REDUCE
            GuardedAction.NORMAL_EXIT -> AdviceDirection.SELL to AdviceKind.EXIT
            GuardedAction.T_SELL -> AdviceDirection.SELL to AdviceKind.T_SELL
            GuardedAction.BUY_SMALL -> AdviceDirection.BUY to AdviceKind.HOLDING_ADD
            else -> return
        }
        val title = "📦 ${result.name}·$titleAction"
        val plannedQuantity = if (advice.first == AdviceDirection.BUY) guarded.maxBuyQuantity else guarded.maxSellQuantity
        val posted = publishDailyAdvice(
            date = today,
            code = result.code,
            direction = advice.first,
            kind = advice.second,
            targetQuantity = plannedQuantity,
            currentQuantity = result.quantity,
            title = title,
            riskPriority = TradeNotification.riskPriority(guarded),
        ) { quantity, continuity ->
            val finalAction = TradeNotification.limitQuantity(guarded, quantity)
            if (finalAction.level != GuardedActionLevel.ACTIONABLE) return@publishDailyAdvice false
            val sellEstimate = if (advice.first == AdviceDirection.SELL) TradeNotification.estimateSell(
                holdingQuantity = result.quantity,
                averageCost = result.cost,
                price = result.price,
                quantity = finalAction.maxSellQuantity,
            ) else null
            val body = String.format(Locale.CHINA, "现价 %.2f｜%s", result.price, finalAction.text) +
                sellEstimate?.let { "｜${TradeNotification.sellEstimateText(it)}" }.orEmpty() + "｜$continuity" +
                levelFreshnessNote(result)
            if (guarded.action == GuardedAction.RISK_EXIT || guarded.action == GuardedAction.RISK_REDUCE) {
                Notifier.riskSell(this, NotificationIds.of(ALERT_ID_PORTFOLIO, result.code), title, body)
            } else {
                Notifier.alert(this, NotificationIds.of(ALERT_ID_PORTFOLIO, result.code), title, body)
            }
        }
        if (!posted) portfolioSignals.remove(result.code)
    }

    /** 每天 16:00 / 18:00 / 21:00 生成盘面总结，只缓存不弹窗；错过整点用“已到槽位且当日未生成”补跑。 */
    private fun autoGenerateSummary(calendar: Calendar = TimeUtils.nowCalendar()) {
        // 周末不生成；否则 16:00 后每 15 秒失败重试一次（指数行情日期不是今天）。
        if (TimeUtils.isWeekend(calendar)) return
        val today = TimeUtils.todayText()
        // 工作日节假日同理：没有今日指数行情时直接跳过，不进入每 15 秒的失败重试。
        if (Repo.quotes["sh000001"]?.quoteDate?.substringBefore(' ') != today) return
        val hour = TimeUtils.hour(calendar)
        val shouldRun =
            when {
                hour >= 21 && store.lastSummaryNight != today -> 21
                hour >= 18 && store.lastSummaryDate != today -> 18
                hour >= 16 && store.lastSummaryAfternoon != today -> 16
                else -> null
            }
        val slot = shouldRun ?: return
        scope.launch {
            if (generateSummary()) {
                when (slot) {
                    16 -> {
                        store.lastSummaryAfternoon = today
                    }

                    18 -> {
                        store.lastSummaryDate = today
                    }

                    21 -> {
                        // 不能置 null：下一 tick 的 hour>=18 条件会立即再次成立，
                        // 21 点后重复生成一次 18 点总结。
                        store.lastSummaryDate = today
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
            // 同期相对量能（与 HoldingAnalyzer.refreshMarketRiskContext 同一口径）：不传的话
            // environmentQuality 只能走全天预估降级路径，「量能环境」除 UNKNOWN 外永不判定，
            // 盘面总结与盘中链路对同一指标两套口径。
            val sameTimeRatio = Repo.turnoverSnapshot?.let { snapshot ->
                turnoverTracker?.sameTimeRatio(today, snapshot.sampleMinute, snapshot.today)
            }
            Repo.summaryTask = BackgroundTaskState.running("盘面总结", message = "正在整理报告…")
            Repo.notifyChanged()
            val text =
                MarketSummary.generate(
                    MarketSummary.Inputs(
                        quotes = freshQuotes,
                        sectors = Repo.sectors,
                        concept = concept,
                        limits = Repo.limits,
                        marketTurnoverRatio = sameTimeRatio,
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
        private val RECLAIM_STRATEGIES =
            setOf(ObservationStrategy.LEADER, ObservationStrategy.LIMIT_UP_CONTINUATION)
        const val ACTION_REFRESH = "com.example.stockmonitor.action.REFRESH"
        const val ACTION_SCREEN = "com.example.stockmonitor.action.SCREEN"
        const val ACTION_SCREEN_FULL = "com.example.stockmonitor.action.SCREEN_FULL"
        const val ACTION_OBSERVE_SCREEN = "com.example.stockmonitor.action.OBSERVE_SCREEN"
        const val ACTION_SUMMARY = "com.example.stockmonitor.action.SUMMARY"
        const val ACTION_NEWS = "com.example.stockmonitor.action.NEWS"
        const val ACTION_PORTFOLIO = "com.example.stockmonitor.action.PORTFOLIO"

        /** 复盘的自动档：每天这个点（含）之后生成一次。15:05 数据可能未定，16 点更稳。 */
        const val DAILY_REVIEW_HOUR = 16

        /** 手动触发持仓分析时用的槽位标记，不参与盘中槽位去重。 */
        private const val MANUAL_SLOT = "manual"
        const val ACTION_STOP = "com.example.stockmonitor.action.STOP"

        /** 通知栏顶栏最多同时显示的股票数，超出的用 … 省略。 */
        private const val BAR_QUOTE_LIMIT = 3
        private const val LEADER_DISTRIBUTION_VOLUME_RATIO = 2.0
        private const val OBSERVATION_INTRADAY_REFRESH_MS = 60_000L
        private const val FIVE_ONE_THREE_SOURCE_VALID_TRADING_DAYS = 3

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
        const val LIMIT_SCAN_INTERVAL_TICKS = 20

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
        private const val ALERT_ID_VOLUME_PRICE_ANOMALY = 7800
        /** 量价异动触发后，BREAKOUT 机会通道的同事件抑制窗（与机会类信号 10 分钟冷却同量级）。 */
        private const val VOLUME_PRICE_ANOMALY_DEDUP_MS = 10 * 60_000L

        private const val ALERT_ID_OBSERVATION_BUY = 8000
        private const val ALERT_ID_TAIL_OBSERVATION = 8200
        private const val ALERT_ID_LAUNCH_DISCOVERY = 8600
        /** 盘中资金环境（只读提示）：档位跃迁时才推，日内次数与冷却另行限制。 */
        private const val ALERT_ID_MARKET_FUND_FLOW = 8700
        private const val MARKET_FUND_FLOW_MAX_NOTIFY_PER_DAY = 3
        private const val MARKET_FUND_FLOW_COOLDOWN_MS = 30 * 60_000L
        private const val MARKET_FUND_FLOW_MAX_HOLDING_LINES = 6
        /** 观察池/自选候选“突破延续下杀风险”（风险事实，不占机会预算）。 */
        private const val ALERT_ID_OBSERVATION_BREAKOUT_RISK = 8800

        private const val LAUNCH_UNIVERSE_LIMIT = 200
        private const val LAUNCH_SCAN_INTERVAL_TICKS = 20
        private const val LAUNCH_PREFILTER_PCT = 2.5
        private const val LAUNCH_MAX_FETCH_PER_SCAN = 6
        private const val LAUNCH_MAX_FETCH_PER_DAY = 60
        private const val LAUNCH_MAX_ALERTS_PER_DAY = 10
        private const val LAUNCH_MIN_HISTORY_BARS = 25
        private const val LAUNCH_DISCOVERY_SOURCE = "启动突破观察"
        private const val TAIL_OBSERVATION_MAX_NOTIFICATIONS = 3
        private const val TAIL_OBSERVATION_TRIGGER = "tail_observation"
        /** 14:55 后用接近收盘的价格结算弱势日；此前盘中走弱只影响即时降档。 */
        private const val WEAK_DAY_SETTLEMENT_START_MINUTE = 14 * 60 + 55
        /** 常规市场基准：供下探回升、相对强弱和市场成交额等非风险策略使用。 */
        private val MARKET_INDEX_CODES = listOf("sh000001", "sz399001", "sz399006")
        /**
         * 风险预警指数：科创50独立纳入，确保其急跌不依赖用户是否保留默认自选。
         * 不复用 [MARKET_INDEX_CODES]，避免扩大下探回升等非风险策略的触发范围。
         */
        private val MARKET_RISK_INDEX_CODES = MARKET_INDEX_CODES + "sh000688"
        private const val STOCK_DROP_ALERT_COOLDOWN_MS = 10 * 60 * 1000L

        /** 同一只股票同一类盘中信号的冷却时间。 */
        private const val SIGNAL_COOLDOWN_MS = 10 * 60 * 1000L

        /** 每只股票每天的盘中提醒上限，防止极端行情里被同一个标的刷屏。 */
        private const val SIGNAL_MAX_PER_DAY = 8

        /** 15/30分钟K线每5分钟刷新；两次刷新之间用实时价更新当前未收盘柱。 */
        private const val MINUTE_KLINE_REFRESH_MS = 5 * 60 * 1000L
        private const val MARKET_FIVE_MINUTE_REFRESH_MS = 5 * 60 * 1000L
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
