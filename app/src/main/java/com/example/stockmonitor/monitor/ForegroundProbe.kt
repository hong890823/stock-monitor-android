package com.example.stockmonitor.monitor

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/**
 * 进程内 Activity 可见性计数：决定分析完成时是直接打开报告页还是发通知。
 * 零依赖实现（不用 ProcessLifecycleOwner），配置变更时 start/stop 成对计数，不会误判后台。
 */
object ForegroundProbe {
    private val lock = Any()

    @Volatile
    private var started = 0

    @Volatile
    private var registered = false

    fun ensure(context: Context) {
        if (registered) return
        synchronized(lock) {
            if (registered) return
            val app = context.applicationContext as? Application ?: return
            app.registerActivityLifecycleCallbacks(callbacks)
            registered = true
        }
    }

    val isInForeground: Boolean get() = started > 0

    private val callbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }
}
