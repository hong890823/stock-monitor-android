package com.example.stockmonitor.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.stockmonitor.core.Store

/** 开机 / 应用升级后按设置恢复后台监控。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = Store(context)
        if (store.monitorEnabled && store.bootAutoStart) {
            runCatching { MonitorService.start(context) }
                .onSuccess { store.lastStartupError = null }
                .onFailure { error ->
                    val message = "开机自动启动后台监控失败：${error.message ?: "系统限制，请打开应用重试"}"
                    store.lastStartupError = message
                    Log.e("StockMonitor", message, error)
                }
        }
    }
}
