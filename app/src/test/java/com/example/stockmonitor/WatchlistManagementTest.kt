package com.example.stockmonitor

import com.example.stockmonitor.core.WatchItem
import com.example.stockmonitor.ui.WatchlistManagement
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchlistManagementTest {
    private val first = WatchItem("sh600000", "first", true)
    private val second = WatchItem("sz000001", "second", false)

    @Test
    fun setTopBarOnlyChangesRequestedStockWithoutReordering() {
        val original = listOf(first, second)
        assertEquals(listOf(first, second.copy(showInBar = true)),
            WatchlistManagement.setTopBar(original, second.code, true))
        assertEquals(listOf(first, second), original)
        assertEquals(original, WatchlistManagement.setTopBar(original, first.code, true))
    }

    @Test
    fun deletingFromCurrentListPreservesStocksAddedAfterDialogOpened() {
        val added = WatchItem("sh600001", "added", true)
        assertEquals(listOf(second, added), WatchlistManagement.remove(listOf(first, second, added), first.code))
    }

    @Test
    fun staleActionsDoNotRestoreAlreadyDeletedStocks() {
        assertEquals(listOf(second), WatchlistManagement.setTopBar(listOf(second), first.code, false))
        assertEquals(listOf(second), WatchlistManagement.remove(listOf(second), first.code))
        assertEquals(emptyList<WatchItem>(), WatchlistManagement.remove(listOf(first), first.code))
    }
}
