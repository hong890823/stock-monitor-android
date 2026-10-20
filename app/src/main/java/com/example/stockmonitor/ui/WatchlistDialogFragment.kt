package com.example.stockmonitor.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stockmonitor.R
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.core.WatchItem
import com.example.stockmonitor.monitor.Repo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

internal fun watchlistIconButton(context: Context, icon: Int, label: String, action: () -> Unit): ImageButton =
    ImageButton(context).apply {
        setImageResource(icon)
        contentDescription = label
        tooltipText = label
        imageTintList = ColorStateList.valueOf(UiKit.color(context, R.color.text_secondary))
        val selectable = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true)
        setBackgroundResource(selectable.resourceId)
        setPadding(UiKit.dp(context, 12), UiKit.dp(context, 12), UiKit.dp(context, 12), UiKit.dp(context, 12))
        layoutParams = LinearLayout.LayoutParams(UiKit.dp(context, 48), UiKit.dp(context, 48))
        setOnClickListener { action() }
    }

internal fun watchlistCard(
    context: Context,
    items: List<WatchItem>,
    onAdd: () -> Unit,
    onManage: () -> Unit,
): UiKit.Card = UiKit.card(context).apply {
    add(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(UiKit.body(context, "自选股管理", 15.5f, UiKit.color(context, R.color.text_primary)))
            addView(UiKit.label(context, "共${items.size}只 · 顶栏${items.count { it.showInBar }}只", 12f))
        })
        addView(watchlistIconButton(context, android.R.drawable.ic_input_add, "添加自选股", onAdd))
        addView(watchlistIconButton(context, android.R.drawable.ic_menu_manage, "管理自选股", onManage))
    })
}

class WatchlistDialogFragment : DialogFragment() {
    private var stockList: RecyclerView? = null
    private var emptyView: TextView? = null
    private var stockAdapter: WatchlistAdapter? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val store = Store(context)
        val adapter = WatchlistAdapter(
            onToggle = { item, enabled ->
                store.watchlist = WatchlistManagement.setTopBar(store.watchlist, item.code, enabled).toMutableList()
                refreshItems()
                Repo.notifyChanged()
            },
            onDelete = { item ->
                MaterialAlertDialogBuilder(context)
                    .setTitle("删除自选股")
                    .setMessage("确定删除 ${item.name}（${item.code}）吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        store.watchlist = WatchlistManagement.remove(store.watchlist, item.code).toMutableList()
                        refreshItems()
                        Repo.notifyChanged()
                    }
                    .show()
            },
        )
        stockAdapter = adapter
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 16), 0, UiKit.dp(context, 8), 0)
        }
        stockList = object : RecyclerView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val screenLimit = (resources.displayMetrics.heightPixels * 0.55).toInt()
                val limit = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) screenLimit
                    else minOf(screenLimit, MeasureSpec.getSize(heightSpec))
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
            }
        }.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            addItemDecoration(DividerItemDecoration(context, LinearLayoutManager.VERTICAL))
            itemAnimator = null
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        emptyView = UiKit.empty(context, "暂无自选股")
        content.addView(stockList)
        content.addView(emptyView)
        return MaterialAlertDialogBuilder(context)
            .setTitle("自选股管理")
            .setView(content)
            .setNeutralButton("添加", null)
            .setPositiveButton("完成", null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        refreshItems()
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            Dialogs.addWatchItem(lifecycleScope, requireContext()) {
                refreshItems()
                Repo.notifyChanged()
            }
        }
    }

    private fun refreshItems() {
        val context = context ?: return
        val items = Store(context).watchlist.toList()
        stockAdapter?.submitList(items)
        stockList?.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        emptyView?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        dialog?.setTitle("自选股管理 · ${items.size}只")
    }

    override fun onDestroyView() {
        stockList?.adapter = null
        stockList = null
        stockAdapter = null
        emptyView = null
        super.onDestroyView()
    }
}

private class WatchlistAdapter(
    private val onToggle: (WatchItem, Boolean) -> Unit,
    private val onDelete: (WatchItem) -> Unit,
) : ListAdapter<WatchItem, WatchlistAdapter.Row>(object : DiffUtil.ItemCallback<WatchItem>() {
    override fun areItemsTheSame(old: WatchItem, new: WatchItem): Boolean = old.code == new.code
    override fun areContentsTheSame(old: WatchItem, new: WatchItem): Boolean = old == new
}) {
    class Row(context: Context) : RecyclerView.ViewHolder(LinearLayout(context)) {
        val name = UiKit.body(context, "", 14f, UiKit.color(context, R.color.text_primary))
        val code = UiKit.label(context, "", 12f)
        val topBar = MaterialSwitch(context).apply { text = "顶栏"; textSize = 12f }
        val delete = watchlistIconButton(context, android.R.drawable.ic_menu_delete, "删除自选股") {}

        init {
            (itemView as LinearLayout).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = UiKit.dp(context, 64)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, UiKit.dp(context, 8), 0, UiKit.dp(context, 8))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(0, 0, UiKit.dp(context, 8), 0)
                    addView(name)
                    addView(code)
                })
                addView(topBar)
                addView(delete)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row = Row(parent.context)

    override fun onBindViewHolder(holder: Row, position: Int) {
        val item = getItem(position)
        holder.name.text = item.name
        holder.code.text = item.code
        holder.topBar.setOnCheckedChangeListener(null)
        holder.topBar.isChecked = item.showInBar
        holder.topBar.contentDescription = "${item.name} 顶栏显示"
        holder.topBar.setOnCheckedChangeListener { _, enabled -> onToggle(item, enabled) }
        holder.delete.contentDescription = "删除${item.name}（${item.code}）"
        holder.delete.tooltipText = "删除${item.name}"
        holder.delete.setOnClickListener { onDelete(item) }
    }
}
