package com.example.stockmonitor.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.app.NotificationManagerCompat
import com.example.stockmonitor.core.AlertRecord
import com.example.stockmonitor.core.AlertRecordBook
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 通知正文详情。使用 DialogFragment，旋转/进程重建后仍保留可见详情。 */
class AlertDetailDialogFragment : AppCompatDialogFragment() {
    val replayKey: String
        get() =
            if (requireArguments().getBoolean(ARG_MISSING)) {
                "missing:${requireArguments().getInt(ARG_NOTIFICATION_ID)}"
            } else {
                requireArguments().getString(ARG_ID).orEmpty()
            }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        val notificationId = arguments.getInt(ARG_NOTIFICATION_ID)
        val missing = arguments.getBoolean(ARG_MISSING)
        val builder = MaterialAlertDialogBuilder(requireActivity())
        if (missing) {
            builder
                .setTitle("提醒详情")
                .setMessage("这条提醒暂时无法读取，可能已被清理、尚未落盘或记录不完整。可打开提醒记录查看最近 30 天、最多 200 条提醒。")
        } else {
            val record =
                AlertRecord(
                    id = arguments.getString(ARG_ID).orEmpty(),
                    notificationId = notificationId,
                    kind = arguments.getString(ARG_KIND).orEmpty(),
                    level = arguments.getString(ARG_LEVEL).orEmpty(),
                    triggerType = arguments.getString(ARG_TRIGGER_TYPE).orEmpty(),
                    title = arguments.getString(ARG_TITLE).orEmpty(),
                    body = arguments.getString(ARG_BODY).orEmpty(),
                    createdAtMs = arguments.getLong(ARG_CREATED_AT_MS),
                )
            builder
                .setTitle(record.title)
                .setMessage(AlertRecordBook.detailText(record))
        }
        return builder
            .setPositiveButton("打开提醒记录") { _, _ ->
                startActivity(Intent(requireContext(), AlertHistoryActivity::class.java))
            }
            .setNegativeButton("关闭", null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        NotificationManagerCompat.from(requireContext())
            .cancel(requireArguments().getInt(ARG_NOTIFICATION_ID))
    }

    companion object {
        const val TAG = "alert_detail_dialog"

        fun newInstance(record: AlertRecord): AlertDetailDialogFragment =
            AlertDetailDialogFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_ID, record.id)
                        putInt(ARG_NOTIFICATION_ID, record.notificationId)
                        putString(ARG_KIND, record.kind)
                        putString(ARG_LEVEL, record.level)
                        putString(ARG_TRIGGER_TYPE, record.triggerType)
                        putString(ARG_TITLE, record.title)
                        putString(ARG_BODY, record.body)
                        putLong(ARG_CREATED_AT_MS, record.createdAtMs)
                    }
            }

        fun newMissing(notificationId: Int): AlertDetailDialogFragment =
            AlertDetailDialogFragment().apply {
                arguments =
                    Bundle().apply {
                        putBoolean(ARG_MISSING, true)
                        putInt(ARG_NOTIFICATION_ID, notificationId)
                    }
            }

        private const val ARG_ID = "id"
        private const val ARG_NOTIFICATION_ID = "notification_id"
        private const val ARG_KIND = "kind"
        private const val ARG_LEVEL = "level"
        private const val ARG_TRIGGER_TYPE = "trigger_type"
        private const val ARG_TITLE = "title"
        private const val ARG_BODY = "body"
        private const val ARG_CREATED_AT_MS = "created_at_ms"
        private const val ARG_MISSING = "missing"
    }
}
