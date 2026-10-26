package com.example.stockmonitor.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.stockmonitor.R
import com.example.stockmonitor.core.AlertRecord
import com.example.stockmonitor.core.AlertRecordBook
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.monitor.MonitorService
import com.example.stockmonitor.monitor.Repo
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private val fragments = linkedMapOf<Int, Fragment>()
    private var currentTab = R.id.nav_market

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resetSelectionPagination(freshLaunch = savedInstanceState == null)
        setContentView(R.layout.activity_main)
        UiKit.applySystemBarInsets(
            findViewById(R.id.root),
            findViewById(R.id.toolbar),
            findViewById(R.id.bottom_nav),
        )
        store = Store(this)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            subtitle = getString(R.string.app_subtitle)
            inflateMenu(R.menu.main_menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_refresh -> {
                        MonitorService.send(this@MainActivity, MonitorService.ACTION_REFRESH)
                        true
                    }
                    R.id.action_summary -> {
                        openSummary()
                        true
                    }
                    R.id.action_alert_history -> {
                        startActivity(Intent(this@MainActivity, AlertHistoryActivity::class.java))
                        true
                    }
                    R.id.action_settings -> {
                        Dialogs.showSettings(this@MainActivity) { switchTab(currentTab) }
                        true
                    }
                    else -> false
                }
            }
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
        switchTab(R.id.nav_market)

        requestNotificationPermission()
        if (store.monitorEnabled) MonitorService.start(this)
        Repo.notifyChanged()
        maybePromptBatteryOptimization()
        handleAlertIntent(intent)
    }

    override fun onDestroy() {
        if (isFinishing) clearSelectionPagination()
        super.onDestroy()
    }

    private fun resetSelectionPagination(freshLaunch: Boolean) {
        val session = getSharedPreferences("screen_session", Context.MODE_PRIVATE)
        val processStart = Process.getStartElapsedRealtime()
        val sameProcess = session.getLong("process_start_elapsed_realtime", -1L) == processStart
        if (freshLaunch || !sameProcess) {
            clearSelectionPagination()
            session.edit().putLong("process_start_elapsed_realtime", processStart).apply()
        }
    }

    private fun clearSelectionPagination() {
        getSharedPreferences("screen_list_pages", Context.MODE_PRIVATE).edit().clear().apply()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlertIntent(intent)
    }

    private fun switchTab(itemId: Int) {
        currentTab = itemId
        val fragment = fragments.getOrPut(itemId) {
            when (itemId) {
                R.id.nav_screen -> ScreenFragment()
                R.id.nav_portfolio -> PortfolioFragment()
                R.id.nav_tools -> ToolsFragment()
                else -> MarketFragment()
            }
        }
        val transaction = supportFragmentManager.beginTransaction()
        fragments.values.filter { it != fragment && it.isAdded }.forEach { transaction.hide(it) }
        if (fragment.isAdded) {
            transaction.show(fragment)
        } else {
            transaction.add(R.id.fragment_container, fragment)
        }
        transaction.commitNowAllowingStateLoss()
        (fragment as? BaseFragment)?.renderIfStale()
    }

    private fun openSummary() {
        val text = Repo.summaryText ?: store.lastSummaryText
        if (text.isNullOrBlank()) {
            MonitorService.send(this, MonitorService.ACTION_SUMMARY)
            MaterialAlertDialogBuilder(this)
                .setTitle("盘面总结")
                .setMessage("盘面总结正在生成，稍后可在顶部菜单再次打开。\n\n每天 16:00 / 18:00 / 21:00 会自动生成并缓存。")
                .setPositiveButton("知道了", null)
                .show()
        } else {
            ReportActivity.open(this, "📋 盘面总结", text)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleAlertIntent(intent: Intent?) {
        intent ?: return
        val recordId = intent.getStringExtra(EXTRA_ALERT_RECORD_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_ALERT_NOTIFICATION_ID, 0)
        val persisted = AlertRecordBook.find(store.alertRecords, recordId)
        val fallback = recordFromIntent(intent, recordId, notificationId)
        val record = AlertRecordBook.replayRecord(persisted, fallback)
        if (record == null) {
            if (showAlertDialog(AlertDetailDialogFragment.newMissing(notificationId))) {
                intent.removeExtra(EXTRA_ALERT_RECORD_ID)
            }
            return
        }
        if (persisted != record) store.upsertAlertRecord(record)
        if (showAlertDialog(AlertDetailDialogFragment.newInstance(record))) {
            intent.removeExtra(EXTRA_ALERT_RECORD_ID)
        }
    }

    private fun recordFromIntent(
        intent: Intent,
        recordId: String,
        notificationId: Int,
    ): AlertRecord? {
        val title = intent.getStringExtra(EXTRA_ALERT_TITLE)
        val body = intent.getStringExtra(EXTRA_ALERT_BODY)
        if (title.isNullOrBlank() || body.isNullOrBlank()) return null
        return AlertRecord(
            id = recordId,
            notificationId = notificationId,
            kind = intent.getStringExtra(EXTRA_ALERT_KIND).orEmpty(),
            level = intent.getStringExtra(EXTRA_ALERT_LEVEL).orEmpty(),
            triggerType = intent.getStringExtra(EXTRA_ALERT_TRIGGER_TYPE).orEmpty(),
            title = title,
            body = body,
            createdAtMs = intent.getLongExtra(EXTRA_ALERT_CREATED_AT_MS, System.currentTimeMillis()),
        )
    }

    private fun showAlertDialog(dialog: AlertDetailDialogFragment): Boolean {
        val current =
            supportFragmentManager.findFragmentByTag(AlertDetailDialogFragment.TAG)
                as? AlertDetailDialogFragment
        if (current?.replayKey == dialog.replayKey) return true
        return runCatching {
            supportFragmentManager.beginTransaction().apply {
                current?.let(::remove)
                add(dialog, AlertDetailDialogFragment.TAG)
            }.commitNowAllowingStateLoss()
            true
        }.getOrDefault(false)
    }

    /** 长时间挂后台时申请忽略电池优化，避免被系统冻结。 */
    private fun maybePromptBatteryOptimization() {
        if (store.batteryPromptDismissed) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        MaterialAlertDialogBuilder(this)
            .setTitle("保持后台监控")
            .setMessage("允许忽略电池优化后，锁屏或长时间不打开应用时也能继续刷新行情并推送提醒。\n\n不授权也不影响使用，只是后台刷新可能被系统暂停。")
            .setPositiveButton("去设置") { _, _ ->
                store.batteryPromptDismissed = true
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
                runCatching { startActivity(intent) }
            }
            .setNegativeButton("以后再说") { _, _ -> store.batteryPromptDismissed = true }
            .show()
    }

    companion object {
        const val EXTRA_ALERT_RECORD_ID = "alert_record_id"
        const val EXTRA_ALERT_NOTIFICATION_ID = "alert_notification_id"
        const val EXTRA_ALERT_TITLE = "alert_title"
        const val EXTRA_ALERT_BODY = "alert_body"
        const val EXTRA_ALERT_CREATED_AT_MS = "alert_created_at_ms"
        const val EXTRA_ALERT_KIND = "alert_kind"
        const val EXTRA_ALERT_LEVEL = "alert_level"
        const val EXTRA_ALERT_TRIGGER_TYPE = "alert_trigger_type"
    }
}
