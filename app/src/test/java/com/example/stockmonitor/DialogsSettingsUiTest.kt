package com.example.stockmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogsSettingsUiTest {
    @Test
    fun notificationSettingsButtonHasTopSpacingFromThreshold() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/Dialogs.kt").readText()
        val button =
            source
                .substringAfter("text = \"打开应用通知设置\"")
                .substringBefore("MaterialAlertDialogBuilder(activity)")

        assertTrue(button.contains("topMargin = UiKit.dp(activity, 12)"))
    }

    @Test
    fun dropAlertThresholdKeepsItsVisibleExplanation() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/Dialogs.kt").readText()
        val settings =
            source
                .substringAfter("fun showSettings(")
                .substringBefore("fun showDetails(")

        assertTrue(settings.contains("dropAlertNote"))
        assertTrue(settings.contains("UiKit.body(activity, dropAlertNote"))
    }

    @Test
    fun settingsDialogOmitsNotificationSettingsExplanation() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/Dialogs.kt").readText()
        val settings =
            source
                .substringAfter("fun showSettings(")
                .substringBefore("fun showDetails(")

        assertFalse(settings.contains("这里用于开启通知权限"))
    }

    @Test
    fun settingsDialogOmitsRefreshRules() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/Dialogs.kt").readText()
        val settings =
            source
                .substringAfter("fun showSettings(")
                .substringBefore("fun showDetails(")

        assertFalse(settings.contains("刷新规则：每 15 秒刷新一次"))
    }
}
