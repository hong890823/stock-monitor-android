package com.example.stockmonitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.stockmonitor.R
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.core.TradeHistoryProjector
import com.example.stockmonitor.core.TradeSide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** 真实交易流水：展示成交事实与可追溯卖出的已实现盈亏。 */
class TradeHistoryActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var recordsList: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = TradeHistoryAdapter(::showRecordActions)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trade_history)
        UiKit.applySystemBarInsets(findViewById(R.id.root), findViewById(R.id.trade_history_toolbar))
        store = Store(this)
        recordsList = findViewById(R.id.trade_history_list)
        emptyView = findViewById(R.id.trade_history_empty)
        recordsList.layoutManager = LinearLayoutManager(this)
        recordsList.adapter = adapter
        findViewById<MaterialToolbar>(R.id.trade_history_toolbar).apply {
            setTitle(R.string.trade_history_title)
            setNavigationOnClickListener { finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        val rows = TradeHistoryProjector.project(store.tradeBook)
            .sortedWith(compareByDescending<TradeHistoryProjector.Row> { it.record.date }.thenByDescending { it.entryIndex })
        adapter.submitList(rows)
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        recordsList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun showRecordActions(row: TradeHistoryProjector.Row) {
        val record = row.record
        if (record.side != TradeSide.BUY || record.bookkeepingOnly) return
        MaterialAlertDialogBuilder(this)
            .setTitle("标记为仅登记买入批次")
            .setMessage(
                "仅在这笔买入的数量当时已经通过“编辑持仓”写入持仓时使用。" +
                    "标记后它只用于 T+1 批次，不再参与账户当日盈亏。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("标记") { _, _ ->
                store.tradeBook = store.tradeBook.copy(
                    entries = store.tradeBook.entries.map {
                        if (it.id == record.id) it.copy(bookkeepingOnly = true) else it
                    },
                )
                onResume()
            }
            .show()
    }
}

private class TradeHistoryAdapter(
    private val onRecordClick: (TradeHistoryProjector.Row) -> Unit,
) : RecyclerView.Adapter<TradeHistoryAdapter.RowHolder>() {
    private val rows = mutableListOf<TradeHistoryProjector.Row>()

    fun submitList(value: List<TradeHistoryProjector.Row>) {
        rows.clear()
        rows.addAll(value)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder = RowHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_trade_record, parent, false),
    )

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(rows[position], onRecordClick)

    class RowHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.trade_record_title)
        private val meta: TextView = itemView.findViewById(R.id.trade_record_meta)
        private val body: TextView = itemView.findViewById(R.id.trade_record_body)

        fun bind(row: TradeHistoryProjector.Row, onRecordClick: (TradeHistoryProjector.Row) -> Unit) {
            val record = row.record
            val side = when {
                record.bookkeepingOnly -> "补记买入批次"
                record.side == TradeSide.BUY -> "买入"
                else -> "卖出"
            }
            title.text = "$side · ${record.name}（${record.code}）"
            meta.text = "${record.date}｜${formatQuantity(record.quantity)} 股｜成交价 ${formatPrice(record.price)} 元｜费用 ${formatPrice(record.fee)} 元"
            body.text = when {
                record.bookkeepingOnly -> "批次补记：不改变持仓数量，不计入账户当日盈亏"
                record.side == TradeSide.BUY -> "已实现盈亏：—（买入后将在卖出时结算；如仅补批次可点此标记）"
                row.realizedPnl != null -> "已实现盈亏：${signedMoney(row.realizedPnl)} 元（已扣买卖费用）"
                else -> "已实现盈亏：暂无法计算（${row.unavailableReason.orEmpty()}）"
            }
            if (record.side == TradeSide.BUY && !record.bookkeepingOnly) {
                itemView.setOnClickListener { onRecordClick(row) }
            } else {
                itemView.setOnClickListener(null)
            }
        }

        private fun formatQuantity(value: Double): String = String.format(Locale.CHINA, "%,.0f", value)
        private fun formatPrice(value: Double): String = String.format(Locale.CHINA, "%,.2f", value)
        private fun signedMoney(value: Double): String = String.format(Locale.CHINA, "%+,.2f", value)
    }
}
