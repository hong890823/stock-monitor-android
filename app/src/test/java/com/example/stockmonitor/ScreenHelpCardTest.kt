package com.example.stockmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenHelpCardTest {
    @Test
    fun helpCardKeepsOnlyPrimaryRiskCopyAndOpensDetails() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/ScreenFragment.kt").readText()
        val card =
            source
                .substringAfter("screenCard(context, \"help\", \"ℹ️ 说明\").apply {")
                .substringBefore("container.addView(view)")

        assertFalse(card.contains("VolumePrice.helpText"))
        assertFalse(card.contains("扫描使用腾讯前复权日线"))
        assertTrue(card.contains("addText(ScreenHelpCopy.PRIMARY_SUMMARY"))
        assertTrue(card.contains("text = \"查看口径与标签说明\""))
        assertTrue(card.contains("ScreenHelpCopy.detailText()"))
        assertTrue(card.contains("Dialogs.showDetails("))
    }
}
