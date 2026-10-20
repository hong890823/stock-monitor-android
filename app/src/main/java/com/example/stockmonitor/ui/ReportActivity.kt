package com.example.stockmonitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.ReportMarkup
import com.google.android.material.appbar.MaterialToolbar

/** 长报告查看页：等宽字体、可复制、可分享。 */
class ReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)
        UiKit.applySystemBarInsets(findViewById(R.id.root), findViewById(R.id.report_toolbar))
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.report_title)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        // 报告里用标记括起来的关键数据要显示成红色；复制/分享出去的是去掉标记的纯文本。
        val plain = ReportMarkup.plain(text)
        findViewById<MaterialToolbar>(R.id.report_toolbar).apply {
            this.title = title
            setNavigationOnClickListener { finish() }
        }
        findViewById<TextView>(R.id.report_text).text = styled(text)
        findViewById<android.view.View>(R.id.action_copy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(title, plain))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<android.view.View>(R.id.action_share).setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, plain)
            }
            startActivity(Intent.createChooser(share, title))
        }
    }

    /** 把报告文本按标记切成普通段与重点段，重点段染红。 */
    private fun styled(text: String): CharSequence {
        val highlight = ContextCompat.getColor(this, R.color.report_highlight)
        val builder = SpannableStringBuilder()
        ReportMarkup.parse(text).forEach { segment ->
            val start = builder.length
            builder.append(segment.text)
            if (segment.highlight) {
                builder.setSpan(
                    ForegroundColorSpan(highlight),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return builder
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        fun open(context: Context, title: String, text: String) {
            context.startActivity(
                Intent(context, ReportActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_TEXT, text)
            )
        }
    }
}
