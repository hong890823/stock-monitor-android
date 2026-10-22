package com.example.stockmonitor.core

import android.content.Context
import android.content.SharedPreferences
import com.example.stockmonitor.analysis.PaperTradeRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地持久化。自选股和持仓使用与 macOS 版 watchlist.json / portfolio.json 相同的字段名，
 * 方便两边互相导入导出。
 */
class Store(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("stock_monitor", Context.MODE_PRIVATE)

    var watchlist: MutableList<WatchItem>
        get() = parseWatchlist(prefs.getString(KEY_WATCHLIST, null))
        set(value) = prefs.edit().putString(KEY_WATCHLIST, toWatchlistJson(value).toString()).apply()

    var portfolio: Portfolio
        // 读档时就折算旧版钉死值，这样后续 copy 改持仓会带着固定修正值浮动。
        get() = parsePortfolio(prefs.getString(KEY_PORTFOLIO, null)).materializeCapitalCorrection()
        set(value) {
            val sanitized =
                value.copy(
                    availableCapital =
                        value.availableCapital
                            .takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                    availableCapitalOverride =
                        value.availableCapitalOverride
                            ?.takeIf { it.isFinite() && it >= 0 },
                    availableCapitalDelta =
                        value.availableCapitalDelta
                            .takeIf { it.isFinite() } ?: 0.0,
                )
            prefs.edit().putString(KEY_PORTFOLIO, toPortfolioJson(sanitized).toString()).apply()
        }

    /**
     * 两市成交额的盘中采样，结构 {"2026-09-23":{"570":1234567.0,...}}，单位元。
     * key 是采样时刻的当天分钟数（09:30 = 570），不是向下取整的槽位起点，
     * 这样次日才能按同一钟表时刻取「昨日同期」；只保留最近几个交易日。
     */
    var turnoverSamples: JSONObject
        get() =
            runCatching { JSONObject(prefs.getString(KEY_TURNOVER_SAMPLES, "{}") ?: "{}") }
                .getOrDefault(JSONObject())
        set(value) = prefs.edit().putString(KEY_TURNOVER_SAMPLES, value.toString()).apply()

    /** 指标选股夜间调度状态。 */
    var indicatorState: JSONObject
        get() =
            runCatching { JSONObject(prefs.getString(KEY_INDICATOR_STATE, "{}") ?: "{}") }
                .getOrDefault(JSONObject())
        set(value) = prefs.edit().putString(KEY_INDICATOR_STATE, value.toString()).apply()

    var portfolioSlot: String?
        get() = prefs.getString(KEY_PORTFOLIO_SLOT, null)
        set(value) = prefs.edit().putString(KEY_PORTFOLIO_SLOT, value).apply()

    /** 最近一次「📦 持仓信息」报告，重启后仍可查看（报告正文里带分析时间）。 */
    var lastPortfolioReport: String?
        get() = prefs.getString(KEY_PORTFOLIO_REPORT, null)
        set(value) = prefs.edit().putString(KEY_PORTFOLIO_REPORT, value).apply()

    /** 最近一次「持仓复盘」报告，重启后仍可查看（正文里带生成时间）。 */
    var lastDailyReviewText: String?
        get() = prefs.getString(KEY_DAILY_REVIEW_TEXT, null)
        set(value) = prefs.edit().putString(KEY_DAILY_REVIEW_TEXT, value).apply()

    /**
     * 今天有没有自动生成过复盘（`yyyy-MM-dd`）。只有自动档写这个戳，手动「重跑」不写——
     * 否则下午手动跑一次就把 16 点的自动档顶掉了。
     */
    var lastDailyReviewDate: String?
        get() = prefs.getString(KEY_DAILY_REVIEW_DATE, null)
        set(value) = prefs.edit().putString(KEY_DAILY_REVIEW_DATE, value).apply()

    var strongContinuationEvents: String
        get() = prefs.getString(KEY_STRONG_CONTINUATION_EVENTS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_STRONG_CONTINUATION_EVENTS, value).apply()

    /**
     * 持仓分析的状态快照（今天 + 上一交易日），[PortfolioSnapshotJson] 编解码。
     * 复盘写「较昨日」全靠它——没有它就只能写「现在在哪」，写不出「今天变了什么」。
     */
    var portfolioSnapshot: String?
        get() = prefs.getString(KEY_PORTFOLIO_SNAPSHOT, null)
        set(value) = prefs.edit().putString(KEY_PORTFOLIO_SNAPSHOT, value).apply()

    /**
     * 个股所属板块的落盘缓存（[BoardTagCache] 编解码）。
     * 归属变化极慢，长缓存 + 按需拉，免得每 30 分钟的持仓分析都去打 F10。
     */
    var boardTags: String?
        get() = prefs.getString(KEY_BOARD_TAGS, null)
        set(value) = prefs.edit().putString(KEY_BOARD_TAGS, value).apply()

    /**
     * 各板块龙头名单的人工修正（指定 / 剔除），按东财板块码索引。
     * 自动排名只是候选，最终以你在「板块龙头」里的勾选为准。
     */
    var sectorLeaderOverrides: Map<String, LeaderOverrides>
        get() =
            runCatching {
                LeaderOverrides.decodeAll(JSONObject(prefs.getString(KEY_SECTOR_LEADER_OVERRIDES, "{}") ?: "{}"))
            }.getOrDefault(emptyMap())
        set(value) =
            prefs
                .edit()
                .putString(KEY_SECTOR_LEADER_OVERRIDES, LeaderOverrides.encodeAll(value).toString())
                .apply()

    /**
     * 板块归类的人工修正（[BoardGroupBook] 编解码）：归类内个股增删、持仓挂靠、自定义归类。
     * 只存动过的部分，没动过的自动归类跟着东财刷新走。
     */
    var boardGroups: BoardGroupBook
        get() =
            runCatching {
                BoardGroupBook.decode(prefs.getString(KEY_BOARD_GROUPS, "{}") ?: "{}")
            }.getOrDefault(BoardGroupBook())
        set(value) = prefs.edit().putString(KEY_BOARD_GROUPS, BoardGroupBook.encode(value).toString()).apply()

    /**
     * 最近一次选股结果（[ScreenResultJson] 编码，含生成时间）。盘中不自动扫描后，
     * 重启就靠它恢复选股页，不再空白。
     */
    var lastScreenResult: String?
        get() = prefs.getString(KEY_SCREEN_RESULT, null)
        set(value) = prefs.edit().putString(KEY_SCREEN_RESULT, value).apply()

    /** 选股后的连续观察池，保存观察阶段、交易日计数和最近一次淘汰原因。 */
    var observationPool: MutableList<ObservationCandidate>
        get() = ObservationPoolJson.decode(prefs.getString(KEY_OBSERVATION_POOL, null))
        set(value) = prefs.edit().putString(KEY_OBSERVATION_POOL, ObservationPoolJson.encode(value)).apply()

    var fundFlowHistory: Map<String, List<FundFlow>>
        get() = FundFlowHistoryJson.decode(prefs.getString(KEY_FUND_FLOW_HISTORY, null))
        set(value) = prefs.edit().putString(KEY_FUND_FLOW_HISTORY, FundFlowHistoryJson.encode(value)).apply()

    /** 观察池每日证据快照；只保留每只股票最近 30 个交易日。 */
    var observationSnapshots: List<ObservationSnapshot>
        get() = ObservationSnapshotJson.decode(prefs.getString(KEY_OBSERVATION_SNAPSHOTS, null))
        set(value) = prefs.edit().putString(KEY_OBSERVATION_SNAPSHOTS, ObservationSnapshotJson.encode(value)).apply()

    /** 由观察池阶段变化生成的纸上交易记录。 */
    var paperTrades: List<PaperTradeRecord>
        get() = PaperTradeJson.decode(prefs.getString(KEY_PAPER_TRADES, null))
        set(value) = prefs.edit().putString(KEY_PAPER_TRADES, PaperTradeJson.encode(value)).apply()

    var lastSummaryText: String?
        get() = prefs.getString(KEY_SUMMARY_TEXT, null)
        set(value) = prefs.edit().putString(KEY_SUMMARY_TEXT, value).apply()

    /** 最近一次成功生成盘面总结的实际时间；不要用 16/18/21 点槽位倒推。 */
    var lastSummaryGeneratedAt: String?
        get() = prefs.getString(KEY_SUMMARY_GENERATED_AT, null)
        set(value) = prefs.edit().putString(KEY_SUMMARY_GENERATED_AT, value).apply()

    var lastSummaryDate: String?
        get() = prefs.getString(KEY_SUMMARY_DATE, null)
        set(value) = prefs.edit().putString(KEY_SUMMARY_DATE, value).apply()

    var lastSummaryNight: String?
        get() = prefs.getString(KEY_SUMMARY_NIGHT, null)
        set(value) = prefs.edit().putString(KEY_SUMMARY_NIGHT, value).apply()

    var lastSummaryAfternoon: String?
        get() = prefs.getString(KEY_SUMMARY_AFTERNOON, null)
        set(value) = prefs.edit().putString(KEY_SUMMARY_AFTERNOON, value).apply()

    /** 最近一次有效今日要闻及其刷新时间；失败时保留，不清空用户还能看到的内容。 */
    var lastNewsJson: String?
        get() = prefs.getString(KEY_LAST_NEWS, null)
        set(value) = prefs.edit().putString(KEY_LAST_NEWS, value).apply()

    var lastNewsUpdatedAtMs: Long
        get() = prefs.getLong(KEY_LAST_NEWS_UPDATED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_NEWS_UPDATED_AT, value).apply()

    var lastNewsAttemptAtMs: Long
        get() = prefs.getLong(KEY_LAST_NEWS_ATTEMPT_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_NEWS_ATTEMPT_AT, value).apply()

    /**
     * 概念板块榜的落盘缓存。东财不可达时用新浪兜底，两个源都拿不到时也能立刻显示上一次成功的结果，
     * 不用等下一轮 30 分钟刷新。
     */
    var conceptBoards: ConceptBoards?
        get() = parseConceptBoards(prefs.getString(KEY_CONCEPT_BOARDS, null))
        set(value) =
            prefs.edit().putString(KEY_CONCEPT_BOARDS, value?.let { toConceptBoardsJson(it).toString() }).apply()

    /**
     * 自选股的日线基线，结构
     * {"date":"2026-09-23","avg":{"sh600522":1234567.0},"window":{"sh600522":[low,high,baseClose]}}。
     * avg 是 20 日均量（单位股），window 是近 60 日价格区间（供「位置」判定）。
     * 两者每天只变一次，落盘后重启不用重拉日线。
     */
    var volumeBaseline: JSONObject
        get() =
            runCatching { JSONObject(prefs.getString(KEY_VOLUME_BASELINE, "{}") ?: "{}") }
                .getOrDefault(JSONObject())
        set(value) = prefs.edit().putString(KEY_VOLUME_BASELINE, value.toString()).apply()

    /**
     * 最近一份行情快照（[QuoteCache] 编解码）。进程被系统回收后「市场」页靠它垫上最近
     * 交易日的收盘数据，否则盘后/周末开屏只能空等网络（或等下个交易日）。
     */
    var lastQuotes: String?
        get() = prefs.getString(KEY_LAST_QUOTES, null)
        set(value) = prefs.edit().putString(KEY_LAST_QUOTES, value).apply()

    /** 技术面、基本面和港美股报告缓存，供 AI 综合分析跨页面复用。 */
    var analysisReports: String
        get() = prefs.getString(KEY_ANALYSIS_REPORTS, "{}") ?: "{}"
        set(value) = prefs.edit().putString(KEY_ANALYSIS_REPORTS, value).apply()

    /** 最近一次 AI 综合分析报告正文（落盘，重启后仍可查看）。 */
    var lastAiReportText: String?
        get() = prefs.getString(KEY_AI_REPORT_TEXT, null)
        set(value) = prefs.edit().putString(KEY_AI_REPORT_TEXT, value).apply()

    var lastAiReportTitle: String?
        get() = prefs.getString(KEY_AI_REPORT_TITLE, null)
        set(value) = prefs.edit().putString(KEY_AI_REPORT_TITLE, value).apply()

    /** 最近一次 AI 综合分析成功生成的实际时间。 */
    var lastAiReportAt: String?
        get() = prefs.getString(KEY_AI_REPORT_AT, null)
        set(value) = prefs.edit().putString(KEY_AI_REPORT_AT, value).apply()

    /** 交易流水与未平批次的完整账本；旧版本为空时由当前持仓生成 legacy 批次。 */
    var tradeBook: TradeBook
        get() = TradeLotJson.decode(prefs.getString(KEY_TRADE_BOOK, null))
        set(value) = prefs.edit().putString(KEY_TRADE_BOOK, TradeLotJson.encode(value)).apply()

    // ── 监控开关 ──
    var monitorEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITOR_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MONITOR_ENABLED, value).apply()

    /** 开机 / 应用升级后是否自动恢复监控，默认关闭，需要时在设置里手动开启。 */
    var bootAutoStart: Boolean
        get() = prefs.getBoolean(KEY_BOOT_AUTOSTART, false)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_AUTOSTART, value).apply()

    /** 开机恢复监控失败时保留一次诊断信息，避免异常被静默吞掉。 */
    var lastStartupError: String?
        get() = prefs.getString(KEY_LAST_STARTUP_ERROR, null)
        set(value) = prefs.edit().putString(KEY_LAST_STARTUP_ERROR, value).apply()

    var alertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ALERTS_ENABLED, value).apply()

    var dropAlertPct: Double
        get() = prefs.getFloat(KEY_DROP_PCT, 5.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_DROP_PCT, value.toFloat()).apply()

    /**
     * 盘中买卖点 / 做 T 提醒：15 秒一轮，价格触发支撑压力或做 T 阈值时震动 + 通知。
     * 和「行情提醒」分开是因为它更频繁、更打扰，需要能单独静音。
     */
    var intradaySignalEnabled: Boolean
        get() = prefs.getBoolean(KEY_INTRADAY_SIGNAL, true)
        set(value) = prefs.edit().putBoolean(KEY_INTRADAY_SIGNAL, value).apply()

    /** 涨跌停全市场扫描（数据量较大，可在移动网络下关闭）。 */
    var limitScanEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIMIT_SCAN, true)
        set(value) = prefs.edit().putBoolean(KEY_LIMIT_SCAN, value).apply()

    /**
     * 往「持仓」里加监控股票时，顺手补进自选股列表，这样「市场」页也能看到它的实时价。
     * 默认开启；补进去的条目顶栏显示为关，不会挤占常驻通知。
     */
    var syncPositionToWatchlist: Boolean
        get() = prefs.getBoolean(KEY_SYNC_POSITION_WATCHLIST, true)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_POSITION_WATCHLIST, value).apply()

    /** 是否已经提示过忽略电池优化。 */
    var batteryPromptDismissed: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPT, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_PROMPT, value).apply()

    // ── JSON 解析 ──

    private fun parseWatchlist(raw: String?): MutableList<WatchItem> {
        val list = mutableListOf<WatchItem>()
        if (raw.isNullOrBlank()) return defaultWatchlist()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val code = item.optString("code")
                if (code.isBlank()) continue
                list.add(
                    WatchItem(
                        code = code,
                        name = item.optString("name", code),
                        showInBar = item.optBoolean("show_in_bar", false),
                    ),
                )
            }
        }
        return if (list.isEmpty()) defaultWatchlist() else list
    }

    private fun toWatchlistJson(list: List<WatchItem>): JSONArray {
        val array = JSONArray()
        list.forEach { item ->
            array.put(
                JSONObject()
                    .put("code", item.code)
                    .put("name", item.name)
                    .put("show_in_bar", item.showInBar),
            )
        }
        return array
    }

    private fun parseConceptBoards(raw: String?): ConceptBoards? {
        if (raw.isNullOrBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val top = parseConceptItems(json.optJSONArray("top"))
        val bottom = parseConceptItems(json.optJSONArray("bottom"))
        if (top.isEmpty() && bottom.isEmpty()) return null
        return ConceptBoards(
            top = top,
            bottom = bottom,
            source =
                runCatching { ConceptSource.valueOf(json.optString("source")) }
                    .getOrDefault(ConceptSource.EASTMONEY),
            stamp = json.optString("stamp"),
        )
    }

    private fun parseConceptItems(array: JSONArray?): List<ConceptItem> {
        if (array == null) return emptyList()
        val items = mutableListOf<ConceptItem>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isBlank()) continue
            items.add(
                ConceptItem(
                    name = name,
                    code = item.optString("code"),
                    change = item.optDouble("change", 0.0),
                    volume = item.optDouble("volume", 0.0),
                    lead = item.optString("lead"),
                    leadPct = item.optString("lead_pct"),
                ),
            )
        }
        return items
    }

    private fun toConceptBoardsJson(boards: ConceptBoards): JSONObject =
        JSONObject()
            .put("source", boards.source.name)
            .put("stamp", boards.stamp)
            .put("top", toConceptItemsJson(boards.top))
            .put("bottom", toConceptItemsJson(boards.bottom))

    private fun toConceptItemsJson(items: List<ConceptItem>): JSONArray {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("name", item.name)
                    .put("code", item.code)
                    .put("change", item.change)
                    .put("volume", item.volume)
                    .put("lead", item.lead)
                    .put("lead_pct", item.leadPct),
            )
        }
        return array
    }

    private fun parsePortfolio(raw: String?): Portfolio {
        val fallback = Portfolio()
        if (raw.isNullOrBlank()) return fallback
        return runCatching {
            val json = JSONObject(raw)
            val positions = mutableListOf<Position>()
            val array = json.optJSONArray("positions") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val code = item.optString("code")
                if (code.isBlank()) continue
                positions.add(
                    Position(
                        code = code,
                        name = item.optString("name", code),
                        quantity = item.optDouble("quantity", 0.0),
                        cost = item.optDouble("cost", 0.0),
                    ),
                )
            }
            // 新档带 available_capital_delta，此时忽略旧版钉死字段，免得被误当成新的校正。
            val hasDelta = json.has("available_capital_delta")
            Portfolio(
                totalCapital = json.optDouble("total_capital", 0.0),
                availableCapital = if (hasDelta) 0.0 else json.optDouble("available_capital", 0.0),
                initialEntryPct = json.optDouble("initial_entry_pct", 5.0),
                maxPositionPct = json.optDouble("max_position_pct", 20.0),
                positions = positions,
                availableCapitalOverride =
                    if (hasDelta) {
                        null
                    } else if (json.has("available_capital_override")) {
                        json.optDouble("available_capital_override").takeIf { it.isFinite() && it >= 0 }
                    } else {
                        null
                    },
                availableCapitalDelta = if (hasDelta) json.optDouble("available_capital_delta", 0.0) else 0.0,
            )
        }.getOrDefault(fallback)
    }

    private fun toPortfolioJson(portfolio: Portfolio): JSONObject {
        val positions = JSONArray()
        portfolio.positions.forEach { position ->
            positions.put(
                JSONObject()
                    .put("code", position.code)
                    .put("name", position.name)
                    .put("quantity", position.quantity)
                    .put("cost", position.cost),
            )
        }
        return JSONObject()
            .put("total_capital", portfolio.totalCapital)
            // available_capital 只为兼容 macOS 版显示当前现金；取值以 available_capital_delta 为准。
            .put("available_capital", portfolio.resolvedAvailableCapital())
            .put("available_capital_delta", portfolio.availableCapitalDelta)
            .put("initial_entry_pct", portfolio.initialEntryPct)
            .put("max_position_pct", portfolio.maxPositionPct)
            .put("positions", positions)
    }

    private fun defaultWatchlist(): MutableList<WatchItem> =
        mutableListOf(
            WatchItem("sh000001", "上证", true),
            WatchItem("sz399006", "创业", false),
            WatchItem("sh000688", "科创", false),
            WatchItem("sh600522", "中天", true),
            WatchItem("sh600884", "杉杉", true),
        )

    companion object {
        private const val KEY_WATCHLIST = "watchlist"
        private const val KEY_PORTFOLIO = "portfolio"
        private const val KEY_INDICATOR_STATE = "indicator_state"
        private const val KEY_PORTFOLIO_SLOT = "portfolio_slot"
        private const val KEY_PORTFOLIO_REPORT = "portfolio_report"
        private const val KEY_DAILY_REVIEW_TEXT = "daily_review_text"
        private const val KEY_DAILY_REVIEW_DATE = "daily_review_date"
        private const val KEY_STRONG_CONTINUATION_EVENTS = "strong_continuation_events"
        private const val KEY_PORTFOLIO_SNAPSHOT = "portfolio_snapshot"
        private const val KEY_BOARD_TAGS = "board_tags"
        private const val KEY_SECTOR_LEADER_OVERRIDES = "sector_leader_overrides"
        private const val KEY_BOARD_GROUPS = "board_groups"
        private const val KEY_SCREEN_RESULT = "screen_result"
        private const val KEY_OBSERVATION_POOL = "observation_pool"
        private const val KEY_FUND_FLOW_HISTORY = "fund_flow_history"
        private const val KEY_OBSERVATION_SNAPSHOTS = "observation_snapshots"
        private const val KEY_PAPER_TRADES = "paper_trades"
        private const val KEY_SUMMARY_TEXT = "summary_text"
        private const val KEY_SUMMARY_GENERATED_AT = "summary_generated_at"
        private const val KEY_SUMMARY_DATE = "summary_date"
        private const val KEY_SUMMARY_NIGHT = "summary_night"
        private const val KEY_SUMMARY_AFTERNOON = "summary_afternoon"
        private const val KEY_LAST_NEWS = "last_news"
        private const val KEY_LAST_NEWS_UPDATED_AT = "last_news_updated_at"
        private const val KEY_LAST_NEWS_ATTEMPT_AT = "last_news_attempt_at"
        private const val KEY_MONITOR_ENABLED = "monitor_enabled"
        private const val KEY_BOOT_AUTOSTART = "boot_autostart"
        private const val KEY_LAST_STARTUP_ERROR = "last_startup_error"
        private const val KEY_ALERTS_ENABLED = "alerts_enabled"
        private const val KEY_DROP_PCT = "drop_alert_pct"
        private const val KEY_INTRADAY_SIGNAL = "intraday_signal_enabled"
        private const val KEY_LIMIT_SCAN = "limit_scan_enabled"
        private const val KEY_SYNC_POSITION_WATCHLIST = "sync_position_to_watchlist"
        private const val KEY_BATTERY_PROMPT = "battery_prompt_dismissed"
        private const val KEY_TURNOVER_SAMPLES = "turnover_samples"
        private const val KEY_CONCEPT_BOARDS = "concept_boards"
        private const val KEY_VOLUME_BASELINE = "volume_baseline"
        private const val KEY_LAST_QUOTES = "last_quotes"
        private const val KEY_ANALYSIS_REPORTS = "analysis_reports"
        private const val KEY_AI_REPORT_TEXT = "ai_report_text"
        private const val KEY_AI_REPORT_TITLE = "ai_report_title"
        private const val KEY_AI_REPORT_AT = "ai_report_at"
        private const val KEY_TRADE_BOOK = "trade_book"
    }
}
