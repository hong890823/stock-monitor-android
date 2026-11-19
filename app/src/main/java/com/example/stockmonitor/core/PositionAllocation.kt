package com.example.stockmonitor.core

import org.json.JSONException
import org.json.JSONObject

enum class PositionAllocationMode {
    NORMAL,
    RISK_OBSERVATION,
}

data class PositionAllocationConfig(
    val mode: PositionAllocationMode,
    val baseQuantityTarget: Double,
) {
    init {
        require(baseQuantityTarget.isFinite() && baseQuantityTarget >= 0.0) {
            "底仓目标必须是大于等于 0 的有限数字"
        }
    }

    companion object {
        fun normal(baseQuantityTarget: Double): PositionAllocationConfig =
            PositionAllocationConfig(PositionAllocationMode.NORMAL, baseQuantityTarget)

        fun riskObservation(): PositionAllocationConfig =
            PositionAllocationConfig(PositionAllocationMode.RISK_OBSERVATION, 0.0)
    }
}

object PositionAllocationJson {
    fun encode(configs: Map<String, PositionAllocationConfig>): String {
        val root = JSONObject()
        configs.toSortedMap().forEach { (code, config) ->
            require(code.isNotBlank()) { "分仓配置缺少股票代码" }
            root.put(
                code,
                JSONObject()
                    .put("mode", config.mode.name)
                    .put("base_quantity_target", config.baseQuantityTarget),
            )
        }
        return root.toString()
    }

    fun decode(raw: String?): Map<String, PositionAllocationConfig> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = try {
            JSONObject(raw)
        } catch (error: JSONException) {
            throw IllegalArgumentException("分仓配置格式无效", error)
        }
        val result = linkedMapOf<String, PositionAllocationConfig>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val code = keys.next().trim()
            require(code.isNotBlank()) { "分仓配置缺少股票代码" }
            val item = root.optJSONObject(code)
                ?: throw IllegalArgumentException("分仓配置 $code 不是对象")
            val mode = runCatching {
                PositionAllocationMode.valueOf(item.getString("mode"))
            }.getOrElse { error ->
                throw IllegalArgumentException("分仓配置 $code 的模式无效", error)
            }
            val target = try {
                item.getDouble("base_quantity_target")
            } catch (error: Exception) {
                throw IllegalArgumentException("分仓配置 $code 的底仓目标无效", error)
            }
            result[code] = PositionAllocationConfig(mode, target)
        }
        return result
    }
}
