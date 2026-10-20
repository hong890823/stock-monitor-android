package com.example.stockmonitor.core

import org.json.JSONObject

/**
 * 板块归类：一组被视作「同板块」的个股，「板块龙头」卡片与做T龙头联动都按它分组。
 *
 * 归类有两个来源：
 * 1. **自动**：持仓 F10 行业带出的东财板块码（`BK0438`），成分股每次从东财 `boardMembers` 现拉。
 *    用户没动过就不落盘，跟着接口走。
 * 2. **人工**：你在「板块管理」里改过成分股的归类、或新建的自定义归类（`custom:` 前缀）。
 *    保存后**按你存的名单来**，不再被东财成分改写——F10 的行业粒度经常和实盘的「同板块」
 *    对不上（比如把中天科技挂在「通信设备」而不是「光通信」），最终以人的判断为准。
 *    「恢复自动成分」可以撤销，回到东财名单。
 *
 * 由此支持三件事：改归类内个股（增删）、把某只持仓挂到指定归类（默认仍 F10 自动归属）、
 * 新建自定义归类。
 */
data class BoardGroup(
    /** 自动归类 = 东财板块码（`BK0438`）；自定义归类 = `custom:` + 名称。 */
    val id: String,
    val name: String,
    /**
     * 成分股（6 位代码 → 名称）。
     * null = 没动过，成分股继续按东财板块现拉；非 null = 以这份名单为准（自定义归类永远非 null）。
     */
    val members: Map<String, String>? = null,
    /** 用户新建的归类（可整体删除）；东财自动归类为 false。 */
    val custom: Boolean = false,
) {
    val membersEdited: Boolean get() = members != null

    companion object {
        fun customId(name: String): String = "custom:$name"
    }
}

/**
 * 归类的人工修正（[Store.boardGroups] 落盘）。
 *
 * 只存**被动过的**归类与持仓挂靠，没动过的自动归类不占地方、跟着东财刷新走。
 */
data class BoardGroupBook(
    /** 归类 id → 归类定义（自定义归类 + 改过成分/挂靠时留名的归类）。 */
    val groups: Map<String, BoardGroup> = emptyMap(),
    /** 持仓代码（`sh600522` 这类行情代码）→ 归类 id；没有记录 = 按 F10 自动归属。 */
    val holdingAssignments: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = groups.isEmpty() && holdingAssignments.isEmpty()

    /** 持仓实际归属的归类 id：人工挂靠优先，否则 F10 板块码。 */
    fun groupIdOf(
        code: String,
        f10BoardCode: String?,
    ): String? = holdingAssignments[code]?.takeIf { it.isNotBlank() } ?: f10BoardCode

    /** 归类显示名：人工归类用存的名字，自动归类用 F10 行业名兜底。 */
    fun nameOf(
        groupId: String,
        f10Name: String?,
    ): String = groups[groupId]?.name ?: f10Name ?: groupId

    /**
     * 有效成分股：人工定过名单的按名单来，否则用自动成分 [autoMembers]（东财板块成员）。
     * 名单里的涨跌幅不落盘，调用方用实时行情补。
     */
    fun membersOf(
        groupId: String,
        autoMembers: List<BoardMember>,
    ): List<BoardMember> {
        val manual = groups[groupId]?.members ?: return autoMembers
        return manual.map { (code, name) -> BoardMember(code, name, Double.NaN) }
    }

    /** 改成分股：[groupId] 不存在时按自动归类落一份（保留原名）。 */
    fun withMembers(
        groupId: String,
        name: String,
        members: Map<String, String>,
    ): BoardGroupBook = copy(groups = groups + (groupId to BoardGroup(groupId, name, members, custom = groups[groupId]?.custom == true)))

    /** 自定义归类：新建（成分先空，进编辑器加个股）。 */
    fun withNewGroup(name: String): Pair<BoardGroupBook, String> {
        val id = BoardGroup.customId(name)
        val book = copy(groups = groups + (id to BoardGroup(id, name, members = emptyMap(), custom = true)))
        return book to id
    }

    /**
     * 清掉人工成分（「恢复自动成分」）：回到东财名单。
     * 自定义归类没有自动成分，只能整组删除（[withoutGroup]）。
     */
    fun withMembersCleared(groupId: String): BoardGroupBook {
        val group = groups[groupId] ?: return this
        val restored = if (group.custom) group else group.copy(members = null)
        val nextGroups =
            if (restored.members == null && !restored.custom) {
                // 名字还要留给挂靠展示用；没有挂靠就整条删掉。
                if (holdingAssignments.values.any { it == groupId }) groups + (groupId to restored) else groups - groupId
            } else {
                groups + (groupId to restored)
            }
        return copy(groups = nextGroups)
    }

    /** 删除归类：自定义组整组删掉，指向它的持仓挂靠一起清掉（回到 F10 自动归属）。 */
    fun withoutGroup(groupId: String): BoardGroupBook =
        copy(
            groups = groups - groupId,
            holdingAssignments = holdingAssignments.filterValues { it != groupId },
        )

    /** 挂靠：归类不存在时补一条只有名字的记录，免得显示名丢失。 */
    fun withAssignment(
        code: String,
        groupId: String?,
        name: String?,
    ): BoardGroupBook {
        val nextAssignments =
            if (groupId == null) {
                holdingAssignments - code
            } else {
                holdingAssignments + (code to groupId)
            }
        val nextGroups =
            if (groupId != null && groupId !in groups && name != null) {
                groups + (groupId to BoardGroup(groupId, name))
            } else {
                groups
            }
        return copy(groups = nextGroups, holdingAssignments = nextAssignments)
    }

    companion object {
        /** 落盘格式：`{"groups":{"custom:液冷":{"name":"液冷","custom":true,"members":{"002837":"英杰电气"}}},"assignments":{"sh600522":"custom:液冷"}}`。 */
        fun encode(book: BoardGroupBook): JSONObject {
            val root = JSONObject()
            root.put(
                "groups",
                JSONObject().apply {
                    book.groups.values.forEach { group ->
                        put(
                            group.id,
                            JSONObject().apply {
                                put("name", group.name)
                                if (group.custom) put("custom", true)
                                group.members?.let { members ->
                                    put(
                                        "members",
                                        JSONObject().apply {
                                            members.forEach { (code, name) -> put(code, name) }
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
            root.put(
                "assignments",
                JSONObject().apply {
                    book.holdingAssignments.forEach { (code, groupId) -> put(code, groupId) }
                },
            )
            return root
        }

        fun decode(raw: String?): BoardGroupBook {
            if (raw.isNullOrBlank()) return BoardGroupBook()
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return BoardGroupBook()
            val groups = LinkedHashMap<String, BoardGroup>()
            root.optJSONObject("groups")?.let { node ->
                val ids = node.keys()
                while (ids.hasNext()) {
                    val id = ids.next()
                    val item = node.optJSONObject(id) ?: continue
                    val members =
                        item.optJSONObject("members")?.let { map ->
                            val result = LinkedHashMap<String, String>()
                            val codes = map.keys()
                            while (codes.hasNext()) {
                                val code = codes.next()
                                result[code] = map.optString(code)
                            }
                            result
                        }
                    groups[id] =
                        BoardGroup(
                            id = id,
                            name = item.optString("name").takeIf { it.isNotBlank() } ?: id,
                            members = members,
                            custom = item.optBoolean("custom", false),
                        )
                }
            }
            val assignments = LinkedHashMap<String, String>()
            root.optJSONObject("assignments")?.let { node ->
                val codes = node.keys()
                while (codes.hasNext()) {
                    val code = codes.next()
                    val groupId = node.optString(code)
                    if (groupId.isNotBlank()) assignments[code] = groupId
                }
            }
            return BoardGroupBook(groups, assignments)
        }
    }
}
