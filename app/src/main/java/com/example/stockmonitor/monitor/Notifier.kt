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
import com.example.stockmonitor.core.AlertRecord
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.ui.MainActivity
import java.util.UUID

/** 通知封装：常驻监控通知 + 行情提醒。 */
object Notifier {
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_ALERT = "alert_v2"
    const val CHANNEL_SIGNAL = "signal_v2"

    /** 工具页分析任务的完成/失败通知。独立渠道，不受「行情提醒」开关拦截。 */
    const val CHANNEL_ANALYSIS = "analysis_v1"
    const val NOTIFICATION_ID_MONITOR = 1001

    /** AnalysisService 常驻通知 id，避开 1001 与告警区间 3000-8009。 */
    const val NOTIFICATION_ID_ANALYSIS_FGS = 1101

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
            ).apply { description = context.getString(R.string.channel_monitor_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                context.getString(R.string.channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_alert_desc) },
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
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ANALYSIS,
                context.getString(R.string.channel_analysis_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_analysis_desc) },
        )
    }

    fun signalStatus(context: Context): SignalStatus = reminderStatus(context, CHANNEL_SIGNAL)

    private fun reminderStatus(
        context: Context,
        channelId: String,
    ): SignalStatus {
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

    fun shouldUseDirectVibration(status: SignalStatus): Boolean = shouldVibrateReminder(status)

    fun shouldVibrateReminder(status: SignalStatus): Boolean =
        status.notificationsEnabled && status.channelEnabled && status.deviceHasVibrator

    fun signalStatusMessage(status: SignalStatus): String =
        when {
            !status.notificationsEnabled -> "通知权限未开启，请打开系统通知设置。"
            !status.channelEnabled -> "买卖点提醒通知已关闭，请打开系统通知设置。"
            !status.deviceHasVibrator -> "买卖点提醒的应用震动不可用，通知仍会显示。"
            else -> "已发出，几秒内应弹出通知并震动"
        }

    fun openAppNotificationSettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun newAlertRecord(
        notificationId: Int,
        kind: String,
        title: String,
        text: String,
        level: String,
        triggerType: String,
    ) = AlertRecord(
        id = UUID.randomUUID().toString(),
        notificationId = notificationId,
        kind = kind,
        level = level,
        triggerType = triggerType,
        title = title,
        body = text,
        createdAtMs = System.currentTimeMillis(),
    )

    private fun buildRecordedNotification(
        context: Context,
        record: AlertRecord,
        channelId: String,
        priority: Int,
        category: String? = null,
    ): Notification {
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ALERT_RECORD_ID, record.id)
                .putExtra(MainActivity.EXTRA_ALERT_NOTIFICATION_ID, record.notificationId)
                .putExtra(MainActivity.EXTRA_ALERT_TITLE, record.title)
                .putExtra(MainActivity.EXTRA_ALERT_BODY, record.body)
                .putExtra(MainActivity.EXTRA_ALERT_CREATED_AT_MS, record.createdAtMs)
                .putExtra(MainActivity.EXTRA_ALERT_KIND, record.kind)
                .putExtra(MainActivity.EXTRA_ALERT_LEVEL, record.level)
                .putExtra(MainActivity.EXTRA_ALERT_TRIGGER_TYPE, record.triggerType)
        val pending =
            PendingIntent.getActivity(
                context,
                record.notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(record.title)
            .setContentText(record.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(record.body))
            .setWhen(record.createdAtMs)
            .setAutoCancel(false)
            .setContentIntent(pending)
            .setCategory(category)
            .setPriority(priority)
            .build()
    }

    private fun postRecordedNotification(
        context: Context,
        record: AlertRecord,
        notification: Notification,
        channelId: String,
        failureMessage: String,
    ): Boolean {
        if (!canPost(context, channelId)) {
            Log.w(TAG, "$failureMessage：系统通知或渠道不可用: id=${record.notificationId}")
            return false
        }
        val store = Store(context)
        val persisted =
            runCatching { store.appendAlertRecord(record) }
                .onFailure { error -> Log.e(TAG, "提醒正文记录失败: id=${record.id}", error) }
                .getOrDefault(false)
        if (!persisted) Log.e(TAG, "提醒正文未持久化: id=${record.id}")
        val posted =
            runCatching {
                NotificationManagerCompat.from(context).notify(record.notificationId, notification)
                true
            }.onFailure { error ->
                Log.e(TAG, "$failureMessage: id=${record.notificationId} title=${record.title}", error)
            }.getOrDefault(false)
        if (!posted) {
            runCatching { store.removeAlertRecord(record.id) }
        }
        return posted
    }

    private fun canPost(context: Context, channelId: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel =
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(channelId)
        return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun buildMonitorNotification(
        context: Context,
        title: String,
        text: String,
        bigText: String?,
    ): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pending =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(context, CHANNEL_MONITOR)
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

    /** 分析完成通知：点击直达报告页。不受「行情提醒」开关拦截。 */
    fun analysisComplete(
        context: Context,
        id: Int,
        title: String,
        text: String,
        reportTitle: String,
        report: String,
    ) {
        ensureChannels(context)
        val record =
            newAlertRecord(
                notificationId = id,
                kind = "analysis_complete",
                title = title,
                text = text,
                level = "INFO",
                triggerType = "analysis",
            )
        val pending =
            PendingIntent.getActivity(
                context,
                id,
                com.example.stockmonitor.ui.ReportActivity
                    .createIntent(context, reportTitle, report, notificationId = id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ANALYSIS)
                .setSmallIcon(R.drawable.ic_stat_monitor)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setWhen(record.createdAtMs)
                .setAutoCancel(false)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        postRecordedNotification(context, record, notification, CHANNEL_ANALYSIS, "分析完成通知发送失败")
    }

    /** 分析失败通知：点击在应用内查看失败详情。不受「行情提醒」开关拦截。 */
    fun analysisFailed(
        context: Context,
        id: Int,
        title: String,
        text: String,
    ) {
        ensureChannels(context)
        val record =
            newAlertRecord(
                notificationId = id,
                kind = "analysis_failed",
                title = title,
                text = text,
                level = "FAILED",
                triggerType = "analysis",
            )
        val notification =
            buildRecordedNotification(
                context = context,
                record = record,
                channelId = CHANNEL_ANALYSIS,
                priority = NotificationCompat.PRIORITY_DEFAULT,
            )
        postRecordedNotification(context, record, notification, CHANNEL_ANALYSIS, "分析失败通知发送失败")
    }

    fun alert(
        context: Context,
        id: Int,
        title: String,
        text: String,
    ): Boolean {
        val store =
            com.example.stockmonitor.core
                .Store(context)
        if (!store.alertsEnabled) return false
        ensureChannels(context)
        val record =
            newAlertRecord(
                notificationId = id,
                kind = "alert",
                title = title,
                text = text,
                level = "INFO",
                triggerType = "alert",
            )
        val notification =
            buildRecordedNotification(
                context = context,
                record = record,
                channelId = CHANNEL_ALERT,
                priority = NotificationCompat.PRIORITY_DEFAULT,
            )
        val status = reminderStatus(context, CHANNEL_ALERT)
        val posted =
            postRecordedNotification(context, record, notification, CHANNEL_ALERT, "普通提醒发送失败")
        if (posted && shouldVibrateReminder(status)) vibrateSignal(context)
        return posted
    }

    /** 大盘风险预警：高优先级通知，并使用应用直接震动避免厂商通道差异。 */
    fun marketRisk(
        context: Context,
        id: Int,
        title: String,
        text: String,
        level: String = "WARNING",
        triggerType: String = "market",
    ) {
        val store =
            com.example.stockmonitor.core
                .Store(context)
        if (!store.alertsEnabled) return
        ensureChannels(context)
        val record =
            newAlertRecord(
                notificationId = id,
                kind = "market_risk",
                title = title,
                text = text,
                level = level,
                triggerType = triggerType,
            )
        val notification =
            buildRecordedNotification(
                context = context,
                record = record,
                channelId = CHANNEL_ALERT,
                priority = NotificationCompat.PRIORITY_HIGH,
                category = NotificationCompat.CATEGORY_ALARM,
            )
        val status = reminderStatus(context, CHANNEL_ALERT)
        val posted =
            postRecordedNotification(context, record, notification, CHANNEL_ALERT, "大盘提醒发送失败")
        if (posted && shouldVibrateReminder(status)) vibrateSignal(context)
    }

    /** 个股风险卖出：优先于买卖点开关和普通冷却，必须及时送达。 */
    fun riskSell(
        context: Context,
        id: Int,
        title: String,
        text: String,
    ): Boolean = holdingRisk(context, id, title, text, "risk_sell")

    fun riskReview(
        context: Context,
        id: Int,
        title: String,
        text: String,
        evidenceKey: String,
        evidenceSummary: String,
    ): Boolean = holdingRisk(context, id, title, text, "risk_exit_review", evidenceKey, evidenceSummary)

    private fun holdingRisk(
        context: Context,
        id: Int,
        title: String,
        text: String,
        kind: String,
        evidenceKey: String? = null,
        evidenceSummary: String? = null,
    ): Boolean {
        val store =
            com.example.stockmonitor.core
                .Store(context)
        if (!store.alertsEnabled) return false
        ensureChannels(context)
        val record =
            newAlertRecord(
                notificationId = id,
                kind = kind,
                title = title,
                text = text,
                level = "RISK",
                triggerType = kind,
            ).copy(evidenceKey = evidenceKey, evidenceSummary = evidenceSummary)
        val notification =
            buildRecordedNotification(
                context = context,
                record = record,
                channelId = CHANNEL_ALERT,
                priority = NotificationCompat.PRIORITY_HIGH,
                category = NotificationCompat.CATEGORY_ALARM,
            )
        val status = reminderStatus(context, CHANNEL_ALERT)
        val posted =
            postRecordedNotification(context, record, notification, CHANNEL_ALERT, "个股风险提醒发送失败")
        if (posted && shouldVibrateReminder(status)) vibrateSignal(context)
        return posted
    }

    /**
     * 买卖点 / 做 T 提醒：应用直接震动 + 高优先级通知。
     *
     * 与 [alert] 分开是因为开关独立（[com.example.stockmonitor.core.Store.intradaySignalEnabled]），
     * 而且它更频繁、更需要被看见。应用直接调用 Vibrator，避免厂商通知通道只显示不震动。
     */
    fun signal(
        context: Context,
        id: Int,
        title: String,
        text: String,
    ): Boolean {
        val store =
            com.example.stockmonitor.core
                .Store(context)
        if (!store.alertsEnabled || !store.intradaySignalEnabled) return false
        ensureChannels(context)
        val record =
            newAlertRecord(
                notificationId = id,
                kind = "signal",
                title = title,
                text = text,
                level = "SIGNAL",
                triggerType = "signal",
            )
        val notification =
            buildRecordedNotification(
                context = context,
                record = record,
                channelId = CHANNEL_SIGNAL,
                priority = NotificationCompat.PRIORITY_HIGH,
                category = NotificationCompat.CATEGORY_ALARM,
            )
        val status = signalStatus(context)
        val posted =
            postRecordedNotification(context, record, notification, CHANNEL_SIGNAL, "盘中信号发送失败")
        if (posted && shouldVibrateReminder(status)) vibrateSignal(context)
        return posted
    }

    /**
     * 盘中资金环境：只读提示，跟随「买卖点 / 做T提醒」开关，但不生成建议计划、不影响做 T 份额。
     * 用独立 kind/triggerType，便于提醒历史里与买卖信号区分。
     */
    fun marketFundFlow(
        context: Context,
        id: Int,
        title: String,
        text: String,
    ): Boolean {
        val store = Store(context)
        if (!store.alertsEnabled || !store.intradaySignalEnabled) return false
        ensureChannels(context)
        val record =
            newAlertRecord(
                notificationId = id,
                kind = "market_fund_flow",
                title = title,
                text = text,
                level = "SIGNAL",
                triggerType = "market_fund_flow",
            )
        val notification =
            buildRecordedNotification(
                context = context,
                record = record,
                channelId = CHANNEL_SIGNAL,
                priority = NotificationCompat.PRIORITY_DEFAULT,
                category = NotificationCompat.CATEGORY_STATUS,
            )
        val status = signalStatus(context)
        val posted =
            postRecordedNotification(context, record, notification, CHANNEL_SIGNAL, "盘中资金环境发送失败")
        if (posted && shouldVibrateReminder(status)) vibrateSignal(context)
        return posted
    }

    /** 尾盘候选复核：与实时买点同通道，但用独立 triggerType 做跨重启的每日去重。 */
    fun tailObservation(
        context: Context,
        id: Int,
        title: String,
        text: String,
        triggerType: String,
    ): Boolean {
        val store = Store(context)
        if (!store.alertsEnabled || !store.intradaySignalEnabled) return false
        ensureChannels(context)
        val record = newAlertRecord(
            notificationId = id,
            kind = "tail_observation",
            title = title,
            text = text,
            level = "SIGNAL",
            triggerType = triggerType,
        )
        val notification = buildRecordedNotification(
            context = context,
            record = record,
            channelId = CHANNEL_SIGNAL,
            priority = NotificationCompat.PRIORITY_HIGH,
            category = NotificationCompat.CATEGORY_ALARM,
        )
        val status = signalStatus(context)
        val posted = postRecordedNotification(context, record, notification, CHANNEL_SIGNAL, "尾盘候选通知发送失败")
        if (posted && shouldVibrateReminder(status)) vibrateSignal(context)
        return posted
    }

    /** 普通量价异动：中性信息级通知，不沿用买卖点的强震动语义。 */
    fun volumePriceAnomaly(
        context: Context,
        id: Int,
        title: String,
        text: String,
    ) {
        if (!Store(context).alertsEnabled) return
        ensureChannels(context)
        val record =
            newAlertRecord(
                notificationId = id,
                kind = "volume_price_anomaly",
                title = title,
                text = text,
                level = "INFO",
                triggerType = "volume_price_anomaly",
            )
        postRecordedNotification(
            context,
            record,
            buildRecordedNotification(
                context = context,
                record = record,
                channelId = CHANNEL_ALERT,
                priority = NotificationCompat.PRIORITY_DEFAULT,
            ),
            CHANNEL_ALERT,
            "量价异动通知发送失败",
        )
    }

    /**
     * 真正的盘中点火强提醒。
     *
     * 不受买卖点开关拦截，但仍受总提醒开关控制；使用高优先级信号通道并直接震动，
     * 保证用户关闭交易动作提示后仍能及时看到高质量盘面事件。
     */
    fun intradayIgnition(
        context: Context,
        id: Int,
        title: String,
        text: String,
    ) {
        if (!Store(context).alertsEnabled) return
        ensureChannels(context)
        val record =
            newAlertRecord(
                notificationId = id,
                kind = "intraday_ignition",
                title = title,
                text = text,
                level = "STRONG",
                triggerType = "intraday_ignition",
            )
        val posted =
            postRecordedNotification(
                context,
                record,
                buildRecordedNotification(
                    context = context,
                    record = record,
                    channelId = CHANNEL_SIGNAL,
                    priority = NotificationCompat.PRIORITY_HIGH,
                    category = NotificationCompat.CATEGORY_ALARM,
                ),
                CHANNEL_SIGNAL,
                "盘中点火强提醒发送失败",
            )
        if (posted && shouldVibrateReminder(signalStatus(context))) vibrateSignal(context)
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
                    AudioAttributes
                        .Builder()
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
