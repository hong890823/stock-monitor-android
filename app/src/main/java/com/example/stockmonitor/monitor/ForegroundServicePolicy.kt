package com.example.stockmonitor.monitor

import android.content.pm.ServiceInfo

object ForegroundServicePolicy {
    fun activeTypeFor(sdkInt: Int): Int =
        if (sdkInt >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
}
