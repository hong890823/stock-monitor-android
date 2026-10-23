package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.BreakoutRiskDecision
import org.json.JSONArray
import org.json.JSONObject

object ObservationPoolJson {
    fun encode(items: List<ObservationCandidate>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("code", item.code)
                    put("name", item.name)
                    put("added_date", item.addedDate)
                    put("last_evaluated_date", item.lastEvaluatedDate)
                    put("stage", item.stage.name)
                    put("score", item.score)
                    put("weak_days", item.weakDays)
                    put("observed_days", item.observedDays)
                    put("last_price", item.lastPrice)
                    put("stop_price", item.stopPrice)
                    put("source", item.source)
                    put("reason", item.reason)
                    put("strategy", item.strategy.name)
                    put("continuous_observation", item.continuousObservation)
                    put("limit_up_blocked", item.limitUpBlocked)
                    put("limit_up_shrink_acceleration", item.limitUpShrinkAcceleration)
                    put("entry_confirmation_rounds", item.entryConfirmationRounds)
                    put("entry_confirmed_at_ms", item.entryConfirmedAtMs)
                    put("entry_confirmation_volume", item.entryConfirmationVolume)
                    item.limitStreak?.let { put("limit_streak", it) }
                    item.sealQualityScore?.let { put("seal_quality_score", it) }
                    item.sealQualityCoveragePct?.let { put("seal_quality_coverage_pct", it) }
                    put("boards", JSONArray(item.boards))
                    item.breakoutRisk?.let { risk ->
                        put("breakout_risk", JSONObject().apply {
                            put("risk", risk.risk)
                            put("reason", risk.reason)
                            risk.breakoutReference?.let { put("breakout_reference", it) }
                        })
                    }
                    ObservationEvidenceJson.put(this, "supporting", item.evidence.supporting)
                    ObservationEvidenceJson.put(this, "counter", item.evidence.counter)
                    ObservationEvidenceJson.put(this, "missing", item.evidence.missing)
                    ObservationEvidenceJson.put(this, "invalidators", item.evidence.invalidators)
                }
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): MutableList<ObservationCandidate> {
        if (raw.isNullOrBlank()) return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                ObservationCandidate(
                    code = item.optString("code"),
                    name = item.optString("name"),
                    addedDate = item.optString("added_date"),
                    lastEvaluatedDate = item.optString("last_evaluated_date").takeIf { it.isNotBlank() },
                    stage = runCatching {
                        ObservationStage.valueOf(item.optString("stage"))
                    }.getOrDefault(ObservationStage.OBSERVING),
                    score = item.optInt("score", 0),
                    weakDays = item.optInt("weak_days", 0),
                    observedDays = item.optInt("observed_days", 0),
                    lastPrice = item.optDouble("last_price", 0.0),
                    stopPrice = item.optDouble("stop_price", Double.NaN).takeUnless { it.isNaN() },
                    source = item.optString("source", "选股"),
                    reason = item.optString("reason"),
                    strategy = runCatching {
                        ObservationStrategy.valueOf(item.optString("strategy"))
                    }.getOrDefault(ObservationStrategy.PRE_BREAKOUT),
                    continuousObservation = item.optBoolean("continuous_observation", false),
                    evidence = ObservationEvidenceJson.read(item),
                    limitUpBlocked = item.optBoolean("limit_up_blocked", item.optString("reason").contains("涨停封板")),
                    limitUpShrinkAcceleration = item.optBoolean(
                        "limit_up_shrink_acceleration", item.optString("reason").contains("涨停缩量加速"),
                    ),
                    entryConfirmationRounds = item.optInt("entry_confirmation_rounds", 0),
                    entryConfirmedAtMs = item.optLong("entry_confirmed_at_ms", 0L),
                    entryConfirmationVolume = item.optDouble("entry_confirmation_volume", 0.0),
                    limitStreak = if (item.has("limit_streak")) item.optInt("limit_streak") else null,
                    sealQualityScore = if (item.has("seal_quality_score")) {
                        item.optInt("seal_quality_score")
                    } else {
                        null
                    },
                    sealQualityCoveragePct = if (item.has("seal_quality_coverage_pct")) {
                        item.optInt("seal_quality_coverage_pct")
                    } else {
                        null
                    },
                    boards = item.optJSONArray("boards")?.let { boards ->
                        (0 until boards.length()).mapNotNull { index ->
                            boards.optString(index).takeIf { it.isNotBlank() }
                        }
                    }.orEmpty(),
                    breakoutRisk = item.optJSONObject("breakout_risk")?.let { risk ->
                        BreakoutRiskDecision(
                            risk = risk.optBoolean("risk", false),
                            reason = risk.optString("reason"),
                            breakoutReference = risk.optDouble("breakout_reference", Double.NaN)
                                .takeUnless { it.isNaN() },
                        )
                    },
                )
            }.filter { it.code.isNotBlank() }.toMutableList()
        }.getOrDefault(mutableListOf())
    }
}
