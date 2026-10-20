package com.example.stockmonitor.ui

import com.example.stockmonitor.core.BoardMember
import com.example.stockmonitor.core.LeaderOverrides

object BoardLeaderEditorPolicy {
    data class GroupRow(
        val groupId: String,
        val name: String,
        val holdingNames: List<String>,
    )

    data class Candidate(
        val member: BoardMember,
        val checked: Boolean,
    )

    fun editableGroups(rows: List<GroupRow>): List<GroupRow> = rows

    fun candidates(
        members: List<BoardMember>,
        currentLeaders: List<BoardMember>,
        overrides: LeaderOverrides,
    ): List<Candidate> {
        val all = LinkedHashMap<String, BoardMember>()
        currentLeaders.forEach { all[it.code] = it }
        members.forEach { all.putIfAbsent(it.code, it) }
        overrides.pinned.forEach { (code, name) ->
            all.putIfAbsent(code, BoardMember(code, name, 0.0))
        }
        val currentCodes = currentLeaders.map { it.code }.toSet()
        return all.values.map { member ->
            val checked = (member.code in currentCodes && member.code !in overrides.excluded) ||
                member.code in overrides.pinned
            Candidate(member, checked)
        }
    }

    fun canOpenEditor(rows: List<GroupRow>): Boolean = true
}
