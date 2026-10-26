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
        val initialNames = universe.entries.associate { it.key to it.value.name }
        val checkedCodes = mutableSetOf<String>()
        policyCandidates.forEach { if (it.checked && it.member.code in universe) checkedCodes += it.member.code }
        val deletedCodes = mutableSetOf<String>()

        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiKit.dp(context, 20), UiKit.dp(context, 8), UiKit.dp(context, 20), 0)
            }
        val addButton =
            MaterialButton(context).apply {
                text = "➕ 添加龙头"
            }
        container.addView(addButton)
        val selectionSummary =
            UiKit.body(context, "已选 0 只龙头", 12f).apply {
                setPadding(0, UiKit.dp(context, 8), 0, UiKit.dp(context, 4))
            }
        container.addView(selectionSummary)
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
            selectionSummary.text = "已选 ${checkedCodes.size} 只龙头 · 共 ${universe.size} 只候选"
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

        addButton.setOnClickListener {
            syncChecked()
            Dialogs.searchStock(scope, context) { code, name ->
                val normalizedCode = sixDigitCode(code)
                universe[normalizedCode] = BoardMember(normalizedCode, name, 0.0)
                checkedCodes.add(normalizedCode)
                renderList()
            }
        }

        listView.setOnItemClickListener { _, _, _, _ ->
            syncChecked()
            selectionSummary.text = "已选 ${checkedCodes.size} 只龙头 · 共 ${universe.size} 只候选"
        }

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
            .setTitle("「$groupName」龙头名单")
            .setMessage("勾选 = 算龙头，取消 = 移出龙头；新增项会保留为人工龙头。长按个股可从板块删除，改归属请用「板块管理」。")
            .setView(container)
            .setNeutralButton("恢复自动") { _, _ ->
                store.sectorLeaderOverrides = store.sectorLeaderOverrides - groupId
                Repo.sectorLeaders = Repo.sectorLeaders - groupId
                MonitorService.send(context, MonitorService.ACTION_REFRESH)
                Toast.makeText(context, "已恢复按成交额前 ${SectorLeaders.SIZE} 名自动挑龙头", Toast.LENGTH_SHORT).show()
                onSaved()
            }.setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                syncChecked()
                val chosenCodes = checkedCodes.filter { it in universe }.toSet()
                val chosen = chosenCodes.mapNotNull { code -> universe[code]?.let { code to it.name } }.toMap()
                val overrides = LeaderOverrides(
                    pinned = chosen,
                    excluded = universe.keys.toSet() - chosenCodes,
                )
                store.sectorLeaderOverrides = store.sectorLeaderOverrides + (groupId to overrides)
                Repo.sectorMembers = Repo.sectorMembers + (groupId to universe.values.toList())
                Repo.sectorLeaders = Repo.sectorLeaders + (
                    groupId to SectorLeaders(
                        members = chosen.map { (code, name) -> BoardMember(code, name, 0.0) },
                        asOfMs = System.currentTimeMillis(),
                        overrides = overrides,
                    )
                )
                if (deletedCodes.isNotEmpty()) {
                    val book = store.boardGroups
                    val group = book.groups[groupId]
                    val base = group?.members ?: initialNames
                    store.boardGroups = book.withMembers(groupId, groupName, base - deletedCodes)
                }
                MonitorService.send(context, MonitorService.ACTION_REFRESH)
                Toast.makeText(context, "已保存 ${chosenCodes.size} 只龙头", Toast.LENGTH_SHORT).show()
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
