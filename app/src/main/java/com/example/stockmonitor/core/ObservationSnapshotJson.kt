package com.example.stockmonitor.core

import org.json.JSONArray
import org.json.JSONObject

object ObservationSnapshotJson {
    fun encode(items: List<ObservationSnapshot>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("code", item.code)
                    put("name", item.name)
                    put("date", item.date)
                    put("stage", item.stage.name)
                    put("strategy", item.strategy.name)
                    put("score", item.score)
                    put("price", item.price)
                    put("change_pct", item.changePct)
                    put("weak_days", item.weakDays)
                    put("observed_days", item.observedDays)
                    put("stop_price", item.stopPrice)
                    put("reason", item.reason)
                    put("highest_stage", item.highestStage.name)
                    put("highest_stage_date", item.highestStageDate)
                    put("highest_stage_at_ms", item.highestStageAtMs)
                    ObservationEvidenceJson.put(this, "supporting", item.evidence.supporting)
                    ObservationEvidenceJson.put(this, "counter", item.evidence.counter)
                    ObservationEvidenceJson.put(this, "missing", item.evidence.missing)
                    ObservationEvidenceJson.put(this, "invalidators", item.evidence.invalidators)
                },
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<ObservationSnapshot> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val code = item.optString("code")
                    val date = item.optString("date")
                    if (code.isBlank() || date.isBlank()) continue
                    add(
                        ObservationSnapshot(
                            code = code,
                            name = item.optString("name", code),
                            date = date,
                            stage = runCatching {
                                ObservationStage.valueOf(item.optString("stage"))
                            }.getOrDefault(ObservationStage.OBSERVING),
                            strategy = runCatching {
                                ObservationStrategy.valueOf(item.optString("strategy"))
                            }.getOrDefault(ObservationStrategy.PRE_BREAKOUT),
                            score = item.optInt("score", 0),
                            price = item.optDouble("price", 0.0),
                            changePct = item.optDouble("change_pct", 0.0),
                            weakDays = item.optInt("weak_days", 0),
                            observedDays = item.optInt("observed_days", 0),
                            stopPrice = item.optDouble("stop_price", Double.NaN)
                                .takeUnless { it.isNaN() },
                            reason = item.optString("reason"),
                            highestStage = runCatching {
                                ObservationStage.valueOf(item.optString("highest_stage"))
                            }.getOrDefault(
                                runCatching {
                                    ObservationStage.valueOf(item.optString("stage"))
                                }.getOrDefault(ObservationStage.OBSERVING),
                            ),
                            highestStageDate = item.optString("highest_stage_date")
                                .ifBlank { date },
                            highestStageAtMs = item.optLong("highest_stage_at_ms", 0L),
                            evidence = ObservationEvidenceJson.read(item),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
