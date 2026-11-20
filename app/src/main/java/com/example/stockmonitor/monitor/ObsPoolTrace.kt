package com.example.stockmonitor.monitor

import android.util.Log
import com.example.stockmonitor.core.LimitDataSource
import com.example.stockmonitor.core.LimitStock
import com.example.stockmonitor.core.ObservationCandidate

/**
 * 观察池入池、淘汰与首板提醒门控的结构化追踪日志。
 *
 * 统一使用 tag `ObsPoolTrace`，可用 `adb logcat -s ObsPoolTrace` 单独提取，
 * 用于复盘“某只涨停股为什么没有进入观察池 / 没有发出提醒”。
 */
internal object ObsPoolTrace {
    private const val TAG = "ObsPoolTrace"
    private const val MAX_CODES = 200
    private const val MAX_REASON_CHARS = 60
    private val loggedDecisions = mutableSetOf<String>()
    private val launchBlocked = mutableSetOf<String>()

    fun limitScan(
        day: String,
        source: LimitDataSource,
        up: List<LimitStock>,
        eligible: List<LimitStock>,
    ) {
        val eligibleCodes = eligible.map { it.code }.toSet()
        val continuation = up.count { it.code in eligibleCodes }
        line(
            "event=limitScan day=$day source=$source up=${up.size} " +
                "continuation=$continuation leader=${up.size - continuation} codes=" +
                up.take(MAX_CODES).joinToString(",") { "${it.code}:${it.limitStreak}" }
        )
    }

    fun poolAdd(
        stage: String,
        day: String,
        before: List<ObservationCandidate>,
        after: List<ObservationCandidate>,
    ) {
        val beforeByKey = before.associateBy { it.code to it.strategy }
        after.forEach { candidate ->
            val key = candidate.code to candidate.strategy
            val previous = beforeByKey[key]
            if (previous == null) {
                line(
                    "event=poolAdd stage=$stage day=$day code=${candidate.code} name=${candidate.name} " +
                        "strategy=${candidate.strategy} streak=${candidate.limitStreak} source=${candidate.source}"
                )
            } else if (previous.limitStreak != candidate.limitStreak) {
                line(
                    "event=poolStreak stage=$stage day=$day code=${candidate.code} " +
                        "streak=${previous.limitStreak}->${candidate.limitStreak}"
                )
            }
        }
    }

    fun poolRefresh(
        stage: String,
        day: String,
        before: List<ObservationCandidate>,
        after: List<ObservationCandidate>,
    ) {
        val beforeByKey = before.associateBy { it.code to it.strategy }
        after.forEach { candidate ->
            val previous = beforeByKey[candidate.code to candidate.strategy] ?: return@forEach
            if (previous.stage != candidate.stage) {
                line(
                    "event=poolStage stage=$stage day=$day code=${candidate.code} name=${candidate.name} " +
                        "strategy=${candidate.strategy} ${previous.stage}->${candidate.stage} " +
                        "age=${previous.observedDays}->${candidate.observedDays} " +
                        "streak=${candidate.limitStreak} reason=${reason(candidate.reason)}"
                )
            } else if (previous.observedDays != candidate.observedDays) {
                line(
                    "event=poolAge stage=$stage day=$day code=${candidate.code} " +
                        "age=${previous.observedDays}->${candidate.observedDays} stage=${candidate.stage}"
                )
            }
        }
    }

    /** 首板提醒门控：每个交易日、每只股票、每种结论只记一条，避免 15 秒轮询刷屏。 */
    fun reclaimGate(day: String, code: String, name: String, eligible: Boolean, detail: String) {
        // 乖离等带数值的原因按类别去重，否则价格每跳一次就多一条日志。
        val key = "$day|$code|$eligible|${detail.substringBefore('=')}"
        if (!loggedDecisions.add(key)) return
        if (loggedDecisions.size > 4000) loggedDecisions.clear()
        line("event=reclaimGate day=$day code=$code name=$name eligible=$eligible detail=$detail")
    }

    fun reclaimAlert(day: String, code: String, price: Double) {
        line("event=reclaimAlert day=$day code=$code price=$price")
    }

    fun reclaimBudgetBlocked(day: String, code: String) {
        line("event=reclaimBudgetBlocked day=$day code=$code")
    }

    /** 启动突破观察：只在真正命中时记录，避免每 5 分钟打印扫描过程。 */
    fun launchAlert(day: String, code: String, price: Double, platform: Double) {
        line("event=launchAlert day=$day code=$code price=$price platform=$platform")
    }

    fun launchBudgetBlocked(day: String, code: String) {
        line("event=launchBudgetBlocked day=$day code=$code")
    }

    /** 启动突破观察因资金过滤被抑制；同股同原因每日只记一条。 */
    fun launchFundFlowBlocked(day: String, code: String, reason: String) {
        val key = "$day|$code|${reason.take(16)}"
        if (!launchBlocked.add(key)) return
        if (launchBlocked.size > 2000) launchBlocked.clear()
        line("event=launchFundFlowBlocked day=$day code=$code reason=${reason.take(40)}")
    }

    /** 通知投递失败（渠道被拦截 / 开关关闭）：预算与去重键已回滚，下一轮可重试。 */
    fun deliveryFailed(type: String, day: String, code: String) {
        line("event=deliveryFailed day=$day type=$type code=$code")
    }

    /** 通知因当日已发送而去重。 */
    fun deliveryDuplicate(type: String, day: String, code: String) {
        line("event=deliveryDuplicate day=$day type=$type code=$code")
    }

    private fun reason(text: String?): String =
        (text ?: "-").replace('\n', ' ').take(MAX_REASON_CHARS)

    private fun line(text: String) {
        Log.i(TAG, text)
    }
}
