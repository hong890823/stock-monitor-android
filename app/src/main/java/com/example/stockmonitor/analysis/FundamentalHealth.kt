package com.example.stockmonitor.analysis

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class FundamentalEvidence(val date: String, val reason: String, val financial: Boolean = false)

/**
 * 基本面健康分级：把 [FundamentalResult] 折成破位文案用得上的一句「倾向」。
 *
 * 程序判断得了的是**财务与公告可见面**——业绩变脸、现金流质量、商誉等资产干净度、
 * 立案处罚、筹码恶化；判断不了行业景气、订单、竞争格局这类「生意逻辑」。所以这里
 * 给出当前优先动作（亮红灯倾向反弹离场、未见恶化倾向等企稳后小仓处理），
 * 不把程序看不到的行业景气和订单硬判成确定结论。
 */
enum class HealthLevel(val label: String) {
    /** 评分偏差/较差、有 ALERT 风险项、或业绩变脸迹象。 */
    ALERT("亮红灯"),

    /** 有瑕疵但够不上恶化。 */
    NEUTRAL("中性观察"),

    /** 评分尚可且无重大风险项。 */
    SOUND("未见恶化"),
}

data class FundamentalHealth(
    val level: HealthLevel,
    val score: Int,
    val verdict: String,
    /** 触发该分级的依据，直接进提醒文案。 */
    val reasons: List<String>,
    val analyzedAt: String,
    val intradayEvidence: List<FundamentalEvidence> = emptyList(),
)

/** 基本面健康的缓存快照：fetchedAtMs 用来判断要不要按需补拉。 */
data class FundamentalHealthSnapshot(val health: FundamentalHealth, val fetchedAtMs: Long)

object FundamentalHealthPolicy {

    /** 业绩变脸阈值：最近两季营收、净利同比都低于这个百分比算双降。 */
    const val DOWNTURN_YOY_PCT = -20.0

    /** 评分到这个分且无 ALERT 风险，才敢说「未见恶化」。 */
    const val SOUND_MIN_SCORE = 70

    /** 评分低于这个分本身就构成恶化信号。 */
    const val WEAK_SCORE = 55

    /** 最多带进文案的触发项条数，多了刷屏。 */
    const val MAX_REASONS = 3

    /**
     * 健康度缓存有效期：基本面变化慢，十天半个月分析下来都差不多，有缓存就用缓存，
     * 不在每次提醒时反复拉接口。财报季报、业绩预告这类变化由亮红灯触发项兜着，
     * 真要立刻看新数据就在工具页手动跑一次（会直接刷新缓存）。
     */
    const val CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000

    /** 拉取失败后的重试间隔（只对没有缓存的代码生效），避免 15 秒轮询反复打接口。 */
    const val RETRY_AFTER_FAILURE_MS = 4L * 60 * 60 * 1000

    /** 要不要后台补拉：有新鲜缓存就用缓存；没缓存/已过期才拉，失败后按 [RETRY_AFTER_FAILURE_MS] 节流。 */
    fun needsFetch(snapshot: FundamentalHealthSnapshot?, lastAttemptMs: Long, nowMs: Long): Boolean {
        if (snapshot != null && nowMs - snapshot.fetchedAtMs < CACHE_TTL_MS) return false
        return nowMs - lastAttemptMs >= RETRY_AFTER_FAILURE_MS
    }

    fun grade(result: FundamentalResult, analyzedAt: String = ""): FundamentalHealth =
        grade(
            score = result.score,
            verdict = result.verdict,
            risks = result.risks,
            quarters = result.quarters,
            notes = result.notes,
            analyzedAt = analyzedAt,
        ).copy(intradayEvidence = buildList {
            result.risks.filter { it.level == RiskLevel.ALERT && it.evidenceDate != null }.forEach {
                add(FundamentalEvidence(it.evidenceDate!!, it.text, financial = !it.announcement))
            }
            if (result.quarters.takeLast(2).let { quarters -> quarters.size == 2 && quarters.all {
                (it.revenueYoy ?: 0.0) <= DOWNTURN_YOY_PCT && (it.netProfitYoy ?: 0.0) <= DOWNTURN_YOY_PCT
            } }) {
                result.latest?.date?.let { add(FundamentalEvidence(it, "最近两季营收及净利同比双降", financial = true)) }
            }
        })

    fun forIntraday(health: FundamentalHealth?, today: String): FundamentalHealth? {
        health ?: return null
        val now = parseDate(today)
        val current = health.intradayEvidence.filter { evidence ->
            val date = parseDate(evidence.date)
            if (date == null || now == null || date > now) return@filter false
            if (!evidence.financial) return@filter ChronoUnit.DAYS.between(date, now) in 0..180
            val deadline = when (date.monthValue to date.dayOfMonth) {
                12 to 31 -> LocalDate.of(date.year + 1, 4, 30)
                3 to 31 -> LocalDate.of(date.year, 8, 31)
                6 to 30 -> LocalDate.of(date.year, 10, 31)
                9 to 30 -> LocalDate.of(date.year + 1, 4, 30)
                else -> return@filter false
            }
            now <= deadline
        }
        return health.copy(
            level = if (current.isNotEmpty()) HealthLevel.ALERT else HealthLevel.NEUTRAL,
            reasons = current.map { it.reason }, intradayEvidence = current,
        )
    }

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    /** 拆开入参是为了单测不用构造整个 [FundamentalResult]。 */
    fun grade(
        score: Int,
        verdict: String,
        risks: List<RiskItem>,
        quarters: List<QuarterPoint>,
        notes: List<String> = emptyList(),
        analyzedAt: String = "",
    ): FundamentalHealth {
        val reasons = mutableListOf<String>()
        var red = false
        if (score < WEAK_SCORE) {
            red = true
            reasons.add("评分 $score（$verdict）")
        }
        val alerts = risks.filter { it.level == RiskLevel.ALERT }
        if (alerts.isNotEmpty()) {
            red = true
            alerts.forEach { reasons.add(it.text) }
        }
        val lastTwo = quarters.takeLast(2)
        val doubleDecline = { quarter: QuarterPoint ->
            quarter.revenueYoy != null && quarter.netProfitYoy != null &&
                quarter.revenueYoy <= DOWNTURN_YOY_PCT && quarter.netProfitYoy <= DOWNTURN_YOY_PCT
        }
        val downturn = lastTwo.size == 2 && lastTwo.all(doubleDecline)
        if (downturn) {
            red = true
            reasons.add("最近两季营收、净利同比双双下滑超 20%（业绩变脸迹象）")
        }
        // 最新一季双降但还没连续两季：够不上变脸，但也不好意思说「未见恶化」。
        val singleDownturn = !downturn && quarters.lastOrNull()?.let(doubleDecline) == true
        if (singleDownturn) reasons.add("最近一季营收、净利同比双降")
        // 周期股在盈利高点的低估值是陷阱；口径提示在场时，再好的评分也不说「未见恶化」。
        val cyclical = notes.any { it.contains("周期") }
        val level = when {
            red -> HealthLevel.ALERT
            singleDownturn || cyclical -> HealthLevel.NEUTRAL
            score >= SOUND_MIN_SCORE && alerts.isEmpty() -> HealthLevel.SOUND
            else -> HealthLevel.NEUTRAL
        }
        if (cyclical && reasons.none { it.contains("周期") }) reasons.add("存在周期口径提示")
        if (reasons.isEmpty()) reasons.add("评分 $score（$verdict）")
        return FundamentalHealth(
            level = level,
            score = score,
            verdict = verdict,
            reasons = reasons.take(MAX_REASONS),
            analyzedAt = analyzedAt,
        )
    }

    /**
     * 基本面倾向一句话。深套时直接给当前优先动作；浅亏时补在减仓建议后面。
     * 没有分析结果时也要交代一句——破位恰是最需要基本面判断的时刻，沉默会让人
     * 以为「没提示 = 没问题」。
     *
     * 结论里带 **评分与触发的具体风险项**——只写「基本面亮红灯」是标签，读者没法核对。
     * 「中性」那一档尤其不能写成「自行取舍」：那是不作为，要说清**程序为什么分不出来**
     * （行业景气与订单它看不到），用户才知道该拿什么来补。
     */
    fun tendency(health: FundamentalHealth?, deep: Boolean): String? {
        if (health == null) {
            return if (deep) {
                "基本面暂无数据：程序给不出倾向——工具页可先跑一次个股基本面；" +
                    "没数据前按技术面走，别把「没数据」当成中性。"
            } else {
                "基本面未分析（工具页可先跑一次个股基本面）。"
            }
        }
        val why = health.reasons.joinToString("；")
        val at = "评分 ${health.score}，$why"
        return when {
            deep && health.level == HealthLevel.ALERT ->
                "基本面亮红灯（$at）：当前优先等反弹到压力位附近分批离场；程序看不到行业景气与订单，只有你确认逻辑未变时才再评估。"
            deep && health.level == HealthLevel.SOUND ->
                "基本面未见恶化（$at）：当前优先等缩量止跌或站回支撑后小仓处理，破位处不接飞刀。"
            deep ->
                "基本面中性（$at）：程序看不到行业景气与订单，当前优先先不补仓；反抽站不回压力再减，重新企稳后再评估。"
            health.level == HealthLevel.ALERT ->
                "基本面亮红灯（$at）：减仓后不急接回。"
            health.level == HealthLevel.SOUND ->
                "基本面未见恶化（$at）：减仓控风险即可，企稳后可再接回。"
            else ->
                "基本面中性（$at）：不构成加仓或减仓的额外理由，按技术面走。"
        }
    }
}
