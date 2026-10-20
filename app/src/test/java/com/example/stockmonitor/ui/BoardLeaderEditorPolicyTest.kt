package com.example.stockmonitor.ui

import com.example.stockmonitor.core.BoardMember
import com.example.stockmonitor.core.LeaderOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardLeaderEditorPolicyTest {

    @Test
    fun leaderEditingIncludesGroupsThatHaveNoCurrentHoldings() {
        val rows = listOf(
            BoardLeaderEditorPolicy.GroupRow(
                groupId = "BK0438",
                name = "光通信",
                holdingNames = emptyList(),
            ),
        )

        val editable = BoardLeaderEditorPolicy.editableGroups(rows)

        assertEquals(rows, editable)
        assertTrue(BoardLeaderEditorPolicy.canOpenEditor(rows))
    }

    @Test
    fun editorCandidatesFallBackToKnownLeadersAndManualOverrides() {
        val candidates = BoardLeaderEditorPolicy.candidates(
            members = emptyList(),
            currentLeaders = listOf(BoardMember("600522", "中天科技", 1.2)),
            overrides = LeaderOverrides(pinned = mapOf("600487" to "亨通光电")),
        )

        assertEquals(listOf("600522", "600487"), candidates.map { it.member.code })
        assertEquals(listOf(true, true), candidates.map { it.checked })
        assertTrue(BoardLeaderEditorPolicy.canOpenEditor(emptyList()))
    }
}
