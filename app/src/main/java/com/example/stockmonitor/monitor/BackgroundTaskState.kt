package com.example.stockmonitor.monitor

enum class TaskPhase {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILURE,
}

data class BackgroundTaskState(
    val title: String,
    val phase: TaskPhase = TaskPhase.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "尚未执行",
    val finishedAt: String? = null,
) {
    val isActive: Boolean
        get() = phase == TaskPhase.RUNNING

    val progressText: String
        get() = if (total > 0) "$done/$total" else ""

    val displayText: String
        get() = when (phase) {
            TaskPhase.IDLE -> message
            TaskPhase.RUNNING -> listOf(message, progressText.takeIf { it.isNotEmpty() })
                .filterNotNull()
                .joinToString(" · ")
            TaskPhase.SUCCESS -> listOf("已完成", message.takeIf { it.isNotBlank() }, finishedAt)
                .filterNotNull()
                .joinToString(" · ")
            TaskPhase.FAILURE -> listOf("失败", message.takeIf { it.isNotBlank() }, finishedAt)
                .filterNotNull()
                .joinToString(" · ")
        }

    companion object {
        fun idle(title: String): BackgroundTaskState = BackgroundTaskState(title)

        fun running(
            title: String,
            done: Int = 0,
            total: Int = 0,
            message: String = "后台执行中…",
        ): BackgroundTaskState = BackgroundTaskState(
            title = title,
            phase = TaskPhase.RUNNING,
            done = done,
            total = total,
            message = message,
        )

        fun success(title: String, finishedAt: String, message: String = ""): BackgroundTaskState =
            BackgroundTaskState(
                title = title,
                phase = TaskPhase.SUCCESS,
                message = message,
                finishedAt = finishedAt,
            )

        fun failure(title: String, message: String, finishedAt: String): BackgroundTaskState =
            BackgroundTaskState(
                title = title,
                phase = TaskPhase.FAILURE,
                message = message,
                finishedAt = finishedAt,
            )
    }
}
