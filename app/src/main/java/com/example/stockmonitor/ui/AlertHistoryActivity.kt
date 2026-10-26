package com.example.stockmonitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.stockmonitor.R
import com.example.stockmonitor.core.AlertRecord
import com.example.stockmonitor.core.AlertRecordBook
import com.example.stockmonitor.core.Store
import com.google.android.material.appbar.MaterialToolbar

/** 最近 30 天、最多 200 条的提醒正文记录。 */
class AlertHistoryActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var recordsList: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = AlertRecordAdapter { record ->
        ReportActivity.open(this, record.title, AlertRecordBook.detailText(record))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alert_history)
        UiKit.applySystemBarInsets(findViewById(R.id.root), findViewById(R.id.alert_history_toolbar))
        store = Store(this)
        recordsList = findViewById(R.id.alert_history_list)
        emptyView = findViewById(R.id.alert_history_empty)
        recordsList.layoutManager = LinearLayoutManager(this)
        recordsList.adapter = adapter
        findViewById<MaterialToolbar>(R.id.alert_history_toolbar).apply {
            setTitle(R.string.alert_history_title)
            setNavigationOnClickListener { finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        val records = store.alertRecords.sortedByDescending { it.createdAtMs }
        adapter.submitList(records)
        emptyView.visibility =
            if (records.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        recordsList.visibility =
            if (records.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }
}

private class AlertRecordAdapter(
    private val onRecordClick: (AlertRecord) -> Unit,
) : RecyclerView.Adapter<AlertRecordAdapter.Row>() {
    private val records = mutableListOf<AlertRecord>()

    fun submitList(value: List<AlertRecord>) {
        records.clear()
        records.addAll(value)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
        Row(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_alert_record, parent, false),
        )

    override fun getItemCount(): Int = records.size

    override fun onBindViewHolder(holder: Row, position: Int) {
        holder.bind(records[position], onRecordClick)
    }

    class Row(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.alert_record_title)
        private val meta: TextView = itemView.findViewById(R.id.alert_record_meta)
        private val body: TextView = itemView.findViewById(R.id.alert_record_body)

        fun bind(record: AlertRecord, onRecordClick: (AlertRecord) -> Unit) {
            title.text = record.title
            meta.text =
                "${AlertRecordBook.kindLabel(record.kind)} · ${AlertRecordBook.formatTime(record.createdAtMs)}"
            body.text = record.body
            itemView.setOnClickListener { onRecordClick(record) }
        }
    }
}
