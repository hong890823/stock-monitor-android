package com.example.stockmonitor.analysis

/**
 * 个股所属板块标签。来自东财 F10 的「所属板块」（`ssbk`）。
 *
 * 那份列表里混着**行业**（食品饮料 / 白酒Ⅱ）、**地域**（贵州板块）、
 * **风格与规模**（消费风格 / 大盘股 / 权重股 / 行业龙头）和**来源标签**（东方财富热股），
 * 所以要用 [SectorContext] 的筛选把真正的行业挑出来——不能把「贵州板块」当行业拿去比涨跌。
 */
data class BoardTag(
    val name: String,
    /** 东财板块码（如 `BK0438`）；解析不出来时为 null，此时只能用名称兜底匹配。 */
    val boardCode: String?,
    /** 层级序号，越小越粗（食品饮料 → 白酒Ⅱ → 白酒Ⅲ）。 */
    val rank: Int,
)

/**
 * 板块当日涨跌的兜底结果。
 *
 * [source] 不只写「从哪来」，失败时还要写清**为什么拿不到**——「行业板块行情未加载」
 * （30 分钟一轮的缓存还没热）和「行业板块里无同名项」（东财与新浪的叫法不一致）
 * 是两个完全不同的问题，混成一句「暂缺」就没法接着修。
 */
data class BoardChange(
    val pct: Double?,
    val source: String,
    val turnover: Double? = null,
    val turnoverRatio: Double? = null,
    val asOfMs: Long? = null,
)

/**
 * 个股的板块上下文：属于哪个行业、行业自身什么状态、今天涨跌多少。
 *
 * 有了它才能分清三种完全不同的情况：
 * 个股强 / 板块弱 = 独立行情，持续性存疑；个股弱 / 板块强 = 掉队，问题在自己；
 * 板块塌陷 = 别指望个股独善其身。没有归属就写「暂缺」，不猜。
 */
data class SectorContext(
    /** 主行业（滤掉地域/风格后 rank 最小的那个）。 */
    val industry: BoardTag?,
    /** 前几个标签，报告里当上下文用。 */
    val tags: List<BoardTag> = emptyList(),
    /** 板块自身状态，用 [MarketRegime.evaluate] 判——与大盘同一套口径。 */
    val state: MarketState? = null,
    /** 板块当日涨跌（%）。 */
    val changePct: Double? = null,
    /** 归属来源，直接进报告（「东财F10」/「暂缺」）。 */
    val source: String = "暂缺",
    /**
     * 当日涨跌的来源。**单独标出来是为了诊断**——板块日线拉不到时会退回按名称匹配的
     * 行业板块行情，两条路都失败才写「暂缺」。用户把报告贴回来，一眼就知道哪条路通了。
     */
    val changeSource: String = "暂缺",
) {
    val isCollapse: Boolean get() = state?.isCollapse == true

    /**
     * 板块对个股解读的修饰语。有它才能分清三种完全不同的情况：
     * 个股强 / 板块弱 = 独立行情，持续性存疑；个股弱 / 板块强 = 掉队，问题在自己；
     * 板块塌陷 = 别指望个股独善其身。
     *
     * 归属或板块行情缺任一项就返回 null——**不猜**，报告里另写「板块归属暂缺」。
     */
    fun interpret(stockChangePct: Double?): String? {
        val name = industry?.name ?: return null
        val sectorChange = changePct
        val sectorRegime = state?.regime
        if (sectorRegime == MarketRegime.COLLAPSE) {
            return "板块「$name」塌陷，别指望个股独善其身"
        }
        if (sectorChange != null && stockChangePct != null && sectorChange.isFinite() && stockChangePct.isFinite()) {
            val gap = stockChangePct - sectorChange
            if (gap >= 2.0) return "个股比板块「$name」强 ${String.format("%.1f", gap)} 个百分点，属独立行情，持续性存疑"
            if (gap <= -2.0) return "个股比板块「$name」弱 ${String.format("%.1f", -gap)} 个百分点，掉队了，问题多在个股自己"
        }
        return when (sectorRegime) {
            MarketRegime.BULL -> "板块「$name」走强，有板块贝塔，持有理由更硬"
            MarketRegime.WEAK -> "板块「$name」转弱，反弹多是跟风"
            else -> null
        }
    }

    companion object {
        /** 归属查不到时的默认：报告里会写「板块归属暂缺」，不猜。 */
        val MISSING = SectorContext(industry = null)

        /**
         * 地域 / 风格 / 规模 / 通道标签，**不是行业**。
         *
         * F10 的 `ssbk` 混着这些：贵州板块（地域）、消费风格（风格）、大盘股/权重股（规模）、
         * 行业龙头（属性）、东方财富热股（来源）。把它们当行业去比涨跌会得出完全错的结论——
         * 「贵州板块跌了」不代表你这只股的行业在跌。
         */
        private val NON_INDUSTRY_SUFFIX = listOf("板块", "风格", "股")
        private val NON_INDUSTRY_KEYWORD = listOf(
            "龙头", "热股", "融资融券", "MSCI", "标普", "富时", "沪股通", "深股通", "AH",
        )

        /** 是否是真正的行业标签。 */
        fun isIndustry(name: String): Boolean {
            if (name.isBlank()) return false
            if (NON_INDUSTRY_SUFFIX.any { name.endsWith(it) }) return false
            if (NON_INDUSTRY_KEYWORD.any { name.contains(it, ignoreCase = true) }) return false
            return true
        }

        /**
         * 从 F10 的原始标签里挑出主行业（滤掉非行业后 rank 最小的那个，也就是最粗的那层行业），
         * 并把板块状态装上。[boardBars] 缺失时只给归属和当日涨跌，不判板块趋势。
         *
         * [changeSource] 说明当日涨跌是从哪来的——板块日线（`boardBars`）还是调用方给的兜底值，
         * 两条都拿不到才写「暂缺」。报告里会标出来，方便诊断。
         */
        fun of(
            tags: List<BoardTag>,
            boardBars: List<com.example.stockmonitor.core.DayBar> = emptyList(),
            boardChangePct: Double? = null,
            source: String = "东财F10",
            changeSource: String? = null,
        ): SectorContext {
            if (tags.isEmpty()) return MISSING
            val industry = tags.filter { isIndustry(it.name) }.minByOrNull { it.rank }
            val state = if (boardBars.size >= MarketRegime.MIN_BARS) {
                MarketRegime.evaluate(boardBars, boardChangePct)
            } else {
                null
            }
            val change = boardChangePct ?: state?.changePct
            return SectorContext(
                industry = industry,
                tags = tags.take(3),
                state = state,
                changePct = change,
                source = source,
                // 调用方说了来源就用调用方的（它能说清「未加载」还是「无同名项」）；
                // 没说才按有没有值兜一个。
                changeSource = changeSource ?: if (change == null) "暂缺" else "板块日线",
            )
        }
    }
}
