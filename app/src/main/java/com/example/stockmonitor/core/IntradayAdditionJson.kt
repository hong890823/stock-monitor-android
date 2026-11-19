package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.IntradayAdditionState
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object IntradayAdditionJson {
    fun encode(states: Map<String, IntradayAdditionState>): String = JSONArray().also { array ->
        states.toSortedMap().forEach { (code, state) ->
            array.put(
                JSONObject()
                    .put("code", code)
                    .put("date", state.date)
                    .put("max_accepted_price", state.maxAcceptedPrice ?: JSONObject.NULL)
                    .put("invalidated", state.invalidated)
                    .put("portfolio_fingerprint", state.portfolioFingerprint)
                    .put("evaluation_fingerprint", state.evaluationFingerprint),
            )
        }
    }.toString()

    fun decode(raw: String?): Map<String, IntradayAdditionState> {
        if (raw.isNullOrBlank()) return emptyMap()
        val array = try {
            JSONArray(raw)
        } catch (error: JSONException) {
            throw IllegalArgumentException("盘中补仓状态格式无效", error)
        }
        val result = linkedMapOf<String, IntradayAdditionState>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw IllegalArgumentException("盘中补仓状态条目无效")
            val code = item.optString("code").trim()
            val date = item.optString("date").trim()
            require(code.isNotBlank() && date.isNotBlank()) { "盘中补仓状态缺少代码或交易日" }
            val maxAccepted = if (item.isNull("max_accepted_price")) {
                null
            } else {
                item.optDouble("max_accepted_price", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                    ?: throw IllegalArgumentException("盘中补仓状态最高接受价无效")
            }
            result[code] = IntradayAdditionState(
                code = code,
                date = date,
                maxAcceptedPrice = maxAccepted,
                invalidated = item.optBoolean("invalidated", false),
                portfolioFingerprint = item.optString("portfolio_fingerprint"),
                evaluationFingerprint = item.optString("evaluation_fingerprint"),
            )
        }
        return result
    }
}
