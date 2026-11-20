package com.example.stockmonitor.analysis

/**
 * 提醒投递前的统一门控：开关 → 行情新鲜度 → 当日去重 → 推送预算。
 *
 * 顺序固定，保证“同一事件重启后不重复、预算没被无效提醒吃掉”。
 * 纯函数，便于覆盖开关关闭 / 行情过期 / 预算不足三条路径。
 */
object AlertOrchestrator {
    enum class Decision {
        SEND,
        SKIP_DISABLED,
        SKIP_STALE,
        SKIP_DUPLICATE,
        SKIP_BUDGET,
    }

    data class Input(
        val alertsEnabled: Boolean,
        val intradayEnabled: Boolean = true,
        val quoteFresh: Boolean = true,
        val duplicate: Boolean = false,
        val budgetAvailable: Boolean = true,
    )

    fun decide(input: Input): Decision = when {
        !input.alertsEnabled || !input.intradayEnabled -> Decision.SKIP_DISABLED
        !input.quoteFresh -> Decision.SKIP_STALE
        input.duplicate -> Decision.SKIP_DUPLICATE
        !input.budgetAvailable -> Decision.SKIP_BUDGET
        else -> Decision.SEND
    }
}
