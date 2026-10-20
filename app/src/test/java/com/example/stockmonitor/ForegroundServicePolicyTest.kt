package com.example.stockmonitor

import android.content.pm.ServiceInfo
import com.example.stockmonitor.monitor.ForegroundServicePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServicePolicyTest {

    @Test
    fun android15AndLaterUsesSpecialUseInsteadOfDataSync() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            ForegroundServicePolicy.activeTypeFor(35),
        )
    }

    @Test
    fun olderAndroidKeepsDataSyncCompatibility() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ForegroundServicePolicy.activeTypeFor(33),
        )
    }
}
