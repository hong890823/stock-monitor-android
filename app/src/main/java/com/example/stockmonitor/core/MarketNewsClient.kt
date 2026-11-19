package com.example.stockmonitor.core

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

enum class NewsSource(val label: String) {
    WALLSTREETCN("华尔街见闻"),
    EASTMONEY("东方财富"),
    SINA("新浪财经"),
}

enum class NewsTrack(val label: String) {
    INVESTMENT("投资要闻"),
    TECHNOLOGY("科技重点"),
}

data class NewsSourceProfile(
    val id: String,
    val label: String,
    val track: NewsTrack,
    val trustScore: Int,
    val url: String,
    /** 同一新闻聚类中的展示优先级。它与来源可信度分开，数值越大越优先。 */
    val sourcePriority: Int = 0,
)

data class NewsItem(
    val title: String,
    val publishedAtMs: Long,
    val source: String,
    val url: String? = null,
    val rawCategory: String? = null,
    val sourceId: String = "",
    val track: NewsTrack = NewsTrack.INVESTMENT,
    val trustScore: Int = 50,
    /** 与 [NewsSourceProfile.sourcePriority] 对应，避免将接口首选顺序伪装成可信度。 */
    val sourcePriority: Int = 0,
)

data class NewsSourceAttempt(
    val profile: NewsSourceProfile,
    val result: Result<List<NewsItem>>,
)

data class NewsSourceFailure(
    val sourceId: String,
    val label: String,
    val message: String,
)

data class NewsFetchResult(
    val items: List<NewsItem>,
    val failures: List<NewsSourceFailure>,
    val successfulSourceCount: Int,
) {
    val allSourcesFailed: Boolean
        get() = successfulSourceCount == 0
}

object MarketNewsClient {
    private const val WALLSTREETCN_URL =
        "https://api-one.wallstcn.com/apiv1/content/lives?channel=global-channel&limit=50&score=2"
    private const val EASTMONEY_URL =
        "https://np-listapi.eastmoney.com/comm/web/getNewsByColumns?client=web&biz=web_news&column=350&order=1&needInteractData=0&page_index=1&page_size=50"
    private const val SINA_URL =
        "https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=2516&k=&num=50&page=1"
    private val shanghaiTimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    val sourceProfiles: List<NewsSourceProfile> = listOf(
        // 仅请求华尔街见闻“只看重要”快讯；priority 是首选展示顺序，不是内容可信度评分。
        NewsSourceProfile("wallstreetcn", NewsSource.WALLSTREETCN.label, NewsTrack.INVESTMENT, 75, WALLSTREETCN_URL, 100),
        NewsSourceProfile("eastmoney", "东方财富", NewsTrack.INVESTMENT, 68, EASTMONEY_URL),
        NewsSourceProfile("sina", "新浪财经", NewsTrack.INVESTMENT, 65, SINA_URL),
        NewsSourceProfile("infoq_cn", "InfoQ 中文", NewsTrack.TECHNOLOGY, 82, "https://www.infoq.cn/feed/"),
        NewsSourceProfile("jiqizhixin", "机器之心", NewsTrack.TECHNOLOGY, 84, "https://www.jiqizhixin.com/rss"),
        NewsSourceProfile("kr36", "36氪", NewsTrack.TECHNOLOGY, 72, "https://36kr.com/feed"),
        NewsSourceProfile("techmeme", "Techmeme", NewsTrack.TECHNOLOGY, 88, "https://www.techmeme.com/feed.xml"),
    )

    suspend fun today(nowMs: Long): NewsFetchResult = recent(nowMs, maxAgeDays = 1)

    suspend fun recent(nowMs: Long, maxAgeDays: Int): NewsFetchResult {
        val attempts = coroutineScope {
            sourceProfiles.map { profile ->
                async(Dispatchers.IO) {
                    NewsSourceAttempt(profile, runCatching { fetch(profile) })
                }
            }.awaitAll()
        }
        val merged = mergeAttempts(attempts, nowMs, maxAgeDays)
        if (merged.allSourcesFailed) {
            throw Http.HttpError(
                merged.failures.joinToString("；", prefix = "新闻来源全部失败：") { "${it.label}：${it.message}" },
            )
        }
        return merged
    }

    fun recentBlocking(nowMs: Long, maxAgeDays: Int): NewsFetchResult =
        runBlocking { recent(nowMs, maxAgeDays) }

    fun mergeAttempts(
        attempts: List<NewsSourceAttempt>,
        nowMs: Long,
        maxAgeDays: Int = 1,
    ): NewsFetchResult {
        val failures = attempts.mapNotNull { attempt ->
            attempt.result.exceptionOrNull()?.let { error ->
                NewsSourceFailure(
                    sourceId = attempt.profile.id,
                    label = attempt.profile.label,
                    message = error.message ?: error.javaClass.simpleName,
                )
            }
        }
        val items = attempts.flatMap { attempt ->
            attempt.result.getOrDefault(emptyList())
        }.filter { item -> isWithinLocalDays(item.publishedAtMs, nowMs, maxAgeDays) }
        return NewsFetchResult(
            items = items.sortedByDescending { it.publishedAtMs },
            failures = failures,
            successfulSourceCount = attempts.count { it.result.isSuccess },
        )
    }

    /**
     * AI 综合分析和持仓复盘每次均单独调用此方法，避免依赖今日要闻的刷新缓存。
     * 这里只返回接口已筛选 score=2 的重要快讯，并再次校验响应中的 score，防止上游静默忽略参数。
     */
    fun wallstreetcnImportant(nowMs: Long, maxAgeDays: Int = 1): List<NewsItem> {
        val profile = sourceProfiles.first { it.id == "wallstreetcn" }
        return fetch(profile)
            .filter { isWithinLocalDays(it.publishedAtMs, nowMs, maxAgeDays) }
            .sortedByDescending { it.publishedAtMs }
    }

    /** 用于 AI 综合分析的实时重要快讯上下文。 */
    fun wallstreetcnImportantSummary(nowMs: Long, maxItems: Int = 8): String =
        formatWallstreetcnImportantNews(wallstreetcnImportant(nowMs), maxItems)

    private fun formatWallstreetcnImportantNews(
        items: List<NewsItem>,
        maxItems: Int,
        noMatchMessage: String = "接口未返回可用的重要快讯。",
    ): String = buildString {
        appendLine("来源：华尔街见闻 7×24 快讯（只看重要，score=2）。")
        if (items.isEmpty()) {
            append(noMatchMessage)
        } else {
            items.take(maxItems.coerceIn(1, 10)).forEach { item ->
                append("- ${formatWallstreetcnTime(item.publishedAtMs)}｜${item.title}")
                item.url?.let { append("｜$it") }
                appendLine()
            }
        }
    }.trim()

    private fun formatWallstreetcnTime(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            .apply { timeZone = shanghaiTimeZone }
            .format(timestampMs)

    private fun fetch(profile: NewsSourceProfile): List<NewsItem> = when (profile.id) {
        "wallstreetcn" -> parseWallstreetcn(
            Http.getText(profile.url, referer = "https://wallstreetcn.com/live/global"),
        ).withProfile(profile)
        "eastmoney" -> parseEastMoney(
            Http.getText(profile.url, referer = "https://finance.eastmoney.com/"),
        ).withProfile(profile)
        "sina" -> parseSina(
            Http.getText(profile.url, referer = "https://finance.sina.com.cn/"),
        ).withProfile(profile)
        else -> TechNewsFeedParser.parse(
            Http.getText(profile.url, referer = profile.url),
            profile,
        )
    }

    private fun List<NewsItem>.withProfile(profile: NewsSourceProfile): List<NewsItem> = map { item ->
        item.copy(
            sourceId = profile.id,
            track = profile.track,
            trustScore = profile.trustScore,
            sourcePriority = profile.sourcePriority,
        )
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

    fun parseWallstreetcn(text: String): List<NewsItem> {
        val root = parseJson(text)
        require(root.optInt("code") == 20000) { "华尔街见闻快讯返回失败：${root.optString("message", "未知错误")}" }
        val rows = firstArray(root, "data.items")
        return rows.objects().mapNotNull { row ->
            // 接口部分 live 的 title 为空，正文首行才是唯一可展示的标题。
            val content = row.firstString("content_text", "content")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val title = row.firstString("title", "highlight_title")?.trim().takeUnless { it.isNullOrBlank() }
                ?: content.take(220)
            val publishedAtMs = parseTimestamp(row.firstString("display_time"))
            if (row.optInt("score") != 2 || title.isBlank() || publishedAtMs <= 0L) {
                null
            } else {
                NewsItem(
                    title = title,
                    publishedAtMs = publishedAtMs,
                    source = NewsSource.WALLSTREETCN.label,
                    url = normalizeUrl(row.firstString("uri")),
                    rawCategory = row.optJSONArray("channels")?.let { channels ->
                        (0 until channels.length()).mapNotNull { index ->
                            channels.optString(index).trim().takeIf { it.isNotBlank() }
                        }.joinToString(",").takeIf { it.isNotBlank() }
                    },
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

    private fun isWithinLocalDays(timestampMs: Long, nowMs: Long, maxAgeDays: Int): Boolean {
        if (timestampMs <= 0L || nowMs <= 0L) return false
        val calendar = Calendar.getInstance(shanghaiTimeZone)
        calendar.timeInMillis = nowMs
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val oldestDayStart = calendar.timeInMillis -
            (maxAgeDays.coerceIn(1, 30) - 1).toLong() * 24L * 60L * 60L * 1_000L
        val todayEndExclusive = calendar.timeInMillis + 24L * 60L * 60L * 1_000L
        return timestampMs in oldestDayStart until todayEndExclusive
    }
}
