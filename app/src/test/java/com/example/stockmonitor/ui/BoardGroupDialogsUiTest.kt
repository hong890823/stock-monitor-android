package com.example.stockmonitor.ui

import com.example.stockmonitor.TestProjectFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardGroupDialogsUiTest {
    @Test
    fun removingBoardMembershipAlsoRemovesManualLeaderPin() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/BoardGroupDialogs.kt").readText()
        val saveHandler =
            source
                .substringAfter(".setPositiveButton(\"保存\")")
                .substringBefore(".show()")

        assertTrue(saveHandler.contains("initialCheckedCodes"))
        assertTrue(saveHandler.contains("withoutPinned"))
    }
}
