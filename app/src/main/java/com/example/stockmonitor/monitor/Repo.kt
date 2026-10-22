package com.example.stockmonitor.monitor

import android.os.Handler
import android.os.Looper
import com.example.stockmonitor.analysis.AiCard
import com.example.stockmonitor.analysis.FundamentalCard
import com.example.stockmonitor.analysis.FundamentalHealth
import com.example.stockmonitor.analysis.FundamentalHealthSnapshot
import com.example.stockmonitor.analysis.GlobalCard
import com.example.stockmonitor.analysis.MarketRiskContext
import com.example.stockmonitor.analysis.MarketState
import com.example.stockmonitor.analysis.PaperTradeRecord
import com.example.stockmonitor.analysis.PositionResult
import com.example.stockmonitor.analysis.PriceContext
import com.example.stockmonitor.analysis.SectorContext
import com.example.stockmonitor.analysis.StrongContinuationEvent
import com.example.stockmonitor.analysis.SwingPlan
import com.example.stockmonitor.analysis.TechnicalCard
import com.example.stockmonitor.analysis.TurnoverTracker
import com.example.stockmonitor.core.BoardMember
import com.example.stockmonitor.core.ConceptBoards
import com.example.stockmonitor.core.ConceptSummary
import com.example.stockmonitor.core.LimitData
import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationSnapshot
import com.example.stockmonitor.core.PortfolioSnapshotState
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.ScreenResult
import com.example.stockmonitor.core.SectorData
import com.example.stockmonitor.core.SectorFlow
import com.example.stockmonitor.core.SectorLeaders
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局行情状态，等价于 macOS 版的 self.stock_data / self.sector_data / self.indicator_data 等缓存字段。
 * 后台服务写入，界面只读并监听变化。
 */
object Repo {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 后台线程整体替换快照，界面线程只读取不可变 Map，避免 clear/putAll 与迭代并发。
     */
    @Volatile
    var quotes: Map<String, Quote> = emptyMap()

    @Volatile
    var sectorsUpdatedAtMs: Long = 0L
        private set

    @Volatile
    var sectors: SectorData? = null
        set(value) {
            field = value
            sectorsUpdatedAtMs = if (value == null) 0L else System.currentTimeMillis()
        }
    var concepts: ConceptBoards? = null

    /** 概念板块汇总缓存，等价于 macOS 版的 _concept_summary_cache。 */
    @Volatile
    var conceptSummaryUpdatedAtMs: Long = 0L
        private set

    @Volatile
    var conceptSummary: ConceptSummary? = null
        set(value) {
            field = value
            conceptSummaryUpdatedAtMs = if (value == null) 0L else System.currentTimeMillis()
        }
    var limits: LimitData? = null
    var screenResult: ScreenResult = ScreenResult(emptyList(), emptyList())

    @Volatile
    var observationPool: List<ObservationCandidate> = emptyList()

    @Volatile
    var observationSnapshots: List<ObservationSnapshot> = emptyList()

    @Volatile
    var paperTrades: List<PaperTradeRecord> = emptyList()
    var screenStatus: String = "等待刷新"
    var screenStale: Boolean = false
    var screenStaleAt: String? = null

    /** 指标两档（严格/宽松）上次真正刷新的时间；快扫只动这一组。 */
    var screenIndicatorAt: String? = null
    var screenIndicatorAtMs: Long = 0L

    /** 趋势 + 513 上次真正刷新的时间；只有深扫会动它。 */
    var screenPatternAt: String? = null
    var screenPatternAtMs: Long = 0L
    var summaryText: String? = null
    var portfolioResults: List<PositionResult> = emptyList()
    var portfolioSwing: SwingPlan? = null

    @Volatile
    var strongContinuationEvents: List<StrongContinuationEvent> = emptyList()

    /**
     * 各持仓最近一次基本面健康判断（见 FundamentalHealthPolicy）。基本面是多接口合成的，
     * 只对浮亏较深或已破位的持仓按需补拉、每股冷却数小时（MonitorService.maybeRefreshFundamentalHealth），
     * 不进 15 秒行情轮询。界面与破位提醒只读。
     */
    @Volatile
    var fundamentalHealth: Map<String, FundamentalHealthSnapshot> = emptyMap()

    /**
     * 写入一次基本面健康判断。监控链路的按需补拉、工具页手动分析、AI 工具取报告
     * 都走这里——同一只股票谁先算完谁写入，其他链路读同一份，不重复打接口。
     */
    fun publishFundamentalHealth(
        code: String,
        health: FundamentalHealth,
        fetchedAtMs: Long = System.currentTimeMillis(),
    ) {
        fundamentalHealth = fundamentalHealth + (code to FundamentalHealthSnapshot(health, fetchedAtMs))
    }

    var portfolioReport: String? = null
    var portfolioTask: BackgroundTaskState = BackgroundTaskState.idle("持仓分析")

    /**
     * 持仓状态快照（今天 + 上一交易日）。重启后由 MonitorService 从 [Store.portfolioSnapshot] 恢复。
     * 「较昨日」的对照数据就是 [PortfolioSnapshotState.prevRows]——没有它，报告只能写「现在在哪」。
     */
    @Volatile
    var portfolioSnapshot: PortfolioSnapshotState? = null

    /**
     * 最近一次算出来的大盘状态（[MarketRegime]）。持仓页「今日要点」要用它，
     * 免得 UI 层自己再拉一次沪指日线去重算 MA20/MA60。
     */
    @Volatile
    var marketState: MarketState? = null

    /** 北向、成交额和时点因素的共享风险上下文，持仓分析与 AI 工具读取同一份。 */
    @Volatile
    var marketRiskContext: MarketRiskContext? = null

    /**
     * 各持仓的板块上下文（批次二接入东财 F10 后填充）。
     * 只读缓存，界面与分析都从这里取「这只股属于哪个行业、行业什么状态」。
     */
    @Volatile
    var sectorContexts: Map<String, SectorContext> = emptyMap()

    /** 行业板块最近一次抓取的成交额增量倍数，按当前同周期历史中位数计算。 */
    @Volatile
    var sectorTurnoverRatios: Map<String, Double?> = emptyMap()

    @Volatile
    var sectorDataAsOfMs: Long? = null

    /** 行业板块主力资金快照，按东方财富板块代码索引。 */
    @Volatile
    var sectorFlows: Map<String, SectorFlow> = emptyMap()

    @Volatile
    var sectorFlowAsOfMs: Long? = null

    /**
     * 各归类的「龙头」集合（`{"BK0438": SectorLeaders(...)}`），做 T 降级只认它们。
     * 强弱（封板 / 炸板守住高位线）由 `LeaderStrengthTracker` 每轮行情实时判，这里只存谁是龙头。
     */
    @Volatile
    var sectorLeaders: Map<String, SectorLeaders> = emptyMap()

    /**
     * 各归类的成分股候选全集（`{"BK0438": [...]}`，自动成分 ∪ 人工名单），
     * 只给「板块管理」和「调整龙头」编辑器列候选用。
     * 跟 [sectorLeaders] 一起在板块数据刷新时更新，不做盘中扫描。
     */
    @Volatile
    var sectorMembers: Map<String, List<BoardMember>> = emptyMap()

    /**
     * 板块**当日涨跌**的最后手段兜底（新浪行业板块，每 30 分钟刷新一次）。
     *
     * 匹配顺序：**精确同名 → 对照表**。新浪是自己的一套 50 个行业
     * （电力行业/电器行业/发电设备），东财 F10 用申万式（电力设备），
     * 跨体系做「互相包含」会错配（东财的「电子」会同时命中新浪的「电子器件」和「电子信息」），
     * 所以不做模糊匹配，改走人工对照表。
     *
     * [onCold] 在行业板块列表还没加载时被调用一次去补拉——那份列表是 30 分钟一轮的缓存，
     * 刚开 App 就手动跑个股分析时它是空的。
     */
    fun sectorChangeByName(
        name: String,
        onCold: (() -> SectorData?)? = null,
    ): com.example.stockmonitor.analysis.BoardChange {
        if (name.isBlank()) {
            return com.example.stockmonitor.analysis
                .BoardChange(null, "无行业名可匹配")
        }
        if (sectors == null && onCold != null) {
            onCold()?.let { sectors = it }
        }
        val all = sectors?.all.orEmpty()
        if (all.isEmpty()) {
            // 「没加载」和「加载了但没这个名字」是两个问题，分开写才修得动。
            // source 只写原因，「暂缺」和括号由渲染层统一加。
            return com.example.stockmonitor.analysis
                .BoardChange(null, "新浪行业板块未加载")
        }
        all.firstOrNull { it.name == name }?.let {
            return com.example.stockmonitor.analysis.BoardChange(
                pct = it.change,
                source = "新浪行业板块（同名匹配）",
                turnover = it.volume,
                turnoverRatio = sectorTurnoverRatios[it.name],
                asOfMs = sectorDataAsOfMs,
            )
        }
        val alias = SINA_BOARD_ALIAS[name]
        if (alias != null) {
            all.firstOrNull { it.name == alias }?.let {
                // 标成「对照表」：这是「最接近的行业」，不是准确归属，别当成精确数据。
                return com.example.stockmonitor.analysis.BoardChange(
                    pct = it.change,
                    source = "新浪行业板块「${it.name}」（对照表）",
                    turnover = it.volume,
                    turnoverRatio = sectorTurnoverRatios[it.name],
                    asOfMs = sectorDataAsOfMs,
                )
            }
        }
        return com.example.stockmonitor.analysis.BoardChange(
            null,
            "新浪行业板块里无同名项（试过同名与对照表）",
        )
    }

    /**
     * 东财/申万行业名 → 新浪行业名的人工对照。
     *
     * 两套分类体系不同，跨体系只能靠这张表。**一对多的取最贴近的那个**（如 电子→电子器件），
     * 所以走这条路的结果标成「对照表」而不是精确归属——它回答的是「这个板块今天跌了多少」，
     * 不是「这只股严格属于哪个板块」。
     *
     * 新浪那 50 个行业：交通运输 仪器仪表 传媒娱乐 供水供气 公路桥梁 其它行业 农林牧渔 农药化肥
     * 化工行业 化纤行业 医疗器械 印刷包装 发电设备 商业百货 塑料制品 家具行业 家电行业 建筑建材
     * 开发区 房地产 摩托车 有色金属 服装鞋类 机械行业 次新股 水泥行业 汽车制造 煤炭行业 物资外贸
     * 环保行业 玻璃行业 生物制药 电力行业 电器行业 电子信息 电子器件 石油行业 纺织机械 纺织行业
     * 综合行业 船舶制造 造纸行业 酒店旅游 酿酒行业 金融行业 钢铁行业 陶瓷行业 飞机制造 食品行业
     */
    private val SINA_BOARD_ALIAS =
        mapOf(
            "电力设备" to "发电设备",
            "电力" to "电力行业",
            "公用事业" to "电力行业",
            "食品饮料" to "食品行业",
            "白酒" to "酿酒行业",
            "医药生物" to "生物制药",
            "医疗器械" to "医疗器械",
            "电子" to "电子器件",
            "半导体" to "电子器件",
            "计算机" to "电子信息",
            "通信" to "电子信息",
            "机械设备" to "机械行业",
            "基础化工" to "化工行业",
            "有色金属" to "有色金属",
            "钢铁" to "钢铁行业",
            "煤炭" to "煤炭行业",
            "石油石化" to "石油行业",
            "房地产" to "房地产",
            "建筑材料" to "建筑建材",
            "建筑装饰" to "建筑建材",
            "交通运输" to "交通运输",
            "汽车" to "汽车制造",
            "家用电器" to "家电行业",
            "纺织服饰" to "纺织行业",
            "轻工制造" to "印刷包装",
            "农林牧渔" to "农林牧渔",
            "商贸零售" to "商业百货",
            "社会服务" to "酒店旅游",
            "传媒" to "传媒娱乐",
            "国防军工" to "飞机制造",
            "银行" to "金融行业",
            "非银金融" to "金融行业",
            "环保" to "环保行业",
            "综合" to "综合行业",
            "美容护理" to "其它行业",
        )

    /** 「持仓复盘」报告正文；重启后由 MonitorService 从 [Store.lastDailyReviewText] 恢复。 */
    var dailyReviewText: String? = null
    var dailyReviewTask: BackgroundTaskState = BackgroundTaskState.idle("持仓复盘")
    var summaryTask: BackgroundTaskState = BackgroundTaskState.idle("盘面总结")

    // ── 工具页分析任务（AnalysisService 写、ToolsFragment 读）。与后台监控开关无关，
    // 所以不进 reset()：停止监控不应清掉用户主动发起的分析任务与结果。──
    var technicalTask: BackgroundTaskState = BackgroundTaskState.idle("个股技术分析")
    var fundamentalTask: BackgroundTaskState = BackgroundTaskState.idle("个股基本面分析")
    var globalTask: BackgroundTaskState = BackgroundTaskState.idle("港美股基本面分析")
    var aiTask: BackgroundTaskState = BackgroundTaskState.idle("AI 综合分析")

    @Volatile
    var toolTechnicalCard: TechnicalCard? = null

    @Volatile
    var toolFundamentalCard: FundamentalCard? = null

    @Volatile
    var toolGlobalCard: GlobalCard? = null

    @Volatile
    var toolAiCard: AiCard? = null

    /** 今日两市累计成交额（元），每轮行情刷新更新。 */
    var turnoverToday: Double = 0.0

    /** 两市成交额的「昨日同一时刻」对比，每 15 分钟重算一次。 */
    var turnoverSnapshot: TurnoverTracker.Snapshot? = null
    var lastUpdateAt: String? = null
    var statusText: String = "初始化…"
    var lastError: String? = null
    var refreshing: Boolean = false
    var screenProgress: String? = null

    /**
     * 自选股量比（今日累计成交量按已交易时间折算 ÷ 20 日均量），由服务每轮 tick 重算。
     * 界面用它决定成交额是否标红、是否加「放量」标识，口径见 VolumeRatio。
     */
    @Volatile
    var volumeRatios: Map<String, Double> = emptyMap()

    /**
     * 自选股价格位置上下文（近 60 日区间位置 + 从低点反弹幅度，见 PricePosition）。区间每天只变一次，
     * 位置本身每轮 tick 用实时价重算，供界面判断量价标签该往哪个方向解释。
     */
    @Volatile
    var priceContexts: Map<String, PriceContext> = emptyMap()

    /** 自选股显示顺序（由服务在读取配置后写入）。 */
    var watchlistOrder: List<Pair<String, String>> = emptyList()

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 通知界面刷新（主线程）。 */
    fun notifyChanged() {
        mainHandler.post { listeners.forEach { it() } }
    }

    fun reset() {
        quotes = emptyMap()
        volumeRatios = emptyMap()
        priceContexts = emptyMap()
        sectors = null
        concepts = null
        conceptSummary = null
        limits = null
        screenResult = ScreenResult(emptyList(), emptyList())
        observationPool = emptyList()
        observationSnapshots = emptyList()
        paperTrades = emptyList()
        screenStale = false
        screenStaleAt = null
        screenIndicatorAt = null
        screenIndicatorAtMs = 0L
        screenPatternAt = null
        screenPatternAtMs = 0L
        portfolioResults = emptyList()
        portfolioSwing = null
        fundamentalHealth = emptyMap()
        portfolioReport = null
        portfolioTask = BackgroundTaskState.idle("持仓分析")
        portfolioSnapshot = null
        marketState = null
        marketRiskContext = null
        sectorContexts = emptyMap()
        sectorTurnoverRatios = emptyMap()
        sectorDataAsOfMs = null
        sectorFlows = emptyMap()
        sectorLeaders = emptyMap()
        sectorMembers = emptyMap()
        sectorFlowAsOfMs = null
        dailyReviewText = null
        dailyReviewTask = BackgroundTaskState.idle("持仓复盘")
        summaryTask = BackgroundTaskState.idle("盘面总结")
        summaryText = null
        turnoverToday = 0.0
        turnoverSnapshot = null
        lastUpdateAt = null
        statusText = "已停止监控"
    }
}
