package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.PositionResult
import org.json.JSONObject

/**
 * 持仓分析的**状态快照**：今天一份、昨天一份，用来写「较昨日」。
 *
 * 没有这份快照，报告只能写「现在在哪」，写不出「今天这根 K 线改变了什么」——
 * 而复盘真正要看的是后者。跌 5% 和横盘的两只股，状态快照可能几乎一样，变化叙述才分得开。
 *
 * 每行只留写「较昨日」需要的最小字段：信号、评分、支撑、压力、现价、当日涨跌、量比。
 * 不存完整 [com.example.stockmonitor.analysis.PositionResult]——那份太大，而且多数字段当天不变。
 */
data class RowSnapshot(
    val code: String,
    val signal: String,
    val score: Int,
    val support: Double,
    val resistance: Double,
    val price: Double,
    val changePct: Double,
    val volumeRatio: Double,
)

/**
 * 快照状态。[rows] 是最新（今天）的结果，[prevRows] 是**上一个交易日**的结果。
 *
 * 更新规则见 [PortfolioSnapshotJson.rollForward]：同一天只刷新 `rows`；
 * 跨天才把旧的 `date/rows` 降级成 `prev_date/prev_rows`。这样「昨日」始终是上一个交易日收盘，
 * 不会被盘中的多次刷新覆盖掉。
 */
data class PortfolioSnapshotState(
    val date: String,
    val rows: Map<String, RowSnapshot>,
    val prevDate: String? = null,
    val prevRows: Map<String, RowSnapshot> = emptyMap(),
) {
    /** 有没有可用的昨日快照（没有就写「无昨日快照」，不硬编「无变化」）。 */
    val hasPrev: Boolean get() = prevDate != null && prevRows.isNotEmpty()
}

/** [Store.portfolioSnapshot] 的编解码，写法与 [ScreenResultJson] 一致。 */
object PortfolioSnapshotJson {

    /** 只有行情明确标注为当天时，才能把一次分析推进为新的交易日快照。 */
    fun hasFreshTradingDate(quotes: Collection<Quote>, today: String): Boolean =
        quotes.any { it.quoteDate?.substringBefore(' ') == today }

    /** 从一次分析结果里取出写「较昨日」需要的最小字段。 */
    fun from(results: List<PositionResult>): Map<String, RowSnapshot> = results.associate { result ->
        result.code to RowSnapshot(
            code = result.code,
            signal = result.signal,
            score = result.score,
            support = result.support,
            resistance = result.resistance,
            price = result.price,
            changePct = result.changePct,
            volumeRatio = result.volumeRatio,
        )
    }

    /** 合并本轮成功结果；同一天本轮失败的持仓保留上一轮已知状态。 */
    fun mergeRows(
        current: PortfolioSnapshotState?,
        today: String,
        freshRows: Map<String, RowSnapshot>,
        activeCodes: Set<String>,
    ): Map<String, RowSnapshot> {
        val previous = current?.takeIf { it.date == today }?.rows.orEmpty()
        return activeCodes.mapNotNull { code ->
            (freshRows[code] ?: previous[code])?.let { code to it }
        }.toMap()
    }

    fun encode(state: PortfolioSnapshotState): String {
        val root = JSONObject()
        root.put("date", state.date)
        root.put("rows", rowsToJson(state.rows))
        state.prevDate?.let { root.put("prev_date", it) }
        if (state.prevRows.isNotEmpty()) root.put("prev_rows", rowsToJson(state.prevRows))
        return root.toString()
    }

    fun decode(raw: String?): PortfolioSnapshotState? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val date = root.optString("date")
        if (date.isBlank()) return null
        return PortfolioSnapshotState(
            date = date,
            rows = rowsFromJson(root.optJSONObject("rows")),
            prevDate = root.optString("prev_date").takeIf { it.isNotBlank() },
            prevRows = rowsFromJson(root.optJSONObject("prev_rows")),
        )
    }

    /**
     * 推进到 [today] 的结果 [rows]。
     *
     * 同一天（盘中每 30 分钟一次）只刷新今天的 `rows`，不动昨日；
     * 换了日期才把上一份整体降级成昨日——所以「昨日」永远是上一个交易日收盘时的样子。
     */
    fun rollForward(
        current: PortfolioSnapshotState?,
        today: String,
        rows: Map<String, RowSnapshot>,
    ): PortfolioSnapshotState = when {
        current == null -> PortfolioSnapshotState(date = today, rows = rows)
        current.date == today -> PortfolioSnapshotState(
            date = today,
            rows = rows,
            prevDate = current.prevDate,
            prevRows = current.prevRows,
        )
        else -> PortfolioSnapshotState(
            date = today,
            rows = rows,
            prevDate = current.date,
            prevRows = current.rows,
        )
    }

    private fun rowsToJson(rows: Map<String, RowSnapshot>): JSONObject {
        val obj = JSONObject()
        rows.forEach { (code, row) ->
            obj.put(
                code,
                JSONObject()
                    .put("code", row.code)
                    .put("signal", row.signal)
                    .put("score", row.score)
                    .put("support", row.support)
                    .put("resistance", row.resistance)
                    .put("price", row.price)
                    .put("change_pct", row.changePct)
                    .put("volume_ratio", row.volumeRatio),
            )
        }
        return obj
    }

    private fun rowsFromJson(obj: JSONObject?): Map<String, RowSnapshot> {
        if (obj == null) return emptyMap()
        val rows = linkedMapOf<String, RowSnapshot>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = obj.optJSONObject(key) ?: continue
            rows[key] = RowSnapshot(
                code = item.optString("code", key),
                signal = item.optString("signal"),
                score = item.optInt("score"),
                support = item.optDouble("support", 0.0),
                resistance = item.optDouble("resistance", 0.0),
                price = item.optDouble("price", 0.0),
                changePct = item.optDouble("change_pct", 0.0),
                volumeRatio = item.optDouble("volume_ratio", 0.0),
            )
        }
        return rows
    }
}
