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
                    item.technicalScore?.let { put("technical_score", it) }
                    item.weeklyTrendLabel?.let { put("weekly_trend", it) }
                    item.monthlyTrendLabel?.let { put("monthly_trend", it) }
                    put("weak_days", item.weakDays)
                    item.weakDaysSettledDate?.let { put("weak_days_settled_date", it) }
                    put("daily_base_entry_confirmed", item.dailyBaseEntryConfirmed)
                    put("structure_quality", item.structureQuality.name)
                    item.relativeStrengthPct?.let { put("relative_strength_pct", it) }
                    item.marketRisk?.let { put("market_risk", it.name) }
                    put("observed_days", item.observedDays)
                    put("post_confirmation_watch_days", item.postConfirmationWatchDays)
                    put("last_price", item.lastPrice)
                    put("stop_price", item.stopPrice)
                    put("source", item.source)
                    put("sources", JSONArray(item.sources.toList()))
                    put("source_valid_trading_days", item.sourceValidTradingDays)
                    put("source_observed_days", item.sourceObservedDays)
                    put("reason", item.reason)
                    put("strategy", item.strategy.name)
                    put("continuous_observation", item.continuousObservation)
                    put("limit_up_blocked", item.limitUpBlocked)
                    put("limit_up_shrink_acceleration", item.limitUpShrinkAcceleration)
                    put("entry_confirmation_rounds", item.entryConfirmationRounds)
                    put("entry_confirmation_required_rounds", item.entryConfirmationRequiredRounds)
                    put("entry_confirmed_at_ms", item.entryConfirmedAtMs)
                    put("entry_confirmation_volume", item.entryConfirmationVolume)
                    put("entry_trigger_price", item.entryTriggerPrice)
                    put("entry_trigger_at_ms", item.entryTriggerAtMs)
                    item.highestStage?.let { put("highest_stage", it.name) }
                    item.highestStageDate?.let { put("highest_stage_date", it) }
                    put("highest_stage_at_ms", item.highestStageAtMs)
                    put("soft_risk_rounds", item.softRiskRounds)
                    put("soft_risk_last_confirmed_at_ms", item.softRiskLastConfirmedAtMs)
                    put("source_expires_at_ms", item.sourceExpiresAtMs)
                    put("post_limit_hold_started_at_ms", item.postLimitHoldStartedAtMs)
                    put("post_limit_hold_low_price", item.postLimitHoldLowPrice)
                    put("alert_state", item.alertState.name)
                    put("alert_detail", item.alertDetail)
                    put("alert_updated_at_ms", item.alertUpdatedAtMs)
                    item.limitStreak?.let { put("limit_streak", it) }
                    item.firstBoardDate?.let { put("first_board_date", it) }
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
                    put("risk_policy_version", item.riskPolicyVersion)
                    put("risk_signals", JSONArray().apply {
                        item.riskSignals.forEach { signal ->
                            put(JSONObject().apply {
                                put("type", signal.type.name)
                                put("detail", signal.detail)
                                signal.keyLevel?.let { put("key_level", it) }
                                signal.currentValue?.let { put("current_value", it) }
                                signal.threshold?.let { put("threshold", it) }
                            })
                        }
                    })
                    put("status_changes", JSONArray().apply {
                        item.statusChanges.forEach { change ->
                            put(JSONObject().apply {
                                put("date", change.date)
                                put("at_ms", change.atMs)
                                put("previous_stage", change.previousStage?.name)
                                put("stage", change.stage.name)
                                put("reason", change.reason)
                            })
                        }
                    })
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
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                runCatching {
                    val source = item.optString("source", "选股")
                    val sources = item.optJSONArray("sources")?.let { encoded ->
                        (0 until encoded.length()).mapNotNull { sourceIndex ->
                            encoded.optString(sourceIndex).takeIf { it.isNotBlank() }
                        }
                    }?.toCollection(linkedSetOf()).orEmpty()
                    val hasTradingDayValidity = item.has("source_valid_trading_days")
                    val legacyPatternSource = !hasTradingDayValidity &&
                        (source == LEGACY_PATTERN_SOURCE || LEGACY_PATTERN_SOURCE in sources)
                    ObservationCandidate(
                        code = item.optString("code"),
                        name = item.optString("name"),
                        addedDate = item.optString("added_date"),
                        lastEvaluatedDate = item.optString("last_evaluated_date").takeIf { it.isNotBlank() },
                        stage = runCatching {
                            ObservationStage.valueOf(item.optString("stage"))
                        }.getOrDefault(ObservationStage.OBSERVING),
                        score = item.optInt("score", 0),
                        technicalScore = if (item.has("technical_score")) item.optInt("technical_score") else null,
                        weeklyTrendLabel = item.optString("weekly_trend").takeIf { it.isNotBlank() },
                        monthlyTrendLabel = item.optString("monthly_trend").takeIf { it.isNotBlank() },
                        weakDays = item.optInt("weak_days", 0),
                        weakDaysSettledDate = item.optString("weak_days_settled_date").takeIf { it.isNotBlank() },
                        dailyBaseEntryConfirmed = item.optBoolean("daily_base_entry_confirmed", false),
                        structureQuality = item.optString("structure_quality").takeIf { it.isNotBlank() }?.let { quality ->
                            runCatching { ObservationStructureQuality.valueOf(quality) }
                                .getOrDefault(ObservationStructureQuality.NONE)
                        } ?: ObservationStructureQuality.NONE,
                        relativeStrengthPct = item.optDouble("relative_strength_pct", Double.NaN)
                            .takeUnless { it.isNaN() },
                        marketRisk = item.optString("market_risk").takeIf { it.isNotBlank() }?.let { risk ->
                            runCatching { ObservationMarketRisk.valueOf(risk) }.getOrNull()
                        },
                        observedDays = item.optInt("observed_days", 0),
                        postConfirmationWatchDays = item.optInt("post_confirmation_watch_days", 0),
                        lastPrice = item.optDouble("last_price", 0.0),
                        stopPrice = item.optDouble("stop_price", Double.NaN).takeUnless { it.isNaN() },
                        source = source,
                        sources = sources,
                        sourceValidTradingDays = when {
                            legacyPatternSource -> LEGACY_PATTERN_VALID_TRADING_DAYS
                            else -> item.optInt("source_valid_trading_days", 0)
                        },
                        sourceObservedDays = item.optInt("source_observed_days", 0),
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
                        entryConfirmationRequiredRounds = item.optInt("entry_confirmation_required_rounds", 0),
                        entryConfirmedAtMs = item.optLong("entry_confirmed_at_ms", 0L),
                        entryConfirmationVolume = item.optDouble("entry_confirmation_volume", 0.0),
                        entryTriggerPrice = item.optDouble("entry_trigger_price", 0.0),
                        entryTriggerAtMs = item.optLong("entry_trigger_at_ms", 0L),
                        highestStage = item.optString("highest_stage").takeIf { it.isNotBlank() }?.let { stageName ->
                            runCatching { ObservationStage.valueOf(stageName) }.getOrNull()
                        },
                        highestStageDate = item.optString("highest_stage_date").takeIf { it.isNotBlank() },
                        highestStageAtMs = item.optLong("highest_stage_at_ms", 0L),
                        softRiskRounds = item.optInt("soft_risk_rounds", 0),
                        softRiskLastConfirmedAtMs = item.optLong("soft_risk_last_confirmed_at_ms", 0L),
                        sourceExpiresAtMs = when {
                            legacyPatternSource -> 0L
                            else -> item.optLong("source_expires_at_ms", 0L)
                        },
                        postLimitHoldStartedAtMs = item.optLong("post_limit_hold_started_at_ms", 0L),
                        postLimitHoldLowPrice = item.optDouble("post_limit_hold_low_price", 0.0),
                        alertState = item.optString("alert_state").takeIf { it.isNotBlank() }?.let { state ->
                            runCatching { ObservationAlertState.valueOf(state) }
                                .getOrDefault(ObservationAlertState.UNKNOWN)
                        } ?: ObservationAlertState.UNKNOWN,
                        alertDetail = item.optString("alert_detail"),
                        alertUpdatedAtMs = item.optLong("alert_updated_at_ms", 0L),
                        limitStreak = if (item.has("limit_streak")) item.optInt("limit_streak") else null,
                        firstBoardDate = item.optString("first_board_date").takeIf { it.isNotBlank() },
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
                        riskPolicyVersion = item.optInt("risk_policy_version", 0),
                        riskSignals = item.optJSONArray("risk_signals")?.let { signals ->
                            (0 until signals.length()).mapNotNull { index ->
                                signals.optJSONObject(index)?.let { signal ->
                                    val type = runCatching {
                                        ObservationRiskType.valueOf(signal.optString("type"))
                                    }.getOrNull() ?: return@let null
                                    ObservationRiskSignal(
                                        type = type,
                                        detail = signal.optString("detail"),
                                        keyLevel = signal.optDouble("key_level", Double.NaN)
                                            .takeUnless { it.isNaN() },
                                        currentValue = signal.optDouble("current_value", Double.NaN)
                                            .takeUnless { it.isNaN() },
                                        threshold = signal.optDouble("threshold", Double.NaN)
                                            .takeUnless { it.isNaN() },
                                    )
                                }
                            }
                        }.orEmpty(),
                        statusChanges = item.optJSONArray("status_changes")?.let { changes ->
                            (0 until changes.length()).mapNotNull { changeIndex ->
                                changes.optJSONObject(changeIndex)?.let { change ->
                                    ObservationStatusChange(
                                        date = change.optString("date"),
                                        atMs = change.optLong("at_ms", 0L),
                                        previousStage = change.optString("previous_stage").takeIf { it.isNotBlank() }
                                            ?.let { stageName ->
                                                runCatching { ObservationStage.valueOf(stageName) }.getOrNull()
                                            },
                                        stage = runCatching {
                                            ObservationStage.valueOf(change.optString("stage"))
                                        }.getOrDefault(ObservationStage.OBSERVING),
                                        reason = change.optString("reason"),
                                    )
                                }
                            }
                        }.orEmpty(),
                    )
                }.getOrElse {
                    // 单条损坏只跳过该条，不能让整个观察池被清空后又被下一次写入覆盖。
                    null
                }
            }.filterNotNull().filter { it.code.isNotBlank() }.toMutableList()
        }.getOrElse {
            // 整表 JSON 损坏（无法逐条恢复）时返回空列表；单条损坏已在上面的逐条容错中跳过。
            mutableListOf()
        }
    }

    private const val LEGACY_PATTERN_SOURCE = "513形态"
    private const val LEGACY_PATTERN_VALID_TRADING_DAYS = 3
}
