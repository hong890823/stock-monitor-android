package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar

/**
 * 「利好落地 → 缩量回踩」的事件后蓄势识别。
 *
 * 突发封板经常不是凭空来的：重组过户完成、业绩预增这类**利好落地**之后，
 * 走「放量上涨 → 缩量回踩不破」的整理形态，往往是启动前的最后姿态
 * （如华天科技 2026-10-20 重组标的过户完成、21 日缩量回踩、22 日封板）。
 *
 * 这是概率性线索不是承诺：给的是「事件后蓄势，放量突破时值得盯」，
 * 不给买入指令。公告标题负责「发生了什么」，量价负责「市场认不认」——
 * 两者都在才成形态，只放量没事件（不知原因的异动）或只有事件没量（没人理）都不算。
 */
object EventCoil {

    /** 事件只回看最近这么多根日线：过户完成这类利好 1~3 天消化，拖久了不是同一件事。 */
    const val EVENT_LOOKBACK_BARS = 10

    /** 事件日相对 20 日均量的放量倍数；达不到就要求当日涨幅够大（[EVENT_MOVE_PCT]）。 */
    const val EVENT_VOLUME_RATIO = 1.15

    /** 放量不够时的替代条件：事件日涨这么多（百分点）也算市场认了。 */
    const val EVENT_MOVE_PCT = 2.0

    /** 回踩日量能不超过 20 日均量的这个倍数——缩量才是蓄势，放量滞跌是出货。 */
    const val COIL_VOLUME_RATIO = 1.05

    /** 回踩守住事件日收盘的这个比例以上；跌破就算利好被证伪，不再提示。 */
    const val HOLD_RATIO = 0.97

    /**
     * 一条事件后蓄势线索。[note] 进个股报告行，[brief] 进持仓页「今日要点」的高亮行
     * （股票名由渲染方前置，这里只描述形态）。
     */
    data class Signal(
        val eventDate: String,
        val eventTitle: String,
        val kind: String,
        val note: String,
        val brief: String,
    )

    /**
     * 找到「利好落地 + 量价蓄势」就给一条观察注记；找不到返回 null。
     * [bars] 需含今日实时段（见 ObservationEntryPolicy.withLiveBar）才能反映最新回踩。
     */
    fun evaluate(announcements: List<Announcement>, bars: List<DayBar>, today: String): Signal? {
        val sorted = bars.sortedBy { it.date }
        if (sorted.size < 3) return null
        val avgVolume = sorted.dropLast(1).takeLast(20).map { it.volume }.average().takeIf { it > 0 } ?: return null
        val event = announcements
            .asSequence()
            .filter { it.date.isNotBlank() && it.date <= today }
            .mapNotNull { announcement -> positiveEventKind(announcement.title)?.let { announcement to it } }
            .filter { (announcement, _) -> sorted.any { it.date >= announcement.date } }
            .maxByOrNull { it.first.date } ?: return null
        val (announcement, _) = event
        val eventIndex = sorted.indexOfFirst { it.date >= announcement.date }
        if (eventIndex < 0 || sorted.size - 1 - eventIndex > EVENT_LOOKBACK_BARS) return null
        val eventBar = sorted[eventIndex]
        val eventChangePct = if (eventIndex > 0) {
            (eventBar.close / sorted[eventIndex - 1].close - 1) * 100
        } else {
            0.0
        }
        val noticed = eventBar.volume >= avgVolume * EVENT_VOLUME_RATIO || eventChangePct >= EVENT_MOVE_PCT
        if (!noticed) return null
        val holdLine = eventBar.close * HOLD_RATIO
        // 事件日之后的中间日必须缩量蓄势；最新一根只要还守得住回踩线（启动日放量上涨也算守住）。
        val middles = sorted.subList(eventIndex + 1, sorted.size - 1)
        if (middles.any { it.volume > avgVolume * COIL_VOLUME_RATIO || it.close < holdLine }) return null
        val latest = sorted.last()
        if (latest.close < holdLine) return null
        val kind = positiveEventKind(announcement.title) ?: return null
        return Signal(
            eventDate = eventBar.date,
            eventTitle = announcement.title,
            kind = kind,
            note = "事件后蓄势：${eventBar.date}「${trimTitle(announcement.title)}」" +
                "（$kind）后量缩价稳、回踩不破 ${"%.2f".format(eventBar.close)}——" +
                "利好落地的整理形态，放量突破时值得盯；属观察线索，不改趋势判断。",
            brief = "放量突破时值得盯（${eventBar.date} $kind 后缩量回踩）",
        )
    }

    /**
     * 公告标题里的利好落地类型；不是利好返回 null。
     * 利空词一票否决——「业绩预增」的标题里混着「减持」也别当利好看。
     */
    fun positiveEventKind(title: String): String? {
        if (NEGATIVE_MARKERS.any { title.contains(it) }) return null
        return when {
            title.contains("过户完成") || title.contains("交割完成") ||
                title.contains("实施完成") || title.contains("注册批复") ||
                title.contains("同意注册") -> "重组落地"
            title.contains("业绩预增") || title.contains("大幅增长") || title.contains("扭亏为盈") -> "业绩预增"
            title.contains("中标") -> "中标"
            title.contains("增持") -> "股东增持"
            title.contains("回购") -> "回购"
            else -> null
        }
    }

    private val NEGATIVE_MARKERS = listOf(
        "减持", "处罚", "立案", "诉讼", "仲裁", "终止", "失败", "问询", "警示",
        "退市", "冻结", "质押", "违规", "亏损", "下滑",
    )

    /** 公告全名常带「公司:」前缀和「的公告」后缀；关键短语在结尾（「…过户完成」），截断保尾。 */
    private fun trimTitle(title: String): String {
        val body = title.substringAfter(":").substringAfter("：").trim()
        val trimmed = body.removePrefix("关于").removeSuffix("的公告").removeSuffix("公告")
        return if (trimmed.length > 24) "…" + trimmed.takeLast(24) else trimmed
    }
}
