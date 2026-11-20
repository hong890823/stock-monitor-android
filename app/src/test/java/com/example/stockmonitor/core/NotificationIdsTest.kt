package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationIdsTest {
    @Test
    fun differentPrefixesDoNotCollideForTheSameCode() {
        val ids = (3000..8600 step 200).map { NotificationIds.of(it, "sh600000") }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun oversizedPrefixIsRejectedInsteadOfSilentlyOverflowing() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationIds.of(30_000, "sh600000")
        }
    }
}