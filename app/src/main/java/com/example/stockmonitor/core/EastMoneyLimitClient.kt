package com.example.stockmonitor.core

import org.json.JSONObject

/** 东方财富涨跌停池。接口返回 JSONP，统计字段比新浪近似扫描更完整。 */
class EastMoneyLimitClient {

    fun pools(date: String): LimitData {
        val queryDate = date.replace("-", "")
        require(queryDate.matches(Regex("\\d{8}"))) { "日期必须是 yyyy-MM-dd 或 yyyyMMdd" }
        val upText = fetch("getTopicZTPool", "fbt%3Aasc", queryDate)
        val downText = fetch("getTopicDTPool", "zdp%3Aasc", queryDate)
        return parsePools(upText, downText)
    }

    fun parsePools(upJson: String, downJson: String): LimitData =
        LimitData(
            up = parsePool(upJson, limitDown = false),
            down = parsePool(downJson, limitDown = true),
            source = LimitDataSource.EASTMONEY_LIMIT_POOL,
        )

    private fun fetch(topic: String, sort: String, date: String): String {
        val url =
            "https://push2ex.eastmoney.com/$topic?cb=callback" +
                "&ut=7eea3edcaed734bea9cbfc24409ed989&dpt=wz.ztzt" +
                "&Pageindex=0&pagesize=1000&sort=$sort&date=$date"
        return Http.getText(
            url,
            referer = "https://quote.eastmoney.com/",
            timeoutMs = 15_000,
        )
    }

    private fun parsePool(payload: String, limitDown: Boolean): List<LimitStock> {
        val json = JSONObject(jsonpObject(payload))
        val resultCode = json.optInt("rc", Int.MIN_VALUE)
        if (resultCode != 0) throw Http.HttpError("东方财富涨跌停池返回异常 rc=$resultCode")
        val data = json.optJSONObject("data") ?: throw Http.HttpError("东方财富涨跌停池数据为空")
        val pool = data.optJSONArray("pool") ?: return emptyList()
        return buildList {
            for (index in 0 until pool.length()) {
                val item = pool.optJSONObject(index) ?: continue
                val code = item.optString("c").trim()
                val name = item.optString("n").trim()
                if (code.length != 6 || name.isBlank()) continue
                val price = item.optDouble("p", Double.NaN) / 1000.0
                val pct = item.optDouble("zdp", Double.NaN)
                if (!price.isFinite() || price <= 0.0 || !pct.isFinite()) continue
                val prev = price / (1.0 + pct / 100.0)
                val openCount = if (limitDown) item.optInt("oc", -1) else item.optInt("zbc", -1)
                add(
                    LimitStock(
                        code = BoardMember.quoteCodeOf(code),
                        name = name,
                        price = price,
                        prev = prev,
                        pct = pct,
                        sealAmountYuan = item.finiteOrNull("fund"),
                        turnoverYuan = item.finiteOrNull("amount"),
                        floatMarketValueYuan = item.finiteOrNull("ltsz"),
                        openCount = openCount.takeIf { it >= 0 },
                        firstSealTime = item.timeOrNull("fbt"),
                        lastSealTime = item.timeOrNull("lbt"),
                        limitStreak = item.optInt("lbc", -1).takeIf { it > 0 },
                        industry = item.optString("hybk").trim().takeIf { it.isNotEmpty() },
                        source = LimitDataSource.EASTMONEY_LIMIT_POOL,
                    ),
                )
            }
        }
    }

    private fun jsonpObject(payload: String): String {
        val start = payload.indexOf('{')
        val end = payload.lastIndexOf('}')
        if (start < 0 || end <= start) throw Http.HttpError("东方财富涨跌停池响应不是 JSON/JSONP")
        return payload.substring(start, end + 1)
    }

    private fun JSONObject.finiteOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key, Double.NaN).takeIf { it.isFinite() } else null

    private fun JSONObject.timeOrNull(key: String): String? {
        val raw = optString(key).trim()
        if (raw.isEmpty()) return null
        val digits = raw.filter { it.isDigit() }.padStart(6, '0').takeLast(6)
        val hour = digits.substring(0, 2).toIntOrNull() ?: return null
        val minute = digits.substring(2, 4).toIntOrNull() ?: return null
        val second = digits.substring(4, 6).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return "%02d:%02d:%02d".format(hour, minute, second)
    }
}
