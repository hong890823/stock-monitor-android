package com.example.stockmonitor.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.stockmonitor.R
import com.example.stockmonitor.ui.MainActivity

/** 通知封装：常驻监控通知 + 行情提醒。 */
object Notifier {
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_ALERT = "alert_v2"
    const val CHANNEL_SIGNAL = "signal_v2"
    const val NOTIFICATION_ID_MONITOR = 1001

    /** 买卖点提醒的震动：两下短震，和普通通知的默认震动区分开。 */
    private val SIGNAL_VIBRATION = longArrayOf(0, 250, 120, 250)

    data class SignalStatus(
        val notificationsEnabled: Boolean,
        val channelEnabled: Boolean,
        val deviceHasVibrator: Boolean,
    )

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITOR,
                context.getString(R.string.channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_monitor_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                context.getString(R.string.channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_alert_desc) }
        )
        // 独立通道：震动与横幅都在通道上配置（Android 8 起 Builder 里的设置会被忽略），
        // 通道 id 独立还让用户可以单独静音买卖点提醒、保留跌幅预警。
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SIGNAL,
                context.getString(R.string.channel_signal_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_signal_desc)
                // 震动由 Vibrator 直接执行，避免部分厂商只显示通知、不执行通道震动。
                enableVibration(false)
                enableLights(true)
            }
        )
    }

    fun signalStatus(context: Context): SignalStatus {
        return reminderStatus(context, CHANNEL_SIGNAL)
    }

    private fun reminderStatus(context: Context, channelId: String): SignalStatus {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager?.getNotificationChannel(channelId)
        val vibrator = context.getSystemService(Vibrator::class.java)
        return SignalStatus(
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelEnabled = channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE,
            deviceHasVibrator = vibrator?.hasVibrator() == true,
        )
    }

    fun shouldUseDirectVibration(status: SignalStatus): Boolean =
        shouldVibrateReminder(status)

    fun shouldVibrateReminder(status: SignalStatus): Boolean =
        status.notificationsEnabled && status.channelEnabled && status.deviceHasVibrator

    fun signalStatusMessage(status: SignalStatus): String = when {
        !status.notificationsEnabled -> "通知权限未开启，请打开系统通知设置。"
        !status.channelEnabled -> "买卖点提醒通知已关闭，请打开系统通知设置。"
        !status.deviceHasVibrator -> "买卖点提醒的应用震动不可用，通知仍会显示。"
        else -> "已发出，几秒内应弹出通知并震动"
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun buildMonitorNotification(context: Context, title: String, text: String, bigText: String?): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText ?: text))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun alert(context: Context, id: Int, title: String, text: String) {
        val store = com.example.stockmonitor.core.Store(context)
        if (!store.alertsEnabled) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val status = reminderStatus(context, CHANNEL_ALERT)
        val posted = runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        }.onFailure { error ->
            Log.e(TAG, "普通提醒发送失败: id=$id title=$title", error)
        }.getOrDefault(false)
        if (posted && shouldVibrateReminder(status)) vibrateSignal(context)
    }

    /** 大盘风险预警：高优先级通知，并使用应用直接震动避免厂商通道差异。 */
    fun marketRisk(context: Context, id: Int, title: String, text: String) {
        val store = com.example.stockmonitor.core.Store(context)
        if (!store.alertsEnabled) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val status = reminderStatus(context, CHANNEL_ALERT)
        val manager = NotificationManagerCompat.from(context)
        val posted = runCatching {
            manager.notify(id, notification)
            true
        }.onFailure { error ->
            Log.e(TAG, "大盘提醒发送失败: id=$id title=$title", error)
        }.getOrDefault(false)
        if (posted && shouldVibrateReminder(status)) vibrateSignal(context)
    }

    /** 个股风险卖出：优先于买卖点开关和普通冷却，必须及时送达。 */
    fun riskSell(context: Context, id: Int, title: String, text: String) {
        val store = com.example.stockmonitor.core.Store(context)
        if (!store.alertsEnabled) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val status = reminderStatus(context, CHANNEL_ALERT)
        val posted = runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        }.onFailure { error ->
            Log.e(TAG, "个股风险卖出提醒发送失败: id=$id title=$title", error)
        }.getOrDefault(false)
        if (posted && shouldVibrateReminder(status)) vibrateSignal(context)
    }

    /**
     * 买卖点 / 做 T 提醒：应用直接震动 + 高优先级通知。
     *
     * 与 [alert] 分开是因为开关独立（[com.example.stockmonitor.core.Store.intradaySignalEnabled]），
     * 而且它更频繁、更需要被看见。应用直接调用 Vibrator，避免厂商通知通道只显示不震动。
     */
    fun signal(context: Context, id: Int, title: String, text: String) {
        val store = com.example.stockmonitor.core.Store(context)
        if (!store.alertsEnabled || !store.intradaySignalEnabled) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SIGNAL)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val status = signalStatus(context)
        val posted = runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        }.onFailure { error ->
            Log.e(TAG, "盘中信号发送失败: id=$id title=$title", error)
        }.getOrDefault(false)
        if (posted && shouldVibrateReminder(status)) vibrateSignal(context)
    }

    @Suppress("DEPRECATION")
    private fun vibrateSignal(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(SIGNAL_VIBRATION, -1)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vibrator.vibrate(
                    effect,
                    android.os.VibrationAttributes.createForUsage(
                        android.os.VibrationAttributes.USAGE_NOTIFICATION,
                    ),
                )
            } else {
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
        }.onFailure { error ->
            Log.e(TAG, "提醒震动执行失败", error)
        }
    }

    private const val TAG = "StockMonitor.Notifier"
}
