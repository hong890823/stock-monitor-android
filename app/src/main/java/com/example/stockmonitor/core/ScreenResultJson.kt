package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.FiveOneThreeHit
import com.example.stockmonitor.analysis.FiveOneThreeStage
import com.example.stockmonitor.analysis.MarketFilter
import com.example.stockmonitor.analysis.PriceContext
import com.example.stockmonitor.analysis.PriceLocation
import org.json.JSONArray
import org.json.JSONObject

/**
 * 选股结果的落盘格式：夜间自动扫描成功一次、或手动扫描后写入，
 * 重启后选股页直接显示上次结果（带生成时间），不再扫一遍也不再空白。
 *
 * 两套刷新逻辑各有各的时间戳（指标两档 / 趋势+513）：快扫只刷新指标组，
 * 时间也只动指标组；超过 [STALE_RETENTION_MS] 的旧结果不再保留（自动清）。
 */
object ScreenResultJson {

    /** 旧命中的保留上限：最后一次真正刷新起超过这么久，「无命中则保留」不再生效。 */
    const val STALE_RETENTION_MS = 3L * 24 * 60 * 60 * 1000

    /** 该时间戳的内容还在保质期内吗？0（未知）按不保留处理，宁可空着不拿旧命中断章取义。 */
    fun isRetained(atMs: Long, nowMs: Long): Boolean =
        atMs > 0 && nowMs - atMs <= STALE_RETENTION_MS

    /** 编码为 {"indicator_at": ..., "pattern_at": ..., "result": {...}}。 */
    fun encode(cached: CachedScreen): String {
        val root = JSONObject()
        cached.indicatorAt?.let { root.put("indicator_at", it) }
        root.put("indicator_at_ms", cached.indicatorAtMs)
        cached.patternAt?.let { root.put("pattern_at", it) }
        root.put("pattern_at_ms", cached.patternAtMs)
        root.put("result", encodeResult(cached.result))
        return root.toString()
    }

    /** 解码；格式损坏时返回 null，调用方按「无缓存」处理。旧格式（单一 generated_at）退回两组同源。 */
    fun decode(raw: String?): CachedScreen? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            val legacyAt = root.optString("generated_at").takeIf { it.isNotBlank() }
            val legacyMs = legacyAt?.let { parseText(it) } ?: 0L
            CachedScreen(
                indicatorAt = root.optString("indicator_at").takeIf { it.isNotBlank() } ?: legacyAt,
                indicatorAtMs = root.optLong("indicator_at_ms").takeIf { it > 0 } ?: legacyMs,
                patternAt = root.optString("pattern_at").takeIf { it.isNotBlank() } ?: legacyAt,
                patternAtMs = root.optLong("pattern_at_ms").takeIf { it > 0 } ?: legacyMs,
                result = decodeResult(root.optJSONObject("result") ?: return null),
            )
        }.getOrNull()
    }

    /** 「yyyy-MM-dd HH:mm」→ 毫秒；解析失败返回 0（按过期处理）。 */
    fun parseText(text: String): Long =
        runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).parse(text)?.time ?: 0L
        }.getOrDefault(0L)

    private fun encodeResult(result: ScreenResult): JSONObject = JSONObject()
        .put("strict", encodeHits(result.strict))
        .put("relaxed", encodeHits(result.relaxed))
        .put("five_one_three", encodeFiveOneThree(result.fiveOneThree))
        .put("stats", encodeStats(result.fiveOneThreeStats))
        .put("trend", encodeTrend(result.trend))

    private fun decodeResult(json: JSONObject): ScreenResult = ScreenResult(
        strict = decodeHits(json.optJSONArray("strict")),
        relaxed = decodeHits(json.optJSONArray("relaxed")),
        fiveOneThree = decodeFiveOneThree(json.optJSONArray("five_one_three")),
        fiveOneThreeStats = decodeStats(json.optJSONObject("stats")),
        trend = decodeTrend(json.optJSONArray("trend")),
    )

    private fun encodeHits(hits: List<ScreenHit>): JSONArray = JSONArray().also { array ->
        hits.forEach { hit ->
            array.put(
                JSONObject()
                    .put("code", hit.code)
                    .put("name", hit.name)
                    // RSI 在平均跌幅为 0 时是 NaN（宽松档里合法存在），double 一律安全写入，
                    // 否则 org.json 对 NaN 直接抛异常，一次落盘失败会把整轮扫描结果带走。
                    .putIfFinite("price", hit.price)
                    .putIfFinite("rsi", hit.rsi)
                    .putIfFinite("k", hit.k)
                    .putIfFinite("d", hit.d)
                    .putIfFinite("dif", hit.dif)
                    .put("relaxed_score", hit.relaxedScore)
                    .put("macd_detail", hit.macdDetail)
                    .put("rsi_ok", hit.rsiOk)
                    .put("kdj_ok", hit.kdjOk)
                    .put("relaxed_trend_eligible", hit.relaxedTrendEligible)
                    .put("technical_score", hit.technicalScore)
                    .put("weekly_trend", hit.weeklyTrendLabel)
                    .put("monthly_trend", hit.monthlyTrendLabel)
                    .putIfFinite("change_pct", hit.changePct)
                    .putIfPresent("volume_ratio", hit.volumeRatio)
                    .putIfPresent("price_context", encodeContext(hit.priceContext)),
            )
        }
    }

    private fun decodeHits(array: JSONArray?): List<ScreenHit> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            ScreenHit(
                code = json.optString("code"),
                name = json.optString("name"),
                price = json.optDouble("price"),
                rsi = json.optDouble("rsi", Double.NaN),
                k = json.optDouble("k"),
                d = json.optDouble("d"),
                dif = json.optDouble("dif"),
                relaxedScore = json.optInt("relaxed_score"),
                macdDetail = json.optString("macd_detail"),
                rsiOk = json.optBoolean("rsi_ok"),
                kdjOk = json.optBoolean("kdj_ok"),
                relaxedTrendEligible = json.optBoolean("relaxed_trend_eligible", false),
                technicalScore = json.optInt("technical_score", json.optInt("relaxed_score") * 40 / 3),
                weeklyTrendLabel = json.optString("weekly_trend", "数据不足"),
                monthlyTrendLabel = json.optString("monthly_trend", "数据不足"),
                changePct = json.optDouble("change_pct", Double.NaN),
                volumeRatio = json.optDoubleOrNull("volume_ratio"),
                priceContext = decodeContext(json.optJSONObject("price_context")),
            )
        }
    }

    private fun encodeTrend(hits: List<TrendScreenHit>): JSONArray = JSONArray().also { array ->
        hits.forEach { hit ->
            array.put(
                JSONObject()
                    .put("code", hit.code)
                    .put("name", hit.name)
                    .putIfFinite("price", hit.price)
                    .putIfFinite("change_pct", hit.changePct)
                    .putIfPresent("volume_ratio", hit.volumeRatio)
                    .put("technical_score", hit.technicalScore)
                    .put("weekly_trend", hit.weeklyTrendLabel)
                    .put("monthly_trend", hit.monthlyTrendLabel)
                    .put("entry_setup", encodeSetup(hit.entrySetup)),
            )
        }
    }

    private fun decodeTrend(array: JSONArray?): List<TrendScreenHit> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val setup = decodeSetup(json.optJSONObject("entry_setup")) ?: return@mapNotNull null
            TrendScreenHit(
                code = json.optString("code"),
                name = json.optString("name"),
                price = json.optDouble("price"),
                changePct = json.optDouble("change_pct", Double.NaN),
                volumeRatio = json.optDoubleOrNull("volume_ratio"),
                technicalScore = json.optInt("technical_score", 40),
                weeklyTrendLabel = json.optString("weekly_trend", "数据不足"),
                monthlyTrendLabel = json.optString("monthly_trend", "数据不足"),
                entrySetup = setup,
            )
        }
    }

    private fun encodeSetup(setup: EntrySetup): JSONObject = JSONObject()
        .put("type", setup.type.name)
        .put("confirmed", setup.confirmed)
        .put("can_buy", setup.canBuy)
        .put("trend_weak", setup.trendWeak)
        .putIfPresent("prior_high", setup.priorHigh)
        .putIfPresent("ma20_slope_pct", setup.ma20SlopePct)
        .put("volume_confirmed", setup.volumeConfirmed)
        .put("market_filter", setup.marketFilter.name)
        .putIfPresent("stop_price", setup.stopPrice)
        .put("reason", setup.reason)

    private fun decodeSetup(json: JSONObject?): EntrySetup? {
        if (json == null) return null
        return runCatching {
            EntrySetup(
                type = EntrySetupType.valueOf(json.optString("type", EntrySetupType.NONE.name)),
                confirmed = json.optBoolean("confirmed"),
                canBuy = json.optBoolean("can_buy"),
                trendWeak = json.optBoolean("trend_weak"),
                priorHigh = json.optDoubleOrNull("prior_high"),
                ma20SlopePct = json.optDoubleOrNull("ma20_slope_pct"),
                volumeConfirmed = json.optBoolean("volume_confirmed"),
                marketFilter = MarketFilter.valueOf(
                    json.optString("market_filter", MarketFilter.NEUTRAL.name)
                ),
                stopPrice = json.optDoubleOrNull("stop_price"),
                reason = json.optString("reason"),
            )
        }.getOrNull()
    }

    private fun encodeFiveOneThree(hits: List<FiveOneThreeHit>): JSONArray = JSONArray().also { array ->
        hits.forEach { hit ->
            array.put(
                JSONObject()
                    .put("code", hit.code)
                    .put("name", hit.name)
                    .putIfFinite("price", hit.price)
                    .put("stage", hit.stage.name)
                    .put("progress", hit.progress)
                    .put("detail", hit.detail)
                    .putIfFinite("gain60_pct", hit.gain60Pct),
            )
        }
    }

    private fun decodeFiveOneThree(array: JSONArray?): List<FiveOneThreeHit> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                FiveOneThreeHit(
                    code = json.optString("code"),
                    name = json.optString("name"),
                    price = json.optDouble("price"),
                    stage = FiveOneThreeStage.valueOf(
                        json.optString("stage", FiveOneThreeStage.OBSERVING.name)
                    ),
                    progress = json.optString("progress"),
                    detail = json.optString("detail"),
                    gain60Pct = json.optDouble("gain60_pct"),
                )
            }.getOrNull()
        }
    }

    private fun encodeStats(stats: FiveOneThreeStats): JSONObject = JSONObject()
        .put("total", stats.totalStocks)
        .put("history_available", stats.historyAvailable)
        .put("historical_five_bullish", stats.historicalFiveBullish)
        .put("recent_five_bullish", stats.recentFiveBullish)
        .put("waiting_confirmation", stats.waitingConfirmation)
        .put("confirmed", stats.confirmed)

    private fun decodeStats(json: JSONObject?): FiveOneThreeStats {
        if (json == null) return FiveOneThreeStats()
        return FiveOneThreeStats(
            totalStocks = json.optInt("total"),
            historyAvailable = json.optInt("history_available"),
            historicalFiveBullish = json.optInt("historical_five_bullish"),
            recentFiveBullish = json.optInt("recent_five_bullish"),
            waitingConfirmation = json.optInt("waiting_confirmation"),
            confirmed = json.optInt("confirmed"),
        )
    }

    private fun encodeContext(context: PriceContext?): JSONObject? = context?.let {
        JSONObject()
            .put("location", it.location.name)
            .put("rebound_from_low", it.reboundFromLow)
    }

    private fun decodeContext(json: JSONObject?): PriceContext? {
        if (json == null) return null
        return runCatching {
            PriceContext(
                location = PriceLocation.valueOf(json.optString("location", PriceLocation.MID.name)),
                reboundFromLow = json.optDouble("rebound_from_low"),
            )
        }.getOrNull()
    }

    private fun JSONObject.putIfFinite(key: String, value: Double): JSONObject =
        if (value.isFinite()) put(key, value) else this

    private fun JSONObject.putIfPresent(key: String, value: Double?): JSONObject =
        if (value != null && value.isFinite()) put(key, value) else this

    private fun JSONObject.putIfPresent(key: String, value: JSONObject?): JSONObject =
        if (value != null) put(key, value) else this

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null
}

/** 落盘的选股结果：内容 + 两组各自的刷新时间（展示与过期判断用）。 */
data class CachedScreen(
    val indicatorAt: String?,
    val indicatorAtMs: Long,
    val patternAt: String?,
    val patternAtMs: Long,
    val result: ScreenResult,
)
