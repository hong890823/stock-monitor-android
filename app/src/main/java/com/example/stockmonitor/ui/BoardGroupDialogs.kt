package com.example.stockmonitor.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.stockmonitor.R
import com.example.stockmonitor.core.BoardGroup
import com.example.stockmonitor.core.BoardGroupBook
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.monitor.MonitorService
import com.example.stockmonitor.monitor.Repo
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope

/**
 * 「板块管理」对话框链。
 *
 * F10 的行业粒度经常和实盘的「同板块」对不上（中天科技挂在「通信设备」而不是「光通信」），
 * 所以归类以人的判断为准：可改归类内个股、把持仓改挂到指定归类、新建自定义归类、
 * 也能整组删除（自定义）或恢复自动成分（自动归类）。保存后龙头挑选与做T联动都跟着改后的归类走。
 */
object BoardGroupDialogs {
    /** 界面展示用的一行归类。 */
    data class GroupRow(
        val groupId: String,
        val name: String,
        val memberCount: Int,
        val holdingNames: List<String>,
        val custom: Boolean,
        val membersEdited: Boolean,
    )

    /**
     * 归类清单：持仓实际归属的归类 + 自定义归类 + 人工改过成分的归类，按首次出现顺序。
     * 成分股数按「候选全集」（自动成分 ∪ 人工名单）算，持仓数只数有仓位的。
     */
    fun displayGroups(store: Store): List<GroupRow> {
        val book = store.boardGroups
        val holdingNames = LinkedHashMap<String, MutableList<String>>()
        val names = LinkedHashMap<String, String>()
        store.portfolio.positions
            .filter { it.quantity.isFinite() && it.quantity > 0 }
            .forEach { position ->
                val f10 = Repo.sectorContexts[position.code]?.industry
                val groupId = book.groupIdOf(position.code, f10?.boardCode) ?: return@forEach
                holdingNames.getOrPut(groupId) { mutableListOf() }.add(position.name)
                names.putIfAbsent(groupId, book.nameOf(groupId, f10?.name))
            }
        book.groups.values.forEach { group ->
            names.putIfAbsent(group.id, group.name)
            holdingNames.getOrPut(group.id) { mutableListOf() }
        }
        return names.map { (groupId, name) ->
            val group = book.groups[groupId]
            val universe = LinkedHashMap<String, String>()
            Repo.sectorMembers[groupId].orEmpty().forEach { universe[it.code] = it.name }
            group?.members?.forEach { (code, stockName) -> universe[code] = stockName }
            GroupRow(
                groupId = groupId,
                name = name,
                memberCount = universe.size,
                holdingNames = holdingNames[groupId].orEmpty(),
                custom = group?.custom == true,
                membersEdited = group?.membersEdited == true,
            )
        }
    }

    /** 板块管理入口：「我的板块」区点板块进成分股编辑，「操作」区是板块级操作。 */
    fun openManager(
        scope: CoroutineScope,
        context: Context,
        store: Store,
        onSaved: () -> Unit,
    ) {
        val rows = displayGroups(store)
        val groupLabels =
            rows.map { row ->
                buildString {
                    append(row.name)
                    append("（")
                    append(if (row.memberCount > 0) "${row.memberCount}只" else "成分暂缺")
                    if (row.holdingNames.isNotEmpty()) append("｜持仓${row.holdingNames.size}只")
                    if (row.custom) {
                        append("｜自定义")
                    } else if (row.membersEdited) {
                        append("｜已改成分")
                    }
                    append("）")
                }
            }
        val listRows = mutableListOf<SectionRow>()
        if (rows.isNotEmpty()) {
            listRows += SectionRow("我的板块", isHeader = true)
            rows.forEachIndexed { index, row ->
                listRows +=
                    SectionRow(groupLabels[index], isHeader = false) {
                        openGroupEditor(scope, context, store, row.groupId, onSaved)
                    }
            }
        }
        listRows += SectionRow("操作", isHeader = true)
        listRows += SectionRow("➕ 新建板块", isHeader = false) { newGroup(scope, context, store, onSaved) }
        listRows += SectionRow("🔗 编辑持仓归属", isHeader = false) { openAssignmentEditor(store, context, onSaved) }
        listRows += SectionRow("✏️ 编辑持仓（数量/成本/增删）", isHeader = false) { openHoldingsMenu(scope, context, onSaved) }
        listRows += SectionRow("🗑 删除板块 / 恢复自动成分", isHeader = false) { openDeleteOrRestore(context, store, rows, onSaved) }
        MaterialAlertDialogBuilder(context)
            .setTitle("板块管理")
            .setAdapter(SectionedAdapter(context, listRows)) { _, which -> listRows[which].onClick?.invoke() }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 弹窗列表行：分区标题灰字不可点，条目才带点击动作。 */
    private class SectionRow(
        val label: String,
        val isHeader: Boolean,
        val onClick: (() -> Unit)? = null,
    )

    private class SectionedAdapter(
        private val context: Context,
        private val rows: List<SectionRow>,
    ) : BaseAdapter() {
        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): SectionRow = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean = !rows[position].isHeader

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
        ): View {
            val row = rows[position]
            val view = (convertView as? TextView) ?: TextView(context)
            view.text = row.label
            if (row.isHeader) {
                view.setTextColor(UiKit.color(context, R.color.text_secondary))
                view.textSize = 12f
                view.setPadding(UiKit.dp(context, 24), UiKit.dp(context, 16), UiKit.dp(context, 24), UiKit.dp(context, 4))
            } else {
                view.setTextColor(UiKit.color(context, R.color.text_primary))
                view.textSize = 16f
                view.setPadding(UiKit.dp(context, 24), UiKit.dp(context, 14), UiKit.dp(context, 24), UiKit.dp(context, 14))
            }
            return view
        }
    }

    /**
     * 板块内个股编辑：勾选 = 归入本板块，长按个股可删除（随「保存」落盘，「取消」不生效）。
     * 候选 = 自动成分 ∪ 人工名单（勾掉的还在列表里，随时能勾回来）；「添加个股」搜代码/名称补新的。
     */
    private fun openGroupEditor(
        scope: CoroutineScope,
        context: Context,
        store: Store,
        groupId: String,
        onSaved: () -> Unit,
        extraMembers: Map<String, String> = emptyMap(),
        removedCodes: Set<String> = emptySet(),
    ) {
        val book = store.boardGroups
        val group = book.groups[groupId]
        val displayName = group?.name ?: groupId
        val entries = LinkedHashMap<String, String>()
        Repo.sectorMembers[groupId].orEmpty().forEach { entries[it.code] = it.name }
        group?.members?.forEach { (code, name) -> entries[code] = name }
        extraMembers.forEach { (code, name) -> entries[code] = name }
        (removedCodes - extraMembers.keys).forEach { entries.remove(it) }
        val initialKeys = entries.keys.toSet()
        val checkedCodes = mutableSetOf<String>()
        checkedCodes += group?.members?.keys ?: entries.keys
        checkedCodes += extraMembers.keys
        val deletedCodes = mutableSetOf<String>()

        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiKit.dp(context, 20), UiKit.dp(context, 8), UiKit.dp(context, 20), 0)
            }
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(
            actionButton(context, "➕ 添加个股") {
                Dialogs.searchStock(scope, context) { code, name ->
                    val six = toSixDigit(code)
                    openGroupEditor(scope, context, store, groupId, onSaved, extraMembers + (six to name), removedCodes + deletedCodes)
                }
            },
        )
        if (group?.custom == true) {
            buttons.addView(
                actionButton(context, "🗑 删除板块") {
                    MaterialAlertDialogBuilder(context)
                        .setTitle("删除板块")
                        .setMessage("确定删除自定义板块「$displayName」吗？挂在它下面的持仓会回到 F10 自动归属。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                            store.boardGroups = book.withoutGroup(groupId)
                            Toast.makeText(context, "已删除板块「$displayName」", Toast.LENGTH_SHORT).show()
                            MonitorService.send(context, MonitorService.ACTION_REFRESH)
                            onSaved()
                        }.show()
                },
            )
        } else if (group?.membersEdited == true) {
            buttons.addView(
                actionButton(context, "↩ 恢复自动成分") {
                    store.boardGroups = book.withMembersCleared(groupId)
                    Toast.makeText(context, "「$displayName」已恢复按东财板块成分", Toast.LENGTH_SHORT).show()
                    MonitorService.send(context, MonitorService.ACTION_REFRESH)
                    onSaved()
                },
            )
        }
        container.addView(buttons)

        // 成分多的板块（东财一行最多 200）交给 ListView 自己滚，别把整表塞进 LinearLayout。
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
            val codes = entries.keys.toList()
            listView.adapter =
                android.widget.ArrayAdapter(
                    context,
                    android.R.layout.simple_list_item_multiple_choice,
                    codes.map { "${entries[it]}（$it）" },
                )
            codes.indices.forEach { listView.setItemChecked(it, codes[it] in checkedCodes) }
        }

        fun syncChecked() {
            val codes = entries.keys.toList()
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
            val code = entries.keys.toList()[position]
            val stockName = entries[code] ?: return@setOnItemLongClickListener true
            syncChecked()
            MaterialAlertDialogBuilder(context)
                .setTitle("删除个股")
                .setMessage("把「$stockName（$code）」从「$displayName」的成分中删除？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    entries.remove(code)
                    checkedCodes.remove(code)
                    deletedCodes.add(code)
                    renderList()
                }.show()
            true
        }
        container.addView(listView)

        MaterialAlertDialogBuilder(context)
            .setTitle("「$displayName」板块个股（勾选 = 归入本板块，长按个股可删除）")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                syncChecked()
                val chosen = checkedCodes.mapNotNull { code -> entries[code]?.let { code to it } }.toMap()
                // 没动过就别写人工名单：白写一份会把自动成分冻住，以后东财加新成分也进不来。
                if (group?.members == null && chosen.keys == initialKeys && extraMembers.isEmpty() && deletedCodes.isEmpty()) {
                    onSaved()
                    return@setPositiveButton
                }
                store.boardGroups = book.withMembers(groupId, displayName, chosen)
                Toast.makeText(context, "「$displayName」板块已保存（${chosen.size} 只）", Toast.LENGTH_SHORT).show()
                MonitorService.send(context, MonitorService.ACTION_REFRESH)
                onSaved()
            }.show()
    }

    /** 持仓挂靠：把某只持仓指到某个归类（默认 F10 自动）。 */
    private fun openAssignmentEditor(
        store: Store,
        context: Context,
        onSaved: () -> Unit,
    ) {
        val book = store.boardGroups
        val positions = store.portfolio.positions
        if (positions.isEmpty()) {
            Toast.makeText(context, "还没有持仓股，先在「持仓设置」里添加", Toast.LENGTH_SHORT).show()
            return
        }
        val rows = displayGroups(store)
        val labels =
            positions
                .map { position ->
                    val f10 = Repo.sectorContexts[position.code]?.industry
                    val groupId = book.groupIdOf(position.code, f10?.boardCode)
                    val where =
                        if (groupId == null) {
                            "暂缺归属"
                        } else {
                            book.nameOf(groupId, f10?.name) + if (book.holdingAssignments[position.code] != null) "（手动）" else "（自动）"
                        }
                    "${position.name}（${position.code}）→ $where"
                }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle("编辑持仓归属（点持仓选板块）")
            .setItems(labels) { _, index ->
                val position = positions[index]
                val f10 = Repo.sectorContexts[position.code]?.industry
                val options = mutableListOf("自动（F10·${f10?.name ?: "暂缺"}）")
                options += rows.map { it.name }
                val currentGroupId = book.holdingAssignments[position.code]
                val checked = rows.indexOfFirst { it.groupId == currentGroupId } + 1
                MaterialAlertDialogBuilder(context)
                    .setTitle("「${position.name}」归入哪个板块")
                    .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                        store.boardGroups =
                            if (which == 0) {
                                book.withAssignment(position.code, null, null)
                            } else {
                                val row = rows[which - 1]
                                book.withAssignment(position.code, row.groupId, row.name)
                            }
                        Toast.makeText(context, "「${position.name}」归属已更新", Toast.LENGTH_SHORT).show()
                        MonitorService.send(context, MonitorService.ACTION_REFRESH)
                        dialog.dismiss()
                        onSaved()
                    }.setNegativeButton("取消", null)
                    .show()
            }.setNegativeButton("取消", null)
            .show()
    }

    /** 编辑持仓入口：复用持仓页已有的追加 / 编辑 / 删除对话框。 */
    private fun openHoldingsMenu(
        scope: CoroutineScope,
        context: Context,
        onSaved: () -> Unit,
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle("编辑持仓")
            .setItems(arrayOf("➕ 追加股票", "✏️ 编辑股数 / 成本", "➖ 删除股票")) { _, which ->
                when (which) {
                    0 -> Dialogs.addPosition(scope, context, onSaved)
                    1 -> Dialogs.editPosition(context, onSaved)
                    2 -> Dialogs.deletePosition(context, onSaved)
                }
            }.setNegativeButton("取消", null)
            .show()
    }

    /** 删除自定义归类 / 把改过成分的归类恢复成自动。 */
    private fun openDeleteOrRestore(
        context: Context,
        store: Store,
        rows: List<GroupRow>,
        onSaved: () -> Unit,
    ) {
        val candidates = rows.filter { it.custom || it.membersEdited }
        if (candidates.isEmpty()) {
            Toast.makeText(context, "没有可删除或恢复的板块", Toast.LENGTH_SHORT).show()
            return
        }
        val labels =
            candidates
                .map {
                    if (it.custom) "删除自定义板块「${it.name}」" else "「${it.name}」恢复自动成分"
                }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle("删除板块 / 恢复自动成分")
            .setItems(labels) { _, index ->
                val target = candidates[index]
                val book = store.boardGroups
                if (target.custom) {
                    store.boardGroups = book.withoutGroup(target.groupId)
                    Toast.makeText(context, "已删除板块「${target.name}」", Toast.LENGTH_SHORT).show()
                } else {
                    store.boardGroups = book.withMembersCleared(target.groupId)
                    Toast.makeText(context, "「${target.name}」已恢复按东财板块成分", Toast.LENGTH_SHORT).show()
                }
                MonitorService.send(context, MonitorService.ACTION_REFRESH)
                onSaved()
            }.setNegativeButton("取消", null)
            .show()
    }

    /** 新建归类：起名后直接进成分股编辑加个股。 */
    private fun newGroup(
        scope: CoroutineScope,
        context: Context,
        store: Store,
        onSaved: () -> Unit,
    ) {
        Dialogs.input(
            context,
            "新建板块",
            "起个名字，例如「液冷」「光通信」。建好后进编辑器添加成分股。",
            listOf(Dialogs.Field("板块名称")),
        ) { values ->
            val name = values.firstOrNull()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(context, "板块名称不能为空", Toast.LENGTH_SHORT).show()
                return@input
            }
            val book = store.boardGroups
            if (book.groups.values.any { it.name == name }) {
                Toast.makeText(context, "已存在同名板块「$name」", Toast.LENGTH_SHORT).show()
                return@input
            }
            val (next, groupId) = book.withNewGroup(name)
            store.boardGroups = next
            Toast.makeText(context, "已新建板块「$name」，接着添加成分股", Toast.LENGTH_SHORT).show()
            openGroupEditor(scope, context, store, groupId, onSaved)
        }
    }

    /** 搜索结果给的是 `sh600522` 这类行情代码，归类成员统一用东财 6 位码。 */
    private fun toSixDigit(code: String): String =
        if (code.length > 6 && (code.startsWith("sh") || code.startsWith("sz") || code.startsWith("bj"))) {
            code.substring(2)
        } else {
            code
        }

    private fun actionButton(
        context: Context,
        text: String,
        onClick: () -> Unit,
    ): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = UiKit.dp(context, 4)
                        bottomMargin = UiKit.dp(context, 4)
                    }
            setOnClickListener { onClick() }
        }
}
