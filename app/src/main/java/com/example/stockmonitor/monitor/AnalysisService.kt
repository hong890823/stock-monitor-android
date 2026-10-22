package com.example.stockmonitor.monitor

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.AiAnalysisReport
import com.example.stockmonitor.analysis.AiCard
import com.example.stockmonitor.analysis.EnvContext
import com.example.stockmonitor.analysis.FundamentalCard
import com.example.stockmonitor.analysis.FundamentalHealthPolicy
import com.example.stockmonitor.analysis.Fundamentals
import com.example.stockmonitor.analysis.FundamentalsReport
import com.example.stockmonitor.analysis.GlobalCard
import com.example.stockmonitor.analysis.GlobalFundamentalsReport
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.StockAnalysis
import com.example.stockmonitor.analysis.TechnicalCard
import com.example.stockmonitor.analysis.TechnicalReference
import com.example.stockmonitor.analysis.TechnicalSummary
import com.example.stockmonitor.analysis.TimeUtils
import com.example.stockmonitor.analysis.TradingCalendar
import com.example.stockmonitor.core.AiAnalysisInput
import com.example.stockmonitor.core.AnalysisReportCache
import com.example.stockmonitor.core.BoardTagCache
import com.example.stockmonitor.core.DeepSeekClient
import com.example.stockmonitor.core.EastMoneyClient
import com.example.stockmonitor.core.FundamentalsClient
import com.example.stockmonitor.core.GlobalFundamentalsClient
import com.example.stockmonitor.core.GlobalSecurity
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.RowSnapshot
import com.example.stockmonitor.core.SinaClient
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.ui.ReportActivity
import com.example.stockmonitor.ui.StockAiToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 工具页分析任务的临时前台服务：串行执行技术/基本面/港美股/AI 四条分析流水线。
 * 与 MonitorService 完全独立——队列空即自停，不受「后台监控」开关影响。
 */
class AnalysisService : Service() {
    enum class Kind {
        TECHNICAL,
        FUNDAMENTAL,
        GLOBAL,
        AI,
    }

    data class Task(
        val kind: Kind,
        val keyword: String,
        val global: GlobalSecurity? = null,
    )

    private data class Completed(
        val reportTitle: String,
        val report: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Task>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    private val notifySeq = AtomicInteger(0)

    @Volatile
    private var lastStartId = 0

    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        ForegroundProbe.ensure(this)
        Notifier.ensureChannels(this)
        startForegroundCompat()
        scope.launch {
            for (task in queue) {
                runTask(task)
                maybeStop()
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
        if (intent?.action == ACTION_ENQUEUE) {
            parse(intent)?.let { task ->
                setState(task.kind, BackgroundTaskState.running(titleOf(task.kind), message = "排队中…"))
                pending.incrementAndGet()
                queue.trySend(task)
            }
        }
        startForegroundCompat()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Kind.entries.forEach { kind ->
            val state = stateOf(kind)
            if (state.isActive) {
                setState(
                    kind,
                    BackgroundTaskState.failure(titleOf(kind), "服务被停止，任务中断", TimeUtils.nowText()),
                )
                Repo.notifyChanged()
            }
        }
        super.onDestroy()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Log.w(TAG, "分析服务达到系统时限，准备安全停止：startId=$startId type=$fgsType")
        Kind.entries.forEach { kind ->
            if (stateOf(kind).isActive) {
                setState(kind, BackgroundTaskState.failure(titleOf(kind), "达到系统时限，任务中断", TimeUtils.nowText()))
            }
        }
        Repo.notifyChanged()
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 先抄 startId 再看队列：其间若有新任务入队，其 startId 更大，本次 stopSelf 是空操作。 */
    private fun maybeStop() {
        val idSnapshot = lastStartId
        if (pending.get() > 0) return
        stopSelf(idSnapshot)
    }

    private suspend fun runTask(task: Task) {
        try {
            setState(task.kind, BackgroundTaskState.running(titleOf(task.kind), message = busyMessageOf(task.kind)))
            Repo.notifyChanged()
            val store = Store(this)
            val payload =
                withContext(Dispatchers.IO) {
                    runCatchingCancellable { execute(task, store) }
                }
            payload.fold(
                onSuccess = { completed ->
                    setState(
                        task.kind,
                        BackgroundTaskState.success(titleOf(task.kind), TimeUtils.nowText(), "报告已生成"),
                    )
                    Repo.notifyChanged()
                    deliver(completed.reportTitle, completed.report)
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    Log.e(TAG, "${titleOf(task.kind)}失败: ${task.keyword}", error)
                    val message = error.message ?: failureLabelOf(task.kind)
                    setState(
                        task.kind,
                        BackgroundTaskState.failure(titleOf(task.kind), message, TimeUtils.nowText()),
                    )
                    Repo.notifyChanged()
                    deliverFailure(titleOf(task.kind), message)
                },
            )
        } finally {
            pending.decrementAndGet()
        }
    }

    /**
     * 与 runCatching 同构，但显式重抛 CancellationException：
     * 否则取消会被折叠成普通失败，用户看到误导性的「分析失败」。
     */
    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    // ── 四条流水线：从 ToolsFragment 保真迁移，只换宿主与结果分发 ──

    private fun execute(
        task: Task,
        store: Store,
    ): Completed =
        when (task.kind) {
            Kind.TECHNICAL -> executeTechnical(task, store)
            Kind.FUNDAMENTAL -> executeFundamental(task, store)
            Kind.GLOBAL -> executeGlobal(task, store)
            Kind.AI -> executeAi(task, store)
        }

    private fun executeTechnical(
        task: Task,
        store: Store,
    ): Completed {
        val keyword = task.keyword
        val sina = SinaClient()
        val hit =
            sina.search(keyword).firstOrNull()
                ?: throw IllegalStateException("没有找到「$keyword」对应的股票")
        val live = sina.quote(hit.first)
        val history = sina.kline(hit.first, 240)
        if (history.size < 60) throw IllegalStateException("日线数据不足60个交易日，无法稳定分析")
        val benchmark = if (hit.first == "sh000001") null else sina.kline("sh000001", 240)
        val result =
            StockAnalysis.analyze(
                history,
                hit.first,
                hit.second,
                live,
                benchmark,
                // 盘中按已过交易时间折算量比，收盘后自动退回纯日线口径。
                elapsedTradingMinutes = TradingCalendar.tradingMinutesElapsed(),
            )
        // 环境要先就位，否则报告里那句「回踩不破均线可以继续持有」在塌陷期是反话。
        val env =
            EnvContext(
                market = MarketRegime.evaluate(benchmark.orEmpty()),
                sector =
                    BoardTagCache.loadContext(
                        store = store,
                        eastMoney = EastMoneyClient(),
                        code = hit.first,
                        boardChangeFallback = { names ->
                            val boards =
                                runCatching { EastMoneyClient().industryBoardChanges() }
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
        val card =
            TechnicalCard(
                result = result,
                summary = TechnicalSummary.from(result),
                report = StockAnalysis.formatReport(result, env),
            )
        store.analysisReports =
            AnalysisReportCache.merge(
                raw = store.analysisReports,
                code = card.result.code,
                name = card.result.name,
                technical = card.report,
                fundamental = null,
                updatedAt = TimeUtils.nowText(),
                // 存下这次的状态，下次的「较上次分析」才有对照。
                snapshot =
                    RowSnapshot(
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
        Repo.toolTechnicalCard = card
        return Completed("🔍 ${card.result.name} 技术分析", card.report)
    }

    private fun executeFundamental(
        task: Task,
        store: Store,
    ): Completed {
        val keyword = task.keyword
        val sina = SinaClient()
        val hit =
            sina.search(keyword).firstOrNull()
                ?: throw IllegalStateException("没有找到「$keyword」对应的股票")
        val data = FundamentalsClient().fetch(hit.first, hit.second)
        val technicalResult =
            runCatching {
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
        val result =
            Fundamentals.analyze(
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
                    today = TimeUtils.todayText(),
                ),
            )
        val card =
            FundamentalCard(
                name = hit.second,
                code = hit.first,
                price = data.price,
                result = result,
                technical = technicalResult,
                report =
                    FundamentalsReport.format(
                        hit.second,
                        hit.first,
                        data.price,
                        TimeUtils.nowText(),
                        result,
                        technical =
                            technicalResult?.let {
                                TechnicalReference(it.support, it.resistance, it.entrySetup)
                            },
                    ),
            )
        // 手动分析的结果写进健康度缓存：盘中破位提醒立刻能用，
        // 监控链路在冷却内也不会把同样的数据再拉一遍。
        Repo.publishFundamentalHealth(
            card.code,
            FundamentalHealthPolicy.grade(card.result, TimeUtils.nowText()),
        )
        store.analysisReports =
            AnalysisReportCache.merge(
                raw = store.analysisReports,
                code = card.code,
                name = card.name,
                technical = null,
                fundamental = card.report,
                updatedAt = TimeUtils.nowText(),
            )
        Repo.toolFundamentalCard = card
        return Completed("📊 ${card.name} 基本面分析", card.report)
    }

    private fun executeGlobal(
        task: Task,
        store: Store,
    ): Completed {
        val keyword = task.keyword
        val client = GlobalFundamentalsClient()
        val security =
            task.global
                ?: client.search(keyword).firstOrNull()
                ?: throw IllegalStateException("没有找到「$keyword」对应的港股或美股")
        val data = client.fetch(security)
        val technicalResult =
            runCatching {
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
                        live =
                            Quote(
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
        val card =
            GlobalCard(
                data = data,
                technical = technicalResult,
                report = GlobalFundamentalsReport.format(data, TimeUtils.nowText(), technicalResult),
            )
        store.analysisReports =
            AnalysisReportCache.merge(
                raw = store.analysisReports,
                code = card.data.security.symbol,
                name = card.data.security.name,
                technical = null,
                fundamental = card.report,
                updatedAt = TimeUtils.nowText(),
            )
        Repo.toolGlobalCard = card
        return Completed("🌎 ${card.data.security.name} 基本面分析", card.report)
    }

    private fun executeAi(
        task: Task,
        store: Store,
    ): Completed {
        val keyword = task.keyword
        val tools = StockAiToolExecutor(this)
        val input =
            AiAnalysisInput(
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
        val name = security?.name ?: keyword.take(24)
        val code = security?.code.orEmpty()
        val market =
            security?.market
                ?: when (result.analysisScope.lowercase()) {
                    "sector" -> "行业/概念"
                    "market" -> "市场"
                    "strategy" -> "策略方法"
                    else -> "一般研究"
                }
        val asOf = TimeUtils.nowText()
        val title = AiAnalysisReport.title(result, name, code)
        val card =
            AiCard(
                name = name,
                code = code,
                market = market,
                result = result,
                report = AiAnalysisReport.format(result, name, code, asOf),
                title = title,
            )
        // AI 报告落盘：进程被杀后仍能从工具页查看最近一次结果。
        store.lastAiReportText = card.report
        store.lastAiReportTitle = card.title
        store.lastAiReportAt = asOf
        Repo.toolAiCard = card
        return Completed(card.title, card.report)
    }

    // ── 结果分发 ──

    private fun deliver(
        title: String,
        report: String,
    ) {
        if (ForegroundProbe.isInForeground) {
            runCatching { ReportActivity.open(this, title, report) }
                .onFailure {
                    Log.w(TAG, "前台打开报告失败，改发通知", it)
                    notifyComplete(title, report)
                }
        } else {
            notifyComplete(title, report)
        }
    }

    private fun notifyComplete(
        title: String,
        report: String,
    ) {
        Notifier.analysisComplete(
            this,
            nextNotifyId(),
            "✅ 分析完成",
            "$title 已生成，点击查看报告",
            title,
            report,
        )
    }

    private fun deliverFailure(
        title: String,
        message: String,
    ) {
        if (ForegroundProbe.isInForeground) {
            mainHandler.post {
                Toast.makeText(this, "${title}失败：$message", Toast.LENGTH_LONG).show()
            }
        } else {
            Notifier.analysisFailed(this, nextNotifyId(), "${title}失败", message)
        }
    }

    private fun nextNotifyId(): Int = 9100 + (notifySeq.incrementAndGet() % 100)

    // ── 前台通知 ──

    private fun startForegroundCompat() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifier.NOTIFICATION_ID_ANALYSIS_FGS,
                notification,
                ForegroundServicePolicy.activeTypeFor(Build.VERSION.SDK_INT),
            )
        } else {
            startForeground(Notifier.NOTIFICATION_ID_ANALYSIS_FGS, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val count = pending.get()
        val text = if (count > 1) "正在分析…（队列中 ${count - 1} 个）" else "正在分析…"
        val pendingIntent =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, com.example.stockmonitor.ui.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, Notifier.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle("分析任务")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun parse(intent: Intent): Task? {
        val kindName = intent.getStringExtra(EXTRA_KIND) ?: return null
        val kind = runCatching { Kind.valueOf(kindName) }.getOrNull() ?: return null
        val keyword = intent.getStringExtra(EXTRA_KEYWORD) ?: return null
        val symbol = intent.getStringExtra(EXTRA_G_SYMBOL)
        val global =
            if (symbol.isNullOrBlank()) {
                null
            } else {
                GlobalSecurity(
                    symbol = symbol,
                    name = intent.getStringExtra(EXTRA_G_NAME).orEmpty(),
                    exchange = intent.getStringExtra(EXTRA_G_EXCHANGE).orEmpty(),
                    currency = intent.getStringExtra(EXTRA_G_CURRENCY).orEmpty(),
                )
            }
        return Task(kind, keyword, global)
    }

    companion object {
        private const val TAG = "StockMonitor"
        const val ACTION_ENQUEUE = "com.example.stockmonitor.action.ANALYSIS_ENQUEUE"
        const val EXTRA_KIND = "kind"
        const val EXTRA_KEYWORD = "keyword"
        const val EXTRA_G_SYMBOL = "g_symbol"
        const val EXTRA_G_NAME = "g_name"
        const val EXTRA_G_EXCHANGE = "g_exchange"
        const val EXTRA_G_CURRENCY = "g_currency"

        // ── 状态 ──

        fun titleOf(kind: Kind): String =
            when (kind) {
                Kind.TECHNICAL -> "个股技术分析"
                Kind.FUNDAMENTAL -> "个股基本面分析"
                Kind.GLOBAL -> "港美股基本面分析"
                Kind.AI -> "AI 综合分析"
            }

        fun busyMessageOf(kind: Kind): String =
            when (kind) {
                Kind.TECHNICAL -> "正在分析…"
                Kind.FUNDAMENTAL -> "正在拉财报、估值与公告…"
                Kind.GLOBAL -> "正在拉取港美股财务数据…"
                Kind.AI -> "正在汇总数据并请求 AI…"
            }

        fun failureLabelOf(kind: Kind): String =
            when (kind) {
                Kind.TECHNICAL -> "分析失败"
                Kind.FUNDAMENTAL -> "基本面分析失败"
                Kind.GLOBAL -> "港美股基本面分析失败"
                Kind.AI -> "AI 综合分析失败"
            }

        fun stateOf(kind: Kind): BackgroundTaskState =
            when (kind) {
                Kind.TECHNICAL -> Repo.technicalTask
                Kind.FUNDAMENTAL -> Repo.fundamentalTask
                Kind.GLOBAL -> Repo.globalTask
                Kind.AI -> Repo.aiTask
            }

        fun setState(
            kind: Kind,
            state: BackgroundTaskState,
        ) {
            when (kind) {
                Kind.TECHNICAL -> Repo.technicalTask = state
                Kind.FUNDAMENTAL -> Repo.fundamentalTask = state
                Kind.GLOBAL -> Repo.globalTask = state
                Kind.AI -> Repo.aiTask = state
            }
        }

        fun enqueue(
            context: Context,
            task: Task,
        ) {
            // 入队即置「排队中…」：等 onStartCommand 再置太晚，按钮点击后要立刻变忙碌态。
            setState(task.kind, BackgroundTaskState.running(titleOf(task.kind), message = "排队中…"))
            val intent =
                Intent(context, AnalysisService::class.java)
                    .setAction(ACTION_ENQUEUE)
                    .putExtra(EXTRA_KIND, task.kind.name)
                    .putExtra(EXTRA_KEYWORD, task.keyword)
            task.global?.let {
                intent
                    .putExtra(EXTRA_G_SYMBOL, it.symbol)
                    .putExtra(EXTRA_G_NAME, it.name)
                    .putExtra(EXTRA_G_EXCHANGE, it.exchange)
                    .putExtra(EXTRA_G_CURRENCY, it.currency)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    Log.e(TAG, "启动分析服务失败", it)
                    setState(task.kind, BackgroundTaskState.failure(titleOf(task.kind), "启动分析服务失败", TimeUtils.nowText()))
                }
        }
    }
}
