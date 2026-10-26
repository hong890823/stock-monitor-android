package com.example.stockmonitor.analysis

enum class NewsDetailFilter {
    ALL,
    INVESTMENT,
    TECHNOLOGY,
    HOLDING_RELATED,
}

enum class NewsDetailSort {
    SCORE,
    LATEST,
}

data class NewsDetailSummary(
    val total: Int,
    val investment: Int,
    val technology: Int,
    val holdingRelated: Int,
)

object NewsDetailPolicy {
    fun filter(
        items: List<SelectedHeadline>,
        filter: NewsDetailFilter,
    ): List<SelectedHeadline> = when (filter) {
        NewsDetailFilter.ALL -> items
        NewsDetailFilter.INVESTMENT -> items.filter { it.track == com.example.stockmonitor.core.NewsTrack.INVESTMENT }
        NewsDetailFilter.TECHNOLOGY -> items.filter { it.track == com.example.stockmonitor.core.NewsTrack.TECHNOLOGY }
        NewsDetailFilter.HOLDING_RELATED -> items.filter { it.score.focus > 0 }
    }

    fun sort(
        items: List<SelectedHeadline>,
        sort: NewsDetailSort,
    ): List<SelectedHeadline> = when (sort) {
        NewsDetailSort.SCORE -> items.sortedWith(
            compareByDescending<SelectedHeadline> { it.score.total }
                .thenByDescending { it.item.publishedAtMs }
                .thenBy { it.item.title },
        )
        NewsDetailSort.LATEST -> items.sortedByDescending { it.item.publishedAtMs }
    }

    fun summary(items: List<SelectedHeadline>): NewsDetailSummary =
        NewsDetailSummary(
            total = items.size,
            investment = items.count { it.track == com.example.stockmonitor.core.NewsTrack.INVESTMENT },
            technology = items.count { it.track == com.example.stockmonitor.core.NewsTrack.TECHNOLOGY },
            holdingRelated = items.count { it.score.focus > 0 },
        )
}
