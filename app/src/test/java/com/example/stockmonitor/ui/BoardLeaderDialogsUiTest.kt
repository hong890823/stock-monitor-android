package com.example.stockmonitor.ui

import com.example.stockmonitor.TestProjectFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardLeaderDialogsUiTest {
    @Test
    fun addingLeaderUpdatesCurrentEditorWithoutOpeningAStackedDialog() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/BoardLeaderDialogs.kt").readText()
        val addHandler =
            source
                .substringAfter("text = \"➕ 添加龙头\"")
                .substringBefore("listView.setOnItemClickListener")

        assertFalse(addHandler.contains("openEditor("))
        assertTrue(addHandler.contains("universe["))
        assertTrue(addHandler.contains("renderList()"))
    }

    @Test
    fun savingLeaderEditsUpdatesCachedLeaderListsImmediately() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/BoardLeaderDialogs.kt").readText()
        val saveHandler =
            source
                .substringAfter(".setPositiveButton(\"保存\")")
                .substringBefore(".show()")

        assertTrue(saveHandler.contains("Repo.sectorMembers ="))
        assertTrue(saveHandler.contains("Repo.sectorLeaders ="))
    }

    @Test
    fun restoringAutomaticLeadersClearsCachedManualListImmediately() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/BoardLeaderDialogs.kt").readText()
        val restoreHandler =
            source
                .substringAfter(".setNeutralButton(\"恢复自动\")")
                .substringBefore(".setNegativeButton")

        assertTrue(restoreHandler.contains("Repo.sectorLeaders = Repo.sectorLeaders - groupId"))
    }

    @Test
    fun leaderEditorUsesClearActionLabelsAndSelectionSummary() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/BoardLeaderDialogs.kt").readText()

        assertTrue(source.contains("text = \"➕ 添加龙头\""))
        assertTrue(source.contains("「${'$'}groupName」龙头名单"))
        assertTrue(source.contains("已选"))
    }
}
