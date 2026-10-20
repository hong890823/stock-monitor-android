package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.BoardTag
import org.json.JSONArray
import org.json.JSONObject

/**
 * 个股所属板块的落盘缓存（[Store.boardTags]）。
 *
 * 板块归属变化极慢（除非重组或改行业分类），所以按需拉、**长缓存**——
 * 与基本面健康判断（`FundamentalHealthPolicy.CACHE_TTL_MS`）同一思路：
 * 别让每 30 分钟的持仓分析都去打一遍 F10，那既慢又容易被限流。
 */
object BoardTagCache {

    /** 归属缓存的有效期。 */
    const val TTL_MS = 15L * 24 * 60 * 60 * 1000

    data class Entry(val tags: List<BoardTag>, val fetchedAtMs: Long)

    /** 编码成 `{code: {"tags": [...], "at": 123}}`。 */
    fun encode(entries: Map<String, Entry>): String {
        val root = JSONObject()
        entries.forEach { (code, entry) ->
            val tags = JSONArray()
            entry.tags.forEach { tag ->
                tags.put(
                    JSONObject()
                        .put("name", tag.name)
                        .put("board_code", tag.boardCode ?: "")
                        .put("rank", tag.rank),
                )
            }
            root.put(code, JSONObject().put("tags", tags).put("at", entry.fetchedAtMs))
        }
        return root.toString()
    }

    fun decode(raw: String?): Map<String, Entry> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val entries = linkedMapOf<String, Entry>()
        val codes = root.keys()
        while (codes.hasNext()) {
            val code = codes.next()
            val item = root.optJSONObject(code) ?: continue
            val array = item.optJSONArray("tags") ?: continue
            val tags = mutableListOf<BoardTag>()
            for (i in 0 until array.length()) {
                val tag = array.optJSONObject(i) ?: continue
                tags.add(
                    BoardTag(
                        name = tag.optString("name"),
                        boardCode = tag.optString("board_code").takeIf { it.isNotBlank() },
                        rank = tag.optInt("rank", Int.MAX_VALUE),
                    ),
                )
            }
            entries[code] = Entry(tags, item.optLong("at", 0L))
        }
        return entries
    }

    /** 缓存里还有没有这条、且没过期。 */
    fun isFresh(entry: Entry?, nowMs: Long): Boolean {
        if (entry == null || entry.tags.isEmpty()) return false
        return nowMs - entry.fetchedAtMs in 0..TTL_MS
    }

    /**
     * 板块当日涨跌的兜底匹配：东财行业板块 → 新浪行业板块。
     *
     * **按 [candidateNames]（F10 给出的全部行业标签，按 rank 排序）逐个试**，不只用主行业——
     * F10 的 rank-1 是申万一级（「电力设备」），而东财行业板块列表是更细的东财行业
     * （「电网设备」「光伏设备」），粒度不同，只拿 rank-1 去匹配会全军覆没。
     * 命中哪个层级就把哪个标在 [com.example.stockmonitor.analysis.BoardChange.source] 里。
     */
    fun matchBoardChange(
        industryBoards: Map<String, Double>,
        candidateNames: List<String>,
        sinaFallback: ((String) -> com.example.stockmonitor.analysis.BoardChange)? = null,
    ): com.example.stockmonitor.analysis.BoardChange {
        if (candidateNames.isEmpty()) {
            return com.example.stockmonitor.analysis.BoardChange(null, "无行业标签可匹配")
        }
        if (industryBoards.isEmpty()) {
            // 东财那条没拿到列表。**不能直接 return 新浪的结果**——那样东财失败的原因会被吃掉，
            // 报告里只剩新浪那句，下一轮还是只能猜（前两轮就是这么白跑的）。
            val sinaResults = candidateNames.mapNotNull { sinaFallback?.invoke(it) }
            sinaResults.firstOrNull { it.pct != null }?.let { return it }
            val sina = sinaResults.firstOrNull()
            return com.example.stockmonitor.analysis.BoardChange(
                null,
                listOfNotNull(
                    "东财行业板块接口不可达或返回空（试过：${candidateNames.joinToString("、")}）",
                    sina?.source,
                ).joinToString("；"),
            )
        }
        candidateNames.forEach { name ->
            industryBoards[name]?.let {
                val label = if (name == candidateNames.first()) "东财行业板块" else "东财行业板块「$name」"
                return com.example.stockmonitor.analysis.BoardChange(it, label)
            }
        }
        // 粒度不同也能靠包含关系对上（「电力设备」⊃「电网设备」，或反过来）。
        candidateNames.forEach { name ->
            val hit = industryBoards.entries.firstOrNull { it.key.contains(name) || name.contains(it.key) }
            if (hit != null) {
                return com.example.stockmonitor.analysis.BoardChange(
                    hit.value,
                    "东财行业板块「${hit.key}」（按包含匹配）",
                )
            }
        }
        // 到这里东财那条完全没命中。诊断要把**两件事**都说清：
        // ① 我们试过哪些标签（F10 到底给了哪几级），② 东财列表里实际有什么（万一分类根本不是行业）。
        // 这两句必须落在最终返回值上——被新浪兜底覆盖掉的话就又白跑了。
        val emFail = "东财行业板块里无同名项（试过：${candidateNames.joinToString("、")}" +
            "；列表前几项：${industryBoards.keys.take(6).joinToString("、")}）"
        val sinaResults = candidateNames.mapNotNull { sinaFallback?.invoke(it) }
        sinaResults.firstOrNull { it.pct != null }?.let { return it }
        val sina = sinaResults.firstOrNull()
        return com.example.stockmonitor.analysis.BoardChange(
            null,
            listOfNotNull(emFail, sina?.source).joinToString("；"),
        )
    }

    /**
     * 取一只股票的板块上下文：归属读缓存、过期才打 F10，板块日线按板块码由 [boardBars] 去重。
     *
     * 持仓分析（多只）和工具页个股分析（一只）共用这一份，免得两处各写一遍缓存规则。
     * 任一步失败只影响这一只，返回 [SectorContext.MISSING]——**不猜**。
     *
     * [boardChangeFallback] 是板块**当日涨跌**的兜底（按行业名匹配行情，比如新浪行业板块）。
     * 板块日线拉不到时用它——涨跌拉得到就能给出「个股比板块强/弱多少」这个判断，
     * 比整块空着有用得多；来源会标在报告里，方便诊断哪条路通了。
     */
    fun loadContext(
        store: Store,
        eastMoney: EastMoneyClient,
        code: String,
        boardBars: MutableMap<String, List<DayBar>> = mutableMapOf(),
        boardChangeFallback: ((List<String>) -> com.example.stockmonitor.analysis.BoardChange)? = null,
    ): com.example.stockmonitor.analysis.SectorContext {
        val cache = decode(store.boardTags).toMutableMap()
        val nowMs = System.currentTimeMillis()
        var entry = cache[code]
        if (!isFresh(entry, nowMs)) {
            val fetched = runCatching { eastMoney.stockBoards(code) }.getOrNull()
            if (!fetched.isNullOrEmpty()) {
                entry = Entry(fetched, nowMs)
                cache[code] = entry
                store.boardTags = encode(cache)
            }
        }
        val tags = entry?.tags.orEmpty()
        if (tags.isEmpty()) return com.example.stockmonitor.analysis.SectorContext.MISSING
        // 主行业是滤掉地域/风格后 rank 最小的那个；拿不到板块码就只给归属，不判趋势。
        val industries = tags.filter { com.example.stockmonitor.analysis.SectorContext.isIndustry(it.name) }
        val industry = industries.minByOrNull { it.rank }
        val bars = if (industry?.boardCode == null) {
            emptyList()
        } else {
            boardBars.getOrPut(industry.boardCode) {
                runCatching { eastMoney.boardKline(industry.boardCode, 120) }.getOrDefault(emptyList())
            }
        }
        val fromKline = if (bars.size >= 2) {
            (bars.last().close / bars[bars.size - 2].close - 1) * 100
        } else {
            null
        }
        // 兜底要拿**全部**行业标签去试：F10 的 rank-1 是申万一级，东财行业板块是更细的东财行业，
        // 只拿 rank-1 匹配会全军覆没（实测「电力设备」在东财行业板块列表里就没有同名项）。
        val fallback = if (fromKline == null && boardChangeFallback != null && industries.isNotEmpty()) {
            boardChangeFallback(industries.sortedBy { it.rank }.map { it.name })
        } else {
            null
        }
        return com.example.stockmonitor.analysis.SectorContext.of(
            tags = tags,
            boardBars = bars,
            boardChangePct = fromKline ?: fallback?.pct,
            changeSource = if (fromKline != null) {
                "板块日线"
            } else {
                fallback?.source ?: "板块码不通，也无兜底"
            },
        )
    }
}
