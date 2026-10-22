package com.example.stockmonitor.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object TradeLotJson {
    fun encode(book: TradeBook): String = JSONObject()
        .put("entries", JSONArray().also { array ->
            book.entries.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("code", entry.code)
                        .put("name", entry.name)
                        .put("side", entry.side.name)
                        .put("date", entry.date)
                        .put("quantity", entry.quantity)
                        .put("price", entry.price)
                        .put("source", entry.source.name),
                )
            }
        })
        .put("lots", JSONArray().also { array ->
            book.lots.forEach { lot ->
                array.put(
                    JSONObject()
                        .put("code", lot.code)
                        .put("name", lot.name)
                        .put("quantity", lot.quantity)
                        .put("unit_cost", lot.unitCost)
                        .put("buy_date", lot.buyDate),
                )
            }
        })
        .toString()

    fun decode(raw: String?): TradeBook {
        if (raw.isNullOrBlank()) return TradeBook()
        val root = try {
            JSONObject(raw)
        } catch (error: JSONException) {
            throw IllegalArgumentException("交易账本格式无效", error)
        }
        return TradeBook(
            entries = decodeEntries(root.optJSONArray("entries")),
            lots = decodeLots(root.optJSONArray("lots")),
        )
    }

    private fun decodeEntries(array: JSONArray?): List<TradeRecord> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val code = item.optString("code")
            val name = item.optString("name")
            val date = item.optString("date")
            val quantity = item.optDouble("quantity", Double.NaN)
            val price = item.optDouble("price", Double.NaN)
            val side = enumValueOrNull<TradeSide>(item.optString("side")) ?: return@mapNotNull null
            val source = enumValueOrNull<TradeSource>(item.optString("source")) ?: return@mapNotNull null
            if (id.isBlank() || code.isBlank() || name.isBlank() || date.isBlank() ||
                !quantity.isFinite() || quantity <= 0.0 || !price.isFinite() || price <= 0.0
            ) {
                return@mapNotNull null
            }
            TradeRecord(id, code, name, side, date, quantity, price, source)
        }
    }

    private fun decodeLots(array: JSONArray?): List<TradeLot> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val code = item.optString("code")
            val name = item.optString("name")
            val buyDate = item.optString("buy_date")
            val quantity = item.optDouble("quantity", Double.NaN)
            val unitCost = item.optDouble("unit_cost", Double.NaN)
            // 成本 0（未填成本的持仓）也要保留批次：丢掉会让这些股份变成「未记录日期」残股，
            // 被 T+1 永远按锁定算。
            if (code.isBlank() || name.isBlank() || buyDate.isBlank() ||
                !quantity.isFinite() || quantity <= 0.0 || !unitCost.isFinite() || unitCost < 0.0
            ) {
                return@mapNotNull null
            }
            TradeLot(code, name, quantity, unitCost, buyDate)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }
}
