package com.example.stockmonitor.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.stockmonitor.R
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
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commitAllowingStateLoss()
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
}
