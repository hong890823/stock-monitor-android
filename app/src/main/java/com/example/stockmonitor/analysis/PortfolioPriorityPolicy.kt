package com.example.stockmonitor.analysis

data class PriorityInput(
    val code: String,
    val name: String,
    val quantity: Double,
    val price: Double,
    val support: Double,
    val ma20: Double,
    val quoteDate: String = "",
    val evidenceDate: String = "",
    val riskConfirmed: Boolean = false,
    val exitTriggered: Boolean = false,
    val highRisk: Boolean = false,
    val fundamentalAlert: Boolean = false,
    val fundamentalReason: String = "",
    val buyApproved: Boolean = false,
)

enum class PriorityState(val order: Int, val label: String) {
    EXIT(0, "退出评估"), REDUCE(1, "减仓评估"), WATCH(2, "风险待确认"),
    BUY(4, "买入候选"), HOLD(5, "持有观察"),
}

data class PortfolioPriorityDecision(
    val input: PriorityInput,
    val state: PriorityState,
    val fresh: Boolean,
    val severity: Double,
    val reason: String,
) {
    val exposure: Double get() = (input.price * input.quantity).takeIf { it.isFinite() && it > 0 } ?: 0.0
    val order: Int get() = if (input.fundamentalAlert && state.order > 2) 3 else state.order
    val actionConfirmed: Boolean get() = state == PriorityState.EXIT || state == PriorityState.REDUCE
}

object PortfolioPriorityPolicy {
    const val VERSION = "portfolio-priority-v1"

    fun canApproveAddition(input: PriorityInput, asOf: String): Boolean {
        val decision = evaluate(input.copy(buyApproved = false), asOf)
        return decision.order > PriorityState.WATCH.order
    }

    fun evaluate(input: PriorityInput, asOf: String): PortfolioPriorityDecision {
        val pricesValid = listOf(input.price, input.support, input.ma20).all { it.isFinite() && it > 0 }
        val fresh = pricesValid && input.quoteDate == asOf && input.evidenceDate == asOf && asOf.isNotBlank()
        val broken = pricesValid && input.price < input.support * 0.995
        val weak = pricesValid && input.price < input.ma20 * 0.995
        val state = when {
            !fresh -> PriorityState.WATCH
            input.riskConfirmed && input.exitTriggered && (broken || input.highRisk) -> PriorityState.EXIT
            input.riskConfirmed && (broken || input.highRisk) -> PriorityState.REDUCE
            broken || weak || input.highRisk -> PriorityState.WATCH
            input.buyApproved && !input.fundamentalAlert -> PriorityState.BUY
            else -> PriorityState.HOLD
        }
        val severity = when {
            !pricesValid -> 0.0
            broken -> 2.0 + (1 - input.price / input.support).coerceIn(0.0, 1.0)
            input.highRisk -> 2.0
            weak -> 1.0 + (1 - input.price / input.ma20).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        val reason = when (state) {
            PriorityState.EXIT -> "结构失效且风险已确认，核对退出依据与可卖数量；不因深套延后评估"
            PriorityState.REDUCE -> "风险已确认，评估减少风险敞口；实际卖出仍需核验可卖数量与交易限制"
            PriorityState.WATCH -> when {
                !fresh -> "行情或技术数据缺失/日期不一致，先刷新核实，不据此生成买卖动作"
                broken -> "跌破支撑待确认，暂不加仓，观察能否收回；不等于立即卖出"
                input.highRisk -> "高位结构风险待确认，暂不加仓，不等于立即卖出"
                else -> "趋势转弱，暂不加仓，观察能否重新站稳；跌破均线不等于触发卖出"
            }
            PriorityState.BUY -> "入场与资金检查通过，仅列候选；执行前重新核对价格、仓位和交易条件"
            PriorityState.HOLD -> "未触发处置条件，继续持有观察，不强行操作"
        }
        return PortfolioPriorityDecision(input, state, fresh, severity, reason)
    }

    fun rank(inputs: List<PriorityInput>, asOf: String): List<PortfolioPriorityDecision> = inputs
        .filter { it.quantity.isFinite() && it.quantity > 0 }
        .map { evaluate(it, asOf) }
        .sortedWith(compareBy<PortfolioPriorityDecision> { it.order }
            .thenByDescending { it.severity }.thenByDescending { it.fresh }
            .thenByDescending { it.exposure }.thenBy { it.input.code })

    fun summary(inputs: List<PriorityInput>, asOf: String): List<String> {
        val ranked = rank(inputs, asOf)
        val confirmed = ranked.firstOrNull { it.actionConfirmed }
        val watch = ranked.firstOrNull { it.state == PriorityState.WATCH }
        val reviews = ranked.filter { it.input.fundamentalAlert }
        val unresolved = confirmed != null || watch != null || reviews.isNotEmpty()
        val candidate = ranked.firstOrNull { it.state == PriorityState.BUY }
        return buildList {
            add(if (confirmed != null) "当前优先事项：${confirmed.input.name}｜${confirmed.state.label}｜${confirmed.reason}"
                else "当前优先事项：暂无已确认的买卖动作" +
                    (watch?.let { "；优先观察${it.input.name}｜${it.reason}" } ?: ""))
            add("风险核实：" + if (reviews.isEmpty()) "暂无已识别的基本面红灯；未识别不代表无风险" else
                reviews.joinToString("；") { "${it.input.name}：${it.input.fundamentalReason.ifBlank { "具体原因未提供" }}" } +
                    "；先别加仓，查看最新财报或公告，不是让你马上卖出")
            add("买入机会：" + when {
                unresolved -> "先处理未解决风险，暂不新增"
                candidate != null -> "${candidate.input.name}｜${candidate.reason}"
                else -> "暂无通过入场与资金检查的候选"
            })
            add("排序口径：$VERSION｜风险类别→严重程度→数据确认→持仓市值降序→代码；行情/技术日期不一致不生成交易动作")
        }
    }
}
