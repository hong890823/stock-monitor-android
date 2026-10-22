package com.example.stockmonitor.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandableRowStateTest {

    @Test
    fun rowsStartCollapsedAndToggleIndependently() {
        val state = ExpandableRowState()

        assertFalse(state.isExpanded("market:600000"))
        assertTrue(state.toggle("market:600000"))
        assertTrue(state.isExpanded("market:600000"))
        assertFalse(state.isExpanded("portfolio:600000"))
        assertFalse(state.toggle("market:600000"))
        assertFalse(state.isExpanded("market:600000"))
    }

    @Test
    fun rowsToggleAsAGroupButKeepIndividualState() {
        val state = ExpandableRowState()
        val keys = listOf("market:600000", "market:000001")

        assertTrue(state.toggleAll(keys))
        assertTrue(state.allExpanded(keys))
        state.toggle("market:600000")
        assertFalse(state.allExpanded(keys))
        assertTrue(state.toggleAll(keys))
        assertTrue(state.allExpanded(keys))
        assertFalse(state.toggleAll(keys))
        assertFalse(state.isExpanded("market:600000"))
        assertFalse(state.isExpanded("market:000001"))
    }
}
