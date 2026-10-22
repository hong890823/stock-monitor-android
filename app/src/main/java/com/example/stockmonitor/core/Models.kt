package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.FiveOneThreeHit
import com.example.stockmonitor.analysis.BoxRangeAssessment
import com.example.stockmonitor.analysis.BreakoutRiskDecision
import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.HighPositionDecision
import com.example.stockmonitor.analysis.PriceContext
import com.example.stockmonitor.analysis.TrendAssessment

/** 实时行情，字段与 stock_menubar.get_stock_data 一致。 */
data class Quote(
    val code: String,
    val name: String,
    val price: Double,
    val prevClose: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val turnover: Double,
    /** 行情接口标注的交易日期；为空时不参与盘中新鲜度判断。 */
    val quoteDate: String? = null,
) {
    val changePct: Double
        get() = if (prevClose != 0.0) (price - prevClose) / prevClose * 100 else 0.0
}

/**
 * 活跃股榜单的一行：代码、名称 + 当日实时快照。
 * 快照用于把当日拼成一根 K 线（新浪日线接口盘中不含当日），也顺带提供昨收。
 */
data class ActiveStock(
    val code: String,
    val name: String,
    val prevClose: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val price: Double,
    val volume: Double,
)

/** 日线，字段与新浪日线接口一致。 */
data class DayBar(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

/** 自选股 / 菜单栏显示项。 */
data class WatchItem(
    val code: String,
    val name: String,
    var showInBar: Boolean,
)

/** 持仓项。 */
data class Position(
    val code: String,
    val name: String,
    val quantity: Double,
    val cost: Double,
)

enum class HoldingSyncStatus {
    MATCHED,
    NOT_FOUND,
    AMBIGUOUS,
}

data class HoldingSyncResult(
    val status: HoldingSyncStatus,
    val input: String? = null,
    val candidates: List<Position> = emptyList(),
)

fun syncHoldingInput(positions: List<Position>, keyword: String): HoldingSyncResult {
    val query = keyword.trim()
    if (query.isEmpty()) return HoldingSyncResult(HoldingSyncStatus.NOT_FOUND)

    val normalizedQuery = normalizeStockCode(query)
    val exactCodeMatches = positions.filter { normalizeStockCode(it.code) == normalizedQuery }
    val exactNameMatches = positions.filter { it.name.equals(query, ignoreCase = true) }
    val exactMatches = (exactCodeMatches + exactNameMatches).distinct()
    if (exactMatches.size == 1) return matched(exactMatches.first())
    if (exactMatches.size > 1) return HoldingSyncResult(HoldingSyncStatus.AMBIGUOUS, candidates = exactMatches)

    val partialMatches = positions.filter {
        normalizeStockCode(it.code).contains(normalizedQuery) || it.name.contains(query, ignoreCase = true)
    }.distinct()
    return when (partialMatches.size) {
        1 -> matched(partialMatches.first())
        0 -> HoldingSyncResult(HoldingSyncStatus.NOT_FOUND)
        else -> HoldingSyncResult(HoldingSyncStatus.AMBIGUOUS, candidates = partialMatches)
    }
}

private fun matched(position: Position) = HoldingSyncResult(
    status = HoldingSyncStatus.MATCHED,
    input = "${formatHoldingNumber(position.quantity)},${formatHoldingNumber(position.cost)}",
    candidates = listOf(position),
)

private fun normalizeStockCode(value: String): String {
    val normalized = value.trim().lowercase()
    return when {
        normalized.length > 2 && normalized.startsWith("sh") -> normalized.removePrefix("sh")
        normalized.length > 2 && normalized.startsWith("sz") -> normalized.removePrefix("sz")
        normalized.length > 2 && normalized.startsWith("bj") -> normalized.removePrefix("bj")
        else -> normalized
    }
}

private fun formatHoldingNumber(value: Double): String =
    if (value.isFinite() && value == value.toLong().toDouble()) value.toLong().toString()
    else value.toString()

/** 持仓配置，字段与 portfolio.json 一致。 */
data class Portfolio(
    /** 本金，只在「设置资金」里手动改，不会被自动写。 */
    val totalCapital: Double = 0.0,
    /**
     * 旧版钉死的可用现金绝对值，只作读旧档用。
     * 新版折算进 [availableCapitalDelta]，见 [Portfolio.materializeCapitalCorrection]。
     */
    val availableCapital: Double = 0.0,
    val initialEntryPct: Double = 5.0,
    val maxPositionPct: Double = 20.0,
    val positions: MutableList<Position> = mutableListOf(),
    /** 旧版手动校正绝对值，同 [availableCapital]，只作读旧档用。 */
    val availableCapitalOverride: Double? = null,
    /**
     * 可用现金的持久修正值：`可用现金 = 总资金 − Σ(股数×成本) + availableCapitalDelta`。
     *
     * 在「设置资金」里输入可用现金时，按「输入值 − 当时的自动值」折算后存下来；
     * 之后持仓变化仍走自动公式，修正值保持不变，直到下次重新输入。留 0 表示纯自动。
     */
    val availableCapitalDelta: Double = 0.0,
)

/** [Portfolio.cashPosition] 的结果。 */
data class CashPosition(
    /** 当前生效的可用现金。 */
    val value: Double,
    /** 不含修正值的自动值 `总资金 − Σ(股数×成本)`；算不出时为 null。 */
    val autoValue: Double?,
    /** 持久修正金额；非 0 表示用户校准过。 */
    val correction: Double,
    /** 自动口径算不出来时的中文原因，能算时为 null。 */
    val blockedReason: String?,
) {
    val isCorrected: Boolean get() = correction != 0.0
}

/**
 * 可用现金的唯一口径：`总资金 − Σ(股数×成本) + 修正值`。
 *
 * 总资金是本金，固定不动；持仓变了自动跟着变。手动输入只产生一个持久修正值，
 * 用来在自动公式偏离真实账户时重新校准一次（卖出盈利后成本口径就会偏）。
 *
 * 旧档存的是钉死的绝对值（[Portfolio.availableCapitalOverride] / [Portfolio.availableCapital]），
 * 这里就地折算成修正值，所以升级瞬间显示不变，之后仍会随持仓浮动。
 */
fun Portfolio.cashPosition(): CashPosition {
    val holdings = positions.filter { it.quantity.isFinite() && it.quantity > 0 }
    val missingCost = holdings.count { !it.cost.isFinite() || it.cost <= 0 }
    val occupied = holdings.sumOf { it.quantity * it.cost }

    val blockedReason =
        when {
            !totalCapital.isFinite() || totalCapital <= 0 -> "总资金未设置，算不出可用现金"
            missingCost > 0 -> "有 $missingCost 只持仓未填成本，算不出可用现金"
            !occupied.isFinite() -> "持仓成本合计无效，算不出可用现金"
            else -> null
        }
    val auto = if (blockedReason == null) (totalCapital - occupied).coerceAtLeast(0.0) else null

    val pinned =
        availableCapitalOverride?.takeIf { it.isFinite() && it >= 0 }
            ?: availableCapital.takeIf { it.isFinite() && it > 0 }
    val correction =
        when {
            availableCapitalDelta.isFinite() && availableCapitalDelta != 0.0 -> availableCapitalDelta
            pinned != null && auto != null -> pinned - auto
            else -> 0.0
        }

    val value =
        when {
            auto != null -> (auto + correction).coerceAtLeast(0.0)
            pinned != null -> pinned.coerceAtLeast(0.0)
            else -> correction.coerceAtLeast(0.0)
        }
    return CashPosition(value = value, autoValue = auto, correction = correction, blockedReason = blockedReason)
}

fun Portfolio.resolvedAvailableCapital(): Double = cashPosition().value

fun Portfolio.availableCapitalIsManual(): Boolean = cashPosition().isCorrected

/** 不含修正值的自动可用现金；算不出时为 null。 */
fun Portfolio.autoAvailableCapital(): Double? = cashPosition().autoValue

/**
 * 把旧档的钉死绝对值折算成 [Portfolio.availableCapitalDelta]。
 *
 * 折算必须在读档时做：它用的是「当前」持仓成本，之后 [updatePosition] 的 copy
 * 才会带着固定的修正值浮动。算不出自动值时不动，等下次再折。
 */
fun Portfolio.materializeCapitalCorrection(): Portfolio {
    if (availableCapitalDelta.isFinite() && availableCapitalDelta != 0.0) return this
    val pinned =
        availableCapitalOverride?.takeIf { it.isFinite() && it >= 0 }
            ?: availableCapital.takeIf { it.isFinite() && it > 0 }
            ?: return this
    val resolved = cashPosition()
    if (resolved.autoValue == null) return this
    return copy(
        availableCapitalDelta = resolved.correction,
        availableCapital = 0.0,
        availableCapitalOverride = null,
    )
}

/**
 * 用「设置资金」里输入的可用现金重新校准：修正值 = 输入值 − 当时的自动值。
 * 传 null 表示清空校正，回到纯自动。
 */
fun Portfolio.withCapitalCorrection(value: Double?): Portfolio {
    if (value == null) {
        return copy(availableCapitalDelta = 0.0, availableCapital = 0.0, availableCapitalOverride = null)
    }
    val auto = cashPosition().autoValue
    return if (auto != null) {
        copy(availableCapitalDelta = value - auto, availableCapital = 0.0, availableCapitalOverride = null)
    } else {
        // 总资金未设置或有持仓没填成本，算不出自动值：先钉住这个数，
        // 等自动值可算时由 [materializeCapitalCorrection] 折算成修正值，免得之后被重复加算。
        copy(availableCapitalDelta = 0.0, availableCapital = 0.0, availableCapitalOverride = value)
    }
}

fun Portfolio.updatePosition(code: String, quantity: Double, cost: Double): Portfolio = copy(
    positions = positions.map { position ->
        if (position.code == code) position.copy(quantity = quantity, cost = cost) else position
    }.toMutableList(),
)

/** 行业板块。 */
data class SectorItem(
    val name: String,
    val change: Double,
    val count: Int,
    val volume: Double,
    val lead: String,
    val leadPct: String,
)

/** 东方财富板块资金流快照；净流入金额单位为元，净占比单位为百分比。 */
data class SectorFlow(
    val mainNetInflow: Double?,
    val mainNetRatio: Double?,
)

data class SectorData(
    val top: List<SectorItem>,
    val bottom: List<SectorItem>,
    val all: List<SectorItem>,
)

/** 概念板块。 */
data class ConceptItem(
    val name: String,
    val code: String,
    val change: Double,
    val volume: Double = 0.0,
    val lead: String = "",
    val leadPct: String = "",
)

/** 概念板块榜的来源，界面据此标注口径。 */
enum class ConceptSource(val label: String) {
    EASTMONEY("东方财富"),
    SINA("新浪"),
}

data class ConceptBoards(
    val top: List<ConceptItem>,
    val bottom: List<ConceptItem>,
    val source: ConceptSource = ConceptSource.EASTMONEY,
    /** 最近一次成功抓取的时间；来自落盘缓存时用于提示数据新旧。 */
    val stamp: String = "",
)

data class ConceptSummary(
    val sectors: List<ConceptItem>,
    val rising: Int,
    val falling: Int,
) {
    /**
     * 切成涨跌幅榜。东方财富不可达时用它兜底：新浪 newFLJK 的概念分类体系与东财不同，
     * 这里直接取整体涨跌幅前/后各 count 名，界面上会标注来源，避免误读成东财口径。
     */
    fun boards(count: Int, source: ConceptSource = ConceptSource.SINA): ConceptBoards {
        val sorted = sectors.sortedByDescending { it.change }
        return ConceptBoards(
            top = sorted.take(count),
            bottom = if (sorted.size > count) sorted.takeLast(count) else sorted,
            source = source,
        )
    }
}

/** 涨跌停个股。 */
data class LimitStock(
    val code: String,
    val name: String,
    val price: Double,
    val prev: Double,
    val pct: Double,
    val boards: List<String> = emptyList(),
)

data class LimitData(val up: List<LimitStock>, val down: List<LimitStock>)

/**
 * 板块成分股。
 *
 * 涨跌停榜（[LimitData]）不带行业，跟 F10 的板块归属对不上；要知道「同板块还有没有人封板」
 * 只能反过来拉板块成分。[code] 是东财的 6 位代码（`600522`），[quoteCode] 是查实时行情用的
 * 新浪式代码（`sh600522`），由 [quoteCodeOf] 推出来——东财的市场字段没在生产链路验证过，
 * 而 6 位码前缀判市场是确定的。
 */
data class BoardMember(val code: String, val name: String, val changePct: Double) {
    val quoteCode: String get() = quoteCodeOf(code)

    companion object {
        /** 6 位码 → 新浪式代码。沪市 6 开头，北交所 8/4/920 开头，其余归深市。 */
        fun quoteCodeOf(code: String): String = when {
            code.startsWith("6") -> "sh$code"
            code.startsWith("8") || code.startsWith("4") || code.startsWith("920") -> "bj$code"
            else -> "sz$code"
        }
    }
}

/**
 * 对龙头名单的人工修正：强制加进去 / 强制剔除。
 *
 * 自动排名（成交额前 [SectorLeaders.SIZE]）只是候选，最终以人的判断为准——
 * 「龙头」在实盘里往往是情绪核心，成交额排不进前 5 也可能是真龙头。
 * 记 code→name 是为了名单还没刷新时也能还原出可点可看的条目。
 */
data class LeaderOverrides(
    val pinned: Map<String, String> = emptyMap(),
    val excluded: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && excluded.isEmpty()

    /** 勾选 = 算龙头。去掉勾选时顺手记进 [excluded]，免得下轮自动排名又把它捞回来。 */
    fun withChecked(member: BoardMember, checked: Boolean): LeaderOverrides =
        if (checked) {
            LeaderOverrides(pinned + (member.code to member.name), excluded - member.code)
        } else {
            LeaderOverrides(pinned - member.code, excluded + member.code)
        }

    companion object {
        /** 落盘格式：`{"BK0438": {"pinned": {"600487": "亨通光电"}, "excluded": ["300570"]}}`。 */
        fun encodeAll(all: Map<String, LeaderOverrides>): org.json.JSONObject {
            val root = org.json.JSONObject()
            all.filterValues { !it.isEmpty }.forEach { (board, overrides) ->
                root.put(board, org.json.JSONObject().apply {
                    put("pinned", org.json.JSONObject().apply {
                        overrides.pinned.forEach { (code, name) -> put(code, name) }
                    })
                    put("excluded", org.json.JSONArray(overrides.excluded.toList()))
                })
            }
            return root
        }

        fun decodeAll(json: org.json.JSONObject): Map<String, LeaderOverrides> {
            val result = LinkedHashMap<String, LeaderOverrides>()
            json.keys().forEach { board ->
                val node = json.optJSONObject(board) ?: return@forEach
                val pinned = LinkedHashMap<String, String>()
                node.optJSONObject("pinned")?.let { it.keys().forEach { code -> pinned[code] = it.optString(code) } }
                val excluded = mutableSetOf<String>()
                node.optJSONArray("excluded")?.let { for (i in 0 until it.length()) excluded.add(it.optString(i)) }
                if (pinned.isNotEmpty() || excluded.isNotEmpty()) {
                    result[board] = LeaderOverrides(pinned, excluded)
                }
            }
            return result
        }
    }
}

/**
 * 一个板块里的「龙头」集合，做 T 降级只认它们。
 *
 * **只认龙头，跟风小票不算**：龙头封板才说明板块还有空间，小票涨停多是情绪跟风。
 * 候选按新浪的当日成交额排（与两市成交额同一数据源，口径已验证），
 * 取前 [SectorLeaders.SIZE] 名当资金龙头，再叠加 [LeaderOverrides] 的人工指定 / 剔除。
 * 不按流通市值排，是因为东财市值字段写这份代码时没能在线核实（接口当时拒连）。
 */
data class SectorLeaders(
    /** 最终生效的龙头，已叠加人工修正。 */
    val members: List<BoardMember>,
    val asOfMs: Long,
    val overrides: LeaderOverrides = LeaderOverrides(),
) {
    companion object {
        /** 自动排名取成交额前几名当龙头候选；可按需要调大调小。 */
        const val SIZE = 5

        /**
         * 成交额前 [SIZE] 名当候选，再套人工修正：剔除 [LeaderOverrides.excluded]，
         * 补上 [LeaderOverrides.pinned]（即使它没排进前 5）。
         */
        fun of(
            all: List<BoardMember>,
            turnovers: Map<String, Double>,
            overrides: LeaderOverrides = LeaderOverrides(),
            asOfMs: Long,
        ): SectorLeaders {
            val byCode = all.associateBy { it.code }
            val auto = all.sortedByDescending { turnovers[it.quoteCode] ?: 0.0 }
                .take(SIZE)
                .filter { it.code !in overrides.excluded }
            val pinned = overrides.pinned.map { (code, name) ->
                byCode[code] ?: BoardMember(code, name, 0.0)
            }
            return SectorLeaders(
                members = (auto + pinned).distinctBy { it.code },
                asOfMs = asOfMs,
                overrides = overrides,
            )
        }
    }
}

/** 指标选股命中项。 */
data class ScreenHit(
    val code: String,
    val name: String,
    val price: Double,
    val rsi: Double,
    val k: Double,
    val d: Double,
    val dif: Double,
    val relaxedScore: Int,
    val macdDetail: String,
    val rsiOk: Boolean,
    val kdjOk: Boolean,
    /** 当日涨跌幅；日线接口盘中不含当日，所以这个值来自实时行情，取不到时为 NaN。 */
    val changePct: Double = Double.NaN,
    /** 量比，口径同 VolumeRatio；取不到实时行情时为 null。 */
    val volumeRatio: Double? = null,
    /** 价格位置上下文（近 60 日区间位置 + 从低点反弹幅度），取不到日线时为 null；界面据此解释量价标签。 */
    val priceContext: PriceContext? = null,
    val relaxedTrendEligible: Boolean = false,
)

/** 趋势入场策略命中项：与原有指标策略、513形态分开展示。 */
data class TrendScreenHit(
    val code: String,
    val name: String,
    val price: Double,
    val changePct: Double,
    val volumeRatio: Double?,
    val entrySetup: EntrySetup,
)

data class FiveOneThreeStats(
    val totalStocks: Int = 0,
    val historyAvailable: Int = 0,
    val historicalFiveBullish: Int = 0,
    val recentFiveBullish: Int = 0,
    val waitingConfirmation: Int = 0,
    val confirmed: Int = 0,
)

data class ScreenResult(
    val strict: List<ScreenHit>,
    val relaxed: List<ScreenHit>,
    val fiveOneThree: List<FiveOneThreeHit> = emptyList(),
    val fiveOneThreeStats: FiveOneThreeStats = FiveOneThreeStats(),
    val trend: List<TrendScreenHit> = emptyList(),
)

enum class ObservationStage(val label: String) {
    OBSERVING("观察中"),
    NEAR_ENTRY("买点待确认"),
    BUY_CANDIDATE("买点已确认"),
    ELIMINATED("已淘汰"),
}

enum class ObservationStrategy(val label: String) {
    PRE_BREAKOUT("启动前潜力"),
    LEADER("强势龙头"),
}

data class ObservationCandidate(
    val code: String,
    val name: String,
    val addedDate: String,
    val lastEvaluatedDate: String?,
    val stage: ObservationStage,
    val score: Int,
    val weakDays: Int,
    val observedDays: Int = 0,
    val lastPrice: Double = 0.0,
    val stopPrice: Double? = null,
    val source: String = "选股",
    val reason: String = "",
    val strategy: ObservationStrategy = ObservationStrategy.PRE_BREAKOUT,
    val evidence: ObservationEvidence = ObservationEvidence(),
    val limitUpBlocked: Boolean = false,
    val limitUpShrinkAcceleration: Boolean = false,
    val entryConfirmationRounds: Int = 0,
    val entryConfirmedAtMs: Long = 0L,
    val entryConfirmationVolume: Double = 0.0,
    val breakoutRisk: BreakoutRiskDecision? = null,
)

data class ObservationSignal(
    val date: String,
    val price: Double,
    val hasScreenSignal: Boolean,
    val confirmedEntry: Boolean,
    val canBuy: Boolean,
    val trendWeak: Boolean,
    val stopPrice: Double?,
    val changePct: Double,
    val strategy: ObservationStrategy = ObservationStrategy.PRE_BREAKOUT,
    val setupScore: Int = 0,
    val strongScore: Int = 0,
    val recentLimitUpDays: Int = 0,
    val relativeStrengthPct: Double? = null,
    val volumeRatio: Double? = null,
    val riskFlag: Boolean = false,
    /** 当前处于涨停价附近，买点条件即使满足也要等开板后重新确认。 */
    val limitUpBlocked: Boolean = false,
    val limitUpShrinkAcceleration: Boolean = false,
    val signalReason: String = "",
    val evidence: ObservationEvidence = ObservationEvidence(),
    val evaluatedAtMs: Long = 0L,
    val quoteVolume: Double = 0.0,
    val trendAssessment: TrendAssessment? = null,
    val boxRange: BoxRangeAssessment? = null,
    val highPosition: HighPositionDecision? = null,
    val breakoutRisk: BreakoutRiskDecision? = null,
)

/**
 * 单个交易日的分时累计成交额曲线。
 * key 为当天分钟数（09:30 = 570），value 为截至该分钟的累计成交额（元）。
 */
data class TurnoverCurve(val date: String, val cumulative: Map<Int, Double>) {

    /** 取不超过 minuteOfDay 的最后一个采样；早于第一个采样点返回 null。 */
    fun at(minuteOfDay: Int): Double? =
        cumulative.keys.filter { it <= minuteOfDay }.maxOrNull()?.let { cumulative[it] }

    /** 收盘累计，取最后一个采样。只有 [isCompleteDay] 时它才是「全天成交额」。 */
    val fullDay: Double?
        get() = cumulative.keys.maxOrNull()?.let { cumulative[it] }

    /**
     * 是否走完一整个交易日。末值要当「全天成交额」用，前提是最后一条采样落在收盘（15:00）之后；
     * 盘中截断的日子最后一条还在 14:xx，末值只是半日累计，不能进历史、也不能当预估的分母基数。
     */
    val isCompleteDay: Boolean
        get() = (cumulative.keys.maxOrNull() ?: 0) >= CLOSE_MINUTE

    val isEmpty: Boolean get() = cumulative.isEmpty()

    companion object {
        /** 收盘分钟数（15:00）。 */
        const val CLOSE_MINUTE = 900
    }
}
