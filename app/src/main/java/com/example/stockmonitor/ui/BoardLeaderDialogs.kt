package com.example.stockmonitor.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import com.example.stockmonitor.core.BoardMember
import com.example.stockmonitor.core.LeaderOverrides
import com.example.stockmonitor.core.SectorLeaders
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.monitor.MonitorService
import com.example.stockmonitor.monitor.Repo
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope

/** 「调整龙头」对话框链：只改龙头名单，不改股票的板块归属。 */
object BoardLeaderDialogs {
    fun openPicker(
        scope: CoroutineScope,
        context: Context,
        store: Store,
        onSaved: () -> Unit,
    ) {
        val rows =
            BoardGroupDialogs.displayGroups(store).map {
                BoardLeaderEditorPolicy.GroupRow(it.groupId, it.name, it.holdingNames)
            }
        if (!BoardLeaderEditorPolicy.canOpenEditor(rows)) return
        val editable = BoardLeaderEditorPolicy.editableGroups(rows)
        if (editable.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle("调整龙头")
                .setMessage("当前还没有可调整的板块。先在「板块管理」里新建板块或修正持仓归属，再回来指定龙头。")
                .setPositiveButton("去板块管理") { _, _ ->
                    BoardGroupDialogs.openManager(scope, context, store, onSaved)
                }.setNegativeButton("关闭", null)
                .show()
            return
        }
        when (editable.size) {
            1 -> {
                openEditor(scope, context, store, editable[0].groupId, editable[0].name, onSaved)
            }

            else -> {
                MaterialAlertDialogBuilder(context)
                    .setTitle("选择要调整的板块")
                    .setItems(editable.map { it.name }.toTypedArray()) { _, which ->
                        openEditor(scope, context, store, editable[which].groupId, editable[which].name, onSaved)
                    }.setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    fun openEditor(
        scope: CoroutineScope,
        context: Context,
        store: Store,
        groupId: String,
        groupName: String,
        onSaved: () -> Unit,
        extraMembers: Map<String, String> = emptyMap(),
        removedCodes: Set<String> = emptySet(),
    ) {
        val overrides = store.sectorLeaderOverrides[groupId] ?: LeaderOverrides()
        val policyCandidates =
            BoardLeaderEditorPolicy.candidates(
                members = Repo.sectorMembers[groupId].orEmpty(),
                currentLeaders = Repo.sectorLeaders[groupId]?.members.orEmpty(),
                overrides = overrides,
            )
        val universe = LinkedHashMap<String, BoardMember>()
        policyCandidates.forEach { universe[it.member.code] = it.member }
        extraMembers.forEach { (code, name) -> universe.putIfAbsent(code, BoardMember(code, name, 0.0)) }
        (removedCodes - extraMembers.keys).forEach { universe.remove(it) }
        val initialNames = universe.entries.associate { it.key to it.value.name }
        val checkedCodes = mutableSetOf<String>()
        policyCandidates.forEach { if (it.checked && it.member.code in universe) checkedCodes += it.member.code }
        checkedCodes += extraMembers.keys
        val deletedCodes = mutableSetOf<String>()

        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiKit.dp(context, 20), UiKit.dp(context, 8), UiKit.dp(context, 20), 0)
            }
        container.addView(
            MaterialButton(context).apply {
                text = "➕ 添加个股"
                setOnClickListener {
                    Dialogs.searchStock(scope, context) { code, name ->
                        openEditor(
                            scope,
                            context,
                            store,
                            groupId,
                            groupName,
                            onSaved,
                            extraMembers + (sixDigitCode(code) to name),
                            removedCodes + deletedCodes,
                        )
                    }
                }
            },
        )
        val listView =
            ListView(context).apply {
                choiceMode = ListView.CHOICE_MODE_MULTIPLE
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        UiKit.dp(context, 320),
                    )
            }

        fun renderList() {
            val codes = universe.keys.toList()
            listView.adapter =
                ArrayAdapter(
                    context,
                    android.R.layout.simple_list_item_multiple_choice,
                    codes.map { "${universe[it]?.name}（$it）" },
                )
            codes.indices.forEach { listView.setItemChecked(it, codes[it] in checkedCodes) }
        }

        fun syncChecked() {
            val codes = universe.keys.toList()
            codes.forEachIndexed { index, code ->
                if (listView.isItemChecked(index)) {
                    checkedCodes.add(code)
                } else {
                    checkedCodes.remove(code)
                }
            }
        }

        renderList()

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val code = universe.keys.toList()[position]
            val member = universe[code] ?: return@setOnItemLongClickListener true
            syncChecked()
            MaterialAlertDialogBuilder(context)
                .setTitle("删除个股")
                .setMessage("把「${member.name}（$code）」从「$groupName」的成分和龙头候选中删除？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    universe.remove(code)
                    checkedCodes.remove(code)
                    deletedCodes.add(code)
                    renderList()
                }.show()
            true
        }
        container.addView(listView)

        MaterialAlertDialogBuilder(context)
            .setTitle("「$groupName」龙头（勾选 = 算龙头，长按个股可删除）")
            .setMessage("勾选 = 算龙头，取消勾选 = 移出龙头；长按个股可从板块删除。要改股票属于哪个板块，请用「板块管理」。")
            .setView(container)
            .setNeutralButton("恢复自动") { _, _ ->
                store.sectorLeaderOverrides = store.sectorLeaderOverrides - groupId
                MonitorService.send(context, MonitorService.ACTION_REFRESH)
                Toast.makeText(context, "已恢复按成交额前 ${SectorLeaders.SIZE} 名自动挑龙头", Toast.LENGTH_SHORT).show()
                onSaved()
            }.setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                syncChecked()
                val chosenCodes = checkedCodes.filter { it in universe }.toSet()
                val chosen = chosenCodes.mapNotNull { code -> universe[code]?.let { code to it.name } }.toMap()
                store.sectorLeaderOverrides = store.sectorLeaderOverrides + (
                    groupId to
                        LeaderOverrides(
                            pinned = chosen,
                            excluded = universe.keys.toSet() - chosenCodes,
                        )
                )
                if (deletedCodes.isNotEmpty()) {
                    val book = store.boardGroups
                    val group = book.groups[groupId]
                    val base = group?.members ?: initialNames
                    store.boardGroups = book.withMembers(groupId, groupName, base - deletedCodes)
                }
                MonitorService.send(context, MonitorService.ACTION_REFRESH)
                onSaved()
            }.show()
    }

    private fun sixDigitCode(code: String): String =
        if (code.length > 6 && (code.startsWith("sh") || code.startsWith("sz") || code.startsWith("bj"))) {
            code.substring(2)
        } else {
            code
        }
}
