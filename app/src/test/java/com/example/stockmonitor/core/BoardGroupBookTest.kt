package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 板块归类的人工修正：归类内个股增删、持仓挂靠、自定义归类。
 *
 * F10 的行业粒度经常和实盘的「同板块」对不上（中天科技挂在「通信设备」而不是「光通信」），
 * 所以归类以人的判断为准：人工名单一旦定下就按它来，「恢复自动成分」才回到东财名单。
 */
class BoardGroupBookTest {

    private fun member(code: String, name: String) = BoardMember(code, name, Double.NaN)

    @Test
    fun holdingAssignmentOverridesF10Board() {
        val book = BoardGroupBook(
            groups = mapOf("custom:液冷" to BoardGroup("custom:液冷", "液冷", emptyMap(), custom = true)),
            holdingAssignments = mapOf("sh600522" to "custom:液冷"),
        )

        // 挂靠优先；没挂靠的持仓才走 F10。
        assertEquals("custom:液冷", book.groupIdOf("sh600522", "BK0436"))
        assertEquals("BK0436", book.groupIdOf("sh600487", "BK0436"))
        assertNull(book.groupIdOf("sh600487", null))
    }

    @Test
    fun manualMembersReplaceAutoMembers() {
        val book = BoardGroupBook(
            groups = mapOf(
                "BK0438" to BoardGroup("BK0438", "光通信", mapOf("600487" to "亨通光电"), custom = false),
            ),
        )
        val auto = listOf(member("600487", "亨通光电"), member("300570", "太辰光"))

        // 人工名单生效：太辰光被勾掉了就不在名单里。
        assertEquals(listOf("亨通光电"), book.membersOf("BK0438", auto).map { it.name })
        // 没动过的归类照用东财成分。
        assertEquals(listOf("亨通光电", "太辰光"), book.membersOf("BK0733", auto).map { it.name })
    }

    @Test
    fun withMembersClearedRestoresAutoMembers() {
        val edited = BoardGroupBook(
            groups = mapOf("BK0438" to BoardGroup("BK0438", "光通信", mapOf("600487" to "亨通光电"))),
        )

        val restored = edited.withMembersCleared("BK0438")

        assertNull(restored.groups["BK0438"]?.members)
        assertEquals(listOf("亨通光电", "太辰光"), restored.membersOf("BK0438", listOf(member("600487", "亨通光电"), member("300570", "太辰光"))).map { it.name })
    }

    @Test
    fun deletingCustomGroupDropsHoldingAssignments() {
        val book = BoardGroupBook(
            groups = mapOf("custom:液冷" to BoardGroup("custom:液冷", "液冷", emptyMap(), custom = true)),
            holdingAssignments = mapOf("sh600522" to "custom:液冷", "sh600487" to "BK0438"),
        )

        val next = book.withoutGroup("custom:液冷")

        assertFalse(next.groups.containsKey("custom:液冷"))
        // 指向它的挂靠清掉（回到 F10 自动）；别的归类的挂靠不动。
        assertNull(next.holdingAssignments["sh600522"])
        assertEquals("BK0438", next.holdingAssignments["sh600487"])
    }

    @Test
    fun assignmentToUnknownGroupKeepsNameForDisplay() {
        val book = BoardGroupBook().withAssignment("sh600522", "BK0438", "光通信")

        assertEquals("光通信", book.groups["BK0438"]?.name)
        assertEquals("光通信", book.nameOf("BK0438", null))
        // 自动归类没动过：显示名回落 F10 行业名。
        assertEquals("通信设备", book.nameOf("BK0436", "通信设备"))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val book = BoardGroupBook(
            groups = mapOf(
                "custom:液冷" to BoardGroup("custom:液冷", "液冷", mapOf("002837" to "英维克"), custom = true),
                "BK0438" to BoardGroup("BK0438", "光通信", null, custom = false),
            ),
            holdingAssignments = mapOf("sh600522" to "custom:液冷"),
        )

        val decoded = BoardGroupBook.decode(BoardGroupBook.encode(book).toString())

        assertEquals(book, decoded)
    }

    @Test
    fun decodeToleratesBlankAndGarbage() {
        assertEquals(BoardGroupBook(), BoardGroupBook.decode(null))
        assertEquals(BoardGroupBook(), BoardGroupBook.decode("  "))
        assertEquals(BoardGroupBook(), BoardGroupBook.decode("not json"))
    }

    @Test
    fun newGroupIsCustomWithEmptyMembers() {
        val (book, groupId) = BoardGroupBook().withNewGroup("液冷")

        assertEquals("custom:液冷", groupId)
        assertTrue(book.groups[groupId]?.custom == true)
        assertTrue(book.groups[groupId]?.members?.isEmpty() == true)
    }
}
