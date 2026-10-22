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
            .mapNotNull { announcement -> positiveEventKind(announcement.title, announcement.content)?.let { announcement to it } }
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
        val kind = positiveEventKind(announcement.title, announcement.content) ?: return null
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
     * 公告标题里的**真利好**类型；不是利好返回 null。
     *
     * 只收股价级别的硬催化：重组并购落地获批、业绩爆发、中标/重大合同、大股东/实控人增持、
     * **占比够大的**回购注销。分红/送转/股权激励、象征性回购与泛泛的增持计划这些**不收**——
     * 不算真利好。「实施完成 / 注册批复 / 同意注册」都不单用，必须带重组/收购语境：
     * 它们是证监会注册环节的通用用语，纯定增（如杉杉股份 2026-10-22 的 40 亿向特定对象
     * 发行获批）拿「同意注册」会撞成重组落地，那不是重组、更不是利好。利空词一票否决——
     * 「业绩预增」的标题里混着「减持」也别当利好看。
     *
     * 回购注销看**占股本/市值的比例**（[BUYBACK_CANCEL_MIN_PCT]）不看绝对金额——
     * 同样 5000 万，对 50 亿和 5000 亿的公司是两回事。占比一般在正文里披露，
     * [content] 解析不出占比时宁缺勿滥、不算利好。
     */
    fun positiveEventKind(title: String, content: String? = null): String? {
        if (NEGATIVE_MARKERS.any { title.contains(it) }) return null
        val majorHolder = title.contains("控股股东") || title.contains("大股东") || title.contains("实际控制人")
        return when {
            title.contains("过户完成") || title.contains("交割完成") ||
                ((title.contains("重组") || title.contains("收购") || title.contains("购买资产")) &&
                    (title.contains("实施完成") || title.contains("注册批复") || title.contains("同意注册"))) ->
                "重组落地"
            title.contains("业绩预增") || title.contains("大幅增长") ||
                title.contains("扭亏为盈") || title.contains("业绩预盈") -> "业绩预增"
            title.contains("中标") -> "中标"
            title.contains("重大合同") -> "重大合同"
            // 大股东/实控人真金白银表态；董监高/员工的小额「增持计划」不算硬催化。
            majorHolder && title.contains("增持") -> "大股东增持"
            // 高占比回购注销：永久缩股本，算真利好；占比太小的注销摊到股价上没水花，不算。
            isBuybackCancellation(title) -> {
                val pct = ratioPct(title, content)
                if (pct != null && pct >= BUYBACK_CANCEL_MIN_PCT) "回购注销" else null
            }
            else -> null
        }
    }

    /** 回购注销的占比下限（占总股本/总市值的百分点）：低于这个数摊到股价上没水花。 */
    const val BUYBACK_CANCEL_MIN_PCT = 0.5

    /**
     * 「回购注销」候选：标题里既有回购又有注销。股权激励的限制性股票注销是例行公事，
     * 不是真利好，先排掉。
     */
    private fun isBuybackCancellation(title: String): Boolean {
        val incentive = title.contains("股权激励") || title.contains("限制性股票") || title.contains("激励计划")
        return !incentive && title.contains("回购") && title.contains("注销")
    }

    /** 回购注销的占比只在正文里，这类公告要单独拉详情（FundamentalsClient 的选择性拉取用）。 */
    fun wantsBuybackContent(title: String): Boolean = isBuybackCancellation(title)

    /** 「占总股本/市值……X%」的披露口径；只认股本与市值做分母，净资产/总资产的比例不是这个口径。 */
    private val RATIO = Regex(
        "占[^。；%]{0,24}(?:总股本|流通股本|股本|股份总数|总市值|流通市值|市值)[^。；%]{0,12}?([0-9]+(?:\\.[0-9]+)?)\\s*%"
    )

    /** 「注销/减少/取消……N 股」的注销股数；表格型披露没有「占 X%」句式时用它兜底。 */
    private val CANCEL_SHARES = Regex("(?:注销|减少|取消)[^。；]{0,16}?([0-9][0-9,]{2,})\\s*股")

    /** 「总股本/股份总数……N」的变动前总股本；同样给表格型披露兜底用。 */
    private val TOTAL_SHARES = Regex("(?:总股本|股份总数)[^。；]{0,16}?([0-9][0-9,]{5,})")

    /**
     * 回购注销占股本比例（百分点）；解析不出返回 null。
     *
     * 两条路：① 正文直接写「占公司总股本的 X%」（按市价折算它与「占市值」同量级，共用门槛）；
     * ② 表格型披露只给股数（如杉杉股份 2026-10-22 注销 4,773 万股那篇），用
     * 「注销股数 ÷ 变动前总股本」自己算。两条路都看不出来就当没有——宁缺勿滥。
     */
    fun ratioPct(title: String, content: String? = null): Double? {
        val text = "$title ${content.orEmpty()}"
        RATIO.findAll(text)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .maxOrNull()
            ?.let { return it }
        val cancel = CANCEL_SHARES.findAll(text)
            .mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }
            .maxOrNull() ?: return null
        val total = TOTAL_SHARES.findAll(text)
            .mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }
            .maxOrNull() ?: return null
        // 股数解析错位（注销数 ≥ 总股本）就宁缺勿滥，别算出个离谱占比。
        if (total <= 0 || cancel <= 0 || cancel >= total) return null
        return cancel / total * 100
    }

    /**
     * 公告标题里的**真利空**类型；不是利空返回 null。与 [positiveEventKind] 同一份
     * 「真」的口径：只收股价级别的硬利空，例行公事和小动作不收。
     *
     * 收：立案调查、行政处罚、退市风险、大股东/实控人减持、终止重组收购、
     * 业绩预亏/大幅下滑、商誉/计提减值、股份司法冻结。
     * 不收：问询函/警示函（流程噪声）、质押（常见操作）、董监高小额减持计划（与小额
     * 增持计划同一标准）、诉讼仲裁进展（金额不明不构成硬利空）。
     */
    fun negativeEventKind(title: String): String? = when {
        title.contains("立案") -> "立案调查"
        title.contains("处罚") -> "行政处罚"
        title.contains("退市") -> "退市风险"
        (title.contains("控股股东") || title.contains("大股东") || title.contains("实际控制人") ||
            title.contains("5%以上") || title.contains("百分之五")) && title.contains("减持") -> "大股东减持"
        (title.contains("终止") || title.contains("失败")) &&
            (title.contains("重组") || title.contains("收购") || title.contains("购买资产")) -> "终止重组"
        title.contains("业绩预亏") || title.contains("预亏") || title.contains("首亏") ||
            title.contains("大幅下滑") || title.contains("业绩预减") -> "业绩预亏/下滑"
        title.contains("商誉减值") || title.contains("减值准备") || title.contains("计提减值") -> "减值计提"
        title.contains("司法冻结") || title.contains("轮候冻结") || title.contains("股份被冻结") -> "股份冻结"
        else -> null
    }

    private val NEGATIVE_MARKERS = listOf(
        "减持", "处罚", "立案", "诉讼", "仲裁", "终止", "失败", "问询", "警示",
        "退市", "冻结", "质押", "违规", "亏损", "下滑", "未中标",
    )

    /** 公告全名常带「公司:」前缀和「的公告」后缀；关键短语在结尾（「…过户完成」），截断保尾。持仓复盘的利好清单也用这份口径。 */
    fun trimTitle(title: String): String {
        val body = title.substringAfter(":").substringAfter("：").trim()
        val trimmed = body.removePrefix("关于").removeSuffix("的公告").removeSuffix("公告")
        return if (trimmed.length > 24) "…" + trimmed.takeLast(24) else trimmed
    }
}
