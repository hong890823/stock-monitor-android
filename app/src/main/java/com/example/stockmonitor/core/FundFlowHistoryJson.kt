package com.example.stockmonitor.core

import org.json.JSONArray
import org.json.JSONObject

object FundFlowHistoryJson {
    const val MAX_DAYS = 5

    fun encode(history: Map<String, List<FundFlow>>): String {
        val root = JSONObject()
        history.forEach { (code, flows) ->
            if (code.isBlank() || flows.isEmpty()) return@forEach
            val rows = JSONArray()
            flows.forEach { flow -> rows.put(encodeFlow(flow)) }
            root.put(code, rows)
        }
        return root.toString()
    }

    fun decode(raw: String?): Map<String, List<FundFlow>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { code ->
                    val rows = root.optJSONArray(code) ?: return@forEach
                    val flows = MutableList(rows.length()) { index ->
                        decodeFlow(rows.getJSONObject(index))
                    }.sortedBy { it.asOfMs }.takeLast(MAX_DAYS)
                    if (flows.isNotEmpty()) put(code, flows)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun withSnapshot(
        history: Map<String, List<FundFlow>>,
        updates: Map<String, FundFlow>,
    ): Map<String, List<FundFlow>> {
        if (updates.isEmpty()) return history
        return buildMap {
            putAll(history)
            updates.forEach { (code, flow) ->
                val merged = (this[code].orEmpty() + flow)
                    .sortedBy { it.asOfMs }
                    .fold(mutableListOf<FundFlow>()) { result, item ->
                        val duplicateIndex = result.indexOfLast {
                            it.tradingDate != null && it.tradingDate == item.tradingDate
                        }
                        if (duplicateIndex >= 0) result[duplicateIndex] = item else result += item
                        result
                    }
                    .sortedBy { it.tradingDate ?: it.asOfMs.toString() }
                    .takeLast(MAX_DAYS)
                if (merged.isNotEmpty()) put(code, merged)
            }
        }
    }

    private fun encodeFlow(flow: FundFlow): JSONObject = JSONObject().apply {
        put("code", flow.code)
        put("name", flow.name)
        put("as_of_ms", flow.asOfMs)
        put("amount_yuan", flow.amountYuan ?: JSONObject.NULL)
        put("main_net_yuan", flow.mainNetYuan ?: JSONObject.NULL)
        put("main_net_pct", flow.mainNetPct ?: JSONObject.NULL)
        put("super_large_net_yuan", flow.superLargeNetYuan ?: JSONObject.NULL)
        put("super_large_net_pct", flow.superLargeNetPct ?: JSONObject.NULL)
        put("large_net_yuan", flow.largeNetYuan ?: JSONObject.NULL)
        put("large_net_pct", flow.largeNetPct ?: JSONObject.NULL)
        put("medium_net_yuan", flow.mediumNetYuan ?: JSONObject.NULL)
        put("medium_net_pct", flow.mediumNetPct ?: JSONObject.NULL)
        put("small_net_yuan", flow.smallNetYuan ?: JSONObject.NULL)
        put("small_net_pct", flow.smallNetPct ?: JSONObject.NULL)
        put("trading_date", flow.tradingDate ?: JSONObject.NULL)
    }

    private fun decodeFlow(item: JSONObject): FundFlow = FundFlow(
        code = item.optString("code"),
        name = item.optString("name"),
        asOfMs = item.optLong("as_of_ms", 0L),
        amountYuan = item.finiteOrNull("amount_yuan"),
        mainNetYuan = item.finiteOrNull("main_net_yuan"),
        mainNetPct = item.finiteOrNull("main_net_pct"),
        superLargeNetYuan = item.finiteOrNull("super_large_net_yuan"),
        superLargeNetPct = item.finiteOrNull("super_large_net_pct"),
        largeNetYuan = item.finiteOrNull("large_net_yuan"),
        largeNetPct = item.finiteOrNull("large_net_pct"),
        mediumNetYuan = item.finiteOrNull("medium_net_yuan"),
        mediumNetPct = item.finiteOrNull("medium_net_pct"),
        smallNetYuan = item.finiteOrNull("small_net_yuan"),
        smallNetPct = item.finiteOrNull("small_net_pct"),
        tradingDate = if (item.isNull("trading_date")) {
            null
        } else {
            item.optString("trading_date").takeIf { it.isNotBlank() }
        },
    )

    private fun JSONObject.finiteOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { it.isFinite() }
}
