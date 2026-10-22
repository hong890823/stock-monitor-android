package com.example.stockmonitor.core

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

enum class NewsSource(val label: String) {
    EASTMONEY("东方财富"),
    SINA("新浪财经"),
}

data class NewsItem(
    val title: String,
    val publishedAtMs: Long,
    val source: String,
    val url: String? = null,
    val rawCategory: String? = null,
)

object MarketNewsClient {
    private const val EASTMONEY_URL =
        "https://np-listapi.eastmoney.com/comm/web/getNewsByColumns?client=web&biz=web_news&column=350&order=1&needInteractData=0&page_index=1&page_size=50"
    private const val SINA_URL =
        "https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=2516&k=&num=50&page=1"
    private val shanghaiTimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    fun today(nowMs: Long): List<NewsItem> {
        val eastMoneyResult = runCatching {
            parseEastMoney(Http.getText(EASTMONEY_URL, referer = "https://finance.eastmoney.com/"))
        }
        val eastMoney = eastMoneyResult.getOrNull()
            ?.filter { item -> isSameLocalDay(item.publishedAtMs, nowMs) }
            .orEmpty()
        if (eastMoney.isNotEmpty()) return eastMoney

        val sinaResult = runCatching {
            parseSina(Http.getText(SINA_URL, referer = "https://finance.sina.com.cn/"))
        }
        val sina = sinaResult.getOrNull()
            ?.filter { item -> isSameLocalDay(item.publishedAtMs, nowMs) }
            .orEmpty()
        if (sina.isNotEmpty()) return sina

        if (eastMoneyResult.isSuccess || sinaResult.isSuccess) return emptyList()
        throw eastMoneyResult.exceptionOrNull()
            ?: sinaResult.exceptionOrNull()
            ?: Http.HttpError("今日要闻请求失败")
    }

    fun parseEastMoney(text: String): List<NewsItem> {
        val root = parseJson(text)
        val rows = firstArray(root, "data.list", "data.newslist", "data.items", "list", "newslist")
        return rows.objects().mapNotNull { row ->
            val title = row.firstString("title", "name")?.trim().orEmpty()
            val publishedAtMs = parseTimestamp(
                row.firstString("showtime", "time", "pubtime", "date", "publishTime"),
            )
            if (title.isBlank() || publishedAtMs <= 0L) {
                null
            } else {
                NewsItem(
                    title = title,
                    publishedAtMs = publishedAtMs,
                    source = row.firstString("media", "source", "mediaName")?.trim()
                        ?.takeIf { it.isNotBlank() } ?: NewsSource.EASTMONEY.label,
                    url = normalizeUrl(row.firstString("url", "link", "wapurl")),
                    rawCategory = row.firstString("category", "column", "type"),
                )
            }
        }.toList()
    }

    fun parseSina(text: String): List<NewsItem> {
        val root = parseJson(text)
        val rows = firstArray(root, "result.data", "result.newslist", "data", "newslist")
        return rows.objects().mapNotNull { row ->
            val title = row.firstString("title", "name")?.trim().orEmpty()
            val publishedAtMs = parseTimestamp(row.firstString("ctime", "time", "pubtime", "date"))
            if (title.isBlank() || publishedAtMs <= 0L) {
                null
            } else {
                NewsItem(
                    title = title,
                    publishedAtMs = publishedAtMs,
                    source = row.firstString("media_name", "mediaName", "source")?.trim()
                        ?.takeIf { it.isNotBlank() } ?: NewsSource.SINA.label,
                    url = normalizeUrl(row.firstString("url", "link")),
                    rawCategory = row.firstString("category", "type"),
                )
            }
        }.toList()
    }

    private fun parseJson(text: String): JSONObject {
        val trimmed = text.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "新闻响应不是 JSON/JSONP" }
        return JSONObject(trimmed.substring(start, end + 1))
    }

    private fun firstArray(root: JSONObject, vararg paths: String): JSONArray {
        paths.forEach { path ->
            var current: Any = root
            val found = path.split('.').all { key ->
                current = when (val value = current) {
                    is JSONObject -> value.opt(key) ?: return@all false
                    else -> return@all false
                }
                true
            }
            if (found && current is JSONArray) return current
        }
        return JSONArray()
    }

    private fun JSONArray.objects(): Sequence<JSONObject> =
        (0 until length()).asSequence().mapNotNull { optJSONObject(it) }

    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            when (val value = opt(key)) {
                null, JSONObject.NULL -> null
                is Number -> value.toString()
                else -> value.toString().takeIf { it.isNotBlank() && it != "-" }
            }
        }

    private fun parseTimestamp(value: String?): Long {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return 0L
        text.toLongOrNull()?.let { raw ->
            return when {
                raw >= 100_000_000_000L -> raw
                raw > 0 -> raw * 1_000L
                else -> 0L
            }
        }
        listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss").forEach { pattern ->
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA)
                    .apply { timeZone = shanghaiTimeZone }
                    .parse(text)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    private fun normalizeUrl(value: String?): String? {
        val text = value?.trim().orEmpty()
        return when {
            text.isBlank() -> null
            text.startsWith("//") -> "https:$text"
            else -> text
        }
    }

    private fun isSameLocalDay(timestampMs: Long, nowMs: Long): Boolean {
        if (timestampMs <= 0L) return false
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { timeZone = shanghaiTimeZone }
        return formatter.format(timestampMs) == formatter.format(nowMs)
    }
}
