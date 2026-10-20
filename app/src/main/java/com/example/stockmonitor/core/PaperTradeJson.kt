package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.PaperTradeRecord
import org.json.JSONArray
import org.json.JSONObject

object PaperTradeJson {
    fun encode(items: List<PaperTradeRecord>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("code", item.code)
                    .put("name", item.name)
                    .put("strategy", item.strategy.name)
                    .put("entry_date", item.entryDate)
                    .put("entry_price", item.entryPrice)
                    .put("quantity", item.quantity)
                    .put("last_date", item.lastDate)
                    .put("last_price", item.lastPrice)
                    .put("exit_date", item.exitDate)
                    .put("exit_price", item.exitPrice)
                    .put("exit_reason", item.exitReason)
                    .put("entry_fees", item.entryFees)
                    .put("exit_fees", item.exitFees)
                    .put("net_pnl", item.netPnl)
                    .put("pnl_pct", item.pnlPct),
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<PaperTradeRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val code = item.optString("code")
                    val entryDate = item.optString("entry_date")
                    if (code.isBlank() || entryDate.isBlank()) continue
                    add(
                        PaperTradeRecord(
                            code = code,
                            name = item.optString("name", code),
                            strategy = runCatching {
                                ObservationStrategy.valueOf(item.optString("strategy"))
                            }.getOrDefault(ObservationStrategy.PRE_BREAKOUT),
                            entryDate = entryDate,
                            entryPrice = item.optDouble("entry_price", 0.0),
                            quantity = item.optInt("quantity", 100),
                            lastDate = item.optString("last_date", entryDate),
                            lastPrice = item.optDouble("last_price", 0.0),
                            exitDate = item.optString("exit_date").takeIf { it.isNotBlank() },
                            exitPrice = item.optDouble("exit_price", Double.NaN).takeUnless { it.isNaN() },
                            exitReason = item.optString("exit_reason").takeIf { it.isNotBlank() },
                            entryFees = item.optDouble("entry_fees", 0.0),
                            exitFees = item.optDouble("exit_fees", Double.NaN).takeUnless { it.isNaN() },
                            netPnl = item.optDouble("net_pnl", Double.NaN).takeUnless { it.isNaN() },
                            pnlPct = item.optDouble("pnl_pct", Double.NaN).takeUnless { it.isNaN() },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
