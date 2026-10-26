package com.example.stockmonitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.HeadlineSelector
import com.example.stockmonitor.analysis.NewsDetailFilter
import com.example.stockmonitor.analysis.NewsDetailPolicy
import com.example.stockmonitor.analysis.NewsDetailSort
import com.example.stockmonitor.analysis.ReportMarkup
import com.example.stockmonitor.analysis.SelectedHeadline
import com.example.stockmonitor.core.MarketNewsJson
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NewsDetailActivity : AppCompatActivity() {
    private var allItems: List<SelectedHeadline> = emptyList()
    private var updatedAtMs: Long = 0L
    private var filter: NewsDetailFilter = NewsDetailFilter.ALL
    private var sort: NewsDetailSort = NewsDetailSort.SCORE
    private lateinit var adapter: NewsDetailAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var summaryView: TextView
    private lateinit var sortButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news_detail)
        UiKit.applySystemBarInsets(findViewById(R.id.root), findViewById(R.id.news_detail_toolbar))
        filter = runCatching {
            NewsDetailFilter.valueOf(savedInstanceState?.getString(STATE_FILTER).orEmpty())
        }.getOrDefault(NewsDetailFilter.ALL)
        sort = runCatching {
            NewsDetailSort.valueOf(savedInstanceState?.getString(STATE_SORT).orEmpty())
        }.getOrDefault(NewsDetailSort.SCORE)
        val snapshot = MarketNewsJson.decode(intent.getStringExtra(EXTRA_PAYLOAD))
        allItems = snapshot?.items.orEmpty()
        updatedAtMs = snapshot?.updatedAtMs ?: 0L

        list = findViewById(R.id.news_detail_list)
        emptyView = findViewById(R.id.news_detail_empty)
        summaryView = findViewById(R.id.news_detail_summary)
        sortButton = findViewById(R.id.news_sort)
        adapter = NewsDetailAdapter(
            onOpen = ::openOriginal,
            onCopy = ::copyItem,
            onShare = ::shareItem,
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<MaterialToolbar>(R.id.news_detail_toolbar).apply {
            setTitle(R.string.news_detail_title)
            setNavigationOnClickListener { finish() }
        }
        findViewById<TextView>(R.id.news_detail_warning).apply {
            val warning = intent.getStringExtra(EXTRA_WARNING).orEmpty()
            text = warning
            visibility = if (warning.isBlank()) View.GONE else View.VISIBLE
        }
        val filterGroup = findViewById<ChipGroup>(R.id.news_filter_group)
        filterGroup.check(
            when (filter) {
                NewsDetailFilter.INVESTMENT -> R.id.news_filter_investment
                NewsDetailFilter.TECHNOLOGY -> R.id.news_filter_technology
                NewsDetailFilter.HOLDING_RELATED -> R.id.news_filter_holding
                NewsDetailFilter.ALL -> R.id.news_filter_all
            },
        )
        filterGroup.setOnCheckedChangeListener { _, checkedId ->
            filter = when (checkedId) {
                R.id.news_filter_investment -> NewsDetailFilter.INVESTMENT
                R.id.news_filter_technology -> NewsDetailFilter.TECHNOLOGY
                R.id.news_filter_holding -> NewsDetailFilter.HOLDING_RELATED
                else -> NewsDetailFilter.ALL
            }
            render()
        }
        sortButton.setOnClickListener {
            sort = if (sort == NewsDetailSort.SCORE) NewsDetailSort.LATEST else NewsDetailSort.SCORE
            render()
        }
        findViewById<View>(R.id.action_copy).setOnClickListener {
            copyText(getString(R.string.news_detail_title), pageText())
        }
        findViewById<View>(R.id.action_share).setOnClickListener {
            shareText(getString(R.string.news_detail_title), pageText())
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, filter.name)
        outState.putString(STATE_SORT, sort.name)
    }

    private fun render() {
        val items = NewsDetailPolicy.sort(NewsDetailPolicy.filter(allItems, filter), sort)
        adapter.submitList(items)
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val summary = NewsDetailPolicy.summary(allItems)
        summaryView.text = buildString {
            append("共 ${summary.total} 条｜投资 ${summary.investment}｜科技 ${summary.technology}｜持仓相关 ${summary.holdingRelated}")
            append("｜更新 ${formatTime(updatedAtMs)}｜当前 ${items.size} 条")
        }
        sortButton.setText(
            if (sort == NewsDetailSort.SCORE) R.string.news_sort_score else R.string.news_sort_latest,
        )
    }

    private fun pageText(): String {
        val report = HeadlineSelector.formatReport(allItems, updatedAtMs)
        val warning = intent.getStringExtra(EXTRA_WARNING).orEmpty()
        return ReportMarkup.plain(if (warning.isBlank()) report else "$report\n\n来源提示：$warning")
    }

    private fun openOriginal(item: SelectedHeadline) {
        val url = item.item.url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, R.string.news_link_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure { Toast.makeText(this, R.string.news_link_unavailable, Toast.LENGTH_SHORT).show() }
    }

    private fun copyItem(item: SelectedHeadline) {
        copyText(item.item.title, itemText(item))
    }

    private fun shareItem(item: SelectedHeadline) {
        shareText(item.item.title, itemText(item))
    }

    private fun itemText(item: SelectedHeadline): String = buildString {
        appendLine(item.item.title)
        appendLine("${item.category.label}｜${item.item.source}｜${formatTime(item.item.publishedAtMs)}")
        append("综合评分 ${item.score.total}")
        item.item.url?.let { append("\n$it") }
    }

    private fun copyText(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, title))
    }

    private fun formatTime(timestampMs: Long): String =
        if (timestampMs <= 0L) "—"
        else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(timestampMs)

    companion object {
        private const val EXTRA_PAYLOAD = "payload"
        private const val EXTRA_WARNING = "warning"
        private const val STATE_FILTER = "filter"
        private const val STATE_SORT = "sort"

        fun createIntent(
            context: Context,
            items: List<SelectedHeadline>,
            updatedAtMs: Long,
            warning: String? = null,
        ): Intent =
            Intent(context, NewsDetailActivity::class.java)
                .putExtra(EXTRA_PAYLOAD, MarketNewsJson.encode(items, updatedAtMs, updatedAtMs))
                .putExtra(EXTRA_WARNING, warning.orEmpty())

        fun open(
            context: Context,
            items: List<SelectedHeadline>,
            updatedAtMs: Long,
            warning: String? = null,
        ) {
            val intent = createIntent(context, items, updatedAtMs, warning)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

private class NewsDetailAdapter(
    private val onOpen: (SelectedHeadline) -> Unit,
    private val onCopy: (SelectedHeadline) -> Unit,
    private val onShare: (SelectedHeadline) -> Unit,
) : RecyclerView.Adapter<NewsDetailAdapter.Row>() {
    private val items = mutableListOf<SelectedHeadline>()

    fun submitList(value: List<SelectedHeadline>) {
        items.clear()
        items.addAll(value)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
        Row(LayoutInflater.from(parent.context).inflate(R.layout.item_news_detail, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Row, position: Int) {
        holder.bind(items[position], onOpen, onCopy, onShare)
    }

    class Row(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val badge: TextView = itemView.findViewById(R.id.news_badge)
        private val title: TextView = itemView.findViewById(R.id.news_title)
        private val meta: TextView = itemView.findViewById(R.id.news_meta)
        private val score: TextView = itemView.findViewById(R.id.news_score)
        private val support: TextView = itemView.findViewById(R.id.news_support)

        fun bind(
            item: SelectedHeadline,
            onOpen: (SelectedHeadline) -> Unit,
            onCopy: (SelectedHeadline) -> Unit,
            onShare: (SelectedHeadline) -> Unit,
        ) {
            badge.text = "${item.track.label}｜${item.category.label}"
            title.text = item.item.title
            meta.text = "${item.item.source}｜${formatTime(item.item.publishedAtMs)}"
            score.text = scoreText(item)
            support.text = "多源印证：${item.supportingSources.joinToString("、")}"
            support.visibility = if (item.supportingSources.size > 1) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onOpen(item) }
            itemView.findViewById<View>(R.id.news_open).setOnClickListener { onOpen(item) }
            itemView.findViewById<View>(R.id.news_copy).setOnClickListener { onCopy(item) }
            itemView.findViewById<View>(R.id.news_share).setOnClickListener { onShare(item) }
        }

        private fun scoreText(item: SelectedHeadline): String {
            val parts = mutableListOf("综合评分 ${item.score.total}")
            if (item.score.focus > 0) parts += "持仓 +${item.score.focus}"
            if (item.score.event > 0) parts += "事件 +${item.score.event}"
            if (item.score.trust > 0) parts += "来源 +${item.score.trust}"
            if (item.score.corroboration > 0) parts += "多源 +${item.score.corroboration}"
            if (item.score.freshness > 0) parts += "新鲜 +${item.score.freshness}"
            if (item.score.penalty > 0) parts += "降权 -${item.score.penalty}"
            return parts.joinToString("｜")
        }

        private fun formatTime(timestampMs: Long): String =
            if (timestampMs <= 0L) "—"
            else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
                .format(timestampMs)
    }
}
