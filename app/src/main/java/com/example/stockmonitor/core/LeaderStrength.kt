package com.example.stockmonitor.core

/**
 * 龙头此刻的强度，决定做 T 要不要降级。
 *
 * 炸板不等于走弱：炸板后能**长时间稳在高位**（主板水上 8 个点以上），说明砸出来的筹码被买盘
 * 吃掉了、承接极强，这时候提示减机动仓很可能正好卖在回封前一秒。所以判定不看「封没封住」，
 * 看「是不是还贴在涨停附近的高位」。
 */
enum class LeaderStrength {
    /** 封板，或炸板后仍贴在高位线以上。 */
    STRONG,

    /** 走弱：没封过板，或炸板后已跌破高位线并确认。 */
    WEAK,
}

/**
 * 龙头强度的盘中状态机。
 *
 * **强**：当前封板；或今日曾封板（日内最高摸到过涨停）且现价仍在高位线以上
 * （[STRONG_GAP_PCT] 个点以内，主板即水上 8 个点）。
 *
 * **弱**：今日没封过板；或炸板后跌破高位线**并持续 [HOLD_MS]**。
 *
 * 时长门槛放在「转弱」那一侧：跌破高位线要持续 3 分钟才算真走弱，刚炸那一瞬的抖动不翻转。
 * 反过来若放在「转强」那一侧，炸板后会空出 3 分钟让你去卖——正好卖飞，正是要避免的事。
 * 封板转强不设门槛（封板本身就是确定信号）。
 *
 * 「今日曾封板」用 `Quote.high` 反推，不靠状态记忆：服务中途重启也不会把早上封过的板忘掉。
 */
class LeaderStrengthTracker {

    /** 每只龙头的转弱计时；其余判断都能从行情里读出来。 */
    private class State(var weakSinceMs: Long? = null, var date: String? = null)

    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun clear() = states.clear()

    @Synchronized
    fun retainCodes(codes: Set<String>) {
        states.keys.retainAll(codes)
    }

    @Synchronized
    fun strength(member: BoardMember, quote: Quote, nowMs: Long): LeaderStrength {
        val state = states.getOrPut(member.code) { State() }
        val today = quote.quoteDate
        if (today != null && state.date != today) {
            state.date = today
            state.weakSinceMs = null
        }
        val limitPct = SinaClient.limitPctFor(member.code, member.name)
        val change = quote.changePct
        if (!change.isFinite()) return LeaderStrength.WEAK

        // 日内最高价够不够到涨停，用来判「今日是否曾封板」。
        val highChange = if (quote.prevClose > 0 && quote.high.isFinite() && quote.high > 0) {
            (quote.high - quote.prevClose) / quote.prevClose * 100
        } else {
            Double.NaN
        }
        val touchedLimit = highChange.isFinite() && highChange >= limitPct - LIMIT_UP_TOLERANCE

        if (change >= limitPct - LIMIT_UP_TOLERANCE) {
            // 封板：立刻算强，也不用再计时。
            state.weakSinceMs = null
            return LeaderStrength.STRONG
        }
        if (touchedLimit && change >= limitPct - STRONG_GAP_PCT) {
            // 炸板但仍贴在高位线以上——承接还在，算强。
            state.weakSinceMs = null
            return LeaderStrength.STRONG
        }
        if (!touchedLimit) return LeaderStrength.WEAK

        // 曾封过板但已跌破高位线：持续 HOLD_MS 才确认走弱，抖动期先不翻转。
        val weakSince = state.weakSinceMs
        if (weakSince == null) {
            state.weakSinceMs = nowMs
            return LeaderStrength.STRONG
        }
        return if (nowMs - weakSince >= HOLD_MS) LeaderStrength.WEAK else LeaderStrength.STRONG
    }

    /** 仍算龙头强势的个股名（封板，或炸板后守住高位线）。 */
    @Synchronized
    fun strongNames(leaders: List<BoardMember>, quotes: Map<String, Quote>, nowMs: Long): List<String> =
        leaders.filter { member ->
            quotes[member.quoteCode]?.let { strength(member, it, nowMs) == LeaderStrength.STRONG } == true
        }.map { it.name }

    companion object {
        /** 触板容差，与全市场涨跌停扫描一致。 */
        const val LIMIT_UP_TOLERANCE = 0.15

        /**
         * 「贴板高位」离涨停的最大距离（百分点）。主板 2% 即水上 8 个点；
         * 创业板 / 科创板同样取 2 个点（水上 18 个点），判的是「离涨停有多近」，不是「涨了多少」。
         */
        const val STRONG_GAP_PCT = 2.0

        /** 从「跌破高位线」到确认走弱的时长，避免刚炸那一瞬的抖动。 */
        const val HOLD_MS = 3 * 60 * 1000L

        /**
         * 此刻真封着板的个股名。**纯计算、不碰计时状态**——界面展示用，
         * 判定走 [strongNames]（那边才需要 3 分钟转弱确认的计时）。
         */
        fun sealedNames(leaders: List<BoardMember>, quotes: Map<String, Quote>): List<String> =
            leaders.filter { member ->
                val quote = quotes[member.quoteCode] ?: return@filter false
                quote.changePct.isFinite() &&
                    quote.changePct >= SinaClient.limitPctFor(member.code, member.name) - LIMIT_UP_TOLERANCE
            }.map { it.name }

        /**
         * 此刻守住高位线的个股名（含封板）。界面把 `heldHighNames − sealedNames` 标 ⚡ = 炸板守住高位。
         * 同样是纯计算：3 分钟缓冲是决策侧抖动门槛，卡片只回答「现在还在不在高位线」。
         */
        fun heldHighNames(leaders: List<BoardMember>, quotes: Map<String, Quote>): List<String> =
            leaders.filter { member ->
                val quote = quotes[member.quoteCode] ?: return@filter false
                if (!quote.changePct.isFinite()) return@filter false
                val limitPct = SinaClient.limitPctFor(member.code, member.name)
                val highChange = if (quote.prevClose > 0 && quote.high.isFinite() && quote.high > 0) {
                    (quote.high - quote.prevClose) / quote.prevClose * 100
                } else {
                    Double.NaN
                }
                highChange.isFinite() && highChange >= limitPct - LIMIT_UP_TOLERANCE &&
                    quote.changePct >= limitPct - STRONG_GAP_PCT
            }.map { it.name }
    }
}
