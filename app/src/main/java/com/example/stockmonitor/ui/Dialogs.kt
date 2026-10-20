package com.example.stockmonitor.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.AddLevel
import com.example.stockmonitor.analysis.AddPositionStrategy
import com.example.stockmonitor.analysis.CostReductionMarket
import com.example.stockmonitor.analysis.CostReductionTrend
import com.example.stockmonitor.analysis.StockAnalysis
import com.example.stockmonitor.analysis.TradeCalculator
import com.example.stockmonitor.analysis.TimeUtils
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.SinaClient
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.core.TradeLotLedger
import com.example.stockmonitor.core.TradeRecord
import com.example.stockmonitor.core.TradeSide
import com.example.stockmonitor.core.TradeSource
import com.example.stockmonitor.core.WatchItem
import com.example.stockmonitor.core.availableCapitalIsManual
import com.example.stockmonitor.core.HoldingSyncStatus
import com.example.stockmonitor.core.resolvedAvailableCapital
import com.example.stockmonitor.core.syncHoldingInput
import com.example.stockmonitor.core.updatePosition
import com.example.stockmonitor.monitor.Notifier
import com.example.stockmonitor.monitor.Repo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 输入框 / 设置 / 交易计算等对话框。 */
object Dialogs {

    data class FieldAction(
        val label: String,
        val onClick: (List<String>) -> String?,
    )

    data class Field(
        val hint: String,
        val value: String = "",
        val numeric: Boolean = false,
        val decimal: Boolean = true,
        /**
         * 判断题的选项。非空时这一项渲染成一组单选框，点一下就行，不用手打；
         * [value] 是要预选的选项，不在列表里（或留空）就选第一项。
         */
        val choices: List<String> = emptyList(),
        val action: FieldAction? = null,
    )

    fun input(
        context: Context,
        title: String,
        message: String? = null,
        fields: List<Field>,
        confirmText: String = "确定",
        onConfirm: (List<String>) -> Unit,
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 20), UiKit.dp(context, 8), UiKit.dp(context, 20), 0)
        }
        message?.let {
            container.addView(
                UiKit.label(context, it, 13f).apply {
                    setPadding(0, UiKit.dp(context, 6), 0, UiKit.dp(context, 10))
                }
            )
        }
        // 每一项交回一个取值函数：输入框读 EditText，判断题读当前选中的单选框。
        // 不用把两者都塞进 EditText，是为了让「是/否」这类选择题走点击而不是键盘。
        val readers = mutableListOf<() -> String>()
        val valueSetters = mutableListOf<((String) -> Unit)?>()
        val actionButtons = mutableListOf<MaterialButton?>()
        fields.forEach { field ->
            // 字段名做成固定标签放在输入框上方：hint 在预填了默认值时不会显示，
            // 光看几个 0 分不清哪个是数量、哪个是成本。
            container.addView(
                UiKit.label(context, field.hint, 12f).apply {
                    setPadding(UiKit.dp(context, 4), UiKit.dp(context, 10), 0, 0)
                }
            )
            val read: () -> String
            if (field.choices.isEmpty()) {
                val edit = EditText(context).apply {
                    setText(field.value)
                    setSingleLine(true)
                    UiKit.styleInput(this)
                    if (field.numeric) {
                        inputType = InputType.TYPE_CLASS_NUMBER or
                            (if (field.decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
                    }
                }
                val actionButton = field.action?.let { action ->
                    MaterialButton(context).apply {
                        text = action.label
                        textSize = 11f
                        minWidth = 0
                        minimumWidth = 0
                    }
                }
                if (actionButton == null) {
                    container.addView(edit)
                } else {
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(
                        edit,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    row.addView(
                        actionButton,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = UiKit.dp(context, 8) },
                    )
                    container.addView(row)
                }
                read = { edit.text.toString().trim() }
                valueSetters.add {
                    edit.setText(it)
                    edit.setSelection(it.length)
                }
                actionButtons.add(actionButton)
            } else {
                val checked = field.choices.indexOf(field.value).takeIf { it >= 0 } ?: 0
                val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
                field.choices.forEachIndexed { index, choice ->
                    val button = RadioButton(context).apply {
                        id = View.generateViewId()
                        text = choice
                        textSize = 14f
                        isChecked = index == checked
                    }
                    group.addView(button)
                }
                container.addView(group)
                read = { group.findViewById<RadioButton>(group.checkedRadioButtonId)?.text?.toString().orEmpty() }
                valueSetters.add(null)
                actionButtons.add(null)
            }
            readers.add(read)
        }
        fields.forEachIndexed { index, field ->
            val action = field.action ?: return@forEachIndexed
            actionButtons[index]?.setOnClickListener {
                val updated = action.onClick(readers.map { it() })
                val setter = valueSetters[index]
                if (updated != null && setter != null) setter(updated)
            }
        }
        // AlertDialog 的自定义内容不会可靠地自行限高；内容比屏幕高时会把系统按钮区一起挤出窗口。
        // 这里明确限制表单高度，让说明和字段在内部滚动，取消/确定按钮始终留在弹窗底部。
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.66f).toInt()
        val scrollView = BoundedScrollView(context, maxHeight).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(
                container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scrollView)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmText) { _, _ ->
                onConfirm(readers.map { it() })
            }
        builder.show()
    }

    fun showSettings(activity: AppCompatActivity, onChanged: () -> Unit) {
        val store = Store(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 8), UiKit.dp(activity, 20), 0)
        }
        fun addSwitch(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, UiKit.dp(activity, 8), 0, UiKit.dp(activity, 8))
            }
            val switch = MaterialSwitch(activity).apply {
                text = title
                textSize = 15f
                isChecked = checked
                setOnCheckedChangeListener { _, value -> onChange(value) }
            }
            row.addView(switch)
            row.addView(UiKit.body(activity, note, 11.5f))
            container.addView(row)
        }

        addSwitch(
            "后台持续监控",
            "关闭后停止前台服务，仅在打开应用时刷新",
            store.monitorEnabled,
        ) { value ->
            store.monitorEnabled = value
            if (value) com.example.stockmonitor.monitor.MonitorService.start(activity)
            else com.example.stockmonitor.monitor.MonitorService.stop(activity)
            onChanged()
        }
        addSwitch("开机自动启动", "默认关闭；开启后开机或应用升级时自动恢复监控", store.bootAutoStart) {
            store.bootAutoStart = it
        }
        addSwitch("行情提醒", "跌幅预警与持仓信号变化时推送通知", store.alertsEnabled) {
            store.alertsEnabled = it
        }
        addSwitch(
            "买卖点 / 做T提醒",
            "持仓破位分为待确认、分批减仓和退出，高位转弱优先减仓；观察池只提醒买点确认。" +
                "同股同类信号冷却10分钟；机会提醒每天最多8条，风险升级不受此上限限制。",
            store.intradaySignalEnabled,
        ) { store.intradaySignalEnabled = it }
        addSwitch("全市场涨跌停扫描", "数据量较大，移动网络下可关闭", store.limitScanEnabled) {
            store.limitScanEnabled = it
        }
        addSwitch(
            "加监控股票时同步到自选股",
            "「持仓」里新增监控股票时，顺手加进「市场」页的自选股（顶栏不显示）",
            store.syncPositionToWatchlist,
        ) { store.syncPositionToWatchlist = it }

        container.addView(
            UiKit.body(
                activity,
                String.format(Locale.CHINA, "急跌预警阈值：%.1f%%（可点击修改）", store.dropAlertPct),
                13f,
            ).apply {
                setPadding(0, UiKit.dp(activity, 10), 0, 0)
                setOnClickListener {
                    input(
                        activity,
                        "急跌预警阈值",
                        "个股较上一次刷新急跌超过该比例时提醒；日内累计跌幅 -3% / -5% 另有固定预警",
                        listOf(Field("阈值（%）", String.format(Locale.CHINA, "%.1f", store.dropAlertPct), numeric = true)),
                    ) { values ->
                        values.firstOrNull()?.toDoubleOrNull()?.let { store.dropAlertPct = it }
                    }
                }
            }
        )
        container.addView(
            UiKit.body(
                activity,
                "刷新规则：每 15 秒刷新一次，工作日 08:30-16:00 请求新行情；" +
                    "板块每 30 分钟、指标选股与持仓分析每 30 分钟一个槽位；盘面总结每天 16:00 / 18:00 / 21:00 生成。" +
                    "夜间 18:00-06:00 指标选股失败会每 90 分钟重试，最多 8 次。",
                11.5f,
            ).apply { setPadding(0, UiKit.dp(activity, 14), 0, 0) }
        )
        container.addView(
            MaterialButton(activity).apply {
                text = "打开应用通知设置"
                setOnClickListener { Notifier.openAppNotificationSettings(activity) }
            }
        )
        container.addView(
            UiKit.body(
                activity,
                "这里用于开启通知权限；买卖点震动由应用执行，仍会受手机静音、勿扰模式和系统震动设置影响。",
                11.5f,
            )
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle("设置")
            .setView(ScrollView(activity).apply { addView(container) })
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 用名称/代码搜索并选择一只股票。 */
    fun searchStock(
        scope: CoroutineScope,
        context: Context,
        onPicked: (code: String, name: String) -> Unit,
    ) {
        input(context, "搜索股票", "支持名称、拼音首字母或代码，例如：特变电工 / sh600089", listOf(Field("关键词"))) { values ->
            val keyword = values.firstOrNull().orEmpty()
            if (keyword.isBlank()) return@input
            scope.launch {
                // 搜索接口是阻塞式 HttpURLConnection，必须切到 IO 线程。
                val results = withContext(Dispatchers.IO) {
                    runCatching { SinaClient().search(keyword) }.getOrDefault(emptyList())
                }
                withContext(Dispatchers.Main) {
                    if (results.isEmpty()) {
                        Toast.makeText(context, "没有找到匹配的股票", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    val labels = results.take(15).map { "${it.second}（${it.first}）" }.toTypedArray()
                    MaterialAlertDialogBuilder(context)
                        .setTitle("选择股票")
                        .setItems(labels) { _, index ->
                            val picked = results[index]
                            onPicked(picked.first, picked.second)
                        }
                        .show()
                }
            }
        }
    }

    /** 买卖成本计算，等价于 show_trade_calculator。 */
    fun tradeCalculator(scope: CoroutineScope, context: Context) {
        input(
            context,
            "💰 买卖成本计算",
            "股票可留空（只算成本，不取实时价）。操作前持仓填「股数,成本」，本次操作先选买入或卖出，再填「股数,价格」。",
            listOf(
                Field("股票名称或代码（可留空）"),
                Field("操作前持仓：股数,平均成本"),
                Field("本次操作", choices = listOf("买入", "卖出")),
                Field("本次股数,价格（例如 500,12.34）"),
                Field("本次交易总费用（可留空）", numeric = true),
            ),
        ) { values ->
            val keyword = values.getOrElse(0) { "" }
            val holding = splitNumbers(values.getOrElse(1) { "" })
            val side = values.getOrElse(2) { "" }
            val trade = splitNumbers(values.getOrElse(3) { "" })
            val fees = values.getOrElse(4) { "" }.toDoubleOrNull() ?: 0.0
            if (holding.size < 2 || trade.size < 2) {
                Toast.makeText(context, "请按提示填写：操作前持仓填「股数,成本」，本次填「股数,价格」", Toast.LENGTH_LONG).show()
                return@input
            }
            if (side.isBlank()) {
                Toast.makeText(context, "请选择本次是买入还是卖出", Toast.LENGTH_LONG).show()
                return@input
            }
            val tradeShares = trade[0]
            val tradePrice = trade[1]
            scope.launch {
                var stockLabel: String? = null
                var currentPrice: Double? = null
                if (keyword.isNotBlank()) {
                    // 搜索与实时价查询都是阻塞 IO，放到 IO 线程执行。
                    val looked = withContext(Dispatchers.IO) {
                        val sina = SinaClient()
                        val hit = runCatching { sina.search(keyword).firstOrNull() }.getOrNull() ?: return@withContext null
                        val price = runCatching { sina.quote(hit.first)?.price }.getOrNull()
                        (hit.first to hit.second) to price
                    }
                    if (looked != null) {
                        stockLabel = "${looked.first.second}（${looked.first.first}）"
                        currentPrice = looked.second
                    } else {
                        stockLabel = keyword
                    }
                }
                val result = runCatching {
                    TradeCalculator.calculate(
                        holdingShares = holding[0],
                        averageCost = holding[1],
                        side = side,
                        tradeShares = tradeShares,
                        tradePrice = tradePrice,
                        fees = fees,
                        currentPrice = currentPrice,
                    )
                }.getOrElse { error ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, error.message ?: "计算失败", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val report = TradeCalculator.formatReport(
                    result,
                    stockLabel,
                    if (fees > 0) "本次按填写费用计入" else "本次按0元费用计算，实际佣金/印花税会影响结果",
                )
                withContext(Dispatchers.Main) {
                    ReportActivity.open(context, "💰 买卖成本计算", report)
                }
            }
        }
    }

    /**
     * 补仓策略：反向测算。输入目标平均成本，取盘中分析的波段回踩区间 / 支撑 / MA20 / 现价
     * 作为候选买入价，回答「在什么价位补多少股能摊到目标成本」，并按深套、仓位、现金口径给约束。
     */
    fun costReductionStrategy(scope: CoroutineScope, context: Context) {
        val store = Store(context)
        val portfolio = store.portfolio
        input(
            context,
            "🎯 降低持仓成本买卖策略",
            "直接填想降到的目标成本。系统会结合趋势、支撑/阻力、MA20、波段区间与现有买卖信号，" +
                "给出回落补仓、冲高卖出后低位接回或风险减仓策略。只算不写回持仓配置。",
            listOf(
                Field("股票名称或代码（可留空）"),
                Field(
                    "操作前持仓：股数,平均成本",
                    action = FieldAction("同步持仓") { values ->
                        val keyword = values.getOrElse(0) { "" }
                        if (keyword.isBlank()) {
                            Toast.makeText(context, "请先输入股票名称或代码", Toast.LENGTH_SHORT).show()
                            return@FieldAction null
                        }
                        val result = syncHoldingInput(portfolio.positions, keyword)
                        when (result.status) {
                            HoldingSyncStatus.MATCHED -> {
                                Toast.makeText(context, "已同步${result.candidates.first().name}持仓", Toast.LENGTH_SHORT).show()
                                result.input
                            }
                            HoldingSyncStatus.AMBIGUOUS -> {
                                val names = result.candidates.joinToString("、") { it.name }
                                Toast.makeText(context, "匹配到多只持仓：$names，请输入完整名称或代码", Toast.LENGTH_SHORT).show()
                                null
                            }
                            HoldingSyncStatus.NOT_FOUND -> {
                                Toast.makeText(context, "持仓列表中未找到“$keyword”", Toast.LENGTH_SHORT).show()
                                null
                            }
                        }
                    },
                ),
                Field("目标持仓成本（元）", numeric = true),
                Field("参考买入价位（可留空，逗号分隔）"),
                Field(
                    "可用补仓资金（元，留空取可用现金）",
                    if (portfolio.resolvedAvailableCapital() > 0) formatNumber(portfolio.resolvedAvailableCapital()) else "",
                    numeric = true,
                ),
            ),
        ) { values ->
            val keyword = values.getOrElse(0) { "" }
            val holding = splitNumbers(values.getOrElse(1) { "" })
            val targetCost = values.getOrElse(2) { "" }.toDoubleOrNull()
            val customPrices = splitNumbers(values.getOrElse(3) { "" })
            val cash = values.getOrElse(4) { "" }.toDoubleOrNull() ?: portfolio.resolvedAvailableCapital()
            if (holding.size < 2 || targetCost == null || targetCost <= 0) {
                Toast.makeText(context, "请按提示填写：持仓填「股数,平均成本」，目标成本填大于0且低于当前成本的数字", Toast.LENGTH_LONG).show()
                return@input
            }
            scope.launch {
                var stockLabel: String? = null
                var code = ""
                var currentPrice: Double? = null
                if (keyword.isNotBlank()) {
                    // 搜索与实时价查询都是阻塞 IO，放到 IO 线程执行。
                    val looked = withContext(Dispatchers.IO) {
                        val sina = SinaClient()
                        val hit = runCatching { sina.search(keyword).firstOrNull() }.getOrNull() ?: return@withContext null
                        val price = runCatching { sina.quote(hit.first)?.price }.getOrNull()
                        (hit.first to hit.second) to price
                    }
                    if (looked != null) {
                        code = looked.first.first
                        stockLabel = "${looked.first.second}（${looked.first.first}）"
                        currentPrice = looked.second
                    } else {
                        stockLabel = keyword
                    }
                }
                val result = Repo.portfolioResults.firstOrNull { it.code == code }
                val swing = Repo.portfolioSwing?.validatedFor(portfolio)?.rows?.firstOrNull { it.code == code }
                val stopPrice = swing?.riskPrice ?: result?.entrySetup?.stopPrice
                val riskSignal = result?.signal?.contains("减仓") == true ||
                    result?.signal?.contains("退出") == true ||
                    result?.signal?.contains("风险") == true
                val adx = result?.trendStrength?.adx
                val plusDi = result?.trendStrength?.plusDi
                val minusDi = result?.trendStrength?.minusDi
                val trend = when {
                    riskSignal -> CostReductionTrend.DOWNTREND
                    adx != null && plusDi != null && minusDi != null && adx >= 20 && plusDi > minusDi ->
                        CostReductionTrend.UPTREND
                    adx != null && plusDi != null && minusDi != null && adx >= 20 && minusDi > plusDi ->
                        CostReductionTrend.DOWNTREND
                    swing?.status == com.example.stockmonitor.analysis.SwingStatus.READY -> CostReductionTrend.UPTREND
                    result != null -> CostReductionTrend.RANGE
                    else -> CostReductionTrend.UNKNOWN
                }
                val analysisNote = listOfNotNull(
                    result?.action,
                    swing?.reason,
                    Repo.marketRiskContext?.portfolioNote(),
                ).joinToString("；").ifBlank { "暂无完整盘中分析，仅按填写价格和趋势规则测算" }
                val market = CostReductionMarket(
                    currentPrice = currentPrice ?: result?.price,
                    support = result?.support,
                    resistance = result?.resistance,
                    ma20 = result?.ma20,
                    entryLow = swing?.entryLow,
                    entryHigh = swing?.entryHigh,
                    stopPrice = stopPrice,
                    trend = trend,
                    buyConfirmed = swing?.status == com.example.stockmonitor.analysis.SwingStatus.READY ||
                        result?.signal?.contains("买入确认") == true,
                    signal = result?.signal,
                    marketNote = analysisNote,
                )

                val plan = runCatching {
                    AddPositionStrategy.costReductionPlan(
                        code = code,
                        holdingShares = holding[0],
                        averageCost = holding[1],
                        targetCost = targetCost,
                        market = market,
                        extraBuyLevels = customPrices.mapIndexed { index, price ->
                            AddLevel("自定义价位${index + 1}", price)
                        },
                        totalCapital = portfolio.totalCapital,
                        maxPositionPct = portfolio.maxPositionPct,
                        availableCapital = cash,
                    )
                }.getOrElse { error ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, error.message ?: "计算失败", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val report = AddPositionStrategy.formatCostReductionReport(plan, stockLabel)
                withContext(Dispatchers.Main) {
                    ReportActivity.open(context, "🎯 降低持仓成本买卖策略", report)
                }
            }
        }
    }

    /** 持仓设置：总资金与仓位比例，等价于 设置总资金。 */
    fun portfolioCapital(activity: AppCompatActivity, onSaved: () -> Unit) {
        val store = Store(activity)
        val portfolio = store.portfolio
        input(
            activity,
            "💰 持仓资金设置",
            "总资金用于计算持仓占比；不填可用现金时，系统按总资金减去持仓成本自动计算。",
            listOf(
                Field("总资金（元）", formatNumber(portfolio.totalCapital), numeric = true),
                Field("单股初始仓位比例（%）", formatNumber(portfolio.initialEntryPct), numeric = true),
                Field("单股最大仓位比例（%）", formatNumber(portfolio.maxPositionPct), numeric = true),
                Field(
                    "可用现金校正（元，留空自动计算）",
                    if (portfolio.availableCapitalIsManual()) formatNumber(portfolio.resolvedAvailableCapital()) else "",
                    numeric = true,
                ),
            ),
        ) { values ->
            val total = values.getOrElse(0) { "" }.toDoubleOrNull() ?: portfolio.totalCapital
            val initial = values.getOrElse(1) { "" }.toDoubleOrNull() ?: portfolio.initialEntryPct
            val max = values.getOrElse(2) { "" }.toDoubleOrNull() ?: portfolio.maxPositionPct
            val cashText = values.getOrElse(3) { "" }.trim()
            val manual = cashText.toDoubleOrNull()
            if (cashText.isNotBlank() && (manual == null || manual < 0)) {
                Toast.makeText(activity, "可用现金校正必须是大于等于 0 的数字，留空则自动计算", Toast.LENGTH_LONG).show()
                return@input
            }
            store.portfolio = portfolio.copy(
                totalCapital = total,
                initialEntryPct = initial,
                maxPositionPct = max,
                availableCapital = manual ?: 0.0,
                availableCapitalOverride = manual,
            )
            onSaved()
        }
    }

    /** 记录一笔真实买卖；同时维护交易批次和持仓总量。 */
    fun recordTrade(context: Context, onSaved: () -> Unit) {
        val store = Store(context)
        val portfolio = store.portfolio
        if (portfolio.positions.isEmpty()) {
            Toast.makeText(context, "请先添加监控股票", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = portfolio.positions.map { "${it.name}（${it.code}）" }
        input(
            context,
            "📒 记录交易",
            "用于计算 A 股 T+1 可卖数量。当天买入的股份当天不可卖出；卖出数量不得超过当天可卖仓位。",
            listOf(
                Field("股票", labels.first(), choices = labels),
                Field("交易日期", TimeUtils.todayText()),
                Field("操作", "买入", choices = listOf("买入", "卖出")),
                Field("数量（股）", "100", numeric = true, decimal = false),
                Field("成交价格（元）", numeric = true),
            ),
        ) { values ->
            val selectedIndex = labels.indexOf(values.getOrElse(0) { labels.first() })
            val position = portfolio.positions.getOrNull(selectedIndex)
            if (position == null) {
                Toast.makeText(context, "请选择股票", Toast.LENGTH_SHORT).show()
                return@input
            }
            val date = values.getOrElse(1) { "" }.trim()
            val sideText = values.getOrElse(2) { "买入" }
            val quantity = values.getOrElse(3) { "" }.toDoubleOrNull()
            val price = values.getOrElse(4) { "" }.toDoubleOrNull()
            val today = TimeUtils.todayText()
            if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || date > today) {
                Toast.makeText(context, "交易日期必须是不晚于今天的 yyyy-MM-dd", Toast.LENGTH_LONG).show()
                return@input
            }
            if (quantity == null || quantity <= 0 || !quantity.isFinite() ||
                price == null || price <= 0 || !price.isFinite()
            ) {
                Toast.makeText(context, "数量和成交价格必须是大于 0 的数字", Toast.LENGTH_LONG).show()
                return@input
            }
            val side = if (sideText == "卖出") TradeSide.SELL else TradeSide.BUY
            val book = store.tradeBook.takeIf { it.lots.isNotEmpty() || it.entries.isNotEmpty() }
                ?: TradeLotLedger.migrate(portfolio.positions)
            val record = TradeRecord(
                id = "${position.code}-$date-${System.currentTimeMillis()}",
                code = position.code,
                name = position.name,
                side = side,
                date = date,
                quantity = quantity,
                price = price,
                source = TradeSource.MANUAL,
            )
            val applied = try {
                if (side == TradeSide.BUY) {
                    TradeLotLedger.buyToPortfolio(book, portfolio, record)
                } else {
                    TradeLotLedger.sellToPortfolio(book, portfolio, record)
                }
            } catch (error: IllegalStateException) {
                Toast.makeText(context, error.message ?: "交易记录失败", Toast.LENGTH_LONG).show()
                return@input
            }
            store.tradeBook = applied.book
            store.portfolio = applied.portfolio
            Toast.makeText(context, "交易已记录", Toast.LENGTH_SHORT).show()
            onSaved()
        }
    }

    /** 追加监控股票，已在列表中的会跳过。 */
    fun addPosition(scope: CoroutineScope, context: Context, onSaved: () -> Unit) {
        searchStock(scope, context) { code, name ->
            val store = Store(context)
            val portfolio = store.portfolio
            if (portfolio.positions.any { it.code == code }) {
                Toast.makeText(context, "$name 已在监控列表中", Toast.LENGTH_SHORT).show()
                return@searchStock
            }
            input(
                context,
                "添加 $name",
                "数量填 0 表示只观察、不计算持仓成本。",
                listOf(Field("持仓数量（股）", "0", numeric = true), Field("持仓成本（元）", "0", numeric = true)),
            ) { values ->
                val quantity = values.getOrElse(0) { "0" }.toDoubleOrNull() ?: 0.0
                val cost = values.getOrElse(1) { "0" }.toDoubleOrNull() ?: 0.0
                store.portfolio = portfolio.copy(
                    positions = (portfolio.positions + Position(code, name, quantity, cost)).toMutableList()
                )
                if (syncToWatchlist(context, code, name)) {
                    Toast.makeText(context, "已同时加入自选股（顶栏不显示）", Toast.LENGTH_SHORT).show()
                }
                onSaved()
            }
        }
    }

    /**
     * 「加入监控股票时同步到自选股」：默认开启。
     * 补进去的条目顶栏显示为关，避免常驻通知被慢慢塞满；已在自选股里则跳过。
     * 返回是否真的新增了。
     */
    private fun syncToWatchlist(context: Context, code: String, name: String): Boolean {
        val store = Store(context)
        if (!store.syncPositionToWatchlist) return false
        if (store.watchlist.any { it.code == code }) return false
        store.watchlist = (store.watchlist + WatchItem(code, name, showInBar = false)).toMutableList()
        return true
    }

    /** 删除监控股票，删除前确认。 */
    fun deletePosition(context: Context, onSaved: () -> Unit) {
        val store = Store(context)
        val portfolio = store.portfolio
        if (portfolio.positions.isEmpty()) {
            Toast.makeText(context, "当前没有监控中的股票", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = portfolio.positions.map { "${it.name}（${it.code}）" }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle("删除监控股票")
            .setItems(labels) { _, index ->
                val target = portfolio.positions[index]
                MaterialAlertDialogBuilder(context)
                    .setTitle("确认删除")
                    .setMessage("确定要从监控列表删除 ${target.name}（${target.code}）吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        store.portfolio = portfolio.copy(
                            positions = portfolio.positions.filterNot { it.code == target.code }.toMutableList()
                        )
                        onSaved()
                    }
                    .show()
            }
            .show()
    }

    /** 编辑已有持仓的数量和成本；数量、成本为 0 时保留为观察标的。 */
    fun editPosition(context: Context, onSaved: () -> Unit) {
        val store = Store(context)
        val portfolio = store.portfolio
        if (portfolio.positions.isEmpty()) {
            Toast.makeText(context, "当前没有可编辑的持仓股票", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = portfolio.positions.map { position ->
            "${position.name}（${position.code}）｜${formatNumber(position.quantity)} 股｜成本 ${formatNumber(position.cost)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle("编辑持仓")
            .setItems(labels) { _, index ->
                val target = portfolio.positions[index]
                input(
                    context,
                    "编辑 ${target.name}",
                    "数量和成本都可以填 0；数量为 0 时仍会继续提醒买入信号。",
                    listOf(
                        Field("持仓数量（股）", formatNumber(target.quantity), numeric = true),
                        Field("持仓成本（元）", formatNumber(target.cost), numeric = true),
                    ),
                ) { values ->
                    val quantity = values.getOrElse(0) { "0" }.toDoubleOrNull()
                    val cost = values.getOrElse(1) { "0" }.toDoubleOrNull()
                    if (quantity == null || cost == null || quantity < 0 || cost < 0) {
                        Toast.makeText(context, "数量和成本必须是大于等于 0 的数字", Toast.LENGTH_SHORT).show()
                        return@input
                    }
                    store.portfolio = portfolio.updatePosition(target.code, quantity, cost)
                    onSaved()
                }
            }
            .show()
    }

    /** 自选股（菜单栏显示项）管理。 */
    fun addWatchItem(scope: CoroutineScope, context: Context, onSaved: () -> Unit) {
        searchStock(scope, context) { code, name ->
            val store = Store(context)
            if (store.watchlist.any { it.code == code }) {
                Toast.makeText(context, "$name 已在自选股中", Toast.LENGTH_SHORT).show()
                return@searchStock
            }
            input(
                context,
                "添加 $name",
                "顶栏显示表示在常驻通知中展示。",
                listOf(Field("顶栏显示", value = "是", choices = listOf("是", "否"))),
            ) { values ->
                val showInBar = values.firstOrNull() != "否"
                store.watchlist = (store.watchlist + WatchItem(code, name, showInBar)).toMutableList()
                onSaved()
            }
        }
    }

    fun splitNumbers(text: String): List<Double> =
        text.split(",", "，").mapNotNull { it.trim().toDoubleOrNull() }

    fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.CHINA, "%.2f", value)

    private class BoundedScrollView(
        context: Context,
        private val maxHeightPx: Int,
    ) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> maxHeightPx
                else -> minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeightPx)
            }
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxOf(availableHeight, 0), MeasureSpec.AT_MOST),
            )
        }
    }
}
