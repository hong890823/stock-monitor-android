package com.example.stockmonitor.ui

import com.example.stockmonitor.core.WatchItem

internal object WatchlistManagement {
    fun setTopBar(current: List<WatchItem>, code: String, enabled: Boolean): List<WatchItem> =
        current.map { if (it.code == code) it.copy(showInBar = enabled) else it }

    fun remove(current: List<WatchItem>, code: String): List<WatchItem> = current.filterNot { it.code == code }
}
