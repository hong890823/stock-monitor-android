package com.example.stockmonitor.core

import org.json.JSONObject

/**
 * 行情快照的落盘缓存与合并规则。
 *
 * 行情原先只活在 [com.example.stockmonitor.monitor.Repo] 的内存里，进程被系统回收后
 * 「市场」页的市场概览 / 指数 / 自选股只能显示「等待行情数据…」，且盘后（以及周末、
 * 清晨）新浪返回的是**最近交易日**的收盘快照，旧逻辑按「日期必须是今天」过滤会把
 * 它们整批丢弃，界面要空到下个交易日才恢复。
 *
 * - [encode]/[decode]：每轮拉取成功后落盘一份快照，服务重启先恢复再等网络刷新；
 * - [mergeNewer]：合并新响应与内存行情，**旧行情只补空缺、绝不覆盖更新的行情**。
 *   「不是今天」的收盘快照正是要显示的最近交易日数据，收；但延迟响应也不能把内存里
 *   今日的行情盖回昨天——旧逻辑的防覆盖语义由日期比较保留下来。
 */
object QuoteCache {

    /** 落盘内容：保存时刻（yyyy-MM-dd HH:mm）+ 行情快照。 */
    data class Snapshot(val savedAt: String, val quotes: Map<String, Quote>)

    fun encode(snapshot: Snapshot): String {
        val quotes = JSONObject()
        snapshot.quotes.values.forEach { quote -> quotes.put(quote.code, quoteToJson(quote)) }
        return JSONObject()
            .put("saved_at", snapshot.savedAt)
            .put("quotes", quotes)
            .toString()
    }

    fun decode(raw: String?): Snapshot? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val savedAt = root.optString("saved_at")
        if (savedAt.isBlank()) return null
        val quotes = root.optJSONObject("quotes") ?: return null
        val result = LinkedHashMap<String, Quote>()
        quotes.keys().forEach { code ->
            val item = quotes.optJSONObject(code) ?: return@forEach
            quoteFromJson(code, item)?.let { result[code] = it }
        }
        return if (result.isEmpty()) null else Snapshot(savedAt, result)
    }

    /**
     * 合并新响应到内存行情：更新的（或同日的）覆盖，更旧的只在内存没有时补空缺。
     * [incoming] 为空原样返回，网络空响应不动已有数据。
     */
    fun mergeNewer(existing: Map<String, Quote>, incoming: Map<String, Quote>): Map<String, Quote> {
        if (incoming.isEmpty()) return existing
        val merged = LinkedHashMap(existing)
        for ((code, quote) in incoming) {
            val old = merged[code]
            if (old == null || !isOlder(quote.quoteDate, old.quoteDate)) merged[code] = quote
        }
        return merged
    }

    /** 比较行情日期（yyyy-MM-dd，字典序即时间序）：解析不出的视为最旧。 */
    private fun isOlder(newDate: String?, oldDate: String?): Boolean {
        val new = newDate?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        val old = oldDate?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        if (new == null) return old != null
        if (old == null) return false
        return new < old
    }

    private fun quoteToJson(quote: Quote): JSONObject =
        JSONObject()
            .put("name", quote.name)
            .put("price", quote.price)
            .put("prev_close", quote.prevClose)
            .put("open", quote.open)
            .put("high", quote.high)
            .put("low", quote.low)
            .put("volume", quote.volume)
            .put("turnover", quote.turnover)
            .apply {
                quote.quoteDate?.let { put("date", it) }
                quote.buy1Volume?.let { put("buy1_volume", it) }
            }

    private fun quoteFromJson(code: String, item: JSONObject): Quote? {
        val name = item.optString("name")
        val price = item.optDouble("price", Double.NaN)
        val prevClose = item.optDouble("prev_close", Double.NaN)
        if (name.isBlank() || !price.isFinite() || price <= 0) return null
        if (!prevClose.isFinite() || prevClose <= 0) return null
        return Quote(
            code = code,
            name = name,
            price = price,
            prevClose = prevClose,
            open = item.optDouble("open", price).takeIf { it.isFinite() && it > 0 } ?: price,
            high = item.optDouble("high", price).takeIf { it.isFinite() && it > 0 } ?: price,
            low = item.optDouble("low", price).takeIf { it.isFinite() && it > 0 } ?: price,
            volume = item.optDouble("volume", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0,
            turnover = item.optDouble("turnover", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0,
            quoteDate = item.optString("date").takeIf { it.isNotBlank() },
            buy1Volume = item.optDouble("buy1_volume", Double.NaN).takeIf { it.isFinite() && it >= 0 },
        )
    }
}
