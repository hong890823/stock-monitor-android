package com.example.stockmonitor.core

import org.json.JSONArray
import org.json.JSONObject

/** 东方财富个股资金流批量接口；字段按订单规模归类，不代表机构身份。 */
class EastMoneyFundFlowClient {

    fun flows(codes: List<String>): Map<String, FundFlow> {
        val requested = codes.mapNotNull { code ->
            val normalized = normalizeCode(code)
            normalized?.let { code to it }
        }
        return requested.chunked(BATCH_SIZE).flatMap { batch ->
            val queryCodes = batch.map { it.second }
            val text = fetch(queryCodes)
            val parsed = parse(text)
            batch.mapNotNull { (sourceCode, normalized) ->
                parsed[normalized]?.let { sourceCode to it.copy(code = sourceCode) }
            }
        }.toMap()
    }

    private fun fetch(codes: List<String>): String {
        val url =
            "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2" +
                "&secids=${secids(codes).joinToString(",")}" +
                "&fields=f12,f14,f6,f62,f184,f66,f69,f72,f75,f78,f81,f84,f87"
        return Http.getText(
            url,
            referer = "https://quote.eastmoney.com/",
            timeoutMs = 15_000,
        )
    }

    companion object {
        private const val BATCH_SIZE = 50

        fun parse(payload: String, asOfMs: Long = System.currentTimeMillis()): Map<String, FundFlow> {
            val root = JSONObject(jsonpObject(payload))
            val data = root.optJSONObject("data") ?: return emptyMap()
            val rows = data.optJSONArray("diff") ?: JSONArray()
            return buildMap {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val code = normalizeCode(row.firstString("f12")) ?: continue
                    put(
                        code,
                        FundFlow(
                            code = code,
                            name = row.firstString("f14").orEmpty(),
                            asOfMs = asOfMs,
                            amountYuan = row.finiteOrNull("f6"),
                            mainNetYuan = row.finiteOrNull("f62"),
                            mainNetPct = row.finiteOrNull("f184"),
                            superLargeNetYuan = row.finiteOrNull("f66"),
                            superLargeNetPct = row.finiteOrNull("f69"),
                            largeNetYuan = row.finiteOrNull("f72"),
                            largeNetPct = row.finiteOrNull("f75"),
                            mediumNetYuan = row.finiteOrNull("f78"),
                            mediumNetPct = row.finiteOrNull("f81"),
                            smallNetYuan = row.finiteOrNull("f84"),
                            smallNetPct = row.finiteOrNull("f87"),
                        ),
                    )
                }
            }
        }

        fun secids(codes: List<String>): List<String> = codes.mapNotNull { code ->
            val normalized = normalizeCode(code) ?: return@mapNotNull null
            val market = when {
                normalized.startsWith("6") || normalized.startsWith("9") ||
                    normalized.startsWith("5") -> "1"
                normalized.startsWith("0") || normalized.startsWith("3") -> "0"
                normalized.startsWith("4") || normalized.startsWith("8") -> "0"
                else -> return@mapNotNull null
            }
            "$market.$normalized"
        }

        private fun normalizeCode(value: String?): String? {
            val text = value?.trim()?.lowercase().orEmpty()
            val digits = text.substringAfterLast('.')
                .removePrefix("sh")
                .removePrefix("sz")
                .removePrefix("bj")
                .filter { it.isDigit() }
            return digits.takeIf { it.length == 6 }
        }

        private fun JSONObject.firstString(key: String): String? =
            when (val value = opt(key)) {
                null, JSONObject.NULL -> null
                is Number -> value.toString()
                else -> value.toString().takeIf { it.isNotBlank() && it != "-" }
            }

        private fun JSONObject.finiteOrNull(key: String): Double? {
            val value = firstString(key)?.toDoubleOrNull() ?: return null
            return value.takeIf { it.isFinite() }
        }

        private fun jsonpObject(payload: String): String {
            val text = payload.trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            require(start >= 0 && end > start) { "东方财富资金流响应不是 JSON/JSONP" }
            return text.substring(start, end + 1)
        }
    }
}
