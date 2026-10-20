package com.example.stockmonitor.core

import org.json.JSONArray
import org.json.JSONObject

/** 观察池候选的支持、反向、缺失和失效证据。 */
data class ObservationEvidence(
    val supporting: List<String> = emptyList(),
    val counter: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val invalidators: List<String> = emptyList(),
)

object ObservationEvidenceJson {
    fun put(target: JSONObject, key: String, values: List<String>) {
        target.put(key, JSONArray().apply { values.forEach(::put) })
    }

    fun get(source: JSONObject, key: String): List<String> {
        val array = source.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    fun read(source: JSONObject): ObservationEvidence = ObservationEvidence(
        supporting = get(source, "supporting"),
        counter = get(source, "counter"),
        missing = get(source, "missing"),
        invalidators = get(source, "invalidators"),
    )
}
