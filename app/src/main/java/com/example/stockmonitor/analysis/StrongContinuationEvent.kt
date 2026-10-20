package com.example.stockmonitor.analysis

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class StrongContinuationEvent(
    val date: String,
    val code: String,
    val name: String,
    val triggeredAt: String,
    val triggerPrice: Double,
    val currentPrice: Double,
    val predictedTurnoverYi: Double,
    val marketChangePct: Double,
    val sectorChangePct: Double,
    val environmentStatus: String = "强势延续",
    val environmentReasons: List<String> = emptyList(),
    val outcomeStatus: String = "观察中",
    val currentChangePct: Double = (currentPrice / triggerPrice - 1) * 100,
    val day1Price: Double? = null,
    val day3Price: Double? = null,
    val day5Price: Double? = null,
    val maxFavorablePct: Double = currentChangePct,
    val maxAdversePct: Double = currentChangePct,
    val observationDates: List<String> = emptyList(),
) {
    fun reportLine(): String =
        "$name（$code）：${triggeredAt.substringAfter(' ').ifBlank { triggeredAt }}触发，触发价 ${price(triggerPrice)}" +
            "；当时两市全天预估成交额 ${amount(predictedTurnoverYi)} 亿、沪指 ${pct(marketChangePct)}、所属板块 ${pct(sectorChangePct)}" +
            "；环境${environmentStatus}" +
            (environmentReasons.takeLast(2).takeIf { it.isNotEmpty() }?.let { "（${it.joinToString("；")}）" } ?: "") +
            "；当前价 ${price(currentPrice)}（较触发 ${pct(currentChangePct)}）；$outcomeStatus。"

    fun withObservation(price: Double, observedAt: String): StrongContinuationEvent {
        if (!price.isFinite() || price <= 0 || !triggerPrice.isFinite() || triggerPrice <= 0) return this
        val change = (price / triggerPrice - 1) * 100
        val date = observedAt.take(10)
        val dates = (listOf(this.date) + observationDates + date).distinct().sorted()
        val offset = dates.indexOf(date) - dates.indexOf(this.date)
        val day1 = if (offset == 1 && day1Price == null) price else day1Price
        val day3 = if (offset == 3 && day3Price == null) price else day3Price
        val day5 = if (offset == 5 && day5Price == null) price else day5Price
        return copy(
            currentPrice = price,
            currentChangePct = change,
            day1Price = day1,
            day3Price = day3,
            day5Price = day5,
            maxFavorablePct = maxOf(maxFavorablePct, change),
            maxAdversePct = minOf(maxAdversePct, change),
            outcomeStatus = "观察中",
            observationDates = dates,
        )
    }

    private fun price(value: Double) = String.format(Locale.CHINA, "%.2f", value)
    private fun amount(value: Double) = String.format(Locale.CHINA, "%.0f", value)
    private fun pct(value: Double) = String.format(Locale.CHINA, "%+.2f%%", value)

    companion object {
        fun encode(events: List<StrongContinuationEvent>): String = JSONArray().apply {
            events.forEach { event ->
                put(JSONObject()
                    .put("date", event.date)
                    .put("code", event.code)
                    .put("name", event.name)
                    .put("triggered_at", event.triggeredAt)
                    .put("trigger_price", event.triggerPrice)
                    .put("current_price", event.currentPrice)
                    .put("predicted_turnover_yi", event.predictedTurnoverYi)
                    .put("market_change_pct", event.marketChangePct)
                    .put("sector_change_pct", event.sectorChangePct)
                    .put("environment_status", event.environmentStatus)
                    .put("outcome_status", event.outcomeStatus)
                    .put("current_change_pct", event.currentChangePct)
                    .put("day_1_price", event.day1Price)
                    .put("day_3_price", event.day3Price)
                    .put("day_5_price", event.day5Price)
                    .put("max_favorable_pct", event.maxFavorablePct)
                    .put("max_adverse_pct", event.maxAdversePct)
                    .put("observation_dates", JSONArray(event.observationDates))
                    .put("environment_reasons", JSONArray(event.environmentReasons)))
            }
        }.toString()

        fun decode(raw: String?): List<StrongContinuationEvent> = runCatching {
            val array = JSONArray(raw ?: "[]")
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val code = item.optString("code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                StrongContinuationEvent(
                    date = item.optString("date"),
                    code = code,
                    name = item.optString("name", code),
                    triggeredAt = item.optString("triggered_at"),
                    triggerPrice = item.optDouble("trigger_price", Double.NaN),
                    currentPrice = item.optDouble("current_price", Double.NaN),
                    predictedTurnoverYi = item.optDouble("predicted_turnover_yi", Double.NaN),
                    marketChangePct = item.optDouble("market_change_pct", Double.NaN),
                    sectorChangePct = item.optDouble("sector_change_pct", Double.NaN),
                    environmentStatus = item.optString("environment_status", "强势延续"),
                    environmentReasons = item.optJSONArray("environment_reasons")?.let { array ->
                        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                    } ?: emptyList(),
                    outcomeStatus = item.optString("outcome_status", "观察中"),
                    currentChangePct = item.optDouble("current_change_pct", Double.NaN).takeIf { it.isFinite() }
                        ?: ((item.optDouble("current_price") / item.optDouble("trigger_price") - 1) * 100),
                    day1Price = item.optDouble("day_1_price", Double.NaN).takeIf { it.isFinite() },
                    day3Price = item.optDouble("day_3_price", Double.NaN).takeIf { it.isFinite() },
                    day5Price = item.optDouble("day_5_price", Double.NaN).takeIf { it.isFinite() },
                    maxFavorablePct = item.optDouble("max_favorable_pct", Double.NaN).takeIf { it.isFinite() }
                        ?: ((item.optDouble("current_price") / item.optDouble("trigger_price") - 1) * 100),
                    maxAdversePct = item.optDouble("max_adverse_pct", Double.NaN).takeIf { it.isFinite() }
                        ?: ((item.optDouble("current_price") / item.optDouble("trigger_price") - 1) * 100),
                    observationDates = item.optJSONArray("observation_dates")?.let { dates ->
                        (0 until dates.length()).mapNotNull { dates.optString(it).takeIf(String::isNotBlank) }
                    } ?: emptyList(),
                ).takeIf { event ->
                    event.date.isNotBlank() && event.triggeredAt.isNotBlank() &&
                        listOf(event.triggerPrice, event.currentPrice, event.predictedTurnoverYi,
                            event.marketChangePct, event.sectorChangePct).all { it.isFinite() }
                }
            }
        }.getOrDefault(emptyList())
    }
}
