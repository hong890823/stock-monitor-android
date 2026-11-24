package com.example.stockmonitor.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 首板承接转强（涨停晋级观察）的一次命中记录。
 *
 * 盘中满足条件时只落盘，不再推送通知；保留最近 [FirstBoardReclaimObservationBook.KEEP_DAYS] 天，
 * 盘后或进程重启后仍能在选股页「涨停晋级观察」卡片里复核并据此选股。
 */
data class FirstBoardReclaimObservation(
    val code: String,
    val name: String,
    /** 触发的交易日（yyyy-MM-dd）。 */
    val tradingDay: String,
    /** 触发时刻（yyyy-MM-dd HH:mm）。 */
    val detectedAt: String,
    /** 产生当前 1 连板的首板日（yyyy-MM-dd）。 */
    val firstBoardDate: String,
    /** 触发时距首板日的交易日数；交易日历缺失时为 null。 */
    val tradingDaysAfterBoard: Int? = null,
    val previousClose: Double,
    val open: Double,
    val low: Double,
    /** 触发时回收开盘价后的价格快照。 */
    val price: Double,
    /** 触发时收盘价相对动态 MA5 的乖离百分比；缺失表示当日缓存不足。 */
    val biasFromMa5Pct: Double? = null,
    /** 资金承接证据（当日/近5分钟/近15分钟主力净流入占比）。 */
    val fundFlowEvidence: String = "",
    val fundFlowReason: String = "",
    val boards: List<String> = emptyList(),
)

object FirstBoardReclaimObservationBook {
    /** 保留最近 14 个自然日（约 10 个交易日），足够覆盖盘后复盘和次日选股。 */
    const val KEEP_DAYS = 14
    private const val MAX_RECORDS = 200

    /**
     * 追加一条命中记录。
     *
     * 同一交易日同一代码只保留最新一次：资金过滤回滚后再次触发时，用新快照覆盖旧快照，
     * 而不是在卡片里堆出两行。随后按 [KEEP_DAYS] 清理过期记录，并限制总条数。
     */
    fun append(
        existing: List<FirstBoardReclaimObservation>,
        record: FirstBoardReclaimObservation,
        today: String,
    ): List<FirstBoardReclaimObservation> {
        val merged = existing.filterNot {
            it.code == record.code && it.tradingDay == record.tradingDay
        } + record
        val cutoff = runCatching { LocalDate.parse(today).minusDays(KEEP_DAYS.toLong()) }.getOrNull()
        val kept = merged.filter { item ->
            val day = runCatching { LocalDate.parse(item.tradingDay) }.getOrNull()
            day != null && (cutoff == null || !day.isBefore(cutoff))
        }
        return kept
            .sortedWith(compareBy({ it.tradingDay }, { it.detectedAt }))
            .takeLast(MAX_RECORDS)
    }

    fun encode(items: List<FirstBoardReclaimObservation>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("code", item.code)
                    put("name", item.name)
                    put("trading_day", item.tradingDay)
                    put("detected_at", item.detectedAt)
                    put("first_board_date", item.firstBoardDate)
                    item.tradingDaysAfterBoard?.let { put("trading_days_after_board", it) }
                    put("previous_close", item.previousClose)
                    put("open", item.open)
                    put("low", item.low)
                    put("price", item.price)
                    item.biasFromMa5Pct?.let { put("ma5_bias_pct", it) }
                    put("fund_flow_evidence", item.fundFlowEvidence)
                    put("fund_flow_reason", item.fundFlowReason)
                    put("boards", JSONArray(item.boards))
                },
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): MutableList<FirstBoardReclaimObservation> {
        if (raw.isNullOrBlank()) return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                runCatching {
                    val code = item.optString("code")
                    val tradingDay = item.optString("trading_day")
                    if (code.isBlank() || tradingDay.isBlank()) return@runCatching null
                    FirstBoardReclaimObservation(
                        code = code,
                        name = item.optString("name"),
                        tradingDay = tradingDay,
                        detectedAt = item.optString("detected_at"),
                        firstBoardDate = item.optString("first_board_date"),
                        tradingDaysAfterBoard = if (item.has("trading_days_after_board")) {
                            item.optInt("trading_days_after_board")
                        } else {
                            null
                        },
                        previousClose = item.optDouble("previous_close", 0.0),
                        open = item.optDouble("open", 0.0),
                        low = item.optDouble("low", 0.0),
                        price = item.optDouble("price", 0.0),
                        biasFromMa5Pct = item.optDouble("ma5_bias_pct", Double.NaN)
                            .takeUnless { it.isNaN() },
                        fundFlowEvidence = item.optString("fund_flow_evidence"),
                        fundFlowReason = item.optString("fund_flow_reason"),
                        boards = item.optJSONArray("boards")?.let { boards ->
                            (0 until boards.length()).mapNotNull { boardIndex ->
                                boards.optString(boardIndex).takeIf { it.isNotBlank() }
                            }
                        }.orEmpty(),
                    )
                }.getOrNull()
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }
}