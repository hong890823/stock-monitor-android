package com.example.stockmonitor

import com.example.stockmonitor.monitor.Notifier
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationDiagnosticsTest {

    @Test
    fun directVibrationRequiresNotificationAndDeviceSupport() {
        assertEquals(
            true,
            Notifier.shouldUseDirectVibration(
                Notifier.SignalStatus(
                    notificationsEnabled = true,
                    channelEnabled = true,
                    deviceHasVibrator = true,
                )
            ),
        )
    }

    @Test
    fun everyReminderUsesDirectVibrationWhenNotificationIsAvailable() {
        val status = Notifier.SignalStatus(
            notificationsEnabled = true,
            channelEnabled = true,
            deviceHasVibrator = true,
        )

        assertEquals(true, Notifier.shouldVibrateReminder(status))
    }

    @Test
    fun unsupportedVibrationExplainsThatNotificationStillShows() {
        val status = Notifier.SignalStatus(
            notificationsEnabled = true,
            channelEnabled = true,
            deviceHasVibrator = false,
        )

        assertEquals(
            "买卖点提醒的应用震动不可用，通知仍会显示。",
            Notifier.signalStatusMessage(status),
        )
    }
}
