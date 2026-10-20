package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.BoardTag
import com.example.stockmonitor.analysis.NorthboundFlowPoint
import com.example.stockmonitor.analysis.NorthboundFlowSummary
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** 东方财富接口：概念板块涨跌榜、指数历史成交额。 */
class EastMoneyClient {
    private val referer = "https://data.eastmoney.com/"

    /** 概念板块榜，等价于 get_concept_data。 */
    fun conceptBoards(): ConceptBoards {
        val diff = fetchConceptDiff()
        val matched = mutableListOf<ConceptItem>()
        for (i in 0 until diff.length()) {
            val item = diff.optJSONObject(i) ?: continue
            val name = item.optString("f14")
            if (CONCEPT_KEYWORDS.none { name.contains(it) }) continue
            matched.add(
                ConceptItem(
                    name = name,
                    code = item.optString("f12"),
                    change = item.optDouble("f3", 0.0),
                )
            )
        }
        matched.sortByDescending { it.change }
        val count = SinaClient.SECTOR_COUNT
        return ConceptBoards(
            top = matched.take(count),
            bottom = if (matched.size > count) matched.takeLast(count) else matched,
        )
    }

    /** 概念板块全量（备用源，等价于 _fetch_concept_summary_backup）。 */
    fun conceptSummary(): ConceptSummary {
        val diff = fetchConceptDiff()
        if (diff.length() == 0) throw Http.HttpError("概念板块数据为空")
        val sectors = mutableListOf<ConceptItem>()
        var rising = 0
        var falling = 0
        for (i in 0 until diff.length()) {
            val item = diff.optJSONObject(i) ?: continue
            val change = item.optDouble("f3", 0.0)
            sectors.add(
                ConceptItem(
                    name = item.optString("f14"),
                    code = item.optString("f12"),
                    change = change,
                    leadPct = change.toString(),
                )
            )
            if (change > 0) rising++ else if (change < 0) falling++
        }
        sectors.sortByDescending { it.change }
        return ConceptSummary(sectors, rising, falling)
    }

    /** 指数近几个交易日成交额，等价于 _fetch_index_daily_amounts。 */
    fun indexDailyAmounts(secid: String): Map<String, Double> {
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=$secid" +
            "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57" +
            "&klt=101&fqt=1&end=20500101&lmt=5"
        val amounts = linkedMapOf<String, Double>()
        Http.retry(retries = 3, delayMs = 1_000, label = "指数K线") {
            val text = Http.getText(url, Charsets.UTF_8, "https://quote.eastmoney.com/", 8_000)
            val klines = JSONObject(text).optJSONObject("data")?.optJSONArray("klines")
            if (klines != null) {
                for (i in 0 until klines.length()) {
                    val parts = klines.optString(i).split(",")
                    if (parts.size >= 7) {
                        parts[6].toDoubleOrNull()?.let { amounts[parts[0]] = it }
                    }
                }
            }
        }
        return amounts
    }

    fun northboundDailyFlow(days: Int = 20): NorthboundFlowSummary? {
        val url = "https://push2his.eastmoney.com/api/qt/kamt.kline/get" +
            "?fields1=f1,f2,f3,f4&fields2=f51,f52,f53,f54,f55" +
            "&klt=101&fqt=1&end=20500101&lmt=${days.coerceIn(5, 60)}" +
            "&ut=b2884a393a59ad64002292a3e90d46a5"
        val text = runCatching {
            Http.getText(url, Charsets.UTF_8, "https://quote.eastmoney.com/", 10_000)
        }.getOrNull() ?: return null
        val points = parseNorthboundDailyFlow(text)
        // 该历史源出现过日期继续前进但净流入连续为 0 的冻结状态，不能把它当成真实中性数据。
        if (isNorthboundFeedDead(points)) return null
        return NorthboundFlowSummary.from(points, "东方财富北向资金历史接口（原始金额按万元换算为亿元）")
    }

    /**
     * 指数分时累计成交额曲线，用于「今日 vs 昨日同一时刻」对比与全天预估。
     * 东方财富 trends2 接口一次返回最近 ndays 个交易日的 1 分钟级数据。
     */
    fun indexTurnoverCurves(secid: String, days: Int = 2): List<TurnoverCurve> {
        val url = "https://push2his.eastmoney.com/api/qt/stock/trends2/get?secid=$secid" +
            "&fields1=f1,f2,f3,f4,f5,f6,f7,f8" +
            "&fields2=f51,f52,f53,f54,f55,f56,f57,f58&iscr=0&ndays=$days"
        val trends = Http.retry(retries = 2, delayMs = 1_000, label = "分时成交额") {
            val text = Http.getText(url, Charsets.UTF_8, "https://quote.eastmoney.com/", 12_000)
            JSONObject(text).optJSONObject("data")?.optJSONArray("trends")
                ?: throw Http.HttpError("分时成交额数据为空")
        }

        // trends 的 f57 是每分钟成交额，先按日期分桶再累加。
        val perDate = linkedMapOf<String, LinkedHashMap<Int, Double>>()
        for (i in 0 until trends.length()) {
            val parts = trends.optString(i).split(",")
            if (parts.size < 7) continue
            val stamp = parts[0]
            val hour = stamp.substringAfter(' ').substringBefore(':').toIntOrNull() ?: continue
            val minute = stamp.substringAfter(' ').substringAfter(':').toIntOrNull() ?: continue
            val amount = parts[6].toDoubleOrNull() ?: continue
            val bucket = perDate.getOrPut(stamp.substringBefore(' ')) { LinkedHashMap() }
            val key = hour * 60 + minute
            bucket[key] = (bucket[key] ?: 0.0) + amount
        }

        return perDate.map { (date, perMinute) ->
            val cumulative = LinkedHashMap<Int, Double>()
            var running = 0.0
            perMinute.toSortedMap().forEach { (minute, amount) ->
                running += amount
                cumulative[minute] = running
            }
            TurnoverCurve(date, cumulative)
        }.sortedBy { it.date }
    }

    /** 按分钟把多条曲线合并成「两市合计」曲线。 */
    fun mergeCurves(curves: List<TurnoverCurve>, date: String): TurnoverCurve? {
        val merged = LinkedHashMap<Int, Double>()
        curves.filter { it.date == date }.forEach { curve ->
            curve.cumulative.forEach { (minute, amount) ->
                merged[minute] = (merged[minute] ?: 0.0) + amount
            }
        }
        return if (merged.isEmpty()) null else TurnoverCurve(date, merged)
    }

    private fun fetchConceptDiff(): org.json.JSONArray = fetchBoardDiff("m:90+t:3")

    /**
     * 行业板块的当日涨跌（`{板块名 → 涨跌幅%}`）。
     *
     * **为什么不用新浪的行业板块兜底**：东财 F10 的个股归属用的是申万式行业名（「电力设备」），
     * 新浪 `newSinaHy.php` 是自己的 50 个行业（「电力行业」「电器行业」「发电设备」），
     * **两套分类体系互相包含都匹配不上**——实测特变电工就是这样拿不到涨跌的。
     * 用东财自己的行业板块列表则与 F10 **同名、必然匹配**，而且端点就是概念板块榜那条，
     * 可靠性和它完全一致。
     */
    fun industryBoardChanges(): Map<String, Double> {
        val diff = fetchBoardDiff("m:90+t:2")
        val changes = linkedMapOf<String, Double>()
        for (i in 0 until diff.length()) {
            val item = diff.optJSONObject(i) ?: continue
            val name = item.optString("f14")
            if (name.isBlank()) continue
            changes[name] = item.optDouble("f3", 0.0)
        }
        return changes
    }

    /**
     * 板块成分股（`fs=b:BK0438`），用来挑出「同板块龙头」。
     *
     * 涨跌停榜不带行业，跟 F10 的板块归属对不上；要知道同板块还有没有人封板，
     * 只能反过来拉板块成分。[boardSecid] 形如 `BK0438`，见 [toBoardSecid]。
     *
     * 只解析 `f12`/`f14`/`f3`（代码/名称/涨跌幅）——这几个字段是 `industryBoardChanges`
     * 在生产链路里一直用的。市值字段没在线核实过，所以龙头排序走新浪成交额，不走这里。
     */
    fun boardMembers(boardSecid: String): List<BoardMember> {
        val diff = fetchBoardDiff("b:$boardSecid")
        val members = ArrayList<BoardMember>(diff.length())
        for (i in 0 until diff.length()) {
            val item = diff.optJSONObject(i) ?: continue
            val code = item.optString("f12")
            val name = item.optString("f14")
            // 停牌等异常行 f3 会是 "-"，optDouble 会给出 0.0，用原始串判一下，别把它们当没涨。
            val raw = item.opt("f3")?.toString() ?: continue
            val change = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
            if (code.isBlank() || name.isBlank()) continue
            members.add(BoardMember(code, name, change))
        }
        return members
    }

    /** 行业板块主力资金净流入和净占比，按东方财富板块代码返回。 */
    fun industryBoardFlows(): Map<String, SectorFlow> = parseIndustryBoardFlows(
        fetchBoardDiffText(
            fs = "m:90+t:2",
            fields = "f2,f3,f5,f6,f12,f14,f62,f184",
        ),
    )

    /** 板块列表（`fs` 选行业 `m:90+t:2` 或概念 `m:90+t:3`），返回 `diff` 数组。 */
    private fun fetchBoardDiff(fs: String): org.json.JSONArray {
        return parseBoardDiff(fetchBoardDiffText(fs))
    }

    private fun fetchBoardDiffText(
        fs: String,
        fields: String = "f2,f3,f12,f14",
    ): String {
        val url = "https://push2.eastmoney.com/api/qt/clist/get?cb=jQuery&pn=1&pz=200&po=1&np=1" +
            "&fltt=2&invt=2&fid=f3&fs=$fs&fields=$fields" +
            "&ut=bd1d9ddb04089700cf9c27f6f7426281"
        return Http.getText(url, Charsets.UTF_8, referer, 15_000)
    }

    private fun parseBoardDiff(text: String): org.json.JSONArray {
        val start = text.indexOf('(')
        val end = text.lastIndexOf(')')
        val payload = if (start in 0 until end) text.substring(start + 1, end) else text
        return JSONObject(payload).optJSONObject("data")?.optJSONArray("diff")
            ?: org.json.JSONArray()
    }

    /**
     * 个股所属板块（东财 F10「核心题材 → 所属板块」，字段 `ssbk`）。
     *
     * 这是**唯一能查到「某只股属于哪个行业」**的入口——现有 `SinaClient.sectors()` /
     * `conceptBoards()` 只有板块自身的涨跌和一只领涨股，没有成分股列表
     * （`MonitorService.buildBoardMap` 因此只能标出「恰好领涨某概念」的那只）。
     *
     * 返回的是**原始标签**，行业 / 地域 / 风格的筛选交给 [com.example.stockmonitor.analysis.SectorContext.of]，
     * 接口层不猜哪些算行业。
     */
    fun stockBoards(stockCode: String): List<BoardTag> = parseSsbk(
        Http.retry(retries = 2, delayMs = 1_000, label = "所属板块", isValid = { it.isNotEmpty() }) {
            Http.getText(
                "https://emweb.securities.eastmoney.com/PC_HSF10/CoreConception/PageAjax?code=${emCode(stockCode)}",
                Charsets.UTF_8,
                "https://emweb.securities.eastmoney.com/",
                10_000,
            )
        },
    )

    /**
     * 板块日线，用来判**板块自身是否破位 / 塌陷**——板块只有当日涨跌幅是判不出趋势的。
     *
     * 端点形态与 [indexDailyAmounts] 完全一致（`push2his` 的 `kline/get`），只是 secid 换成板块。
     * 行格式 `date,open,close,high,low,volume,amount`，**close 在第 2 位**（与腾讯一致，不是第 3 位）。
     */
    fun boardKline(boardSecid: String, length: Int): List<DayBar> = parseBoardKline(
        Http.retry(retries = 2, delayMs = 1_000, label = "板块日线", isValid = { it.isNotEmpty() }) {
            Http.getText(
                "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=90.$boardSecid" +
                    "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57" +
                    "&klt=101&fqt=1&end=20500101&lmt=$length",
                Charsets.UTF_8,
                "https://quote.eastmoney.com/",
                10_000,
            )
        },
    )

    /** `sh600519` / `sz000001` → F10 要的 `SH600519` / `SZ000001`。 */
    private fun emCode(stockCode: String): String {
        val lower = stockCode.lowercase()
        return when {
            lower.startsWith("sh") || lower.startsWith("sz") || lower.startsWith("bj") ->
                lower.substring(0, 2).uppercase() + lower.substring(2)
            else -> stockCode.uppercase()
        }
    }

    companion object {
        fun parseNorthboundDailyFlow(text: String): List<NorthboundFlowPoint> {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val data = root.optJSONObject("data") ?: return emptyList()
            val aggregateKey = listOf("northbound", "north", "klines")
                .firstOrNull { data.optJSONArray(it) != null }
            val keys = if (aggregateKey != null) {
                listOf(aggregateKey)
            } else {
                listOf("hk2sh", "hk2sz")
            }
            val arrays = keys.mapNotNull { key -> data.optJSONArray(key) }
            if (arrays.isEmpty()) return emptyList()
            val byDate = linkedMapOf<String, Double>()
            arrays.forEach { rows ->
                for (index in 0 until rows.length()) {
                    parseNorthboundRow(rows.opt(index))?.let { (date, netBillion) ->
                        byDate[date] = (byDate[date] ?: 0.0) + netBillion
                    }
                }
            }
            return byDate.map { (date, netBillion) -> NorthboundFlowPoint(date, netBillion) }
        }

        fun isNorthboundFeedDead(points: List<NorthboundFlowPoint>): Boolean {
            val recent = points.takeLast(5)
            return recent.isEmpty() || recent.size >= 5 && recent.all { abs(it.netBillion) < 0.0001 }
        }

        /** 解析东方财富行业板块资金流字段；支持 JSON 和 JSONP。 */
        fun parseIndustryBoardFlows(text: String): Map<String, SectorFlow> {
            val diff = parseBoardDiffPayload(text)
            val flows = linkedMapOf<String, SectorFlow>()
            for (i in 0 until diff.length()) {
                val item = diff.optJSONObject(i) ?: continue
                val code = item.optString("f12").takeIf { it.isNotBlank() } ?: continue
                val netInflow = item.optString("f62").toDoubleOrNull()
                val netRatio = item.optString("f184").toDoubleOrNull()
                if (netInflow == null && netRatio == null) continue
                flows[code] = SectorFlow(netInflow, netRatio)
            }
            return flows
        }

        private fun parseBoardDiffPayload(text: String): org.json.JSONArray {
            val start = text.indexOf('(')
            val end = text.lastIndexOf(')')
            val payload = if (start in 0 until end) text.substring(start + 1, end) else text
            return runCatching {
                JSONObject(payload).optJSONObject("data")?.optJSONArray("diff")
                    ?: org.json.JSONArray()
            }.getOrDefault(org.json.JSONArray())
        }

        private fun parseNorthboundRow(value: Any?): NorthboundFlowPoint? {
            if (value is JSONObject) {
                val date = sequenceOf("date", "f51", "day")
                    .map { value.optString(it) }
                    .map { it.take(10) }
                    .firstOrNull { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                    ?: return null
                val net = sequenceOf("net", "net_flow", "netBillion", "f52", "value")
                    .mapNotNull { key -> value.optString(key).toDoubleOrNull() }
                    .firstOrNull()
                    ?: return null
                return NorthboundFlowPoint(date, net / 10_000.0)
            }
            if (value is String) {
                val parts = value.split(',')
                val date = parts.firstOrNull()?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                    ?: return null
                val net = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
                return NorthboundFlowPoint(date, net / 10_000.0)
            }
            return null
        }

        /**
         * `BOARD_CODE`（F10 返回的纯数字，如 `438`）→ 东财板块码（`BK0438`）。
         *
         * 这个拼法要在真机上用一只真实股票核对一次（见 README「持仓复盘的口径」）；
         * 拼不对就返回 null，调用方退回名称兜底匹配，不会拿错板块去比涨跌。
         */
        fun toBoardSecid(boardCode: String): String? {
            val digits = boardCode.filter { it.isDigit() }
            if (digits.isEmpty() || digits.length > 6) return null
            return "BK" + digits.padStart(4, '0')
        }

        /** 解析 F10 的 `ssbk` 数组。纯函数，便于单测。 */
        fun parseSsbk(text: String): List<BoardTag> {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val array = root.optJSONArray("ssbk") ?: return emptyList()
            val tags = mutableListOf<BoardTag>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                // 标签名是 BOARD_NAME（SECURITY_NAME_ABBR 是个股简称，不能拿来当板块名）。
                val boardName = item.optString("BOARD_NAME")
                if (boardName.isBlank()) continue
                tags.add(
                    BoardTag(
                        name = boardName,
                        boardCode = toBoardSecid(item.optString("BOARD_CODE")),
                        rank = item.optInt("BOARD_RANK", Int.MAX_VALUE),
                    ),
                )
            }
            return tags.sortedBy { it.rank }
        }

        /** 解析板块日线。纯函数，便于单测。 */
        fun parseBoardKline(text: String): List<DayBar> {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val klines = root.optJSONObject("data")?.optJSONArray("klines") ?: return emptyList()
            val bars = mutableListOf<DayBar>()
            for (i in 0 until klines.length()) {
                val parts = klines.optString(i).split(",")
                // date,open,close,high,low,volume,amount —— close 在第 2 位。
                if (parts.size < 6) continue
                val close = parts[2].toDoubleOrNull() ?: continue
                if (close <= 0) continue
                bars.add(
                    DayBar(
                        date = parts[0],
                        open = parts[1].toDoubleOrNull() ?: close,
                        high = parts[3].toDoubleOrNull() ?: close,
                        low = parts[4].toDoubleOrNull() ?: close,
                        close = close,
                        volume = parts[5].toDoubleOrNull() ?: 0.0,
                    ),
                )
            }
            return bars
        }

        /** 关注的概念板块关键词，与 macOS 版一致。 */
        val CONCEPT_KEYWORDS = listOf(
            "机器人", "航天", "半导体", "电网", "芯片", "新能源", "人工智能", "光伏", "锂电",
        )
    }
}
