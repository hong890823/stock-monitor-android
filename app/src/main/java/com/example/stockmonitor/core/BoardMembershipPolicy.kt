package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.BoardTag

object BoardMembershipPolicy {
    const val DEFAULT_MAX_FETCHES = 20

    data class Stock(val code: String, val name: String)

    data class Result(
        val boardsByCode: Map<String, List<String>>,
        val entries: Map<String, BoardTagCache.Entry>,
        val refreshedCodes: List<String>,
    )

    fun resolve(
        stocks: List<Stock>,
        leaderBoardsByName: Map<String, List<String>>,
        cachedEntries: Map<String, BoardTagCache.Entry>,
        nowMs: Long,
        maxFetches: Int = DEFAULT_MAX_FETCHES,
        fetchTags: (String) -> List<BoardTag>,
    ): Result {
        val entries = cachedEntries.toMutableMap()
        val refreshedCodes = mutableListOf<String>()
        val boardsByCode = LinkedHashMap<String, List<String>>()
        stocks.distinctBy { it.code }.forEach { stock ->
            var entry = entries[stock.code]
            if (!BoardTagCache.isFresh(entry, nowMs) &&
                refreshedCodes.size < maxFetches.coerceAtLeast(0)
            ) {
                val fetched = fetchTags(stock.code)
                if (fetched.isNotEmpty()) {
                    entry = BoardTagCache.Entry(fetched, nowMs)
                    entries[stock.code] = entry
                    refreshedCodes += stock.code
                }
            }
            boardsByCode[stock.code] = (
                leaderBoardsByName[stock.name].orEmpty() +
                    entry?.tags.orEmpty().map { it.name }
                ).filter { it.isNotBlank() }.distinct()
        }
        return Result(boardsByCode, entries, refreshedCodes)
    }
}
