package com.example.stockmonitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.Tone
import com.example.stockmonitor.analysis.VolumePriceSignal
import com.google.android.material.card.MaterialCardView
import com.example.stockmonitor.monitor.BackgroundTaskState
import com.example.stockmonitor.monitor.TaskPhase

/** 统一的卡片/文本样式，避免每个页面重复写布局代码。 */
object UiKit {

    fun color(context: Context, @ColorRes res: Int): Int = ContextCompat.getColor(context, res)

    /**
     * Android 15 起 targetSdk 35 强制边到边显示，工具栏会顶到状态栏下面。
     * 传入 appBar 时把状态栏高度补进工具栏（背景与状态栏连成一块），
     * 否则直接给根布局加内边距；底部避开导航栏与输入法。
     *
     * 注意：Material 的 NavigationBarView（底部 Tab）会自己把系统导航栏的高度吃成自身 padding，
     * 所以有底部栏时根布局不能再补一遍导航栏高度，否则栏下方会多出一条空白带；这时只补输入法的高度。
     */
    fun applySystemBarInsets(root: View, appBar: View? = null, bottomBar: View? = null) {
        val appBarHeight = appBar?.layoutParams?.height?.takeIf { it > 0 } ?: 0
        val bottomBarHeight = bottomBar?.layoutParams?.height?.takeIf { it > 0 } ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = isImeVisible(root, insets.isVisible(WindowInsetsCompat.Type.ime()), ime.bottom)
            val bottom = UiInteractionPolicy.bottomBarInset(bars.bottom, ime.bottom)

            var handledTop = false
            if (appBar != null && appBarHeight > 0) {
                appBar.setPadding(appBar.paddingLeft, bars.top, appBar.paddingRight, 0)
                val params = appBar.layoutParams
                val target = appBarHeight + bars.top
                if (params.height != target) {
                    params.height = target
                    appBar.layoutParams = params
                }
                handledTop = true
            }

            // 有底部栏时只补输入法，导航栏交给底部栏自己处理。
            val rootBottom = if (bottomBar != null) ime.bottom else bottom
            var handledBottom = false
            if (bottomBar != null && bottomBarHeight > 0) {
                bottomBar.visibility = if (imeVisible) View.GONE else View.VISIBLE
                bottomBar.setPadding(bottomBar.paddingLeft, 0, bottomBar.paddingRight, bottom)
                val params = bottomBar.layoutParams
                val target = bottomBarHeight + bottom
                if (params.height != target) {
                    params.height = target
                    bottomBar.layoutParams = params
                }
                handledBottom = true
            }

            view.setPadding(
                bars.left,
                if (handledTop) 0 else bars.top,
                bars.right,
                if (handledBottom) 0 else rootBottom,
            )
            insets
        }
        if (bottomBar != null) {
            root.viewTreeObserver.addOnGlobalLayoutListener {
                val frame = Rect()
                root.getWindowVisibleDisplayFrame(frame)
                bottomBar.visibility = if (UiInteractionPolicy.isImeVisible(
                        insetsVisible = false,
                        imeBottom = 0,
                        rootHeight = root.rootView.height,
                        visibleFrameBottom = frame.bottom,
                        threshold = dp(root.context, 160),
                    )
                ) View.GONE else View.VISIBLE
            }
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun isImeVisible(root: View, insetsVisible: Boolean, imeBottom: Int): Boolean {
        val frame = Rect()
        root.getWindowVisibleDisplayFrame(frame)
        return UiInteractionPolicy.isImeVisible(
            insetsVisible = insetsVisible,
            imeBottom = imeBottom,
            rootHeight = root.rootView.height,
            visibleFrameBottom = frame.bottom,
            threshold = dp(root.context, 160),
        )
    }

    /** 中国市场习惯：涨红跌绿。 */
    fun changeColor(context: Context, change: Double): Int = when {
        change > 0.0001 -> color(context, R.color.quote_up)
        change < -0.0001 -> color(context, R.color.quote_down)
        else -> color(context, R.color.quote_flat)
    }

    /** 量价信号的图标：只给资金明显在进（放量上涨）或剧烈下跌（急跌）的组合加。 */
    /** 图标由信号自带：🔥 = 资金进场确认，⚠️ = 需要警惕。 */
    fun signalIcon(signal: VolumePriceSignal): String = signal.icon

    /**
     * 量价标签的颜色只表达性质，不表达好坏：
     * 红 = 资金进场确认（放量上涨），橙 = 需要警惕（高位放量 / 滞涨 / 加速 / 两种急跌），
     * 灰 = 中性提示。
     */
    fun signalColor(context: Context, signal: VolumePriceSignal): Int = when (signal.tone) {
        Tone.UP -> color(context, R.color.quote_up)
        Tone.CAUTION -> color(context, R.color.warn)
        Tone.NEUTRAL -> color(context, R.color.text_secondary)
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** 打上这个 tag 的控件是「展开/收起」「立即分析」这类操作入口，其文字不进复制文案。 */
    private const val SKIP_COPY = "ui:skip-copy"

    /** 挂在视图 tag 上的整段文案（如收缩行的完整详情）；[collectText] 优先读它，避免折叠未展开时漏内容。 */
    private class CopyableText(val value: String)

    /** 复制到剪贴板，带 Toast 反馈（13+ 系统只在后台写入时才自带提示，前台还是自己报）。 */
    fun copyToClipboard(context: Context, text: String, label: String? = null) {
        if (text.isBlank()) {
            Toast.makeText(context, "这张卡片还没有可复制的文字", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("卡片文案", text))
        Toast.makeText(context, if (label == null) "已复制到剪贴板" else "已复制「$label」", Toast.LENGTH_SHORT).show()
    }

    /**
     * 深度优先收集视图树里的文字，拼成纯文本：
     * 横向一行里只有文本时拼成一行（「总资金」+「123 元」→「总资金：123 元」），
     * 按钮与操作入口不进文案；折叠中的内容也收集——复制的是卡片全部内容，不是当前可见快照。
     */
    fun collectText(root: View): String {
        val out = StringBuilder()
        fun textOf(view: View): String? =
            (view as? TextView)?.text?.toString()?.trim()?.takeUnless { it.isNullOrEmpty() }
        fun appendLine(text: String) {
            if (text.isEmpty()) return
            if (out.isNotEmpty()) out.append('\n')
            out.append(text)
        }
        fun walk(view: View) {
            val tag = view.tag
            if (tag is CopyableText) {
                appendLine(tag.value)
                return
            }
            if (tag == SKIP_COPY) return
            when (view) {
                // MaterialButton 也是 Button：操作按钮不是文案。
                is android.widget.Button -> return
                is TextView -> appendLine(textOf(view) ?: return)
                is ViewGroup -> {
                    val children = (0 until view.childCount).map { view.getChildAt(it) }
                    if (view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL &&
                        children.isNotEmpty() &&
                        children.all { it is TextView && it !is android.widget.Button }
                    ) {
                        val texts = children.filter { it.tag != SKIP_COPY }.mapNotNull { textOf(it) }
                        // 两个文本的行是「标签 + 数值」，用冒号；三个以上（行情行）用空格。
                        val joiner = if (children.size == 2) "：" else " "
                        appendLine(texts.joinToString(joiner))
                    } else {
                        children.forEach(::walk)
                    }
                }
            }
        }
        walk(root)
        return out.toString()
    }

    fun label(context: Context, text: String, size: Float = 12f): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color(context, R.color.text_secondary))
    }

    fun value(context: Context, text: String, size: Float = 14f, textColor: Int? = null): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            gravity = Gravity.END
            setTextColor(textColor ?: color(context, R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
        }

    /** 传入 Spannable 时以 Span 的颜色为准，用于在一行里只把成交额等局部文字染色。 */
    fun body(context: Context, text: CharSequence, size: Float = 13f, textColor: Int? = null): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            includeFontPadding = false
            setTextColor(textColor ?: color(context, R.color.text_secondary))
            setLineSpacing(dp(context, 2).toFloat(), 1f)
        }

    /**
     * 统一输入框外观：圆角填充块 + 聚焦时的品牌蓝描边（[R.drawable.bg_input] 里的状态选择器）。
     *
     * 单行框靠 gravity 垂直居中，不再靠上下 padding 凑高度，字号变大或系统字体放大时也不会顶到边框。
     * 多行框顶对齐，上下留足内边距，避免第一行文字贴着圆角。
     */
    fun styleInput(edit: EditText, multiline: Boolean = false) {
        val context = edit.context
        edit.setBackgroundResource(R.drawable.bg_input)
        edit.setTextColor(color(context, R.color.text_primary))
        edit.setHintTextColor(color(context, R.color.input_hint))
        edit.highlightColor = color(context, R.color.input_selection)
        edit.textSize = 15f
        edit.includeFontPadding = false
        edit.setLineSpacing(dp(context, 3).toFloat(), 1f)
        edit.gravity = if (multiline) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL or Gravity.START
        edit.setPadding(
            dp(context, 14),
            if (multiline) dp(context, 12) else 0,
            dp(context, 14),
            if (multiline) dp(context, 12) else 0,
        )
        edit.minHeight = dp(context, if (multiline) 88 else 48)
        // 光标着色是 TextView 的公开 API 起步于 29，之前的系统用默认光标。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            edit.textCursorDrawable = ContextCompat.getDrawable(context, R.drawable.bg_input_cursor)
        }
        // 输入框和上面的说明文字之间原本只隔 addText 的 2dp，挤在一起像被压扁；
        // 这里自带上下间距，调用方不用再单独加 margin。
        if (edit.layoutParams == null) {
            edit.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, if (multiline) 8 else 10)
                bottomMargin = dp(context, if (multiline) 2 else 4)
            }
        }
    }

    fun divider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxOf(1, dp(context, 1)),
        ).apply { topMargin = dp(context, 8); bottomMargin = dp(context, 8) }
        setBackgroundColor(color(context, R.color.divider))
    }

    fun empty(context: Context, text: String): TextView = body(context, text).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(context, 18), 0, dp(context, 18))
    }

    fun card(
        context: Context,
        title: String? = null,
        actionText: String? = null,
        onSecondaryAction: (() -> Boolean)? = null,
        onAction: (() -> Unit)? = null,
    ): Card =
        Card(
            context,
            title,
            actionText,
            onAction = onAction,
            secondaryAction = onSecondaryAction,
        )

    fun collapsibleCard(
        context: Context,
        title: String,
        expanded: Boolean,
        onExpansionChanged: (Boolean) -> Unit,
    ): Card = Card(context, title, null, null, expanded, onExpansionChanged)

    /** 卡片容器：标题 + 若干行内容。 */
    class Card internal constructor(
        private val context: Context,
        title: String?,
        actionText: String?,
        onAction: (() -> Unit)?,
        initialExpanded: Boolean? = null,
        onExpansionChanged: ((Boolean) -> Unit)? = null,
        secondaryAction: (() -> Boolean)? = null,
    ) {
        val view: MaterialCardView = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(context, 10) }
            radius = dp(context, 14).toFloat()
            cardElevation = dp(context, 2).toFloat()
            setCardBackgroundColor(color(context, R.color.card_bg))
            strokeWidth = dp(context, 1)
            strokeColor = color(context, R.color.card_stroke)
        }

        val inner: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
        }

        val body: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        private var secondaryActionView: TextView? = null

        init {
            view.addView(inner)
            if (title != null) {
                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                header.addView(
                    TextView(context).apply {
                        text = title
                        textSize = 15.5f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(color(context, R.color.text_primary))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                )
                if (actionText != null && onAction != null) {
                    header.addView(
                        TextView(context).apply {
                            text = actionText
                            tag = SKIP_COPY
                            textSize = 13f
                            setTextColor(color(context, R.color.brand_primary))
                            setPadding(dp(context, 8), dp(context, 4), 0, dp(context, 4))
                            setOnClickListener { onAction() }
                        }
                    )
                }
                if (secondaryAction != null) {
                    val secondaryLabel = TextView(context).apply {
                        textSize = 12f
                        tag = SKIP_COPY
                        setTextColor(color(context, R.color.brand_primary))
                        setPadding(dp(context, 8), dp(context, 4), 0, dp(context, 4))
                        setOnClickListener {
                            setSecondaryExpanded(secondaryAction())
                        }
                    }
                    secondaryActionView = secondaryLabel
                    header.addView(secondaryLabel)
                    setSecondaryExpanded(false)
                }
                val headerDivider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        maxOf(1, dp(context, 1)),
                    ).apply { topMargin = dp(context, 6); bottomMargin = dp(context, 5) }
                    setBackgroundColor(color(context, R.color.divider))
                }
                if (initialExpanded != null) {
                    var expanded = initialExpanded
                    val toggleLabel = TextView(context).apply {
                        textSize = 12f
                        tag = SKIP_COPY
                        setTextColor(color(context, R.color.brand_primary))
                        setPadding(dp(context, 8), 0, 0, 0)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    header.addView(toggleLabel)
                    header.minimumHeight = dp(context, 48)
                    header.isFocusable = true
                    header.contentDescription = title
                    fun updateExpansion() {
                        body.visibility = if (expanded) View.VISIBLE else View.GONE
                        headerDivider.visibility = body.visibility
                        toggleLabel.text = if (expanded) "收起 ▴" else "展开 ▾"
                        ViewCompat.setStateDescription(header, if (expanded) "已展开" else "已收起")
                    }
                    updateExpansion()
                    header.setOnClickListener {
                        expanded = !expanded
                        updateExpansion()
                        onExpansionChanged?.invoke(expanded)
                    }
                }
                // 整卡复制的常驻入口；自身打 SKIP_COPY，免得「复制」二字混进文案。
                header.addView(
                    TextView(context).apply {
                        text = "📋 复制"
                        textSize = 12f
                        setTextColor(color(context, R.color.brand_primary))
                        setPadding(dp(context, 8), dp(context, 4), 0, dp(context, 4))
                        tag = SKIP_COPY
                        contentDescription = "复制整张卡片的文字"
                        setOnClickListener { copyToClipboard(context, collectText(view), title) }
                    }
                )
                inner.addView(header)
                inner.addView(headerDivider)
            }
            inner.addView(body)
            // 长按卡片的文字区域同样整卡复制；行内可点击区域（展开、按钮）各自消费事件，不受影响。
            view.setOnLongClickListener {
                copyToClipboard(context, collectText(view), title)
                true
            }
        }

        fun setSecondaryExpanded(allExpanded: Boolean) {
            secondaryActionView?.text = if (allExpanded) "全部收起 ▴" else "全部展开 ▾"
        }

        fun addRow(view: View): Card {
            body.addView(view)
            return this
        }

        /**
         * 左右两列的一行：左标题 + 右数值。
         *
         * 标题占自然宽度、数值吃剩余空间。反过来（标题 weight、数值 wrap）会让长数值
         * 先把行宽占满，把「个股日内跌幅」这类标题挤成一字一行的竖排。
         */
        fun addKeyValue(
            label: String,
            value: String,
            valueColor: Int? = null,
            note: CharSequence? = null,
        ): Card {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(context, 5), 0, dp(context, 5))
            }
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(UiKit.label(context, label, 13f))
            line.addView(
                UiKit.value(context, value, 14f, valueColor).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                }
            )
            row.addView(line)
            if (note != null) row.addView(UiKit.body(context, note, 11.5f))
            body.addView(row)
            return this
        }

        fun addText(text: String, size: Float = 13f, textColor: Int? = null): Card {
            body.addView(
                UiKit.body(context, text, size, textColor).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(context, 2)
                        bottomMargin = dp(context, 2)
                    }
                }
            )
            return this
        }

        fun addTaskStatus(task: BackgroundTaskState): Card {
            val taskColor = when (task.phase) {
                TaskPhase.FAILURE -> color(context, R.color.warn)
                TaskPhase.SUCCESS -> color(context, R.color.risk_good)
                else -> color(context, R.color.text_secondary)
            }
            addText("后台任务：${task.title}\n${task.displayText}", 12f, taskColor)
            if (task.isActive && task.total > 0) {
                add(
                    android.widget.ProgressBar(
                        context,
                        null,
                        android.R.attr.progressBarStyleHorizontal,
                    ).apply {
                        max = task.total
                        progress = task.done.coerceIn(0, task.total)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(context, 4),
                        ).apply {
                            topMargin = dp(context, 6)
                            bottomMargin = dp(context, 4)
                        }
                    }
                )
            }
            return this
        }

        fun addDivider(): Card {
            body.addView(UiKit.divider(context))
            return this
        }

        fun add(view: View): Card = addRow(view)
    }

    /** 行情列表行：名称(代码) + 现价 + 涨跌幅。 */
    fun quoteRow(
        context: Context,
        name: String,
        code: String,
        price: String,
        changePct: Double,
        note: CharSequence? = null,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        }
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        line.addView(
            TextView(context).apply {
                text = name
                textSize = 15f
                setTextColor(color(context, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        line.addView(
            TextView(context).apply {
                text = price
                textSize = 15f
                gravity = Gravity.END
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(changeColor(context, changePct))
            }
        )
        line.addView(
            TextView(context).apply {
                // 拿不到实时行情时不给假数字，直接留空位。
                text = if (changePct.isNaN()) "" else String.format(java.util.Locale.CHINA, "  %+.2f%%", changePct)
                textSize = 13f
                gravity = Gravity.END
                setTextColor(changeColor(context, changePct))
                minWidth = dp(context, 68)
            }
        )
        row.addView(line)
        val detail: CharSequence = note ?: code
        row.addView(UiKit.body(context, detail, 11.5f))
        return row
    }

    data class DetailRow(
        val label: String,
        val value: String,
        /** 用于把操作建议等相关信息组成独立的视觉区块。 */
        val dividerBefore: Boolean = false,
        val dividerAfter: Boolean = false,
    )

    class ExpandableQuoteRowView(
        val view: View,
        private val applyExpansion: (Boolean) -> Unit,
    ) {
        fun setExpanded(expanded: Boolean) {
            applyExpansion(expanded)
        }
    }

    /**
     * 个股列表的收缩行：点击核心区域展开结构化详情，点击状态文字也能切换。
     * 状态按 [stateKey] 保存在页面会话中，行情刷新重建视图后不会意外收起。
     */
    fun expandableQuoteRow(
        context: Context,
        stateKey: String,
        name: String,
        code: String,
        price: String,
        changePct: Double,
        summary: CharSequence,
        details: List<DetailRow>,
        onExpansionChanged: (Boolean) -> Unit = {},
    ): ExpandableQuoteRowView {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // 本行的完整纯文本：名称/行情/摘要 + 全部详情（结构化数据现拼，不依赖详情是否展开过）。
        val rowText = buildString {
            append(name).append("（").append(code).append("）现价 ").append(price)
            if (!changePct.isNaN()) append(String.format(java.util.Locale.CHINA, " %+.2f%%", changePct))
            summary.toString().trim().takeUnless { it.isEmpty() }?.let { append('\n').append(it) }
            details.filter { it.value.isNotBlank() }.forEach { detail ->
                append('\n').append(detail.label).append("：").append(detail.value)
            }
        }
        val core = quoteRow(context, name, code, price, changePct, summary)
        root.addView(core)

        val detailsView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 8), dp(context, 2), dp(context, 4), dp(context, 5))
            visibility = if (QuoteRowExpansionState.isExpanded(stateKey)) View.VISIBLE else View.GONE
        }
        fun buildDetails() {
            details.forEach { detail ->
                if (detail.dividerBefore) detailsView.addView(divider(context))
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(context, 2), 0, dp(context, 2))
                }
                row.addView(
                    TextView(context).apply {
                        text = detail.label
                        textSize = 11.5f
                        setTextColor(color(context, R.color.text_secondary))
                        layoutParams = LinearLayout.LayoutParams(
                            dp(context, 82),
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    }
                )
                row.addView(
                    body(context, detail.value, 11.5f, color(context, R.color.text_primary)).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                    }
                )
                detailsView.addView(row)
                if (detail.dividerAfter) detailsView.addView(divider(context))
            }
        }
        root.addView(detailsView)

        val toggleLabel = TextView(context).apply {
            textSize = 11.5f
            tag = SKIP_COPY
            setTextColor(color(context, R.color.brand_primary))
            gravity = Gravity.END
            setPadding(dp(context, 8), 0, dp(context, 4), dp(context, 4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(toggleLabel)

        fun updateExpansion(expanded: Boolean) {
            if (expanded && detailsView.childCount == 0) buildDetails()
            detailsView.visibility = if (expanded) View.VISIBLE else View.GONE
            toggleLabel.text = if (expanded) "收起详情 ▴" else "展开详情 ▾"
            ViewCompat.setStateDescription(core, if (expanded) "已展开详情" else "已收起详情")
        }

        val toggleExpansion = View.OnClickListener {
            val expanded = QuoteRowExpansionState.toggle(stateKey)
            updateExpansion(expanded)
            onExpansionChanged(expanded)
        }
        core.isClickable = true
        core.isFocusable = true
        core.contentDescription = "$name $code"
        core.setOnClickListener(toggleExpansion)
        toggleLabel.setOnClickListener(toggleExpansion)
        // 长按这一行：复制本股完整文案（详情没展开也照抄），方便喂给 AI 或转发；与单击展开互不打架。
        core.setOnLongClickListener {
            copyToClipboard(context, rowText, name)
            true
        }
        // 整卡复制时 [collectText] 优先读这份全文，折叠未展开也不会漏详情。
        root.tag = CopyableText(rowText)
        updateExpansion(QuoteRowExpansionState.isExpanded(stateKey))
        return ExpandableQuoteRowView(view = root, applyExpansion = { updateExpansion(it) })
    }
}
